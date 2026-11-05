package com.vbuser.inf.network;

import com.vbuser.inf.chunk.ExtendedColumn;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
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

            short[] offsets = msg.getOffsets();
            int[] states = msg.getStates();
            int chunkX = msg.getChunkX() << 4;
            int chunkZ = msg.getChunkZ() << 4;
            int sectionY = msg.getSectionY() << 4;

            for (int i = 0; i < offsets.length; i++) {
                int index = offsets[i] & 0xFFFF;
                BlockPos pos = new BlockPos(
                        chunkX + ExtendedColumn.indexX(index),
                        sectionY + ExtendedColumn.indexY(index),
                        chunkZ + ExtendedColumn.indexZ(index));
                IBlockState state = Block.getStateById(states[i]);
                world.setBlockState(pos, state, 2);
            }
        });
        return null;
    }
}
