package com.vbuser.inf.light;

import com.vbuser.inf.HeightConstants;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Light propagation for the extended (Y outside {@code 0..255}) region.
 *
 * <p>Vanilla's {@code World.checkLightFor} runs a BFS capped to a 17 block
 * Manhattan radius and re-reads {@code IBlockAccess} for every node. That cap
 * is fine for {@code 0..255} because vanilla calls the check for every position
 * between two height map values, but a single call can never darken a deep
 * shaft - the darkness has to travel the whole depth - and running it per
 * position is far too expensive.</p>
 *
 * <p>This engine runs its own add/remove BFS over a queue drained with a per
 * tick budget, so a large relight is spread over a few ticks instead of
 * stalling one. It owns the extended cells only; {@code 0..255} stays vanilla's
 * business and is only read - and at the seams written - as a light source.</p>
 */
public final class ExtendedLightEngine {

    /** Sections seeded per tick; a whole chunk is done in one tick. */
    private static final int MAX_SECTIONS_PER_TICK = 48;

    private static final int[] DX = {1, -1, 0, 0, 0, 0};
    private static final int[] DY = {0, 0, 1, -1, 0, 0};
    private static final int[] DZ = {0, 0, 0, 0, 1, -1};

    private final World world;
    private final BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();


    private final NodeQueue skyAdd = new NodeQueue();
    private final NodeQueue skyRemove = new NodeQueue();
    private final NodeQueue blockAdd = new NodeQueue();
    private final NodeQueue blockRemove = new NodeQueue();

    /** Sections whose initial light still has to be seeded: {@code {chunkX, sectionY, chunkZ}}. */
    private final ConcurrentLinkedQueue<int[]> pendingSections = new ConcurrentLinkedQueue<>();

