package com.vbuser.genshin.tool.cg.network;

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

    @SuppressWarnings("unused")
    public PlayCGPacket() {}
    public PlayCGPacket(String fileName) {
        this.fileName = fileName;
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
    @SideOnly(Side.CLIENT)
    public void handle(MessageContext ctx) {
        Minecraft.getMinecraft().addScheduledTask(() -> VideoPlaybackManager.getInstance().playVideo(fileName));
    }
}