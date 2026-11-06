package com.vbuser.genshin.tool.al.server;

import com.vbuser.genshin.tool.al.BGMFileManager;
import com.vbuser.genshin.tool.al.network.BGMFileChunkPacket;
import com.vbuser.genshin.tool.network.NetworkManager;
import net.minecraft.entity.player.EntityPlayerMP;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.FileInputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Streams a bgm file to one client.
 *
 * <p>Requests are validated by {@link BGMFileManager#resolve(String)} (so a client can
 * never read outside the bgm directory) and served by a bounded pool, so a client cannot
 * spawn an unbounded number of threads by spamming requests.</p>
 */
public class BGMFileServerHandler {

    private static final Logger LOGGER = LogManager.getLogger("BGMFileServer");
    private static final int CHUNK_SIZE = 16 * 1024;
    /** Small delay between chunks so downloads do not starve gameplay traffic. */
    private static final long CHUNK_DELAY_MS = 50L;
    private static final int MAX_ACTIVE_STREAMS = 4;

    private static final AtomicInteger ACTIVE = new AtomicInteger();
    private static final ExecutorService STREAM_POOL = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "BGMFileStream");
        thread.setDaemon(true);
        return thread;
    });

    public static void handleRequest(EntityPlayerMP player, String fileName) {
        if (player == null || !BGMFileManager.isValidName(fileName)) {
            LOGGER.warn("Rejected an illegal bgm request: {}", fileName);
            return;
        }
        File file;
        try {
            file = BGMFileManager.getFile(fileName);
        } catch (IllegalArgumentException e) {
            LOGGER.warn("Rejected bgm request outside the bgm directory: {}", fileName);
            return;
        }
        if (!file.isFile()) {
            LOGGER.warn("BGM request for a missing file: {}", file.getAbsolutePath());
            return;
        }
        if (ACTIVE.incrementAndGet() > MAX_ACTIVE_STREAMS) {
            ACTIVE.decrementAndGet();
            LOGGER.warn("Too many concurrent bgm streams, rejecting {}", fileName);
            return;
        }
        try {
            STREAM_POOL.execute(() -> stream(player, file, fileName));
        } catch (RejectedExecutionException e) {
            ACTIVE.decrementAndGet();
            LOGGER.warn("BGM stream pool is saturated, rejecting {}", fileName);
        }
    }

    private static void stream(EntityPlayerMP player, File file, String fileName) {
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[CHUNK_SIZE];
            int index = 0;
            int totalChunks = (int) Math.ceil((double) file.length() / CHUNK_SIZE);
            int bytesRead;
            while ((bytesRead = input.read(buffer)) != -1) {
                byte[] chunk = new byte[bytesRead];
                System.arraycopy(buffer, 0, chunk, 0, bytesRead);
                NetworkManager.sendToPlayer(new BGMFileChunkPacket(fileName, index, totalChunks, chunk), player);
                index++;
                if (!sleep()) {
                    break;
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to stream bgm {} to {}", fileName, player.getName(), e);
        } finally {
            ACTIVE.decrementAndGet();
        }
    }

    private static boolean sleep() {
        try {
            Thread.sleep(CHUNK_DELAY_MS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
