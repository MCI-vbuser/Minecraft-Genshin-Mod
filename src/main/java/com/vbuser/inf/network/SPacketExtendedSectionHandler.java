package com.vbuser.inf.network;

import com.vbuser.inf.chunk.ColumnManager;
import com.vbuser.inf.chunk.ExtendedColumn;
import com.vbuser.inf.light.ColumnLight;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.EmptyChunk;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

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

            short[] offsets = msg.getOffsets();
            int[] states = msg.getStates();
            int baseX = msg.getChunkX() << 4;
            int baseZ = msg.getChunkZ() << 4;
            int baseY = msg.getSectionY() << 4;

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

            light.rebuildTop(world);
            light.onSectionCreated(world, msg.getSectionY());
        });
        return null;
    }
}
