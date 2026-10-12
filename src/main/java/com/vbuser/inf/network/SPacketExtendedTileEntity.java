package com.vbuser.inf.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;

public class SPacketExtendedTileEntity implements IMessage {

    private BlockPos pos;
    private NBTTagCompound nbt;

    public SPacketExtendedTileEntity() {}

    public SPacketExtendedTileEntity(BlockPos pos, NBTTagCompound nbt) {
        this.pos = pos;
        this.nbt = nbt;
    }

    public BlockPos getPos() { return pos; }
    public NBTTagCompound getNbt() { return nbt; }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.pos = BlockPos.fromLong(buf.readLong());
        this.nbt = ByteBufUtils.readTag(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(this.pos.toLong());
        ByteBufUtils.writeTag(buf, this.nbt);
    }
}