package com.vbuser.genshin.debug;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ActiveRenderInfo;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

public class CameraPos {
    @SubscribeEvent
    public void onRenderOverlay(RenderGameOverlayEvent.Text event) {
        if (event.getType() != RenderGameOverlayEvent.ElementType.TEXT) return;

        Minecraft mc = Minecraft.getMinecraft();
        Entity viewEntity = mc.getRenderViewEntity();
        int view = mc.gameSettings.thirdPersonView;
        if (viewEntity == null) return;

        Vec3d camPos = ActiveRenderInfo.getCameraPosition().add(viewEntity.getPositionVector());

        float partialTicks = event.getPartialTicks();
        float yaw = viewEntity.prevRotationYaw + (viewEntity.rotationYaw - viewEntity.prevRotationYaw) * partialTicks;
        float pitch = viewEntity.prevRotationPitch + (viewEntity.rotationPitch - viewEntity.prevRotationPitch) * partialTicks;

        if (view == 2) {
            yaw += 180.0F;
            pitch = -pitch;
        }
        yaw = MathHelper.wrapDegrees(yaw);

        String text = String.format(
                "Camera Pos: %.2f / %.2f / %.2f   Yaw: %.1f   Pitch: %.1f",
                camPos.x, camPos.y, camPos.z, yaw, pitch
        );

        mc.fontRenderer.drawStringWithShadow(text, 2, 2, 0x00FF00);
    }
}
