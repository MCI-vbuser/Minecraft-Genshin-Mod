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

    private static final int CHUNK_RADIUS = 6;
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

        // Iterate chunks around the camera instead of every tile entity in the
        // world twice per frame. Extended tile entities live in the chunk's own
        // tileEntities map (World.setTileEntity -> Chunk.addTileEntity).
        List<TileEntity> visible = new ArrayList<>();
        for (int cx = view.chunkCoordX - CHUNK_RADIUS; cx <= view.chunkCoordX + CHUNK_RADIUS; cx++) {
            for (int cz = view.chunkCoordZ - CHUNK_RADIUS; cz <= view.chunkCoordZ + CHUNK_RADIUS; cz++) {
                Chunk chunk = world.getChunkProvider().getLoadedChunk(cx, cz);
                if (chunk == null) continue;

                for (TileEntity te : chunk.getTileEntityMap().values()) {
                    if (te.isInvalid()) continue;
                    BlockPos p = te.getPos();
                    if (!HeightConstants.isExtendedY(p.getY())) continue;

                    double dx = p.getX() + 0.5 - vx;
                    double dy = p.getY() + 0.5 - vy;
                    double dz = p.getZ() + 0.5 - vz;
                    if (dx * dx + dy * dy + dz * dz <= MAX_DIST_SQ) {
                        visible.add(te);
                    }
                }
            }
        }

        if (visible.isEmpty()) return;

        GlStateManager.pushMatrix();
        GlStateManager.translate(-vx, -vy, -vz);

        GlStateManager.disableLighting();
        GlStateManager.enableTexture2D();
        GlStateManager.enableCull();
        GlStateManager.enableAlpha();
        GlStateManager.disableBlend();
        GlStateManager.color(1f, 1f, 1f, 1f);
        mc.getTextureManager().bindTexture(TextureMap.LOCATION_BLOCKS_TEXTURE);

        for (TileEntity te : visible) {
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
