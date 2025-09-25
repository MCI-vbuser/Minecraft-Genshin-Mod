package com.vbuser.movement.network;

import com.vbuser.movement.data.InputData;
import com.vbuser.movement.event.FakeInput;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.PacketBuffer;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.UUID;

public class S2CInput implements IMessage {

    public S2CInput(){}

    public S2CInput(EntityPlayer p, InputData.State state){
        uuid = p.getUniqueID();
        this.state = state;
    }

    public UUID uuid;
    public InputData.State state;

    @Override
    public void fromBytes(ByteBuf buf) {
        state = new InputData.State();
        state.moveStrafing = buf.readFloat();
        state.moveForward = buf.readFloat();
        PacketBuffer buffer = new PacketBuffer(buf);
        uuid = UUID.fromString(buffer.readString(114));
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeFloat(state.moveStrafing);
        buf.writeFloat(state.moveForward);
        PacketBuffer buffer = new PacketBuffer(buf);
        buffer.writeString(uuid.toString());
    }

    public static class Handler implements IMessageHandler<S2CInput,IMessage>{
        @Override
        public IMessage onMessage(S2CInput message, MessageContext ctx) {
            if(ctx.side.isServer()) return null;
            Minecraft.getMinecraft().addScheduledTask(()->{
                EntityPlayer player = Minecraft.getMinecraft().world.getPlayerEntityByUUID(message.uuid);
                if (player != null) {
                    InputData.State state1 = FakeInput.get(player);
                    state1.moveForward = message.state.moveForward;
                    state1.moveStrafing = message.state.moveStrafing;
                }
            });
            return null;
        }
    }
}
