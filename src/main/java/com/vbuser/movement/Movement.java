package com.vbuser.movement;

import com.vbuser.movement.capability.GliderUtils;
import com.vbuser.movement.command.FOVCommand;
import com.vbuser.movement.entity.EntityInit;
import com.vbuser.movement.entity.FakePlayer;
import com.vbuser.movement.entity.geckolib.FakePlayerRender;
import com.vbuser.movement.event.FakeInput;
import com.vbuser.movement.event.FOVHandler;
import com.vbuser.movement.event.Sprint;
import com.vbuser.movement.network.C2SInput;
import com.vbuser.movement.network.S2CInput;
import com.vbuser.movement.network.S2CSpawnFakePlayer;
import com.vbuser.movement.network.SprintPacket;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.registry.RenderingRegistry;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;

@Mod(modid = "movement")
public class Movement {

    @Mod.Instance
    public static Movement instance;

    public static SimpleNetworkWrapper network;

    @Mod.EventHandler
    public void onInit(FMLInitializationEvent event) {
        network = NetworkRegistry.INSTANCE.newSimpleChannel("MovementChannel");
        network.registerMessage(SprintPacket.Handle.class, SprintPacket.class, 400, Side.SERVER);
        network.registerMessage(C2SInput.Handler.class, C2SInput.class, 401, Side.SERVER);
        network.registerMessage(S2CInput.Handler.class, S2CInput.class,402,Side.CLIENT);
        network.registerMessage(S2CSpawnFakePlayer.Handler.class, S2CSpawnFakePlayer.class,403,Side.CLIENT);
        MinecraftForge.EVENT_BUS.register(new Sprint());
        MinecraftForge.EVENT_BUS.register(new FOVHandler());
        MinecraftForge.EVENT_BUS.register(new FakeInput());
    }

    @Mod.EventHandler
    public void onPreInit(FMLPreInitializationEvent event) {
        GliderUtils.initGlider(event);
        EntityInit.registerEntities();
        RenderingRegistry.registerEntityRenderingHandler(FakePlayer.class, FakePlayerRender::new);
    }

    @Mod.EventHandler
    public void serverInit(FMLServerStartingEvent event) {
        event.registerServerCommand(new FOVCommand());
    }

}
