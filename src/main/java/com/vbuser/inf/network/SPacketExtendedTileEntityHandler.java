package com.vbuser.inf.network;

import net.minecraft.client.Minecraft;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public class SPacketExtendedTileEntityHandler
        implements IMessageHandler<SPacketExtendedTileEntity, IMessage> {

    @Override
    public IMessage onMessage(SPacketExtendedTileEntity msg, MessageContext ctx) {
        Minecraft mc = Minecraft.getMinecraft();
        mc.addScheduledTask(() -> {
            if (mc.world == null) return;
            BlockPos pos = msg.getPos();
            Chunk chunk = mc.world.getChunkFromBlockCoords(pos);
            TileEntity te = chunk.getTileEntity(pos, Chunk.EnumCreateEntityType.IMMEDIATE);
            if (te != null && msg.getNbt() != null) {
                te.readFromNBT(msg.getNbt());
            }
        });
        return null;
    }
}