package com.vbuser.genshin.tool.al.server;

import com.vbuser.genshin.tool.al.BGMFileManager;
import com.vbuser.genshin.tool.al.network.BGMFileChunkPacket;
import com.vbuser.genshin.tool.network.NetworkManager;
import net.minecraft.entity.player.EntityPlayerMP;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
 *
 * <p>Every refusal that happens after the file name was validated is reported to the
 * client as a zero-chunk {@link BGMFileChunkPacket}, so the client can fail the download
 * immediately instead of waiting for its idle timeout.</p>
 */
public class BGMFileServerHandler {

    private static final Logger LOGGER = LogManager.getLogger("BGMFileServer");
    private static final int CHUNK_SIZE = BGMFileChunkPacket.DEFAULT_CHUNK_SIZE;
    /** Small delay between chunks so downloads do not starve gameplay traffic. */
    private static final long CHUNK_DELAY_MS = 50L;
    private static final int MAX_ACTIVE_STREAMS = 4;
    /** Concurrent streams one player may have; the client may preload a couple of files. */
    private static final int MAX_STREAMS_PER_PLAYER = 2;

    private static final AtomicInteger ACTIVE = new AtomicInteger();
    private static final Map<UUID, AtomicInteger> PLAYER_STREAMS = new ConcurrentHashMap<>();
    private static final ExecutorService STREAM_POOL = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "BGMFileStream");
        thread.setDaemon(true);
        return thread;
    });

    public static void handleRequest(EntityPlayerMP player, String fileName) {
        handleRequest(player, fileName, 0L);
    }

    /**
     * @param sessionId id of the client transfer, echoed in every chunk so late chunks of
     *                  an older request can be discarded; 0 when the sender has none
     */
    public static void handleRequest(EntityPlayerMP player, String fileName, long sessionId) {
        if (player == null) {
            return;
        }
        if (!BGMFileManager.isValidName(fileName)) {
            LOGGER.warn("Rejected an illegal bgm request from {}: {}", player.getName(), fileName);
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
            reject(player, fileName, sessionId, "file not found on the server");
            return;
        }
        long totalLength = file.length();
        if (totalLength <= 0L) {
            reject(player, fileName, sessionId, "the file is empty");
            return;
        }
        if (totalLength > BGMFileChunkPacket.MAX_FILE_BYTES) {
            reject(player, fileName, sessionId, "the file is too large");
            return;
        }
        int totalChunks = (int) ((totalLength + CHUNK_SIZE - 1) / CHUNK_SIZE);
        if (totalChunks <= 0 || totalChunks > BGMFileChunkPacket.MAX_TOTAL_CHUNKS) {
            reject(player, fileName, sessionId, "the file is too large");
            return;
        }

        UUID playerId = player.getUniqueID();
        // Acquisition must use the same per-key lock as release. computeIfAbsent
        // followed by incrementAndGet can otherwise retain a counter that a
        // concurrent release has just removed, allowing an untracked stream.
        AtomicInteger playerStreams = PLAYER_STREAMS.compute(playerId, (id, count) -> {
            AtomicInteger next = count == null ? new AtomicInteger() : count;
            next.incrementAndGet();
            return next;
        });
        if (playerStreams.get() > MAX_STREAMS_PER_PLAYER) {
            releasePlayer(playerId);
            reject(player, fileName, sessionId, "the player already has too many active downloads");
            return;
        }
        if (ACTIVE.incrementAndGet() > MAX_ACTIVE_STREAMS) {
            ACTIVE.decrementAndGet();
            releasePlayer(playerId);
            reject(player, fileName, sessionId, "too many concurrent downloads on the server");
            return;
        }
        try {
            STREAM_POOL.execute(() -> stream(player, file, fileName, sessionId, totalChunks,
                    totalLength, playerId));
        } catch (RejectedExecutionException e) {
            ACTIVE.decrementAndGet();
            releasePlayer(playerId);
            reject(player, fileName, sessionId, "the server download pool is saturated");
        }
    }

    private static void stream(EntityPlayerMP player, File file, String fileName, long sessionId,
                               int totalChunks, long totalLength, UUID playerId) {
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[CHUNK_SIZE];
            for (int index = 0; index < totalChunks; index++) {
                int wanted = (int) Math.min(CHUNK_SIZE, totalLength - (long) index * CHUNK_SIZE);
                int bytesRead = readFully(input, buffer, wanted);
                if (bytesRead != wanted) {
                    throw new IOException("the file was truncated while streaming (chunk " + index + ")");
                }
                byte[] chunk = new byte[bytesRead];
                System.arraycopy(buffer, 0, chunk, 0, bytesRead);
                NetworkManager.sendToPlayer(new BGMFileChunkPacket(fileName, index, totalChunks,
                        totalLength, sessionId, CHUNK_SIZE, chunk), player);
                if (index + 1 < totalChunks && !sleep()) {
                    throw new IOException("bgm streaming was interrupted");
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to stream bgm {} to {}", fileName, player.getName(), e);
            reject(player, fileName, sessionId, "the server could not finish sending this bgm file");
        } finally {
            ACTIVE.decrementAndGet();
            releasePlayer(playerId);
        }
    }

    private static int readFully(FileInputStream input, byte[] buffer, int wanted) throws IOException {
        int offset = 0;
        while (offset < wanted) {
            int read = input.read(buffer, offset, wanted - offset);
            if (read < 0) {
                break;
            }
            offset += read;
        }
        return offset;
    }

    private static void releasePlayer(UUID playerId) {
        // Decrement and removal must be one per-key operation. Otherwise a new
        // request can increment the old counter between those two steps and
        // then have its live entry removed, bypassing the per-player limit.
        PLAYER_STREAMS.computeIfPresent(playerId, (id, count) ->
                count.decrementAndGet() <= 0 ? null : count);
    }

    /** Tells the client this request will not be served, so it fails once and fast. */
    private static void reject(EntityPlayerMP player, String fileName, long sessionId, String reason) {
        LOGGER.warn("Rejecting bgm request {} from {}: {}", fileName, player.getName(), reason);
        try {
            NetworkManager.sendToPlayer(BGMFileChunkPacket.failure(fileName, sessionId), player);
        } catch (RuntimeException e) {
            // The player may already be disconnecting; the client timeout is the fallback.
            LOGGER.warn("Could not send the bgm rejection for {} to {}", fileName, player.getName(), e);
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
