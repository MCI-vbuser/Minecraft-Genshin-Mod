package com.vbuser.genshin.debug;

import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;

public class NetworkHandler {
    public static final SimpleNetworkWrapper INSTANCE =
            NetworkRegistry.INSTANCE.newSimpleChannel("ccb_channel");

    private static int packetId = 0;

    public static void registerMessages() {
        INSTANCE.registerMessage(
                ClientDebugMessage.Handler.class,
                ClientDebugMessage.class,
                packetId++,
                Side.CLIENT
        );
    }

    public static void sendToAllClients(ClientDebugMessage message) {
        INSTANCE.sendToAll(message);
    }
}