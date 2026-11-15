package com.vbuser.genshin.tool.mcgit;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagByteArray;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagIntArray;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.common.util.Constants;

import java.util.*;

public class ChunkMerger {

    /**
     * The infinite-height module stores everything outside Y 0..255 in its own
     * list inside the chunk NBT, not in vanilla "Sections". Without merging it
     * a remote update would never reach the extended region, and keeping the
     * old light cache next to merged blocks would leave stale lighting.
     */
    private static final String INF_SECTIONS = "inf:ExtendedSections";
    private static final String INF_FORMAT = "inf:Format";
    private static final String INF_LIGHT = "inf:Light";
    private static final int INF_FORMAT_DATA_ARRAY = 2;
    private static final int INF_FORMAT_PALETTE = 3;
    private static final int SECTION_VOLUME = 4096;

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
        mergeExtendedSections(baseLevel, remoteLevel, mergedLevel);
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

    // ------------------------------------------------------------------
    // Infinite height: Y < 0 / Y > 255
    // ------------------------------------------------------------------

    /**
     * Three-way merge the extended block sections exactly like the vanilla
     * ones: keep the player's change, otherwise take the remote change. The
     * stored light arrays and the {@code inf:Light} trust marker are dropped
     * because they described the old block set, not the merged one; the mod
     * re-derives them the next time the chunk is loaded.
     */
    private void mergeExtendedSections(NBTTagCompound baseLevel, NBTTagCompound remoteLevel,
                                       NBTTagCompound mergedLevel) {
        int air = Block.getStateId(Blocks.AIR.getDefaultState());
        Map<Integer, int[]> base = readExtendedSections(baseLevel, air);
        Map<Integer, int[]> remote = readExtendedSections(remoteLevel, air);
        Map<Integer, int[]> world = readExtendedSections(mergedLevel, air);

        if (base.isEmpty() && remote.isEmpty() && world.isEmpty()) {
            return;
        }

        TreeSet<Integer> sectionYs = new TreeSet<>();
        sectionYs.addAll(base.keySet());
        sectionYs.addAll(remote.keySet());
        sectionYs.addAll(world.keySet());

        NBTTagList sections = new NBTTagList();
        for (int sectionY : sectionYs) {
            int[] merged = mergeExtendedSection(base.get(sectionY), remote.get(sectionY),
                    world.get(sectionY), air);
            if (merged == null) {
                continue;
            }
            sections.appendTag(encodeExtendedSection(sectionY, merged, air));
        }

        if (sections.tagCount() > 0) {
            mergedLevel.setTag(INF_SECTIONS, sections);
            mergedLevel.setInteger(INF_FORMAT, INF_FORMAT_PALETTE);
        } else {
            mergedLevel.removeTag(INF_SECTIONS);
            mergedLevel.removeTag(INF_FORMAT);
        }
        mergedLevel.removeTag(INF_LIGHT);
    }

    private Map<Integer, int[]> readExtendedSections(NBTTagCompound level, int air) {
        Map<Integer, int[]> result = new HashMap<>();
        if (level == null || !level.hasKey(INF_SECTIONS, Constants.NBT.TAG_LIST)) {
            return result;
        }
        int format = level.getInteger(INF_FORMAT);
        NBTTagList sections = level.getTagList(INF_SECTIONS, Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < sections.tagCount(); i++) {
            NBTTagCompound section = sections.getCompoundTagAt(i);
            int sectionY = section.getInteger("Y");
            result.put(sectionY, decodeExtendedSection(section, format, air));
        }
        return result;
    }

