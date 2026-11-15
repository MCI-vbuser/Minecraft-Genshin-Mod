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
 *
 * <p>Light is not sent: both sides run the same {@code ExtendedLightEngine}
 * over the same blocks and the same vanilla light, so each side derives the
 * same values without 4 KiB of stale arrays per section.</p>
 */
public class SPacketExtendedSection implements IMessage {

    /**
     * Regular incremental update: apply the listed blocks.
     */
    public static final byte MODE_NONE = 0;

    /**
     * Start of a whole-chunk resync: drop the client copy of this chunk's
     * extended column (blocks, tile entities and light) before applying the
     * section packets that follow.
     */
    public static final byte MODE_RESET = 1;

    /**
     * End of a whole-chunk resync: re-derive the column top and light from the
     * blocks that were just imported.
     */
    public static final byte MODE_FINISH = 2;

    private int chunkX;
    private int chunkZ;
    private int sectionY;
    private short[] offsets = new short[0];
    private int[] states = new int[0];
    private byte mode = MODE_NONE;

    public SPacketExtendedSection() {}

    public SPacketExtendedSection(int chunkX, int chunkZ, int sectionY, short[] offsets, int[] states) {
        this(chunkX, chunkZ, sectionY, offsets, states, MODE_NONE);
    }

    public SPacketExtendedSection(int chunkX, int chunkZ, int sectionY,
                                  short[] offsets, int[] states, byte mode) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.sectionY = sectionY;
        this.offsets = offsets;
        this.states = states;
        this.mode = mode;
    }

    public int getChunkX() { return chunkX; }
    public int getChunkZ() { return chunkZ; }
    public int getSectionY() { return sectionY; }
    public short[] getOffsets() { return offsets; }
    public int[] getStates() { return states; }
    public byte getMode() { return mode; }

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
        // Added after the first release. Older senders simply stop after the
        // block list, so a missing trailing byte means MODE_NONE; older
        // receivers ignore the extra byte. This keeps the packet usable in
        // both directions without a protocol version handshake.
        this.mode = buf.isReadable() ? buf.readByte() : MODE_NONE;
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
        buf.writeByte(this.mode);
    }
}
