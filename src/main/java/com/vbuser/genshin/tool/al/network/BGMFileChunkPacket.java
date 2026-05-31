package com.vbuser.genshin.tool.al.network;

import com.vbuser.genshin.tool.al.BGMPlayer;
import com.vbuser.genshin.tool.network.ModPacket;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.nio.charset.StandardCharsets;

public class BGMFileChunkPacket extends ModPacket {
    private String fileName;
    private int chunkIndex;
    private int totalChunks;
    private byte[] data;

    @SuppressWarnings("unused")
    public BGMFileChunkPacket() {}

    public BGMFileChunkPacket(String fileName, int chunkIndex, int totalChunks, byte[] data) {
        this.fileName = fileName;
        this.chunkIndex = chunkIndex;
        this.totalChunks = totalChunks;
        this.data = data;
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
    }

    @Override
    public void decode(ByteBuf buf) {
        int nameLen = buf.readUnsignedShort();
        byte[] nameBytes = new byte[nameLen];
        buf.readBytes(nameBytes);
        fileName = new String(nameBytes, StandardCharsets.UTF_8);
        chunkIndex = buf.readInt();
        totalChunks = buf.readInt();
        int len = buf.readInt();
        data = new byte[len];
        buf.readBytes(data);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void handle(MessageContext ctx) {
        Minecraft.getMinecraft().addScheduledTask(() ->
                BGMPlayer.getInstance().receiveChunk(fileName, chunkIndex, totalChunks, data));
    }
}