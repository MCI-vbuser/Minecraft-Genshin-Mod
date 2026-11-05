package com.vbuser.mixin;

import com.vbuser.inf.chunk.ColumnManager;
import com.vbuser.inf.chunk.ExtendedColumn;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagByteArray;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagIntArray;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.AnvilChunkLoader;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Arrays;
import java.util.BitSet;
import java.util.Map;

@Mixin(AnvilChunkLoader.class)
public abstract class MixinAnvilChunkLoader {

    @Unique
    private static final String INF_SECTIONS = "inf:ExtendedSections";

    @Unique
    private static final String INF_FORMAT = "inf:Format";

    @Unique
    private static final int INF_FORMAT_DATA_ARRAY = 2;

    @Unique
    private static final int INF_FORMAT_PALETTE = 3;

    @Inject(
            method = "writeChunkToNBT(Lnet/minecraft/world/chunk/Chunk;Lnet/minecraft/world/World;Lnet/minecraft/nbt/NBTTagCompound;)V",
            at = @At("TAIL")
    )
    private void inf$writeChunkToNBT(Chunk chunk, World world, NBTTagCompound nbt, CallbackInfo ci) {
        ExtendedColumn col = ColumnManager.getIfPresent(chunk);
        if (col == null || col.isEmpty()) {
            nbt.removeTag(INF_SECTIONS);
            nbt.removeTag(INF_FORMAT);
            return;
        }

        NBTTagList sections = new NBTTagList();
        for (Map.Entry<Integer, ExtendedBlockStorage> entry : col.getSections().entrySet()) {
            ExtendedBlockStorage ebs = entry.getValue();
            if (ebs.isEmpty()) {
                continue;
            }

            BitSet nonAir = col.getNonAir(entry.getKey());
            if (nonAir == null || nonAir.isEmpty()) {
                continue;
            }

            // Palette + byte indices: usually 4 KiB per section instead of
            // one NBT compound per block or 16 KiB of raw state ids.
            int[] palette = new int[16];
            int paletteSize = 1;
            palette[0] = Block.getStateId(Blocks.AIR.getDefaultState());
            Int2IntOpenHashMap paletteLookup = new Int2IntOpenHashMap();
            paletteLookup.defaultReturnValue(-1);
            paletteLookup.put(palette[0], 0);

            int[] indices = new int[4096];
            for (int index = nonAir.nextSetBit(0); index >= 0; index = nonAir.nextSetBit(index + 1)) {
                IBlockState state = ebs.get(
                        ExtendedColumn.indexX(index),
                        ExtendedColumn.indexY(index),
                        ExtendedColumn.indexZ(index));
                int stateId = Block.getStateId(state);
                int paletteIndex = paletteLookup.get(stateId);
                if (paletteIndex < 0) {
                    if (paletteSize == palette.length) {
                        palette = Arrays.copyOf(palette, palette.length * 2);
                    }
                    paletteIndex = paletteSize++;
                    palette[paletteIndex] = stateId;
                    paletteLookup.put(stateId, paletteIndex);
                }
                indices[index] = paletteIndex;
            }

            NBTTagCompound sec = new NBTTagCompound();
            sec.setInteger("Y", entry.getKey());
            sec.setTag("Palette", new NBTTagIntArray(Arrays.copyOf(palette, paletteSize)));
            if (paletteSize <= 256) {
                byte[] packed = new byte[4096];
                for (int i = 0; i < 4096; i++) {
                    packed[i] = (byte) indices[i];
                }
                sec.setTag("Indices", new NBTTagByteArray(packed));
            } else {
                sec.setTag("Indices", new NBTTagIntArray(indices));
            }
            sections.appendTag(sec);
        }

        if (sections.tagCount() > 0) {
            nbt.setTag(INF_SECTIONS, sections);
            nbt.setInteger(INF_FORMAT, INF_FORMAT_PALETTE);
        } else {
            nbt.removeTag(INF_SECTIONS);
            nbt.removeTag(INF_FORMAT);
        }
    }

