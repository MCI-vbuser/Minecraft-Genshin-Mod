package com.vbuser.genshin.tool.cg.decoder;

public interface IVideoFrameProvider {

    boolean isPlaying();

    int getWidth();

    int getHeight();

    int[] getFramePixels();

    void start();

    void stop();

    /** Pauses decoding without releasing the video session. */
    default void pause() {
    }

    default void resume() {
    }

    default boolean isPaused() {
        return false;
    }

    /** Playback progress in {@code [0, 1]}, or 0 when unknown. */
    default float getProgress() {
        return 0f;
    }

    /** Total duration in milliseconds, or 0 when unknown. */
    default long getDurationMs() {
        return 0L;
    }
}
