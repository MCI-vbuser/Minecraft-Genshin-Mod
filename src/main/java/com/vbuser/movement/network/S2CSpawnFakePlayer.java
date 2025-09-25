package com.vbuser.movement.network;

import com.vbuser.movement.entity.FakePlayer;
import com.vbuser.movement.event.FakeInput;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.UUID;

public class S2CSpawnFakePlayer implements IMessage {

    private UUID targetUUID;
    private boolean spawn;
    private double x, y, z;

    public S2CSpawnFakePlayer() {}

    public S2CSpawnFakePlayer(UUID targetUUID, boolean spawn, double x, double y, double z) {
        this.targetUUID = targetUUID;
        this.spawn = spawn;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        long most = buf.readLong();
        long least = buf.readLong();
        targetUUID = new UUID(most, least);
        spawn = buf.readBoolean();
        x = buf.readDouble();
        y = buf.readDouble();
        z = buf.readDouble();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(targetUUID.getMostSignificantBits());
        buf.writeLong(targetUUID.getLeastSignificantBits());
        buf.writeBoolean(spawn);
        buf.writeDouble(x);
        buf.writeDouble(y);
        buf.writeDouble(z);
    }

    public static class Handler implements IMessageHandler<S2CSpawnFakePlayer, IMessage> {
        @Override
        public IMessage onMessage(S2CSpawnFakePlayer message, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(() -> {
                World world = Minecraft.getMinecraft().world;
                if (world != null) {
                    if (message.spawn) {
                        FakePlayer fp = new FakePlayer(world, message.targetUUID);
                        fp.setPosition(message.x, message.y, message.z);
                        FakeInput.f_pMap.put(message.targetUUID, fp);
                        world.spawnEntity(fp);
                    } else {
                        FakePlayer fp = FakeInput.f_pMap.get(message.targetUUID);
                        if (fp != null) {
                            fp.setDead();
                            FakeInput.f_pMap.remove(message.targetUUID);
                        }
                    }
                }
            });
            return null;
        }
    }
}