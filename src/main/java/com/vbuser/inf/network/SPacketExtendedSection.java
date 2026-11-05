package com.vbuser.inf.network;

import io.netty.buffer.ByteBuf;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;

/**
 * Batched update for one 16x16x16 extended section.
 *
 * <p>Vanilla {@code SPacketMultiBlockChange} encodes its y component in the
 * low 8 bits of a short, so it cannot address Y outside 0..255. This packet
 * carries the section Y explicitly and sends up to 4096 positions in one
 * message.</p>
 */
public class SPacketExtendedSection implements IMessage {

    private int chunkX;
    private int chunkZ;
    private int sectionY;
    private short[] offsets = new short[0];
    private int[] states = new int[0];

    public SPacketExtendedSection() {}

    public SPacketExtendedSection(int chunkX, int chunkZ, int sectionY, short[] offsets, int[] states) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.sectionY = sectionY;
        this.offsets = offsets;
        this.states = states;
    }

    public int getChunkX() { return chunkX; }
    public int getChunkZ() { return chunkZ; }
    public int getSectionY() { return sectionY; }
    public short[] getOffsets() { return offsets; }
    public int[] getStates() { return states; }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.chunkX = buf.readInt();
        this.chunkZ = buf.readInt();
        this.sectionY = buf.readInt();
        int count = buf.readInt();
        this.offsets = new short[count];
        this.states = new int[count];
        for (int i = 0; i < count; i++) {
            this.offsets[i] = buf.readShort();
            this.states[i] = buf.readInt();
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(this.chunkX);
        buf.writeInt(this.chunkZ);
        buf.writeInt(this.sectionY);
        buf.writeInt(this.offsets.length);
        for (int i = 0; i < this.offsets.length; i++) {
            buf.writeShort(this.offsets[i]);
            buf.writeInt(this.states[i]);
        }
    }
}

