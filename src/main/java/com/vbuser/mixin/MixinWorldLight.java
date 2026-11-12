package com.vbuser.mixin;

import com.vbuser.inf.HeightConstants;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Client render entry point for light.
 *
 * <p>{@code World.getCombinedLight} - and with it every vertex that is lit by
 * a chunk mesh or by the extended block renderer - goes through
 * {@code getLightFromNeighborsFor}, which maps every {@code Y < 0} position to
 * {@code Y = 0} before reading. That clamp makes a seam block at {@code Y=-1}
 * report the light of the block above it, so remove it for extended Y.</p>
 */
@Mixin(World.class)
public abstract class MixinWorldLight {

    @Inject(
            method = "getLightFromNeighborsFor(Lnet/minecraft/world/EnumSkyBlock;Lnet/minecraft/util/math/BlockPos;)I",
            at = @At("HEAD"),
            cancellable = true
    )
    private void inf$getLightFromNeighborsFor(EnumSkyBlock type, BlockPos pos,
                                              CallbackInfoReturnable<Integer> cir) {
        if (!HeightConstants.isExtendedY(pos.getY())) {
            return;
        }

        World self = (World) (Object) this;
        if (!self.provider.hasSkyLight() && type == EnumSkyBlock.SKY) {
            cir.setReturnValue(0);
            return;
        }
        if (!self.isValid(pos) || !self.isBlockLoaded(pos)) {
            cir.setReturnValue(type.defaultLightValue);
            return;
        }
        if (self.getBlockState(pos).useNeighborBrightness()) {
            int max = self.getLightFor(type, pos.up());
            int value = self.getLightFor(type, pos.east());
            if (value > max) {
                max = value;
            }
            value = self.getLightFor(type, pos.west());
            if (value > max) {
                max = value;
            }
            value = self.getLightFor(type, pos.south());
            if (value > max) {
                max = value;
            }
            value = self.getLightFor(type, pos.north());
            if (value > max) {
                max = value;
            }
            cir.setReturnValue(max);
            return;
        }
        cir.setReturnValue(self.getChunkFromBlockCoords(pos).getLightFor(type, pos));
    }
}
