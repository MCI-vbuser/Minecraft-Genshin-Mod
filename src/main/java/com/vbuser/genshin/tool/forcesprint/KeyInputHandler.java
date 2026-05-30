package com.vbuser.genshin.tool.forcesprint;

import net.minecraft.client.Minecraft;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public class KeyInputHandler {

    private static boolean enabled = true;

    public static boolean isEnabled() {
        return enabled;
    }

    public static void toggle() {
        enabled = !enabled;
    }

    @SubscribeEvent
    public void onKeyInput(InputEvent.KeyInputEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.currentScreen == null && mc.gameSettings.keyBindSprint.isPressed()) {
            toggle();
            String status = isEnabled() ? "§aON" : "§cOFF";
            if (mc.player != null) {
                mc.player.sendMessage(new TextComponentString("§e[ForceSprint] " + status));
            }
        }
    }
}
