package com.vbuser.inf.client;

import com.vbuser.inf.HeightConstants;
import com.vbuser.inf.InfiniteHeight;
import com.vbuser.inf.chunk.ColumnManager;
import com.vbuser.inf.chunk.ExtendedColumn;
import com.vbuser.inf.light.LightRenderHook;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockRendererDispatcher;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.vertex.VertexBuffer;
import net.minecraft.entity.Entity;
import net.minecraft.init.Blocks;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.ChunkCache;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders the extended sections from baked vertex buffers, the same way vanilla
 * renders its own 16x16x16 sections (see {@code VboRenderList}).
 *
 * <p>Every (chunk, section) owns two {@link VertexBuffer}s - opaque and
 * translucent. They are built with the same {@code BlockRendererDispatcher}
 * call vanilla uses, from the same {@code ChunkCache}, and rebuilt when a block
 * or a light value inside them changes. The vertices therefore carry the same
 * per-vertex light and ambient occlusion as a vanilla chunk mesh.</p>
 *
 * <p>Visibility follows the vanilla settings instead of a hard coded radius:
 * the horizontal radius comes from {@code renderDistanceChunks}, and each
 * section is frustum culled before it is drawn. There is deliberately no
 * vertical radius - a build 300 blocks below the player has to stay visible
 * when looking down, exactly like the 0..255 region does.</p>
 */
@SideOnly(Side.CLIENT)
@Mod.EventBusSubscriber(modid = InfiniteHeight.MODID, value = Side.CLIENT)
public class ExtendedBlockRenderHandler {

    private static final int MAX_REBUILDS_PER_FRAME = 6;

    /** A section further away than the render distance plus this margin is dropped. */
    private static final int DISTANCE_MARGIN = 16;

    /** BLOCK vertex format: 3f position, 4ub color, 2f uv, 2s lightmap = 28 bytes. */
    private static final int BLOCK_STRIDE = 28;

    /** Cleanup walks every mesh, so it runs every N frames instead of every frame. */
    private static final int CLEANUP_INTERVAL = 20;

    private static final Map<Long, SectionMesh> MESHES = new HashMap<>();
    private static final LongSet DIRTY = new LongOpenHashSet();

    private static final List<SectionMesh> VISIBLE = new ArrayList<>();
    private static final Comparator<SectionMesh> FAR_FIRST =
            (a, b) -> Double.compare(b.distanceSq, a.distanceSq);

    private static int frameCounter;

    static {
        LightRenderHook.install((world, x, y, z) -> {
            if (world != Minecraft.getMinecraft().world) {
                return;
            }
            if (!HeightConstants.isExtendedWorld(world)) {
                return;
            }
            markSectionDirty(x >> 4, z >> 4, y >> 4);
            // A change on a section border also affects the neighbor's culling
            // and its baked neighbor sampling.
            if ((x & 15) == 0) {
                markSectionDirty((x >> 4) - 1, z >> 4, y >> 4);
            } else if ((x & 15) == 15) {
                markSectionDirty((x >> 4) + 1, z >> 4, y >> 4);
            }
            if ((z & 15) == 0) {
                markSectionDirty(x >> 4, (z >> 4) - 1, y >> 4);
            } else if ((z & 15) == 15) {
                markSectionDirty(x >> 4, (z >> 4) + 1, y >> 4);
            }
            if ((y & 15) == 0) {
                markSectionDirty(x >> 4, z >> 4, (y >> 4) - 1);
            } else if ((y & 15) == 15) {
                markSectionDirty(x >> 4, z >> 4, (y >> 4) + 1);
            }
        });
    }

    public static void markSectionDirty(int chunkX, int chunkZ, int sectionY) {
        DIRTY.add(key(chunkX, chunkZ, sectionY));
    }

    private static long key(int chunkX, int chunkZ, int sectionY) {
        return ((long) (chunkX & 0x3FFFFFF) << 38) | ((long) (chunkZ & 0x3FFFFFF) << 12)
                | (sectionY & 0xFFF);
    }

    /** Leaving a world / changing dimension: the GL buffers belong to that world. */
    @SubscribeEvent
    public static void onWorldUnload(WorldEvent.Unload event) {
        if (event.getWorld() != null && event.getWorld().isRemote) {
            clearAll();
        }
    }

