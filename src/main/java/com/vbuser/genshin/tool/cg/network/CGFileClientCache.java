package com.vbuser.genshin.tool.cg.network;

import com.vbuser.genshin.tool.cg.decoder.VideoPlaybackManager;
import net.minecraft.client.Minecraft;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class CGFileClientCache {
    private static final Map<String, byte[][]> receivingFiles = new HashMap<>();
    private static final File CG_CLIENT_DIR = new File( "cg");

    static boolean ignored;

    public static void receiveChunk(String fileName, int chunkIndex, int totalChunks, byte[] data) {
        if (!CG_CLIENT_DIR.exists()) {
            ignored = CG_CLIENT_DIR.mkdirs();
        }

        receivingFiles.putIfAbsent(fileName, new byte[totalChunks][]);
        byte[][] chunks = receivingFiles.get(fileName);
        if (chunkIndex < 0 || chunkIndex >= totalChunks) return;
        chunks[chunkIndex] = data;

        for (byte[] chunk : chunks) {
            if (chunk == null) return;
        }

        File outputFile = new File(CG_CLIENT_DIR, fileName);
        try (FileOutputStream fos = new FileOutputStream(outputFile)) {
            for (byte[] chunk : chunks) {
                fos.write(chunk);
            }
            receivingFiles.remove(fileName);receivingFiles.remove(fileName);
            Minecraft.getMinecraft().addScheduledTask(() -> VideoPlaybackManager.getInstance().playVideo(fileName));
        } catch (IOException e) {
            // e.printStackTrace();
        }
    }
}