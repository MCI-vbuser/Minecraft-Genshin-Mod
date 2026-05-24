package com.vbuser.genshin.tool.network.util.sender;

import com.vbuser.genshin.tool.network.AggregatedPacket;
import com.vbuser.genshin.tool.network.ModPacket;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;

import java.util.Collections;

public class ServerSender implements NetworkSender {
    private final SimpleNetworkWrapper wrapper;

    public ServerSender(SimpleNetworkWrapper wrapper) {
        this.wrapper = wrapper;
    }

    @Override
    public void sendImmediately(Object target, ModPacket packet) {
        AggregatedPacket agg = new AggregatedPacket(Collections.singletonList(packet));
        send(target, agg);
    }

    @Override
    public void send(Object target, AggregatedPacket aggregated) {
        if (target instanceof EntityPlayerMP) {
            wrapper.sendTo(aggregated, (EntityPlayerMP) target);
        } else {
            throw new IllegalArgumentException("Server sender target must be EntityPlayerMP");
        }
    }
}
