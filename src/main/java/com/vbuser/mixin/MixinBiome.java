package com.vbuser.mixin;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Biome.class)
public abstract class MixinBiome {

    @Inject(
            method = "getTemperature(Lnet/minecraft/util/math/BlockPos;)F",
            at = @At("RETURN"),
            cancellable = true
    )
    private void inf$sanitizeTemperature(BlockPos pos, CallbackInfoReturnable<Float> cir) {
        float t = cir.getReturnValue();
        if (Float.isNaN(t) || Float.isInfinite(t)) {
            cir.setReturnValue(0.5F);
            return;
        }
        if (t < -0.5F) {
            cir.setReturnValue(-0.5F);
        } else if (t > 2.0F) {
            cir.setReturnValue(2.0F);
        }
    }
}