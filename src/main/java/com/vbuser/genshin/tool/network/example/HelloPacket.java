package com.vbuser.genshin.tool.network.example;

import com.vbuser.genshin.tool.network.ModPacket;
import io.netty.buffer.ByteBuf;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

public class HelloPacket extends ModPacket {
    private String message;

    public HelloPacket() {}

    public HelloPacket(String message) {
        this.message = message;
    }

    @Override
    public void encode(ByteBuf buf) {
        byte[] bytes = message.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        buf.writeShort(bytes.length);
        buf.writeBytes(bytes);
    }

    @Override
    public void decode(ByteBuf buf) {
        int len = buf.readUnsignedShort();
        byte[] bytes = new byte[len];
        buf.readBytes(bytes);
        message = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    @Override
    public void handle(MessageContext ctx) {
        if (ctx.side.isServer()) {
            System.out.println("[Server] Received hello from client: " + message);
        } else {
            System.out.println("[Client] Received hello from server: " + message);
        }
    }

    public String getMessage() { return message; }
}