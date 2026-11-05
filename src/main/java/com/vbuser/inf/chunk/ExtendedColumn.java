package com.vbuser.inf.chunk;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.block.Block;
import net.minecraft.block.BlockLiquid;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import java.util.BitSet;
import java.util.Map;

/**
 * Extended (Y outside 0..255) block storage for one chunk column.
 *
 * <p>Sections are keyed by an int section Y and every section also keeps a
 * 4096-bit non-air bitmap. Render, network and save paths iterate only the set
 * bits instead of scanning all 4096 positions of every section.</p>
 */
public class ExtendedColumn {

    private static final int SECTION_VOLUME = 4096;

    private final Int2ObjectOpenHashMap<ExtendedBlockStorage> sections = new Int2ObjectOpenHashMap<>();
    private final Int2ObjectOpenHashMap<BitSet> nonAir = new Int2ObjectOpenHashMap<>();

    public static boolean allowFluidToCreateNewSections = false;

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
     * Store one extended block.
     *
     * <p>Neighbor updates / client notifications are intentionally NOT done
     * here: the caller mirrors vanilla {@code World.setBlockState} and calls
     * {@code markAndNotifyBlock} with the real flags. This method only handles
     * storage, block lifecycle callbacks and tile entities.</p>
     */
    public IBlockState setBlock(World world, int x, int y, int z, IBlockState newState, int flags) {
        int sectionY = y >> 4;
        ExtendedBlockStorage section = sections.get(sectionY);
        BitSet bits = nonAir.get(sectionY);

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
        }

        IBlockState oldState = section.get(x & 15, y & 15, z & 15);
        if (oldState == newState) {
            return null;
        }

        int index = indexOf(x, y, z);
        section.set(x & 15, y & 15, z & 15, newState);

        boolean oldAir = oldState.getBlock() == Blocks.AIR;
        boolean newAir = newState.getBlock() == Blocks.AIR;
        if (oldAir && !newAir) {
            bits.set(index);
        } else if (!oldAir && newAir) {
            bits.clear(index);
        }

        Block oldBlock = oldState.getBlock();
        Block newBlock = newState.getBlock();
        BlockPos pos = new BlockPos(x, y, z);

        if (!world.isRemote) {
            if (oldBlock != newBlock) {
                oldBlock.breakBlock(world, pos, oldState);

                if (oldBlock.hasTileEntity(oldState) && !newBlock.hasTileEntity(newState)) {
                    world.removeTileEntity(pos);
                }

                if (!world.captureBlockSnapshots || newBlock.hasTileEntity(newState)) {
                    newBlock.onBlockAdded(world, pos, newState);
                }

                if (newBlock.hasTileEntity(newState)) {
                    TileEntity existing = world.getTileEntity(pos);
                    if (existing == null || existing.getBlockType() != newBlock) {
                        if (existing != null) {
                            existing.invalidate();
                        }
                        TileEntity te = newBlock.createTileEntity(world, newState);
                        if (te != null) {
                            world.setTileEntity(pos, te);
                        }
                    }
                }
            }

            if (newBlock instanceof BlockLiquid) {
                world.scheduleUpdate(pos, newBlock, newBlock.tickRate(world));
            }
        } else if (oldBlock != newBlock
                && oldBlock.hasTileEntity(oldState)
                && !newBlock.hasTileEntity(newState)) {
            world.removeTileEntity(pos);
        }

        // Drop a section that became completely empty; otherwise a long-lived
        // world accumulates thousands of empty section objects.
        if (newAir && section.isEmpty() && sections.get(sectionY) == section) {
            sections.remove(sectionY);
            nonAir.remove(sectionY);
        }

        return oldState;
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
