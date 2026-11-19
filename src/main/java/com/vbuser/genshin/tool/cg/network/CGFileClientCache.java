package com.vbuser.genshin.tool.cg.network;

import com.vbuser.genshin.tool.cg.CGFileManager;
import com.vbuser.genshin.tool.cg.CGProtocol;
import com.vbuser.genshin.tool.cg.decoder.VideoPlaybackManager;
import net.minecraft.client.Minecraft;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.BitSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Client side cache for cg files streamed by the server.
 *
 * <p>A {@link Transfer} is created when the request is sent, so a download that never
 * receives a first chunk fails through the same timeout path (and with the same single
 * failure callback) as a download that stalls later. Chunks must match the metadata of the
 * current transfer and are written to a {@code .part} file on an IO thread; the final file
 * is only published once every chunk arrived, the length matches and the content version
 * (length + SHA-256) was verified, and is moved into place atomically. A partial or
 * mismatching download is deleted and never becomes a cache hit.</p>
 */
public final class CGFileClientCache {

    private static final Logger LOGGER = LogManager.getLogger("CGFileClient");
    /** How long a download may go without a chunk before it is declared stalled. */
    private static final long STALL_TIMEOUT_MS = 20_000L;

    /** The transfer currently running per cg file. */
    private static final Map<String, Transfer> TRANSFERS = new ConcurrentHashMap<>();
    private static final Map<String, Integer> LAST_PERCENT = new ConcurrentHashMap<>();
    /** Latest request generation per file, retained after completion for callback filtering. */
    private static final Map<String, Long> LATEST_SESSION = new ConcurrentHashMap<>();
    private static final AtomicLong SESSION_COUNTER = new AtomicLong(System.nanoTime());

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

    // ------------------------------------------------------------- requests

    /**
     * Starts requesting a cg file that is trusted as-is (a local install).
     *
     * @param autoPlay whether playback should start when the download completes
     */
    public static void request(String fileName, boolean autoPlay) {
        request(fileName, CGProtocol.VERSION_LOCAL, autoPlay);
    }

    /** Starts a new request generation and supersedes every older callback. */
    public static void request(String fileName, String expectedVersion, boolean autoPlay) {
        if (!CGFileManager.isValidName(fileName)) {
            LOGGER.warn("Refusing to request an illegal cg file name: {}", fileName);
            return;
        }
        String version = expectedVersion == null ? CGProtocol.VERSION_NONE : expectedVersion;
        long sessionId = SESSION_COUNTER.incrementAndGet();
        LATEST_SESSION.put(fileName, sessionId);
        // A cache hit supersedes the old transfer too. Otherwise that transfer
        // could keep downloading invisibly and publish stale bytes afterwards.
        Transfer pending = TRANSFERS.get(fileName);
        if (pending != null && finish(pending)) {
            IO.execute(pending::discard);
        }
        if (isCached(fileName, version)) {
            onClientThread(() -> {
                if (isLatest(fileName, sessionId)) {
                    VideoPlaybackManager.getInstance().onDownloadFinished(fileName, true, autoPlay);
                }
            });
            return;
        }
        Transfer transfer = new Transfer(fileName, version, autoPlay, sessionId);
        Transfer previous = TRANSFERS.put(fileName, transfer);
        if (previous != null) {
            if (finish(previous)) {
                IO.execute(previous::discard);
            }
            LOGGER.debug("Replacing the pending cg transfer of {}", fileName);
        }
        LOGGER.info("Requesting cg {} (content version {}, session {})", fileName, version, sessionId);
        RequestCGFilePacket.send(fileName, version, sessionId);
    }

    private static boolean isLatest(String fileName, long sessionId) {
        Long latest = LATEST_SESSION.get(fileName);
        return latest != null && latest == sessionId;
    }

    private static boolean isLatest(Transfer transfer) {
        return isLatest(transfer.fileName, transfer.sessionId);
    }

    /** True when a local copy exists and matches the requested content version. */
    public static boolean isCached(String fileName, String version) {
        if (!CGFileManager.exists(fileName)) {
            return false;
        }
        if (CGProtocol.isLocalVersion(version)) {
            return true;
        }
        return version.equals(CGFileManager.fingerprint(fileName));
    }