    /**
     * Decode one saved extended section into 4096 state ids, index layout
     * {@code (x << 8) | (z << 4) | y} (same as {@code ExtendedColumn}).
     */
    private int[] decodeExtendedSection(NBTTagCompound section, int format, int air) {
        int[] states = new int[SECTION_VOLUME];
        Arrays.fill(states, air);

        if (format >= INF_FORMAT_PALETTE
                && section.hasKey("Palette", Constants.NBT.TAG_INT_ARRAY)
                && (section.hasKey("Indices", Constants.NBT.TAG_BYTE_ARRAY)
                || section.hasKey("Indices", Constants.NBT.TAG_INT_ARRAY))) {
            int[] palette = ((NBTTagIntArray) section.getTag("Palette")).getIntArray();
            if (section.hasKey("Indices", Constants.NBT.TAG_BYTE_ARRAY)) {
                byte[] indices = section.getByteArray("Indices");
                int limit = Math.min(indices.length, SECTION_VOLUME);
                for (int index = 0; index < limit; index++) {
                    int paletteIndex = indices[index] & 0xFF;
                    if (paletteIndex < palette.length) {
                        states[index] = palette[paletteIndex];
                    }
                }
            } else {
                int[] indices = ((NBTTagIntArray) section.getTag("Indices")).getIntArray();
                int limit = Math.min(indices.length, SECTION_VOLUME);
                for (int index = 0; index < limit; index++) {
                    int paletteIndex = indices[index];
                    if (paletteIndex >= 0 && paletteIndex < palette.length) {
                        states[index] = palette[paletteIndex];
                    }
                }
            }
        } else if (format >= INF_FORMAT_DATA_ARRAY
                && section.hasKey("Data", Constants.NBT.TAG_INT_ARRAY)) {
            int[] data = ((NBTTagIntArray) section.getTag("Data")).getIntArray();
            int limit = Math.min(data.length, SECTION_VOLUME);
            for (int index = 0; index < limit; index++) {
                if (data[index] >= 0) {
                    states[index] = data[index];
                }
            }
        } else {
            NBTTagList blocks = section.getTagList("Blocks", Constants.NBT.TAG_COMPOUND);
            for (int i = 0; i < blocks.tagCount(); i++) {
                NBTTagCompound block = blocks.getCompoundTagAt(i);
                int packed = block.getShort("P") & 0xFFFF;
                int index = ((packed >> 8) & 15) << 8 | ((packed >> 4) & 15) << 4 | (packed & 15);
                states[index] = block.getInteger("S");
            }
        }
        return states;
    }

    private int[] mergeExtendedSection(int[] base, int[] remote, int[] world, int air) {
        int[] merged = new int[SECTION_VOLUME];
        boolean nonAir = false;
        for (int index = 0; index < SECTION_VOLUME; index++) {
            int baseState = base != null ? base[index] : air;
            int remoteState = remote != null ? remote[index] : air;
            int worldState = world != null ? world[index] : air;
            int value = worldState;
            // Same rule as the vanilla merge: the player's change wins a
            // conflict, otherwise the remote change is taken.
            if (remoteState != baseState && worldState == baseState) {
                value = remoteState;
            }
            merged[index] = value;
            if (value != air) {
                nonAir = true;
            }
        }
        return nonAir ? merged : null;
    }

    private NBTTagCompound encodeExtendedSection(int sectionY, int[] states, int air) {
        int[] palette = new int[16];
        int paletteSize = 1;
        palette[0] = air;
        Map<Integer, Integer> lookup = new HashMap<>();
        lookup.put(air, 0);

        int[] indices = new int[SECTION_VOLUME];
        for (int index = 0; index < SECTION_VOLUME; index++) {
            int state = states[index];
            if (state == air) {
                continue;
            }
            Integer paletteIndex = lookup.get(state);
            if (paletteIndex == null) {
                if (paletteSize == palette.length) {
                    palette = Arrays.copyOf(palette, palette.length * 2);
                }
                paletteIndex = paletteSize++;
                palette[paletteIndex] = state;
                lookup.put(state, paletteIndex);
            }
            indices[index] = paletteIndex;
        }

        NBTTagCompound section = new NBTTagCompound();
        section.setInteger("Y", sectionY);
        section.setTag("Palette", new NBTTagIntArray(Arrays.copyOf(palette, paletteSize)));
        if (paletteSize <= 256) {
            byte[] packed = new byte[SECTION_VOLUME];
            for (int i = 0; i < SECTION_VOLUME; i++) {
                packed[i] = (byte) indices[i];
            }
            section.setTag("Indices", new NBTTagByteArray(packed));
        } else {
            section.setTag("Indices", new NBTTagIntArray(indices));
        }
        return section;
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