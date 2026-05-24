package com.vbuser.genshin.tool.thread;

import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.relauncher.Side;

@SuppressWarnings("unused")
public final class SideHelper {

    private SideHelper() {}

    public static Side getSide() {
        return FMLCommonHandler.instance().getEffectiveSide();
    }

    public static boolean isClient() {
        return getSide() == Side.CLIENT;
    }

    public static boolean isServer() {
        return getSide() == Side.SERVER;
    }
}
