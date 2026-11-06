package com.vbuser.genshin.tool.al.command;

import com.vbuser.genshin.tool.al.BGMAPI;
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
public class CommandBGM extends CommandBase {

    @Override
    public String getName() {
        return "bgm";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/bgm <selector> <play|stop> [name]";
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
        String operate = args[1];

        List<EntityPlayerMP> players = getPlayers(server, sender, selector);

        if ("play".equalsIgnoreCase(operate)) {
            if (args.length < 3) {
                throw new WrongUsageException("Usage: /bgm <selector> play <name>");
            }
            String fileName = args[2];
            int sent = BGMAPI.playTo(players, fileName);
            if (sent == 0) {
                throw new CommandException("Could not play '" + fileName
                        + "': the file does not exist in " + BGMAPI.getAssetDir());
            }
            sender.sendMessage(new TextComponentString(
                    "Sent BGM play '" + fileName + "' to " + sent + " player(s)."));

        } else if ("stop".equalsIgnoreCase(operate)) {
            int stopped = 0;
            for (EntityPlayerMP player : players) {
                BGMAPI.stopTo(player);
                stopped++;
            }
            sender.sendMessage(new TextComponentString(
                    "Stopped BGM for " + stopped + " player(s)."));
        } else {
            throw new WrongUsageException("Unknown operation '" + operate + "'. Use play or stop.");
        }
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args, @Nullable BlockPos targetPos) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, server.getOnlinePlayerNames());
        }
        if (args.length == 2) {
            return getListOfStringsMatchingLastWord(args, "play", "stop");
        }
        if (args.length == 3 && "play".equalsIgnoreCase(args[1])) {
            return getListOfStringsMatchingLastWord(args, BGMAPI.list());
        }
        return Collections.emptyList();
    }
}
