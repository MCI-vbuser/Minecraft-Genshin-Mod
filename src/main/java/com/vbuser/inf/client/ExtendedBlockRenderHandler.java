package com.vbuser.inf.client;

import com.vbuser.inf.InfiniteHeight;
import com.vbuser.inf.chunk.ColumnManager;
import com.vbuser.inf.chunk.ExtendedColumn;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockRendererDispatcher;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.opengl.GL11;

import java.util.Map;

@SideOnly(Side.CLIENT)
@Mod.EventBusSubscriber(modid = InfiniteHeight.MODID, value = Side.CLIENT)
public class ExtendedBlockRenderHandler {

    private static final int CHUNK_RADIUS = 6;
    private static final double MAX_DIST_SQ = 96.0 * 96.0;

    @SubscribeEvent
    public static void onRenderWorldLast(RenderWorldLastEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        Entity view = mc.getRenderViewEntity();
        World world = mc.world;
        if (view == null || world == null) return;

        float partial = event.getPartialTicks();
        double vx = view.lastTickPosX + (view.posX - view.lastTickPosX) * partial;
        double vy = view.lastTickPosY + (view.posY - view.lastTickPosY) * partial;
        double vz = view.lastTickPosZ + (view.posZ - view.lastTickPosZ) * partial;

        int minCX = view.chunkCoordX - CHUNK_RADIUS;
        int maxCX = view.chunkCoordX + CHUNK_RADIUS;
        int minCZ = view.chunkCoordZ - CHUNK_RADIUS;
        int maxCZ = view.chunkCoordZ + CHUNK_RADIUS;

        BlockRendererDispatcher dispatcher = mc.getBlockRendererDispatcher();
        BufferBuilder buf = Tessellator.getInstance().getBuffer();

        GlStateManager.pushMatrix();
        GlStateManager.translate(-vx, -vy, -vz);
        GlStateManager.disableLighting();
        GlStateManager.enableTexture2D();
        GlStateManager.enableCull();
        GlStateManager.enableAlpha();
        GlStateManager.disableBlend();
        GlStateManager.color(1f, 1f, 1f, 1f);
        mc.getTextureManager().bindTexture(TextureMap.LOCATION_BLOCKS_TEXTURE);

        buf.begin(GL11.GL_QUADS, DefaultVertexFormats.BLOCK);

        for (int cx = minCX; cx <= maxCX; cx++) {
            for (int cz = minCZ; cz <= maxCZ; cz++) {
                Chunk chunk = world.getChunkProvider().getLoadedChunk(cx, cz);
                if (chunk == null) continue;

                ExtendedColumn col = ColumnManager.getIfPresent(chunk);
                if (col == null || col.isEmpty()) continue;

                int baseX = cx << 4;
                int baseZ = cz << 4;

                for (Map.Entry<Integer, ExtendedBlockStorage> entry : col.getSections().entrySet()) {
                    ExtendedBlockStorage ebs = entry.getValue();
                    int baseY = entry.getKey() << 4;

                    for (int ly = 0; ly < 16; ly++) {
                        for (int lz = 0; lz < 16; lz++) {
                            for (int lx = 0; lx < 16; lx++) {
                                IBlockState state = ebs.get(lx, ly, lz);
                                if (state.getBlock() == Blocks.AIR) continue;

                                int wx = baseX + lx;
                                int wy = baseY + ly;
                                int wz = baseZ + lz;

                                double dx = wx + 0.5 - vx;
                                double dy = wy + 0.5 - vy;
                                double dz = wz + 0.5 - vz;
                                if (dx * dx + dy * dy + dz * dz > MAX_DIST_SQ) continue;

                                BlockPos pos = new BlockPos(wx, wy, wz);
                                try {
                                    dispatcher.renderBlock(state, pos, world, buf);
                                } catch (Throwable ignored) {

                                }
                            }
                        }
                    }
                }
            }
        }

        Tessellator.getInstance().draw();
        GlStateManager.popMatrix();
    }
}