package com.vbuser.genshin;

import com.vbuser.genshin.client.renderer.CloudVolumeRenderer;
import com.vbuser.genshin.command.CommandCloud;
import com.vbuser.genshin.command.CommandCloudFill;
import com.vbuser.genshin.debug.CameraPos;
import com.vbuser.genshin.proxy.CommonProxy;
import com.vbuser.genshin.tool.al.event.BGMClientEventHandler;
import com.vbuser.genshin.tool.al.command.CommandBGM;
import com.vbuser.genshin.tool.cg.PlayerEventHandler;
import com.vbuser.genshin.tool.cg.command.CommandCG;
import com.vbuser.genshin.tool.forcesprint.KeyInputHandler;
import com.vbuser.genshin.tool.ime.IMEHandler;
import com.vbuser.genshin.tool.mcgit.UpdateCommand;
import com.vbuser.genshin.tool.network.NetworkManager;
import com.vbuser.genshin.tool.network.example.ClientEventHandler;
import com.vbuser.genshin.tool.network.example.ServerEventHandler;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.SidedProxy;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppingEvent;
import org.spongepowered.asm.launch.MixinBootstrap;

@Mod(modid = "genshin", name = "Minecraft Genshin Mod", version = "basic 1.14")
public class Main {

    @SidedProxy(clientSide = "com.vbuser.genshin.proxy.ClientProxy", serverSide = "com.vbuser.genshin.proxy.CommonProxy")

    public static CommonProxy proxy;

    @Mod.Instance
    public static Main instance;

    /** Registered for {@code /cloudfill} and for its server tick handler. */
    private static final CommandCloudFill CLOUD_FILL = new CommandCloudFill();

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        instance = this;
        // Scan all the optimized network packets.
        NetworkManager.initialize(event,"com.vbuser");
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {

        MixinBootstrap.init();

        // Register IME controller to disable input method.
        MinecraftForge.EVENT_BUS.register(new IMEHandler());
        // Register event hook for example network packet.
        if (event.getSide().isClient()) {
            MinecraftForge.EVENT_BUS.register(new ClientEventHandler());
            // Client only: these touch client classes (video decoder, sound system).
            MinecraftForge.EVENT_BUS.register(new PlayerEventHandler());
            MinecraftForge.EVENT_BUS.register(new BGMClientEventHandler());
            // JavaFX is started lazily on the first cg playback (FXVideoPlayer.start):
            // booting the toolkit here crashes the whole game on machines where the
            // JavaFX native stack cannot initialise (for example Linux without GTK2).
        }
        MinecraftForge.EVENT_BUS.register(new ServerEventHandler());
        // Register force sprint.
        MinecraftForge.EVENT_BUS.register(new KeyInputHandler());
        // Register Camera Position Tracker.
        MinecraftForge.EVENT_BUS.register(new CameraPos());

        if (event.getSide().isClient()) {
            // Volumetric clouds need a GL context, so client only.
            MinecraftForge.EVENT_BUS.register(new CloudVolumeRenderer());
            ClientCommandHandler.instance.registerCommand(new CommandCloud());
        }
        // /cloudfill applies its blocks over several ticks, so the command object
        // is also a tick listener and must be registered exactly once (the server
        // command list itself is rebuilt for every world).
        MinecraftForge.EVENT_BUS.register(CLOUD_FILL);
    }

    @Mod.EventHandler
    public void serverInit(FMLServerStartingEvent event) {
        event.registerServerCommand(new CommandBGM());
        event.registerServerCommand(new CommandCG());
        event.registerServerCommand(new UpdateCommand());
        event.registerServerCommand(CLOUD_FILL);
    }

    @Mod.EventHandler
    public void serverStopping(FMLServerStoppingEvent event) {
        NetworkManager.flushAll();
    }

}
