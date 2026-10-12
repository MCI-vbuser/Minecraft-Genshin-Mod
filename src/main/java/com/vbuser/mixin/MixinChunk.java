package com.vbuser.mixin;

import com.vbuser.inf.HeightConstants;
import com.vbuser.inf.chunk.ColumnManager;
import com.vbuser.inf.chunk.ExtendedColumn;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.chunk.Chunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.Map;

@Mixin(Chunk.class)
public abstract class MixinChunk {

    @Inject(
            method = "getBlockState(III)Lnet/minecraft/block/state/IBlockState;",
            at = @At("HEAD"),
            cancellable = true
    )
    private void inf$getBlockState(int x, int y, int z, CallbackInfoReturnable<IBlockState> cir) {
        if (HeightConstants.isExtendedY(y)) {
            ExtendedColumn col = ColumnManager.get((Chunk) (Object) this);
            cir.setReturnValue(col.getBlock(x, y, z));
        }
    }

    @Inject(
            method = "setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/state/IBlockState;)Lnet/minecraft/block/state/IBlockState;",
            at = @At("HEAD"),
            cancellable = true
    )
    private void inf$setBlockState(BlockPos pos, IBlockState state,
                                   CallbackInfoReturnable<IBlockState> cir) {
        int y = pos.getY();
        if (HeightConstants.isExtendedY(y)) {
            ExtendedColumn col = ColumnManager.get((Chunk) (Object) this);
            cir.setReturnValue(col.setBlock(((Chunk)(Object)this).getWorld(), pos.getX(), y, pos.getZ(), state));
        }
    }

    @Inject(
            method = "setLightFor(Lnet/minecraft/world/EnumSkyBlock;Lnet/minecraft/util/math/BlockPos;I)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void inf$setLightFor(EnumSkyBlock type, BlockPos pos, int value,
                                 CallbackInfo ci) {
        if (HeightConstants.isExtendedY(pos.getY())) {
            ci.cancel();
        }
    }

    @Inject(
            method = "getLightFor(Lnet/minecraft/world/EnumSkyBlock;Lnet/minecraft/util/math/BlockPos;)I",
            at = @At("HEAD"),
            cancellable = true
    )
    private void inf$getLightFor(EnumSkyBlock type, BlockPos pos,
                                 CallbackInfoReturnable<Integer> cir) {
        int y = pos.getY();
        if (HeightConstants.isExtendedY(y)) {
            cir.setReturnValue(15);
        }
    }
}