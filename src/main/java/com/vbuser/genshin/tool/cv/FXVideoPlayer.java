package com.vbuser.genshin.tool.cv;
import javafx.application.Platform;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import javafx.scene.image.PixelFormat;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.stage.Stage;
import javafx.embed.swing.JFXPanel;
import javafx.stage.StageStyle;

import java.io.File;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

public class FXVideoPlayer implements IVideoFrameProvider {
    private MediaPlayer mediaPlayer;
    private MediaView mediaView;
    private Stage invisibleStage;
    private int width, height;
    private volatile boolean playing;
    private final ArrayBlockingQueue<int[]> frameQueue = new ArrayBlockingQueue<>(3);
    private volatile boolean stopped = false;

    private static volatile boolean jfxInitialized = false;
    public static void initJavaFX() {
        if (!jfxInitialized) {
            new JFXPanel();
            Platform.setImplicitExit(false);
            jfxInitialized = true;
        }
    }

    private final String videoFilePath;

    public FXVideoPlayer(String videoFilePath) {
        this.videoFilePath = videoFilePath;
    }

    @Override
    public int getWidth() { return width; }
    @Override
    public int getHeight() { return height; }

    @Override
    public synchronized void start() {
        initJavaFX();
        Platform.runLater(() -> {
            try {
                invisibleStage = new Stage(StageStyle.UTILITY);
                File videoFile = new File(videoFilePath);
                System.out.println("[VideoMod] Looking for video at: " + videoFile.getAbsolutePath());
                if (!videoFile.exists()) {
                    System.err.println("[VideoMod] Video file not found!");
                    playing = false;
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
                    System.out.println("[VideoMod] Media ready, resolution: " + media.getWidth() + "x" + media.getHeight());
                    startSnapshotTimer();
                    mediaPlayer.play();
                });
                mediaPlayer.setOnEndOfMedia(() -> VideoPlaybackManager.getInstance().stopVideo());

                mediaPlayer.setOnError(() -> System.err.println("[VideoMod] MediaPlayer error: " + mediaPlayer.getError()));
            } catch (Exception e) {
                System.err.println("[VideoMod] Error starting player: "+e.getMessage());
            }
        });
        playing = true;
    }

    private void startSnapshotTimer() {
        new javafx.animation.AnimationTimer() {
            @Override
            public void handle(long now) {
                if (stopped || mediaPlayer == null) {
                    stop();
                    return;
                }
                captureFrame();
            }
        }.start();
    }

    private void captureFrame() {
        if (mediaView == null || width <= 0 || height <= 0) return;
        WritableImage snapshot = mediaView.snapshot(null, null);
        if (snapshot != null) {
            int[] pixels = new int[width * height];
            snapshot.getPixelReader().getPixels(0, 0, width, height,
                    PixelFormat.getIntArgbInstance(), pixels, 0, width);
            frameQueue.poll();
            frameQueue.offer(pixels);
        }
    }

    @Override
    public int[] getFramePixels() {
        try {
            return frameQueue.poll(10, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            return null;
        }
    }

    public synchronized void stop() {
        stopped = true;
        playing = false;
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
    public boolean isPlaying() {
        return playing && !stopped;
    }
}