package com.vbuser.genshin.tool.network;
import com.vbuser.genshin.tool.network.util.VarInt;
import io.netty.buffer.ByteBuf;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.ArrayList;
import java.util.List;

public class AggregatedPacket implements IMessage {
    private List<ModPacket> packets = new ArrayList<>();

    public AggregatedPacket() {}

    public AggregatedPacket(List<ModPacket> packets) {
        this.packets = packets;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        int count = VarInt.readVarInt(buf);
        packets = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int id = VarInt.readVarInt(buf);
            ModPacket packet = PacketRegistry.create(id, buf);
            packets.add(packet);
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        VarInt.writeVarInt(buf, packets.size());
        for (ModPacket p : packets) {
            int id = PacketRegistry.getId(p.getClass());
            VarInt.writeVarInt(buf, id);
            p.encode(buf);
        }
    }

    public void handle(MessageContext ctx) {
        for (ModPacket p : packets) {
            p.handle(ctx);
        }
    }
}
