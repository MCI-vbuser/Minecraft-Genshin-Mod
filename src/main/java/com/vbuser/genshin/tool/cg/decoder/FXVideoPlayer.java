package com.vbuser.genshin.tool.cg.decoder;

import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * JavaFX based video decoder that exposes the decoded frame as an ARGB int array.
 *
 * <p>Decoding, pausing and seeking all happen on the JavaFX thread; the state read by the
 * render thread is kept in volatile fields.</p>
 *
 * <p>Two details keep the JavaFX toolkit alive, both of which matter inside Minecraft:</p>
 * <ul>
 *   <li>the {@link JFXPanel} used to boot the toolkit is kept in a static field. A
 *       unreachable JFXPanel can be collected, and the toolkit then believes it has no
 *       windows and tries to exit (Forge logs "MOD HAS DIRECT REFERENCE System.exit()"
 *       from {@code Scene$ScenePulseListener.pulse()} and intercepts it, which leaves the
 *       video sink without a window and makes playback fail with ERROR_MEDIA_INVALID);</li>
 *   <li>implicit exit is disabled again right before the media stage is created, because
 *       the toolkit may have been started before this class was initialized.</li>
 * </ul>
 */
public class FXVideoPlayer implements IVideoFrameProvider {

    private static final Logger LOGGER = LogManager.getLogger("FXVideoPlayer");

    private MediaPlayer mediaPlayer;
    private MediaView mediaView;
    private Stage invisibleStage;
    private String mediaUri;
    private int width;
    private int height;
    private volatile boolean playing;
    private volatile boolean stopped;
    private volatile boolean paused;
    private volatile long currentMs;
    private volatile long totalMs;
    private final ArrayBlockingQueue<int[]> frameQueue = new ArrayBlockingQueue<>(3);

    private static volatile boolean jfxInitialized = false;
    /** Strong reference on purpose: see the class javadoc. */
    private static JFXPanel toolkitPanel;
    private static volatile boolean unavailableLogged;
    private static volatile boolean unavailable;

    /**
     * Boots the JavaFX toolkit. Never throws: on platforms without JavaFX natives (for
     * example a Linux JDK without OpenJFX) the failure is logged once and
     * {@link #isInitialized()} stays false, so callers can report a clean error instead of
     * crashing the client thread.
     */
    public static synchronized void initJavaFX() {
        if (jfxInitialized || unavailable) {
            return;
        }
        try {
            toolkitPanel = new JFXPanel();
            Platform.setImplicitExit(false);
            jfxInitialized = true;
        } catch (Throwable t) {
            // The toolkit is not usable here (for example Linux without GTK2). Do not
            // retry: every attempt spawns a broken FX thread.
            unavailable = true;
            if (!unavailableLogged) {
                unavailableLogged = true;
                LOGGER.error("[FXVideoPlayer] JavaFX toolkit is unavailable on this platform", t);
            }
        }
    }

    /** True once the JavaFX toolkit has been started. */
    public static boolean isInitialized() {
        return jfxInitialized;
    }

    private final String videoFilePath;

    public FXVideoPlayer(String videoFilePath) {
        this.videoFilePath = videoFilePath;
    }

    @Override
    public int getWidth() {
        return width;
    }

    @Override
    public int getHeight() {
        return height;
    }

    @Override
    public synchronized void start() {
        initJavaFX();
        if (!jfxInitialized) {
            playing = false;
            reportFailure("JavaFX is not available on this platform (no JavaFX natives?)");
            return;
        }
        paused = false;
        stopped = false;
        playing = true;
        try {
            Platform.runLater(this::openMedia);
        } catch (Throwable t) {
            playing = false;
            LOGGER.error("[FXVideoPlayer] Could not schedule media creation", t);
            reportFailure("JavaFX could not start playback: " + t);
        }
    }

