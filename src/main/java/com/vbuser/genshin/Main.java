package com.vbuser.genshin;

import com.vbuser.genshin.tool.al.command.CommandBGM;
import com.vbuser.genshin.tool.forcesprint.KeyInputHandler;
import com.vbuser.genshin.tool.ime.IMEHandler;
import com.vbuser.genshin.tool.network.NetworkManager;
import com.vbuser.genshin.tool.network.example.ClientEventHandler;
import com.vbuser.genshin.tool.network.example.ServerEventHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;

@Mod(modid = "genshin", name = "Minecraft Genshin Mod", version = "basic 1.14")
public class Main {

    @Mod.Instance
    public static Main instance;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        instance = this;
        // Scan all the optimized network packets.
        NetworkManager.initialize(event,"com.vbuser");
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        // Register IME controller to disable input method.
        MinecraftForge.EVENT_BUS.register(new IMEHandler());
        // Register event hook for example network packet.
        if (event.getSide().isClient()) {
            MinecraftForge.EVENT_BUS.register(new ClientEventHandler());
        }
        MinecraftForge.EVENT_BUS.register(new ServerEventHandler());
        // Register force sprint.
        MinecraftForge.EVENT_BUS.register(new KeyInputHandler());
    }

    @Mod.EventHandler
    public void serverInit(FMLServerStartingEvent event) {
        event.registerServerCommand(new CommandBGM());
    }

}
