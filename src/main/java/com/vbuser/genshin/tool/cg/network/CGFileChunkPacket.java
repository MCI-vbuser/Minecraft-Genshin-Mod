package com.vbuser.genshin.tool.cg.network;

import com.vbuser.genshin.tool.cg.CGProtocol;
import com.vbuser.genshin.tool.network.ModPacket;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.nio.charset.StandardCharsets;

public class CGFileChunkPacket extends ModPacket {
    private String fileName;
    private int chunkIndex;
    private int totalChunks;
    private byte[] data;
    /** Transfer metadata, only sent by servers that support the versioned protocol. */
    private int chunkSize;
    private long totalBytes;
    private String version = CGProtocol.VERSION_NONE;
    private long sessionId;
    private boolean metadataPresent;

    @SuppressWarnings("unused")
    public CGFileChunkPacket() {}

    public CGFileChunkPacket(String fileName, int chunkIndex, int totalChunks,
                             byte[] data) {
        this(fileName, chunkIndex, totalChunks, 0, 0L, CGProtocol.VERSION_NONE, data);
    }

    public CGFileChunkPacket(String fileName, int chunkIndex, int totalChunks,
                             int chunkSize, long totalBytes, String version, byte[] data) {
        this(fileName, chunkIndex, totalChunks, chunkSize, totalBytes, version, 0L, data);
    }

    public CGFileChunkPacket(String fileName, int chunkIndex, int totalChunks,
                             int chunkSize, long totalBytes, String version, long sessionId, byte[] data) {
        this.fileName = fileName;
        this.chunkIndex = chunkIndex;
        this.totalChunks = totalChunks;
        this.chunkSize = chunkSize;
        this.totalBytes = totalBytes;
        this.version = version == null ? CGProtocol.VERSION_NONE : version;
        this.sessionId = sessionId;
        this.data = data;
        this.metadataPresent = chunkSize > 0;
    }

    @Override
    public void encode(ByteBuf buf) {
        byte[] nameBytes = fileName.getBytes(StandardCharsets.UTF_8);
        buf.writeShort(nameBytes.length);
        buf.writeBytes(nameBytes);
        buf.writeInt(chunkIndex);
        buf.writeInt(totalChunks);
        buf.writeInt(data.length);
        buf.writeBytes(data);
        // Trailing metadata: older receivers simply ignore the remaining bytes.
        buf.writeInt(chunkSize);
        buf.writeLong(totalBytes);
        byte[] versionBytes = version.getBytes(StandardCharsets.UTF_8);
        buf.writeShort(versionBytes.length);
        buf.writeBytes(versionBytes);
        buf.writeLong(this.sessionId);
    }

    @Override
    public void decode(ByteBuf buf) {
        if (buf.readableBytes() < 2) {
            throw new IllegalArgumentException("Truncated cg chunk name");
        }
        int nameLen = buf.readUnsignedShort();
        if (nameLen <= 0 || nameLen > CGProtocol.MAX_NAME_BYTES || nameLen > buf.readableBytes()) {
            throw new IllegalArgumentException("Illegal cg chunk name length: " + nameLen);
        }
        byte[] nameBytes = new byte[nameLen];
        buf.readBytes(nameBytes);
        fileName = new String(nameBytes, StandardCharsets.UTF_8);
        if (buf.readableBytes() < 12) {
            throw new IllegalArgumentException("Truncated cg chunk header");
        }
        chunkIndex = buf.readInt();
        totalChunks = buf.readInt();
        int len = buf.readInt();
        if (len < 0 || len > CGProtocol.MAX_CHUNK_BYTES || len > buf.readableBytes()) {
            throw new IllegalArgumentException("Illegal cg chunk length: " + len);
        }
        data = new byte[len];
        buf.readBytes(data);

        if (!buf.isReadable()) {
            metadataPresent = false;
            chunkSize = 0;
            totalBytes = 0L;
            version = CGProtocol.VERSION_NONE;
            sessionId = 0L;
            return;
        }
        if (buf.readableBytes() < 14) {
            throw new IllegalArgumentException("Truncated cg chunk metadata");
        }
        chunkSize = buf.readInt();
        totalBytes = buf.readLong();
        int versionLen = buf.readUnsignedShort();
        if (versionLen > CGProtocol.MAX_NAME_BYTES || versionLen > buf.readableBytes()) {
            throw new IllegalArgumentException("Illegal cg version length: " + versionLen);
        }
        byte[] versionBytes = new byte[versionLen];
        buf.readBytes(versionBytes);
        version = new String(versionBytes, StandardCharsets.UTF_8);
        sessionId = buf.isReadable(8) ? buf.readLong() : 0L;
        metadataPresent = true;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void handle(MessageContext ctx) {
        Minecraft.getMinecraft().addScheduledTask(() ->
                CGFileClientCache.receiveChunk(fileName, chunkIndex, totalChunks,
                        metadataPresent, chunkSize, totalBytes, version, sessionId, data));
    }

    @Override
    public boolean neverAggregate(){
        return true;
    }
}
