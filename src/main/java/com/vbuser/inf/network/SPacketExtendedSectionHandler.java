package com.vbuser.inf.network;

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
        mc.addScheduledTask(() -> {
            World world = mc.world;
            if (world == null) {
                return;
            }

            Chunk chunk = world.getChunkFromChunkCoords(msg.getChunkX(), msg.getChunkZ());
            if (chunk instanceof EmptyChunk) {
                // The chunk itself has not arrived yet (or was unloaded in the
                // meantime); the send path ships this section again with it.
                return;
            }
            ExtendedColumn column = ColumnManager.get(chunk);
            ColumnLight light = column.getLight();
            byte mode = msg.getMode();

            if (mode == SPacketExtendedSection.MODE_RESET) {
                // Whole-chunk resync starts here: throw away the previous copy
                // (blocks, light and baked meshes) so nothing that the new base
                // world removed can survive.
                ExtendedBlockRenderHandler.clearChunk(msg.getChunkX(), msg.getChunkZ());
                column.clear();
            }

            short[] offsets = msg.getOffsets();
            int[] states = msg.getStates();
            int baseX = msg.getChunkX() << 4;
            int baseZ = msg.getChunkZ() << 4;
            int baseY = msg.getSectionY() << 4;

            if (offsets.length > 0) {
                // Straight into the section arrays: a vanilla style per block
                // setBlockState would fire 4096 block callbacks and light checks.
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
                // The column is complete again: re-derive the top map and all
                // light values from scratch instead of trusting the partial
                // state accumulated while the section packets arrived.
                removeStaleExtendedTileEntities(chunk);
                light.onChunkLoaded(world, false);
                return;
            }
            if (mode == SPacketExtendedSection.MODE_RESET) {
                // Marker packet only; the section packets that follow do the work.
                return;
            }

            light.rebuildTop(world);
            light.onSectionCreated(world, msg.getSectionY());
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
