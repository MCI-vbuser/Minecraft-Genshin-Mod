package com.vbuser.mixin;

import com.vbuser.inf.HeightConstants;
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
        if (HeightConstants.isExtendedY(pos.getY())) {
            float t = ((Biome)(Object)this).getDefaultTemperature();
            if (Float.isNaN(t) || Float.isInfinite(t)) t = 0.5F;
            cir.setReturnValue(t);
        }
    }
}