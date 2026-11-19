package com.vbuser.mixin;

import com.vbuser.genshin.tool.network.util.NetConfig;
import com.vbuser.genshin.tool.network.util.VanillaPendingPacket;
import com.vbuser.genshin.tool.network.util.VarInt;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import net.minecraft.network.EnumConnectionState;
import net.minecraft.network.EnumPacketDirection;
import net.minecraft.network.INetHandler;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.PacketBuffer;
import net.minecraftforge.fml.common.network.internal.FMLProxyPacket;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Aggregates vanilla play packets at the connection level and tunnels them
 * through a dedicated Forge channel as Zstd-compressed bundles.
 *
 * <p>Forge/custom payload packets (FMLProxyPacket) are deliberately left alone:
 * the app-level {@code improved_channel} aggregation already handles mod
 * packets, and mixing both paths would double-wrap them.</p>
 */
@Mixin(NetworkManager.class)
public abstract class MixinNetworkManager {

    @Unique
    private static final Logger INF_LOGGER = LogManager.getLogger("genshin-network");

    @Unique
    private static final ThreadLocal<Boolean> INF_BYPASS = ThreadLocal.withInitial(() -> Boolean.FALSE);

    @Shadow
    private Channel channel;

    @Final
    @Shadow
    private EnumPacketDirection direction;

    @Shadow
    private INetHandler packetListener;

    @Unique
    private final List<VanillaPendingPacket> inf$vanillaPending = new ArrayList<>();

    @Unique
    private int inf$vanillaBytes;

    @Inject(
            method = "sendPacket(Lnet/minecraft/network/Packet;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void inf$sendPacket(Packet<?> packet, CallbackInfo ci) {
        if (Boolean.TRUE.equals(INF_BYPASS.get())) {
            return;
        }
        if (!NetConfig.ENABLE_VANILLA_HIJACK || !inf$isChannelOpen() || this.channel == null) {
            return;
        }

        if (packet instanceof FMLProxyPacket) {
            String channelName = ((FMLProxyPacket) packet).channel();
            if (NetConfig.VANILLA_CHANNEL_NAME.equals(channelName)) {
                return;
            }
            // Keep ordering: queued vanilla packets go out before a mod packet.
            synchronized (this) {
                inf$flushVanilla();
            }
            return;
        }

        EnumConnectionState state = this.channel.attr(NetworkManager.PROTOCOL_ATTRIBUTE_KEY).get();
        if (state != EnumConnectionState.PLAY) {
            synchronized (this) {
                inf$flushVanilla();
            }
            return;
        }

        if (inf$isBlacklisted(packet)) {
            synchronized (this) {
                inf$flushVanilla();
            }
            return;
        }

        // Explicit low-latency types (keepalive, movement, teleport) are
        // passed through by inf$queueVanilla without being encoded; everything
        // else joins the per-connection batch and is flushed at the end of the
        // tick, which is what lets a burst of small packets aggregate.
        int queued;
        synchronized (this) {
            queued = inf$queueVanilla(packet);
            if (queued < 0) {
                inf$flushVanilla();
            }
        }
        if (queued > 0) {
            ci.cancel();
        }
    }

    @Inject(method = "processReceivedPackets", at = @At("TAIL"))
    private void inf$flushOnTick(CallbackInfo ci) {
        synchronized (this) {
            inf$flushVanilla();
        }
    }

    @Inject(method = "channelInactive", at = @At("HEAD"))
    private void inf$onChannelInactive(CallbackInfo ci) {
        synchronized (this) {
            this.inf$vanillaPending.clear();
            this.inf$vanillaBytes = 0;
        }
    }

    @Inject(method = "setConnectionState", at = @At("HEAD"))
    private void inf$beforeStateChange(EnumConnectionState state, CallbackInfo ci) {
        if (state != EnumConnectionState.PLAY) {
            synchronized (this) {
                inf$flushVanilla();
            }
        }
    }

    @Unique
    private boolean inf$isChannelOpen() {
        return this.channel != null && this.channel.isOpen();
    }

    @Unique
    private EnumPacketDirection inf$outboundDirection() {
        // NetworkManager.direction is the inbound direction; outbound is the
        // opposite one.
        return this.direction == EnumPacketDirection.CLIENTBOUND
                ? EnumPacketDirection.SERVERBOUND
                : EnumPacketDirection.CLIENTBOUND;
    }

