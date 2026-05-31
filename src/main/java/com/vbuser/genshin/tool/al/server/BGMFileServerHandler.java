package com.vbuser.genshin.tool.al.server;

import com.vbuser.genshin.tool.al.BGMFileManager;
import com.vbuser.genshin.tool.al.network.BGMFileChunkPacket;
import com.vbuser.genshin.tool.network.NetworkManager;
import net.minecraft.entity.player.EntityPlayerMP;

import java.io.File;
import java.io.FileInputStream;

public class BGMFileServerHandler {
    private static final int CHUNK_SIZE = 16384; // 16KB

    @SuppressWarnings("BusyWait")
    public static void handleRequest(EntityPlayerMP player, String fileName) {
        File file = BGMFileManager.getFile(fileName);
        if (!file.exists()) return;

        new Thread(() -> {
            try (FileInputStream fis = new FileInputStream(file)) {
                byte[] buffer = new byte[CHUNK_SIZE];
                int index = 0;
                long fileSize = file.length();
                int totalChunks = (int) Math.ceil((double) fileSize / CHUNK_SIZE);
                int bytesRead;
                while ((bytesRead = fis.read(buffer)) != -1) {
                    byte[] chunkData = new byte[bytesRead];
                    System.arraycopy(buffer, 0, chunkData, 0, bytesRead);
                    BGMFileChunkPacket packet = new BGMFileChunkPacket(fileName, index, totalChunks, chunkData);
                    NetworkManager.sendToPlayer(packet, player);
                    index++;
                    Thread.sleep(50);
                }
            } catch (Exception e) {
                // It just skipped but sth. should be written here
            }
        }).start();
    }
}