package com.vbuser.inf.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;

public class SPacketExtendedTileEntity implements IMessage {

    private BlockPos pos;
    private NBTTagCompound nbt;
    private int epoch;
    private int dimension;

    public SPacketExtendedTileEntity() {}

    public SPacketExtendedTileEntity(BlockPos pos, NBTTagCompound nbt) {
        this(pos, nbt, 0);
    }

    public SPacketExtendedTileEntity(BlockPos pos, NBTTagCompound nbt, int epoch) {
        this.pos = pos;
        this.nbt = nbt;
        this.epoch = epoch;
    }

    public BlockPos getPos() { return pos; }
    public NBTTagCompound getNbt() { return nbt; }
    public int getEpoch() { return epoch; }
    public int getDimension() { return dimension; }
    public void setEpoch(int epoch) { this.epoch = epoch; }
    public void setDimension(int dimension) { this.dimension = dimension; }

    @Override
    public void fromBytes(ByteBuf buf) {
        int x = buf.readInt();
        int y = buf.readInt();
        int z = buf.readInt();
        this.pos = new BlockPos(x, y, z);
        this.nbt = ByteBufUtils.readTag(buf);
        // Optional trailing fields: packets from earlier releases end here.
        this.epoch = buf.isReadable(4) ? buf.readInt() : 0;
        this.dimension = buf.isReadable(4) ? buf.readInt() : 0;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(this.pos.getX());
        buf.writeInt(this.pos.getY());
        buf.writeInt(this.pos.getZ());
        ByteBufUtils.writeTag(buf, this.nbt);
        buf.writeInt(this.epoch);
        buf.writeInt(this.dimension);
    }
}