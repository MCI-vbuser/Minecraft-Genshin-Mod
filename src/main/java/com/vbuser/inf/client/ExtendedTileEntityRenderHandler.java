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
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.ArrayList;
import java.util.List;

@SideOnly(Side.CLIENT)
@Mod.EventBusSubscriber(modid = InfiniteHeight.MODID, value = Side.CLIENT)
public class ExtendedTileEntityRenderHandler {

    private static final List<TileEntity> VISIBLE = new ArrayList<>();

    @SubscribeEvent
    public static void onRenderWorldLast(RenderWorldLastEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        World world = mc.world;
        Entity view = mc.getRenderViewEntity();
        if (view == null || !HeightConstants.isExtendedWorld(world)) {
            VISIBLE.clear();
            return;
        }

        float partial = event.getPartialTicks();
        double vx = view.lastTickPosX + (view.posX - view.lastTickPosX) * partial;
        double vy = view.lastTickPosY + (view.posY - view.lastTickPosY) * partial;
        double vz = view.lastTickPosZ + (view.posZ - view.lastTickPosZ) * partial;

        // Same radius the block renderer and vanilla use.
        int radius = Math.max(2, mc.gameSettings.renderDistanceChunks) + 1;
        // Vertical is far more generous: looking down at a build below you is
        // the normal case for this mod, and frustum culling is not applied to
        // tile entities.
        double maxDistSq = (radius * 16.0 + 16) * (radius * 16.0 + 16);

        // Iterate chunks around the camera instead of every tile entity in the
        // world twice per frame. Extended tile entities live in the chunk's own
        // tileEntities map (World.setTileEntity -> Chunk.addTileEntity).
        VISIBLE.clear();
        for (int cx = view.chunkCoordX - radius; cx <= view.chunkCoordX + radius; cx++) {
            for (int cz = view.chunkCoordZ - radius; cz <= view.chunkCoordZ + radius; cz++) {
                double centerX = (cx << 4) + 8;
                double centerZ = (cz << 4) + 8;
                double dx = Math.max(Math.abs(centerX - vx) - 8, 0);
                double dz = Math.max(Math.abs(centerZ - vz) - 8, 0);
                if (dx * dx + dz * dz > maxDistSq) {
                    continue;
                }
                Chunk chunk = world.getChunkProvider().getLoadedChunk(cx, cz);
                if (chunk == null) {
                    continue;
                }
                for (TileEntity te : chunk.getTileEntityMap().values()) {
                    if (te.isInvalid()) {
                        continue;
                    }
                    BlockPos p = te.getPos();
                    if (!HeightConstants.isExtendedY(p.getY())) {
                        continue;
                    }
                    double ex = p.getX() + 0.5 - vx;
                    double ey = p.getY() + 0.5 - vy;
                    double ez = p.getZ() + 0.5 - vz;
                    if (ex * ex + ey * ey + ez * ez <= maxDistSq * 4) {
                        VISIBLE.add(te);
                    }
                }
            }
        }

        if (VISIBLE.isEmpty()) {
            return;
        }

        GlStateManager.pushMatrix();
        GlStateManager.translate(-vx, -vy, -vz);

        GlStateManager.disableLighting();
        GlStateManager.enableTexture2D();
        GlStateManager.enableCull();
        GlStateManager.enableAlpha();
        GlStateManager.disableBlend();
        GlStateManager.color(1f, 1f, 1f, 1f);
        mc.getTextureManager().bindTexture(TextureMap.LOCATION_BLOCKS_TEXTURE);

        for (TileEntity te : VISIBLE) {
            BlockPos p = te.getPos();
            try {
                TileEntityRendererDispatcher.instance.render(
                        te, p.getX(), p.getY(), p.getZ(), partial);
            } catch (Throwable ignored) {
            }
        }

        GlStateManager.popMatrix();
    }
}
