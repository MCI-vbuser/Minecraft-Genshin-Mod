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

        // Small single packets are passed through by inf$queueVanilla; bursts
        // in either direction are aggregated.
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
        try {
            int id = EnumConnectionState.PLAY.getPacketId(this.inf$outboundDirection(), packet);

            PacketBuffer data = new PacketBuffer(Unpooled.buffer());
            byte[] bytes;
            try {
                packet.writePacketData(data);
                int length = data.readableBytes();
                if (length < 0 || length > NetConfig.VANILLA_MAX_RAW_BYTES) {
                    return -1;
                }
                bytes = new byte[length];
                data.readBytes(bytes);
            } finally {
                data.release();
            }

            // A lone small packet would pay more carrier overhead than it
            // saves. Let it go out immediately on the vanilla path.
            if (this.inf$vanillaPending.isEmpty()
                    && bytes.length < NetConfig.VANILLA_SMALL_PACKET_BYTES) {
                return 0;
            }

            this.inf$vanillaPending.add(new VanillaPendingPacket(id, bytes, packet));
            this.inf$vanillaBytes += VarInt.sizeOf(id) + VarInt.sizeOf(bytes.length) + bytes.length;

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
                && toSend.get(0).data.length < NetConfig.VANILLA_SMALL_PACKET_BYTES
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

            byte[] bytes = new byte[raw.readableBytes()];
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
