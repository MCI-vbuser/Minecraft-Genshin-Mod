package com.vbuser.genshin.tool.network;

import com.vbuser.genshin.tool.network.util.NetConfig;
import com.vbuser.genshin.tool.network.util.VarInt;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.network.EnumConnectionState;
import net.minecraft.network.EnumPacketDirection;
import net.minecraft.network.INetHandler;
import net.minecraft.network.Packet;
import net.minecraft.network.PacketBuffer;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Decodes a vanilla bundle and replays the packets through the normal
 * INetHandler. Carrier packets are handled on the network thread, exactly like
 * vanilla NetworkManager.channelRead0, so ordering relative to non-hijacked
 * packets is preserved.
 */
public class VanillaBundleHandler implements IMessageHandler<VanillaBundlePacket, IMessage> {

    private static final Logger LOGGER = LogManager.getLogger("genshin-network");

    @Override
    public IMessage onMessage(VanillaBundlePacket message, MessageContext ctx) {
        byte[] raw = message.getRaw();
        if (raw == null || raw.length == 0) {
            return null;
        }

        INetHandler handler = ctx.side.isClient()
                ? ctx.getClientHandler()
                : ctx.getServerHandler();
        if (handler == null) {
            LOGGER.warn("Dropping vanilla bundle without a net handler");
            return null;
        }

        EnumPacketDirection direction = ctx.side.isClient()
                ? EnumPacketDirection.CLIENTBOUND
                : EnumPacketDirection.SERVERBOUND;

        try {
            process(raw, handler, direction);
        } catch (Throwable t) {
            LOGGER.error("Failed to process vanilla packet bundle", t);
        }
        return null;
    }

    private void process(byte[] raw, INetHandler handler, EnumPacketDirection direction) {
        ByteBuf buf = Unpooled.wrappedBuffer(raw);
        try {
            int magic = buf.readUnsignedByte();
            if (magic != (NetConfig.VANILLA_MAGIC & 0xFF)) {
                throw new IllegalArgumentException("Bad vanilla bundle magic: " + magic);
            }
            int version = buf.readUnsignedByte();
            if (version != (NetConfig.VANILLA_VERSION & 0xFF)) {
                throw new IllegalArgumentException("Unsupported vanilla bundle version: " + version);
            }

            int count = VarInt.readVarInt(buf);
            if (count < 0 || count > NetConfig.VANILLA_MAX_PACKETS) {
                throw new IllegalArgumentException("Invalid vanilla bundle packet count: " + count);
            }

            for (int i = 0; i < count; i++) {
                int id = VarInt.readVarInt(buf);
                int length = VarInt.readVarInt(buf);
                if (length < 0 || length > buf.readableBytes()) {
                    throw new IllegalArgumentException("Invalid vanilla packet length: " + length);
                }

                ByteBuf slice = buf.readSlice(length);
                Packet<?> packet;
                try {
                    packet = EnumConnectionState.PLAY.getPacket(direction, id);
                } catch (ReflectiveOperationException e) {
                    LOGGER.warn("Cannot instantiate vanilla packet id {} in bundle", id, e);
                    continue;
                }
                if (packet == null) {
                    LOGGER.warn("Unknown vanilla packet id {} in bundle", id);
                    continue;
                }

                try {
                    packet.readPacketData(new PacketBuffer(slice));
                    // noinspection unchecked,rawtypes
                    ((Packet) packet).processPacket(handler);
                } catch (net.minecraft.network.ThreadQuickExitException ignored) {
                    // PacketThreadUtil already scheduled this packet on the
                    // world thread; this exception is normal control flow.
                } catch (Throwable t) {
                    LOGGER.error("Failed to handle bundled vanilla packet id {}", id, t);
                }
            }
        } finally {
            buf.release();
        }
    }
}
