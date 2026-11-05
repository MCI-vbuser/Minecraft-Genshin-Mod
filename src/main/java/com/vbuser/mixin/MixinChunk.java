package com.vbuser.mixin;

import com.vbuser.inf.HeightConstants;
import com.vbuser.inf.chunk.ColumnManager;
import com.vbuser.inf.chunk.ExtendedColumn;
import com.vbuser.inf.chunk.ExtendedColumnHolder;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Chunk.class)
public abstract class MixinChunk implements ExtendedColumnHolder {

    @Unique
    private ExtendedColumn inf$extendedColumn;

    @Unique
    @Override
    public ExtendedColumn inf$getExtendedColumn(boolean create) {
        if (this.inf$extendedColumn == null && create) {
            this.inf$extendedColumn = new ExtendedColumn();
        }
        return this.inf$extendedColumn;
    }

    @Inject(
            method = "getBlockState(III)Lnet/minecraft/block/state/IBlockState;",
            at = @At("HEAD"),
            cancellable = true
    )
    private void inf$getBlockState(int x, int y, int z, CallbackInfoReturnable<IBlockState> cir) {
        if (HeightConstants.isExtendedY(y)) {
            ExtendedColumn column = ColumnManager.getIfPresent((Chunk) (Object) this);
            cir.setReturnValue(column == null
                    ? Blocks.AIR.getDefaultState()
                    : column.getBlock(x, y, z));
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
        if (!HeightConstants.isExtendedY(y)) {
            return;
        }

        Chunk self = (Chunk) (Object) this;
        World world = self.getWorld();
        ExtendedColumn column = ColumnManager.get(self);
        IBlockState oldState = column.setBlock(world, pos.getX(), y, pos.getZ(), state, 3);

        if (oldState != null) {
            // Chunk.setBlockState normally sets this.dirty at its tail; the
            // HEAD injection bypasses that, so do it explicitly or autosave
            // will not persist extended-only changes.
            self.markDirty();

            if (!world.isRemote && !world.captureBlockSnapshots) {
                // Direct Chunk.setBlockState callers do not pass through
                // World.setBlockState, so mirror its notification behavior.
                world.markAndNotifyBlock(pos, self, oldState, state, 3);
            }
        }

        cir.setReturnValue(oldState);
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
        if (HeightConstants.isExtendedY(pos.getY())) {
            cir.setReturnValue(15);
        }
    }
}
