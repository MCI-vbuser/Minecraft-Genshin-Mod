package com.vbuser.genshin.tool.mcgit;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.common.util.Constants;

import java.util.*;

public class ChunkMerger {

    private final NBTTagCompound baseRoot;
    private final NBTTagCompound remoteRoot;
    private final NBTTagCompound worldRoot;

    public ChunkMerger(NBTTagCompound baseRoot, NBTTagCompound remoteRoot, NBTTagCompound worldRoot) {
        this.baseRoot = baseRoot;
        this.remoteRoot = remoteRoot;
        this.worldRoot = worldRoot;
    }

    public NBTTagCompound merge() {
        if (worldRoot == null) {
            return remoteRoot.copy();
        }

        NBTTagCompound baseLevel = baseRoot != null ? baseRoot.getCompoundTag("Level") : null;
        NBTTagCompound remoteLevel = remoteRoot.getCompoundTag("Level");
        NBTTagCompound worldLevel = worldRoot.getCompoundTag("Level");

        if (remoteLevel.equals(worldLevel)) {
            return worldRoot.copy();
        }
        NBTTagCompound mergedLevel = worldLevel.copy();
        mergeBlocks(baseLevel, remoteLevel, mergedLevel);
        mergeTileEntities(baseLevel, remoteLevel, mergedLevel);
        NBTTagCompound mergedRoot = new NBTTagCompound();
        mergedRoot.setTag("Level", mergedLevel);
        if (worldRoot.hasKey("DataVersion")) {
            mergedRoot.setInteger("DataVersion", worldRoot.getInteger("DataVersion"));
        }
        return mergedRoot;
    }

    private void mergeBlocks(NBTTagCompound baseLevel, NBTTagCompound remoteLevel, NBTTagCompound worldLevel) {
        NBTTagList baseSections = baseLevel != null ? baseLevel.getTagList("Sections", Constants.NBT.TAG_COMPOUND) : new NBTTagList();
        NBTTagList remoteSections = remoteLevel.getTagList("Sections", Constants.NBT.TAG_COMPOUND);
        NBTTagList worldSections = worldLevel.getTagList("Sections", Constants.NBT.TAG_COMPOUND);

        Map<Integer, SectionData> baseSecMap = parseSections(baseSections);
        Map<Integer, SectionData> remoteSecMap = parseSections(remoteSections);
        Map<Integer, SectionData> worldSecMap = parseSections(worldSections);

        NBTTagList newSections = new NBTTagList();

        for (int yIndex = 0; yIndex < 16; yIndex++) {
            SectionData worldSec = worldSecMap.get(yIndex);
            SectionData baseSec = baseSecMap.get(yIndex);
            SectionData remoteSec = remoteSecMap.get(yIndex);

            if (remoteSec == null) {
                if (worldSec != null) {
                    newSections.appendTag(worldSec.toNBT());
                } else if (baseSec != null) {
                    newSections.appendTag(baseSec.toNBT());
                }
                continue;
            }

            if (worldSec == null && baseSec == null) {
                newSections.appendTag(remoteSec.toNBT());
                continue;
            }

            if (worldSec != null && baseSec == null) {
                newSections.appendTag(worldSec.toNBT());
                continue;
            }

            assert worldSec != null;
            SectionData mergedSec = mergeSection(baseSec, remoteSec, worldSec);
            newSections.appendTag(mergedSec.toNBT());
        }

        worldLevel.setTag("Sections", newSections);
    }

    private SectionData mergeSection(SectionData baseSec, SectionData remoteSec, SectionData worldSec) {
        BlockState[] mergedBlocks = worldSec.blocks.clone();

        for (int i = 0; i < 4096; i++) {
            BlockState baseBlock = baseSec.blocks[i];
            BlockState remoteBlock = remoteSec.blocks[i];
            BlockState worldBlock = worldSec.blocks[i];

            if (baseBlock.equals(remoteBlock)) continue;

            if (worldBlock.equals(baseBlock)) {
                mergedBlocks[i] = remoteBlock.copy();
            }
        }

        SectionData result = new SectionData(worldSec.y);
        result.blocks = mergedBlocks;
        result.blockLight = worldSec.blockLight;
        result.skyLight = worldSec.skyLight;
        return result;
    }

