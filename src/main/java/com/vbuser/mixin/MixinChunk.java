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
            this.inf$extendedColumn = new ExtendedColumn((Chunk) (Object) this);
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

    /**
     * Vanilla height map changes are the only way the {@code 0..255} part of a
     * column can affect extended Y: as long as it holds a blocking block every
     * {@code Y < 0} cell is shadowed, and once it is empty the extended top
     * alone decides. Detecting that transition here keeps {@code relightBlock},
     * {@code generateSkylightMap} and {@code heightMap} untouched.
     */
    @Inject(
            method = "setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/state/IBlockState;)Lnet/minecraft/block/state/IBlockState;",
            at = @At("RETURN")
    )
    private void inf$onVanillaHeightChanged(BlockPos pos, IBlockState state,
                                            CallbackInfoReturnable<IBlockState> cir) {
        if (HeightConstants.isExtendedY(pos.getY())) {
            return;
        }
        ExtendedColumn column = ColumnManager.getIfPresent((Chunk) (Object) this);
        if (column == null) {
            return;
        }
        column.getLight().onVanillaHeightChanged(
                ((Chunk) (Object) this).getWorld(), pos.getX() & 15, pos.getZ() & 15);
    }

    @Inject(
            method = "setLightFor(Lnet/minecraft/world/EnumSkyBlock;Lnet/minecraft/util/math/BlockPos;I)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void inf$setLightFor(EnumSkyBlock type, BlockPos pos, int value,
                                 CallbackInfo ci) {
        if (!HeightConstants.isExtendedY(pos.getY())) {
            return;
        }
        Chunk self = (Chunk) (Object) this;
        ColumnManager.get(self).getLight().setLightFor(type, pos.getX(), pos.getY(), pos.getZ(), value);
        self.markDirty();
        ci.cancel();
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
        ExtendedColumn column = ColumnManager.getIfPresent((Chunk) (Object) this);
        if (column == null) {
            cir.setReturnValue(inf$defaultLight(type, pos.getX(), pos.getY(), pos.getZ()));
            return;
        }
        cir.setReturnValue(column.getLight().getLightFor(type, pos.getX(), pos.getY(), pos.getZ()));
    }

    @Inject(
            method = "getLightSubtracted(Lnet/minecraft/util/math/BlockPos;I)I",
            at = @At("HEAD"),
            cancellable = true
    )
    private void inf$getLightSubtracted(BlockPos pos, int amount,
                                        CallbackInfoReturnable<Integer> cir) {
        if (!HeightConstants.isExtendedY(pos.getY())) {
            return;
        }
        ExtendedColumn column = ColumnManager.getIfPresent((Chunk) (Object) this);
        if (column == null) {
            int sky = 0;
            if (inf$defaultLight(EnumSkyBlock.SKY, pos.getX(), pos.getY(), pos.getZ()) > 0) {
                sky = EnumSkyBlock.SKY.defaultLightValue - amount;
                if (sky < 0) {
                    sky = 0;
                }
            }
            cir.setReturnValue(sky);
            return;
        }
        cir.setReturnValue(column.getLight().getLightSubtracted(
                pos.getX(), pos.getY(), pos.getZ(), amount));
    }

    @Inject(
            method = "canSeeSky(Lnet/minecraft/util/math/BlockPos;)Z",
            at = @At("HEAD"),
            cancellable = true
    )
    private void inf$canSeeSky(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (!HeightConstants.isExtendedY(pos.getY())) {
            return;
        }
        ExtendedColumn column = ColumnManager.getIfPresent((Chunk) (Object) this);
        if (column == null) {
            cir.setReturnValue(inf$defaultLight(EnumSkyBlock.SKY, pos.getX(), pos.getY(), pos.getZ()) > 0);
            return;
        }
        cir.setReturnValue(column.getLight().canSeeSky(pos.getX(), pos.getY(), pos.getZ()));
    }

    /**
     * Light value of an extended cell when the chunk has no extended column at
     * all. Extended Y is never stored in the vanilla arrays, so the answer is
     * the pure column model: 15 above the vanilla height map, 0 below it.
     */
    @Unique
    private int inf$defaultLight(EnumSkyBlock type, int x, int y, int z) {
        if (type != EnumSkyBlock.SKY) {
            return 0;
        }
        Chunk self = (Chunk) (Object) this;
        if (!self.getWorld().provider.hasSkyLight()) {
            return 0;
        }
        int top = self.getHeightValue(x & 15, z & 15);
        return top == 0 || y >= top ? EnumSkyBlock.SKY.defaultLightValue : 0;
    }
}
