package com.vbuser.genshin.tool.al;

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
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Client side BGM engine (paulscode {@link SoundSystem} streaming sources).
 *
 * <p>Everything in here must run on the client thread; the public entry points
 * ({@link #play}, {@link #stopScheduled}, {@link #preload}) schedule themselves onto the
 * client thread, so {@link BGMAPI} can be called from any thread.</p>
 */
@SuppressWarnings("unused")
@SideOnly(Side.CLIENT)
public class BGMPlayer {

    private static final Logger LOGGER = LogManager.getLogger("BGMPlayer");
    /** How long a client waits for the server to start delivering a file. */
    private static final long DOWNLOAD_TIMEOUT_MS = 15_000L;
    /** Grace period before the end-of-track detector is allowed to fire. */
    private static final long END_GRACE_MS = 1_500L;

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
    private final Map<String, Map<Integer, byte[]>> downloadBuffers = new HashMap<>();
    private final Map<String, Integer> downloadTotals = new HashMap<>();
    private final Map<String, Long> downloadDeadlines = new HashMap<>();
    private final Map<String, Integer> lastProgressPercent = new HashMap<>();
    private final Map<String, Consumer<Boolean>> preloadCallbacks = new HashMap<>();

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
        Integer total = downloadTotals.get(fileName);
        Map<Integer, byte[]> chunks = downloadBuffers.get(fileName);
        if (total == null || total <= 0) {
            return 0f;
        }
        int received = chunks == null ? 0 : chunks.size();
        return Math.min(1f, (float) received / (float) total);
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
            requestDownload(fileName);
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
        pendingFileName = null;
        isDownloading = false;
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
            if (!downloadBuffers.containsKey(fileName)) {
                requestDownload(fileName);
            }
        });
    }

    private void requestDownload(String fileName) {
        downloadDeadlines.put(fileName, System.currentTimeMillis() + DOWNLOAD_TIMEOUT_MS);
        downloadBuffers.computeIfAbsent(fileName, key -> new HashMap<>());
        RequestBGMPacket.send(fileName);
    }

    /** Called from the chunk packet on the client thread. */
    public void receiveChunk(String fileName, int index, int total, byte[] data) {
        if (!BGMFileManager.isValidName(fileName) || index < 0 || index >= total) {
            return;
        }
        if (data == null) {
            return;
        }
        Map<Integer, byte[]> chunks = downloadBuffers.computeIfAbsent(fileName, key -> new HashMap<>());
        chunks.put(index, data);
        downloadTotals.putIfAbsent(fileName, total);
        fireProgress(fileName, (float) chunks.size() / (float) total);

        if (chunks.size() < total) {
            return;
        }
        try {
            BGMFileManager.saveChunks(fileName, chunks, total);
        } catch (Exception e) {
            LOGGER.error("[BGMPlayer] Failed to save downloaded bgm {}", fileName, e);
            clearDownload(fileName);
            fireFail(fileName, "could not save the downloaded file: " + e.getMessage());
            finishPreload(fileName, preloadCallbacks.remove(fileName), false, null);
            return;
        }
        clearDownload(fileName);
        onFileReady(fileName);
    }

    private void clearDownload(String fileName) {
        downloadBuffers.remove(fileName);
        downloadTotals.remove(fileName);
        downloadDeadlines.remove(fileName);
        lastProgressPercent.remove(fileName);
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
        if (downloadDeadlines.isEmpty() && currentSource == null) {
            return;
        }
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Long>> iterator = downloadDeadlines.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Long> entry = iterator.next();
            if (now <= entry.getValue()) {
                continue;
            }
            String fileName = entry.getKey();
            iterator.remove();
            clearDownload(fileName);
            LOGGER.warn("[BGMPlayer] Download timed out: {}", fileName);
            if (fileName.equals(pendingFileName)) {
                isDownloading = false;
                pendingFileName = null;
            }
            fireFail(fileName, "timed out while waiting for the server to send the file");
            finishPreload(fileName, preloadCallbacks.remove(fileName), false, "download timed out");
        }

        if (currentSource == null || !initialized) {
            return;
        }
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
