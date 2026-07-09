package com.vbuser.database.function.gamerule.network;

import com.vbuser.database.function.gamerule.CustomGameRuleApi;
import com.vbuser.genshin.tool.network.ModPacket;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.nio.charset.StandardCharsets;

public class SetRuleResponsePacket extends ModPacket {
    private long requestId;
    private boolean success;
    private String newValue;

    @SuppressWarnings("unused")
    public SetRuleResponsePacket() {}

    public SetRuleResponsePacket(long requestId, boolean success, String newValue) {
        this.requestId = requestId;
        this.success = success;
        this.newValue = newValue;
    }

    @Override
    public void encode(ByteBuf buf) {
        buf.writeLong(requestId);
        buf.writeBoolean(success);
        if (success) {
            writeString(buf, newValue);
        }
    }

    @Override
    public void decode(ByteBuf buf) {
        requestId = buf.readLong();
        success = buf.readBoolean();
        if (success) {
            newValue = readString(buf);
        }
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void handle(MessageContext ctx) {
        Minecraft.getMinecraft().addScheduledTask(() ->
                CustomGameRuleApi.completeSetRule(requestId, success, newValue)
        );
    }

    private void writeString(ByteBuf buf, String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        buf.writeShort(bytes.length);
        buf.writeBytes(bytes);
    }

    private String readString(ByteBuf buf) {
        int len = buf.readUnsignedShort();
        byte[] bytes = new byte[len];
        buf.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}