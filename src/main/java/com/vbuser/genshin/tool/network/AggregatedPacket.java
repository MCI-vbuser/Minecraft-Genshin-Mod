package com.vbuser.genshin.tool.network;

import com.vbuser.genshin.tool.network.util.NetConfig;
import com.vbuser.genshin.tool.network.util.VarInt;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.ArrayList;
import java.util.List;

/**
 * One wire packet carrying a batch of {@link ModPacket}s.
 *
 * <pre>
 * byte magic, byte version, int registryHash, varint count,
 * { varint id, varint length, bytes payload }...
 * </pre>
 *
 * <p>The registry hash prevents a client/server with different packet sets
 * from silently decoding each other's ids as the wrong class.</p>
 */
public class AggregatedPacket implements IMessage {

    private List<ModPacket> packets = new ArrayList<>();

    public AggregatedPacket() {}

    public AggregatedPacket(List<ModPacket> packets) {
        this.packets = packets;
    }

    public List<ModPacket> getPackets() {
        return packets;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        int magic = buf.readUnsignedByte();
        if (magic != (NetConfig.PROTOCOL_MAGIC & 0xFF)) {
            throw new IllegalArgumentException("Bad aggregation magic: " + magic);
        }
        int version = buf.readUnsignedByte();
        if (version != (NetConfig.PROTOCOL_VERSION & 0xFF)) {
            throw new IllegalArgumentException("Unsupported aggregation version: " + version);
        }
        int registryHash = buf.readInt();
        if (registryHash != PacketRegistry.getProtocolHash()) {
            throw new IllegalArgumentException(
                    "Packet registry mismatch: remote=" + Integer.toHexString(registryHash)
                            + " local=" + Integer.toHexString(PacketRegistry.getProtocolHash()));
        }

        int count = VarInt.readVarInt(buf);
        if (count < 0 || count > NetConfig.AGGREGATE_MAX_PACKETS) {
            throw new IllegalArgumentException("Invalid aggregated packet count: " + count);
        }

        packets = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int id = VarInt.readVarInt(buf);
            int length = VarInt.readVarInt(buf);
            if (length < 0 || length > buf.readableBytes()) {
                throw new IllegalArgumentException(
                        "Invalid payload length " + length + " for packet id " + id);
            }
            ByteBuf payload = buf.readSlice(length);
            ModPacket packet = PacketRegistry.create(id, payload);
            packets.add(packet);
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeByte(NetConfig.PROTOCOL_MAGIC);
        buf.writeByte(NetConfig.PROTOCOL_VERSION);
        buf.writeInt(PacketRegistry.getProtocolHash());
        VarInt.writeVarInt(buf, packets.size());

        for (ModPacket packet : packets) {
            int id = PacketRegistry.getId(packet.getClass());
            ByteBuf payload = Unpooled.buffer();
            try {
                packet.encode(payload);
                VarInt.writeVarInt(buf, id);
                VarInt.writeVarInt(buf, payload.readableBytes());
                buf.writeBytes(payload);
            } finally {
                payload.release();
            }
        }
    }

    public void handle(MessageContext ctx) {
        for (ModPacket packet : packets) {
            packet.handle(ctx);
        }
    }
}
