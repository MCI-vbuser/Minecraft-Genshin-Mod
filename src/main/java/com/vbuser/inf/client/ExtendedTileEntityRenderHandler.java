package com.vbuser.inf.client;

import com.vbuser.inf.HeightConstants;
import com.vbuser.inf.InfiniteHeight;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.entity.Entity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
@Mod.EventBusSubscriber(modid = InfiniteHeight.MODID, value = Side.CLIENT)
public class ExtendedTileEntityRenderHandler {

    private static final double MAX_DIST_SQ = 96.0 * 96.0;

    @SubscribeEvent
    public static void onRenderWorldLast(RenderWorldLastEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        World world = mc.world;
        Entity view = mc.getRenderViewEntity();
        if (world == null || view == null) return;

        float partial = event.getPartialTicks();
        double vx = view.lastTickPosX + (view.posX - view.lastTickPosX) * partial;
        double vy = view.lastTickPosY + (view.posY - view.lastTickPosY) * partial;
        double vz = view.lastTickPosZ + (view.posZ - view.lastTickPosZ) * partial;

        boolean any = false;
        for (TileEntity te : world.loadedTileEntityList) {
            if (te.isInvalid()) continue;
            BlockPos p = te.getPos();
            if (!HeightConstants.isExtendedY(p.getY())) continue;
            double dx = p.getX() + 0.5 - vx;
            double dy = p.getY() + 0.5 - vy;
            double dz = p.getZ() + 0.5 - vz;
            if (dx * dx + dy * dy + dz * dz <= MAX_DIST_SQ) { any = true; break; }
        }
        if (!any) return;

        GlStateManager.pushMatrix();
        GlStateManager.translate(-vx, -vy, -vz);

        GlStateManager.disableLighting();
        GlStateManager.enableTexture2D();
        GlStateManager.enableCull();
        GlStateManager.enableAlpha();
        GlStateManager.disableBlend();
        GlStateManager.color(1f, 1f, 1f, 1f);
        mc.getTextureManager().bindTexture(TextureMap.LOCATION_BLOCKS_TEXTURE);

        for (TileEntity te : world.loadedTileEntityList) {
            if (te.isInvalid()) continue;
            BlockPos p = te.getPos();
            if (!HeightConstants.isExtendedY(p.getY())) continue;

            double dx = p.getX() + 0.5 - vx;
            double dy = p.getY() + 0.5 - vy;
            double dz = p.getZ() + 0.5 - vz;
            if (dx * dx + dy * dy + dz * dz > MAX_DIST_SQ) continue;

            try {
                TileEntityRendererDispatcher.instance.render(
                        te, p.getX(), p.getY(), p.getZ(), partial);
            } catch (Throwable ignored) {

            }
        }

        GlStateManager.popMatrix();
    }
}