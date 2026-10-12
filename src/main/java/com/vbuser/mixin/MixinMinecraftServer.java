package com.vbuser.mixin;

import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MinecraftServer.class)
public abstract class MixinMinecraftServer {

    @Inject(
            method = "getBuildLimit",
            at = @At("HEAD"),
            cancellable = true
    )
    private void inf$getBuildLimit(CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(Integer.MAX_VALUE);
    }
}