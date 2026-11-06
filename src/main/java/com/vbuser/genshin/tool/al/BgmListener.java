package com.vbuser.genshin.tool.al;

/**
 * Playback callbacks for {@link BGMAPI#addListener(BgmListener)}.
 *
 * <p>All callbacks run on the client thread.</p>
 */
public interface BgmListener {

    /** Playback actually started (the local file was found and handed to the engine). */
    default void onStart(String fileName) {
    }

    /** The file finished downloading (or already existed) and is ready for playback. */
    default void onReady(String fileName) {
    }

    /** Download progress in {@code [0, 1]}. Fired when the rounded percentage changes. */
    default void onProgress(String fileName, float progress) {
    }

    /** Playback or download failed; {@code reason} is meant for logs or a chat message. */
    default void onFail(String fileName, String reason) {
    }

    /** The track ended on its own (not fired for an explicit stop). */
    default void onEnd(String fileName) {
    }
}