    /** Section keys already queued, so the cascade below cannot explode. */
    private final java.util.Set<Long> scheduledSections =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());


    /** Cells whose light has to be recomputed once the queues settled. */
    private final NodeQueue dirty = new NodeQueue();

    /**
     * Columns that were rebuilt from block data and may be marked trusted once
     * the engine has no work left. Weak so an unloaded chunk can never keep its
     * column alive through this set.
     */
    private final java.util.Set<ColumnLight> trustPending =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    // One entry cache for chunk lookups; only valid inside a single BFS run.
    private int cacheChunkX = Integer.MIN_VALUE;
    private int cacheChunkZ = Integer.MIN_VALUE;
    private ColumnLight cacheColumn;
    private boolean cacheValid;

    public ExtendedLightEngine(World world) {
        this.world = world;
    }

    // ------------------------------------------------------------------
    // Queue
    // ------------------------------------------------------------------

    private static final class NodeQueue {
        int[] data = new int[4 * 256];
        int read;
        int end;

        boolean isEmpty() {
            return this.read >= this.end;
        }

        void push(int x, int y, int z, int value) {
            if (this.end + 4 > this.data.length) {
                this.data = Arrays.copyOf(this.data, this.data.length * 2);
            }
            int i = this.end;
            this.data[i] = x;
            this.data[i + 1] = y;
            this.data[i + 2] = z;
            this.data[i + 3] = value;
            this.end = i + 4;
        }

        void compact() {
            if (this.read == 0) {
                return;
            }
            int remaining = this.end - this.read;
            if (remaining > 0) {
                System.arraycopy(this.data, this.read, this.data, 0, remaining);
            }
            this.read = 0;
            this.end = remaining;
            if (this.data.length > 4 * 4096 && remaining * 8 < this.data.length) {
                this.data = Arrays.copyOf(this.data, Math.max(4 * 256, this.data.length / 2));
            }
        }

    }

    // ------------------------------------------------------------------
    // Public update entry points
    // ------------------------------------------------------------------

    /** Re-derive the light of one extended cell from its neighbors and its own block. */
    public void updateCell(int x, int y, int z) {
        if (!HeightConstants.isExtendedY(y)) {
            return;
        }
        // Deferred: when a block changes, its neighbors still hold the values
        // from before the change, so computing now mixes old and new state and
        // the result depends on the order the cells were visited. Queue instead
        // and recompute once the removal/add queues are empty.
        this.dirty.push(x, y, z, 0);
    }

    /**
     * A block at {@code (x,y,z)} changed: its own light and the light of the
     * surrounding cells may have to be recomputed.
     */
    public void updateCubeNeighbourhood(int x, int y, int z) {
        updateCell(x, y, z);
        for (int d = 0; d < 6; d++) {
            int ny = y + DY[d];
            if (HeightConstants.isExtendedY(ny)) {
                updateCell(x + DX[d], ny, z + DZ[d]);
            }
        }
    }

    /**
     * The sky visibility of a whole block column changed (a roof was added or
     * removed, or the vanilla height map emptied).
     *
     * <p>The cells of the column itself follow the implicit column value on
     * their own, so the work is in the stored cells: the column's own arrays,
     * and - this is the important part - the cells of the four horizontal
     * neighbors, which may still hold light that used to come from this
     * column. Every stored cell between the two tops is re-derived and handed
     * to the BFS, which then carries the change outwards.</p>
     */
    public void onColumnTopChanged(int x, int z, int oldTop, int newTop) {
        int from = oldTop == ColumnLight.NO_TOP ? Integer.MIN_VALUE : oldTop;
        int to = newTop == ColumnLight.NO_TOP ? Integer.MIN_VALUE : newTop;
        int low = Math.min(from, to);
        int high = Math.max(from, to);
        if (low == high) {
            return;
        }

        ColumnLight centre = columnAt(x, z);
        if (centre == null) {
            return;
        }

        // The band only has to be walked where light can actually be stored,
        // which is where this column or one of its horizontal neighbors has a
        // materialized block or light section.
        int minSection = Integer.MAX_VALUE;
        int maxSection = Integer.MIN_VALUE;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx != 0 && dz != 0) {
                    continue;
                }
                ColumnLight neighbour = columnAt(x + dx, z + dz);
                if (neighbour == null) {
                    continue;
                }
                for (int sectionY : neighbour.sectionYs()) {
                    if (sectionY < minSection) {
                        minSection = sectionY;
                    }
                    if (sectionY > maxSection) {
                        maxSection = sectionY;
                    }
                }
            }
        }
        if (minSection > maxSection) {
            return;
        }
        int y0 = Math.max(low, minSection << 4);
        int y1 = Math.min(high, (maxSection + 1) << 4);
        if (y0 >= y1) {
            return;
        }

        for (int y = y0; y < y1; y++) {
            if (!HeightConstants.isExtendedY(y)) {
                continue;
            }
            updateSkySource(centre, x, y, z);
            for (int d = 0; d < 4; d++) {
                // Horizontal only: the first four direction entries are +-x / +-z.
                int nx = x + DX[d];
                int nz = z + DZ[d];
                ColumnLight neighbour = columnAt(nx, nz);
                if (neighbour != null && !neighbour.isSuspended()) {
                    updateSkySource(neighbour, nx, y, nz);
                }
            }
        }
        // The vanilla mesh next to the seam bakes the seam light into its
        // vertices; when the whole column changes visibility nothing writes to
        // the seam array, so the rebuild has to be requested explicitly.
        if (low <= -1 && -1 < high) {
            notifySeamColumn(x, z, 0);
        }
        if (low <= 256 && 256 < high) {
            notifySeamColumn(x, z, 255);
        }
    }

    private void notifySeamColumn(int x, int z, int vanillaY) {
        if (!this.world.isRemote) {
            return;
        }
        BlockPos pos = new BlockPos(x, vanillaY, z);
        this.world.notifyLightSet(pos);
        this.world.markBlockRangeForRenderUpdate(pos, pos);
    }

    /**
     * Only look at the cell's own sky source, never at its neighbors: after a
     * column top change a whole band of cells changes at once, and evaluating
     * raw light against neighbors that still hold the old values makes removal
     * and re-addition fight each other (light that should be gone survives and
     * values end up arbitrary).
     *
     * <p>Cells that gain the sky are set to 15 and re-propagated. Cells that
     * lose it are zeroed and their old value goes to the removal BFS, which
     * carries the darkness outwards and re-adds whatever really comes from
     * somewhere else at its boundary.</p>
     */
    private void updateSkySource(ColumnLight column, int x, int y, int z) {
        if (!this.world.provider.hasSkyLight()) {
            return;
        }
        int old = column.getLightFor(EnumSkyBlock.SKY, x, y, z);
        if (column.canSeeSky(x, y, z)) {
            if (old != 15) {
                column.setLightFor(EnumSkyBlock.SKY, x, y, z, 15);
                this.skyAdd.push(x, y, z, 15);
            }
            return;
        }
        if (old > 0) {
            column.setLightFor(EnumSkyBlock.SKY, x, y, z, 0);
            this.skyRemove.push(x, y, z, old);
        }
    }

    /**
     * Seed one section: the cells that carry light in from outside (the shell)
     * and, where the section contains the sky/dark boundary of a column, that
     * boundary cell. Everything else is left at 0/15 and filled by the BFS.
     */
    public boolean initSection(ColumnLight column, int sectionY) {
        boolean changed = false;
        boolean sky = this.world.provider.hasSkyLight();
        int baseX = column.getChunk().x << 4;
        int baseZ = column.getChunk().z << 4;
        int baseY = sectionY << 4;

        for (int lx = 0; lx < 16; lx++) {
            int x = baseX + lx;
            for (int lz = 0; lz < 16; lz++) {
                int z = baseZ + lz;
                if (sky) {
                    int top = column.top(x, z);
                    // The first sky visible cell of this column is the only new
                    // light source the section can contain. Vanilla Y is never
                    // seeded: writing it would put values into the extended
                    // arrays for 0..255 and then mirror them into the vanilla
                    // arrays, which are not ours to own.
                    if (top != ColumnLight.NO_TOP && top >= baseY && top < baseY + 16
                            && HeightConstants.isExtendedY(top)) {
                        if (column.getLightFor(EnumSkyBlock.SKY, x, top, z) != 15) {
                            changed = true;
                        }
                        column.setLightFor(EnumSkyBlock.SKY, x, top, z, 15);
                        this.skyAdd.push(x, top, z, 15);
                    }
                }
            }
        }

        // Block light sources inside the section have to be re-seeded too: a
        // torch that survived the reload is not "placed" again, so nothing else
        // would ever make it emit, and its whole glow (the 1..3 fringe in
        // particular) would be missing.
        net.minecraft.world.chunk.storage.ExtendedBlockStorage storage = column.getColumn().getSection(sectionY);
        java.util.BitSet nonAir = column.getColumn().getNonAir(sectionY);
        if (storage != null && nonAir != null && !nonAir.isEmpty()) {
            for (int index = nonAir.nextSetBit(0); index >= 0; index = nonAir.nextSetBit(index + 1)) {
                int lx = com.vbuser.inf.chunk.ExtendedColumn.indexX(index);
                int ly = com.vbuser.inf.chunk.ExtendedColumn.indexY(index);
                int lz = com.vbuser.inf.chunk.ExtendedColumn.indexZ(index);
                int x = baseX + lx;
                int y = baseY + ly;
                int z = baseZ + lz;
                if (!HeightConstants.isExtendedY(y)) {
                    continue;
                }
                IBlockState state = storage.get(lx, ly, lz);
                int emitted = state.getBlock().getLightValue(
                        state, this.world, this.mutablePos.setPos(x, y, z));
                if (emitted > 0) {
                    if (column.getLightFor(EnumSkyBlock.BLOCK, x, y, z) != emitted) {
                        changed = true;
                    }
                    column.setLightFor(EnumSkyBlock.BLOCK, x, y, z, emitted);
                    this.blockAdd.push(x, y, z, emitted);
                }
            }
        }

        // Shell: light from the neighboring sections has to be pulled in.
        for (int ly = 0; ly < 16; ly++) {
            int y = baseY + ly;
            for (int lz = 0; lz < 16; lz++) {
                int z = baseZ + lz;
                for (int lx = 0; lx < 16; lx++) {
                    if (lx != 0 && lx != 15 && ly != 0 && ly != 15 && lz != 0 && lz != 15) {
                        continue;
                    }
                    int x = baseX + lx;
                    if (!HeightConstants.isExtendedY(y)) {
                        continue;
                    }
                    if (sky) {
                        int before = column.getLightFor(EnumSkyBlock.SKY, x, y, z);
                        if (before > 0) {
                            this.skyAdd.push(x, y, z, before);
                        } else {
                            updateSkyCell(column, x, y, z);
                            if (column.getLightFor(EnumSkyBlock.SKY, x, y, z) != before) {
                                changed = true;
                            }
                        }
                    }
                    int beforeBlock = column.getLightFor(EnumSkyBlock.BLOCK, x, y, z);
                    if (beforeBlock > 0) {
                        this.blockAdd.push(x, y, z, beforeBlock);
                    } else {
                        updateBlockCell(column, x, y, z);
                        if (column.getLightFor(EnumSkyBlock.BLOCK, x, y, z) != beforeBlock) {
                            changed = true;
                        }
                    }
                }
            }
        }

        // A section touching the seam can change the vanilla foliage next to it.
        if (!this.world.isRemote) {
            return changed;
        }
        int vanillaY = baseY <= -1 && -1 < baseY + 16 ? 0 : baseY <= 256 && 256 < baseY + 16 ? 255 : Integer.MIN_VALUE;
        if (vanillaY == Integer.MIN_VALUE) {
            return changed;
        }
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                notifySeamColumn(baseX + lx, baseZ + lz, vanillaY);
            }
        }
        return changed;
    }
    /** Queue a whole section for seeding (used when a section appears or is loaded). */
    public void scheduleSectionInit(int chunkX, int chunkZ, int sectionY) {
        long key = sectionKey(chunkX, chunkZ, sectionY);
        if (this.scheduledSections.add(key)) {
            this.pendingSections.add(new int[]{chunkX, sectionY, chunkZ});
        }
    }

    /** A column was rebuilt and wants to know when it may be trusted again. */
    void addTrustPending(ColumnLight column) {
        this.trustPending.add(column);
    }

    private static long sectionKey(int chunkX, int chunkZ, int sectionY) {
        return ((long) (chunkX & 0x3FFFFFF) << 38) | ((long) (chunkZ & 0x3FFFFFF) << 12)
                | (sectionY & 0xFFF);
    }
    // ------------------------------------------------------------------
    // Cell updates
    // ------------------------------------------------------------------

    private void updateSkyCell(ColumnLight column, int x, int y, int z) {
        if (!this.world.provider.hasSkyLight()) {
            return;
        }
        int old = column.getLightFor(EnumSkyBlock.SKY, x, y, z);
        int raw = rawSky(column, x, y, z);
        if (raw > old) {
            column.setLightFor(EnumSkyBlock.SKY, x, y, z, raw);
            this.skyAdd.push(x, y, z, raw);
        } else if (raw < old) {
            column.setLightFor(EnumSkyBlock.SKY, x, y, z, 0);
            this.skyRemove.push(x, y, z, old);
        }
    }

    private void updateBlockCell(ColumnLight column, int x, int y, int z) {
        int old = column.getLightFor(EnumSkyBlock.BLOCK, x, y, z);
        int raw = rawBlock(column, x, y, z);
        if (raw > old) {
            column.setLightFor(EnumSkyBlock.BLOCK, x, y, z, raw);
            this.blockAdd.push(x, y, z, raw);
        } else if (raw < old) {
            column.setLightFor(EnumSkyBlock.BLOCK, x, y, z, 0);
            this.blockRemove.push(x, y, z, old);
            this.blockAdd.push(x, y, z, 0);
        }
    }

    /** Same rules as {@code World.getRawLight}, without the 15 block radius. */
    private int rawSky(ColumnLight column, int x, int y, int z) {
        if (column.canSeeSky(x, y, z)) {
            return 15;
        }
        int best = 0;
        int opacity = Math.max(1, opacityAt(x, y, z));
        for (int i = 0; i < 6; i++) {
            int value = getLight(EnumSkyBlock.SKY, x + DX[i], y + DY[i], z + DZ[i]) - opacity;
            if (value > best) {
                best = value;
            }
        }
        return best;
    }

    private int rawBlock(ColumnLight column, int x, int y, int z) {
        IBlockState state = column.getBlock(x, y, z);
        int best = state.getBlock().getLightValue(state, this.world, this.mutablePos.setPos(x, y, z));
        if (best >= 15) {
            return best;
        }
        int opacity = Math.max(1, state.getLightOpacity(this.world, this.mutablePos.setPos(x, y, z)));
        for (int i = 0; i < 6; i++) {
            int value = getLight(EnumSkyBlock.BLOCK, x + DX[i], y + DY[i], z + DZ[i]) - opacity;
            if (value > best) {
                best = value;
            }
        }
        return best;
    }

    private int opacityAt(int x, int y, int z) {
        IBlockState state = stateAt(x, y, z);
        if (state.getBlock() == Blocks.AIR) {
            return 0;
        }
        if (state.getBlock().isAir(state, this.world, this.mutablePos.setPos(x, y, z))) {
            return 0;
        }
        return state.getLightOpacity(this.world, this.mutablePos.setPos(x, y, z));
    }

    private IBlockState stateAt(int x, int y, int z) {
        if (HeightConstants.isExtendedY(y)) {
            ColumnLight column = columnAt(x, z);
            return column == null ? Blocks.AIR.getDefaultState() : column.getBlock(x, y, z);
        }
        return this.world.getBlockState(this.mutablePos.setPos(x, y, z));
    }

    // ------------------------------------------------------------------
    // Light access across the seam
    // ------------------------------------------------------------------

    int getLight(EnumSkyBlock type, int x, int y, int z) {
        if (HeightConstants.isExtendedY(y)) {
            ColumnLight column = columnAt(x, z);
            return column == null ? 0 : column.getLightFor(type, x, y, z);
        }
        Chunk chunk = chunkAt(x, z);
        return chunk == null ? 0 : chunk.getLightFor(type, this.mutablePos.setPos(x, y, z));
    }

    void setLight(EnumSkyBlock type, int x, int y, int z, int value) {
        if (HeightConstants.isExtendedY(y)) {
            ColumnLight column = columnAt(x, z);
            if (column != null) {
                column.setLightFor(type, x, y, z, value);
            }
            return;
        }
        Chunk chunk = chunkAt(x, z);
        if (chunk == null) {
            return;
        }
        // A seam cell: write the vanilla array and refresh the render chunk
        // that samples it.
        BlockPos pos = this.mutablePos.setPos(x, y, z);
        chunk.setLightFor(type, pos, value);
        this.world.notifyLightSet(pos.toImmutable());
    }

    // ------------------------------------------------------------------
    // BFS
    // ------------------------------------------------------------------

    /**
     * Drain the queues. Light is drained completely, like vanilla does: a
     * budgeted diff makes the player watch the brightness crawl towards its new
     * value over several frames, which looks far worse than one slightly longer
     * tick. Section seeding is the only step that is limited per tick.
     */
    public boolean tick() {
        this.cacheValid = true;
        // Converge: removals first, then additions, then recompute the cells
        // whose neighbors changed and run again until nothing is left. This is
        // what keeps the field consistent no matter in which order blocks
        // changed during the tick.
        for (int pass = 0; pass < 8; pass++) {
            int budget = Integer.MAX_VALUE;
            budget = runRemove(this.skyRemove, EnumSkyBlock.SKY, budget);
            budget = runRemove(this.blockRemove, EnumSkyBlock.BLOCK, budget);
            budget = runAdd(this.skyAdd, EnumSkyBlock.SKY, budget);
            runAdd(this.blockAdd, EnumSkyBlock.BLOCK, budget);
            if (this.dirty.isEmpty()) {
                break;
            }
            runDirty();
        }
        runSectionInit();
        this.cacheValid = false;

        this.dirty.compact();
        boolean done = !hasPendingWork();
        if (done) {
            this.skyAdd.compact();
            this.skyRemove.compact();
            this.blockAdd.compact();
            this.blockRemove.compact();
            resolveTrust();
        }
        return done;
    }

    /**
     * Nothing is queued anymore, so every column that was rebuilt from block
     * data now holds a consistent field and may be written to disk as such.
     * Without this the {@code inf:Light} tag was never written again and every
     * single load recomputed the whole extended region.
     */
    private void resolveTrust() {
        if (this.trustPending.isEmpty()) {
            return;
        }
        java.util.Iterator<ColumnLight> it = this.trustPending.iterator();
        while (it.hasNext()) {
            ColumnLight column = it.next();
            if (column == null) {
                it.remove();
                continue;
            }
            if (column.isSettled()) {
                column.markTrusted();
                it.remove();
            }
        }
    }

    public boolean hasPendingWork() {
        return !this.skyRemove.isEmpty() || !this.blockRemove.isEmpty()
                || !this.skyAdd.isEmpty() || !this.blockAdd.isEmpty()
                || !this.pendingSections.isEmpty() || !this.dirty.isEmpty();
    }

    private int runRemove(NodeQueue queue, EnumSkyBlock type, int budget) {
        NodeQueue add = type == EnumSkyBlock.SKY ? this.skyAdd : this.blockAdd;
        while (!queue.isEmpty() && budget > 0) {
            int i = queue.read;
            queue.read += 4;
            budget--;
            int x = queue.data[i];
            int y = queue.data[i + 1];
            int z = queue.data[i + 2];
            int old = queue.data[i + 3];
            if (old <= 0) {
                continue;
            }
            for (int d = 0; d < 6; d++) {
                int nx = x + DX[d];
                int ny = y + DY[d];
                int nz = z + DZ[d];
                int neighbour = getLight(type, nx, ny, nz);
                if (neighbour == 0) {
                    continue;
                }
                if (neighbour < old) {
                    setLight(type, nx, ny, nz, 0);
                    queue.push(nx, ny, nz, neighbour);
                } else {
                    // Boundary of the removed region: re-propagate from here.
                    add.push(nx, ny, nz, neighbour);
                }
            }
        }
        return budget;
    }

    private int runAdd(NodeQueue queue, EnumSkyBlock type, int budget) {
        boolean sky = type == EnumSkyBlock.SKY;
        while (!queue.isEmpty() && budget > 0) {
            int i = queue.read;
            queue.read += 4;
            budget--;
            int x = queue.data[i];
            int y = queue.data[i + 1];
            int z = queue.data[i + 2];
            int value = queue.data[i + 3];
            // Nodes can be queued before a removal zeroes their cell; a stale
            // node must never resurrect light, so the stored value wins.
            int stored = getLight(type, x, y, z);
            if (stored < value) {
                value = stored;
            }
            if (value <= 0) {
                continue;
            }
            for (int d = 0; d < 6; d++) {
                int nx = x + DX[d];
                int ny = y + DY[d];
                int nz = z + DZ[d];
                int target = value - Math.max(1, opacityAt(nx, ny, nz));
                if (sky) {
                    if (this.world.provider.hasSkyLight() && canSeeSky(nx, ny, nz)) {
                        target = 15;
                    }
                } else {
                    IBlockState state = stateAt(nx, ny, nz);
                    int emitted = state.getBlock().getLightValue(
                            state, this.world, this.mutablePos.setPos(nx, ny, nz));
                    if (emitted > target) {
                        target = emitted;
                    }
                }
                if (target > 0 && target > getLight(type, nx, ny, nz)) {
                    setLight(type, nx, ny, nz, target);
                    queue.push(nx, ny, nz, target);
                }
            }
        }
        return budget;
    }

    private void runDirty() {
        NodeQueue queue = this.dirty;
        int processed = 0;
        while (!queue.isEmpty()) {
            int i = queue.read;
            queue.read += 4;
            if (++processed > 200000) {
                break;
            }
            int x = queue.data[i];
            int y = queue.data[i + 1];
            int z = queue.data[i + 2];
            ColumnLight column = columnAt(x, z);
            if (column == null) {
                continue;
            }
            updateSkyCell(column, x, y, z);
            updateBlockCell(column, x, y, z);
        }
        queue.compact();
    }

    private void runSectionInit() {
        int done = 0;
        while (done < ExtendedLightEngine.MAX_SECTIONS_PER_TICK) {
            int[] next = this.pendingSections.poll();
            if (next == null) {
                break;
            }
            this.scheduledSections.remove(sectionKey(next[0], next[2], next[1]));
            done++;
            int firstY = next[1] << 4;
            if (HeightConstants.isVanillaY(firstY) && HeightConstants.isVanillaY(firstY + 15)) {
                // A vanilla section can never hold extended light; the cascade
                // below reaches one section past the seam on purpose.
                continue;
            }
            ColumnLight column = columnAt(next[0] << 4, next[2] << 4);
            if (column == null) {
                continue;
            }
            // Counts towards this column's rebuild progress even when the
            // section itself is skipped for being suspended.
            column.seedConsumed();
            if (column.isSuspended()) {
                continue;
            }
            // A section only learns about light that already exists next door,
            // and sections are loaded in an arbitrary order. A section seeded
            // before its neighbor therefore pulls zeros and would stay dark
            // until some block update happens to touch it. Whenever this
            // section actually gained light, queue its neighbors again: the
            // values only ever grow, so this converges.
            if (initSection(column, next[1])) {
                int cx = next[0];
                int cz = next[2];
                int sy = next[1];
                scheduleSectionInit(cx, cz, sy - 1);
                scheduleSectionInit(cx, cz, sy + 1);
                scheduleSectionInit(cx - 1, cz, sy);
                scheduleSectionInit(cx + 1, cz, sy);
                scheduleSectionInit(cx, cz - 1, sy);
                scheduleSectionInit(cx, cz + 1, sy);
            }
        }
    }
    private boolean canSeeSky(int x, int y, int z) {
        ColumnLight column = columnAt(x, z);
        return column != null && column.canSeeSky(x, y, z);
    }

    // ------------------------------------------------------------------
    // Chunk / column lookup
    // ------------------------------------------------------------------

    private Chunk chunkAt(int x, int z) {
        return this.world.getChunkProvider().getLoadedChunk(x >> 4, z >> 4);
    }

    @Nullable
    ColumnLight columnAt(int x, int z) {
        int cx = x >> 4;
        int cz = z >> 4;
        if (this.cacheValid && cx == this.cacheChunkX && cz == this.cacheChunkZ) {
            return this.cacheColumn;
        }
        Chunk chunk = this.world.getChunkProvider().getLoadedChunk(cx, cz);
        ColumnLight column = chunk == null ? null : ColumnLight.get(chunk);
        if (this.cacheValid) {
            this.cacheChunkX = cx;
            this.cacheChunkZ = cz;
            this.cacheColumn = column;
        }
        return column;
    }
}
