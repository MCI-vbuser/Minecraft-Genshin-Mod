package com.vbuser.database.function.gamerule.network;

import com.vbuser.database.function.gamerule.CustomGameRuleManager;
import com.vbuser.genshin.tool.network.ModPacket;
import com.vbuser.genshin.tool.network.NetworkManager;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.nio.charset.StandardCharsets;

public class SetRuleRequestPacket extends ModPacket {
    private String rule;
    private String value;
    private long requestId;

    @SuppressWarnings("unused")
    public SetRuleRequestPacket() {}

    public SetRuleRequestPacket(String rule, String value, long requestId) {
        this.rule = rule;
        this.value = value;
        this.requestId = requestId;
    }

    @Override
    public void encode(ByteBuf buf) {
        writeString(buf, rule);
        writeString(buf, value);
        buf.writeLong(requestId);
    }

    @Override
    public void decode(ByteBuf buf) {
        rule = readString(buf);
        value = readString(buf);
        requestId = buf.readLong();
    }

    @Override
    public void handle(MessageContext ctx) {
        EntityPlayerMP player = ctx.getServerHandler().player;
        boolean success = CustomGameRuleManager.getInstance().setRule(rule, value);
        SetRuleResponsePacket response = new SetRuleResponsePacket(requestId, success,
                success ? CustomGameRuleManager.getInstance().getRule(rule).getValue() : null);
        NetworkManager.sendToPlayer(response, player);
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