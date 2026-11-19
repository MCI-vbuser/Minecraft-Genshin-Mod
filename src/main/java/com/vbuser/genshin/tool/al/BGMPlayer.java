package com.vbuser.genshin.tool.al;

import com.vbuser.genshin.tool.al.network.BGMFileChunkPacket;
import com.vbuser.genshin.tool.al.network.RequestBGMPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.SoundHandler;
import net.minecraft.client.audio.SoundManager;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import paulscode.sound.SoundSystem;
import paulscode.sound.SoundSystemConfig;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Client side BGM engine (paulscode {@link SoundSystem} streaming sources).
 *
 * <p>Everything in here must run on the client thread; the public entry points
 * ({@link #play}, {@link #stopScheduled}, {@link #preload}) schedule themselves onto the
 * client thread, so {@link BGMAPI} can be called from any thread.</p>
 *
 * <p>Downloads are written chunk by chunk to a hidden temp file on an IO thread and only
 * published (atomic move) after every chunk of the current transfer arrived and the total
 * length was verified. The client thread never assembles the whole file in memory.</p>
 */
@SuppressWarnings("unused")
@SideOnly(Side.CLIENT)
public class BGMPlayer {

    private static final Logger LOGGER = LogManager.getLogger("BGMPlayer");
    /** How long a download may stay silent (no valid chunk) before it is considered dead. */
    private static final long DOWNLOAD_IDLE_TIMEOUT_MS = 15_000L;
    /** Absolute upper bound for one transfer, so a trickling download cannot hold state forever. */
    private static final long DOWNLOAD_MAX_TRANSFER_MS = 5L * 60_000L;
    /** Grace period before the end-of-track detector is allowed to fire. */
    private static final long END_GRACE_MS = 1_500L;

    /** Serializes temp file writes and the final atomic move; a single thread keeps order. */
    private static final ExecutorService DOWNLOAD_IO = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "BGMFileDownload");
        thread.setDaemon(true);
        return thread;
    });

    private static BGMPlayer instance;

    private SoundSystem sndSystem;
    private volatile boolean initialized;

    private String currentSource;
    private String currentName;
    private String pendingFileName;
    private boolean isDownloading;
    private float volume = 1.0f;
    private long playbackStartedAt;

    private final CopyOnWriteArrayList<BgmListener> listeners = new CopyOnWriteArrayList<>();
    private final Map<String, Transfer> downloads = new HashMap<>();
    private final Map<String, Integer> lastProgressPercent = new HashMap<>();
    private final Map<String, Consumer<Boolean>> preloadCallbacks = new HashMap<>();
    private final AtomicLong sessionCounter = new AtomicLong(System.nanoTime());

    public static synchronized BGMPlayer getInstance() {
        if (instance == null) {
            instance = new BGMPlayer();
            instance.init();
        }
        return instance;
    }

    private synchronized void init() {
        if (initialized) {
            return;
        }
        try {
            SoundHandler handler = Minecraft.getMinecraft().getSoundHandler();
            SoundManager soundManager = null;
            for (Field field : SoundHandler.class.getDeclaredFields()) {
                if (SoundManager.class.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    soundManager = (SoundManager) field.get(handler);
                    break;
                }
            }
            if (soundManager == null) {
                throw new RuntimeException("Failed to access SoundManager");
            }
            for (Field field : SoundManager.class.getDeclaredFields()) {
                if (SoundSystem.class.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    sndSystem = (SoundSystem) field.get(soundManager);
                    break;
                }
            }
            if (sndSystem == null) {
                throw new RuntimeException("Failed to access SoundSystem");
            }
            initialized = true;
        } catch (Exception e) {
            LOGGER.error("[BGMPlayer] Failed to initialize the paulscode SoundSystem. BGM playback is disabled.", e);
            initialized = false;
        }
    }

    // ------------------------------------------------------------- state

    public boolean isAvailable() {
        if (!initialized) {
            // The sound system may not exist yet when a mod queries this early; retry
            // instead of caching the failure for the whole session.
            init();
        }
        return initialized;
    }

    public boolean isPlaying() {
        String source = currentSource;
        if (!initialized || source == null) {
            return false;
        }
        try {
            return sndSystem.playing(source);
        } catch (Throwable t) {
            return false;
        }
    }

    public boolean isWaitingFor(String fileName) {
        return isDownloading && fileName != null && fileName.equals(pendingFileName);
    }

    public String getCurrent() {
        return currentName;
    }

    public float getVolume() {
        return volume;
    }

    public void setVolume(float newVolume) {
        volume = Math.max(0.0f, Math.min(1.0f, newVolume));
        if (initialized && currentSource != null) {
            sndSystem.setVolume(currentSource, volume);
        }
    }

    /** Download progress in {@code [0, 1]}; 1 when the file is already local. */
    public float getDownloadProgress(String fileName) {
        if (fileName == null) {
            return 0f;
        }
        if (BGMFileManager.exists(fileName)) {
            return 1f;
        }
        Transfer transfer = downloads.get(fileName);
        return transfer == null ? 0f : transfer.progress();
    }

    // ------------------------------------------------------------- listeners

    public void addListener(BgmListener listener) {
        if (listener != null) {
            listeners.addIfAbsent(listener);
        }
    }

    public void removeListener(BgmListener listener) {
        listeners.remove(listener);
    }

    // ------------------------------------------------------------- playback

    /** Schedules playback on the client thread. */
    public void play(String fileName, String expectedMD5) {
        Minecraft.getMinecraft().addScheduledTask(() -> playNow(fileName, expectedMD5));
    }

    private void playNow(String fileName, String expectedMD5) {
        if (!initialized) {
            init();
        }
        if (!initialized) {
            fireFail(fileName, "the audio system is not available");
            return;
        }
        if (!BGMFileManager.isValidName(fileName)) {
            fireFail(fileName, "illegal bgm file name");
            return;
        }
        stopCurrent();

        boolean needDownload = !BGMFileManager.exists(fileName);
        if (!needDownload && expectedMD5 != null && !expectedMD5.isEmpty()) {
            try {
                String localMD5 = BGMFileManager.getMD5(fileName);
                if (localMD5 == null || !localMD5.equalsIgnoreCase(expectedMD5)) {
                    needDownload = true;
                }
            } catch (Exception e) {
                needDownload = true;
            }
        }

        if (needDownload) {
            pendingFileName = fileName;
            isDownloading = true;
            requestDownload(fileName, expectedMD5);
        } else {
            startPlayback(fileName);
        }
    }

    /** Schedules a stop on the client thread. */
    public void stopScheduled() {
        Minecraft.getMinecraft().addScheduledTask(this::stopCurrent);
    }

    /** Must run on the client thread. */
    public void stopCurrent() {
        String source = currentSource;
        currentSource = null;
        currentName = null;
        String cancelled = pendingFileName;
        pendingFileName = null;
        isDownloading = false;
        if (cancelled != null) {
            Transfer transfer = downloads.get(cancelled);
            if (transfer != null) {
                cancelTransfer(transfer);
            }
        }
        if (source != null && initialized) {
            try {
                sndSystem.stop(source);
                sndSystem.removeSource(source);
            } catch (Throwable t) {
                LOGGER.warn("[BGMPlayer] Failed to release the audio source", t);
            }
        }
    }

    private void startPlayback(String fileName) {
        if (!initialized) {
            fireFail(fileName, "the audio system is not available");
            return;
        }
        File file = BGMFileManager.exists(fileName) ? BGMFileManager.getFile(fileName) : null;
        if (file == null) {
            fireFail(fileName, "file not found");
            return;
        }
        Minecraft.getMinecraft().addScheduledTask(() -> {
            try {
                String source = "bgm_" + System.nanoTime();
                sndSystem.newStreamingSource(false, source, file.toURI().toURL(), file.getName(),
                        true, 0, 0, 0, SoundSystemConfig.ATTENUATION_NONE, 0);
                sndSystem.setVolume(source, volume);
                sndSystem.play(source);
                currentSource = source;
                currentName = fileName;
                playbackStartedAt = System.currentTimeMillis();
                fireStart(fileName);
            } catch (Exception e) {
                LOGGER.error("[BGMPlayer] Error starting playback of {}", fileName, e);
                fireFail(fileName, "could not start playback: " + e.getMessage());
            }
        });
    }

    // ------------------------------------------------------------- download

    /** Schedules a pre-download; the callback receives {@code true} on success. */
    public void preload(String fileName, Consumer<Boolean> callback) {
        Minecraft.getMinecraft().addScheduledTask(() -> {
            if (!BGMFileManager.isValidName(fileName)) {
                finishPreload(fileName, callback, false, "illegal bgm file name");
                return;
            }
            if (BGMFileManager.exists(fileName)) {
                finishPreload(fileName, callback, true, null);
                return;
            }
            if (callback != null) {
                preloadCallbacks.put(fileName, callback);
            }
            if (!downloads.containsKey(fileName)) {
                requestDownload(fileName, null);
            }
        });
    }

    private void requestDownload(String fileName, String expectedMD5) {
        // A new request for the same file starts a new generation: chunks of the previous
        // one carry a different session id and are ignored when they still arrive.
        Transfer stale = downloads.remove(fileName);
        if (stale != null) {
            stale.cancel();
            submitDiscard(stale);
        }
        lastProgressPercent.remove(fileName);
        Transfer transfer = new Transfer(fileName, sessionCounter.incrementAndGet(), expectedMD5,
                System.currentTimeMillis());
        downloads.put(fileName, transfer);
        RequestBGMPacket.send(fileName, transfer.sessionId);
    }

    /**
     * Called from the chunk packet on the client thread.
     *
     * @param totalLength total size declared by the sender
     * @param sessionId   request generation the chunk belongs to; 0 for legacy senders
     * @param chunkSize   payload size of every chunk except the last one
     */
    public void receiveChunk(String fileName, int index, int total, long totalLength,
                             long sessionId, int chunkSize, byte[] data) {
        Transfer transfer = downloads.get(fileName);
        if (transfer == null) {
            // Late chunk of a transfer that already timed out, failed or completed.
            return;
        }
        if (sessionId != 0 && sessionId != transfer.sessionId) {
            // Late chunk from a previous request for the same file.
            return;
        }
        if (total == 0) {
            failTransfer(transfer, "the server could not send the file");
            return;
        }
        if (data == null || total < 0 || total > BGMFileChunkPacket.MAX_TOTAL_CHUNKS
                || chunkSize <= 0 || chunkSize > BGMFileChunkPacket.MAX_CHUNK_SIZE
                || totalLength <= 0 || totalLength > BGMFileChunkPacket.MAX_FILE_BYTES
                || index < 0 || index >= total) {
            failTransfer(transfer, "received an invalid file chunk");
            return;
        }
        if ((totalLength + chunkSize - 1) / chunkSize != total) {
            failTransfer(transfer, "received a chunk with an inconsistent layout");
            return;
        }
        if (transfer.totalChunks < 0) {
            // First chunk fixes the metadata for this transfer.
            transfer.totalChunks = total;
            transfer.totalLength = totalLength;
            transfer.chunkSize = chunkSize;
            transfer.received = new boolean[total];
        } else if (transfer.totalChunks != total || transfer.totalLength != totalLength
                || transfer.chunkSize != chunkSize) {
            failTransfer(transfer, "received a chunk with a different file layout");
            return;
        }

        long offset = (long) index * chunkSize;
        long expectedLength = index == total - 1 ? totalLength - offset : chunkSize;
        if (expectedLength <= 0 || data.length != expectedLength) {
            failTransfer(transfer, "received a chunk with an unexpected size");
            return;
        }

        transfer.idleDeadline = System.currentTimeMillis() + DOWNLOAD_IDLE_TIMEOUT_MS;
        if (transfer.received[index]) {
            return;
        }
        transfer.received[index] = true;
        transfer.receivedChunks++;
        transfer.receivedBytes += data.length;
        fireProgress(fileName, transfer.progress());

        submitChunkWrite(transfer, index, data);
        if (transfer.receivedChunks == transfer.totalChunks) {
            submitFinish(transfer);
        }
    }

    private void submitChunkWrite(Transfer transfer, int index, byte[] data) {
        try {
            DOWNLOAD_IO.execute(() -> {
                try {
                    RandomAccessFile output = transfer.open();
                    output.seek((long) index * transfer.chunkSize);
                    output.write(data);
                } catch (IOException e) {
                    if (!transfer.cancelled.get()) {
                        scheduleOnClient(() -> failTransfer(transfer,
                                "could not write the downloaded file: " + e.getMessage()));
                    }
                    // A cancelled transfer is already cleaned up; the queued discard removes the temp file.
                }
            });
        } catch (RejectedExecutionException e) {
            failTransfer(transfer, "the download writer is not available");
        }
    }

    private void submitFinish(Transfer transfer) {
        transfer.finishing = true;
        try {
            DOWNLOAD_IO.execute(() -> {
                try {
                    if (transfer.cancelled.get()) {
                        transfer.discard();
                        return;
                    }
                    transfer.closeQuietly();
                    File temp = transfer.tempFile;
                    if (temp == null || !temp.isFile()) {
                        throw new IOException("the temporary file is missing");
                    }
                    long size = temp.length();
                    if (size != transfer.totalLength) {
                        throw new IOException("expected " + transfer.totalLength + " bytes but received " + size);
                    }
                    if (transfer.expectedMD5 != null && !transfer.expectedMD5.isEmpty()) {
                        String actual = BGMFileManager.getMD5(temp);
                        if (actual == null || !actual.equalsIgnoreCase(transfer.expectedMD5)) {
                            throw new IOException("the checksum does not match the requested file");
                        }
                    }
                    transfer.publish(temp);
                    transfer.discard();
                    scheduleOnClient(() -> onTransferFinished(transfer));
                } catch (Exception e) {
                    transfer.discard();
                    scheduleOnClient(() -> failTransfer(transfer,
                            "could not save the downloaded file: " + e.getMessage()));
                }
            });
        } catch (RejectedExecutionException e) {
            failTransfer(transfer, "the download writer is not available");
        }
    }

    private static void scheduleOnClient(Runnable task) {
        Minecraft.getMinecraft().addScheduledTask(task);
    }

    private void submitDiscard(Transfer transfer) {
        try {
            DOWNLOAD_IO.execute(transfer::discard);
        } catch (RejectedExecutionException e) {
            transfer.discard();
        }
    }

    /** Removes the transfer state; returns false when it was already cleaned up. */
    private boolean cleanupDownload(String fileName, Transfer expected) {
        Transfer current = downloads.get(fileName);
        if (current == null || (expected != null && current != expected)) {
            return false;
        }
        downloads.remove(fileName);
        lastProgressPercent.remove(fileName);
        return true;
    }

    private void failTransfer(Transfer transfer, String reason) {
        if (!cleanupDownload(transfer.fileName, transfer)) {
            return; // already failed or completed: report exactly once
        }
        transfer.cancel();
        submitDiscard(transfer);
        LOGGER.warn("[BGMPlayer] Download of {} failed: {}", transfer.fileName, reason);
        fireFail(transfer.fileName, reason);
        if (transfer.fileName.equals(pendingFileName)) {
            isDownloading = false;
            pendingFileName = null;
        }
        finishPreload(transfer.fileName, preloadCallbacks.remove(transfer.fileName), false, null);
    }

    /** Stops an in-flight download without reporting a failure (explicit stop/supersede). */
    private void cancelTransfer(Transfer transfer) {
        if (!cleanupDownload(transfer.fileName, transfer)) {
            return;
        }
        transfer.cancel();
        submitDiscard(transfer);
        finishPreload(transfer.fileName, preloadCallbacks.remove(transfer.fileName), false, null);
    }

    private void onTransferFinished(Transfer transfer) {
        if (downloads.get(transfer.fileName) != transfer) {
            return; // cancelled or superseded while the file was being published
        }
        cleanupDownload(transfer.fileName, transfer);
        onFileReady(transfer.fileName);
    }

    private void onFileReady(String fileName) {
        fireReady(fileName);
        finishPreload(fileName, preloadCallbacks.remove(fileName), true, null);
        if (pendingFileName != null && pendingFileName.equals(fileName)) {
            isDownloading = false;
            pendingFileName = null;
            startPlayback(fileName);
        }
    }

    private void finishPreload(String fileName, Consumer<Boolean> callback, boolean success, String reason) {
        if (!success && reason != null) {
            fireFail(fileName, reason);
        }
        if (callback != null) {
            try {
                callback.accept(success);
            } catch (RuntimeException e) {
                LOGGER.error("[BGMPlayer] Preload callback threw", e);
            }
        }
    }

    // ------------------------------------------------------------- tick

    /** Called once per client tick: download timeouts and natural end-of-track detection. */
    public void tick() {
        if (!downloads.isEmpty()) {
            long now = System.currentTimeMillis();
            for (Transfer transfer : new ArrayList<>(downloads.values())) {
                if (transfer.finishing) {
                    continue;
                }
                if (now > transfer.hardDeadline) {
                    failTransfer(transfer, "the download took too long to complete");
                } else if (now > transfer.idleDeadline) {
                    failTransfer(transfer, "timed out while waiting for the next file chunk");
                }
            }
        }

        if (currentSource == null || !initialized) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - playbackStartedAt < END_GRACE_MS) {
            return;
        }
        boolean stillPlaying;
        try {
            stillPlaying = sndSystem.playing(currentSource);
        } catch (Throwable t) {
            stillPlaying = false;
        }
        if (!stillPlaying) {
            String finished = currentName;
            stopCurrent();
            fireEnd(finished);
        }
    }

    // ------------------------------------------------------------- transfer state

    /** One in-flight download. All mutable fields are only touched on the client thread. */
    private static final class Transfer {
        final String fileName;
        final long sessionId;
        final String expectedMD5;
        final long hardDeadline;
        long idleDeadline;

        int totalChunks = -1;
        long totalLength = -1L;
        int chunkSize = -1;
        boolean[] received;
        int receivedChunks;
        long receivedBytes;
        /** Client-thread flag: all chunks are now queued for final disk verification. */
        boolean finishing;

        private RandomAccessFile output;
        private File tempFile;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicBoolean discarded = new AtomicBoolean();

        Transfer(String fileName, long sessionId, String expectedMD5, long now) {
            this.fileName = fileName;
            this.sessionId = sessionId;
            this.expectedMD5 = expectedMD5;
            this.hardDeadline = now + DOWNLOAD_MAX_TRANSFER_MS;
            this.idleDeadline = now + DOWNLOAD_IDLE_TIMEOUT_MS;
        }

        float progress() {
            if (totalChunks <= 0) {
                return 0f;
            }
            if (totalLength > 0) {
                return Math.min(1f, (float) receivedBytes / (float) totalLength);
            }
            return Math.min(1f, (float) receivedChunks / (float) totalChunks);
        }

        /** Opens the temp file lazily on the IO thread. */
        RandomAccessFile open() throws IOException {
            if (cancelled.get()) {
                throw new IOException("the transfer was cancelled");
            }
            if (output == null) {
                tempFile = BGMFileManager.getTempFile(fileName, sessionId);
                output = new RandomAccessFile(tempFile, "rw");
            }
            return output;
        }

        void closeQuietly() {
            if (output != null) {
                try {
                    output.close();
                } catch (IOException e) {
                    LOGGER.warn("[BGMPlayer] Failed to close the temp file of {}", fileName, e);
                }
                output = null;
            }
        }

        void cancel() {
            synchronized (this) {
                cancelled.set(true);
            }
        }

        /** Serializes the final move with cancellation on the client thread. */
        synchronized void publish(File temp) throws IOException {
            if (cancelled.get()) {
                throw new IOException("the transfer was cancelled");
            }
            BGMFileManager.publish(fileName, temp);
        }

        /** Idempotent: closes the handle and deletes a partial temp file. */
        void discard() {
            if (!discarded.compareAndSet(false, true)) {
                return;
            }
            closeQuietly();
            File temp = tempFile;
            tempFile = null;
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp.toPath());
                } catch (IOException e) {
                    LOGGER.warn("[BGMPlayer] Failed to delete the partial download of {}", fileName, e);
                }
            }
        }
    }

    // ------------------------------------------------------------- event helpers

    private void fireStart(String fileName) {
        for (BgmListener listener : listeners) {
            try {
                listener.onStart(fileName);
            } catch (RuntimeException e) {
                LOGGER.warn("[BGMPlayer] Listener threw", e);
            }
        }
    }

    private void fireReady(String fileName) {
        if (fileName == null) {
            return;
        }
        for (BgmListener listener : listeners) {
            try {
                listener.onReady(fileName);
            } catch (RuntimeException e) {
                LOGGER.warn("[BGMPlayer] Listener threw", e);
            }
        }
    }

    private void fireProgress(String fileName, float progress) {
        int percent = (int) (progress * 100f);
        Integer previous = lastProgressPercent.put(fileName, percent);
        if (previous != null && previous == percent) {
            return;
        }
        for (BgmListener listener : listeners) {
            try {
                listener.onProgress(fileName, progress);
            } catch (RuntimeException e) {
                LOGGER.warn("[BGMPlayer] Listener threw", e);
            }
        }
    }

    private void fireFail(String fileName, String reason) {
        LOGGER.warn("[BGMPlayer] {} failed: {}", fileName, reason);
        for (BgmListener listener : listeners) {
            try {
                listener.onFail(fileName, reason);
            } catch (RuntimeException e) {
                LOGGER.warn("[BGMPlayer] Listener threw", e);
            }
        }
    }

    private void fireEnd(String fileName) {
        if (fileName == null) {
            return;
        }
        for (BgmListener listener : listeners) {
            try {
                listener.onEnd(fileName);
            } catch (RuntimeException e) {
                LOGGER.warn("[BGMPlayer] Listener threw", e);
            }
        }
    }
}
