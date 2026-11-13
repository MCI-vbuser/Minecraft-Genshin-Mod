package com.vbuser.genshin.tool.cg;

import com.vbuser.genshin.tool.cg.decoder.FXVideoPlayer;
import com.vbuser.genshin.tool.cg.decoder.VideoPlaybackManager;
import com.vbuser.genshin.tool.cg.network.CGFileClientCache;
import com.vbuser.genshin.tool.cg.network.PlayCGPacket;
import com.vbuser.genshin.tool.cg.network.StopCGPacket;
import com.vbuser.genshin.tool.network.NetworkManager;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.relauncher.Side;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

/**
 * The only cg (video) entry point a mod author needs.
 *
 * <h2>Side model</h2>
 * <p>Every method states its side explicitly; nothing changes meaning depending on where it
 * runs. The side is decided from the environment, never from {@code getEffectiveSide()},
 * which reports CLIENT for any thread a mod creates.</p>
 * <ul>
 *   <li>{@code playLocal(...)} — client only, plays for the local player.</li>
 *   <li>{@code playTo(...)} / {@code playToAll(...)} — needs a local server (dedicated
 *       server or single player).</li>
 * </ul>
 *
 * <h2>Rendering</h2>
 * <p>By default, a full screen overlay is shown. Call {@link #setAutoOverlay(boolean) false}
 * and use {@link #getTextureId()} / {@link #updateTexture()} to draw the frame inside your
 * own GUI.</p>
 *
 * <h2>Files</h2>
 * <p>{@code fileName} is a relative path inside {@link #getCGDir()}; traversal attempts are
 * rejected on both sides.</p>
 */
@SuppressWarnings("unused")
public final class CGAPI {

    private static final Logger LOGGER = LogManager.getLogger("CGAPI");

    private CGAPI() {
    }

    // ------------------------------------------------------------- availability

    /** True when the local client can decode and play cg files right now. */
    public static boolean isAvailable() {
        if (!isPhysicalClient()) {
            return false;
        }
        return FXVideoPlayer.isInitialized();
    }

    /** True when calls run against a local server (dedicated server or single player). */
    public static boolean hasServer() {
        return FMLCommonHandler.instance().getMinecraftServerInstance() != null;
    }

    public static boolean isClient() {
        return isPhysicalClient();
    }

    // ------------------------------------------------------------- local playback

    /** Plays a cg for the local player; downloads it first when missing. Client side only. */
    public static void playLocal(String fileName) {
        requireClient("playLocal");
        VideoPlaybackManager.getInstance().playLocal(fileName);
    }

    /** Stops the local cg. Client side only. */
    public static void stopLocal() {
        requireClient("stopLocal");
        VideoPlaybackManager.getInstance().stopVideo();
    }

    public static void pauseLocal() {
        requireClient("pauseLocal");
        VideoPlaybackManager.getInstance().pauseVideo();
    }

    public static void resumeLocal() {
        requireClient("resumeLocal");
        VideoPlaybackManager.getInstance().resumeVideo();
    }

    public static boolean isPlaying() {
        requireClient("isPlaying");
        return VideoPlaybackManager.getInstance().isVideoActive();
    }

    public static boolean isPaused() {
        requireClient("isPaused");
        return VideoPlaybackManager.getInstance().isPaused();
    }

    /** Playback progress in {@code [0, 1]}. Client side only. */
    public static float getProgress() {
        requireClient("getProgress");
        return VideoPlaybackManager.getInstance().getProgress();
    }

    /** Total duration in milliseconds, or 0 when unknown. Client side only. */
    public static long getDurationMs() {
        requireClient("getDurationMs");
        return VideoPlaybackManager.getInstance().getDurationMs();
    }

    /** Currently playing cg, or {@code null}. Client side only. */
    public static String getCurrent() {
        requireClient("getCurrent");
        return VideoPlaybackManager.getInstance().getCurrent();
    }

    /** Download progress of a cg file in {@code [0, 1]}. Client side only. */
    public static float getDownloadProgress(String fileName) {
        requireClient("getDownloadProgress");
        return CGFileClientCache.getProgress(fileName);
    }

