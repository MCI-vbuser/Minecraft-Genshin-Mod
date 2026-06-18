package com.vbuser.genshin.tool.al.event;

import com.vbuser.genshin.tool.al.BGMPlayer;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

public class BGMClientEventHandler {

    @SubscribeEvent
    @SideOnly(Side.CLIENT)
    public void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        BGMPlayer.getInstance().stopCurrent();
    }
}