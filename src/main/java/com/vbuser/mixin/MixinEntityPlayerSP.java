package com.vbuser.mixin;

import com.vbuser.genshin.tool.forcesprint.KeyInputHandler;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.util.MovementInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityPlayerSP.class)
public class MixinEntityPlayerSP {

    @Inject(method = "onLivingUpdate", at = @At("RETURN"))
    private void onLivingUpdateReturn(CallbackInfo ci) {
        EntityPlayerSP player = (EntityPlayerSP) (Object) this;
        MovementInput input = player.movementInput;

        if (KeyInputHandler.isEnabled()) {
            player.setSprinting(input.moveForward != 0 || input.moveStrafe != 0);
        } else {
            player.setSprinting(false);
        }
    }
}