    // ------------------------------------------------------------- server playback

    /**
     * Sends a cg to one player. Server side only.
     *
     * @return false when the file does not exist on the server; nothing is sent in that case
     */
    public static boolean playTo(EntityPlayerMP player, String fileName) {
        requireServer("playTo");
        if (player == null) {
            return false;
        }
        if (!CGFileManager.exists(fileName)) {
            LOGGER.warn("CG file not found on this server: {}", CGFileManager.getCGDir() + "/" + fileName);
            return false;
        }
        NetworkManager.sendToPlayer(new PlayCGPacket(fileName), player);
        return true;
    }

    /** Sends a cg to several players. Server side only. @return how many players got it */
    public static int playTo(Collection<EntityPlayerMP> players, String fileName) {
        requireServer("playTo");
        if (players == null || players.isEmpty()) {
            return 0;
        }
        if (!CGFileManager.exists(fileName)) {
            LOGGER.warn("CG file not found on the server: {}", CGFileManager.getCGDir() + "/" + fileName);
            return 0;
        }
        PlayCGPacket packet = new PlayCGPacket(fileName);
        int sent = 0;
        for (EntityPlayerMP player : players) {
            if (player != null) {
                NetworkManager.sendToPlayer(packet, player);
                sent++;
            }
        }
        return sent;
    }

    /** Sends a cg to every online player. Server side only. @return how many players got it */
    public static int playToAll(String fileName) {
        requireServer("playToAll");
        List<EntityPlayerMP> players = onlinePlayers();
        return players.isEmpty() ? 0 : playTo(players, fileName);
    }

    /** Stops the cg of one player. Server side only. */
    public static void stopTo(EntityPlayerMP player) {
        requireServer("stopTo");
        if (player != null) {
            NetworkManager.sendToPlayer(new StopCGPacket(), player);
        }
    }

    /** Stops the cg of several players. Server side only. */
    public static void stopTo(Collection<EntityPlayerMP> players) {
        requireServer("stopTo");
        if (players == null) {
            return;
        }
        for (EntityPlayerMP player : players) {
            stopTo(player);
        }
    }

    /** Stops the cg of every online player. Server side only. @return how many were targeted */
    public static int stopToAll() {
        requireServer("stopToAll");
        List<EntityPlayerMP> players = onlinePlayers();
        if (players.isEmpty()) {
            return 0;
        }
        StopCGPacket packet = new StopCGPacket();
        for (EntityPlayerMP player : players) {
            NetworkManager.sendToPlayer(packet, player);
        }
        return players.size();
    }

    // ------------------------------------------------------------- files

    public static File getCGDir() {
        return CGFileManager.getCGDir();
    }

    public static boolean has(String fileName) {
        return CGFileManager.exists(fileName);
    }

    public static List<String> list() {
        return CGFileManager.list();
    }

    /** Copies a stream shipped with a mod into the cg directory. */
    public static boolean install(InputStream stream, String fileName) {
        try {
            CGFileManager.install(stream, fileName);
            return true;
        } catch (IOException | IllegalArgumentException e) {
            LOGGER.warn("Failed to install cg {}", fileName, e);
            return false;
        }
    }

    // ------------------------------------------------------------- rendering hook

    /** Enables or disables the built-in full screen overlay. Client side only. */
    public static void setAutoOverlay(boolean enabled) {
        requireClient("setAutoOverlay");
        VideoPlaybackManager.getInstance().setAutoOverlay(enabled);
    }

    public static boolean isAutoOverlay() {
        requireClient("isAutoOverlay");
        return VideoPlaybackManager.getInstance().isAutoOverlay();
    }

    /** GL texture id of the current frame, or -1. Client side only. */
    public static int getTextureId() {
        requireClient("getTextureId");
        return VideoPlaybackManager.getInstance().getTextureId();
    }

    /** Uploads the newest decoded frame; call once per frame before drawing the texture. */
    public static void updateTexture() {
        requireClient("updateTexture");
        VideoPlaybackManager.getInstance().updateTexture();
    }

