package com.vbuser.genshin.client.renderer;

import com.vbuser.genshin.client.renderer.CloudVolumeTracker.Section;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;

import java.nio.ByteBuffer;

/**
 * The cloud block field as a 3D texture.
 *
 * <p>One texel covers {@link #getCellSize()} blocks (1 whenever the region fits
 * the 3D texture limit), the value is the block density scaled to 0..255. The
 * raymarcher samples it with hardware trilinear filtering, which is both far
 * cheaper and far more faithful than the procedural fBm the old shader
 * evaluated per step.</p>
 *
 * <p>Rebuilds are incremental: the tracker reports dirty chunk sections, each
 * section maps to a small aligned box of texels, and the box is recomputed and
 * uploaded with {@code glTexSubImage3D}. The first build (or a region resize)
 * queues every section and drains a budget per frame, so joining a world with a
 * large cloud region never stalls a frame.</p>
 */
public final class CloudDensityVolume {

    /**
     * Hard cap on the fine texture axis. Anything bigger is averaged down, which
     * both keeps the texture small (a 300x40x300 cloud region is 0.8 MB at
     * 2 blocks per texel instead of 5.6 MB) and removes the block lattice from
     * the surface, exactly the LOD trade-off the raymarcher wants.
     */
    private static final int AXIS_LIMIT = 256;
    /** Hard cap on total texels, keeps the CPU copy below ~24 MB. */
    private static final long CELLS_LIMIT = 24L * 1024L * 1024L;
    /** Blocks of padding around the region, avoids a rebuild per block edit. */
    private static final int PADDING = 8;
    /** Sections processed per frame while draining the queue. */
    private static final int SECTIONS_PER_FRAME = 96;
    /** Minimum spacing between texture reallocations, nanoseconds. */
    private static final long REALLOCATE_INTERVAL_NANOS = 250_000_000L;

    private int maxAxis = AXIS_LIMIT;

    private int dimension = Integer.MIN_VALUE;
    private int cellSize = 1;
    private int originX;
    private int originY;
    private int originZ;
    private int dimX;
    private int dimY;
    private int dimZ;

    private ByteBuffer cells;

    private int fineTexture = -1;
    private int internalFormat = GL11.GL_LUMINANCE8;
    private int pixelFormat = GL11.GL_LUMINANCE;

    private ByteBuffer uploadScratch;

    private final LongOpenHashSet pending = new LongOpenHashSet();
    private boolean active;
    private boolean supported;
    private boolean clamped;
    private long lastUploadNanos;
    private long lastReallocateNanos;

    private static final int GL_R8 = 0x8229;

    public CloudDensityVolume() {
        this.supported = true;
    }

    // ------------------------------------------------------------------
    // setup
    // ------------------------------------------------------------------

    public void init(int glMax3DTextureSize, boolean redChannelFormats) {
        this.maxAxis = Math.max(16, Math.min(AXIS_LIMIT, glMax3DTextureSize));
        if (redChannelFormats) {
            this.internalFormat = GL_R8;
            this.pixelFormat = GL11.GL_RED;
        } else {
            this.internalFormat = GL11.GL_LUMINANCE8;
            this.pixelFormat = GL11.GL_LUMINANCE;
        }
        this.supported = glMax3DTextureSize >= 16;
    }

    public boolean isSupported() {
        return this.supported;
    }

    public boolean isActive() {
        return this.active;
    }

    public int getFineTexture() {
        return this.fineTexture;
    }

    public int getCellSize() {
        return this.cellSize;
    }

    public int getOriginX() {
        return this.originX;
    }

    public int getOriginY() {
        return this.originY;
    }

    public int getOriginZ() {
        return this.originZ;
    }

    public int getDimX() {
        return this.dimX;
    }

    public int getDimY() {
        return this.dimY;
    }

    public int getDimZ() {
        return this.dimZ;
    }

    /** Total number of texels in the volume. */
    public int getTexelCount() {
        return this.dimX * this.dimY * this.dimZ;
    }

    public void release() {
        if (this.fineTexture != -1) {
            GL11.glDeleteTextures(this.fineTexture);
            this.fineTexture = -1;
        }
        this.cells = null;
        this.pending.clear();
        this.active = false;
        this.dimension = Integer.MIN_VALUE;
        this.dimX = this.dimY = this.dimZ = 0;
    }

