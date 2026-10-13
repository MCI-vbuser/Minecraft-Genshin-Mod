package com.vbuser.mixin;

import com.vbuser.inf.HeightConstants;
import net.minecraft.command.server.CommandTeleport;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

@Mixin(CommandTeleport.class)
public abstract class MixinCommandTeleport {

    @ModifyConstant(
            method = "execute",
            constant = @Constant(intValue = -4096)
    )
    private static int inf$teleportMinY(int original) {
        return HeightConstants.MIN_Y;
    }

    @ModifyConstant(
            method = "execute",
            constant = @Constant(intValue = 4096)
    )
    private static int inf$teleportMaxY(int original) {
        return HeightConstants.MAX_Y;
    }
}