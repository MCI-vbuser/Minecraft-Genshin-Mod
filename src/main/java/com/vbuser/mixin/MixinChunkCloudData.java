package com.vbuser.mixin;

import com.vbuser.genshin.client.renderer.CloudVolumeTracker;
import net.minecraft.block.state.IBlockState;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Client side block data feed for the volumetric cloud renderer.
 *
 * <p>{@code Block.onBlockAdded} / {@code Block.breakBlock} are server only
 * (see {@code Chunk.setBlockState}), and a chunk that is re-sent after a bulk
 * edit is applied through {@code Chunk.read}, so neither the block callbacks
 * nor {@code ChunkEvent.Load} see a client side change. These two injections
 * are the only places where the client's cloud blocks can be observed
 * reliably.</p>
 */
@Mixin(Chunk.class)
public abstract class MixinChunkCloudData {

    @Inject(
            method = "read(Lnet/minecraft/network/PacketBuffer;IZ)V",
            at = @At("RETURN")
    )
    private void genshin$afterChunkDataRead(PacketBuffer buf, int availableSections,
                                            boolean groundUpContinuous, CallbackInfo ci) {
        Chunk self = (Chunk) (Object) this;
        World world = self.getWorld();
        if (world == null || !world.isRemote) {
            return;
        }
        // In 1.12 availableSections is already the section bit mask.
        CloudVolumeTracker.onClientChunkData(world, self, availableSections & 0xFFFF, groundUpContinuous);
    }

    /**
     * HEAD rather than RETURN: {@code MixinChunk} cancels this method for Y
     * outside 0..255 (the extended height path), which would skip a RETURN
     * injection and leave cloud edits above the vanilla build height invisible
     * until a full chunk rescan. Reading the old state first also means the
     * tracker never depends on the return value.
     */
    @Inject(
            method = "setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/state/IBlockState;)Lnet/minecraft/block/state/IBlockState;",
            at = @At("HEAD")
    )
    private void genshin$beforeSetBlockState(BlockPos pos, IBlockState state, CallbackInfoReturnable<IBlockState> cir) {
        Chunk self = (Chunk) (Object) this;
        World world = self.getWorld();
        if (world == null || !world.isRemote) {
            return;
        }
        CloudVolumeTracker.onClientBlockChanged(world, pos, self.getBlockState(pos), state);
    }
}
