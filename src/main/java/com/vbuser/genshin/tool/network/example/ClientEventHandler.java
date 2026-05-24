package com.vbuser.genshin.tool.network.example;

import com.vbuser.genshin.tool.network.NetworkManager;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;

public class ClientEventHandler {
    @SubscribeEvent
    public void onClientConnected(FMLNetworkEvent.ClientConnectedToServerEvent event) {
        HelloPacket packet = new HelloPacket("Hello from client, I'm " +
                net.minecraft.client.Minecraft.getMinecraft().getSession().getUsername());
        NetworkManager.sendToServer(packet);
    }
}
