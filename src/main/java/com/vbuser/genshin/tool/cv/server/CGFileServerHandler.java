package com.vbuser.genshin.tool.cv.server;

import com.vbuser.genshin.tool.cv.network.CGFileChunkPacket;
import com.vbuser.genshin.tool.network.NetworkManager;
import net.minecraft.entity.player.EntityPlayerMP;

import java.io.File;
import java.io.FileInputStream;

public class CGFileServerHandler {
    private static final int CHUNK_SIZE = 16384;
    private static final File CG_DIR = new File("cg");

    @SuppressWarnings("BusyWait")
    public static void handleRequest(EntityPlayerMP player, String fileName) {
        File file = new File(CG_DIR, fileName);
        if (!file.exists()) {
            return;
        }

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
                    CGFileChunkPacket packet = new CGFileChunkPacket(fileName, index, totalChunks, chunkData);
                    NetworkManager.sendToPlayer(packet, player);
                    index++;
                    Thread.sleep(50);
                }
            } catch (Exception e) {
                // e.printStackTrace();
            }
        }).start();
    }
}