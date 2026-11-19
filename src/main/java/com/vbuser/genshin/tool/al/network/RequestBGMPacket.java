package com.vbuser.genshin.tool.al.network;

import com.vbuser.genshin.tool.al.server.BGMFileServerHandler;
import com.vbuser.genshin.tool.network.ModPacket;
import com.vbuser.genshin.tool.network.NetworkManager;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.nio.charset.StandardCharsets;

/**
 * Requests a bgm file from the server.
 *
 * <p>The optional {@code sessionId} is appended at the end of the payload and echoed by
 * the server in every {@link BGMFileChunkPacket}. It lets the client discard chunks that
 * belong to an older request for the same file. A legacy sender that does not include it
 * decodes as session {@code 0}.</p>
 */
public class RequestBGMPacket extends ModPacket {
    private String fileName;
    private long sessionId;

    @SuppressWarnings("unused")
    public RequestBGMPacket() {}
    public RequestBGMPacket(String fileName) {
        this(fileName, 0L);
    }
    public RequestBGMPacket(String fileName, long sessionId) {
        this.fileName = fileName;
        this.sessionId = sessionId;
    }

    @SideOnly(Side.CLIENT)
    public static void send(String fileName) {
        send(fileName, 0L);
    }

    @SideOnly(Side.CLIENT)
    public static void send(String fileName, long sessionId) {
        NetworkManager.sendToServer(new RequestBGMPacket(fileName, sessionId));
    }

    @Override
    public void encode(ByteBuf buf) {
        byte[] b = fileName.getBytes(StandardCharsets.UTF_8);
        buf.writeShort(b.length);
        buf.writeBytes(b);
        buf.writeLong(sessionId);
    }

    @Override
    public void decode(ByteBuf buf) {
        if (buf.readableBytes() < 2) {
            fileName = null;
            sessionId = 0L;
            return;
        }
        int len = buf.readUnsignedShort();
        if (len <= 0 || len > 1024 || buf.readableBytes() < len) {
            fileName = null;
            sessionId = 0L;
            return;
        }
        byte[] b = new byte[len];
        buf.readBytes(b);
        fileName = new String(b, StandardCharsets.UTF_8);
        sessionId = buf.isReadable(8) ? buf.readLong() : 0L;
    }

    @Override
    public void handle(MessageContext ctx) {
        if (ctx.side.isServer() && fileName != null) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            BGMFileServerHandler.handleRequest(player, fileName, sessionId);
        }
    }
}
