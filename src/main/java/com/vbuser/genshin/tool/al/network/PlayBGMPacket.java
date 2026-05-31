package com.vbuser.genshin.tool.al.network;

import com.vbuser.genshin.tool.al.BGMPlayer;
import com.vbuser.genshin.tool.network.ModPacket;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import java.nio.charset.StandardCharsets;

public class PlayBGMPacket extends ModPacket {
    private String fileName;
    private String md5;

    @SuppressWarnings("unused")
    public PlayBGMPacket() {}
    public PlayBGMPacket(String fileName, String md5) {
        this.fileName = fileName;
        this.md5 = md5;
    }

    @Override
    public void encode(ByteBuf buf) {
        writeString(buf, fileName);
        writeString(buf, md5 != null ? md5 : "");
    }

    @Override
    public void decode(ByteBuf buf) {
        fileName = readString(buf);
        md5 = readString(buf);
        if (md5.isEmpty()) md5 = null;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void handle(MessageContext ctx) {
        Minecraft.getMinecraft().addScheduledTask(() ->
                BGMPlayer.getInstance().play(fileName, md5));
    }

    public static void writeString(ByteBuf buf, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        buf.writeShort(b.length);
        buf.writeBytes(b);
    }

    public static String readString(ByteBuf buf) {
        int len = buf.readUnsignedShort();
        byte[] b = new byte[len];
        buf.readBytes(b);
        return new String(b, StandardCharsets.UTF_8);
    }
}