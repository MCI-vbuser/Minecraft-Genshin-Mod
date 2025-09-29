package com.vbuser.movement.network.glider;

import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;

public class GliderNetwork {
    public static final SimpleNetworkWrapper CHANNEL = NetworkRegistry.INSTANCE.newSimpleChannel("movement_glider");
    public static void init() {
        CHANNEL.registerMessage(C2SToggleGlider.Handler.class, C2SToggleGlider.class, 700, Side.SERVER);
        CHANNEL.registerMessage(C2SGliderInput.Handler.class, C2SGliderInput.class, 701, Side.SERVER);
    }
}