    private Map<Integer, SectionData> parseSections(NBTTagList list) {
        Map<Integer, SectionData> map = new HashMap<>();
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound secTag = list.getCompoundTagAt(i);
            int y = secTag.getByte("Y") & 0xFF;
            SectionData data = new SectionData(y);
            data.blocks = extractBlocks(secTag);
            if (secTag.hasKey("BlockLight")) {
                data.blockLight = secTag.getByteArray("BlockLight");
            }
            if (secTag.hasKey("SkyLight")) {
                data.skyLight = secTag.getByteArray("SkyLight");
            }
            map.put(y, data);
        }
        return map;
    }

    private BlockState[] extractBlocks(NBTTagCompound sectionTag) {
        byte[] blocks = sectionTag.getByteArray("Blocks");
        byte[] add = sectionTag.hasKey("Add") ? sectionTag.getByteArray("Add") : new byte[2048];
        byte[] data = sectionTag.getByteArray("Data");

        BlockState[] result = new BlockState[4096];
        for (int i = 0; i < 4096; i++) {
            int id = blocks[i] & 0xFF;
            if (add.length >= 2048) {
                int addByte = add[i >> 1] & 0xFF;
                int addValue = (i & 1) == 0 ? (addByte & 0x0F) : ((addByte >> 4) & 0x0F);
                id |= (addValue << 8);
            }
            int meta;
            if ((i & 1) == 0) {
                meta = data[i >> 1] & 0x0F;
            } else {
                meta = (data[i >> 1] >> 4) & 0x0F;
            }
            result[i] = new BlockState(id, meta);
        }
        return result;
    }

    private void mergeTileEntities(NBTTagCompound baseLevel, NBTTagCompound remoteLevel, NBTTagCompound worldLevel) {
        NBTTagList baseTE = baseLevel != null ? baseLevel.getTagList("TileEntities", Constants.NBT.TAG_COMPOUND) : new NBTTagList();
        NBTTagList remoteTE = remoteLevel.getTagList("TileEntities", Constants.NBT.TAG_COMPOUND);
        NBTTagList worldTE = worldLevel.getTagList("TileEntities", Constants.NBT.TAG_COMPOUND);

        Map<BlockPos, NBTTagCompound> baseTEMap = indexTEByPos(baseTE);
        Map<BlockPos, NBTTagCompound> remoteTEMap = indexTEByPos(remoteTE);
        Map<BlockPos, NBTTagCompound> worldTEMap = indexTEByPos(worldTE);

        NBTTagList newList = new NBTTagList();

        for (Map.Entry<BlockPos, NBTTagCompound> entry : worldTEMap.entrySet()) {
            BlockPos pos = entry.getKey();
            NBTTagCompound worldTag = entry.getValue();
            NBTTagCompound baseTag = baseTEMap.get(pos);
            NBTTagCompound remoteTag = remoteTEMap.get(pos);

            if (worldTag.equals(baseTag)) {
                if (remoteTag != null && !remoteTag.equals(baseTag)) {
                    newList.appendTag(remoteTag.copy());
                } else {
                    newList.appendTag(worldTag.copy());
                }
            } else {
                newList.appendTag(worldTag.copy());
            }
        }

        for (Map.Entry<BlockPos, NBTTagCompound> entry : remoteTEMap.entrySet()) {
            if (!worldTEMap.containsKey(entry.getKey())) {
                if (!baseTEMap.containsKey(entry.getKey())) {
                    newList.appendTag(entry.getValue().copy());
                }
            }
        }

        worldLevel.setTag("TileEntities", newList);
    }

    private Map<BlockPos, NBTTagCompound> indexTEByPos(NBTTagList list) {
        Map<BlockPos, NBTTagCompound> map = new HashMap<>();
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound tag = list.getCompoundTagAt(i);
            int x = tag.getInteger("x");
            int y = tag.getInteger("y");
            int z = tag.getInteger("z");
            map.put(new BlockPos(x, y, z), tag);
        }
        return map;
    }

    static class BlockState {
        int id;
        int meta;
        BlockState(int id, int meta) { this.id = id; this.meta = meta; }
        BlockState copy() { return new BlockState(id, meta); }
        @Override
        public boolean equals(Object o) {
            if (!(o instanceof BlockState)) return false;
            BlockState that = (BlockState) o;
            return this.id == that.id && this.meta == that.meta;
        }
        @Override
        public int hashCode() {
            return id * 31 + meta;
        }
    }

    static class SectionData {
        int y;
        BlockState[] blocks = new BlockState[4096];
        byte[] blockLight;
        byte[] skyLight;

        SectionData(int y) { this.y = y; }

        NBTTagCompound toNBT() {
            NBTTagCompound tag = new NBTTagCompound();
            tag.setByte("Y", (byte) y);

            byte[] blocksArr = new byte[4096];
            byte[] dataArr = new byte[2048];
            byte[] addArr;
            boolean needAdd = false;

            for (int i = 0; i < 4096; i++) {
                BlockState bs = blocks[i];
                int id = bs.id;
                int meta = bs.meta & 0xF;
                if (id > 255) needAdd = true;
                blocksArr[i] = (byte) (id & 0xFF);
                if ((i & 1) == 0) {
                    dataArr[i >> 1] = (byte) ((dataArr[i >> 1] & 0xF0) | meta);
                } else {
                    dataArr[i >> 1] = (byte) ((dataArr[i >> 1] & 0x0F) | (meta << 4));
                }
            }

            if (needAdd) {
                addArr = new byte[2048];
                for (int i = 0; i < 4096; i++) {
                    int high = (blocks[i].id >> 8) & 0xF;
                    if ((i & 1) == 0) {
                        addArr[i >> 1] = (byte) ((addArr[i >> 1] & 0xF0) | high);
                    } else {
                        addArr[i >> 1] = (byte) ((addArr[i >> 1] & 0x0F) | (high << 4));
                    }
                }
                tag.setByteArray("Add", addArr);
            }

            tag.setByteArray("Blocks", blocksArr);
            tag.setByteArray("Data", dataArr);
            if (blockLight != null) tag.setByteArray("BlockLight", blockLight);
            if (skyLight != null) tag.setByteArray("SkyLight", skyLight);
            return tag;
        }
    }

    static class BlockPos {
        final int x, y, z;
        BlockPos(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
        @Override
        public boolean equals(Object o) {
            if (o instanceof BlockPos) {
                BlockPos p = (BlockPos) o;
                return p.x == x && p.y == y && p.z == z;
            }
            return false;
        }
        @Override
        public int hashCode() { return (x * 31 + y) * 31 + z; }
    }
}