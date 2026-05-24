package com.vbuser.genshin.tool.network.util;

import com.vbuser.genshin.tool.network.AggregatedPacket;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.Objects;

public class AggregatedPacketHandler implements IMessageHandler<AggregatedPacket, IMessage> {
    @Override
    public IMessage onMessage(AggregatedPacket message, MessageContext ctx) {
        if (ctx.side.isClient()) {
            net.minecraft.client.Minecraft.getMinecraft().addScheduledTask(() -> message.handle(ctx));
        } else {
            Objects.requireNonNull(ctx.getServerHandler().player.getServer()).addScheduledTask(() -> message.handle(ctx));
        }
        return null;
    }
}
