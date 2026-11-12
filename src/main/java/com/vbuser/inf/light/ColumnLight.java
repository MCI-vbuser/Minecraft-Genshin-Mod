package com.vbuser.inf.light;

import com.vbuser.inf.HeightConstants;
import com.vbuser.inf.chunk.ColumnManager;
import com.vbuser.inf.chunk.ExtendedColumn;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.NibbleArray;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import java.util.Arrays;
import java.util.BitSet;
import java.util.Map;

/**
 * Sky / block light data for the extended (Y outside {@code 0..255}) part of
 * one chunk column.
 *
 * <p>Vanilla keeps light inside {@code Chunk.storageArrays} and every light
 * entry point clamps Y back into {@code 0..255}. This class keeps the very same
 * layout - one {@link NibbleArray} per 16 block section - in its own maps, so a
 * section that only contains light (caves, the air under a roof) is kept even
 * when the matching block section is empty.</p>
 *
 * <p>This class only stores and answers values; propagation lives in
 * {@link ExtendedLightEngine}. A section without an array answers with the
 * column value, which is exact for a section that was never touched: everything
 * above the column top sees the sky (15), everything below is dark (0). Arrays
 * are materialised the first time a cell needs a value that differs.</p>
 */
public class ColumnLight {

    /** Returned by {@link #top(int, int)} when the column has no light blocking block. */
    public static final int NO_TOP = Integer.MIN_VALUE;

    private final ExtendedColumn column;
    private final Chunk chunk;
    private final boolean skyEnabled;

    private final Int2ObjectOpenHashMap<NibbleArray> skyLight = new Int2ObjectOpenHashMap<>();
    private final Int2ObjectOpenHashMap<NibbleArray> blockLight = new Int2ObjectOpenHashMap<>();

    /**
     * Highest light blocking block + 1 per {@code (x, z)}, or {@link #NO_TOP}.
     * Index matches {@code Chunk.heightMap}: {@code z << 4 | x}.
     */
    private final int[] topMap = new int[256];

    /**
     * Mirrors {@code Chunk.heightMap[x, z] == 0} so a vanilla column that
     * becomes empty (or stops being empty) is detected without touching
     * {@code relightBlock}.
     */
    private final BitSet vanillaEmpty = new BitSet(256);

    /** Set while a whole section is imported at once; the engine skips the column. */
    private boolean suspended;

    /** True when the stored arrays match the block data and may be saved as-is. */
    private boolean trusted;

    public ColumnLight(ExtendedColumn column) {
        this.column = column;
        this.chunk = column.getChunk();
        this.skyEnabled = chunk.getWorld().provider.hasSkyLight();
        Arrays.fill(this.topMap, NO_TOP);
        initVanillaEmpty();
        // An untouched column is consistent with its (empty) block data by
        // construction; loading a chunk from disk overrides this in onChunkLoaded().
        this.trusted = true;
    }

    public static ColumnLight of(Chunk chunk) {
        ExtendedColumn column = ColumnManager.getIfPresent(chunk);
        return column == null ? null : column.getLight();
    }

    public static ColumnLight get(Chunk chunk) {
        return ColumnManager.get(chunk).getLight();
    }

    public ExtendedColumn getColumn() {
        return this.column;
    }

    public Chunk getChunk() {
        return this.chunk;
    }

    public boolean isSuspended() {
        return this.suspended;
    }

    public void setSuspended(boolean suspended) {
        this.suspended = suspended;
    }

    public boolean isTrusted() {
        return this.trusted;
    }

    // ------------------------------------------------------------------
    // Column top
    // ------------------------------------------------------------------

    /**
     * Combined column top: the highest of the extended top and the vanilla
     * height map, {@link #NO_TOP} when nothing blocks the sky at all.
     */
    public int top(int x, int z) {
        int lx = x & 15;
        int lz = z & 15;
        int extended = this.topMap[(lz << 4) | lx];
        int vanilla = this.chunk.getHeightValue(lx, lz);
        if (vanilla == 0) {
            // A vanilla top of 0 means "nothing blocks the sky in 0..255", so
            // the extended top alone decides.
            return extended;
        }
        if (extended == NO_TOP || extended < vanilla) {
            return vanilla;
        }
        return extended;
    }

    public boolean canSeeSky(int x, int y, int z) {
        int top = top(x, z);
        return top == NO_TOP || y >= top;
    }

