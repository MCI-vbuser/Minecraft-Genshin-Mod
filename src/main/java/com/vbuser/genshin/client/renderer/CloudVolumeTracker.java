package com.vbuser.genshin.client.renderer;

import com.vbuser.genshin.block.BlockCloud;
import com.vbuser.inf.HeightConstants;
import com.vbuser.inf.chunk.ColumnManager;
import com.vbuser.inf.chunk.ExtendedColumn;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import javax.annotation.Nullable;
import java.util.Map;

/**
 * Client side cloud block index.
 *
 * <p>Cloud blocks are only a data source: nothing is rendered from the chunk
 * meshes. The renderer needs the density field of every cloud block inside the
 * camera's cloudy region, in a form that can be turned into a 3D texture. A
 * hash map of {@code BlockPos -> density} (the previous approach) costs ~300 MB
 * for a 300x40x300 test region and has to be re-scanned from scratch, so the
 * index is stored per chunk section as a flat {@code byte[4096]}:
 * 4 KB per section, 16x16x16 blocks per section, density 0..15 per block.</p>
 *
 * <p>All methods are called from the client thread (packet handling, block
 * updates, render loop), so there is no locking here on purpose.</p>
 */
public final class CloudVolumeTracker {

    public static final int SECTION_SIZE = 16;
    public static final int SECTION_VOLUME = SECTION_SIZE * SECTION_SIZE * SECTION_SIZE;

    /** Lossless three-dimensional key; unlike a packed long this cannot alias extreme Y. */
    public static final class SectionKey {
        public final int sx;
        public final int sy;
        public final int sz;

