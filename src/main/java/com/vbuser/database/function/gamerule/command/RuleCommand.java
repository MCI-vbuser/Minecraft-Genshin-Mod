package com.vbuser.database.function.gamerule.command;

import com.vbuser.database.function.gamerule.CustomGameRuleManager;
import com.vbuser.database.function.gamerule.network.RuleQueryResponsePacket;
import com.vbuser.database.function.gamerule.util.RuleEntry;
import com.vbuser.genshin.tool.network.NetworkManager;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;

@SuppressWarnings("all")
public class RuleCommand extends CommandBase {
    @Override
    public String getName() {
        return "rule";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/rule <rule> [value] ";
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) {
        if (args.length < 1) {
            sender.sendMessage(new TextComponentString(TextFormatting.RED + "Usage: " + getUsage(sender)));
            return;
        }
        String ruleName = args[0];
        CustomGameRuleManager manager = CustomGameRuleManager.getInstance();
        if (manager == null) {
            sender.sendMessage(new TextComponentString(TextFormatting.RED + "Uninitialized CustomGameRuleManager!。"));
            return;
        }
        if (args.length == 1) {
            RuleEntry entry = manager.getRule(ruleName);
            if (entry == null) {
                sender.sendMessage(new TextComponentString(TextFormatting.YELLOW + "Rule " + ruleName + " does not exist!"));
            } else {
                sender.sendMessage(new TextComponentString(
                        TextFormatting.GREEN + entry.getRule() + ":server = " + entry.getValue() +
                                " [" + entry.getType() + "]"
                ));

                if (sender instanceof EntityPlayerMP) {
                    NetworkManager.sendToPlayer(
                            new RuleQueryResponsePacket(entry.getRule()),
                            (EntityPlayerMP) sender
                    );
                }
            }
        } else {
            String value = args[1];
            boolean success = manager.setRule(ruleName, value);
            if (success) {
                sender.sendMessage(new TextComponentString(TextFormatting.GREEN + "Setting " + ruleName + " to " + value));
            } else {
                sender.sendMessage(new TextComponentString(TextFormatting.RED + "Fail to edit " + ruleName ));
            }
        }
    }
}