    @Inject(
            method = "readChunkFromNBT(Lnet/minecraft/world/World;Lnet/minecraft/nbt/NBTTagCompound;)Lnet/minecraft/world/chunk/Chunk;",
            at = @At("RETURN")
    )
    private void inf$readChunkFromNBT(World world, NBTTagCompound nbt,
                                      CallbackInfoReturnable<Chunk> cir) {
        Chunk chunk = cir.getReturnValue();
        if (chunk == null || !nbt.hasKey(INF_SECTIONS, 9)) {
            return;
        }

        ExtendedColumn col = ColumnManager.get(chunk);
        boolean storeSky = world.provider.hasSkyLight();
        int format = nbt.getInteger(INF_FORMAT);

        NBTTagList sections = nbt.getTagList(INF_SECTIONS, 10);
        for (int i = 0; i < sections.tagCount(); i++) {
            NBTTagCompound sec = sections.getCompoundTagAt(i);
            int sectionY = sec.getInteger("Y");
            ExtendedBlockStorage ebs = new ExtendedBlockStorage(sectionY << 4, storeSky);

            if (format >= INF_FORMAT_PALETTE
                    && sec.hasKey("Palette", 11)
                    && (sec.hasKey("Indices", 7) || sec.hasKey("Indices", 11))) {
                int[] palette = ((NBTTagIntArray) sec.getTag("Palette")).getIntArray();
                if (sec.hasKey("Indices", 7)) {
                    byte[] indices = sec.getByteArray("Indices");
                    int limit = Math.min(indices.length, 4096);
                    for (int index = 0; index < limit; index++) {
                        inf$setFromPalette(ebs, palette, indices[index] & 0xFF, index);
                    }
                } else {
                    int[] indices = ((NBTTagIntArray) sec.getTag("Indices")).getIntArray();
                    int limit = Math.min(indices.length, 4096);
                    for (int index = 0; index < limit; index++) {
                        inf$setFromPalette(ebs, palette, indices[index], index);
                    }
                }
            } else if (format >= INF_FORMAT_DATA_ARRAY && sec.hasKey("Data", 11)) {
                int[] data = ((NBTTagIntArray) sec.getTag("Data")).getIntArray();
                int limit = Math.min(data.length, 4096);
                for (int index = 0; index < limit; index++) {
                    int stateId = data[index];
                    if (stateId < 0) {
                        continue;
                    }
                    inf$setState(ebs, index, stateId);
                }
            } else {
                // Legacy format: one compound per non-air block.
                NBTTagList blocks = sec.getTagList("Blocks", 10);
                for (int j = 0; j < blocks.tagCount(); j++) {
                    NBTTagCompound b = blocks.getCompoundTagAt(j);
                    int p = b.getShort("P") & 0xFFFF;
                    int lx = (p >> 8) & 15;
                    int lz = (p >> 4) & 15;
                    int ly = p & 15;
                    IBlockState state = Block.getStateById(b.getInteger("S"));
                    if (state.getBlock() != Blocks.AIR) {
                        ebs.set(lx, ly, lz, state);
                    }
                }
            }

            col.putSection(sectionY, ebs);
        }
    }

    @Unique
    private static void inf$setFromPalette(ExtendedBlockStorage ebs, int[] palette, int paletteIndex, int index) {
        if (paletteIndex < 0 || paletteIndex >= palette.length) {
            return;
        }
        inf$setState(ebs, index, palette[paletteIndex]);
    }

    @Unique
    private static void inf$setState(ExtendedBlockStorage ebs, int index, int stateId) {
        IBlockState state = Block.getStateById(stateId);
        if (state.getBlock() != Blocks.AIR) {
            ebs.set(
                    ExtendedColumn.indexX(index),
                    ExtendedColumn.indexY(index),
                    ExtendedColumn.indexZ(index),
                    state);
        }
    }
}
