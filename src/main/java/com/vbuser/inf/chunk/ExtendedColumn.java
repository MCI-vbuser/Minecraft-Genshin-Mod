package com.vbuser.inf.chunk;

import com.vbuser.inf.light.ColumnLight;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.block.Block;
import net.minecraft.block.BlockLiquid;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import java.util.BitSet;
import java.util.Map;

/**
 * Extended (Y outside 0..255) block storage for one chunk column.
 *
 * <p>Sections are keyed by an int section Y and every section also keeps a
 * 4096-bit non-air bitmap. Render, network and save paths iterate only the set
 * bits instead of scanning all 4096 positions of every section.</p>
 *
 * <p>The column also owns the {@link ColumnLight} of the chunk, because light
 * sections are keyed exactly like block sections but must survive independently
 * of them (a cave under a roof has light but no blocks in most of its
 * sections).</p>
 */
public class ExtendedColumn {

    private static final int SECTION_VOLUME = 4096;

    private final Chunk chunk;
    private final ColumnLight light;
    private final Int2ObjectOpenHashMap<ExtendedBlockStorage> sections = new Int2ObjectOpenHashMap<>();
    private final Int2ObjectOpenHashMap<BitSet> nonAir = new Int2ObjectOpenHashMap<>();

    public static boolean allowFluidToCreateNewSections = false;

    public ExtendedColumn(Chunk chunk) {
        this.chunk = chunk;
        this.light = new ColumnLight(this);
    }

    public Chunk getChunk() {
        return this.chunk;
    }

    public ColumnLight getLight() {
        return this.light;
    }

    public Map<Integer, ExtendedBlockStorage> getSections() {
        return sections;
    }

    public ExtendedBlockStorage getSection(int sectionY) {
        return sections.get(sectionY);
    }

    public BitSet getNonAir(int sectionY) {
        return nonAir.get(sectionY);
    }

    public boolean isEmpty() {
        return sections.isEmpty();
    }

    /**
     * Drop every extended block and every light value of this column.
     *
     * <p>Only the client uses this, as the first step of a whole-chunk resync:
     * the server is about to send the complete new contents, and anything that
     * is not re-sent (blocks removed by the new base world) must disappear
     * instead of lingering in the client copy.</p>
     */
    public void clear() {
        sections.clear();
        nonAir.clear();
        light.reset();
    }

    /**
     * Put a section loaded from disk and rebuild its non-air bitmap.
     */
    public void putSection(int sectionY, ExtendedBlockStorage section) {
        if (section == null || section.isEmpty()) {
            sections.remove(sectionY);
            nonAir.remove(sectionY);
            return;
        }
        sections.put(sectionY, section);
        BitSet bits = new BitSet(SECTION_VOLUME);
        for (int index = 0; index < SECTION_VOLUME; index++) {
            if (section.get(indexX(index), indexY(index), indexZ(index)).getBlock() != Blocks.AIR) {
                bits.set(index);
            }
        }
        nonAir.put(sectionY, bits);
    }

    public IBlockState getBlock(int x, int y, int z) {
        ExtendedBlockStorage section = sections.get(y >> 4);
        if (section == null) {
            return Blocks.AIR.getDefaultState();
        }
        return section.get(x & 15, y & 15, z & 15);
    }

    /**
     * Store one block without any callback, block update or light work.
     *
     * <p>Used by the client section packet: a vanilla style per block
     * {@code setBlockState} would run 4096 block lifecycles and light checks for
     * one packet. The caller re-derives the column top and lighting
     * afterward.</p>
     */
    public void importBlock(int x, int y, int z, IBlockState state) {
        int sectionY = y >> 4;
        ExtendedBlockStorage section = sections.get(sectionY);
        BitSet bits = nonAir.get(sectionY);
        if (section == null) {
            if (state.getBlock() == Blocks.AIR) {
                return;
            }
            section = new ExtendedBlockStorage(sectionY << 4, this.chunk.getWorld().provider.hasSkyLight());
            sections.put(sectionY, section);
            bits = new BitSet(SECTION_VOLUME);
            nonAir.put(sectionY, bits);
        }
        int index = indexOf(x, y, z);
        section.set(x & 15, y & 15, z & 15, state);
        if (state.getBlock() == Blocks.AIR) {
            bits.clear(index);
        } else {
            bits.set(index);
        }
        if (this.chunk.getWorld().isRemote) {
            // A bulk import still has to invalidate the section mesh.
            com.vbuser.inf.light.LightRenderHook.cellChanged(this.chunk.getWorld(), x, y, z);
        }
    }

