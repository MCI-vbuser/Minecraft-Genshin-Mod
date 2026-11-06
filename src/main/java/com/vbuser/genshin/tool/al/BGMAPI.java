package com.vbuser.genshin.tool.al;

import com.vbuser.genshin.tool.al.network.PlayBGMPacket;
import com.vbuser.genshin.tool.al.network.StopBGMPacket;
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
 * The only BGM entry point a mod author needs.
 *
 * <h2>Side model</h2>
 * <p>Every method states its side explicitly; nothing changes meaning depending on where
 * it runs. The side is decided from the environment (physical side / presence of a local
 * server), never from {@code getEffectiveSide()}, which reports CLIENT for any thread a
 * mod creates.</p>
 * <ul>
 *   <li>{@code playLocal(...)} — client only, plays for the local player.</li>
 *   <li>{@code playTo(...)} / {@code playToAll(...)} — needs a local server (dedicated
 *       server or single player).</li>
 * </ul>
 *
 * <h2>File names</h2>
 * <p>{@code fileName} is always a relative path inside the bgm directory
 * ({@link #getAssetDir()}). Names containing {@code ..}, a drive letter or a backslash are
 * rejected, and the API validates them on both sides.</p>
 *
 * <h2>Where files live</h2>
 * <ul>
 *   <li>server: {@code <server working dir>/bgm/<fileName>}</li>
 *   <li>client: {@code <minecraft dir>/bgm/<fileName>}</li>
 * </ul>
 * <p>A client that does not have the file receives it from the server on demand.</p>
 */
@SuppressWarnings("unused")
public final class BGMAPI {

    private static final Logger LOGGER = LogManager.getLogger("BGMAPI");

    private BGMAPI() {
    }

    // ------------------------------------------------------------- availability

    /** True when the local player could actually play BGM right now. */
    public static boolean isAvailable() {
        if (!isPhysicalClient()) {
            return false;
        }
        return BGMPlayer.getInstance().isAvailable();
    }

    /** True when calls run against a local server (dedicated server or single player). */
    public static boolean hasServer() {
        return FMLCommonHandler.instance().getMinecraftServerInstance() != null;
    }

    public static boolean isClient() {
        return isPhysicalClient();
    }

    // ------------------------------------------------------------- local playback

    /**
     * Plays BGM for the local player only. Client side only.
     *
     * @throws IllegalStateException when called on a dedicated server
     */
    public static void playLocal(String fileName) {
        requireClient("playLocal");
        BGMPlayer.getInstance().play(fileName, null);
    }

    /**
     * Plays BGM at a specific volume for the local player only. The volume is kept for
     * later playback as well (see {@link #setVolume(float)}).
     */
    public static void playLocal(String fileName, float volume) {
        requireClient("playLocal");
        BGMPlayer.getInstance().setVolume(volume);
        BGMPlayer.getInstance().play(fileName, null);
    }

    /** Stops the local player's BGM. Client side only. */
    public static void stopLocal() {
        requireClient("stopLocal");
        BGMPlayer.getInstance().stopScheduled();
    }

    /** Currently playing BGM, or {@code null}. Client side only. */
    public static String getCurrent() {
        requireClient("getCurrent");
        return BGMPlayer.getInstance().getCurrent();
    }

    public static boolean isPlaying() {
        requireClient("isPlaying");
        return BGMPlayer.getInstance().isPlaying();
    }

    /** True while the given file is being downloaded for the local player. */
    public static boolean isWaiting(String fileName) {
        requireClient("isWaiting");
        return BGMPlayer.getInstance().isWaitingFor(fileName);
    }

    /** Download progress in {@code [0, 1]}. Client side only. */
    public static float getDownloadProgress(String fileName) {
        requireClient("getDownloadProgress");
        return BGMPlayer.getInstance().getDownloadProgress(fileName);
    }

    /** Local playback volume, kept for the next track as well. Client side only. */
    public static void setVolume(float volume) {
        requireClient("setVolume");
        BGMPlayer.getInstance().setVolume(volume);
    }

    public static float getVolume() {
        requireClient("getVolume");
        return BGMPlayer.getInstance().getVolume();
    }

    // ------------------------------------------------------------- server playback

    /**
     * Sends a BGM to one player. Server side only.
     *
     * @return false when the file does not exist on the server or its checksum cannot be
     * computed; nothing is sent in that case
     */
    public static boolean playTo(EntityPlayerMP player, String fileName) {
        requireServer("playTo");
        if (player == null) {
            return false;
        }
        String md5 = serverChecksum(fileName);
        if (md5 == null) {
            return false;
        }
        NetworkManager.sendToPlayer(new PlayBGMPacket(fileName, md5), player);
        return true;
    }

    /** Sends a BGM to several players. Server side only. @return how many players got it */
    public static int playTo(Collection<EntityPlayerMP> players, String fileName) {
        requireServer("playTo");
        if (players == null || players.isEmpty()) {
            return 0;
        }
        String md5 = serverChecksum(fileName);
        if (md5 == null) {
            return 0;
        }
        PlayBGMPacket packet = new PlayBGMPacket(fileName, md5);
        int sent = 0;
        for (EntityPlayerMP player : players) {
            if (player != null) {
                NetworkManager.sendToPlayer(packet, player);
                sent++;
            }
        }
        return sent;
    }

    /** Sends a BGM to every online player. Server side only. @return how many players got it */
    public static int playToAll(String fileName) {
        requireServer("playToAll");
        List<EntityPlayerMP> players = onlinePlayers();
        if (players.isEmpty()) {
            return 0;
        }
        return playTo(players, fileName);
    }

    /** Stops the BGM of one player. Server side only. */
    public static void stopTo(EntityPlayerMP player) {
        requireServer("stopTo");
        if (player != null) {
            NetworkManager.sendToPlayer(new StopBGMPacket(), player);
        }
    }

    /** Stops the BGM of several players. Server side only. */
    public static void stopTo(Collection<EntityPlayerMP> players) {
        requireServer("stopTo");
        if (players == null) {
            return;
        }
        for (EntityPlayerMP player : players) {
            stopTo(player);
        }
    }

    /** Stops the BGM of every online player. Server side only. @return how many were targeted */
    public static int stopToAll() {
        requireServer("stopToAll");
        List<EntityPlayerMP> players = onlinePlayers();
        if (players.isEmpty()) {
            return 0;
        }
        StopBGMPacket packet = new StopBGMPacket();
        for (EntityPlayerMP player : players) {
            NetworkManager.sendToPlayer(packet, player);
        }
        return players.size();
    }

    // ------------------------------------------------------------- files

    /** Directory that holds the bgm files on this side. */
    public static File getAssetDir() {
        return BGMFileManager.getBGMDir();
    }

    /** True when this side has the file locally (server assets or client cache). */
    public static boolean has(String fileName) {
        return BGMFileManager.exists(fileName);
    }

    /** All bgm file names available on this side, sorted. */
    public static List<String> list() {
        return BGMFileManager.list();
    }

    /**
     * Copies a stream shipped with a mod into the bgm directory. Handy for mods that
     * bundle their own tracks.
     *
     * @return false when the name is illegal or the copy failed
     */
    public static boolean install(InputStream stream, String fileName) {
        try {
            BGMFileManager.install(stream, fileName);
            return true;
        } catch (IOException | IllegalArgumentException e) {
            LOGGER.warn("Failed to install bgm {}", fileName, e);
            return false;
        }
    }

    /** Pre-downloads a track for the local player. Client side only. */
    public static void preload(String fileName, Consumer<Boolean> callback) {
        requireClient("preload");
        BGMPlayer.getInstance().preload(fileName, callback);
    }

    /** Registers playback callbacks. Client side only. */
    public static void addListener(BgmListener listener) {
        requireClient("addListener");
        BGMPlayer.getInstance().addListener(listener);
    }

    public static void removeListener(BgmListener listener) {
        if (isPhysicalClient()) {
            BGMPlayer.getInstance().removeListener(listener);
        }
    }

    // ------------------------------------------------------------- deprecated aliases

    /**
     * @deprecated ambiguous side semantics; use {@link #playLocal(String)} or
     * {@link #playToAll(String)}.
     */
    @Deprecated
    public static void playBGM(String fileName) {
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
    public static void playBGM(EntityPlayerMP player, String fileName) {
        playTo(player, fileName);
    }

    /**
     * @deprecated use {@link #playToAll(String)}.
     */
    @Deprecated
    public static void playBGMToAll(String fileName) {
        playToAll(fileName);
    }

    /**
     * @deprecated ambiguous side semantics; use {@link #stopLocal()} or
     * {@link #stopToAll()}.
     */
    @Deprecated
    public static void stopBGM() {
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
    public static void stopBGM(EntityPlayerMP player) {
        stopTo(player);
    }

    /**
     * @deprecated use {@link #stopToAll()}.
     */
    @Deprecated
    public static void stopBGMToAll() {
        stopToAll();
    }

    // ------------------------------------------------------------- internals

    private static String serverChecksum(String fileName) {
        if (!BGMFileManager.isValidName(fileName)) {
            LOGGER.warn("Rejected illegal bgm file name: {}", fileName);
            return null;
        }
        if (!BGMFileManager.exists(fileName)) {
            LOGGER.warn("BGM file not found on the server: {}", BGMFileManager.getBGMDir() + "/" + fileName);
            return null;
        }
        try {
            String md5 = BGMFileManager.getMD5(fileName);
            if (md5 == null) {
                LOGGER.warn("Could not read bgm file: {}", fileName);
            }
            return md5;
        } catch (IOException e) {
            LOGGER.warn("Could not compute the checksum of {}", fileName, e);
            return null;
        }
    }

    private static List<EntityPlayerMP> onlinePlayers() {
        if (FMLCommonHandler.instance().getMinecraftServerInstance() == null
                || FMLCommonHandler.instance().getMinecraftServerInstance().getPlayerList() == null) {
            return new ArrayList<>();
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
