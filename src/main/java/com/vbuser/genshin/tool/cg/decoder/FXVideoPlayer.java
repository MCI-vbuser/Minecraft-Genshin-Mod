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
 */
public class FXVideoPlayer implements IVideoFrameProvider {

    private static final Logger LOGGER = LogManager.getLogger("FXVideoPlayer");

    private MediaPlayer mediaPlayer;
    private MediaView mediaView;
    private Stage invisibleStage;
    private int width;
    private int height;
    private volatile boolean playing;
    private volatile boolean stopped;
    private volatile boolean paused;
    private volatile long currentMs;
    private volatile long totalMs;
    private final ArrayBlockingQueue<int[]> frameQueue = new ArrayBlockingQueue<>(3);

    private static volatile boolean jfxInitialized = false;

    public static void initJavaFX() {
        if (!jfxInitialized) {
            new JFXPanel();
            Platform.setImplicitExit(false);
            jfxInitialized = true;
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
        Platform.runLater(() -> {
            try {
                invisibleStage = new Stage(StageStyle.UTILITY);
                File videoFile = new File(videoFilePath);
                if (!videoFile.isFile()) {
                    LOGGER.error("[FXVideoPlayer] Video file not found: {}", videoFile.getAbsolutePath());
                    playing = false;
                    reportFailure("file not found: " + videoFile.getAbsolutePath());
                    return;
                }
                invisibleStage.setOpacity(0);
                invisibleStage.setAlwaysOnTop(false);
                invisibleStage.setWidth(1);
                invisibleStage.setHeight(1);

                Media media = new Media(videoFile.toURI().toString());
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
                    startSnapshotTimer();
                    mediaPlayer.play();
                });
                mediaPlayer.setOnEndOfMedia(() -> VideoPlaybackManager.getInstance().onPlaybackEnded());

                mediaPlayer.setOnError(() -> {
                    String error = mediaPlayer.getError() == null ? "unknown media error" : mediaPlayer.getError().getMessage();
                    LOGGER.error("[FXVideoPlayer] MediaPlayer error: {}", error);
                    reportFailure(error);
                });
            } catch (Exception e) {
                LOGGER.error("[FXVideoPlayer] Error starting the player", e);
                reportFailure("could not start the player: " + e.getMessage());
            }
        });
        paused = false;
        stopped = false;
        playing = true;
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
        Platform.runLater(() -> {
            if (mediaPlayer != null) {
                mediaPlayer.stop();
                mediaPlayer.dispose();
            }
            if (invisibleStage != null) {
                invisibleStage.hide();
            }
            frameQueue.clear();
        });
    }

    @Override
    public void pause() {
        if (!playing || stopped) {
            return;
        }
        paused = true;
        Platform.runLater(() -> {
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
        Platform.runLater(() -> {
            if (mediaPlayer != null) {
                mediaPlayer.play();
            }
        });
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
}
