package com.vbuser.movement.network;

import com.vbuser.movement.Movement;
import com.vbuser.movement.data.InputData;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

public class C2SInput implements IMessage {

    public C2SInput(){

    }

    public C2SInput(InputData.State state){
        this.state = state;
    }

    public InputData.State state;

    @Override
    public void fromBytes(ByteBuf buf) {
        state = new InputData.State();
        state.moveForward = buf.readFloat();
        state.moveStrafing = buf.readFloat();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeFloat(state.moveForward);
        buf.writeFloat(state.moveStrafing);
    }

    public static class Handler implements IMessageHandler<C2SInput, IMessage> {

        @Override
        public IMessage onMessage(C2SInput message, MessageContext ctx) {
            if (ctx.side.isServer()) {
                EntityPlayerMP p = ctx.getServerHandler().player;
                p.getServerWorld().addScheduledTask(() -> {
                    InputData.State state1 = InputData.get(p);
                    state1.moveStrafing = message.state.moveStrafing;
                    state1.moveForward = message.state.moveForward;

                    Movement.network.sendToAll(new S2CInput(p,state1));
                });
            }
            return null;
        }
    }
}