    /** Creates the (invisible) stage and the media pipeline. */
    private void openMedia() {
        if (stopped) {
            return;
        }
        releaseStageAndPlayer();
        String source = null;
        try {
            // The toolkit may have been started earlier (mod init), so make sure the
            // implicit-exit flag is applied again before the first Stage exists.
            Platform.setImplicitExit(false);

            // Canonicalize: the game directory is "." in a dev run, so a raw path would
            // end up as "...\.\cg\x.mp4" and JavaFX would turn that into
            // "file:/..././cg/x.mp4". Keep the URI clean and absolute.
            File videoFile = new File(videoFilePath).getCanonicalFile();
            if (!videoFile.isFile()) {
                LOGGER.error("[FXVideoPlayer] Video file not found: {}", videoFile.getAbsolutePath());
                playing = false;
                reportFailure("file not found: " + videoFile.getAbsolutePath());
                return;
            }
            source = videoFile.toURI().toString();
            mediaUri = source;
            final String src = source;
            LOGGER.info("[FXVideoPlayer] Opening {} ({} bytes)", src, videoFile.length());

            invisibleStage = new Stage(StageStyle.UTILITY);
            invisibleStage.setOpacity(0);
            invisibleStage.setAlwaysOnTop(false);
            invisibleStage.setWidth(1);
            invisibleStage.setHeight(1);

            final Media media = new Media(src);
            mediaPlayer = new MediaPlayer(media);
            mediaView = new MediaView(mediaPlayer);
            Scene scene = new Scene(new Group(mediaView));
            scene.setFill(javafx.scene.paint.Color.TRANSPARENT);
            invisibleStage.setScene(scene);
            invisibleStage.show();

            mediaPlayer.setOnReady(() -> {
                width = media.getWidth();
                height = media.getHeight();
                totalMs = (long) media.getDuration().toMillis();
                LOGGER.info("[FXVideoPlayer] Ready: {}x{} ({} ms)", width, height, totalMs);
                startSnapshotTimer();
                mediaPlayer.play();
            });
            mediaPlayer.setOnEndOfMedia(() -> VideoPlaybackManager.getInstance().onPlaybackEnded());
            mediaPlayer.setOnError(() -> onPlayerError(src));
        } catch (Exception e) {
            LOGGER.error("[FXVideoPlayer] Error starting the player [source={}]", source, e);
            reportFailure("could not start the player: " + e.getMessage()
                    + (source == null ? "" : " [source=" + source + "]"));
        }
    }

    private void onPlayerError(String source) {
        String error = mediaPlayer == null || mediaPlayer.getError() == null
                ? "unknown media error"
                : mediaPlayer.getError().getMessage();
        LOGGER.error("[FXVideoPlayer] MediaPlayer error: {} [source={}]", error, source);
        releaseStageAndPlayer();
        reportFailure(error + " [source=" + source + "]");
    }

    private void releaseStageAndPlayer() {
        if (mediaPlayer != null) {
            try {
                mediaPlayer.stop();
            } catch (Throwable ignored) {
            }
            try {
                mediaPlayer.dispose();
            } catch (Throwable ignored) {
            }
            mediaPlayer = null;
        }
        mediaView = null;
        if (invisibleStage != null) {
            try {
                invisibleStage.hide();
            } catch (Throwable ignored) {
            }
            invisibleStage = null;
        }
        frameQueue.clear();
    }

    private void reportFailure(String reason) {
        VideoPlaybackManager.getInstance().onPlaybackFailed(reason);
    }

    private void startSnapshotTimer() {
        new javafx.animation.AnimationTimer() {
            @Override
            public void handle(long now) {
                if (stopped || mediaPlayer == null) {
                    stop();
                    return;
                }
                updateTiming();
                captureFrame();
            }
        }.start();
    }

    private void updateTiming() {
        try {
            javafx.util.Duration current = mediaPlayer.getCurrentTime();
            javafx.util.Duration total = mediaPlayer.getTotalDuration();
            if (current != null) {
                currentMs = (long) current.toMillis();
            }
            if (total != null) {
                totalMs = (long) total.toMillis();
            }
        } catch (Throwable ignored) {
            // Timing is informational only.
        }
    }

    private void captureFrame() {
        if (mediaView == null || width <= 0 || height <= 0) {
            return;
        }
        WritableImage snapshot = mediaView.snapshot(null, null);
        if (snapshot == null) {
            return;
        }
        int[] pixels = new int[width * height];
        snapshot.getPixelReader().getPixels(0, 0, width, height,
                PixelFormat.getIntArgbInstance(), pixels, 0, width);
        frameQueue.poll();
        frameQueue.offer(pixels);
    }

    @Override
    public int[] getFramePixels() {
        try {
            return frameQueue.poll(10, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    @Override
    public synchronized void stop() {
        stopped = true;
        playing = false;
        paused = false;
        runOnFx(this::releaseStageAndPlayer);
    }

    @Override
    public void pause() {
        if (!playing || stopped) {
            return;
        }
        paused = true;
        runOnFx(() -> {
            if (mediaPlayer != null) {
                mediaPlayer.pause();
            }
        });
    }

    @Override
    public void resume() {
        if (!playing || stopped) {
            return;
        }
        paused = false;
        runOnFx(() -> {
            if (mediaPlayer != null) {
                mediaPlayer.play();
            }
        });
    }

    /** Runs a task on the JavaFX thread, tolerating a toolkit that is not running. */
    private static void runOnFx(Runnable task) {
        try {
            Platform.runLater(task);
        } catch (Throwable t) {
            LOGGER.debug("[FXVideoPlayer] JavaFX thread is unavailable", t);
        }
    }

    @Override
    public boolean isPaused() {
        return paused;
    }

    @Override
    public float getProgress() {
        return totalMs <= 0 ? 0f : Math.max(0f, Math.min(1f, (float) ((double) currentMs / (double) totalMs)));
    }

    @Override
    public long getDurationMs() {
        return totalMs;
    }

    @Override
    public boolean isPlaying() {
        return playing && !stopped;
    }

    /** Last media URI this player tried to open, for diagnostics. */
    public String getMediaUri() {
        return mediaUri;
    }
}