    // ------------------------------------------------------------------
    // Values
    // ------------------------------------------------------------------

    public int getLightFor(EnumSkyBlock type, int x, int y, int z) {
        if (type == EnumSkyBlock.SKY) {
            if (!this.skyEnabled) {
                return 0;
            }
            NibbleArray array = this.skyLight.get(y >> 4);
            if (array == null) {
                return canSeeSky(x, y, z) ? 15 : 0;
            }
            return array.get(x & 15, y & 15, z & 15);
        }
        NibbleArray array = this.blockLight.get(y >> 4);
        return array == null ? 0 : array.get(x & 15, y & 15, z & 15);
    }

    public int getLightSubtracted(int x, int y, int z, int amount) {
        int sky = this.skyEnabled ? getLightFor(EnumSkyBlock.SKY, x, y, z) - amount : 0;
        if (sky < 0) {
            sky = 0;
        }
        int block = getLightFor(EnumSkyBlock.BLOCK, x, y, z);
        return block > sky ? block : sky;
    }

    /** Raw write; propagation is the engine's job. */
    public void setLightFor(EnumSkyBlock type, int x, int y, int z, int value) {
        if (type == EnumSkyBlock.SKY) {
            setSkyLight(x, y, z, value);
        } else {
            setBlockLight(x, y, z, value);
        }
    }

    private void setSkyLight(int x, int y, int z, int value) {
        if (!this.skyEnabled) {
            return;
        }
        int sectionY = y >> 4;
        NibbleArray array = this.skyLight.get(sectionY);
        if (array == null) {
            int implicit = canSeeSky(x, y, z) ? 15 : 0;
            if (value == implicit) {
                return;
            }
            array = new NibbleArray();
            initSkyArray(sectionY, array);
            this.skyLight.put(sectionY, array);
        }
        if (array.get(x & 15, y & 15, z & 15) == value) {
            return;
        }
        array.set(x & 15, y & 15, z & 15, value);
        this.chunk.markDirty();
        notifySeam(x, y, z);
        notifyRenderer(x, y, z);
    }

    private void setBlockLight(int x, int y, int z, int value) {
        int sectionY = y >> 4;
        NibbleArray array = this.blockLight.get(sectionY);
        if (array == null) {
            if (value == 0) {
                return;
            }
            array = new NibbleArray();
            this.blockLight.put(sectionY, array);
        }
        if (array.get(x & 15, y & 15, z & 15) == value) {
            return;
        }
        array.set(x & 15, y & 15, z & 15, value);
        this.chunk.markDirty();
        notifySeam(x, y, z);
        notifyRenderer(x, y, z);
    }


    /**
     * A light change in the {@code Y=-1} / {@code Y=256} seam changes the
     * brightness of the vanilla faces that sample it, so the render chunk next
     * to it has to be rebuilt. {@code notifyLightSet} only queues a light update
     * for the chunk the position falls in; a retired mesh needs the stronger
     * render-range update.
     */
    /**
     * The baked section mesh has the light baked into its vertices, so the
     * section has to be re-meshed whenever a value in it changes.
     */
    private void notifyRenderer(int x, int y, int z) {
        if (this.chunk.getWorld().isRemote) {
            LightRenderHook.cellChanged(this.chunk.getWorld(), x, y, z);
        }
    }

    private void notifySeam(int x, int y, int z) {        if (y != -1 && y != 256) {
        return;
    }
        World world = this.chunk.getWorld();
        if (!world.isRemote) {
            return;
        }
        BlockPos pos = new BlockPos(x, y == -1 ? 0 : 255, z);
        world.notifyLightSet(pos);
        world.markBlockRangeForRenderUpdate(pos, pos);
    }