    @Unique
    private int inf$queueVanilla(Packet<?> packet) {
        // Explicit low-latency types are recognised before serialization, so
        // they never pay for an encode that is then thrown away. If something
        // is already pending, report "flush first" so the queued batches still
        // leave before this packet on the vanilla path.
        if (NetConfig.isVanillaDirect(packet.getClass().getSimpleName())) {
            return this.inf$vanillaPending.isEmpty() ? 0 : -1;
        }
        try {
            int id = EnumConnectionState.PLAY.getPacketId(this.inf$outboundDirection(), packet);

            PacketBuffer data = new PacketBuffer(Unpooled.buffer());
            byte[] bytes;
            try {
                packet.writePacketData(data);
                int length = data.readableBytes();
                if (length < 0) {
                    return -1;
                }
                int entryBytes = NetConfig.vanillaEntryBytes(id, length);
                if (NetConfig.vanillaBundleBytes(1, entryBytes) > NetConfig.VANILLA_MAX_RAW_BYTES) {
                    // Cannot fit even alone: hand it to the ordered vanilla path.
                    return -1;
                }
                if (!this.inf$vanillaPending.isEmpty()
                        && NetConfig.vanillaBundleBytes(this.inf$vanillaPending.size() + 1,
                        this.inf$vanillaBytes + entryBytes) > NetConfig.VANILLA_MAX_RAW_BYTES) {
                    // Split before the peer's whole-batch limit is crossed.
                    inf$flushVanilla();
                }
                bytes = new byte[length];
                data.readBytes(bytes);
            } finally {
                data.release();
            }

            this.inf$vanillaPending.add(new VanillaPendingPacket(id, bytes, packet));
            this.inf$vanillaBytes += NetConfig.vanillaEntryBytes(id, bytes.length);

            if (this.inf$vanillaBytes >= NetConfig.VANILLA_MAX_BYTES
                    || this.inf$vanillaPending.size() >= NetConfig.VANILLA_MAX_PACKETS) {
                inf$flushVanilla();
            }
            return 1;
        } catch (Throwable t) {
            INF_LOGGER.error("Failed to serialize vanilla packet {}", packet.getClass().getName(), t);
            return -1;
        }
    }

    @Unique
    private void inf$flushVanilla() {
        if (this.inf$vanillaPending.isEmpty()) {
            return;
        }

        List<VanillaPendingPacket> toSend = new ArrayList<>(this.inf$vanillaPending);
        this.inf$vanillaPending.clear();
        this.inf$vanillaBytes = 0;

        if (toSend.size() == 1
                && toSend.get(0).data.length < NetConfig.VANILLA_DIRECT_SMALL_BYTES
                && toSend.get(0).original != null) {
            inf$sendDirect(toSend.get(0).original);
            return;
        }

        ByteBuf raw = Unpooled.buffer();
        try {
            raw.writeByte(NetConfig.VANILLA_MAGIC);
            raw.writeByte(NetConfig.VANILLA_VERSION);
            VarInt.writeVarInt(raw, toSend.size());
            for (VanillaPendingPacket pending : toSend) {
                VarInt.writeVarInt(raw, pending.id);
                VarInt.writeVarInt(raw, pending.data.length);
                raw.writeBytes(pending.data);
            }

            int rawBytes = raw.readableBytes();
            if (rawBytes > NetConfig.VANILLA_MAX_RAW_BYTES) {
                // The queue only ever closes a batch under this limit; if a
                // future change breaks that, do not hand the peer a packet it
                // is guaranteed to reject.
                INF_LOGGER.error("Refusing to send a {} byte vanilla bundle (limit {})",
                        rawBytes, NetConfig.VANILLA_MAX_RAW_BYTES);
                return;
            }
            byte[] bytes = new byte[rawBytes];
            raw.readBytes(bytes);
            // INF_LOGGER.debug("Vanilla bundle: {} packets, {} raw bytes", toSend.size(), bytes.length);
            com.vbuser.genshin.tool.network.NetworkManager.sendVanillaBundle(
                    bytes, this.inf$outboundDirection(), this.packetListener);
        } catch (Throwable t) {
            INF_LOGGER.error("Failed to flush {} vanilla packets", toSend.size(), t);
        } finally {
            raw.release();
        }
    }

    @Unique
    private void inf$sendDirect(Packet<?> packet) {
        if (packet == null) {
            return;
        }
        INF_BYPASS.set(Boolean.TRUE);
        try {
            ((NetworkManager) (Object) this).sendPacket(packet);
        } catch (Throwable t) {
            INF_LOGGER.error("Failed to send passthrough packet {}", packet.getClass().getName(), t);
        } finally {
            INF_BYPASS.set(Boolean.FALSE);
        }
    }

    @Unique
    private static boolean inf$isBlacklisted(Packet<?> packet) {
        String name = packet.getClass().getSimpleName();
        return "SPacketJoinGame".equals(name)
                || "SPacketDisconnect".equals(name)
                || "SPacketCustomPayload".equals(name)
                || "CPacketCustomPayload".equals(name);
    }
}
