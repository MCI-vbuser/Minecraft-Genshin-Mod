package com.vbuser.inf.network;

import com.vbuser.inf.HeightConstants;
import com.vbuser.inf.chunk.ColumnManager;
import com.vbuser.inf.chunk.ExtendedColumn;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Builds and queues the extended-region part of a chunk for one or more
 * players.
 *
 * <p>{@link com.vbuser.mixin.MixinPlayerChunkMapEntry} uses
 * {@link #buildSectionPackets(Chunk)} once per chunk send, and
 * {@code /update} uses {@link #resyncChunk} to replace a client copy wholesale
 * after the server merged a new base world underneath it.</p>
 *
 * <p>A resync is a three step conversation on the existing per-player FIFO
 * queue: {@code MODE_RESET}, one regular section packet per non-empty section,
 * {@code MODE_FINISH}. The reset marker makes the client drop everything it
 * has for the column, which is also how blocks that were removed by the new
 * base world disappear; sending only the new non-empty sections would leave
 * those blocks behind forever.</p>
 */
public final class ExtendedChunkSync {

    private static final short[] EMPTY_OFFSETS = new short[0];
    private static final int[] EMPTY_STATES = new int[0];

    public static List<SPacketExtendedSection> buildSectionPackets(Chunk chunk) {
        ExtendedColumn column = ColumnManager.getIfPresent(chunk);
        if (column == null || column.isEmpty()) {
            return Collections.emptyList();
        }

        List<SPacketExtendedSection> packets = new ArrayList<>();
        for (Map.Entry<Integer, ExtendedBlockStorage> entry : column.getSections().entrySet()) {
            int sectionY = entry.getKey();
            ExtendedBlockStorage storage = entry.getValue();
            BitSet nonAir = column.getNonAir(sectionY);
            if (storage.isEmpty() || nonAir == null || nonAir.isEmpty()) {
                continue;
            }

            int count = nonAir.cardinality();
            short[] offsets = new short[count];
            int[] states = new int[count];
            int cursor = 0;
            for (int index = nonAir.nextSetBit(0); index >= 0; index = nonAir.nextSetBit(index + 1)) {
                offsets[cursor] = (short) index;
                states[cursor] = Block.getStateId(storage.get(
                        ExtendedColumn.indexX(index),
                        ExtendedColumn.indexY(index),
                        ExtendedColumn.indexZ(index)));
                cursor++;
            }
            packets.add(new SPacketExtendedSection(chunk.x, chunk.z, sectionY, offsets, states));
        }
        return packets;
    }

    /** Queue the extended sections and tile entities of one chunk. */
    public static void sendChunk(EntityPlayerMP player, Chunk chunk) {
        for (SPacketExtendedSection packet : buildSectionPackets(chunk)) {
            ExtendedPacketQueue.sendSection(player, packet);
        }
        sendTileEntities(player, chunk);
    }

    /**
     * Replace the client copy of this chunk's extended column.
     *
     * <p>The caller is responsible for having sent a vanilla
     * {@code SPacketChunkData} first; this method only deals with the part of
     * the chunk that vanilla does not know about.</p>
     */
    public static void resyncChunk(EntityPlayerMP player, Chunk chunk) {
        ExtendedPacketQueue.sendSection(player, new SPacketExtendedSection(
                chunk.x, chunk.z, 0, EMPTY_OFFSETS, EMPTY_STATES,
                SPacketExtendedSection.MODE_RESET));
        for (SPacketExtendedSection packet : buildSectionPackets(chunk)) {
            ExtendedPacketQueue.sendSection(player, packet);
        }
        ExtendedPacketQueue.sendSection(player, new SPacketExtendedSection(
                chunk.x, chunk.z, 0, EMPTY_OFFSETS, EMPTY_STATES,
                SPacketExtendedSection.MODE_FINISH));
        sendTileEntities(player, chunk);
    }

    public static void sendTileEntities(EntityPlayerMP player, Chunk chunk) {
        for (TileEntity tileEntity : chunk.getTileEntityMap().values()) {
            if (tileEntity.isInvalid()) {
                continue;
            }
            BlockPos pos = tileEntity.getPos();
            if (!HeightConstants.isExtendedY(pos.getY())) {
                continue;
            }
            NBTTagCompound nbt = tileEntity.getUpdateTag();
            ExtendedPacketQueue.sendTileEntity(
                    player, new SPacketExtendedTileEntity(pos, nbt));
        }
    }

    private ExtendedChunkSync() {}
}
