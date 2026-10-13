package com.vbuser.inf.chunk;

import net.minecraft.block.Block;
import net.minecraft.block.BlockLiquid;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import java.util.HashMap;
import java.util.Map;

public class ExtendedColumn {

    private final Map<Integer, ExtendedBlockStorage> sections = new HashMap<>();

    public static boolean allowFluidToCreateNewSections = false;

    public Map<Integer, ExtendedBlockStorage> getSections() {
        return sections;
    }

    public boolean isEmpty() {
        return sections.isEmpty();
    }

    public IBlockState getBlock(int x, int y, int z) {
        ExtendedBlockStorage section = sections.get(y >> 4);
        if (section == null) {
            return Blocks.AIR.getDefaultState();
        }
        return section.get(x & 15, y & 15, z & 15);
    }

    public IBlockState setBlock(World world, int x, int y, int z, IBlockState newState) {
        int sectionY = y >> 4;
        ExtendedBlockStorage section = sections.get(sectionY);

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
        }

        IBlockState oldState = section.get(x & 15, y & 15, z & 15);
        if (oldState == newState) {
            return null;
        }

        section.set(x & 15, y & 15, z & 15, newState);

        Block oldBlock = oldState.getBlock();
        Block newBlock = newState.getBlock();
        BlockPos pos = new BlockPos(x, y, z);

        if (!world.isRemote) {
            if (oldBlock != newBlock) {
                oldBlock.breakBlock(world, pos, oldState);

                if (oldBlock.hasTileEntity(oldState) && !newBlock.hasTileEntity(newState)) {
                    world.removeTileEntity(pos);
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

                if (!world.captureBlockSnapshots || newBlock.hasTileEntity(newState)) {
                    newBlock.onBlockAdded(world, pos, newState);
                }
            }

            world.notifyNeighborsOfStateChange(pos, oldBlock, true);

            if (newState.hasComparatorInputOverride()) {
                world.updateComparatorOutputLevel(pos, newBlock);
            }
            if (newBlock instanceof BlockLiquid) {
                world.scheduleUpdate(pos, newBlock, newBlock.tickRate(world));
            }
            world.notifyBlockUpdate(pos, oldState, newState, 3);
        } else {
            if (oldBlock != newBlock
                    && oldBlock.hasTileEntity(oldState)
                    && !newBlock.hasTileEntity(newState)) {
                world.removeTileEntity(pos);
            }
        }

        return oldState;
    }
}