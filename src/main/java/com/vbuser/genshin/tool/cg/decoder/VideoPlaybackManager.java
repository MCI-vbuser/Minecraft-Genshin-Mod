package com.vbuser.genshin.tool.cg.decoder;

import com.vbuser.genshin.tool.cg.CGFileManager;
import com.vbuser.genshin.tool.cg.CGProtocol;
import com.vbuser.genshin.tool.cg.CgListener;
import com.vbuser.genshin.tool.cg.GuiVideoOverlay;
import com.vbuser.genshin.tool.cg.network.CGFileClientCache;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Client side cg state machine: download, playback, overlay and listeners.
 *
 * <p>Every public method may be called from any thread; work is moved to the client thread
 * when needed. The current video is exposed both as the built-in full screen overlay and as
 * a raw GL texture ({@link #getTextureId()}) so a mod can draw it inside its own GUI by
 * calling {@link #setAutoOverlay(boolean) setAutoOverlay(false)}.</p>
 *
 * <p>Decoders are opened on a background thread. Each playback attempt gets a session id;
 * "started", "ended" and "failed" are only delivered while that session is still the active
 * one, so opening a Windows decoder can never block the client thread and a stale decoder
 * can neither fail nor end the video that replaced it.</p>
 */
@SideOnly(Side.CLIENT)
public class VideoPlaybackManager {

    private static final Logger LOGGER = LogManager.getLogger("VideoPlayback");
    private static final VideoPlaybackManager INSTANCE = new VideoPlaybackManager();
    /** How long a decoder may take to become ready before the attempt fails. */
    private static final long DECODER_OPEN_TIMEOUT_MS = 15_000L;

    private static final ExecutorService DECODER_OPEN = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "CGDecoderOpen");
        thread.setDaemon(true);
        return thread;
    });

    private IVideoFrameProvider currentPlayer;
    private VideoTexture currentTexture;
    private boolean textureReady;
    private GuiVideoOverlay overlayGui;
    private String currentName;
    private boolean autoOverlay = true;

    /** Incremented for every playback attempt; stale callbacks compare against it. */
    private long sessionCounter;
    private volatile long activeSession;

    private final CopyOnWriteArrayList<CgListener> listeners = new CopyOnWriteArrayList<>();

    public static VideoPlaybackManager getInstance() {
        return INSTANCE;
    }

    // ------------------------------------------------------------- configuration

    /** When false the built-in full screen overlay is not opened; draw the texture yourself. */
    public void setAutoOverlay(boolean value) {
        autoOverlay = value;
    }

    public boolean isAutoOverlay() {
        return autoOverlay;
    }

    public void addListener(CgListener listener) {
        if (listener != null) {
            listeners.addIfAbsent(listener);
        }
    }

    public void removeListener(CgListener listener) {
        listeners.remove(listener);
    }

    // ------------------------------------------------------------- local playback

    /** Plays a cg for the local player, downloading it first when necessary. */
    public void playLocal(String fileName) {
        playLocal(fileName, CGProtocol.VERSION_LOCAL);
    }

    /**
     * Plays a cg for the local player.
     *
     * @param version content version announced by the server; an existing local file is
     *                only reused when it matches. {@link CGProtocol#VERSION_LOCAL} keeps
     *                hand-installed resources playable.
     */
    public void playLocal(String fileName, String version) {
        runOnClient(() -> {
            try {
                stopVideoNow();
                if (!CGFileManager.isValidName(fileName)) {
                    fireFail(fileName, "illegal cg file name");
                    return;
                }
                currentName = fileName;
                if (CGFileClientCache.isCached(fileName, version)) {
                    startLocalPlayback(fileName);
                } else {
                    fireLoading(fileName, 0f);
                    CGFileClientCache.request(fileName, version, true);
                }
            } catch (Throwable t) {
                LOGGER.error("[VideoPlayback] playLocal({}) failed", fileName, t);
                stopVideoNow();
                fireFail(fileName, "could not start playback: " + t);
            }
        });
    }

    /** Legacy name kept for compatibility. */
    public void playVideo(String fileName) {
        playLocal(fileName);
    }

    /** Plays a cg announced by the server together with its content version. */
    public void playVideo(String fileName, String version) {
        playLocal(fileName, version);
    }

    /** Downloads a cg without playing it. */
    public void prefetch(String fileName) {
        runOnClient(() -> {
            if (!CGFileManager.isValidName(fileName)) {
                fireFail(fileName, "illegal cg file name");
                return;
            }
            if (CGFileClientCache.isCached(fileName, CGProtocol.VERSION_LOCAL)) {
                fireReady(fileName);
                return;
            }
            fireLoading(fileName, 0f);
            CGFileClientCache.request(fileName, CGProtocol.VERSION_LOCAL, false);
        });
    }

    private void startLocalPlayback(String fileName) {
        final long session = beginSession();
        File file;
        try {
            file = CGFileManager.getFile(fileName);
        } catch (IllegalArgumentException e) {
            failSession(fileName, "illegal cg file name");
            return;
        }
        if (!file.isFile()) {
            failSession(fileName, "file not found: " + file.getAbsolutePath());
            return;
        }
        try {
            if (autoOverlay && overlayGui == null) {
                GuiScreen previous = Minecraft.getMinecraft().currentScreen;
                overlayGui = new GuiVideoOverlay(previous);
                Minecraft.getMinecraft().displayGuiScreen(overlayGui);
            }
        } catch (Throwable t) {
            LOGGER.error("[VideoPlayback] Could not open the cg overlay", t);
            stopVideoNow();
            fireFail(fileName, "could not open the cg overlay: " + t);
            return;
        }
        final String absolutePath = file.getAbsolutePath();
        try {
            DECODER_OPEN.execute(() -> openDecoder(session, fileName, absolutePath));
        } catch (Throwable t) {
            LOGGER.error("[VideoPlayback] Could not schedule the decoder for {}", fileName, t);
            stopVideoNow();
            fireFail(fileName, "could not start the video decoder: " + t);
        }
    }

    // ------------------------------------------------------------- decoder opening

    private boolean environmentLogged;

    private void logEnvironmentOnce() {
        if (environmentLogged) {
            return;
        }
        environmentLogged = true;
        LOGGER.info("[VideoPlayback] env os={} java={} mfSupported={} javafxInitialized={}",
                System.getProperty("os.name"), System.getProperty("java.version"),
                MFVideoPlayer.isSupported(), FXVideoPlayer.isInitialized());
    }

    /**
     * Opens the decoder off the client thread.
     *
     * <p>On Windows Media Foundation is preferred because JavaFX's Windows media backend
     * (GStreamer + DirectShow) fails with {@code ERROR_MEDIA_INVALID} on some Windows/driver
     * combinations. Everywhere else, or when MF cannot open the file, JavaFX is used.</p>
     */
    private void openDecoder(long session, String fileName, String absolutePath) {
        logEnvironmentOnce();
        IVideoFrameProvider player = null;
        String failure = null;
        try {
            if (session != activeSession) {
                return; // the video was stopped, or replaced, before the decoder opened
            }
            if (MFVideoPlayer.isSupported()) {
                MFVideoPlayer mf = new MFVideoPlayer(absolutePath,
                        endCallback(session), failureCallback(session));
                mf.start();
                if (mf.awaitReady(DECODER_OPEN_TIMEOUT_MS)) {
                    player = mf;
                    LOGGER.info("[VideoPlayback] Decoder: Media Foundation");
                } else {
                    failure = mf.getOpenFailure();
                    LOGGER.warn("[VideoPlayback] Media Foundation could not play this file ({}), "
                            + "falling back to JavaFX", failure);
                    mf.stop();
                }
            }
            if (player == null && session != activeSession) {
                // Do not spend another open timeout on a playback that was already replaced.
                return;
            }
            if (player == null) {
                FXVideoPlayer fx = new FXVideoPlayer(absolutePath,
                        endCallback(session), failureCallback(session));
                fx.start();
                if (fx.awaitReady(DECODER_OPEN_TIMEOUT_MS)) {
                    player = fx;
                    LOGGER.info("[VideoPlayback] Decoder: JavaFX");
                } else {
                    failure = fx.getOpenFailure();
                    fx.stop();
                }
            }
        } catch (Throwable t) {
            LOGGER.error("[VideoPlayback] Could not open a decoder for {}", absolutePath, t);
            failure = "could not start playback: " + t;
            if (player != null) {
                try {
                    player.stop();
                } catch (Throwable ignored) {
                }
            }
            player = null;
        }
        final IVideoFrameProvider opened = player;
        final String reason = failure;
        runOnClient(() -> onDecoderOpened(session, fileName, opened, reason));
    }

    /** Runs on the client thread once the decoder for {@code session} settled. */
    private void onDecoderOpened(long session, String fileName,
                                 IVideoFrameProvider player, String failure) {
        if (session != activeSession) {
            // A newer playback started (or the video was stopped) meanwhile.
            if (player != null) {
                try {
                    player.stop();
                } catch (Throwable ignored) {
                }
            }
            return;
        }
        if (player == null || player.getWidth() <= 0 || player.getHeight() <= 0) {
            if (player != null) {
                try {
                    player.stop();
                } catch (Throwable ignored) {
                }
            }
            stopVideoNow();
            fireFail(fileName, failure == null || failure.isEmpty()
                    ? "the video decoder could not start" : failure);
            return;
        }
        currentPlayer = player;
        textureReady = false;
        fireStart(fileName);
    }

    private Runnable endCallback(long session) {
        return () -> runOnClient(() -> {
            if (session != activeSession) {
                return; // stale decoder from a previous playback
            }
            String finished = currentName;
            stopVideoNow();
            fireEnd(finished);
        });
    }

    private Consumer<String> failureCallback(long session) {
        return reason -> runOnClient(() -> {
            if (session != activeSession) {
                return; // stale decoder from a previous playback
            }
            String failed = currentName;
            stopVideoNow();
            fireFail(failed, reason);
        });
    }

    // ------------------------------------------------------------- controls

    public void stopVideo() {
        runOnClient(this::stopVideoNow);
    }

    private void stopVideoNow() {
        // Invalidate pending decoder opens and every callback of the previous session.
        activeSession = ++sessionCounter;
        currentName = null;
        stopPlayerOnlyNow();
        if (overlayGui != null) {
            GuiVideoOverlay overlay = overlayGui;
            overlayGui = null;
            overlay.closeOverlay();
        }
    }

    private void stopPlayerOnlyNow() {
        if (currentPlayer != null) {
            currentPlayer.stop();
            currentPlayer = null;
        }
        if (currentTexture != null) {
            currentTexture.close();
            currentTexture = null;
        }
        textureReady = false;
    }

    /** Called by the overlay when the player closes it with ESC. */
    public void onOverlayClosed() {
        runOnClient(() -> {
            overlayGui = null;
            activeSession = ++sessionCounter;
            stopPlayerOnlyNow();
            currentName = null;
        });
    }

    public void pauseVideo() {
        runOnClient(() -> {
            if (currentPlayer != null) {
                currentPlayer.pause();
            }
        });
    }

    public void resumeVideo() {
        runOnClient(() -> {
            if (currentPlayer != null) {
                currentPlayer.resume();
            }
        });
    }

    // ------------------------------------------------------------- state

    private long beginSession() {
        activeSession = ++sessionCounter;
        return activeSession;
    }

    private void failSession(String fileName, String reason) {
        stopVideoNow();
        fireFail(fileName, reason);
    }

    public boolean isVideoActive() {
        return currentPlayer != null && currentPlayer.isPlaying();
    }

    public boolean isPaused() {
        return currentPlayer != null && currentPlayer.isPaused();
    }

    public float getProgress() {
        IVideoFrameProvider player = currentPlayer;
        return player == null ? 0f : player.getProgress();
    }

    public long getDurationMs() {
        IVideoFrameProvider player = currentPlayer;
        return player == null ? 0L : player.getDurationMs();
    }

    public String getCurrent() {
        return currentName;
    }

    public boolean isTextureReady() {
        return textureReady;
    }

    public int getTextureId() {
        if (currentTexture != null) {
            currentTexture.bind();
            return currentTexture.getTextureId();
        }
        return -1;
    }

    public void updateTexture() {
        IVideoFrameProvider player = currentPlayer;
        if (player == null || !player.isPlaying()) {
            return;
        }
        int[] frame = player.getFramePixels();
        if (frame != null) {
            if (currentTexture == null) {
                currentTexture = new VideoTexture(player.getWidth(), player.getHeight());
                textureReady = true;
            }
            currentTexture.uploadFrame(frame);
        }
    }

    public int getVideoWidth() {
        IVideoFrameProvider player = currentPlayer;
        return player != null ? player.getWidth() : 1;
    }

    public int getVideoHeight() {
        IVideoFrameProvider player = currentPlayer;
        return player != null ? player.getHeight() : 1;
    }

    // ------------------------------------------------------------- callbacks

    /** Called by the download cache on the client thread. */
    public void onDownloadProgress(String fileName, float progress) {
        fireLoading(fileName, progress);
    }

    /** Called by the download cache on the client thread. */
    public void onDownloadFinished(String fileName, boolean saved, boolean autoPlay) {
        if (!saved) {
            onDownloadFailed(fileName, "the downloaded file could not be saved");
            return;
        }
        fireReady(fileName);
        if (autoPlay && fileName.equals(currentName)) {
            startLocalPlayback(fileName);
        }
    }

    /** Called by the download cache on the client thread. */
    public void onDownloadFailed(String fileName, String reason) {
        if (fileName != null && fileName.equals(currentName)) {
            stopVideoNow();
        }
        fireFail(fileName, reason);
    }

    // ------------------------------------------------------------- events

    private void fireLoading(String fileName, float progress) {
        for (CgListener listener : listeners) {
            try {
                listener.onLoading(fileName, progress);
            } catch (RuntimeException e) {
                LOGGER.warn("CG listener threw", e);
            }
        }
    }

    private void fireReady(String fileName) {
        for (CgListener listener : listeners) {
            try {
                listener.onReady(fileName);
            } catch (RuntimeException e) {
                LOGGER.warn("CG listener threw", e);
            }
        }
    }

    private void fireStart(String fileName) {
        for (CgListener listener : listeners) {
            try {
                listener.onStart(fileName);
            } catch (RuntimeException e) {
                LOGGER.warn("CG listener threw", e);
            }
        }
    }

    private void fireFail(String fileName, String reason) {
        LOGGER.warn("CG {} failed: {}", fileName, reason);
        for (CgListener listener : listeners) {
            try {
                listener.onFail(fileName, reason);
            } catch (RuntimeException e) {
                LOGGER.warn("CG listener threw", e);
            }
        }
    }

    private void fireEnd(String fileName) {
        if (fileName == null) {
            return;
        }
        for (CgListener listener : listeners) {
            try {
                listener.onEnd(fileName);
            } catch (RuntimeException e) {
                LOGGER.warn("CG listener threw", e);
            }
        }
    }

    private static void runOnClient(Runnable task) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.isCallingFromMinecraftThread()) {
            task.run();
        } else {
            minecraft.addScheduledTask(task);
        }
    }
}
