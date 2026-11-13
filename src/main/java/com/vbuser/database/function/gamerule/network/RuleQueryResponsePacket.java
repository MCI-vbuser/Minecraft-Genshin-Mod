package com.vbuser.database.function.gamerule.network;

import com.vbuser.database.function.gamerule.util.ClientRuleCache;
import com.vbuser.database.function.gamerule.util.RuleEntry;
import com.vbuser.genshin.tool.network.ModPacket;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.nio.charset.StandardCharsets;

@SuppressWarnings("unused")
public class RuleQueryResponsePacket extends ModPacket {
    private String rule;

    public RuleQueryResponsePacket() {}

    public RuleQueryResponsePacket(String rule) {
        this.rule = rule;
    }

    @Override
    public void encode(ByteBuf buf) {
        byte[] bytes = rule.getBytes(StandardCharsets.UTF_8);
        buf.writeShort(bytes.length);
        buf.writeBytes(bytes);
    }

    @Override
    public void decode(ByteBuf buf) {
        int len = buf.readUnsignedShort();
        byte[] bytes = new byte[len];
        buf.readBytes(bytes);
        rule = new String(bytes, StandardCharsets.UTF_8);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void handle(MessageContext ctx) {
        Minecraft.getMinecraft().addScheduledTask(() -> {
            RuleEntry entry = ClientRuleCache.get(rule);
            if (entry != null) {
                String message = TextFormatting.GREEN + entry.getRule() + ":client = " +
                        entry.getValue() + " [" + entry.getType() + "]";
                Minecraft.getMinecraft().player.sendMessage(new TextComponentString(message));
            } else {
                Minecraft.getMinecraft().player.sendMessage(
                        new TextComponentString(TextFormatting.RED + "Rule " + rule + " doesn't exist!")
                );
            }
        });
    }
}