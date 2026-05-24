package com.vbuser.genshin.tool.network.util.sender;

import com.vbuser.genshin.tool.network.AggregatedPacket;
import com.vbuser.genshin.tool.network.ModPacket;

public interface NetworkSender {
    void sendImmediately(Object target, ModPacket packet);
    void send(Object target, AggregatedPacket aggregated);
}

