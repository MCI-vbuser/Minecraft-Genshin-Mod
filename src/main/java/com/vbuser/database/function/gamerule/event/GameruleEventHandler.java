package com.vbuser.database.function.gamerule.event;

import com.vbuser.database.function.gamerule.util.ClientRuleCache;
import com.vbuser.database.function.gamerule.CustomGameRuleManager;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent.PlayerLoggedInEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.io.File;

public class GameruleEventHandler {

    @SubscribeEvent
    public void onPlayerLogin(PlayerLoggedInEvent event) {
        if (event.player instanceof EntityPlayerMP) {
            EntityPlayerMP player = (EntityPlayerMP) event.player;
            File worldDir = player.getServerWorld().getSaveHandler().getWorldDirectory();
            CustomGameRuleManager.init(worldDir);
            CustomGameRuleManager.getInstance().sendAllRulesToPlayer(player);
        }
    }

    @SideOnly(Side.CLIENT)
    @SubscribeEvent
    public void onClientDisconnect(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        ClientRuleCache.clear();
    }
}