package com.vbuser.mixin;

import com.vbuser.inf.HeightConstants;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.ChunkCache;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * {@code ChunkCache} is the {@code IBlockAccess} used while a chunk mesh is
 * built. For Y outside {@code 0..255} it unconditionally answers "air, skylight
 * 15, block light 0", which renders the vanilla side of the
 * {@code Y=0/-1} and {@code Y=255/256} seams as if it were open sky.
 *
 * <p>This mixin answers from the chunks already cached in the instance, so it
 * stays usable on the render thread and never touches the world / chunk map.</p>
 */
@Mixin(ChunkCache.class)
public abstract class MixinChunkCache {

    @Shadow protected int chunkX;

    @Shadow protected int chunkZ;

    @Shadow protected Chunk[][] chunkArray;

    @Shadow protected World world;

    @Inject(
            method = "getBlockState(Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/block/state/IBlockState;",
            at = @At("HEAD"),
            cancellable = true
    )
    private void inf$getBlockState(BlockPos pos, CallbackInfoReturnable<IBlockState> cir) {
        if (!HeightConstants.isExtendedY(pos.getY())) {
            return;
        }
        if (!HeightConstants.isExtendedWorld(this.world)) {
            return;
        }
        Chunk chunk = inf$chunk(pos);
        cir.setReturnValue(chunk == null ? Blocks.AIR.getDefaultState() : chunk.getBlockState(pos));
    }

    @Inject(
            method = "getLightFor(Lnet/minecraft/world/EnumSkyBlock;Lnet/minecraft/util/math/BlockPos;)I",
            at = @At("HEAD"),
            cancellable = true
    )
    private void inf$getLightFor(EnumSkyBlock type, BlockPos pos,
                                 CallbackInfoReturnable<Integer> cir) {
        if (!HeightConstants.isExtendedY(pos.getY())) {
            return;
        }
        if (!HeightConstants.isExtendedWorld(this.world)) {
            return;
        }
        Chunk chunk = inf$chunk(pos);
        cir.setReturnValue(chunk == null ? type.defaultLightValue : chunk.getLightFor(type, pos));
    }

    @Inject(
            method = "getLightForExt(Lnet/minecraft/world/EnumSkyBlock;Lnet/minecraft/util/math/BlockPos;)I",
            at = @At("HEAD"),
            cancellable = true
    )
    private void inf$getLightForExt(EnumSkyBlock type, BlockPos pos,
                                    CallbackInfoReturnable<Integer> cir) {
        if (!HeightConstants.isExtendedY(pos.getY())) {
            return;
        }
        if (!HeightConstants.isExtendedWorld(this.world)) {
            return;
        }
        if (type == EnumSkyBlock.SKY && !this.world.provider.hasSkyLight()) {
            cir.setReturnValue(0);
            return;
        }
        Chunk chunk = inf$chunk(pos);
        if (chunk == null) {
            cir.setReturnValue(type.defaultLightValue);
            return;
        }
        if (chunk.getBlockState(pos).useNeighborBrightness()) {
            int max = 0;
            for (EnumFacing facing : EnumFacing.values()) {
                // Go through ChunkCache.getLightFor: the neighbour of an edge
                // cell may live in the next chunk of the cache.
                int value = ((ChunkCache) (Object) this).getLightFor(type, pos.offset(facing));
                if (value > max) {
                    max = value;
                }
                if (max >= 15) {
                    break;
                }
            }
            cir.setReturnValue(max);
            return;
        }
        cir.setReturnValue(chunk.getLightFor(type, pos));
    }

    @Unique
    private Chunk inf$chunk(BlockPos pos) {
        int i = (pos.getX() >> 4) - this.chunkX;
        int j = (pos.getZ() >> 4) - this.chunkZ;
        if (i < 0 || i >= this.chunkArray.length) {
            return null;
        }
        if (j < 0 || j >= this.chunkArray[i].length) {
            return null;
        }
        return this.chunkArray[i][j];
    }
}
