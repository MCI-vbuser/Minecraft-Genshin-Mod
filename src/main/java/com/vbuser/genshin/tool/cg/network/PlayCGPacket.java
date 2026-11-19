package com.vbuser.genshin.tool.cg.network;

import com.vbuser.genshin.tool.cg.CGProtocol;
import com.vbuser.genshin.tool.cg.decoder.VideoPlaybackManager;
import com.vbuser.genshin.tool.network.ModPacket;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.nio.charset.StandardCharsets;

public class PlayCGPacket extends ModPacket {
    private String fileName;
    /**
     * Content version of the file on the server. A client that already has the same name
     * with different content re-downloads instead of playing the stale local movie;
     * {@link CGProtocol#VERSION_LOCAL} (older senders) keeps the local copy playable.
     */
    private String version = CGProtocol.VERSION_LOCAL;

    @SuppressWarnings("unused")
    public PlayCGPacket() {}
    public PlayCGPacket(String fileName) {
        this(fileName, CGProtocol.VERSION_LOCAL);
    }

    public PlayCGPacket(String fileName, String version) {
        this.fileName = fileName;
        this.version = version == null ? CGProtocol.VERSION_LOCAL : version;
    }

    @Override
    public void encode(ByteBuf buf) {
        byte[] b = fileName.getBytes(StandardCharsets.UTF_8);
        buf.writeShort(b.length);
        buf.writeBytes(b);
        // Trailing field: older receivers ignore the remaining bytes.
        byte[] v = version.getBytes(StandardCharsets.UTF_8);
        buf.writeShort(v.length);
        buf.writeBytes(v);
    }

    @Override
    public void decode(ByteBuf buf) {
        int len = buf.readUnsignedShort();
        if (len > CGProtocol.MAX_NAME_BYTES || len > buf.readableBytes()) {
            throw new IllegalArgumentException("Illegal cg play name length: " + len);
        }
        byte[] b = new byte[len];
        buf.readBytes(b);
        fileName = new String(b, StandardCharsets.UTF_8);
        if (buf.isReadable()) {
            int versionLen = buf.readUnsignedShort();
            if (versionLen > CGProtocol.MAX_NAME_BYTES || versionLen > buf.readableBytes()) {
                throw new IllegalArgumentException("Illegal cg play version length: " + versionLen);
            }
            byte[] v = new byte[versionLen];
            buf.readBytes(v);
            version = new String(v, StandardCharsets.UTF_8);
        } else {
            version = CGProtocol.VERSION_LOCAL;
        }
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void handle(MessageContext ctx) {
        Minecraft.getMinecraft().addScheduledTask(
                () -> VideoPlaybackManager.getInstance().playVideo(fileName, version));
    }
}
