package com.vbuser.genshin.client.renderer;

import com.vbuser.genshin.client.renderer.CloudVolumeTracker.Section;
import com.vbuser.genshin.client.renderer.CloudVolumeTracker.SectionKey;
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

    /**
     * Fine texels per coarse texel of the max-density grid. The shader uses the
     * coarse grid to prove that a whole box contains no cloud at all, so it can
     * jump over it instead of creeping through it with the fine step; inside a
     * box that may contain cloud it steps at most one fine texel at a time. That
     * is what makes a one texel thick cloud layer impossible to step over
     * without paying the fine step over the empty sky.
     */
    private static final int MAX_GROUP = 8;

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

    /** Coarse max-density grid: {@link #MAX_GROUP} fine texels per texel. */
    private int maxTexture = -1;
    private ByteBuffer maxCells;
    private int maxDimX;
    private int maxDimY;
    private int maxDimZ;

    private ByteBuffer uploadScratch;
    private ByteBuffer maxUploadScratch;

    private final java.util.Set<SectionKey> pending = new java.util.LinkedHashSet<>();
    private boolean active;
    private boolean supported;
    private boolean clamped;
    private long lastUploadNanos;
    /** Timestamp of the last accepted resize, the interval throttle uses it. */
    private long lastReallocateStampNanos;
    /** Measured cost of the last resize, including the whole texture upload. */
    private long lastResizeNanos;
    /** Measured cost of the last whole 3D texture upload ({@link #createTexture}). */
    private long lastTextureUploadNanos;
    /** Measured cost of the last overlap copy during a resize. */
    private long lastOverlapCopyNanos;
    /** Measured cost of the last whole {@link #update} call. */
    private long lastRebuildNanos;

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

    /** Coarse max-density texture, or -1 before the first build. */
    public int getMaxTexture() {
        return this.maxTexture;
    }

    public int getMaxDimX() {
        return this.maxDimX;
    }

    public int getMaxDimY() {
        return this.maxDimY;
    }

    public int getMaxDimZ() {
        return this.maxDimZ;
    }

    /** Edge of one coarse max texel, in blocks. */
    public int getMaxCellBlocks() {
        return this.cellSize * MAX_GROUP;
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
        if (this.maxTexture != -1) {
            GL11.glDeleteTextures(this.maxTexture);
            this.maxTexture = -1;
        }
        this.cells = null;
        this.maxCells = null;
        this.pending.clear();
        this.active = false;
        this.dimension = Integer.MIN_VALUE;
        this.dimX = this.dimY = this.dimZ = 0;
        this.maxDimX = this.maxDimY = this.maxDimZ = 0;
    }

    // ------------------------------------------------------------------
    // per frame update
    // ------------------------------------------------------------------

    /**
     * Brings the volume in sync with the tracker.
     *
     * <p>The budget starts before the resize path, because the reallocation,
     * the overlap copy and the whole texture upload are the heaviest steps of a
     * rebuild and used to run outside it. A resize found with no budget left is
     * deferred to a later frame (the existing interval throttle still applies;
     * the overlap is preserved, so the only effect is that the newest margin of
     * newly discovered cloud shows up a few frames late).</p>
     *
     * @return true when a usable volume texture exists for this dimension
     */
    public boolean update(int dimension, long budgetNanos) {
        if (!this.supported) {
            return false;
        }
        long frameStart = System.nanoTime();
        long deadline = frameStart + budgetNanos;
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
            if (this.cells == null || (System.nanoTime() < deadline && canReallocate())) {
                reallocate(dimension, cell, gx0, gy0, gz0, nx, ny, nz);
            }
            // else: keep the current texture for a few more frames. The overlap
            // is preserved anyway, so the only visible effect is that the newest
            // margin of newly discovered cloud shows up a few frames late.
        }
        if (resized && (dimension != this.dimension || cell != this.cellSize
                || gx0 != this.originX || gy0 != this.originY || gz0 != this.originZ
                || nx != this.dimX || ny != this.dimY || nz != this.dimZ)) {
            // A deferred resize still has the old geometry. Do not consume the
            // new region's dirty keys and apply them with the old origin/LOD;
            // that both corrupts texels and loses the only pending update.
            this.lastRebuildNanos = System.nanoTime() - frameStart;
            return this.active;
        }

        CloudVolumeTracker.drainDirtyKeys(dimension, this.pending::add);

        boolean uploaded = false;
        // One byte per texel: the row stride must not be padded to 4 bytes.
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
        GlStateManager.setActiveTexture(GL13.GL_TEXTURE0);
        try {
            java.util.Iterator<SectionKey> iterator = this.pending.iterator();
            int processed = 0;
            while (iterator.hasNext()) {
                // The residue of the resize and of the texture upload is
                // already in the budget, so a frame can not stack a resize on
                // top of a full per-frame section batch.
                if (processed >= SECTIONS_PER_FRAME || System.nanoTime() >= deadline) {
                    break;
                }
                SectionKey key = iterator.next();
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
        this.lastRebuildNanos = System.nanoTime() - frameStart;
        return true;
    }

    /**
     * Blanks the texture once the region has no cloud blocks left, so that a
     * later region cannot inherit stale texels from this one.
     *
     * <p>The CPU copy is zeroed in place: a fresh direct buffer of the same size
     * would only hand a few megabytes of garbage to the collector, and the whole
     * texture still has to be re-uploaded either way.</p>
     */
    private void clearTexture() {
        if (this.cells == null || this.fineTexture == -1) {
            return;
        }
        ByteBuffer cells = this.cells;
        for (int index = 0, limit = cells.limit(); index < limit; index++) {
            cells.put(index, (byte) 0);
        }
        if (this.maxCells != null) {
            ByteBuffer maxCells = this.maxCells;
            for (int index = 0, limit = maxCells.limit(); index < limit; index++) {
                maxCells.put(index, (byte) 0);
            }
        }
        this.pending.clear();
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
        GlStateManager.setActiveTexture(GL13.GL_TEXTURE0);
        try {
            createTexture();
            createMaxTexture();
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
        if (now - this.lastReallocateStampNanos < REALLOCATE_INTERVAL_NANOS) {
            return false;
        }
        this.lastReallocateStampNanos = now;
        return true;
    }

    /**
     * Resizes the volume, copying the texels that are still valid.
     *
     * <p>Rebuilding from scratch would blank the cloud for as long as the
     * per-frame budget needs to refill every section, which reads as the clouds
     * blinking out and fading back in. Copying the overlap means the cloud never
     * disappears; only the newly covered margin has to be filled in.</p>
     *
     * <p>The CPU copy and the GL texture are always kept identical, so a resize
     * that keeps the grid geometry (only the dimension changed) needs neither
     * the overlap copy nor the whole texture upload. A real resize still has to
     * re-upload the whole texture in one call; that cost is measured and
     * exposed through {@link #getLastTextureUploadNanos()} so it can be
     * accounted next to the frame budget.</p>
     */
    private void reallocate(int dimension, int cell, int gx0, int gy0, int gz0,
                            int nx, int ny, int nz) {
        long resizeStart = System.nanoTime();
        ByteBuffer previous = this.cells;
        ByteBuffer previousMax = this.maxCells;
        int previousCell = this.cellSize;
        int previousOriginX = this.originX;
        int previousOriginY = this.originY;
        int previousOriginZ = this.originZ;
        int previousDimX = this.dimX;
        int previousDimY = this.dimY;
        int previousDimZ = this.dimZ;
        int previousMaxDimX = this.maxDimX;
        int previousMaxDimY = this.maxDimY;
        int previousMaxDimZ = this.maxDimZ;

        boolean sameGrid = previous != null && this.fineTexture != -1
                && previousCell == cell
                && previousOriginX == gx0 && previousOriginY == gy0 && previousOriginZ == gz0
                && previousDimX == nx && previousDimY == ny && previousDimZ == nz;

        this.dimension = dimension;
        this.cellSize = cell;
        this.originX = gx0;
        this.originY = gy0;
        this.originZ = gz0;
        this.dimX = nx;
        this.dimY = ny;
        this.dimZ = nz;
        this.maxDimX = ceilDiv(nx, MAX_GROUP);
        this.maxDimY = ceilDiv(ny, MAX_GROUP);
        this.maxDimZ = ceilDiv(nz, MAX_GROUP);

        if (sameGrid) {
            // Same geometry: the CPU copy and the texture already hold exactly
            // the same texels, so reusing the buffer avoids both the copy and
            // the whole texture re-upload.
            this.cells = previous;
            this.active = true;
            this.lastOverlapCopyNanos = 0L;
            this.lastResizeNanos = System.nanoTime() - resizeStart;
            return;
        }

        long copyStart = System.nanoTime();
        this.cells = ByteBuffer.allocateDirect(nx * ny * nz);
        boolean copied = previous != null && previousCell == cell
                && DensityGridOps.copyOverlap(this.cells, this.originX, this.originY, this.originZ,
                this.dimX, this.dimY, this.dimZ, previous, previousOriginX, previousOriginY,
                previousOriginZ, previousDimX, previousDimY, previousDimZ, cell);
        this.lastOverlapCopyNanos = System.nanoTime() - copyStart;
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
                int bx = key.sx << 4;
                int by = key.sy << 4;
                int bz = key.sz << 4;
                if (bx >= oldBlockX0 && bx + 16 <= oldBlockX1
                        && by >= oldBlockY0 && by + 16 <= oldBlockY1
                        && bz >= oldBlockZ0 && bz + 16 <= oldBlockZ1) {
                    return;
                }
            }
            this.pending.add(key);
        });

        createTexture();
        // The coarse grid is small; copy its overlap when the grid stayed
        // aligned, otherwise rebuild it from the fine copy. Rebuilding reads the
        // whole CPU volume, so it is the fallback rather than the default.
        this.maxCells = ByteBuffer.allocateDirect(this.maxDimX * this.maxDimY * this.maxDimZ);
        int maxCell = cell * MAX_GROUP;
        boolean maxAligned = previousMax != null && previousCell == cell
                && (this.originX - previousOriginX) % maxCell == 0
                && (this.originY - previousOriginY) % maxCell == 0
                && (this.originZ - previousOriginZ) % maxCell == 0;
        boolean maxCopied = maxAligned && DensityGridOps.copyOverlap(
                this.maxCells, this.originX, this.originY, this.originZ,
                this.maxDimX, this.maxDimY, this.maxDimZ, previousMax,
                previousOriginX, previousOriginY, previousOriginZ,
                previousMaxDimX, previousMaxDimY, previousMaxDimZ, maxCell);
        if (!maxCopied) {
            rebuildMaxCells();
        }
        createMaxTexture();
        this.active = true;
        this.lastResizeNanos = System.nanoTime() - resizeStart;
    }

    private void createTexture() {
        long uploadStart = System.nanoTime();
        if (this.fineTexture == -1) {
            this.fineTexture = GL11.glGenTextures();
        }
        // One byte per texel: the row stride must not be padded to 4 bytes.
        // This matters for a grid whose X axis is not a multiple of four, which
        // happens at the coarser LOD cell sizes.
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, this.fineTexture);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL12.GL_TEXTURE_WRAP_R, GL12.GL_CLAMP_TO_EDGE);
        GL12.glTexImage3D(GL12.GL_TEXTURE_3D, 0, this.internalFormat,
                this.dimX, this.dimY, this.dimZ, 0, this.pixelFormat, GL11.GL_UNSIGNED_BYTE, this.cells);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
        this.lastTextureUploadNanos = System.nanoTime() - uploadStart;
    }

    /**
     * Uploads the coarse max grid. Nearest filtering on purpose: a value of 0
     * must mean "this box is provably empty", and interpolation would blur a
     * neighbour's cloud into an empty box (harmless) or a box's own value out
     * of itself (not harmless).
     */
    private void createMaxTexture() {
        if (this.maxCells == null || this.maxDimX <= 0 || this.maxDimY <= 0 || this.maxDimZ <= 0) {
            return;
        }
        if (this.maxTexture == -1) {
            this.maxTexture = GL11.glGenTextures();
        }
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, this.maxTexture);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL12.GL_TEXTURE_WRAP_R, GL12.GL_CLAMP_TO_EDGE);
        GL12.glTexImage3D(GL12.GL_TEXTURE_3D, 0, this.internalFormat,
                this.maxDimX, this.maxDimY, this.maxDimZ, 0, this.pixelFormat,
                GL11.GL_UNSIGNED_BYTE, this.maxCells);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
    }

    /** Recomputes the whole coarse grid from the fine CPU copy. */
    private void rebuildMaxCells() {
        if (this.maxCells == null) {
            return;
        }
        for (int mz = 0; mz < this.maxDimZ; mz++) {
            for (int my = 0; my < this.maxDimY; my++) {
                for (int mx = 0; mx < this.maxDimX; mx++) {
                    this.maxCells.put(maxIndex(mx, my, mz), (byte) maxInGroup(mx, my, mz));
                }
            }
        }
    }

    private int maxIndex(int x, int y, int z) {
        return x + this.maxDimX * (y + this.maxDimY * z);
    }

    /** Largest fine texel value in one coarse box (0 when the box is empty). */
    private int maxInGroup(int mx, int my, int mz) {
        int x0 = mx * MAX_GROUP;
        int y0 = my * MAX_GROUP;
        int z0 = mz * MAX_GROUP;
        int x1 = Math.min(x0 + MAX_GROUP, this.dimX);
        int y1 = Math.min(y0 + MAX_GROUP, this.dimY);
        int z1 = Math.min(z0 + MAX_GROUP, this.dimZ);
        int max = 0;
        for (int z = z0; z < z1; z++) {
            for (int y = y0; y < y1; y++) {
                for (int x = x0; x < x1; x++) {
                    int value = this.cells.get(index(x, y, z)) & 0xFF;
                    if (value > max) {
                        max = value;
                    }
                }
            }
        }
        return max;
    }

    // ------------------------------------------------------------------
    // section upload
    // ------------------------------------------------------------------

    private void applySection(SectionKey key) {
        Section section = CloudVolumeTracker.getSection(this.dimension, key);
        int baseBlockX = key.sx << 4;
        int baseBlockY = key.sy << 4;
        int baseBlockZ = key.sz << 4;

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
        updateMaxCells(cx0, cy0, cz0, cx1, cy1, cz1);
    }

    /**
     * Refreshes the coarse boxes touched by one fine box. A coarse box may
     * straddle two sections; whichever of the two arrives last recomputes it
     * from the full fine copy, so the result is the same in any load order.
     */
    private void updateMaxCells(int cx0, int cy0, int cz0, int cx1, int cy1, int cz1) {
        if (this.maxCells == null) {
            return;
        }
        int mx0 = cx0 / MAX_GROUP;
        int my0 = cy0 / MAX_GROUP;
        int mz0 = cz0 / MAX_GROUP;
        int mx1 = Math.min((cx1 - 1) / MAX_GROUP + 1, this.maxDimX);
        int my1 = Math.min((cy1 - 1) / MAX_GROUP + 1, this.maxDimY);
        int mz1 = Math.min((cz1 - 1) / MAX_GROUP + 1, this.maxDimZ);
        if (mx0 >= mx1 || my0 >= my1 || mz0 >= mz1) {
            return;
        }
        for (int mz = mz0; mz < mz1; mz++) {
            for (int my = my0; my < my1; my++) {
                for (int mx = mx0; mx < mx1; mx++) {
                    this.maxCells.put(maxIndex(mx, my, mz), (byte) maxInGroup(mx, my, mz));
                }
            }
        }
        uploadMax(mx0, my0, mz0, mx1 - mx0, my1 - my0, mz1 - mz0);
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

    /** Same as {@link #upload}, for the coarse max texture. */
    private void uploadMax(int x, int y, int z, int width, int height, int depth) {
        if (this.maxTexture == -1) {
            return;
        }
        int bytes = width * height * depth;
        ByteBuffer scratch = this.maxUploadScratch;
        if (scratch == null || scratch.capacity() < bytes) {
            scratch = ByteBuffer.allocateDirect(bytes);
            this.maxUploadScratch = scratch;
        }
        scratch.clear();
        for (int slice = 0; slice < depth; slice++) {
            int sourceZ = z + slice;
            for (int row = 0; row < height; row++) {
                int sourceY = y + row;
                int rowBase = x + this.maxDimX * (sourceY + this.maxDimY * sourceZ);
                for (int column = 0; column < width; column++) {
                    scratch.put(this.maxCells.get(rowBase + column));
                }
            }
        }
        scratch.flip();
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, this.maxTexture);
        GL12.glTexSubImage3D(GL12.GL_TEXTURE_3D, 0, x, y, z, width, height, depth,
                this.pixelFormat, GL11.GL_UNSIGNED_BYTE, scratch);
    }

    /** Nanoseconds of the last successful upload, used by {@code /cloud stats}. */
    public long getLastUploadNanos() {
        return this.lastUploadNanos;
    }

    /** Nanoseconds spent in the last whole {@code update} rebuild. */
    public long getLastRebuildNanos() {
        return this.lastRebuildNanos;
    }

    /** Nanoseconds spent in the last resize, including the whole texture re-upload. */
    public long getLastReallocateNanos() {
        return this.lastResizeNanos;
    }

    /**
     * Nanoseconds spent in the last full 3D texture upload. A resize has to
     * re-upload the whole texture with one call; the cost is exposed here so a
     * frame spike can be attributed to the upload instead of to a leak.
     */
    public long getLastTextureUploadNanos() {
        return this.lastTextureUploadNanos;
    }

    /** Nanoseconds spent copying the overlap during the last resize. */
    public long getLastOverlapCopyNanos() {
        return this.lastOverlapCopyNanos;
    }
}
