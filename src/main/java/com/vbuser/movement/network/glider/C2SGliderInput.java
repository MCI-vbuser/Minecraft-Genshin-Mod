package com.vbuser.movement.network.glider;

import com.vbuser.movement.data.GliderData;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

public class C2SGliderInput implements IMessage {
    private boolean hasInput;
    private float dirX;
    private float dirZ;
    public C2SGliderInput() {}
    public C2SGliderInput(boolean hasInput, float dirX, float dirZ) {
        this.hasInput = hasInput;
        this.dirX = dirX;
        this.dirZ = dirZ;
    }
    @Override
    public void fromBytes(ByteBuf buf) {
        hasInput = buf.readBoolean();
        dirX = buf.readFloat();
        dirZ = buf.readFloat();
    }
    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeBoolean(hasInput);
        buf.writeFloat(dirX);
        buf.writeFloat(dirZ);
    }
    public static class Handler implements IMessageHandler<C2SGliderInput, IMessage> {
        @Override
        public IMessage onMessage(C2SGliderInput msg, MessageContext ctx) {
            EntityPlayerMP p = ctx.getServerHandler().player;
            p.getServerWorld().addScheduledTask(() -> {
                GliderData.State s = GliderData.get(p);
                s.hasInput = msg.hasInput;
                s.dirX = msg.dirX;
                s.dirZ = msg.dirZ;
            });
            return null;
        }
    }
}
