package com.vbuser.genshin.tool.al.event;

import com.vbuser.genshin.tool.al.BGMPlayer;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * Drives the client side BGM state machine: download timeouts and natural end-of-track
 * detection, plus a hard stop on logout.
 */
public class BGMClientEventHandler {

    @SubscribeEvent
    @SideOnly(Side.CLIENT)
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        BGMPlayer.getInstance().tick();
    }

    @SubscribeEvent
    @SideOnly(Side.CLIENT)
    public void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        BGMPlayer.getInstance().stopCurrent();
    }
}
