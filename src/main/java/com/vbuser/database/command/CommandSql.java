package com.vbuser.database.command;

import com.vbuser.database.network.OpenSqlConsolePacket;
import com.vbuser.genshin.tool.network.NetworkManager;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;

@SuppressWarnings("all")
public class CommandSql extends CommandBase {
    @Override
    public String getName() {
        return "sql";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/sql - Open SQL Command GUI.";
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) {
        if (sender instanceof EntityPlayerMP) {
            EntityPlayerMP player = (EntityPlayerMP) sender;
            NetworkManager.sendToPlayer(new OpenSqlConsolePacket(), player);
        } else {
            sender.sendMessage(new TextComponentString(TextFormatting.RED + "Only players can execute this command!"));
        }
    }
}