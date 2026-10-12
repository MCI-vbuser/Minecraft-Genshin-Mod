package com.vbuser.mixin;

import com.vbuser.inf.chunk.ColumnManager;
import com.vbuser.inf.chunk.ExtendedColumn;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
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

import java.util.Map;

@Mixin(AnvilChunkLoader.class)
public abstract class MixinAnvilChunkLoader {

    @Unique
    private static final String INF_SECTIONS = "inf:ExtendedSections";

    @Inject(
            method = "writeChunkToNBT(Lnet/minecraft/world/chunk/Chunk;Lnet/minecraft/world/World;Lnet/minecraft/nbt/NBTTagCompound;)V",
            at = @At("TAIL")
    )
    private void inf$writeChunkToNBT(Chunk chunk, World world, NBTTagCompound nbt, CallbackInfo ci) {
        ExtendedColumn col = ColumnManager.getIfPresent(chunk);
        if (col == null || col.isEmpty()) {
            return;
        }

        NBTTagList sections = new NBTTagList();
        for (Map.Entry<Integer, ExtendedBlockStorage> e : col.getSections().entrySet()) {
            ExtendedBlockStorage ebs = e.getValue();
            NBTTagList blocks = new NBTTagList();

            for (int ly = 0; ly < 16; ly++) {
                for (int lz = 0; lz < 16; lz++) {
                    for (int lx = 0; lx < 16; lx++) {
                        IBlockState state = ebs.get(lx, ly, lz);
                        if (state.getBlock() == Blocks.AIR) {
                            continue;
                        }
                        NBTTagCompound b = new NBTTagCompound();
                        b.setShort("P", (short) ((lx << 8) | (lz << 4) | ly));
                        b.setInteger("S", Block.getStateId(state));
                        blocks.appendTag(b);
                    }
                }
            }

            if (blocks.tagCount() == 0) {
                continue;
            }

            NBTTagCompound sec = new NBTTagCompound();
            sec.setInteger("Y", e.getKey());
            sec.setTag("Blocks", blocks);
            sections.appendTag(sec);
        }

        if (sections.tagCount() > 0) {
            nbt.setTag(INF_SECTIONS, sections);
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

        NBTTagList sections = nbt.getTagList(INF_SECTIONS, 10);
        for (int i = 0; i < sections.tagCount(); i++) {
            NBTTagCompound sec = sections.getCompoundTagAt(i);
            int sectionY = sec.getInteger("Y");
            ExtendedBlockStorage ebs = new ExtendedBlockStorage(sectionY << 4, storeSky);

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

            col.getSections().put(sectionY, ebs);
        }
    }
}