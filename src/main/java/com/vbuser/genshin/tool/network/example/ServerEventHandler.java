package com.vbuser.genshin.tool.network.example;

import com.vbuser.genshin.tool.network.NetworkManager;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;

public class ServerEventHandler {
    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        HelloPacket packet = new HelloPacket("Hello from server, " + event.player.getName());
        NetworkManager.sendToPlayer(packet, (net.minecraft.entity.player.EntityPlayerMP) event.player);
    }
}
