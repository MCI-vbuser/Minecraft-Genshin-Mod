package com.vbuser.mixin;

import com.vbuser.inf.HeightConstants;
import net.minecraft.block.Block;
import net.minecraft.block.BlockPistonBase;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockPistonBase.class)
public abstract class MixinBlockPistonBase {

    @Inject(
            method = "canPush",
            at = @At("HEAD"),
            cancellable = true
    )
    private static void inf$canPush(IBlockState blockStateIn, World worldIn, BlockPos pos,
                                    EnumFacing facing, boolean destroyBlocks, EnumFacing pistonFacing,
                                    CallbackInfoReturnable<Boolean> cir) {
        int y = pos.getY();
        if (y > HeightConstants.VANILLA_MIN_Y && y < HeightConstants.VANILLA_MAX_Y) {
            return;
        }
        if (!HeightConstants.isExtendedWorld(worldIn)) {
            return;
        }

        Block block = blockStateIn.getBlock();

        if (block == Blocks.OBSIDIAN) {
            cir.setReturnValue(false);
            return;
        }
        if (!worldIn.getWorldBorder().contains(pos)) {
            cir.setReturnValue(false);
            return;
        }

        if (block != Blocks.PISTON && block != Blocks.STICKY_PISTON) {
            if (blockStateIn.getBlockHardness(worldIn, pos) == -1.0F) {
                cir.setReturnValue(false);
                return;
            }
            switch (blockStateIn.getMobilityFlag()) {
                case BLOCK:
                    cir.setReturnValue(false);
                    return;
                case DESTROY:
                    cir.setReturnValue(destroyBlocks);
                    return;
                case PUSH_ONLY:
                    cir.setReturnValue(facing == pistonFacing);
                    return;
                default:
                    break;
            }
        } else if (blockStateIn.getValue(BlockPistonBase.EXTENDED)) {
            cir.setReturnValue(false);
            return;
        }

        cir.setReturnValue(!block.hasTileEntity(blockStateIn));
    }
}