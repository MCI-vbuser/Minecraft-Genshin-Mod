package com.vbuser.inf.network;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
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
        final net.minecraft.world.World packetWorld = mc.world;
        mc.addScheduledTask(() -> {
            if (mc.world == null || (packetWorld != null && mc.world != packetWorld)) return;
            if (!ExtendedSyncSession.accept(msg.getEpoch(), msg.getDimension(), mc.world)) return;
            BlockPos pos = msg.getPos();
            if (msg.getNbt() == null) return;

            TileEntity te = mc.world.getTileEntity(pos);

            if (te == null) {
                te = TileEntity.create(mc.world, msg.getNbt());
                if (te == null) {
                    IBlockState state = mc.world.getBlockState(pos);
                    Block block = state.getBlock();
                    if (block.hasTileEntity(state)) {
                        te = block.createTileEntity(mc.world, state);
                    }
                }
                if (te == null) {
                    return;
                }
                mc.world.setTileEntity(pos, te);
            }

            te.handleUpdateTag(msg.getNbt());
        });
        return null;
    }
}