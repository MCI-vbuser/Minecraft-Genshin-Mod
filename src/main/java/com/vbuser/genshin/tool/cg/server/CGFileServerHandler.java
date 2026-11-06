package com.vbuser.genshin.tool.cg.server;

import com.vbuser.genshin.tool.cg.CGFileManager;
import com.vbuser.genshin.tool.cg.network.CGFileChunkPacket;
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
 * Streams a cg file to one client.
 *
 * <p>Names are resolved by {@link CGFileManager#resolve(String)}, which rejects traversal
 * and absolute paths, and transfers run on a bounded pool so a client cannot exhaust
 * server threads.</p>
 */
public class CGFileServerHandler {

    private static final Logger LOGGER = LogManager.getLogger("CGFileServer");
    public static final int CHUNK_SIZE = 16 * 1024;
    private static final int MAX_ACTIVE_STREAMS = 4;

    private static final AtomicInteger ACTIVE = new AtomicInteger();
    private static final ExecutorService STREAM_POOL = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "CGFileStream");
        thread.setDaemon(true);
        return thread;
    });

    public static void handleRequest(EntityPlayerMP player, String fileName) {
        if (player == null) {
            return;
        }
        if (!CGFileManager.isValidName(fileName)) {
            LOGGER.warn("Rejected an illegal cg request from {}: {}", player.getName(), fileName);
            return;
        }
        File file;
        try {
            file = CGFileManager.getFile(fileName);
        } catch (IllegalArgumentException e) {
            LOGGER.warn("Rejected cg request outside the cg directory from {}: {}", player.getName(), fileName);
            return;
        }
        if (!file.isFile()) {
            LOGGER.warn("CG request for a missing file: {} (requested by {})",
                    file.getAbsolutePath(), player.getName());
            return;
        }
        if (ACTIVE.incrementAndGet() > MAX_ACTIVE_STREAMS) {
            ACTIVE.decrementAndGet();
            LOGGER.warn("Too many concurrent cg streams, rejecting {}", fileName);
            return;
        }
        try {
            STREAM_POOL.execute(() -> stream(player, file, fileName));
        } catch (RejectedExecutionException e) {
            ACTIVE.decrementAndGet();
            LOGGER.warn("CG stream pool is saturated, rejecting {}", fileName);
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
                NetworkManager.sendToPlayer(new CGFileChunkPacket(fileName, index, totalChunks, chunk), player);
                index++;
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to stream cg {} to {}", fileName, player.getName(), e);
        } finally {
            ACTIVE.decrementAndGet();
        }
    }
}
