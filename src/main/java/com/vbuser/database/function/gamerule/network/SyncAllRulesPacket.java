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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SyncAllRulesPacket extends ModPacket {
    private List<RuleEntry> rules;

    @SuppressWarnings("unused")
    public SyncAllRulesPacket() {}

    public SyncAllRulesPacket(List<RuleEntry> rules) {
        this.rules = rules;
    }

    @Override
    public void encode(ByteBuf buf) {
        buf.writeInt(rules.size());
        for (RuleEntry entry : rules) {
            writeString(buf, entry.getRule());
            writeString(buf, entry.getValue());
            writeString(buf, entry.getType());
        }
    }

    @Override
    public void decode(ByteBuf buf) {
        int size = buf.readInt();
        rules = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            String rule = readString(buf);
            String value = readString(buf);
            String type = readString(buf);
            rules.add(new RuleEntry(rule, value, type));
        }
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void handle(MessageContext ctx) {
        Map<String, RuleEntry> map = new HashMap<>();
        for (RuleEntry entry : rules) {
            map.put(entry.getRule(), entry);
        }
        Minecraft.getMinecraft().addScheduledTask(() -> ClientRuleCache.putAll(map));
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