package com.vbuser.database.network;

import com.vbuser.database.gui.SqlConsoleGui;
import com.vbuser.genshin.tool.network.ModPacket;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

public class OpenSqlConsolePacket extends ModPacket {

    public OpenSqlConsolePacket() {}

    @Override
    public void encode(ByteBuf buf) {
    }

    @Override
    public void decode(ByteBuf buf) {
    }

    @Override
    public void handle(MessageContext ctx) {
        if (ctx.side.isClient()) {
            openGui();
        }
    }

    @SideOnly(Side.CLIENT)
    private void openGui() {
        Minecraft.getMinecraft().addScheduledTask(() -> {
            Minecraft.getMinecraft().displayGuiScreen(new SqlConsoleGui());
        });
    }
}