package com.vbuser.database.command;

import com.vbuser.database.sql.DatabaseHelper;
import com.vbuser.database.sql.SqlExecutionResult;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;

import java.util.Collections;
import java.util.List;

@SuppressWarnings("all")
public class CommandHash extends CommandBase {
    @Override
    public String getName() {
        return "#";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/# <SQL statement>";
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length == 0) {
            sender.sendMessage(new TextComponentString(TextFormatting.RED + "Please write down sql statement!"));
            return;
        }
        String sql = String.join(" ", args);
        SqlExecutionResult result = DatabaseHelper.executeSql(sql, Collections.emptyList());
        if (result.isSuccess()) {
            if (result.getRows() != null) {
                sender.sendMessage(new TextComponentString(TextFormatting.GREEN + "Query successfully and returned " + result.getRows().size() + " lines"));
            } else {
                sender.sendMessage(new TextComponentString(TextFormatting.GREEN + "Operated successfully and influenced " + result.getUpdateCount()+" lines"));
            }
        } else {
            sender.sendMessage(new TextComponentString(TextFormatting.RED + "SQL Error: " + result.getErrorMessage()));
        }
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args, net.minecraft.util.math.BlockPos targetPos) {
        return Collections.emptyList();
    }

    @Override
    public boolean isUsernameIndex(String[] args, int index) {
        return false;
    }
}