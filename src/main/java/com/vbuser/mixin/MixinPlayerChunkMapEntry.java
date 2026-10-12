package com.vbuser.mixin;

import com.vbuser.inf.HeightConstants;
import com.vbuser.inf.InfiniteHeight;
import com.vbuser.inf.chunk.ColumnManager;
import com.vbuser.inf.chunk.ExtendedColumn;
import com.vbuser.inf.network.SPacketExtendedTileEntity;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.play.server.SPacketBlockChange;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.server.management.PlayerChunkMapEntry;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;

@Mixin(PlayerChunkMapEntry.class)
public abstract class MixinPlayerChunkMapEntry {

    @Shadow @Final private List<EntityPlayerMP> players;

    @Shadow @Nullable private Chunk chunk;

    @Inject(method = "sendToPlayers", at = @At("RETURN"))
    private void inf$sendExtendedBlocksAll(CallbackInfoReturnable<Boolean> cir) {
        if (this.chunk == null) {
            return;
        }
        for (EntityPlayerMP player : this.players) {
            inf$sendExtendedBlocksTo(player);
        }
    }

    @Inject(
            method = "sendToPlayer(Lnet/minecraft/entity/player/EntityPlayerMP;)V",
            at = @At("TAIL")
    )
    private void inf$sendExtendedBlocksOne(EntityPlayerMP player, CallbackInfo ci) {
        if (this.chunk == null) {
            return;
        }
        inf$sendExtendedBlocksTo(player);
    }

    private void inf$sendExtendedBlocksTo(EntityPlayerMP player) {
        ExtendedColumn col = ColumnManager.getIfPresent(this.chunk);
        if (col == null || col.isEmpty()) {
            return;
        }

        int baseX = this.chunk.x << 4;
        int baseZ = this.chunk.z << 4;

        for (Map.Entry<Integer, ExtendedBlockStorage> entry : col.getSections().entrySet()) {
            ExtendedBlockStorage ebs = entry.getValue();
            int baseY = entry.getKey() << 4;

            for (int ly = 0; ly < 16; ly++) {
                for (int lz = 0; lz < 16; lz++) {
                    for (int lx = 0; lx < 16; lx++) {
                        IBlockState state = ebs.get(lx, ly, lz);
                        if (state.getBlock() == Blocks.AIR) {
                            continue;
                        }
                        BlockPos pos = new BlockPos(baseX + lx, baseY + ly, baseZ + lz);
                        player.connection.sendPacket(
                                new SPacketBlockChange(player.world, pos));
                    }
                }
            }
        }
        for (TileEntity te : this.chunk.getTileEntityMap().values()) {
            if (te.isInvalid()) continue;
            BlockPos p = te.getPos();
            if (!HeightConstants.isExtendedY(p.getY())) continue;
            NBTTagCompound nbt = te.getUpdateTag();
            InfiniteHeight.NETWORK.sendTo(
                    new SPacketExtendedTileEntity(p, nbt), player);
        }
    }
}