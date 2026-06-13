package com.vbuser.genshin.tool.cv.command;

import com.vbuser.genshin.tool.cv.network.PlayCGPacket;
import com.vbuser.genshin.tool.network.NetworkManager;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;

@SuppressWarnings("all")
public class CommandCG extends CommandBase {

    @Override
    public String getName() {
        return "cg";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/cg <selector> <file>";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length < 2) {
            throw new WrongUsageException(getUsage(sender));
        }

        String selector = args[0];
        String fileName = args[1];

        List<EntityPlayerMP> players = getPlayers(server, sender, selector);

        PlayCGPacket packet = new PlayCGPacket(fileName);
        for (EntityPlayerMP player : players) {
            NetworkManager.sendToPlayer(packet, player);
        }
        sender.sendMessage(new TextComponentString(
                "Sent CG play '" + fileName + "' to " + players.size() + " player(s)."));
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args, @Nullable BlockPos targetPos) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, server.getOnlinePlayerNames());
        }
        return Collections.emptyList();
    }
}