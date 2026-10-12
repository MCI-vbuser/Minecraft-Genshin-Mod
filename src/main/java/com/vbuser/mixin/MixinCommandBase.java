package com.vbuser.mixin;

import com.vbuser.inf.HeightConstants;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(CommandBase.class)
public abstract class MixinCommandBase {

    @Inject(
            method = "parseBlockPos",
            at = @At("HEAD"),
            cancellable = true
    )
    private static void inf$parseBlockPos(ICommandSender sender, String[] args, int startIndex,
                                          boolean centerBlock,
                                          CallbackInfoReturnable<BlockPos> cir) throws Exception {
        BlockPos base = sender.getPosition();
        cir.setReturnValue(new BlockPos(
                CommandBase.parseDouble(base.getX(), args[startIndex],     -30000000, 30000000, centerBlock),
                CommandBase.parseDouble(base.getY(), args[startIndex + 1], HeightConstants.MIN_Y, HeightConstants.MAX_Y, false),
                CommandBase.parseDouble(base.getZ(), args[startIndex + 2], -30000000, 30000000, centerBlock)
        ));
    }
}