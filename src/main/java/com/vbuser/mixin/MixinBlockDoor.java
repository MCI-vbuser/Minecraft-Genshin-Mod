package com.vbuser.mixin;

import net.minecraft.block.BlockDoor;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(BlockDoor.class)
public abstract class MixinBlockDoor {

    @Redirect(
            method = "canPlaceBlockAt(Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;)Z",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/World;getHeight()I")
    )
    private int inf$extendedWorldHeight(World world) {
        return Integer.MAX_VALUE;
    }
}