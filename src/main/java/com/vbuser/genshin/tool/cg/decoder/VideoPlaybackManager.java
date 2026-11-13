package com.vbuser.genshin.tool.cg.decoder;

import com.vbuser.genshin.tool.cg.CGFileManager;
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

/**
 * Client side cg state machine: download, playback, overlay and listeners.
 *
 * <p>Every public method may be called from any thread; work is moved to the client thread
 * when needed. The current video is exposed both as the built-in full screen overlay and as
 * a raw GL texture ({@link #getTextureId()}) so a mod can draw it inside its own GUI by
 * calling {@link #setAutoOverlay(boolean) setAutoOverlay(false)}.</p>
 */
@SideOnly(Side.CLIENT)
public class VideoPlaybackManager {

    private static final Logger LOGGER = LogManager.getLogger("VideoPlayback");
    private static final VideoPlaybackManager INSTANCE = new VideoPlaybackManager();

    private IVideoFrameProvider currentPlayer;
    private VideoTexture currentTexture;
    private boolean textureReady;
    private GuiVideoOverlay overlayGui;
    private String currentName;
    private boolean autoOverlay = true;

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
        runOnClient(() -> {
            try {
                stopVideoNow();
                if (!CGFileManager.isValidName(fileName)) {
                    fireFail(fileName, "illegal cg file name");
                    return;
                }
                currentName = fileName;
                if (CGFileManager.exists(fileName)) {
                    startLocalPlayback(fileName);
                } else {
                    fireLoading(fileName, 0f);
                    CGFileClientCache.request(fileName, true);
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

    /** Downloads a cg without playing it. */
    public void prefetch(String fileName) {
        runOnClient(() -> {
            if (!CGFileManager.isValidName(fileName)) {
                fireFail(fileName, "illegal cg file name");
                return;
            }
            if (CGFileManager.exists(fileName)) {
                fireReady(fileName);
                return;
            }
            fireLoading(fileName, 0f);
            CGFileClientCache.request(fileName, false);
        });
    }

    private void startLocalPlayback(String fileName) {
        try {
            File file;
            try {
                file = CGFileManager.getFile(fileName);
            } catch (IllegalArgumentException e) {
                fireFail(fileName, "illegal cg file name");
                return;
            }
            if (!file.isFile()) {
                fireFail(fileName, "file not found: " + file.getAbsolutePath());
                return;
            }
            if (autoOverlay && overlayGui == null) {
                GuiScreen previous = Minecraft.getMinecraft().currentScreen;
                overlayGui = new GuiVideoOverlay(previous);
                Minecraft.getMinecraft().displayGuiScreen(overlayGui);
            }
            currentPlayer = createPlayer(file.getAbsolutePath());
            textureReady = false;
            fireStart(fileName);
        } catch (Throwable t) {
            // Never let a decoder problem escape into the client tick.
            LOGGER.error("[VideoPlayback] Could not start playback of {}", fileName, t);
            stopVideoNow();
            fireFail(fileName, "could not start playback: " + t);
        }
    }

    /**
     * Picks the decoder for this platform.
     *
     * <p>On Windows Media Foundation is preferred because JavaFX's Windows media backend
     * (GStreamer + DirectShow) fails with {@code ERROR_MEDIA_INVALID} on some Windows/driver
     * combinations. Everywhere else, or when MF cannot open the file, JavaFX is used.</p>
     */
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

    private IVideoFrameProvider createPlayer(String absolutePath) {
        logEnvironmentOnce();
        if (MFVideoPlayer.isSupported()) {
            VideoPlaybackManager self = this;
            MFVideoPlayer mf = new MFVideoPlayer(absolutePath,
                    self::onPlaybackEnded,
                    self::onPlaybackFailed);
            try {
                mf.start();
                LOGGER.info("[VideoPlayback] Decoder: Media Foundation");
                return mf;
            } catch (Throwable t) {
                LOGGER.warn("[VideoPlayback] Media Foundation could not play this file, "
                        + "falling back to JavaFX", t);
                try {
                    mf.stop();
                } catch (Throwable ignored) {
                }
            }
        }
        FXVideoPlayer fx = new FXVideoPlayer(absolutePath);
        fx.start();
        LOGGER.info("[VideoPlayback] Decoder: JavaFX");
        return fx;
    }

    // ------------------------------------------------------------- controls

    public void stopVideo() {
        runOnClient(this::stopVideoNow);
    }

    private void stopVideoNow() {
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

    /** Called by the decoder when the video reached its end. */
    public void onPlaybackEnded() {
        runOnClient(() -> {
            String finished = currentName;
            stopVideoNow();
            fireEnd(finished);
        });
    }

    /** Called by the decoder when decoding could not start. */
    public void onPlaybackFailed(String reason) {
        runOnClient(() -> {
            String failed = currentName;
            stopVideoNow();
            fireFail(failed, reason);
        });
    }

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
