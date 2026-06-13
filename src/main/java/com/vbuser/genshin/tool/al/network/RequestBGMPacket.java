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

public class RequestBGMPacket extends ModPacket {
    private String fileName;

    @SuppressWarnings("unused")
    public RequestBGMPacket() {}
    public RequestBGMPacket(String fileName) {
        this.fileName = fileName;
    }

    @SideOnly(Side.CLIENT)
    public static void send(String fileName) {
        NetworkManager.sendToServer(new RequestBGMPacket(fileName));
    }

    @Override
    public void encode(ByteBuf buf) {
        byte[] b = fileName.getBytes(StandardCharsets.UTF_8);
        buf.writeShort(b.length);
        buf.writeBytes(b);
    }

    @Override
    public void decode(ByteBuf buf) {
        int len = buf.readUnsignedShort();
        byte[] b = new byte[len];
        buf.readBytes(b);
        fileName = new String(b, StandardCharsets.UTF_8);
    }

    @Override
    public void handle(MessageContext ctx) {
        if (ctx.side.isServer()) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            if (fileName.contains("..")) {
                return;
            }
            BGMFileServerHandler.handleRequest(player, fileName);
        }
    }
}