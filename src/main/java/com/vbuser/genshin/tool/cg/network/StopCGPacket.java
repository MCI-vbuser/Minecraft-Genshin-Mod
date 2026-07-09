package com.vbuser.genshin.tool.cg.network;

import com.vbuser.genshin.tool.cg.decoder.VideoPlaybackManager;
import com.vbuser.genshin.tool.network.ModPacket;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

public class StopCGPacket extends ModPacket {

    @SuppressWarnings("unused")
    public StopCGPacket() {}

    @Override
    public void encode(ByteBuf buf) {
    }

    @Override
    public void decode(ByteBuf buf) {
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void handle(MessageContext ctx) {
        Minecraft.getMinecraft().addScheduledTask(() -> VideoPlaybackManager.getInstance().stopVideo());
    }

    @Override
    public boolean neverAggregate() {
        return true;
    }
}