package com.vbuser.database.function.gamerule.network;

import com.vbuser.database.function.gamerule.util.ClientRuleCache;
import com.vbuser.database.function.gamerule.util.RuleEntry;
import com.vbuser.genshin.tool.network.ModPacket;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.nio.charset.StandardCharsets;

public class SyncRuleUpdatePacket extends ModPacket {
    private String rule;
    private String value;
    private String type;

    @SuppressWarnings("unused")
    public SyncRuleUpdatePacket() {}

    public SyncRuleUpdatePacket(String rule, String value, String type) {
        this.rule = rule;
        this.value = value;
        this.type = type;
    }

    @Override
    public void encode(ByteBuf buf) {
        writeString(buf, rule);
        writeString(buf, value);
        writeString(buf, type);
    }

    @Override
    public void decode(ByteBuf buf) {
        rule = readString(buf);
        value = readString(buf);
        type = readString(buf);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void handle(MessageContext ctx) {
        Minecraft.getMinecraft().addScheduledTask(() ->
                ClientRuleCache.update(new RuleEntry(rule, value, type))
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