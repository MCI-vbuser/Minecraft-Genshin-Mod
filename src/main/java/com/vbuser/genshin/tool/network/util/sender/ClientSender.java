package com.vbuser.genshin.tool.network.util.sender;

import com.vbuser.genshin.tool.network.AggregatedPacket;
import com.vbuser.genshin.tool.network.ModPacket;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;

import java.util.Collections;

public class ClientSender implements NetworkSender {
    private final SimpleNetworkWrapper wrapper;

    public ClientSender(SimpleNetworkWrapper wrapper) {
        this.wrapper = wrapper;
    }

    @Override
    public void sendImmediately(Object target, ModPacket packet) {
        AggregatedPacket agg = new AggregatedPacket(Collections.singletonList(packet));
        send(target, agg);
    }

    @Override
    public void send(Object target, AggregatedPacket aggregated) {
        wrapper.sendToServer(aggregated);
    }
}
