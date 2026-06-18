package com.vbuser.genshin.tool.al;

import com.vbuser.genshin.tool.al.network.PlayBGMPacket;
import com.vbuser.genshin.tool.al.network.StopBGMPacket;
import com.vbuser.genshin.tool.network.NetworkManager;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.FMLCommonHandler;

import java.util.List;

@SuppressWarnings("unused")
public final class BGMAPI {

    private BGMAPI() {}

    public static void playBGM(String fileName) {
        if (FMLCommonHandler.instance().getEffectiveSide().isClient()) {
            BGMPlayer.getInstance().play(fileName, null);
        } else {
            playBGMToAll(fileName);
        }
    }

    public static void playBGM(EntityPlayerMP player, String fileName) {
        if (FMLCommonHandler.instance().getEffectiveSide().isServer()) {
            String md5 = null;
            try {
                md5 = BGMFileManager.getMD5(fileName);
            } catch (Exception ignored) {}
            NetworkManager.sendToPlayer(new PlayBGMPacket(fileName, md5), player);
        } else {
            throw new IllegalStateException("play BGM with player parameter can only be called on server side");
        }
    }

    public static void playBGMToAll(String fileName) {
        if (FMLCommonHandler.instance().getEffectiveSide().isServer()) {
            String md5 = null;
            try {
                md5 = BGMFileManager.getMD5(fileName);
            } catch (Exception ignored) {}
            PlayBGMPacket packet = new PlayBGMPacket(fileName, md5);
            List<EntityPlayerMP> players = FMLCommonHandler.instance()
                    .getMinecraftServerInstance().getPlayerList().getPlayers();
            for (EntityPlayerMP player : players) {
                NetworkManager.sendToPlayer(packet, player);
            }
        } else {
            throw new IllegalStateException("play BGM To All can only be called on server side");
        }
    }

    public static void stopBGM() {
        if (FMLCommonHandler.instance().getEffectiveSide().isClient()) {
            BGMPlayer.getInstance().stopCurrent();
        } else {
            stopBGMToAll();
        }
    }

    public static void stopBGM(EntityPlayerMP player) {
        if (FMLCommonHandler.instance().getEffectiveSide().isServer()) {
            NetworkManager.sendToPlayer(new StopBGMPacket(), player);
        } else {
            throw new IllegalStateException("stop BGM with player parameter can only be called on server side");
        }
    }

    public static void stopBGMToAll() {
        if (FMLCommonHandler.instance().getEffectiveSide().isServer()) {
            StopBGMPacket packet = new StopBGMPacket();
            List<EntityPlayerMP> players = FMLCommonHandler.instance()
                    .getMinecraftServerInstance().getPlayerList().getPlayers();
            for (EntityPlayerMP player : players) {
                NetworkManager.sendToPlayer(packet, player);
            }
        } else {
            throw new IllegalStateException("stop BGM To All can only be called on server side");
        }
    }
}