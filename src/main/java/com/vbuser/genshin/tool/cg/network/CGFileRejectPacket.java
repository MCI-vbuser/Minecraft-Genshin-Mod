package com.vbuser.genshin.tool.cg.network;

import com.vbuser.genshin.tool.cg.CGProtocol;
import com.vbuser.genshin.tool.network.ModPacket;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.nio.charset.StandardCharsets;

/**
 * Explicit refusal of a cg download request.
 *
 * <p>Before this packet existed the server silently returned for a missing file and the
 * client kept waiting for a first chunk until its stall timeout, so the failure never
 * reached the caller. Every rejection path now sends this packet, which fails the pending
 * transfer immediately and exactly once.</p>
 */
public class CGFileRejectPacket extends ModPacket {

    private String fileName;
    private String reason;
    private long sessionId;

    @SuppressWarnings("unused")
    public CGFileRejectPacket() {
    }

    public CGFileRejectPacket(String fileName, String reason) {
        this(fileName, reason, 0L);
    }

    public CGFileRejectPacket(String fileName, String reason, long sessionId) {
        this.fileName = fileName;
        this.reason = reason == null ? "" : reason;
        this.sessionId = sessionId;
    }

    @Override
    public void encode(ByteBuf buf) {
        writeString(buf, fileName);
        writeString(buf, reason);
        buf.writeLong(this.sessionId);
    }

    @Override
    public void decode(ByteBuf buf) {
        fileName = readString(buf);
        reason = readString(buf);
        this.sessionId = buf.isReadable(8) ? buf.readLong() : 0L;
    }

    private static void writeString(ByteBuf buf, String value) {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        buf.writeShort(bytes.length);
        buf.writeBytes(bytes);
    }

    private static String readString(ByteBuf buf) {
        int length = buf.readUnsignedShort();
        if (length > CGProtocol.MAX_NAME_BYTES || length > buf.readableBytes()) {
            throw new IllegalArgumentException("Illegal cg reject string length: " + length);
        }
        byte[] bytes = new byte[length];
        buf.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void handle(MessageContext ctx) {
        Minecraft.getMinecraft().addScheduledTask(() ->
                CGFileClientCache.receiveReject(fileName, reason, sessionId));
    }

    @Override
    public boolean neverAggregate() {
        return true;
    }
}
