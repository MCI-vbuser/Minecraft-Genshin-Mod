package com.vbuser.genshin.tool.cv;

import com.vbuser.genshin.tool.cv.decoder.VideoPlaybackManager;
import com.vbuser.genshin.tool.cv.network.PlayCGPacket;
import com.vbuser.genshin.tool.cv.network.StopCGPacket;
import com.vbuser.genshin.tool.network.NetworkManager;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.FMLCommonHandler;

import java.util.List;

@SuppressWarnings("unused")
public final class CGAPI {

    private CGAPI() {}

    public static void playCG(String fileName) {
        if (FMLCommonHandler.instance().getEffectiveSide().isClient()) {
            VideoPlaybackManager.getInstance().playVideo(fileName);
        } else {
            playCGToAll(fileName);
        }
    }

    public static void playCG(EntityPlayerMP player, String fileName) {
        if (FMLCommonHandler.instance().getEffectiveSide().isClient()) {
            throw new IllegalStateException("playCG with player parameter can only be called on server side");
        }
        NetworkManager.sendToPlayer(new PlayCGPacket(fileName), player);
    }

    public static void playCGToAll(String fileName) {
        if (FMLCommonHandler.instance().getEffectiveSide().isClient()) {
            throw new IllegalStateException("playCGToAll can only be called on server side");
        }
        List<EntityPlayerMP> players = FMLCommonHandler.instance().getMinecraftServerInstance().getPlayerList().getPlayers();
        PlayCGPacket packet = new PlayCGPacket(fileName);
        for (EntityPlayerMP player : players) {
            NetworkManager.sendToPlayer(packet, player);
        }
    }

    public static void stopCG() {
        if (FMLCommonHandler.instance().getEffectiveSide().isClient()) {
            VideoPlaybackManager.getInstance().stopVideo();
        } else {
            stopCGToAll();
        }
    }

    public static void stopCG(EntityPlayerMP player) {
        if (FMLCommonHandler.instance().getEffectiveSide().isClient()) {
            throw new IllegalStateException("stopCG with player parameter can only be called on server side");
        }
        NetworkManager.sendToPlayer(new StopCGPacket(), player);
    }

    public static void stopCGToAll() {
        if (FMLCommonHandler.instance().getEffectiveSide().isClient()) {
            throw new IllegalStateException("stopCGToAll can only be called on server side");
        }
        List<EntityPlayerMP> players = FMLCommonHandler.instance().getMinecraftServerInstance().getPlayerList().getPlayers();
        StopCGPacket packet = new StopCGPacket();
        for (EntityPlayerMP player : players) {
            NetworkManager.sendToPlayer(packet, player);
        }
    }
}