    /**
     * Store one extended block.
     *
     * <p>Neighbor updates / client notifications are intentionally NOT done
     * here: the caller mirrors vanilla {@code World.setBlockState} and calls
     * {@code markAndNotifyBlock} with the real flags. This method only handles
     * storage, block lifecycle callbacks and tile entities.</p>
     */
    public IBlockState setBlock(World world, int x, int y, int z, IBlockState newState, int ignored) {
        int sectionY = y >> 4;
        ExtendedBlockStorage section = sections.get(sectionY);
        BitSet bits = nonAir.get(sectionY);
        boolean created = false;

        if (section == null) {
            if (newState.getBlock() == Blocks.AIR) {
                return null;
            }
            if (!allowFluidToCreateNewSections
                    && newState.getBlock() instanceof BlockLiquid) {
                return null;
            }
            section = new ExtendedBlockStorage(sectionY << 4, world.provider.hasSkyLight());
            sections.put(sectionY, section);
            bits = new BitSet(SECTION_VOLUME);
            nonAir.put(sectionY, bits);
            created = true;
        }

        IBlockState oldState = section.get(x & 15, y & 15, z & 15);
        if (oldState == newState) {
            return null;
        }

        // Skylight is column based: capture the top before the write so the
        // relight can tell whether the column got more open or more blocked.
        int oldTop = light.top(x & 15, z & 15);

        int index = indexOf(x, y, z);
        section.set(x & 15, y & 15, z & 15, newState);

        boolean oldAir = oldState.getBlock() == Blocks.AIR;
        boolean newAir = newState.getBlock() == Blocks.AIR;
        if (oldAir && !newAir) {
            bits.set(index);
        } else if (!oldAir && newAir) {
            bits.clear(index);
        }

        light.onBlockChanged(world, x, y, z, oldState, newState, oldTop);
        if (world.isRemote) {
            // The section mesh bakes the block into its vertices.
            com.vbuser.inf.light.LightRenderHook.cellChanged(world, x, y, z);
        }
        if (created) {
            light.onSectionCreated(world, sectionY);
        }

        // A block sitting in the Y=-1 / Y=256 seam changes the occlusion the
        // vanilla render section next to it sees at its border, so that mesh has
        // to be rebuilt even though the block itself is not part of it.
        if ((y == -1 || y == 256) && world.isRemote) {
            BlockPos seam = new BlockPos(x, y == -1 ? 0 : 255, z);
            world.markBlockRangeForRenderUpdate(seam, seam);
        }

        Block oldBlock = oldState.getBlock();
        Block newBlock = newState.getBlock();
        BlockPos pos = new BlockPos(x, y, z);

        if (!world.isRemote) {
            if (oldBlock != newBlock) {
                oldBlock.breakBlock(world, pos, oldState);
            }
            inf$refreshTileEntity(world, pos, oldState, newState);

            if (oldBlock != newBlock
                    && (!world.captureBlockSnapshots || newBlock.hasTileEntity(newState))) {
                newBlock.onBlockAdded(world, pos, newState);
            }

            if (newBlock instanceof BlockLiquid) {
                world.scheduleUpdate(pos, newBlock, newBlock.tickRate(world));
            }
        } else if (oldBlock.hasTileEntity(oldState)) {
            inf$refreshTileEntity(world, pos, oldState, newState);
        }

        // Vanilla Chunk.setBlockState() ends with this block on *both* sides and
        // for *every* state change of a tile entity block, not only when the
        // block itself is replaced. TileEntity caches blockType/blockMetadata,
        // and blocks that flip only their own state (BlockHopper.ENABLED coming
        // from redstone is the canonical case) read that cached metadata back.
        // Without the invalidation the tile entity keeps acting on the state it
        // saw before the change - a hopper would ignore its redstone lock until
        // something else (e.g. markDirty() from a GUI slot change) refreshes the
        // cache, which is why extended-region contraptions behaved differently.
        if (newBlock.hasTileEntity(newState)) {
            TileEntity tileEntity = inf$getTileEntity(world, pos);
            if (tileEntity == null) {
                tileEntity = newBlock.createTileEntity(world, newState);
                if (tileEntity != null) {
                    world.setTileEntity(pos, tileEntity);
                }
            }
            if (tileEntity != null) {
                tileEntity.updateContainingBlockInfo();
            }
        }

        // Drop a section that became completely empty; otherwise a long-lived
        // world accumulates thousands of empty section objects.
        if (newAir && section.isEmpty() && sections.get(sectionY) == section) {
            sections.remove(sectionY);
            nonAir.remove(sectionY);
        }

        return oldState;
    }

    /**
     * Existing tile entity lookup that never creates one, mirroring
     * {@code Chunk.getTileEntity(pos, Chunk.EnumCreateEntityType.CHECK)} which
     * is what vanilla {@code Chunk.setBlockState} uses for its TE bookkeeping.
     */
    private static TileEntity inf$getTileEntity(World world, BlockPos pos) {
        return world.getChunkFromBlockCoords(pos)
                .getTileEntity(pos, Chunk.EnumCreateEntityType.CHECK);
    }

    /**
     * Drop a tile entity that does not survive this state change. This is
     * {@code Chunk.setBlockState}'s {@code te.shouldRefresh(...)} step; it
     * replaces the old "new state has no tile entity" shortcut so that modded
     * tile entities may keep their own refresh policy.
     */
    private static void inf$refreshTileEntity(World world, BlockPos pos,
                                              IBlockState oldState, IBlockState newState) {
        TileEntity tileEntity = inf$getTileEntity(world, pos);
        if (tileEntity != null && tileEntity.shouldRefresh(world, pos, oldState, newState)) {
            world.removeTileEntity(pos);
        }
    }

    public static int indexOf(int x, int y, int z) {
        return ((x & 15) << 8) | ((z & 15) << 4) | (y & 15);
    }

    public static int indexX(int index) {
        return (index >> 8) & 15;
    }

    public static int indexY(int index) {
        return index & 15;
    }

    public static int indexZ(int index) {
        return (index >> 4) & 15;
    }
}
