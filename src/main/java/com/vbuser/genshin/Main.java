package com.vbuser.genshin;

import com.vbuser.genshin.command.CommandCloudFill;
import com.vbuser.genshin.command.CommandSetBiome;
import com.vbuser.genshin.command.CommandTpDim;
import com.vbuser.genshin.data.save.CommandRule;
import com.vbuser.genshin.data.save.RuleManager;
import com.vbuser.genshin.debug.ClientDebugHandler;
import com.vbuser.genshin.debug.CommandCCB;
import com.vbuser.genshin.debug.NetworkHandler;
import com.vbuser.genshin.event.BiomeDisplayHandler;
import com.vbuser.genshin.init.ModItems;
import com.vbuser.genshin.init.key.KeyboardManager;
import com.vbuser.genshin.items.FoodBase;
import com.vbuser.genshin.proxy.CommonProxy;
import com.vbuser.genshin.tabs.TabBase;
import com.vbuser.ime.IMEController;
import net.minecraft.client.Minecraft;
import net.minecraft.world.biome.Biome;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.SidedProxy;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import java.util.ArrayList;
import java.util.List;

@SuppressWarnings("unused")
@Mod(modid = "genshin", name = "Minecraft Genshin Mod", version = "basic 1.14")
public class Main {

    /**
     * Creative Tabs:
     */
    public static final List<TabBase> TABS = new ArrayList<>();
    public static final TabBase SHENG_YI_WU = new TabBase("sheng_yi_wu", ModItems.ICON_A);
    public static final TabBase WU_QI = new TabBase("wu_qi", ModItems.ICON_B);
    public static final TabBase SHI_WU = new TabBase("shi_wu", ModItems.ICON_D);
    public static final TabBase YANG_CHENG_DAO_JU = new TabBase("yang_cheng_dao_ju", ModItems.ICON_C);
    public static final TabBase BAI_SHE = new TabBase("bai_she", ModItems.ICON_I);
    public static final TabBase GUI_ZHONG_DAO_JU = new TabBase("gui_zhong_dao_ju", ModItems.ICON_J);
    public static final TabBase RENG_WU = new TabBase("reng_wu", ModItems.ICON_G);
    public static final TabBase CAI_LIAO = new TabBase("cai_liao", ModItems.ICON_E);
    public static final TabBase XIAO_DAO_JU = new TabBase("xiao_dao_ju", ModItems.ICON_F);

    static {
        TABS.add(WU_QI);
        TABS.add(SHENG_YI_WU);
        TABS.add(YANG_CHENG_DAO_JU);
        TABS.add(SHI_WU);
        TABS.add(CAI_LIAO);
        TABS.add(XIAO_DAO_JU);
        TABS.add(RENG_WU);
        TABS.add(GUI_ZHONG_DAO_JU);
        TABS.add(BAI_SHE);
    }

    /**
     * Assignment:
     */
    @SidedProxy(clientSide = "com.vbuser.genshin.proxy.ClientProxy", serverSide = "com.vbuser.genshin.proxy.CommonProxy")

    public static CommonProxy proxy;

    @Mod.Instance
    public static Main instance;

    /**
     * Register Event:
     */
    @Mod.EventHandler
    public void onInit(FMLInitializationEvent event) {
        //JNI:
        System.out.println("[JNI] Loading Native Libraries.");
        IMEController.load();
        //Key Bindings:
        KeyboardManager.init();
        //Event Listener:
        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.register(new BiomeDisplayHandler());
        MinecraftForge.EVENT_BUS.register(new ClientDebugHandler());
        //Bullshit:
        ModItems.ITEMS.stream()
                .filter(item -> item instanceof FoodBase)
                .forEach(item -> item.setCreativeTab(SHI_WU));
    }

    @Mod.EventHandler
    public void onPreInit(FMLPreInitializationEvent event) {
        RuleManager.preInit(event);
        RuleManager.registerDefaultRule();
        NetworkHandler.registerMessages();
    }

    @Mod.EventHandler
    public static void serverInit(FMLServerStartingEvent event) {
        event.registerServerCommand(new CommandSetBiome());
        event.registerServerCommand(new CommandTpDim());
        event.registerServerCommand(new CommandCloudFill());
        event.registerServerCommand(new CommandRule());
        event.registerServerCommand(new CommandCCB());
    }

    @SubscribeEvent
    public static void onEvent(final RegistryEvent.Register<Biome> event) {
    }

    /**
     * IME Event:
     */
    boolean ime = false;

    @SubscribeEvent
    public void ime(TickEvent.ClientTickEvent event) {
        if (!ime && Minecraft.getMinecraft().player != null) {
            ime = true;
            IMEController.toggleIME(true);
        } else if (ime && Minecraft.getMinecraft().player == null) {
            ime = false;
            IMEController.toggleIME(false);
        }
    }
}