    // ------------------------------------------------------------------
    // per frame update
    // ------------------------------------------------------------------

    /**
     * Brings the volume in sync with the tracker.
     *
     * @return true when a usable volume texture exists for this dimension
     */
    public boolean update(int dimension, long budgetNanos) {
        if (!this.supported) {
            return false;
        }
        BlockPos min = CloudVolumeTracker.getMin(dimension);
        BlockPos max = CloudVolumeTracker.getMax(dimension);
        if (min == null || max == null) {
            if (this.active) {
                clearTexture();
            }
            this.active = false;
            return false;
        }

        // Grid origin/limits snapped to whole chunk sections so that a section
        // always maps onto a whole, aligned box of texels.
        int gx0 = Math.floorDiv(min.getX() - PADDING, 16) * 16;
        int gy0 = Math.floorDiv(min.getY() - PADDING, 16) * 16;
        int gz0 = Math.floorDiv(min.getZ() - PADDING, 16) * 16;
        int gx1 = ceilDiv(max.getX() + PADDING + 1, 16) * 16;
        int gy1 = ceilDiv(max.getY() + PADDING + 1, 16) * 16;
        int gz1 = ceilDiv(max.getZ() + PADDING + 1, 16) * 16;

        int cell = chooseCellSize(gx1 - gx0, gy1 - gy0, gz1 - gz0);
        int nx = (gx1 - gx0) / cell;
        int ny = (gy1 - gy0) / cell;
        int nz = (gz1 - gz0) / cell;
        if (nx > this.maxAxis) {
            nx = this.maxAxis;
            warnClamped();
        }
        if (ny > this.maxAxis) {
            ny = this.maxAxis;
            warnClamped();
        }
        if (nz > this.maxAxis) {
            nz = this.maxAxis;
            warnClamped();
        }

        boolean resized = dimension != this.dimension || cell != this.cellSize
                || gx0 != this.originX || gy0 != this.originY || gz0 != this.originZ
                || nx != this.dimX || ny != this.dimY || nz != this.dimZ;
        if (resized) {
            if (this.cells == null || canReallocate()) {
                reallocate(dimension, cell, gx0, gy0, gz0, nx, ny, nz);
            }
            // else: keep the current texture for a few more frames. The overlap
            // is preserved anyway, so the only visible effect is that the newest
            // margin of newly discovered cloud shows up a few frames late.
        }

        CloudVolumeTracker.drainDirtyKeys(dimension, this.pending::add);

        long start = System.nanoTime();
        boolean uploaded = false;
        // One byte per texel: the row stride must not be padded to 4 bytes.
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
        GlStateManager.setActiveTexture(GL13.GL_TEXTURE0);
        try {
            LongIterator iterator = this.pending.iterator();
            int processed = 0;
            while (iterator.hasNext()) {
                long key = iterator.nextLong();
                if (processed >= SECTIONS_PER_FRAME || System.nanoTime() - start > budgetNanos) {
                    break;
                }
                iterator.remove();
                applySection(key);
                uploaded = true;
                processed++;
            }
        } finally {
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
            GlStateManager.setActiveTexture(GL13.GL_TEXTURE0);
        }

        this.active = true;
        if (uploaded) {
            this.lastUploadNanos = System.nanoTime();
        }
        return true;
    }

    /**
     * Blanks the texture once the region has no cloud blocks left, so that a
     * later region cannot inherit stale texels from this one.
     */
    private void clearTexture() {
        if (this.cells == null || this.fineTexture == -1) {
            return;
        }
        this.cells = ByteBuffer.allocateDirect(this.cells.capacity());
        this.pending.clear();
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
        GlStateManager.setActiveTexture(GL13.GL_TEXTURE0);
        try {
            createTexture();
        } finally {
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
            GlStateManager.setActiveTexture(GL13.GL_TEXTURE0);
        }
    }

    private void warnClamped() {        if (!this.clamped) {
            this.clamped = true;
            System.err.println("[CloudDensityVolume] cloud region is too large for a 3D texture,"
                    + " the far side of it will not render (split the region or use a bigger GPU limit)");
        }
    }

