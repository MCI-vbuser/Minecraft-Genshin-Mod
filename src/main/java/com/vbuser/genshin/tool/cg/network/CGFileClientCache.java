package com.vbuser.genshin.tool.cg.network;

import com.vbuser.genshin.tool.cg.CGFileManager;
import com.vbuser.genshin.tool.cg.decoder.VideoPlaybackManager;
import net.minecraft.client.Minecraft;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Client side cache for cg files streamed by the server.
 *
 * <p>Tracks per-file progress, remembers whether a finished download should start
 * playback or was only a prefetch, and fails stalled downloads instead of waiting
 * forever (the old implementation printed nothing and never told anyone that the server
 * did not answer).</p>
 */
public final class CGFileClientCache {

    private static final Logger LOGGER = LogManager.getLogger("CGFileClient");
    /** How long a download may go without a chunk before it is declared stalled. */
    private static final long STALL_TIMEOUT_MS = 20_000L;

    private static final Map<String, byte[][]> RECEIVING = new ConcurrentHashMap<>();
    private static final Map<String, AtomicInteger> RECEIVED = new ConcurrentHashMap<>();
    private static final Map<String, Integer> TOTALS = new ConcurrentHashMap<>();
    private static final Map<String, Long> LAST_CHUNK_AT = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> AUTO_PLAY = new ConcurrentHashMap<>();
    private static final Map<String, Integer> LAST_PERCENT = new ConcurrentHashMap<>();

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "CGFileSave");
        thread.setDaemon(true);
        return thread;
    });
    private static final ScheduledExecutorService WATCHDOG =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "CGFileWatchdog");
                thread.setDaemon(true);
                return thread;
            });

    static {
        WATCHDOG.scheduleWithFixedDelay(CGFileClientCache::checkStalls, 5, 5, TimeUnit.SECONDS);
    }

    private CGFileClientCache() {
    }

    /**
     * Starts requesting a cg file.
     *
     * @param autoPlay whether playback should start when the download completes
     */
    public static void request(String fileName, boolean autoPlay) {
        if (!CGFileManager.isValidName(fileName)) {
            LOGGER.warn("Refusing to request an illegal cg file name: {}", fileName);
            return;
        }
        if (CGFileManager.exists(fileName)) {
            onClientThread(() -> VideoPlaybackManager.getInstance().onDownloadFinished(fileName, true, autoPlay));
            return;
        }
        AUTO_PLAY.put(fileName, autoPlay);
        LAST_CHUNK_AT.put(fileName, System.currentTimeMillis());
        RECEIVED.putIfAbsent(fileName, new AtomicInteger());
        RequestCGFilePacket.send(fileName);
    }

    /** True while the given file is being received. */
    public static boolean isDownloading(String fileName) {
        return fileName != null && RECEIVING.containsKey(fileName);
    }

    /** Download progress in {@code [0, 1]}. */
    public static float getProgress(String fileName) {
        if (fileName == null) {
            return 0f;
        }
        if (CGFileManager.exists(fileName)) {
            return 1f;
        }
        Integer total = TOTALS.get(fileName);
        AtomicInteger received = RECEIVED.get(fileName);
        if (total == null || total <= 0 || received == null) {
            return 0f;
        }
        return Math.min(1f, (float) received.get() / (float) total);
    }

    /** Drops all state for a file, e.g. when the player stops the cg. */
    public static void cancel(String fileName) {
        if (fileName == null) {
            return;
        }
        RECEIVING.remove(fileName);
        RECEIVED.remove(fileName);
        TOTALS.remove(fileName);
        LAST_CHUNK_AT.remove(fileName);
        AUTO_PLAY.remove(fileName);
        LAST_PERCENT.remove(fileName);
    }

    /** Called from the chunk packet on the client thread. */
    public static void receiveChunk(String fileName, int chunkIndex, int totalChunks, byte[] data) {
        if (!CGFileManager.isValidName(fileName) || data == null) {
            return;
        }
        if (totalChunks <= 0 || chunkIndex < 0 || chunkIndex >= totalChunks) {
            LOGGER.warn("Dropping an out of range cg chunk {} ({}/{})", fileName, chunkIndex, totalChunks);
            return;
        }
        LAST_CHUNK_AT.put(fileName, System.currentTimeMillis());
        TOTALS.put(fileName, totalChunks);

        byte[][] chunks = RECEIVING.get(fileName);
        if (chunks == null || chunks.length != totalChunks) {
            chunks = new byte[totalChunks][];
            RECEIVING.put(fileName, chunks);
        }
        if (chunks[chunkIndex] == null) {
            chunks[chunkIndex] = data;
            RECEIVED.computeIfAbsent(fileName, key -> new AtomicInteger()).incrementAndGet();
        }

        fireLoading(fileName);
        AtomicInteger received = RECEIVED.get(fileName);
        if (received != null && received.get() >= totalChunks) {
            byte[][] complete = chunks;
            RECEIVING.remove(fileName);
            save(fileName, complete);
        }
    }

    private static void save(String fileName, byte[][] chunks) {
        IO.execute(() -> {
            boolean ok;
            try {
                File target = CGFileManager.getFile(fileName);
                File parent = target.getParentFile();
                if (parent != null && !parent.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    parent.mkdirs();
                }
                try (FileOutputStream output = new FileOutputStream(target)) {
                    for (byte[] chunk : chunks) {
                        if (chunk != null) {
                            output.write(chunk);
                        }
                    }
                }
                ok = true;
            } catch (IOException | IllegalArgumentException e) {
                LOGGER.error("Failed to save the downloaded cg {}", fileName, e);
                ok = false;
            }
            boolean autoPlay = Boolean.TRUE.equals(AUTO_PLAY.remove(fileName));
            RECEIVED.remove(fileName);
            TOTALS.remove(fileName);
            LAST_CHUNK_AT.remove(fileName);
            LAST_PERCENT.remove(fileName);
            final boolean saved = ok;
            onClientThread(() -> VideoPlaybackManager.getInstance().onDownloadFinished(fileName, saved, autoPlay));
        });
    }

    private static void checkStalls() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Long> entry : LAST_CHUNK_AT.entrySet()) {
            String fileName = entry.getKey();
            if (now - entry.getValue() <= STALL_TIMEOUT_MS) {
                continue;
            }
            if (RECEIVING.remove(fileName) == null) {
                // Not actually receiving anything; just forget the timestamp.
                LAST_CHUNK_AT.remove(fileName);
                continue;
            }
            RECEIVED.remove(fileName);
            TOTALS.remove(fileName);
            LAST_CHUNK_AT.remove(fileName);
            AUTO_PLAY.remove(fileName);
            LAST_PERCENT.remove(fileName);
            LOGGER.warn("CG download stalled: {}", fileName);
            onClientThread(() -> VideoPlaybackManager.getInstance().onDownloadFailed(fileName,
                    "the server stopped sending the file"));
        }
    }

    private static void fireLoading(String fileName) {
        float progress = getProgress(fileName);
        int percent = (int) (progress * 100f);
        Integer previous = LAST_PERCENT.put(fileName, percent);
        if (previous != null && previous == percent) {
            return;
        }
        onClientThread(() -> VideoPlaybackManager.getInstance().onDownloadProgress(fileName, progress));
    }

    private static void onClientThread(Runnable runnable) {
        Minecraft.getMinecraft().addScheduledTask(runnable);
    }
}
