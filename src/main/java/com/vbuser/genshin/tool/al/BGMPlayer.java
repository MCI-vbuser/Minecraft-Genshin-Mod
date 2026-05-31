package com.vbuser.genshin.tool.al;

import com.vbuser.genshin.tool.al.network.RequestBGMPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.SoundHandler;
import net.minecraft.client.audio.SoundManager;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import paulscode.sound.SoundSystem;
import paulscode.sound.SoundSystemConfig;

import java.io.File;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

@SuppressWarnings("unused")
@SideOnly(Side.CLIENT)
public class BGMPlayer {
    private static BGMPlayer instance;
    private SoundSystem sndSystem;
    private boolean initialized = false;
    private String currentSource = null;
    private String pendingFileName = null;
    private float volume = 1.0f;
    private boolean isDownloading = false;

    private final Map<String, Map<Integer, byte[]>> downloadBuffers = new HashMap<>();
    @SuppressWarnings("all")
    private final Map<String, Integer> downloadTotals = new HashMap<>();

    public static BGMPlayer getInstance() {
        if (instance == null) {
            instance = new BGMPlayer();
            instance.init();
        }
        return instance;
    }

    private void init() {
        try {
            SoundHandler handler = Minecraft.getMinecraft().getSoundHandler();
            SoundManager sndManager = null;
            for (Field field : SoundHandler.class.getDeclaredFields()) {
                if (SoundManager.class.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    sndManager = (SoundManager) field.get(handler);
                    break;
                }
            }
            if (sndManager == null) {
                throw new RuntimeException("Failed to access SoundManager");
            }
            for (Field field : SoundManager.class.getDeclaredFields()) {
                if (SoundSystem.class.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    sndSystem = (SoundSystem) field.get(sndManager);
                    break;
                }
            }
            if (sndSystem == null) {
                throw new RuntimeException("Failed to access SoundSystem");
            }

            initialized = true;
        } catch (Exception e) {
            System.err.println("[BGMPlayer] Failed to initialize Paulscode SoundSystem. BGM playback will be disabled.");
            initialized = false;
        }
    }

    public void play(String fileName, String expectedMD5) {
        if (!initialized) {
            System.err.println("[BGMPlayer] Not initialized, cannot play BGM.");
            return;
        }
        stopCurrent();

        boolean needDownload = false;
        if (BGMFileManager.exists(fileName)) {
            try {
                String localMD5 = BGMFileManager.getMD5(fileName);
                if (expectedMD5 != null && !expectedMD5.isEmpty()) {
                    assert localMD5 != null;
                    if (!localMD5.equalsIgnoreCase(expectedMD5)) {
                        needDownload = true;
                    }
                }
            } catch (Exception ignored) {
                needDownload = true;
            }
        } else {
            needDownload = true;
        }

        if (needDownload) {
            pendingFileName = fileName;
            isDownloading = true;
            RequestBGMPacket.send(fileName);
        } else {
            startPlayback(fileName);
        }
    }

    private void startPlayback(String fileName) {
        Minecraft.getMinecraft().addScheduledTask(() -> {
            try {
                File file = BGMFileManager.getFile(fileName);
                if (!file.exists()) {
                    System.err.println("[BGMPlayer] File not found: " + file.getAbsolutePath());
                    return;
                }
                currentSource = "bgm_" + System.nanoTime();
                sndSystem.newStreamingSource(false, currentSource,
                        file.toURI().toURL(), file.getName(),
                        true, 0, 0, 0, SoundSystemConfig.ATTENUATION_NONE, 0);
                sndSystem.setVolume(currentSource, volume);
                sndSystem.play(currentSource);
            } catch (Exception e) {
                System.err.println("[BGMPlayer] Error starting playback: " + fileName);
            }
        });
    }

    public void stopCurrent() {
        if (!initialized || currentSource == null) return;
        sndSystem.stop(currentSource);
        sndSystem.removeSource(currentSource);
        currentSource = null;
        pendingFileName = null;
        isDownloading = false;
    }

    public void setVolume(float vol) {
        this.volume = vol;
        if (initialized && currentSource != null) sndSystem.setVolume(currentSource, vol);
    }

    public void receiveChunk(String fileName, int index, int total, byte[] data) {
        downloadBuffers.computeIfAbsent(fileName, k -> new HashMap<>()).put(index, data);
        downloadTotals.putIfAbsent(fileName, total);

        Map<Integer, byte[]> chunks = downloadBuffers.get(fileName);
        if (chunks.size() == total) {
            try {
                BGMFileManager.saveChunks(fileName, chunks, total);
                downloadBuffers.remove(fileName);
                downloadTotals.remove(fileName);
                onFileReady(fileName);
            } catch (Exception e) {
               // It just skipped but sth should be written here.
            }
        }
    }

    public void onFileReady(String fileName) {
        if (pendingFileName != null && pendingFileName.equals(fileName)) {
            isDownloading = false;
            startPlayback(fileName);
            pendingFileName = null;
        }
    }

    public boolean isWaitingFor(String fileName) {
        return isDownloading && fileName.equals(pendingFileName);
    }
}