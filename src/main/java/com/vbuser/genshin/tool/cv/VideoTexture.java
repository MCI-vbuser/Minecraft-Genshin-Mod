package com.vbuser.genshin.tool.cv;

import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import java.nio.IntBuffer;

public class VideoTexture implements AutoCloseable {
    private final int textureId;
    private final int width, height;

    public VideoTexture(int width, int height) {
        this.width = width;
        this.height = height;
        textureId = GL11.glGenTextures();
        GlStateManager.bindTexture(textureId);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
    }

    public void uploadFrame(int[] pixels) {
        GlStateManager.bindTexture(textureId);
        IntBuffer buffer = BufferUtils.createIntBuffer(pixels.length);
        buffer.put(pixels);
        buffer.flip();
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, width, height,
                GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, buffer);
    }

    public void bind() {
        GlStateManager.bindTexture(textureId);
    }

    public int getTextureId() { return textureId; }

    @Override
    public void close() {
        GL11.glDeleteTextures(textureId);
    }
}
