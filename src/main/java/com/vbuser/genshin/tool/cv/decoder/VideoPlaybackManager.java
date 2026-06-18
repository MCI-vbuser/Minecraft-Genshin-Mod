package com.vbuser.genshin.tool.cv.decoder;

import com.vbuser.genshin.tool.cv.GuiVideoOverlay;
import com.vbuser.genshin.tool.cv.network.RequestCGFilePacket;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.FMLCommonHandler;

import java.io.File;

public class VideoPlaybackManager {
    private static final VideoPlaybackManager INSTANCE = new VideoPlaybackManager();
    private IVideoFrameProvider currentPlayer;
    private VideoTexture currentTexture;
    private boolean textureReady = false;

    public static VideoPlaybackManager getInstance() { return INSTANCE; }

    private GuiVideoOverlay overlayGui;

    public void playVideo(String fileName) {
        stopVideo();
        if (fileName.contains("..")) {
            return;
        }
        if (FMLCommonHandler.instance().getEffectiveSide().isClient()) {
            File clientFile = new File("cg/" + fileName);
            if (clientFile.exists()) {
                startLocalPlayback(fileName);
            } else {
                RequestCGFilePacket.send(fileName);
            }
        } else {
            throw new IllegalStateException("Server cannot directly play video, use CGAPI.playCG(player, fileName) instead.");
        }
    }

    private void startLocalPlayback(String fileName) {
        String fullPath = "cg/" + fileName;
        Minecraft.getMinecraft().addScheduledTask(() -> {
            if (overlayGui == null) {
                overlayGui = new GuiVideoOverlay();
                Minecraft.getMinecraft().displayGuiScreen(overlayGui);
            }
        });
        FXVideoPlayer player = new FXVideoPlayer(fullPath);
        player.start();
        currentPlayer = player;
        textureReady = false;
    }

    public void stopPlayerOnly() {
        if (currentPlayer != null) {
            currentPlayer.stop();
            currentPlayer = null;
        }
        Minecraft.getMinecraft().addScheduledTask(() -> {
            if (currentTexture != null) {
                currentTexture.close();
                currentTexture = null;
            }
            textureReady = false;
        });
    }

    public void stopVideo() {
        if (currentPlayer != null) {
            currentPlayer.stop();
            currentPlayer = null;
        }
        Minecraft.getMinecraft().addScheduledTask(() -> {
            if (currentTexture != null) {
                currentTexture.close();
                currentTexture = null;
            }
            textureReady = false;
            if (overlayGui != null) {
                overlayGui.closeOverlay();
                overlayGui = null;
            }
        });
    }

    public boolean isVideoActive() {
        return currentPlayer != null && currentPlayer.isPlaying();
    }

    public boolean isTextureReady() {
        return textureReady;
    }

    public int getTextureId() {
        if (currentTexture != null) {
            currentTexture.bind();
            return currentTexture.getTextureId();
        }
        return -1;
    }

    public void updateTexture() {
        if (currentPlayer == null || !currentPlayer.isPlaying()) return;
        int[] frame = currentPlayer.getFramePixels();
        if (frame != null) {
            if (currentTexture == null) {
                currentTexture = new VideoTexture(currentPlayer.getWidth(), currentPlayer.getHeight());
                textureReady = true;
            }
            currentTexture.uploadFrame(frame);
        }
    }

    public int getVideoWidth() {
        return currentPlayer != null ? currentPlayer.getWidth() : 1;
    }
    public int getVideoHeight() {
        return currentPlayer != null ? currentPlayer.getHeight() : 1;
    }
}