package com.vbuser.mixin;

import com.vbuser.inf.HeightConstants;
import com.vbuser.inf.network.ExtendedChunkSync;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.management.PlayerChunkMapEntry;
import net.minecraft.world.chunk.Chunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import javax.annotation.Nullable;
import java.util.List;

@Mixin(PlayerChunkMapEntry.class)
public abstract class MixinPlayerChunkMapEntry {

    @Shadow @Final private List<EntityPlayerMP> players;

    @Shadow @Nullable private Chunk chunk;

    @Inject(method = "sendToPlayers", at = @At("RETURN"))
    private void inf$sendExtendedBlocksAll(CallbackInfoReturnable<Boolean> cir) {
        if (this.chunk == null || !HeightConstants.isExtendedWorld(this.chunk.getWorld())) {
            return;
        }
        ExtendedChunkSync.sendChunk(this.players, this.chunk);
    }

    @Inject(
            method = "sendToPlayer(Lnet/minecraft/entity/player/EntityPlayerMP;)V",
            at = @At("TAIL")
    )
    private void inf$sendExtendedBlocksOne(EntityPlayerMP player, CallbackInfo ci) {
        if (this.chunk == null || !HeightConstants.isExtendedWorld(this.chunk.getWorld())) {
            return;
        }
        ExtendedChunkSync.sendChunk(player, this.chunk);
    }
}