    public static boolean isTextureReady() {
        requireClient("isTextureReady");
        return VideoPlaybackManager.getInstance().isTextureReady();
    }

    public static int getVideoWidth() {
        requireClient("getVideoWidth");
        return VideoPlaybackManager.getInstance().getVideoWidth();
    }

    public static int getVideoHeight() {
        requireClient("getVideoHeight");
        return VideoPlaybackManager.getInstance().getVideoHeight();
    }

    // ------------------------------------------------------------- events

    /** Downloads a cg without playing it; the callback receives {@code true} on success. */
    public static void preload(String fileName, Consumer<Boolean> callback) {
        requireClient("preload");
        VideoPlaybackManager manager = VideoPlaybackManager.getInstance();
        if (CGFileManager.exists(fileName)) {
            if (callback != null) {
                callback.accept(true);
            }
            return;
        }
        if (callback != null) {
            manager.addListener(new CgListener() {
                @Override
                public void onReady(String name) {
                    if (fileName.equals(name)) {
                        manager.removeListener(this);
                        callback.accept(true);
                    }
                }

                @Override
                public void onFail(String name, String reason) {
                    if (fileName.equals(name)) {
                        manager.removeListener(this);
                        callback.accept(false);
                    }
                }
            });
        }
        manager.prefetch(fileName);
    }

    public static void addListener(CgListener listener) {
        requireClient("addListener");
        VideoPlaybackManager.getInstance().addListener(listener);
    }

    public static void removeListener(CgListener listener) {
        if (isPhysicalClient()) {
            VideoPlaybackManager.getInstance().removeListener(listener);
        }
    }

    // ------------------------------------------------------------- deprecated aliases

    /**
     * @deprecated ambiguous side semantics; use {@link #playLocal(String)} or
     * {@link #playToAll(String)}.
     */
    @Deprecated
    public static void playCG(String fileName) {
        if (hasServer()) {
            playToAll(fileName);
        } else {
            playLocal(fileName);
        }
    }

    /**
     * @deprecated use {@link #playTo(EntityPlayerMP, String)}.
     */
    @Deprecated
    public static void playCG(EntityPlayerMP player, String fileName) {
        playTo(player, fileName);
    }

    /**
     * @deprecated use {@link #playToAll(String)}.
     */
    @Deprecated
    public static void playCGToAll(String fileName) {
        playToAll(fileName);
    }

    /**
     * @deprecated ambiguous side semantics; use {@link #stopLocal()} or {@link #stopToAll()}.
     */
    @Deprecated
    public static void stopCG() {
        if (hasServer()) {
            stopToAll();
        } else {
            stopLocal();
        }
    }

    /**
     * @deprecated use {@link #stopTo(EntityPlayerMP)}.
     */
    @Deprecated
    public static void stopCG(EntityPlayerMP player) {
        stopTo(player);
    }

    /**
     * @deprecated use {@link #stopToAll()}.
     */
    @Deprecated
    public static void stopCGToAll() {
        stopToAll();
    }

    // ------------------------------------------------------------- internals

    private static List<EntityPlayerMP> onlinePlayers() {
        if (FMLCommonHandler.instance().getMinecraftServerInstance() == null) {
            return new ArrayList<>();
        } else {
            FMLCommonHandler.instance().getMinecraftServerInstance();
        }
        return new ArrayList<>(FMLCommonHandler.instance()
                .getMinecraftServerInstance().getPlayerList().getPlayers());
    }

    private static void requireClient(String operation) {
        if (!isPhysicalClient()) {
            throw new IllegalStateException(operation + "() can only be called on the client side.");
        }
    }

    private static void requireServer(String operation) {
        if (!hasServer()) {
            throw new IllegalStateException(operation + "() requires the server side: call it from the "
                    + "dedicated server or in single player, or use the *Local variants on the client.");
        }
    }

    private static boolean isPhysicalClient() {
        return FMLCommonHandler.instance().getSide() == Side.CLIENT;
    }
}