    private static void clearAll() {
        for (SectionMesh mesh : MESHES.values()) {
            mesh.delete();
        }
        MESHES.clear();
        DIRTY.clear();
        VISIBLE.clear();
    }

    @SubscribeEvent
    public static void onRenderWorldLast(RenderWorldLastEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        Entity view = mc.getRenderViewEntity();
        World world = mc.world;
        if (view == null || world == null) {
            return;
        }
        if (!HeightConstants.isExtendedWorld(world)) {
            if (!MESHES.isEmpty()) {
                clearAll();
            }
            return;
        }

        float partial = event.getPartialTicks();
        double vx = view.lastTickPosX + (view.posX - view.lastTickPosX) * partial;
        double vy = view.lastTickPosY + (view.posY - view.lastTickPosY) * partial;
        double vz = view.lastTickPosZ + (view.posZ - view.lastTickPosZ) * partial;

        // Vanilla setting, not a hard coded radius.
        int renderDistance = Math.max(2, mc.gameSettings.renderDistanceChunks);
        double maxDist = renderDistance * 16.0 + DISTANCE_MARGIN;
        double maxDistSq = maxDist * maxDist;

        Frustum frustum = new Frustum();
        frustum.setPosition(vx, vy, vz);

        int chunkRadius = renderDistance + 1;
        int minCX = view.chunkCoordX - chunkRadius;
        int maxCX = view.chunkCoordX + chunkRadius;
        int minCZ = view.chunkCoordZ - chunkRadius;
        int maxCZ = view.chunkCoordZ + chunkRadius;

        int rebuilt = 0;
        for (int cx = minCX; cx <= maxCX; cx++) {
            for (int cz = minCZ; cz <= maxCZ; cz++) {
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
                ExtendedColumn col = ColumnManager.getIfPresent(chunk);
                if (col == null || col.isEmpty()) {
                    continue;
                }
                for (Map.Entry<Integer, ExtendedBlockStorage> entry : col.getSections().entrySet()) {
                    int sectionY = entry.getKey();
                    double centerY = (sectionY << 4) + 8;
                    double dy = Math.max(Math.abs(centerY - vy) - 8, 0);
                    // Vertical distance does not cull: looking straight down at a
                    // build far below must still draw it. The frustum test below
                    // is what rejects off-screen sections.
                    long key = key(cx, cz, sectionY);
                    SectionMesh mesh = MESHES.get(key);
                    if (mesh == null) {
                        mesh = new SectionMesh(cx, cz, sectionY);
                        MESHES.put(key, mesh);
                        DIRTY.add(key);
                    }
                    mesh.distanceSq = dx * dx + dy * dy + dz * dz;
                    if (rebuilt < MAX_REBUILDS_PER_FRAME && DIRTY.contains(key)) {
                        rebuild(world, chunk, col, mesh);
                        DIRTY.remove(key);
                        rebuilt++;
                    }
                }
            }
        }

        if (++frameCounter % CLEANUP_INTERVAL == 0 || DIRTY.size() > 8192) {
            cleanup(world, vx, vz, maxDistSq);
        }

        if (MESHES.isEmpty()) {
            return;
        }

        VISIBLE.clear();
        double cullMargin = 16.0;
        for (SectionMesh mesh : MESHES.values()) {
            if (mesh.isEmptyBoth()) {
                continue;
            }
            double x0 = (mesh.chunkX << 4) - cullMargin;
            double z0 = (mesh.chunkZ << 4) - cullMargin;
            double y0 = (mesh.sectionY << 4) - cullMargin;
            if (!frustum.isBoxInFrustum(x0, y0, z0,
                    x0 + 16 + cullMargin * 2, y0 + 16 + cullMargin * 2, z0 + 16 + cullMargin * 2)) {
                continue;
            }
            VISIBLE.add(mesh);
        }
        if (VISIBLE.isEmpty()) {
            return;
        }

        GlStateManager.pushMatrix();
        GlStateManager.translate(-vx, -vy, -vz);
        mc.getTextureManager().bindTexture(TextureMap.LOCATION_BLOCKS_TEXTURE);
        mc.entityRenderer.enableLightmap();
        GlStateManager.disableLighting();
        GlStateManager.enableTexture2D();
        GlStateManager.enableCull();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);

