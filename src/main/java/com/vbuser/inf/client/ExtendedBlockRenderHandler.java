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
import net.minecraft.util.BlockRenderLayer;
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

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;

@SideOnly(Side.CLIENT)
@Mod.EventBusSubscriber(modid = InfiniteHeight.MODID, value = Side.CLIENT)
public class ExtendedBlockRenderHandler {

    private static final int CHUNK_RADIUS = 6;
    private static final int MAX_DIST = 96;
    private static final double MAX_DIST_SQ = MAX_DIST * MAX_DIST;

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

        List<RenderUnit> opaque = new ArrayList<>();
        List<RenderUnit> translucent = new ArrayList<>();

        for (int cx = minCX; cx <= maxCX; cx++) {
            for (int cz = minCZ; cz <= maxCZ; cz++) {
                Chunk chunk = world.getChunkProvider().getLoadedChunk(cx, cz);
                if (chunk == null) continue;

                ExtendedColumn col = ColumnManager.getIfPresent(chunk);
                if (col == null || col.isEmpty()) continue;

                int baseX = cx << 4;
                int baseZ = cz << 4;

                for (Map.Entry<Integer, ExtendedBlockStorage> entry : col.getSections().entrySet()) {
                    int sectionY = entry.getKey();
                    int baseY = sectionY << 4;

                    // Skip sections entirely outside the vertical view band
                    // before touching their 4096 slots.
                    if (baseY + 16 < vy - MAX_DIST || baseY > vy + MAX_DIST) continue;

                    ExtendedBlockStorage ebs = entry.getValue();
                    if (ebs.isEmpty()) continue;

                    BitSet nonAir = col.getNonAir(sectionY);
                    if (nonAir == null || nonAir.isEmpty()) continue;

                    double sx = Math.max(Math.abs(baseX + 8 - vx) - 8, 0);
                    double sy = Math.max(Math.abs(baseY + 8 - vy) - 8, 0);
                    double sz = Math.max(Math.abs(baseZ + 8 - vz) - 8, 0);
                    if (sx * sx + sy * sy + sz * sz > MAX_DIST_SQ) continue;

                    for (int index = nonAir.nextSetBit(0); index >= 0; index = nonAir.nextSetBit(index + 1)) {
                        int lx = ExtendedColumn.indexX(index);
                        int ly = ExtendedColumn.indexY(index);
                        int lz = ExtendedColumn.indexZ(index);

                        IBlockState state = ebs.get(lx, ly, lz);
                        if (state.getBlock() == Blocks.AIR) continue;

                        int wx = baseX + lx;
                        int wy = baseY + ly;
                        int wz = baseZ + lz;

                        double dx = wx + 0.5 - vx;
                        double dy = wy + 0.5 - vy;
                        double dz = wz + 0.5 - vz;
                        double distSq = dx * dx + dy * dy + dz * dz;
                        if (distSq > MAX_DIST_SQ) continue;

                        BlockPos pos = new BlockPos(wx, wy, wz);
                        BlockRenderLayer layer = state.getBlock().getBlockLayer();
                        RenderUnit unit = new RenderUnit(state, pos, distSq);

                        if (layer == BlockRenderLayer.TRANSLUCENT) {
                            translucent.add(unit);
                        } else {
                            opaque.add(unit);
                        }
                    }
                }
            }
        }

        if (opaque.isEmpty() && translucent.isEmpty()) return;

        translucent.sort((a, b) -> Double.compare(b.distSq, a.distSq));

        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();

        GlStateManager.pushMatrix();
        GlStateManager.translate(-vx, -vy, -vz);

        mc.getTextureManager().bindTexture(TextureMap.LOCATION_BLOCKS_TEXTURE);
        mc.entityRenderer.enableLightmap();

        if (!opaque.isEmpty()) {
            GlStateManager.disableLighting();
            GlStateManager.enableTexture2D();
            GlStateManager.enableAlpha();
            GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1F);
            GlStateManager.disableBlend();
            GlStateManager.enableCull();
            GlStateManager.depthMask(true);
            GlStateManager.color(1f, 1f, 1f, 1f);

            buf.begin(GL11.GL_QUADS, DefaultVertexFormats.BLOCK);
            for (RenderUnit u : opaque) {
                try {
                    dispatcher.renderBlock(u.state, u.pos, world, buf);
                } catch (Throwable ignored) {
                }
            }
            tess.draw();
        }

        if (!translucent.isEmpty()) {
            GlStateManager.disableLighting();
            GlStateManager.enableTexture2D();
            GlStateManager.disableAlpha();
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(
                    GlStateManager.SourceFactor.SRC_ALPHA,
                    GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                    GlStateManager.SourceFactor.ONE,
                    GlStateManager.DestFactor.ZERO);
            GlStateManager.depthMask(false);
            GlStateManager.enableCull();
            GlStateManager.color(1f, 1f, 1f, 1f);

            buf.begin(GL11.GL_QUADS, DefaultVertexFormats.BLOCK);
            for (RenderUnit u : translucent) {
                try {
                    dispatcher.renderBlock(u.state, u.pos, world, buf);
                } catch (Throwable ignored) {
                }
            }
            tess.draw();

            GlStateManager.depthMask(true);
            GlStateManager.disableBlend();
            GlStateManager.enableAlpha();
        }

        mc.entityRenderer.disableLightmap();
        GlStateManager.popMatrix();
    }

    private static final class RenderUnit {
        final IBlockState state;
        final BlockPos pos;
        final double distSq;

        RenderUnit(IBlockState state, BlockPos pos, double distSq) {
            this.state = state;
            this.pos = pos;
            this.distSq = distSq;
        }
    }
}