    /** Fill a fresh sky array with the current column value. */
    private void initSkyArray(int sectionY, NibbleArray array) {
        int baseY = sectionY << 4;
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int top = top(lx, lz);
                if (top == NO_TOP) {
                    for (int ly = 0; ly < 16; ly++) {
                        array.set(lx, ly, lz, 15);
                    }
                } else {
                    for (int ly = 0; ly < 16; ly++) {
                        if (baseY + ly >= top) {
                            array.set(lx, ly, lz, 15);
                        }
                    }
                }
            }
        }
    }

    public IBlockState getBlock(int x, int y, int z) {
        return this.column.getBlock(x, y, z);
    }

    // ------------------------------------------------------------------
    // Incremental maintenance
    // ------------------------------------------------------------------

    /**
     * Called after a block was written into the extended column.
     *
     * @param oldTop column top captured <b>before</b> the block changed
     */
    public void onBlockChanged(World world, int x, int y, int z,
                               IBlockState oldState, IBlockState newState, int oldTop) {
        int lx = x & 15;
        int lz = z & 15;
        int index = (lz << 4) | lx;
        int previous = this.topMap[index];
        int updated = previous;

        int opacity = newState.getLightOpacity(world, new BlockPos(x, y, z));
        if (opacity != 0) {
            if (y < Integer.MAX_VALUE && y + 1 > updated) {
                updated = y + 1;
            }
        } else if (previous != NO_TOP && previous == y + 1) {
            updated = scanTop(world, lx, lz, y - 1);
        }
        this.topMap[index] = updated;

        ExtendedLightEngine engine = engine(world);
        if (engine == null || this.suspended) {
            return;
        }
        int newTop = top(lx, lz);
        if (newTop != oldTop) {
            engine.onColumnTopChanged(x, z, oldTop, newTop);
        }
        engine.updateCubeNeighbourhood(x, y, z);
    }

    /** A new block section appeared: seed its light. */
    public void onSectionCreated(World world, int sectionY) {
        ExtendedLightEngine engine = engine(world);
        if (engine != null) {
            engine.scheduleSectionInit(this.chunk.x, this.chunk.z, sectionY);
        }
    }

    /** Chunk was loaded from disk (or received from the network). */
    public void onChunkLoaded(World world, boolean trustLight) {
        rebuildTop(world);
        this.trusted = trustLight;
        if (trustLight) {
            return;
        }
        // Old saves (and the previous "everything is 15" implementation) cannot
        // be trusted: drop the arrays and let the engine derive them again.
        //
        // Every section that held *any* data has to be re-seeded, not just the
        // ones with blocks: the air around a shaft carries light without owning
        // a single block, and those sections are exactly what made "light only
        // appears after you break a block next to it" happen. One layer of
        // neighbours is seeded as well, because light bleeds across the border.
        IntSet sections = new IntOpenHashSet();
        sections.addAll(this.column.getSections().keySet());
        sections.addAll(this.skyLight.keySet());
        sections.addAll(this.blockLight.keySet());
        this.skyLight.clear();
        this.blockLight.clear();

        IntSet seeds = new IntOpenHashSet(sections.size() + 16);
        for (int sectionY : sections) {
            seeds.add(sectionY - 1);
            seeds.add(sectionY);
            seeds.add(sectionY + 1);
        }
        ExtendedLightEngine engine = engine(world);
        if (engine != null) {
            for (int sectionY : seeds) {
                engine.scheduleSectionInit(this.chunk.x, this.chunk.z, sectionY);
            }
        }
    }

    /**
     * The vanilla {@code Chunk.heightMap} switched between 0 and non-zero. Only
     * that transition can change {@link #canSeeSky} for {@code Y < 0}.
     */
    public void onVanillaHeightChanged(World world, int lx, int lz) {
        int index = (lz << 4) | lx;
        boolean nowEmpty = this.chunk.getHeightValue(lx, lz) == 0;
        boolean wasEmpty = this.vanillaEmpty.get(index);
        if (wasEmpty == nowEmpty) {
            return;
        }
        this.vanillaEmpty.set(index, nowEmpty);

        ExtendedLightEngine engine = engine(world);
        if (engine == null || this.suspended) {
            return;
        }
        // Reconstruct the two combined tops from the extended top alone: the
        // vanilla height map is 0 on one side of the transition and positive on
        // the other, which is all that matters for Y < 0.
        int extended = this.topMap[index];
        int x = (this.chunk.x << 4) + lx;
        int z = (this.chunk.z << 4) + lz;
        int oldTop;
        int newTop;
        if (nowEmpty) {
            oldTop = extended == NO_TOP || extended < 1 ? 1 : extended;
            newTop = extended;
        } else {
            int vanilla = this.chunk.getHeightValue(lx, lz);
            oldTop = extended;
            newTop = extended == NO_TOP || extended < vanilla ? vanilla : extended;
        }
        engine.onColumnTopChanged(x, z, oldTop, newTop);
    }

    private int scanTop(World world, int lx, int lz, int fromY) {
        int best = NO_TOP;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int worldX = (this.chunk.x << 4) + lx;
        int worldZ = (this.chunk.z << 4) + lz;

        for (Map.Entry<Integer, ExtendedBlockStorage> entry : this.column.getSections().entrySet()) {
            int sectionY = entry.getKey();
            if ((sectionY << 4) > fromY) {
                continue;
            }
            BitSet nonAir = this.column.getNonAir(sectionY);
            if (nonAir == null || nonAir.isEmpty()) {
                continue;
            }
            ExtendedBlockStorage storage = entry.getValue();
            int baseY = sectionY << 4;
            for (int index = nonAir.nextSetBit(0); index >= 0; index = nonAir.nextSetBit(index + 1)) {
                int ly = ExtendedColumn.indexY(index);
                int y = baseY + ly;
                if (y > fromY || y + 1 <= best) {
                    continue;
                }
                IBlockState state = storage.get(ExtendedColumn.indexX(index), ly, ExtendedColumn.indexZ(index));
                pos.setPos(worldX, y, worldZ);
                if (state.getLightOpacity(world, pos) != 0) {
                    best = y + 1;
                }
            }
        }
        return best;
    }

    /** Recompute {@link #topMap} from the extended block sections. */
    public void rebuildTop(World world) {
        Arrays.fill(this.topMap, NO_TOP);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int baseX = this.chunk.x << 4;
        int baseZ = this.chunk.z << 4;

        for (Map.Entry<Integer, ExtendedBlockStorage> entry : this.column.getSections().entrySet()) {
            int sectionY = entry.getKey();
            BitSet nonAir = this.column.getNonAir(sectionY);
            if (nonAir == null || nonAir.isEmpty()) {
                continue;
            }
            ExtendedBlockStorage storage = entry.getValue();
            int baseY = sectionY << 4;
            for (int index = nonAir.nextSetBit(0); index >= 0; index = nonAir.nextSetBit(index + 1)) {
                int lx = ExtendedColumn.indexX(index);
                int ly = ExtendedColumn.indexY(index);
                int lz = ExtendedColumn.indexZ(index);
                int column = (lz << 4) | lx;
                int y = baseY + ly;
                if (y + 1 <= this.topMap[column]) {
                    continue;
                }
                IBlockState state = storage.get(lx, ly, lz);
                if (state.getBlock() == Blocks.AIR) {
                    continue;
                }
                pos.setPos(baseX + lx, y, baseZ + lz);
                if (state.getLightOpacity(world, pos) != 0) {
                    this.topMap[column] = y + 1;
                }
            }
        }
        initVanillaEmpty();
    }

    private void initVanillaEmpty() {
        for (int index = 0; index < 256; index++) {
            this.vanillaEmpty.set(index, this.chunk.getHeightValue(index & 15, index >> 4) == 0);
        }
    }

    private static ExtendedLightEngine engine(World world) {
        return world instanceof ExtendedWorldLight ? ((ExtendedWorldLight) world).inf$lightEngine() : null;
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    public byte[] getSkyLightData(int sectionY) {
        NibbleArray array = this.skyLight.get(sectionY);
        return array == null ? null : array.getData();
    }

    public byte[] getBlockLightData(int sectionY) {
        NibbleArray array = this.blockLight.get(sectionY);
        return array == null ? null : array.getData();
    }

    public void putSkyLightData(int sectionY, byte[] data) {
        if (data == null) {
            this.skyLight.remove(sectionY);
            return;
        }
        if (data.length != 2048) {
            return;
        }
        this.skyLight.put(sectionY, new NibbleArray(data));
    }

    public void putBlockLightData(int sectionY, byte[] data) {
        if (data == null) {
            this.blockLight.remove(sectionY);
            return;
        }
        if (data.length != 2048) {
            return;
        }
        this.blockLight.put(sectionY, new NibbleArray(data));
    }

    /** Every section that has blocks or light data. */
    public IntSet sectionYs() {
        IntOpenHashSet result = new IntOpenHashSet(
                this.skyLight.size() + this.blockLight.size() + this.column.getSections().size() + 4);
        result.addAll(this.skyLight.keySet());
        result.addAll(this.blockLight.keySet());
        result.addAll(this.column.getSections().keySet());
        return result;
    }

    public boolean isEmpty() {
        return this.skyLight.isEmpty() && this.blockLight.isEmpty() && this.column.isEmpty();
    }
}