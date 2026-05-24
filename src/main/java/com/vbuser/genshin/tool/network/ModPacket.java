package com.vbuser.genshin.tool.network;

import io.netty.buffer.ByteBuf;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

public abstract class ModPacket {

    public abstract void encode(ByteBuf buf);

    public abstract void decode(ByteBuf buf);

    public abstract void handle(MessageContext ctx);

    public boolean neverAggregate() {
        return false;
    }

}