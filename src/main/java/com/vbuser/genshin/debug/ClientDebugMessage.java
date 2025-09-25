package com.vbuser.genshin.debug;

import io.netty.buffer.ByteBuf;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

public class ClientDebugMessage implements IMessage {
    public boolean enabled;
    public String entityFilter;

    public ClientDebugMessage() {}

    public ClientDebugMessage(boolean enabled, String entityFilter) {
        this.enabled = enabled;
        this.entityFilter = entityFilter;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.enabled = buf.readBoolean();
        this.entityFilter = ByteBufUtils.readUTF8String(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeBoolean(enabled);
        ByteBufUtils.writeUTF8String(buf, entityFilter);
    }

    public static class Handler implements IMessageHandler<ClientDebugMessage, IMessage> {
        @Override
        public IMessage onMessage(ClientDebugMessage message, MessageContext ctx) {
            net.minecraftforge.fml.common.FMLCommonHandler.instance().getWorldThread(ctx.netHandler)
                    .addScheduledTask(() -> {
                        ClientDebugHandler.setDebugEnabled(message.enabled, message.entityFilter);
                    });
            return null;
        }
    }
}
