package com.vbuser.genshin.tool.al.network;

import com.vbuser.genshin.tool.al.BGMPlayer;
import com.vbuser.genshin.tool.network.ModPacket;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

public class StopBGMPacket extends ModPacket {
    public StopBGMPacket() {}
    @Override public void encode(ByteBuf buf) {}
    @Override public void decode(ByteBuf buf) {}

    @Override
    @SideOnly(Side.CLIENT)
    public void handle(MessageContext ctx) {
        Minecraft.getMinecraft().addScheduledTask(() ->
                BGMPlayer.getInstance().stopCurrent());
    }
}