package com.vbuser.mixin;

import com.vbuser.genshin.math.WaterHelper;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public abstract class MixinEntity {

    @Inject(method = "spawnRunningParticles", at = @At("HEAD"), cancellable = true)
    private void cancelRunningParticles(CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(method = "isInWater", at = @At("RETURN"), cancellable = true)
    private void skipShallowWater(CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValue()) return;

        Entity self = (Entity) (Object) this;
        if (self instanceof EntityPlayer && WaterHelper.isInShallowWaterRaw((EntityPlayer) self)) {
            cir.setReturnValue(false);
        }
    }
}
