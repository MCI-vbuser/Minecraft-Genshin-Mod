package com.vbuser.genshin.client.renderer;

import com.vbuser.genshin.block.BlockCloud;
import com.vbuser.inf.HeightConstants;
import com.vbuser.inf.chunk.ColumnManager;
import com.vbuser.inf.chunk.ExtendedColumn;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
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

    /** One 16x16x16 chunk section, in local coordinates. */
    public static final class Section {
        public final int sx;
        public final int sy;
        public final int sz;
        public final byte[] density = new byte[SECTION_VOLUME];
        public int nonZero;

        Section(int sx, int sy, int sz) {
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
        final Long2ObjectOpenHashMap<Section> sections = new Long2ObjectOpenHashMap<>();
        final LongOpenHashSet dirty = new LongOpenHashSet();
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

    private static final class PendingScan {
        final ChunkPos pos;
        int mask;
        boolean full;
        boolean ext;

        PendingScan(ChunkPos pos) {
            this.pos = pos;
        }
    }

    private CloudVolumeTracker() {
    }

    // ------------------------------------------------------------------
    // keys
    // ------------------------------------------------------------------

    public static long sectionKey(int sx, int sy, int sz) {
        return ((long) (sx & 0x1FFFFF) << 42) | ((long) (sy & 0x1FFFFF) << 21) | (sz & 0x1FFFFF);
    }

    public static int keyX(long key) {
        return (int) (key >>> 42) << 11 >> 11;
    }

    public static int keyY(long key) {
        return (int) (key >>> 21) << 11 >> 11;
    }

    public static int keyZ(long key) {
        return (int) key << 11 >> 11;
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
    public static Section getSection(int dimension, long key) {
        DimensionData data = DATA.get(dimension);
        return data == null ? null : data.sections.get(key);
    }

    /** Hands every live section key to {@code sink}. */
    public static void collectSectionKeys(int dimension, LongConsumer sink) {
        DimensionData data = DATA.get(dimension);
        if (data == null) {
            return;
        }
        for (long key : data.sections.keySet()) {
            sink.accept(key);
        }
    }

    /** Moves the pending changed-section keys into {@code sink}. */
    public static int drainDirtyKeys(int dimension, LongConsumer sink) {
        DimensionData data = DATA.get(dimension);
        if (data == null || data.dirty.isEmpty()) {
            return 0;
        }
        int count = data.dirty.size();
        for (long key : data.dirty) {
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

    public interface LongConsumer {
        void accept(long value);
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
        long key = sectionKey(sx, sy, sz);
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
        java.util.ArrayList<Long> remove = null;
        for (Section section : data.sections.values()) {
            if (section.sx == chunkPos.x && section.sz == chunkPos.z) {
                if (remove == null) {
                    remove = new java.util.ArrayList<>();
                }
                remove.add(Long.valueOf(sectionKey(section.sx, section.sy, section.sz)));
            }
        }
        if (remove == null || remove.isEmpty()) {
            PENDING.remove(ChunkPos.asLong(chunkPos.x, chunkPos.z));
            return;
        }
        for (Long key : remove) {
            Section section = data.sections.remove(key.longValue());
            if (section == null) {
                continue;
            }
            for (byte value : section.density) {
                if (value != 0) {
                    data.totalDensity -= value & 0xFF;
                    data.blockCount--;
                }
            }
            data.dirty.add(key.longValue());
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
            scan.full = true;
            scan.mask = 0xFFFF;
        } else {
            scan.mask |= sectionMask;
        }
        if (HeightConstants.isExtendedWorld(world)) {
            scan.ext = true;
        }
    }

    /** Reads at most {@code budget} milliseconds worth of queued chunk data. */
    public static void processPendingScans(World world, long budgetNanos) {
        if (PENDING.isEmpty() || world == null) {
            return;
        }
        long deadline = System.nanoTime() + budgetNanos;
        it.unimi.dsi.fastutil.objects.ObjectIterator<PendingScan> iterator = PENDING.values().iterator();
        while (iterator.hasNext()) {
            PendingScan scan = iterator.next();
            iterator.remove();
            Chunk chunk = world.getChunkProvider().getLoadedChunk(scan.pos.x, scan.pos.z);
            if (chunk == null) {
                continue;
            }
            int dimension = world.provider.getDimension();
            int mask = scan.full ? 0xFFFF : scan.mask;
            for (int sy = 0; sy < 16; sy++) {
                if ((mask & (1 << sy)) == 0) {
                    continue;
                }
                scanSection(dimension, chunk, sy, null);
            }
            if (scan.ext && scan.full) {
                scanExtendedSections(world, dimension, chunk);
            }
            if (System.nanoTime() >= deadline) {
                break;
            }
        }
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

    private static void scanExtendedSections(World world, int dimension, Chunk chunk) {
        ExtendedColumn column = ColumnManager.getIfPresent(chunk);
        if (column == null) {
            return;
        }
        Map<Integer, ExtendedBlockStorage> sections = column.getSections();
        if (sections == null || sections.isEmpty()) {
            return;
        }
        for (Map.Entry<Integer, ExtendedBlockStorage> entry : sections.entrySet()) {
            Integer sy = entry.getKey();
            ExtendedBlockStorage storage = entry.getValue();
            if (sy == null || storage == null) {
                continue;
            }
            if (HeightConstants.isVanillaY(sy << 4)) {
                continue;
            }
            scanSection(dimension, chunk, sy, storage);
        }
    }

    private static void clearSection(int dimension, int cx, int sy, int cz) {
        DimensionData data = DATA.get(dimension);
        if (data == null) {
            return;
        }
        long key = sectionKey(cx, sy, cz);
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
        long key = sectionKey(cx, sy, cz);
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
                scan.full = true;
                scan.mask = 0xFFFF;
                scan.ext = HeightConstants.isExtendedWorld(world);
                PENDING.put(key, scan);
            }
        }
    }
}