    private int chooseCellSize(int extentX, int extentY, int extentZ) {
        int cell = 1;
        while (cell < 16) {
            int dx = ceilDiv(extentX, cell);
            int dy = ceilDiv(extentY, cell);
            int dz = ceilDiv(extentZ, cell);
            int maxSide = Math.max(dx, Math.max(dy, dz));
            long total = (long) dx * (long) dy * (long) dz;
            if (maxSide <= this.maxAxis && total <= CELLS_LIMIT) {
                break;
            }
            cell <<= 1;
        }
        return cell;
    }

    private static int ceilDiv(int value, int divisor) {
        return -Math.floorDiv(-value, divisor);
    }

    private boolean canReallocate() {
        long now = System.nanoTime();
        if (now - this.lastReallocateNanos < REALLOCATE_INTERVAL_NANOS) {
            return false;
        }
        this.lastReallocateNanos = now;
        return true;
    }

    /**
     * Resizes the volume, copying the texels that are still valid.
     *
     * <p>Rebuilding from scratch would blank the cloud for as long as the
     * per-frame budget needs to refill every section, which reads as the clouds
     * blinking out and fading back in. Copying the overlap means the cloud never
     * disappears; only the newly covered margin has to be filled in.</p>
     */
    private void reallocate(int dimension, int cell, int gx0, int gy0, int gz0,
                            int nx, int ny, int nz) {
        ByteBuffer previous = this.cells;
        int previousCell = this.cellSize;
        int previousOriginX = this.originX;
        int previousOriginY = this.originY;
        int previousOriginZ = this.originZ;
        int previousDimX = this.dimX;
        int previousDimY = this.dimY;
        int previousDimZ = this.dimZ;

        this.dimension = dimension;
        this.cellSize = cell;
        this.originX = gx0;
        this.originY = gy0;
        this.originZ = gz0;
        this.dimX = nx;
        this.dimY = ny;
        this.dimZ = nz;
        this.cells = ByteBuffer.allocateDirect(nx * ny * nz);

        boolean copied = previous != null && previousCell == cell
                && DensityGridOps.copyOverlap(this.cells, this.originX, this.originY, this.originZ,
                this.dimX, this.dimY, this.dimZ, previous, previousOriginX, previousOriginY,
                previousOriginZ, previousDimX, previousDimY, previousDimZ, cell);

        // Keep keys that were already queued locally; they are still pending.
        final boolean overlapComplete = copied;
        final int oldBlockX0 = previousOriginX;
        final int oldBlockX1 = previousOriginX + previousDimX * cell;
        final int oldBlockY0 = previousOriginY;
        final int oldBlockY1 = previousOriginY + previousDimY * cell;
        final int oldBlockZ0 = previousOriginZ;
        final int oldBlockZ1 = previousOriginZ + previousDimZ * cell;
        CloudVolumeTracker.collectSectionKeys(dimension, key -> {
            if (overlapComplete) {
                int bx = CloudVolumeTracker.keyX(key) << 4;
                int by = CloudVolumeTracker.keyY(key) << 4;
                int bz = CloudVolumeTracker.keyZ(key) << 4;
                if (bx >= oldBlockX0 && bx + 16 <= oldBlockX1
                        && by >= oldBlockY0 && by + 16 <= oldBlockY1
                        && bz >= oldBlockZ0 && bz + 16 <= oldBlockZ1) {
                    return;
                }
            }
            this.pending.add(key);
        });

        createTexture();
        this.active = true;
    }

