package com.vbuser.inf.network;

import com.vbuser.inf.HeightConstants;
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

    public static final int MAX_BLOCKS = 16 * 16 * 16;

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
    /**
     * Session of the world this snapshot was taken in. The client ignores a
     * packet whose session is older than the world it is currently in, which
     * keeps a backlog of the previous world from being imported into a chunk
     * that happens to sit at the same coordinates.
     */
    private int epoch;
    /** Dimension the snapshot was built in; checked again by the client. */
    private int dimension;

    public SPacketExtendedSection() {}

    public SPacketExtendedSection(int chunkX, int chunkZ, int sectionY, short[] offsets, int[] states) {
        this(chunkX, chunkZ, sectionY, offsets, states, MODE_NONE);
    }

    public SPacketExtendedSection(int chunkX, int chunkZ, int sectionY,
                                  short[] offsets, int[] states, byte mode) {
        this(chunkX, chunkZ, sectionY, offsets, states, mode, 0);
    }

    public SPacketExtendedSection(int chunkX, int chunkZ, int sectionY,
                                  short[] offsets, int[] states, byte mode, int epoch) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.sectionY = sectionY;
        this.offsets = offsets;
        this.states = states;
        this.mode = mode;
        this.epoch = epoch;
    }

    public int getChunkX() { return chunkX; }
    public int getChunkZ() { return chunkZ; }
    public int getSectionY() { return sectionY; }
    public short[] getOffsets() { return offsets; }
    public int[] getStates() { return states; }
    public byte getMode() { return mode; }
    public int getEpoch() { return epoch; }
    public int getDimension() { return dimension; }
    public void setEpoch(int epoch) { this.epoch = epoch; }
    public void setDimension(int dimension) { this.dimension = dimension; }

    @Override
    public void fromBytes(ByteBuf buf) {
        if (buf.readableBytes() < 16) {
            throw new IllegalArgumentException("Truncated extended section header");
        }
        this.chunkX = buf.readInt();
        this.chunkZ = buf.readInt();
        this.sectionY = buf.readInt();
        if (this.sectionY < (HeightConstants.MIN_Y >> 4)
                || this.sectionY > (HeightConstants.MAX_Y >> 4)) {
            throw new IllegalArgumentException("Extended section Y is outside the block coordinate range");
        }
        int count = buf.readInt();
        if (count < 0 || count > MAX_BLOCKS || buf.readableBytes() < count * 6) {
            throw new IllegalArgumentException("Invalid extended section block count: " + count);
        }
        this.offsets = new short[count];
        this.states = new int[count];
        for (int i = 0; i < count; i++) {
            this.offsets[i] = buf.readShort();
            this.states[i] = buf.readInt();
        }
        // Optional trailing fields retain compatibility with earlier senders.
        // Check the complete field width, not merely one readable byte.
        this.mode = buf.isReadable() ? buf.readByte() : MODE_NONE;
        if (this.mode != MODE_NONE && this.mode != MODE_RESET && this.mode != MODE_FINISH) {
            throw new IllegalArgumentException("Unknown extended section mode: " + this.mode);
        }
        this.epoch = buf.isReadable(4) ? buf.readInt() : 0;
        this.dimension = buf.isReadable(4) ? buf.readInt() : 0;
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
        buf.writeInt(this.epoch);
        buf.writeInt(this.dimension);
    }
}
