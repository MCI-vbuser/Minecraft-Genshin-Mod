package com.vbuser.genshin.tool.ime;

import com.vbuser.ime.IMEController;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

public class IMEHandler {

    private static boolean wasControllable = false;
    private static int tickCounter = 0;
    private static volatile boolean imeActive = false;

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (imeActive) {
                IMEController.toggleIME(false);
            }
        }));
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getMinecraft();
        boolean controllable = mc.player != null && mc.currentScreen == null;

        if (controllable && !wasControllable) {
            IMEController.toggleIME(true);
            imeActive = true;
            tickCounter = 0;
        } else if (!controllable && wasControllable) {
            IMEController.toggleIME(false);
            imeActive = false;
            tickCounter = 0;
        }

        wasControllable = controllable;

        if (controllable) {
            tickCounter++;
            if (tickCounter >= 20) {
                IMEController.toggleIME(true);
                imeActive = true;
                tickCounter = 0;
            }
        }
    }
}