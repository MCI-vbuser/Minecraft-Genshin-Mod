package com.vbuser.genshin.tool.cv;

import com.vbuser.genshin.tool.cv.decoder.VideoPlaybackManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.opengl.GL11;

public class PlayerEventHandler {

    @SubscribeEvent
    public void onRenderOverlay(RenderGameOverlayEvent.Post event) {
        if (event.getType() != RenderGameOverlayEvent.ElementType.ALL) return;

        VideoPlaybackManager manager = VideoPlaybackManager.getInstance();
        if (!manager.isVideoActive()) return;

        manager.updateTexture();

        if (!manager.isTextureReady()) return;

        GlStateManager.enableBlend();
        GlStateManager.disableAlpha();
        GlStateManager.disableDepth();
        GlStateManager.disableLighting();

        int texId = manager.getTextureId();
        if (texId == -1) return;

        GlStateManager.bindTexture(texId);

        Minecraft mc = Minecraft.getMinecraft();
        ScaledResolution sr = new ScaledResolution(mc);
        int screenW = sr.getScaledWidth();
        int screenH = sr.getScaledHeight();
        int videoW = manager.getVideoWidth();
        int videoH = manager.getVideoHeight();

        double scale = Math.max((double) screenW / videoW, (double) screenH / videoH);
        double u = screenW / (videoW * scale);
        double v = screenH / (videoH * scale);
        double left = (1.0 - u) / 2.0;
        double right = (1.0 + u) / 2.0;
        double bottom = 0.5 + v / 2.0;
        double top = 0.5 - v / 2.0;

        Tessellator tess = Tessellator.getInstance();
        BufferBuilder vb = tess.getBuffer();
        vb.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
        vb.pos(0, screenH, 0).tex(left, bottom).endVertex();
        vb.pos(screenW, screenH, 0).tex(right, bottom).endVertex();
        vb.pos(screenW, 0, 0).tex(right, top).endVertex();
        vb.pos(0, 0, 0).tex(left, top).endVertex();
        tess.draw();

        GlStateManager.enableAlpha();
        GlStateManager.disableBlend();
    }
}