        public SectionKey(int sx, int sy, int sz) {
            this.sx = sx;
            this.sy = sy;
            this.sz = sz;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof SectionKey)) return false;
            SectionKey that = (SectionKey) other;
            return this.sx == that.sx && this.sy == that.sy && this.sz == that.sz;
        }

        @Override
        public int hashCode() {
            int result = this.sx;
            result = 31 * result + this.sy;
            result = 31 * result + this.sz;
            return result;
        }
    }

    /** One 16x16x16 chunk section, in local coordinates. */
    public static final class Section {
        public final SectionKey key;
        public final int sx;
        public final int sy;
        public final int sz;
        public final byte[] density = new byte[SECTION_VOLUME];
        public int nonZero;

        Section(int sx, int sy, int sz) {
            this.key = new SectionKey(sx, sy, sz);
            this.sx = sx;
            this.sy = sy;
            this.sz = sz;
        }

        public int baseX() {
            return this.sx << 4;
        }

        public int baseY() {
            return this.sy << 4;
        }

        public int baseZ() {
            return this.sz << 4;
        }

        public static int index(int x, int y, int z) {
            return (y << 8) | (z << 4) | x;
        }

        /** @return true when the cell actually changed. */
        boolean set(int x, int y, int z, int density) {
            int index = index(x, y, z);
            int old = this.density[index] & 0xFF;
            if (old == density) {
                return false;
            }
            this.density[index] = (byte) density;
            if (old == 0) {
                this.nonZero++;
            } else if (density == 0) {
                this.nonZero--;
            }
            return true;
        }
    }

    private static final class DimensionData {
        final Map<SectionKey, Section> sections = new java.util.HashMap<>();
        final java.util.Set<SectionKey> dirty = new java.util.LinkedHashSet<>();
        long totalDensity;
        int blockCount;
        /**
         * Grow only bounds of the cloud region.
         *
         * <p>They are deliberately <em>not</em> recomputed when blocks or whole
         * chunks go away: only loaded chunks are indexed, so walking around the
         * edge of a cloud field makes chunks (and their cloud blocks) load and
         * unload, and a shrinking region would reallocate the volume texture and
         * rebuild it over and over - which reads as the clouds blinking out and
         * coming back. The bounds are reset only when the region becomes empty.</p>
         */
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;

        BlockPos minPos;
        BlockPos maxPos;
        boolean posDirty = true;

        void reset() {
            this.minX = this.minY = this.minZ = Integer.MAX_VALUE;
            this.maxX = this.maxY = this.maxZ = Integer.MIN_VALUE;
            this.minPos = this.maxPos = null;
            this.posDirty = false;
        }
    }

    private static final Map<Integer, DimensionData> DATA = new java.util.concurrent.ConcurrentHashMap<>();

    /** Chunk sections that still have to be read from a freshly arrived chunk. */
    private static final Long2ObjectOpenHashMap<PendingScan> PENDING = new Long2ObjectOpenHashMap<>();

    /**
     * Maximum chunk sections read in one {@link #processPendingScans} call. The
     * per call deadline normally bites first; this only bounds how many cheap
     * (empty storage) sections a burst can consume before yielding.
     */
    private static final int SECTIONS_PER_SCAN_CALL = 256;

    /**
     * A chunk whose cloud sections still have to be read.
     *
     * <p>The reading is incremental: {@link #mask} holds the vanilla sections
     * that are left and {@link #extDirty} the extended sections that still have
     * to be read, so the scan can yield between sections and be resumed on the
     * next tick instead of blocking on a whole chunk.</p>
     *
     * <p>Extended sections are tracked individually because they are delivered
     * by the infinite-height channel, which is a different (and slower, per
     * player byte budget) path than the vanilla chunk packet: at the time the
     * chunk data is applied they are usually not there yet.</p>
     */
    private static final class PendingScan {
        final ChunkPos pos;
        /** Vanilla section mask; a bit is cleared once its section was read. */
        int mask;
        java.util.Set<Integer> extDirty;
        PendingScan(ChunkPos pos) {
            this.pos = pos;
        }
    }

    private CloudVolumeTracker() {
    }

    // ------------------------------------------------------------------
    // keys
    // ------------------------------------------------------------------

    public static SectionKey sectionKey(int sx, int sy, int sz) {
        return new SectionKey(sx, sy, sz);
    }

    private static DimensionData data(int dimension) {
        return DATA.computeIfAbsent(dimension, k -> new DimensionData());
    }

    // ------------------------------------------------------------------
    // queries used by the renderer
    // ------------------------------------------------------------------

    public static boolean hasClouds(int dimension) {
        DimensionData data = DATA.get(dimension);
        return data != null && data.blockCount > 0;
    }

    public static int getCloudBlockCount(int dimension) {
        DimensionData data = DATA.get(dimension);
        return data == null ? 0 : data.blockCount;
    }

    public static float getAverageDensity(int dimension) {
        DimensionData data = DATA.get(dimension);
        if (data == null || data.blockCount == 0) {
            return 8.0F;
        }
        return (float) ((double) data.totalDensity / (double) data.blockCount);
    }

    @Nullable
    public static BlockPos getMin(int dimension) {
        DimensionData data = DATA.get(dimension);
        if (data == null || data.blockCount == 0) {
            return null;
        }
        refreshPositions(data);
        return data.minPos;
    }

    @Nullable
    public static BlockPos getMax(int dimension) {
        DimensionData data = DATA.get(dimension);
        if (data == null || data.blockCount == 0) {
            return null;
        }
        refreshPositions(data);
        return data.maxPos;
    }

    /** @return the number of uploadable sections in this dimension. */
    public static int getSectionCount(int dimension) {
        DimensionData data = DATA.get(dimension);
        return data == null ? 0 : data.sections.size();
    }

    @Nullable
    public static Section getSection(int dimension, SectionKey key) {
        DimensionData data = DATA.get(dimension);
        return data == null ? null : data.sections.get(key);
    }

    /** Hands every live section key to {@code sink}. */
    public static void collectSectionKeys(int dimension, SectionKeyConsumer sink) {
        DimensionData data = DATA.get(dimension);
        if (data == null) {
            return;
        }
        for (SectionKey key : data.sections.keySet()) {
            sink.accept(key);
        }
    }

    /** Moves the pending changed-section keys into {@code sink}. */
    public static int drainDirtyKeys(int dimension, SectionKeyConsumer sink) {
        DimensionData data = DATA.get(dimension);
        if (data == null || data.dirty.isEmpty()) {
            return 0;
        }
        int count = data.dirty.size();
        for (SectionKey key : data.dirty) {
            sink.accept(key);
        }
        data.dirty.clear();
        return count;
    }

    /** @return number of sections waiting for a texture refresh. */
    public static int getDirtyCount(int dimension) {
        DimensionData data = DATA.get(dimension);
        return data == null ? 0 : data.dirty.size();
    }

    public interface SectionKeyConsumer {
        void accept(SectionKey value);
    }

    // ------------------------------------------------------------------
    // mutation
    // ------------------------------------------------------------------

    /** Sets the density (0 = no cloud) of a single block. */
    public static void setBlock(int dimension, int x, int y, int z, int density) {
        if (density < 0) {
            density = 0;
        } else if (density > 15) {
            density = 15;
        }
        int sx = x >> 4;
        int sy = y >> 4;
        int sz = z >> 4;
        SectionKey key = sectionKey(sx, sy, sz);
        DimensionData data = data(dimension);
        Section section = data.sections.get(key);
        if (section == null) {
            if (density == 0) {
                return;
            }
            section = new Section(sx, sy, sz);
            data.sections.put(key, section);
        }
        int old = section.density[Section.index(x & 15, y & 15, z & 15)] & 0xFF;
        if (!section.set(x & 15, y & 15, z & 15, density)) {
            return;
        }
        data.totalDensity += density - old;
        if (old == 0 && density != 0) {
            data.blockCount++;
            extendBounds(data, x, y, z);
        } else if (old != 0 && density == 0) {
            data.blockCount--;
            if (data.blockCount <= 0) {
                data.blockCount = 0;
                data.totalDensity = 0;
                data.reset();
            }
        }
        if (section.nonZero == 0) {
            data.sections.remove(key);
        }
        data.dirty.add(key);
    }

    private static void extendBounds(DimensionData data, int x, int y, int z) {
        boolean changed = false;
        if (x < data.minX) { data.minX = x; changed = true; }
        if (x > data.maxX) { data.maxX = x; changed = true; }
        if (y < data.minY) { data.minY = y; changed = true; }
        if (y > data.maxY) { data.maxY = y; changed = true; }
        if (z < data.minZ) { data.minZ = z; changed = true; }
        if (z > data.maxZ) { data.maxZ = z; changed = true; }
        if (changed) {
            data.posDirty = true;
        }
    }

    /** Rebuilds the cached immutable bounds after the grow only extent changed. */
    private static void refreshPositions(DimensionData data) {
        if (!data.posDirty) {
            return;
        }
        data.posDirty = false;
        if (data.maxX < data.minX || data.blockCount <= 0) {
            data.minPos = data.maxPos = null;
            return;
        }
        data.minPos = new BlockPos(data.minX, data.minY, data.minZ);
        data.maxPos = new BlockPos(data.maxX, data.maxY, data.maxZ);
    }

    public static void removeChunk(int dimension, ChunkPos chunkPos) {
        DimensionData data = DATA.get(dimension);
        if (data == null || data.sections.isEmpty()) {
            PENDING.remove(ChunkPos.asLong(chunkPos.x, chunkPos.z));
            return;
        }
        java.util.ArrayList<SectionKey> remove = null;
        for (Section section : data.sections.values()) {
            if (section.sx == chunkPos.x && section.sz == chunkPos.z) {
                if (remove == null) {
                    remove = new java.util.ArrayList<>();
                }
                remove.add(section.key);
            }
        }
        if (remove == null || remove.isEmpty()) {
            PENDING.remove(ChunkPos.asLong(chunkPos.x, chunkPos.z));
            return;
        }
        for (SectionKey key : remove) {
            Section section = data.sections.remove(key);
            if (section == null) {
                continue;
            }
            for (byte value : section.density) {
                if (value != 0) {
                    data.totalDensity -= value & 0xFF;
                    data.blockCount--;
                }
            }
            data.dirty.add(key);
        }
        if (data.blockCount < 0) {
            data.blockCount = 0;
            data.totalDensity = 0;
        }
        if (data.blockCount == 0) {
            data.reset();
        }
        // The bounds are intentionally *not* shrunk here: an unloaded chunk at
        // the edge of the cloud field must not reallocate the volume texture.
        PENDING.remove(ChunkPos.asLong(chunkPos.x, chunkPos.z));
    }

    public static void clearDimension(int dimension) {
        DATA.remove(dimension);
    }

    public static void clearAll() {
        DATA.clear();
        PENDING.clear();
    }

    // ------------------------------------------------------------------
    // client hooks (called from MixinChunkCloudData)
    // ------------------------------------------------------------------

    /**
     * A single block was changed on the client (S23/S16 packet or local edit).
     */
    public static void onClientBlockChanged(World world, BlockPos pos,
                                            @Nullable IBlockState oldState, @Nullable IBlockState newState) {
        boolean was = oldState != null && oldState.getBlock() instanceof BlockCloud;
        boolean now = newState != null && newState.getBlock() instanceof BlockCloud;
        if (!was && !now) {
            return;
        }
        int density = 0;
        if (now) {
            Integer value = newState.getValue(BlockCloud.DENSITY);
            density = value == null ? 8 : value;
        }
        setBlock(world.provider.getDimension(), pos.getX(), pos.getY(), pos.getZ(), density);
    }

    /**
     * A chunk data packet was applied. The affected sections are re-read on a
     * later tick so that a large fill does not stall the packet thread.
     */
    public static void onClientChunkData(World world, Chunk chunk, int sectionMask, boolean full) {
        if (!world.isRemote) {
            return;
        }
        long key = ChunkPos.asLong(chunk.x, chunk.z);
        PendingScan scan = PENDING.get(key);
        if (scan == null) {
            scan = new PendingScan(new ChunkPos(chunk.x, chunk.z));
            PENDING.put(key, scan);
        }
        if (full) {
            scan.mask = 0xFFFF;
        } else {
            scan.mask |= sectionMask;
        }
    }

    /**
     * Queues one extended section delivered by the infinite-height channel.
     * The channel is independent from the vanilla chunk packet, so this is what
     * makes cloud blocks above/below {@code 0..255} show up at all.
     */
    public static void onClientExtendedSectionData(World world, Chunk chunk, int sectionY) {
        if (world == null || !world.isRemote || chunk == null) {
            return;
        }
        long key = ChunkPos.asLong(chunk.x, chunk.z);
        PendingScan scan = PENDING.get(key);
        if (scan == null) {
            scan = new PendingScan(new ChunkPos(chunk.x, chunk.z));
            PENDING.put(key, scan);
        }
        if (scan.extDirty == null) {
            scan.extDirty = new java.util.HashSet<>();
        }
        scan.extDirty.add(sectionY);
    }

    /**
     * Replaces the extended part of a column after MODE_RESET/MODE_FINISH.
     * Sections absent from the new snapshot must be removed from the tracker as
     * well as from the chunk, otherwise their old density remains visible.
     *
     * <p>Only the extended sections are re-read. The resync does not resend the
     * vanilla chunk data, so touching {@code mask} here would re-read all 16
     * vanilla sections (65536 blocks) of the chunk for nothing.</p>
     */
    public static void onClientExtendedResync(World world, Chunk chunk) {
        if (world == null || !world.isRemote || chunk == null) {
            return;
        }
        int dimension = world.provider.getDimension();
        DimensionData data = DATA.get(dimension);
        if (data != null) {
            java.util.ArrayList<SectionKey> remove = null;
            for (Section section : data.sections.values()) {
                if (section.sx == chunk.x && section.sz == chunk.z
                        && (section.sy < 0 || section.sy >= 16)) {
                    if (remove == null) {
                        remove = new java.util.ArrayList<>();
                    }
                    remove.add(section.key);
                }
            }
            if (remove != null) {
                for (SectionKey key : remove) {
                    Section section = data.sections.remove(key);
                    if (section == null) {
                        continue;
                    }
                    for (byte value : section.density) {
                        if (value != 0) {
                            data.totalDensity -= value & 0xFF;
                            data.blockCount--;
                        }
                    }
                    data.dirty.add(key);
                }
            }
            if (data.blockCount <= 0) {
                data.blockCount = 0;
                data.totalDensity = 0;
                data.reset();
            }
        }
        // Re-read only the sections the new snapshot actually contains; the
        // vanilla chunk data was not resent, so the mask stays untouched.
        long key = ChunkPos.asLong(chunk.x, chunk.z);
        PendingScan scan = PENDING.get(key);
        if (scan == null) {
            scan = new PendingScan(new ChunkPos(chunk.x, chunk.z));
            PENDING.put(key, scan);
        }
        if (scan.extDirty == null) {
            scan.extDirty = new java.util.HashSet<>();
        }
        ExtendedColumn column = ColumnManager.getIfPresent(chunk);
        if (column != null) {
            for (Integer sectionY : column.getSections().keySet()) {
                // Vanilla sections are read from the chunk storage, never from
                // the extended column; mixing them in would clear valid data.
                if (sectionY != null && !HeightConstants.isVanillaY(sectionY << 4)) {
                    scan.extDirty.add(sectionY);
                }
            }
        }
    }

    /**
     * Reads at most {@code budgetNanos} worth of queued chunk data.
     *
     * <p>The scan keeps a per section cursor and yields as soon as the deadline
     * or the per call section cap is reached, so a freshly arrived chunk is
     * never read as one unbounded unit. A partially read chunk stays queued and
     * is resumed on the next call; the public behaviour is unchanged otherwise.</p>
     */
    public static void processPendingScans(World world, long budgetNanos) {
        if (PENDING.isEmpty() || world == null) {
            return;
        }
        long deadline = System.nanoTime() + budgetNanos;
        int dimension = world.provider.getDimension();
        int remaining = SECTIONS_PER_SCAN_CALL;
        it.unimi.dsi.fastutil.objects.ObjectIterator<PendingScan> iterator = PENDING.values().iterator();
        while (iterator.hasNext()) {
            PendingScan scan = iterator.next();
            Chunk chunk = world.getChunkProvider().getLoadedChunk(scan.pos.x, scan.pos.z);
            if (chunk == null) {
                iterator.remove();
                continue;
            }
            remaining -= scanChunkSections(dimension, chunk, scan, deadline, remaining);
            if (isScanComplete(scan)) {
                iterator.remove();
            }
            if (remaining <= 0 || System.nanoTime() >= deadline) {
                break;
            }
        }
    }

    /** @return true when every requested section of this chunk has been read. */
    private static boolean isScanComplete(PendingScan scan) {
        return scan.mask == 0 && (scan.extDirty == null || scan.extDirty.isEmpty());
    }

    /**
     * Reads sections of one chunk until the deadline, the per call cap or the
     * end of this chunk is reached.
     *
     * @return the number of sections actually read
     */
    private static int scanChunkSections(int dimension, Chunk chunk, PendingScan scan,
                                         long deadline, int maxSections) {
        int processed = 0;
        while (scan.mask != 0 && processed < maxSections) {
            int sy = Integer.numberOfTrailingZeros(scan.mask);
            scanSection(dimension, chunk, sy, null);
            scan.mask &= ~(1 << sy);
            processed++;
            if (System.nanoTime() >= deadline) {
                return processed;
            }
        }
        if (scan.mask != 0) {
            return processed;
        }
        if (scan.extDirty != null && !scan.extDirty.isEmpty()) {
            java.util.Iterator<Integer> dirty = scan.extDirty.iterator();
            while (dirty.hasNext() && processed < maxSections) {
                int sectionY = dirty.next();
                dirty.remove();
                scanExtendedSection(dimension, chunk, sectionY);
                processed++;
                if (System.nanoTime() >= deadline) {
                    return processed;
                }
            }
        }
        return processed;
    }

    private static void scanSection(int dimension, Chunk chunk, int sy, @Nullable ExtendedBlockStorage direct) {
        ExtendedBlockStorage storage = direct;
        if (storage == null) {
            ExtendedBlockStorage[] array = chunk.getBlockStorageArray();
            storage = sy >= 0 && sy < array.length ? array[sy] : null;
        }
        if (storage == null || storage.isEmpty()) {
            clearSection(dimension, chunk.x, sy, chunk.z);
            return;
        }
        byte[] found = null;
        int nonZero = 0;
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    IBlockState state = storage.get(x, y, z);
                    if (!(state.getBlock() instanceof BlockCloud)) {
                        continue;
                    }
                    Integer value = state.getValue(BlockCloud.DENSITY);
                    int density = value == null ? 8 : value;
                    if (density <= 0) {
                        continue;
                    }
                    if (found == null) {
                        found = new byte[SECTION_VOLUME];
                    }
                    found[Section.index(x, y, z)] = (byte) density;
                    nonZero++;
                }
            }
        }
        applySection(dimension, chunk.x, sy, chunk.z, found, nonZero);
    }

    /** Re-reads one extended section of the column. */
    private static void scanExtendedSection(int dimension, Chunk chunk, int sy) {
        ExtendedColumn column = ColumnManager.getIfPresent(chunk);
        ExtendedBlockStorage storage = column == null ? null : column.getSections().get(sy);
        if (storage == null) {
            clearSection(dimension, chunk.x, sy, chunk.z);
            return;
        }
        scanSection(dimension, chunk, sy, storage);
    }

    private static void clearSection(int dimension, int cx, int sy, int cz) {
        DimensionData data = DATA.get(dimension);
        if (data == null) {
            return;
        }
        SectionKey key = sectionKey(cx, sy, cz);
        Section old = data.sections.remove(key);
        if (old == null) {
            return;
        }
        for (byte value : old.density) {
            if (value != 0) {
                data.totalDensity -= value & 0xFF;
                data.blockCount--;
            }
        }
        if (data.blockCount < 0) {
            data.blockCount = 0;
            data.totalDensity = 0;
        }
        data.dirty.add(key);
        if (data.blockCount == 0) {
            data.reset();
        }
    }

    private static void applySection(int dimension, int cx, int sy, int cz,
                                     @Nullable byte[] found, int nonZero) {
        DimensionData data = data(dimension);
        SectionKey key = sectionKey(cx, sy, cz);
        Section section = data.sections.get(key);
        if (found == null || nonZero == 0) {
            if (section != null) {
                clearSection(dimension, cx, sy, cz);
            }
            return;
        }
        if (section == null) {
            section = new Section(cx, sy, cz);
            data.sections.put(key, section);
        }
        for (int index = 0; index < SECTION_VOLUME; index++) {
            int old = section.density[index] & 0xFF;
            int now = found[index] & 0xFF;
            if (old == now) {
                continue;
            }
            int x = index & 15;
            int z = (index >> 4) & 15;
            int y = (index >> 8) & 15;
            section.set(x, y, z, now);
            data.totalDensity += now - old;
            if (old == 0 && now != 0) {
                data.blockCount++;
                extendBounds(data, section.baseX() + x, section.baseY() + y, section.baseZ() + z);
            } else if (old != 0 && now == 0) {
                data.blockCount--;
            }
        }
        if (data.blockCount < 0) {
            data.blockCount = 0;
            data.totalDensity = 0;
        }
        if (section.nonZero == 0) {
            data.sections.remove(key);
        }
        if (data.blockCount == 0) {
            data.reset();
        }
        data.dirty.add(key);
    }

    /**
     * Clears everything for a dimension and queues every loaded chunk section
     * around the given chunk, which is what a manual rescan needs.
     */
    public static void requestRescan(World world, int dimension, int chunkX, int chunkZ, int radius) {
        DATA.remove(dimension);
        PENDING.clear();
        if (world == null) {
            return;
        }
        for (int cx = chunkX - radius; cx <= chunkX + radius; cx++) {
            for (int cz = chunkZ - radius; cz <= chunkZ + radius; cz++) {
                Chunk chunk = world.getChunkProvider().getLoadedChunk(cx, cz);
                if (chunk == null) {
                    continue;
                }
                long key = ChunkPos.asLong(cx, cz);
                PendingScan scan = new PendingScan(new ChunkPos(cx, cz));
                scan.mask = 0xFFFF;
                ExtendedColumn column = ColumnManager.getIfPresent(chunk);
                if (column != null && HeightConstants.isExtendedWorld(world)) {
                    scan.extDirty = new java.util.HashSet<>();
                    for (Integer sectionY : column.getSections().keySet()) {
                        if (sectionY != null && !HeightConstants.isVanillaY(sectionY << 4)) {
                            scan.extDirty.add(sectionY);
                        }
                    }
                }
                PENDING.put(key, scan);
            }
        }
    }
}
