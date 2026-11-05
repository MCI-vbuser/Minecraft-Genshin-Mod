package com.vbuser.inf;

import net.minecraftforge.fml.common.Mod;
import com.vbuser.inf.network.SPacketExtendedSection;
import com.vbuser.inf.network.SPacketExtendedSectionHandler;
import com.vbuser.inf.network.SPacketExtendedTileEntity;
import com.vbuser.inf.network.SPacketExtendedTileEntityHandler;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;

@Mod(
        modid = InfiniteHeight.MODID,
        name = InfiniteHeight.NAME,
        version = InfiniteHeight.VERSION,
        acceptedMinecraftVersions = "[1.12,1.13)"
)
public class InfiniteHeight {
    public static final String MODID = "inf";
    public static final String NAME = "InfiniteHeight";
    public static final String VERSION = "0.1.0";

    public static final SimpleNetworkWrapper NETWORK =
            NetworkRegistry.INSTANCE.newSimpleChannel(MODID);

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        NETWORK.registerMessage(
                SPacketExtendedTileEntityHandler.class,
                SPacketExtendedTileEntity.class,
                0, Side.CLIENT);
        NETWORK.registerMessage(
                SPacketExtendedSectionHandler.class,
                SPacketExtendedSection.class,
                1, Side.CLIENT);
    }
}