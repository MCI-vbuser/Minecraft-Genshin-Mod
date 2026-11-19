package com.vbuser.genshin.tool.cg.decoder;

public interface IVideoFrameProvider {

    boolean isPlaying();

    int getWidth();

    int getHeight();

    int[] getFramePixels();

    void start();

    void stop();

    /**
     * Waits until this decoder either became ready or failed. Must be called from a worker
     * thread, never from the client thread: {@link #start()} only begins opening the media.
     *
     * @return true when the decoder is ready to produce frames
     */
    default boolean awaitReady(long timeoutMs) {
        return isPlaying();
    }

    /** Human readable reason of the last failed {@link #awaitReady(long)}; may be null. */
    default String getOpenFailure() {
        return null;
    }

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
