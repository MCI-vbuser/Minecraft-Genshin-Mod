package com.vbuser.genshin.tool.cg;

/**
 * Playback callbacks for {@code CGAPI.addListener(CgListener)}.
 *
 * <p>All callbacks run on the client thread.</p>
 */
public interface CgListener {

    /** Download/generation progress in {@code [0, 1]} while a cg is being fetched. */
    default void onLoading(String fileName, float progress) {
    }

    /** The file is available locally (downloaded or already present). */
    default void onReady(String fileName) {
    }

    /** Playback started. */
    default void onStart(String fileName) {
    }

    /** Playback, download or decoding failed; {@code reason} is meant for logs. */
    default void onFail(String fileName, String reason) {
    }

    /** The video reached its end (not fired for an explicit stop). */
    default void onEnd(String fileName) {
    }
}
