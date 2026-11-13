package com.vbuser.mixin;

import com.vbuser.inf.HeightConstants;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Extended block positions on the wire.
 *
 * <p>Vanilla {@code PacketBuffer#writeBlockPos} stores {@code BlockPos.toLong()},
 * whose Y field is only 12 bits ({@code -2048..2047}), so a position outside
 * {@code 0..255} would be silently truncated. Rather than changing
 * {@code BlockPos.toLong} itself (used by other code that expects the vanilla
 * packing, and by every other mod), the packet format gets one flag byte ahead
 * of the payload: {@code 0 + long} for a vanilla position, {@code 1 + x,y,z}
 * otherwise.</p>
 *
 * <p>Consequence: the mod is mandatory on both sides. {@code ExtendedJoinCheck}
 * disconnects clients that do not run it instead of letting them desync.</p>
 */
@Mixin(PacketBuffer.class)
public abstract class MixinPacketBuffer {

    @Inject(
            method = "writeBlockPos(Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/network/PacketBuffer;",
            at = @At("HEAD"),
            cancellable = true
    )
    private void inf$writeBlockPos(BlockPos pos, CallbackInfoReturnable<PacketBuffer> cir) {
        PacketBuffer self = (PacketBuffer) (Object) this;
        int y = pos.getY();

        if (HeightConstants.isExtendedY(y)) {
            self.writeBoolean(true);
            self.writeInt(pos.getX());
            self.writeInt(y);
            self.writeInt(pos.getZ());
        } else {
            self.writeBoolean(false);
            self.writeLong(pos.toLong());
        }

        cir.setReturnValue(self);
    }

    @Inject(
            method = "readBlockPos()Lnet/minecraft/util/math/BlockPos;",
            at = @At("HEAD"),
            cancellable = true
    )
    private void inf$readBlockPos(CallbackInfoReturnable<BlockPos> cir) {
        PacketBuffer self = (PacketBuffer) (Object) this;

        if (self.readBoolean()) {
            int x = self.readInt();
            int y = self.readInt();
            int z = self.readInt();
            cir.setReturnValue(new BlockPos(x, y, z));
        } else {
            cir.setReturnValue(BlockPos.fromLong(self.readLong()));
        }
    }
}