package com.vbuser.genshin.tool.al.network;

import com.vbuser.genshin.tool.al.BGMPlayer;
import com.vbuser.genshin.tool.network.ModPacket;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.charset.StandardCharsets;

/**
 * One chunk of a bgm file.
 *
 * <p>Wire layout (in this order): name, chunk index, total chunks, payload length,
 * payload, then the transfer metadata ({@code totalLength}, {@code sessionId},
 * {@code chunkSize}) appended at the end. Older senders stop after the payload; the
 * extra fields are only read when present and default to "unknown" values that the
 * receiver rejects instead of trusting.</p>
 *
 * <p>A packet with {@code totalChunks == 0} is an explicit rejection: the server could
 * not serve the file (missing, too large, or the player already has too many streams).
 * Sending it lets the client fail exactly once instead of waiting for the timeout.</p>
 */
public class BGMFileChunkPacket extends ModPacket {

    private static final Logger LOGGER = LogManager.getLogger("BGMFileChunkPacket");

    /** Payload size the server uses for every chunk except the last one. */
    public static final int DEFAULT_CHUNK_SIZE = 16 * 1024;
    /** Hard bound for a declared payload size. */
    public static final int MAX_CHUNK_SIZE = 64 * 1024;
    /** Hard cap for a single bgm file, larger requests are rejected. */
    public static final long MAX_FILE_BYTES = 64L * 1024L * 1024L;
    /** Hard bound for a declared chunk count. */
    public static final int MAX_TOTAL_CHUNKS = 65536;
    private static final int MAX_NAME_BYTES = 1024;

    private String fileName;
    private int chunkIndex;
    private int totalChunks;
    private byte[] data;
    private long totalLength = -1L;
    private long sessionId;
    private int chunkSize = DEFAULT_CHUNK_SIZE;
    /** Set when decode rejected the packet; {@link #handle(MessageContext)} then ignores it. */
    private boolean invalid;

    @SuppressWarnings("unused")
    public BGMFileChunkPacket() {}

    public BGMFileChunkPacket(String fileName, int chunkIndex, int totalChunks, byte[] data) {
        this(fileName, chunkIndex, totalChunks, -1L, 0L, DEFAULT_CHUNK_SIZE, data);
    }

    public BGMFileChunkPacket(String fileName, int chunkIndex, int totalChunks, long totalLength,
                              long sessionId, int chunkSize, byte[] data) {
        this.fileName = fileName;
        this.chunkIndex = chunkIndex;
        this.totalChunks = totalChunks;
        this.totalLength = totalLength;
        this.sessionId = sessionId;
        this.chunkSize = chunkSize;
        this.data = data;
    }

    /** Explicit "this request will not be served" answer, so the client can fail fast. */
    public static BGMFileChunkPacket failure(String fileName, long sessionId) {
        return new BGMFileChunkPacket(fileName, 0, 0, 0L, sessionId, DEFAULT_CHUNK_SIZE, new byte[0]);
    }

    @Override
    public void encode(ByteBuf buf) {
        byte[] nameBytes = fileName.getBytes(StandardCharsets.UTF_8);
        buf.writeShort(nameBytes.length);
        buf.writeBytes(nameBytes);
        buf.writeInt(chunkIndex);
        buf.writeInt(totalChunks);
        byte[] payload = data != null ? data : new byte[0];
        buf.writeInt(payload.length);
        buf.writeBytes(payload);
        // Appended fields: legacy decoders stop after the payload and ignore these.
        buf.writeLong(totalLength);
        buf.writeLong(sessionId);
        buf.writeInt(chunkSize);
    }

    @Override
    public void decode(ByteBuf buf) {
        invalid = false;
        if (buf.readableBytes() < 2) {
            reject("truncated chunk header");
            return;
        }
        int nameLen = buf.readUnsignedShort();
        if (nameLen <= 0 || nameLen > MAX_NAME_BYTES || buf.readableBytes() < nameLen) {
            reject("invalid file name length " + nameLen);
            return;
        }
        byte[] nameBytes = new byte[nameLen];
        buf.readBytes(nameBytes);
        if (buf.readableBytes() < 12) {
            reject("truncated chunk header");
            return;
        }
        int index = buf.readInt();
        int total = buf.readInt();
        int len = buf.readInt();
        // Validate every declared size before allocating anything.
        if (index < 0 || total < 0 || total > MAX_TOTAL_CHUNKS
                || len < 0 || len > MAX_CHUNK_SIZE || buf.readableBytes() < len) {
            reject("invalid chunk header (index=" + index + ", total=" + total + ", length=" + len + ")");
            return;
        }
        if (total == 0 && len != 0) {
            reject("rejection packet carries a payload");
            return;
        }
        fileName = new String(nameBytes, StandardCharsets.UTF_8);
        chunkIndex = index;
        totalChunks = total;
        if (len == 0) {
            data = new byte[0];
        } else {
            data = new byte[len];
            buf.readBytes(data);
        }
        // Optional trailing metadata; older senders do not include it.
        totalLength = buf.isReadable(8) ? buf.readLong() : -1L;
        sessionId = buf.isReadable(8) ? buf.readLong() : 0L;
        chunkSize = buf.isReadable(4) ? buf.readInt() : DEFAULT_CHUNK_SIZE;
        if (chunkSize <= 0 || chunkSize > MAX_CHUNK_SIZE) {
            reject("invalid chunk size " + chunkSize);
        }
    }

    private void reject(String reason) {
        invalid = true;
        data = null;
        fileName = null;
        LOGGER.warn("Rejected a malformed bgm file chunk: {}", reason);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void handle(MessageContext ctx) {
        if (invalid || fileName == null || data == null) {
            return;
        }
        final String name = fileName;
        final byte[] payload = data;
        final int index = chunkIndex;
        final int total = totalChunks;
        final long length = totalLength;
        final long session = sessionId;
        final int size = chunkSize;
        Minecraft.getMinecraft().addScheduledTask(() ->
                BGMPlayer.getInstance().receiveChunk(name, index, total, length, session, size, payload));
    }
}