    private void createTexture() {
        if (this.fineTexture == -1) {
            this.fineTexture = GL11.glGenTextures();
        }
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, this.fineTexture);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL12.GL_TEXTURE_WRAP_R, GL12.GL_CLAMP_TO_EDGE);
        GL12.glTexImage3D(GL12.GL_TEXTURE_3D, 0, this.internalFormat,
                this.dimX, this.dimY, this.dimZ, 0, this.pixelFormat, GL11.GL_UNSIGNED_BYTE, this.cells);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
    }

    // ------------------------------------------------------------------
    // section upload
    // ------------------------------------------------------------------

    private void applySection(long key) {
        Section section = CloudVolumeTracker.getSection(this.dimension, key);
        int baseBlockX = CloudVolumeTracker.keyX(key) << 4;
        int baseBlockY = CloudVolumeTracker.keyY(key) << 4;
        int baseBlockZ = CloudVolumeTracker.keyZ(key) << 4;

        int cellsPerAxis = 16 / this.cellSize;
        int cx0 = (baseBlockX - this.originX) / this.cellSize;
        int cy0 = (baseBlockY - this.originY) / this.cellSize;
        int cz0 = (baseBlockZ - this.originZ) / this.cellSize;

        int cx1 = Math.min(cx0 + cellsPerAxis, this.dimX);
        int cy1 = Math.min(cy0 + cellsPerAxis, this.dimY);
        int cz1 = Math.min(cz0 + cellsPerAxis, this.dimZ);
        cx0 = Math.max(cx0, 0);
        cy0 = Math.max(cy0, 0);
        cz0 = Math.max(cz0, 0);
        if (cx0 >= cx1 || cy0 >= cy1 || cz0 >= cz1) {
            return;
        }

        int width = cx1 - cx0;
        int height = cy1 - cy0;
        int depth = cz1 - cz0;

        for (int cz = cz0; cz < cz1; cz++) {
            for (int cy = cy0; cy < cy1; cy++) {
                for (int cx = cx0; cx < cx1; cx++) {
                    int value = section == null ? 0 : sampleCell(section, cx, cy, cz);
                    this.cells.put(index(cx, cy, cz), (byte) value);
                }
            }
        }

        upload(this.cells, this.dimX, this.dimY, cx0, cy0, cz0, width, height, depth);
    }

    private int index(int x, int y, int z) {
        return x + this.dimX * (y + this.dimY * z);
    }

    /** Block density (0..255) of one texel, averaged over the cell's blocks. */
    private int sampleCell(Section section, int cx, int cy, int cz) {
        int cell = this.cellSize;
        int localX = this.originX + cx * cell - section.baseX();
        int localY = this.originY + cy * cell - section.baseY();
        int localZ = this.originZ + cz * cell - section.baseZ();
        if (cell == 1) {
            return (section.density[Section.index(localX, localY, localZ)] & 0xFF) * 17;
        }
        if (localX < 0 || localY < 0 || localZ < 0
                || localX + cell > 16 || localY + cell > 16 || localZ + cell > 16) {
            return 0;
        }
        int sum = 0;
        for (int z = 0; z < cell; z++) {
            for (int y = 0; y < cell; y++) {
                for (int x = 0; x < cell; x++) {
                    sum += section.density[Section.index(localX + x, localY + y, localZ + z)] & 0xFF;
                }
            }
        }
        // 0..15 average scaled to 0..255 without losing the low end
        return (sum * 255) / (cell * cell * cell * 15);
    }

    /** Gathers a box out of the CPU copy and uploads it with glTexSubImage3D. */
    private void upload(ByteBuffer source, int sourceWidth, int sourceHeight,
                        int x, int y, int z, int width, int height, int depth) {
        int bytes = width * height * depth;
        ByteBuffer scratch = this.uploadScratch;
        if (scratch == null || scratch.capacity() < bytes) {
            scratch = ByteBuffer.allocateDirect(bytes);
            this.uploadScratch = scratch;
        }
        scratch.clear();
        for (int slice = 0; slice < depth; slice++) {
            int sourceZ = z + slice;
            for (int row = 0; row < height; row++) {
                int sourceY = y + row;
                int rowBase = x + sourceWidth * (sourceY + sourceHeight * sourceZ);
                for (int column = 0; column < width; column++) {
                    scratch.put(source.get(rowBase + column));
                }
            }
        }
        scratch.flip();
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, this.fineTexture);
        GL12.glTexSubImage3D(GL12.GL_TEXTURE_3D, 0, x, y, z, width, height, depth,
                this.pixelFormat, GL11.GL_UNSIGNED_BYTE, scratch);
    }

    /** Nanoseconds of the last successful upload, used by {@code /cloud stats}. */
    public long getLastUploadNanos() {
        return this.lastUploadNanos;
    }
}
