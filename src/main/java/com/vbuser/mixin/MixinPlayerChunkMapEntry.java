package com.vbuser.mixin;

import com.vbuser.inf.HeightConstants;
import com.vbuser.inf.InfiniteHeight;
import com.vbuser.inf.chunk.ColumnManager;
import com.vbuser.inf.chunk.ExtendedColumn;
import com.vbuser.inf.network.SPacketExtendedSection;
import com.vbuser.inf.network.SPacketExtendedTileEntity;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.management.PlayerChunkMapEntry;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
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

        List<SPacketExtendedSection> packets = inf$buildExtendedPackets();
        for (EntityPlayerMP player : this.players) {
            for (SPacketExtendedSection packet : packets) {
                InfiniteHeight.NETWORK.sendTo(packet, player);
            }
            inf$sendExtendedTileEntitiesTo(player);
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
        for (SPacketExtendedSection packet : inf$buildExtendedPackets()) {
            InfiniteHeight.NETWORK.sendTo(packet, player);
        }
        inf$sendExtendedTileEntitiesTo(player);
    }

    @Unique
    private List<SPacketExtendedSection> inf$buildExtendedPackets() {
        ExtendedColumn col = ColumnManager.getIfPresent(this.chunk);
        if (col == null || col.isEmpty()) {
            return Collections.emptyList();
        }

        List<SPacketExtendedSection> packets = new ArrayList<>();
        for (Map.Entry<Integer, ExtendedBlockStorage> entry : col.getSections().entrySet()) {
            int sectionY = entry.getKey();
            ExtendedBlockStorage ebs = entry.getValue();
            BitSet nonAir = col.getNonAir(sectionY);
            if (ebs.isEmpty() || nonAir == null || nonAir.isEmpty()) {
                continue;
            }

            int count = nonAir.cardinality();
            short[] offsets = new short[count];
            int[] states = new int[count];
            int cursor = 0;
            for (int index = nonAir.nextSetBit(0); index >= 0; index = nonAir.nextSetBit(index + 1)) {
                offsets[cursor] = (short) index;
                states[cursor] = Block.getStateId(ebs.get(
                        ExtendedColumn.indexX(index),
                        ExtendedColumn.indexY(index),
                        ExtendedColumn.indexZ(index)));
                cursor++;
            }

            assert this.chunk != null;
            packets.add(new SPacketExtendedSection(this.chunk.x, this.chunk.z, sectionY, offsets, states));
        }
        return packets;
    }

    @Unique
    private void inf$sendExtendedTileEntitiesTo(EntityPlayerMP player) {
        assert this.chunk != null;
        for (TileEntity te : this.chunk.getTileEntityMap().values()) {
            if (te.isInvalid()) {
                continue;
            }
            BlockPos pos = te.getPos();
            if (!HeightConstants.isExtendedY(pos.getY())) {
                continue;
            }
            NBTTagCompound nbt = te.getUpdateTag();
            InfiniteHeight.NETWORK.sendTo(
                    new SPacketExtendedTileEntity(pos, nbt), player);
        }
    }
}