    /** Download progress in {@code [0, 1]}. */
    public static float getProgress(String fileName) {
        if (fileName == null) {
            return 0f;
        }
        Transfer transfer = TRANSFERS.get(fileName);
        if (transfer != null) {
            return transfer.progress();
        }
        return CGFileManager.exists(fileName) ? 1f : 0f;
    }

    // ------------------------------------------------------------- receive

    /** Called from the chunk packet on the client thread. */
    public static void receiveChunk(String fileName, int chunkIndex, int totalChunks,
                                    boolean metadataPresent, int chunkSize, long totalBytes,
                                    String version, long sessionId, byte[] data) {
        if (!CGFileManager.isValidName(fileName) || data == null) {
            return;
        }
        Transfer transfer = TRANSFERS.get(fileName);
        if (transfer == null) {
            LOGGER.debug("Dropping a cg chunk for a transfer that is no longer active: {}", fileName);
            return;
        }
        if (sessionId != 0L && sessionId != transfer.sessionId) {
            // A late chunk from the superseded request must not write into the
            // new transfer's part file or refresh its watchdog.
            return;
        }
        // Validate the declared metadata before any byte is written: a truncated or
        // out of order stream must fail deterministically instead of producing a file.
        if (!metadataPresent || !CGProtocol.isValidLayout(totalChunks, chunkSize, totalBytes)) {
            abort(transfer, "the server used an unsupported cg transfer protocol", true);
            return;
        }
        if (chunkIndex < 0 || chunkIndex >= totalChunks) {
            abort(transfer, "the server sent an out of range cg chunk", true);
            return;
        }
        int expectedLength = CGProtocol.chunkLength(chunkIndex, chunkSize, totalBytes);
        if (data.length != expectedLength) {
            abort(transfer, "the server sent a cg chunk with an unexpected size", true);
            return;
        }
        if (!transfer.acceptMetadata(totalChunks, chunkSize, totalBytes, version)) {
            abort(transfer, "the cg transfer metadata changed while downloading", true);
            return;
        }
        if (!transfer.markReceived(chunkIndex)) {
            // Duplicate chunk; every index counts once.
            return;
        }
        IO.execute(() -> writeChunk(transfer, chunkIndex, data));
        fireLoading(transfer);
        if (transfer.markComplete()) {
            IO.execute(() -> complete(transfer));
        }
    }

    /** Called from the reject packet on the client thread. */
    public static void receiveReject(String fileName, String reason, long sessionId) {
        if (!CGFileManager.isValidName(fileName)) {
            return;
        }
        Transfer transfer = TRANSFERS.get(fileName);
        if (transfer != null && sessionId != 0L && sessionId != transfer.sessionId) {
            return;
        }
        String why = reason == null || reason.isEmpty()
                ? "the server rejected the cg request" : reason;
        if (transfer == null) {
            LOGGER.warn("The server rejected cg {} but no transfer is pending: {}", fileName, why);
            return;
        }
        abort(transfer, why, true);
    }

    // ------------------------------------------------------------- IO side

    private static void writeChunk(Transfer transfer, int index, byte[] data) {
        try {
            transfer.writeAt(index, data);
        } catch (IOException | RuntimeException e) {
            LOGGER.error("Failed to write a chunk of the downloaded cg {}", transfer.fileName, e);
            abort(transfer, "the downloaded cg file could not be written", true);
        }
    }

