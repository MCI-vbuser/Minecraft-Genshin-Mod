package com.vbuser.inf.network;

import com.vbuser.genshin.client.renderer.CloudVolumeTracker;
import com.vbuser.inf.HeightConstants;
import com.vbuser.inf.chunk.ColumnManager;
import com.vbuser.inf.chunk.ExtendedColumn;
import com.vbuser.inf.client.ExtendedBlockRenderHandler;
import com.vbuser.inf.light.ColumnLight;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.EmptyChunk;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.Iterator;
import java.util.Map;

@SideOnly(Side.CLIENT)
public class SPacketExtendedSectionHandler
        implements IMessageHandler<SPacketExtendedSection, IMessage> {

    @Override
    public IMessage onMessage(SPacketExtendedSection msg, MessageContext ctx) {
        Minecraft mc = Minecraft.getMinecraft();
        final World packetWorld = mc.world;
        mc.addScheduledTask(() -> {
            World world = mc.world;
            if (world == null || (packetWorld != null && world != packetWorld)) {
                // The packet arrived before a dimension switch but its main
                // thread task ran afterwards. Never import it into the new world.
                return;
            }
            if (!ExtendedSyncSession.accept(msg.getEpoch(), msg.getDimension(), world)) {
                return;
            }

            Chunk chunk = world.getChunkFromChunkCoords(msg.getChunkX(), msg.getChunkZ());
            if (chunk instanceof EmptyChunk) {
                return;
            }
            ExtendedColumn column = ColumnManager.get(chunk);
            ColumnLight light = column.getLight();
            byte mode = msg.getMode();

            if (mode == SPacketExtendedSection.MODE_RESET) {
                ExtendedBlockRenderHandler.clearChunk(msg.getChunkX(), msg.getChunkZ());
                column.clear();
            }

            short[] offsets = msg.getOffsets();
            int[] states = msg.getStates();
            int baseX = msg.getChunkX() << 4;
            int baseZ = msg.getChunkZ() << 4;
            int baseY = msg.getSectionY() << 4;

            if (offsets.length > 0) {
                light.setSuspended(true);
                try {
                    for (int i = 0; i < offsets.length; i++) {
                        int index = offsets[i] & 0xFFFF;
                        IBlockState state = Block.getStateById(states[i]);
                        column.importBlock(
                                baseX + ExtendedColumn.indexX(index),
                                baseY + ExtendedColumn.indexY(index),
                                baseZ + ExtendedColumn.indexZ(index),
                                state);
                    }
                } finally {
                    light.setSuspended(false);
                }
            }

            if (mode == SPacketExtendedSection.MODE_FINISH) {
                removeStaleExtendedTileEntities(chunk);
                light.onChunkLoaded(world, false);
                // MODE_FINISH is only sent by a whole-column resync. Sections
                // dropped by the new snapshot have to leave the cloud index too;
                // the re-read is limited to the extended sections.
                CloudVolumeTracker.onClientExtendedResync(world, chunk);
                return;
            }
            if (mode == SPacketExtendedSection.MODE_RESET) {
                return;
            }

            light.rebuildTop(world);
            light.onSectionCreated(world, msg.getSectionY());
            // The extended sections travel on their own channel and usually
            // arrive after the vanilla chunk data was already scanned, so the
            // cloud index has to re-read this one section.
            CloudVolumeTracker.onClientExtendedSectionData(world, chunk, msg.getSectionY());
        });
        return null;
    }

    /**
     * Bulk imports bypass {@code Chunk#setBlockState}, so a tile entity whose
     * block disappeared is never asked {@code shouldRefresh} and would keep
     * rendering at an empty position. Clean those up once the resync finished.
     */
    private static void removeStaleExtendedTileEntities(Chunk chunk) {
        Iterator<Map.Entry<BlockPos, TileEntity>> iterator =
                chunk.getTileEntityMap().entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<BlockPos, TileEntity> entry = iterator.next();
            BlockPos pos = entry.getKey();
            if (!HeightConstants.isExtendedY(pos.getY())) {
                continue;
            }
            IBlockState state = chunk.getBlockState(pos);
            if (!state.getBlock().hasTileEntity(state)) {
                entry.getValue().invalidate();
                iterator.remove();
            }
        }
    }
}
