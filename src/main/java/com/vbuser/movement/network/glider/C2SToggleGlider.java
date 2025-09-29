package com.vbuser.movement.network.glider;

import com.vbuser.movement.capability.GliderUtils;
import com.vbuser.movement.data.GliderData;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

public class C2SToggleGlider implements IMessage {
    private boolean open;
    public C2SToggleGlider() {}
    public C2SToggleGlider(boolean open) { this.open = open; }
    @Override
    public void fromBytes(ByteBuf buf) { open = buf.readBoolean(); }
    @Override
    public void toBytes(ByteBuf buf) { buf.writeBoolean(open); }
    public static class Handler implements IMessageHandler<C2SToggleGlider, IMessage> {
        @Override
        public IMessage onMessage(C2SToggleGlider msg, MessageContext ctx) {
            EntityPlayerMP p = ctx.getServerHandler().player;
            p.getServerWorld().addScheduledTask(() -> {
                GliderData.State s = GliderData.get(p);
                boolean twoAir = GliderUtils.isTwoBlocksAirBelow(p);
                if (msg.open) {
                    if (twoAir && GliderUtils.isEnabled()) {
                        s.open = true;
                    }
                } else {
                    if (s.open) {
                        s.open = false;
                        p.fallDistance = 0.0F;
                    }
                }
                if (!twoAir && s.open) {
                    s.open = false;
                    p.fallDistance = 0.0F;
                }
            });
            return null;
        }
    }
}