    private static void complete(Transfer transfer) {
        if (transfer.isFinished()) {
            return;
        }
        String fileName = transfer.fileName;
        String failure = null;
        try {
            transfer.closeOutput();
            File temp = transfer.getTempFile();
            long declaredBytes = transfer.getTotalBytes();
            String announcedVersion = transfer.getContentVersion();
            if (temp == null || !temp.isFile()) {
                throw new IOException("the partial cg file is missing");
            }
            if (temp.length() != declaredBytes) {
                throw new IOException("the downloaded cg file has " + temp.length()
                        + " bytes but " + declaredBytes + " were announced");
            }
            String actualVersion = CGFileManager.fingerprint(temp);
            if (actualVersion == null) {
                throw new IOException("the downloaded cg file could not be verified");
            }
            if (!CGProtocol.isLocalVersion(announcedVersion) && !announcedVersion.equals(actualVersion)) {
                throw new IOException("the downloaded cg file does not match the announced content");
            }
            File target = CGFileManager.getFile(fileName);
            File parent = target.getParentFile();
            if (parent != null && !parent.exists()) {
                //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            }
            if (!publishAndFinish(transfer, temp, target)) {
                // Publication and cancellation are serialized, so an aborted
                // generation cannot replace the new owner's cache.
                return;
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.error("Failed to finish the downloaded cg {}", fileName, e);
            failure = "the downloaded cg file could not be stored: " + e.getMessage();
        }
        if (failure != null) {
            abort(transfer, failure, true);
            return;
        }
        // publishAndFinish has already unregistered this exact generation.
        boolean autoPlay = transfer.autoPlay;
        long sessionId = transfer.sessionId;
        if (isLatest(transfer)) {
            onClientThread(() -> {
                if (isLatest(fileName, sessionId)) {
                    VideoPlaybackManager.getInstance().onDownloadFinished(fileName, true, autoPlay);
                }
            });
        }
    }

    /** Atomically orders the final move against cancellation and replacement. */
    private static boolean publishAndFinish(Transfer transfer, File temp, File target) throws IOException {
        synchronized (transfer) {
            if (transfer.finished || !isLatest(transfer)) {
                return false;
            }
            moveIntoPlace(temp, target);
            CGFileManager.invalidateFingerprint(target);
            transfer.finished = true;
        }
        TRANSFERS.remove(transfer.fileName, transfer);
        LAST_PERCENT.remove(transfer.fileName);
        return true;
    }

    private static void moveIntoPlace(File temp, File target) throws IOException {
        try {
            Files.move(temp.toPath(), target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException | java.nio.file.FileAlreadyExistsException e) {
            // Some file systems cannot replace atomically; fall back to a plain replace.
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void checkStalls() {
        long now = System.currentTimeMillis();
        for (Transfer transfer : TRANSFERS.values()) {
            if (transfer.isStalled(now)) {
                abort(transfer, "the server stopped sending the file", true);
            }
        }
    }

    /**
     * Fails one transfer exactly once: the part file is discarded, the state is removed and
     * the caller receives one failure callback. Used for the pre-first-chunk timeout, the
     * post-first-chunk stall, protocol violations and server rejections alike.
     */
    private static void abort(Transfer transfer, String reason, boolean notify) {
        if (!finish(transfer)) {
            return; // already finished or aborted; never report twice
        }
        IO.execute(transfer::discard);
        if (notify) {
            String name = transfer.fileName;
            long sessionId = transfer.sessionId;
            LOGGER.warn("CG download failed: {} ({})", name, reason);
            if (isLatest(transfer)) {
                onClientThread(() -> {
                    if (isLatest(name, sessionId)) {
                        VideoPlaybackManager.getInstance().onDownloadFailed(name, reason);
                    }
                });
            }
        }
    }

    /** Marks the transfer as finished and unregisters it. Returns false when it already was. */
    private static boolean finish(Transfer transfer) {
        synchronized (transfer) {
            if (transfer.finished) {
                return false;
            }
            transfer.finished = true;
        }
        TRANSFERS.remove(transfer.fileName, transfer);
        LAST_PERCENT.remove(transfer.fileName);
        return true;
    }

    private static void fireLoading(Transfer transfer) {
        String fileName = transfer.fileName;
        float progress = transfer.progress();
        int percent = (int) (progress * 100f);
        Integer previous = LAST_PERCENT.put(fileName, percent);
        if (previous != null && previous == percent) {
            return;
        }
        long sessionId = transfer.sessionId;
        onClientThread(() -> {
            if (isLatest(fileName, sessionId)) {
                VideoPlaybackManager.getInstance().onDownloadProgress(fileName, progress);
            }
        });
    }

    private static void onClientThread(Runnable runnable) {
        Minecraft.getMinecraft().addScheduledTask(runnable);
    }

    /**
     * One download attempt. All mutable state is guarded by {@code synchronized (this)};
     * the part file is only touched by the single IO thread.
     */
    private static final class Transfer {

        final String fileName;
        final String expectedVersion;
        final boolean autoPlay;
        final long sessionId;

        private long lastChunkAt = System.currentTimeMillis();
        private int totalChunks = -1;
        private int chunkSize = -1;
        private long totalBytes = -1L;
        private String contentVersion = CGProtocol.VERSION_NONE;
        private BitSet received;
        private int receivedCount;
        private boolean finished;
        private boolean verifying;

        // IO thread confined.
        private File tempFile;
        private RandomAccessFile output;

        Transfer(String fileName, String expectedVersion, boolean autoPlay, long sessionId) {
            this.fileName = fileName;
            this.expectedVersion = expectedVersion;
            this.autoPlay = autoPlay;
            this.sessionId = sessionId;
        }

        synchronized boolean isFinished() {
            return finished;
        }

        synchronized boolean acceptMetadata(int totalChunks, int chunkSize, long totalBytes,
                                            String version) {
            if (!CGProtocol.isValidLayout(totalChunks, chunkSize, totalBytes)) {
                return false;
            }
            if (!CGProtocol.isLocalVersion(expectedVersion) && !expectedVersion.equals(version)) {
                return false;
            }
            if (this.totalChunks < 0) {
                this.totalChunks = totalChunks;
                this.chunkSize = chunkSize;
                this.totalBytes = totalBytes;
                this.contentVersion = version == null ? CGProtocol.VERSION_NONE : version;
                this.received = new BitSet(totalChunks);
                return true;
            }
            // Metadata is fixed for the lifetime of a transfer.
            return this.totalChunks == totalChunks
                    && this.chunkSize == chunkSize
                    && this.totalBytes == totalBytes
                    && this.contentVersion.equals(version == null ? CGProtocol.VERSION_NONE : version);
        }

        synchronized boolean markReceived(int index) {
            if (finished || received == null || received.get(index)) {
                return false;
            }
            received.set(index);
            receivedCount++;
            lastChunkAt = System.currentTimeMillis();
            return true;
        }

        /** True exactly once, when the last outstanding chunk arrived. */
        synchronized boolean markComplete() {
            if (finished || verifying || totalChunks < 0 || receivedCount < totalChunks) {
                return false;
            }
            verifying = true;
            return true;
        }

        synchronized boolean isStalled(long now) {
            return !finished && !verifying && now - lastChunkAt > STALL_TIMEOUT_MS;
        }

        synchronized float progress() {
            if (totalChunks <= 0) {
                return 0f;
            }
            return Math.min(1f, (float) receivedCount / (float) totalChunks);
        }

        synchronized long getTotalBytes() {
            return totalBytes;
        }

        synchronized String getContentVersion() {
            return contentVersion;
        }

        synchronized File getTempFile() {
            return tempFile;
        }

        void writeAt(int index, byte[] data) throws IOException {
            int size;
            long bytes;
            synchronized (this) {
                if (finished) {
                    return;
                }
                size = chunkSize;
                bytes = totalBytes;
            }
            if (output == null) {
                openOutput(bytes);
            }
            output.seek((long) index * (long) size);
            output.write(data);
        }

        private void openOutput(long bytes) throws IOException {
            File target = CGFileManager.getFile(fileName);
            File parent = target.getParentFile();
            if (parent != null && !parent.exists()) {
                //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            }
            File part = new File(parent, target.getName() + "." + sessionId + CGProtocol.PART_SUFFIX);
            Files.deleteIfExists(part.toPath());
            RandomAccessFile file = new RandomAccessFile(part, "rw");
            try {
                file.setLength(bytes);
            } catch (IOException e) {
                file.close();
                Files.deleteIfExists(part.toPath());
                throw e;
            }
            tempFile = part;
            output = file;
        }

        void closeOutput() throws IOException {
            RandomAccessFile file = output;
            output = null;
            if (file != null) {
                file.close();
            }
        }

        /** Closes and deletes the part file; runs on the IO thread. */
        void discard() {
            try {
                closeOutput();
            } catch (IOException e) {
                LOGGER.warn("Could not close the partial cg file of {}", fileName, e);
            }
            File part = tempFile;
            tempFile = null;
            if (part != null && part.isFile() && !part.delete()) {
                LOGGER.warn("Could not delete the partial cg file {}", part.getAbsolutePath());
            }
        }
    }
}