        // The vertex arrays have to be enabled by hand here: vanilla does it in
        // RenderGlobal before it draws its own chunk layers.
        GlStateManager.glEnableClientState(GL11.GL_VERTEX_ARRAY);
        OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit);
        GlStateManager.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        OpenGlHelper.setClientActiveTexture(OpenGlHelper.lightmapTexUnit);
        GlStateManager.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit);
        GlStateManager.glEnableClientState(GL11.GL_COLOR_ARRAY);

        // The per-vertex AO lives in the color array, and the ambient
        // occlusion and light interpolation are only smooth when the shade
        // model is GL_SMOOTH. Whatever ran before this pass (hand, particles,
        // outline) may have left it at GL_FLAT, which collapses every quad to
        // one color - the "flat face with hard steps" look.
        GlStateManager.shadeModel(GL11.GL_SMOOTH);
        GlStateManager.enableAlpha();
        GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1F);
        GlStateManager.disableBlend();
        GlStateManager.depthMask(true);
        drawLayer(false);

        GlStateManager.disableAlpha();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(
                GlStateManager.SourceFactor.SRC_ALPHA,
                GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE,
                GlStateManager.DestFactor.ZERO);
        GlStateManager.depthMask(false);
        // Vanilla sorts its translucent render chunks back to front; without
        // this water and glass behind each other blend in arbitrary order.
        VISIBLE.sort(FAR_FIRST);
        drawLayer(true);
        GlStateManager.depthMask(true);
        GlStateManager.disableBlend();
        GlStateManager.enableAlpha();

        OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, 0);
        GlStateManager.glDisableClientState(GL11.GL_VERTEX_ARRAY);
        OpenGlHelper.setClientActiveTexture(OpenGlHelper.lightmapTexUnit);
        GlStateManager.glDisableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit);
        GlStateManager.glDisableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        GlStateManager.glDisableClientState(GL11.GL_COLOR_ARRAY);
        GlStateManager.resetColor();

        mc.entityRenderer.disableLightmap();
        GlStateManager.popMatrix();
    }

    /**
     * Drop meshes whose chunk/section disappeared or that drifted out of the
     * render distance. Runs on the render thread, where the GL buffers live.
     */
    private static void cleanup(World world, double vx, double vz, double maxDistSq) {
        if (MESHES.isEmpty()) {
            return;
        }
        double unloadSq = maxDistSq + 32 * 32;
        MESHES.entrySet().removeIf(e -> {
            SectionMesh mesh = e.getValue();
            double centerX = (mesh.chunkX << 4) + 8;
            double centerZ = (mesh.chunkZ << 4) + 8;
            double dx = Math.max(Math.abs(centerX - vx) - 8, 0);
            double dz = Math.max(Math.abs(centerZ - vz) - 8, 0);
            if (dx * dx + dz * dz > unloadSq) {
                mesh.delete();
                DIRTY.remove(e.getKey());
                return true;
            }
            Chunk chunk = world.getChunkProvider().getLoadedChunk(mesh.chunkX, mesh.chunkZ);
            if (chunk == null) {
                mesh.delete();
                DIRTY.remove(e.getKey());
                return true;
            }
            ExtendedColumn column = ColumnManager.getIfPresent(chunk);
            if (column == null || column.getSection(mesh.sectionY) == null) {
                mesh.delete();
                DIRTY.remove(e.getKey());
                return true;
            }
            return false;
        });
    }

    private static void drawLayer(boolean translucent) {
        for (SectionMesh mesh : VISIBLE) {
            VertexBuffer buffer = translucent ? mesh.translucent : mesh.opaque;
            if (mesh.isEmpty(translucent)) {
                continue;
            }
            buffer.bindBuffer();
            setupArrayPointers();
            buffer.drawArrays(GL11.GL_QUADS);
            buffer.unbindBuffer();
        }
    }

    /** Same as {@code VboRenderList.setupArrayPointers}. */
    private static void setupArrayPointers() {
        GlStateManager.glVertexPointer(3, GL11.GL_FLOAT, BLOCK_STRIDE, 0);
        GlStateManager.glColorPointer(4, GL11.GL_UNSIGNED_BYTE, BLOCK_STRIDE, 12);
        GlStateManager.glTexCoordPointer(2, GL11.GL_FLOAT, BLOCK_STRIDE, 16);
        OpenGlHelper.setClientActiveTexture(OpenGlHelper.lightmapTexUnit);
        GlStateManager.glTexCoordPointer(2, GL11.GL_SHORT, BLOCK_STRIDE, 24);
        OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit);
    }

    private static void rebuild(World world, Chunk chunk, ExtendedColumn col, SectionMesh mesh) {
        ExtendedBlockStorage ebs = col.getSection(mesh.sectionY);
        BitSet nonAir = col.getNonAir(mesh.sectionY);
        if (ebs == null || nonAir == null || nonAir.isEmpty()) {
            mesh.reset(0, 0);
            return;
        }

        int baseX = chunk.x << 4;
        int baseZ = chunk.z << 4;
        int baseY = mesh.sectionY << 4;

        // Vanilla builds chunk meshes against a ChunkCache; the same thing gives
        // the same block, light and occlusion inputs here.
        ChunkCache cache = new ChunkCache(world,
                new BlockPos(baseX, 0, baseZ),
                new BlockPos(baseX + 15, 255, baseZ + 15), 1);

        BlockRendererDispatcher dispatcher = Minecraft.getMinecraft().getBlockRendererDispatcher();
        BufferBuilder buf = Tessellator.getInstance().getBuffer();

        int opaque = buildLayer(dispatcher, buf, cache, ebs, nonAir, baseX, baseY, baseZ, false);
        mesh.upload(false, buf);
        int translucent = buildLayer(dispatcher, buf, cache, ebs, nonAir, baseX, baseY, baseZ, true);
        mesh.upload(true, buf);
        mesh.reset(opaque, translucent);
    }

    private static int buildLayer(BlockRendererDispatcher dispatcher, BufferBuilder buf,
                                  ChunkCache cache, ExtendedBlockStorage ebs, BitSet nonAir,
                                  int baseX, int baseY, int baseZ, boolean translucent) {
        buf.begin(GL11.GL_QUADS, DefaultVertexFormats.BLOCK);
        int rendered = 0;
        for (int index = nonAir.nextSetBit(0); index >= 0; index = nonAir.nextSetBit(index + 1)) {
            int lx = ExtendedColumn.indexX(index);
            int ly = ExtendedColumn.indexY(index);
            int lz = ExtendedColumn.indexZ(index);
            IBlockState state = ebs.get(lx, ly, lz);
            if (state.getBlock() == Blocks.AIR) {
                continue;
            }
            boolean isTranslucent = state.getBlock().getBlockLayer() == BlockRenderLayer.TRANSLUCENT;
            if (isTranslucent != translucent) {
                continue;
            }
            try {
                if (dispatcher.renderBlock(state,
                        new BlockPos(baseX + lx, baseY + ly, baseZ + lz), cache, buf)) {
                    rendered++;
                }
            } catch (Throwable ignored) {
                // A broken modded model must not take the whole pass down.
            }
        }
        buf.finishDrawing();

        return rendered;
    }

    /** One baked section, mirroring what vanilla keeps per RenderChunk layer. */
    private static final class SectionMesh {
        final int chunkX;
        final int chunkZ;
        final int sectionY;
        final VertexBuffer opaque = new VertexBuffer(DefaultVertexFormats.BLOCK);
        final VertexBuffer translucent = new VertexBuffer(DefaultVertexFormats.BLOCK);
        int opaqueBlocks;
        int translucentBlocks;
        double distanceSq;

        SectionMesh(int chunkX, int chunkZ, int sectionY) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.sectionY = sectionY;
        }

        boolean isEmpty(boolean translucentLayer) {
            return (translucentLayer ? this.translucentBlocks : this.opaqueBlocks) <= 0;
        }

        boolean isEmptyBoth() {
            return this.opaqueBlocks <= 0 && this.translucentBlocks <= 0;
        }

        void reset(int opaqueCount, int translucentCount) {
            this.opaqueBlocks = opaqueCount;
            this.translucentBlocks = translucentCount;
        }

        void upload(boolean translucentLayer, BufferBuilder buf) {
            VertexBuffer target = translucentLayer ? this.translucent : this.opaque;
            target.bufferData(buf.getByteBuffer());
        }

        void delete() {
            this.opaque.deleteGlBuffers();
            this.translucent.deleteGlBuffers();
        }
    }
}
