package com.vbuser.mixin;

import com.vbuser.inf.HeightConstants;
import net.minecraft.command.CommandTP;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

@Mixin(CommandTP.class)
public abstract class MixinCommandTP {

    @ModifyConstant(
            method = "execute",
            constant = @Constant(intValue = -4096)
    )
    private static int inf$tpMinY(int original) {
        return HeightConstants.MIN_Y;
    }

    @ModifyConstant(
            method = "execute",
            constant = @Constant(intValue = 4096)
    )
    private static int inf$tpMaxY(int original) {
        return HeightConstants.MAX_Y;
    }
}