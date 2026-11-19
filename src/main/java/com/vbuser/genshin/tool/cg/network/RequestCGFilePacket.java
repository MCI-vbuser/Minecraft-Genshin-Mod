package com.vbuser.genshin.tool.cg.network;

import com.vbuser.genshin.tool.cg.CGFileManager;
import com.vbuser.genshin.tool.cg.CGProtocol;
import com.vbuser.genshin.tool.cg.server.CGFileServerHandler;
import com.vbuser.genshin.tool.network.ModPacket;
import com.vbuser.genshin.tool.network.NetworkManager;
import io.netty.buffer.ByteBuf;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.nio.charset.StandardCharsets;

public class RequestCGFilePacket extends ModPacket {
    private String fileName;
    /**
     * Content version the client expects. The server refuses to stream a file whose
     * version differs, so a stale request can never fill the cache with the wrong bytes.
     */
    private String version = CGProtocol.VERSION_NONE;
    private long sessionId;

    @SuppressWarnings("unused")
    public RequestCGFilePacket() {
    }

    public RequestCGFilePacket(String fileName) {
        this(fileName, CGProtocol.VERSION_NONE);
    }

    public RequestCGFilePacket(String fileName, String version) {
        this(fileName, version, 0L);
    }

    public RequestCGFilePacket(String fileName, String version, long sessionId) {
        this.fileName = fileName;
        this.version = version == null ? CGProtocol.VERSION_NONE : version;
        this.sessionId = sessionId;
    }

    @SideOnly(Side.CLIENT)
    public static void send(String fileName) {
        send(fileName, CGProtocol.VERSION_NONE);
    }

    @SideOnly(Side.CLIENT)
    public static void send(String fileName, String version) {
        send(fileName, version, 0L);
    }

    @SideOnly(Side.CLIENT)
    public static void send(String fileName, String version, long sessionId) {
        NetworkManager.sendToServer(new RequestCGFilePacket(fileName, version, sessionId));
    }

    @Override
    public void encode(ByteBuf buf) {
        byte[] b = fileName.getBytes(StandardCharsets.UTF_8);
        buf.writeShort(b.length);
        buf.writeBytes(b);
        // Trailing field: senders of the older protocol end the payload here.
        byte[] v = version.getBytes(StandardCharsets.UTF_8);
        buf.writeShort(v.length);
        buf.writeBytes(v);
        buf.writeLong(this.sessionId);
    }

    @Override
    public void decode(ByteBuf buf) {
        int len = buf.readUnsignedShort();
        if (len > CGProtocol.MAX_NAME_BYTES || len > buf.readableBytes()) {
            throw new IllegalArgumentException("Illegal cg request name length: " + len);
        }
        byte[] b = new byte[len];
        buf.readBytes(b);
        fileName = new String(b, StandardCharsets.UTF_8);
        if (buf.isReadable()) {
            int versionLen = buf.readUnsignedShort();
            if (versionLen > CGProtocol.MAX_NAME_BYTES || versionLen > buf.readableBytes()) {
                throw new IllegalArgumentException("Illegal cg request version length: " + versionLen);
            }
            byte[] v = new byte[versionLen];
            buf.readBytes(v);
            version = new String(v, StandardCharsets.UTF_8);
        } else {
            version = CGProtocol.VERSION_NONE;
        }
        this.sessionId = buf.isReadable(8) ? buf.readLong() : 0L;
    }

    @Override
    public void handle(MessageContext ctx) {
        if (!ctx.side.isServer()) {
            return;
        }
        if (!CGFileManager.isValidName(fileName)) {
            // Rejected for every client, not only the ones using the API.
            return;
        }
        CGFileServerHandler.handleRequest(ctx.getServerHandler().player, fileName, version, sessionId);
    }
}
