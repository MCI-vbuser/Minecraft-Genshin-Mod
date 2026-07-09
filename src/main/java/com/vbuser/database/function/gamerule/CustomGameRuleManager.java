package com.vbuser.database.function.gamerule;

import com.vbuser.database.function.gamerule.util.RuleEntry;
import com.vbuser.database.sql.DatabaseHelper;
import com.vbuser.database.sql.SqlExecutionResult;
import com.vbuser.database.function.gamerule.network.SyncAllRulesPacket;
import com.vbuser.database.function.gamerule.network.SyncRuleUpdatePacket;
import com.vbuser.genshin.tool.network.NetworkManager;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.common.FMLCommonHandler;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class CustomGameRuleManager {
    private static CustomGameRuleManager instance;
    private final ConcurrentHashMap<String, RuleEntry> rules = new ConcurrentHashMap<>();

    public static CustomGameRuleManager getInstance() {
        return instance;
    }

    public static void init(File worldDir) {
        instance = new CustomGameRuleManager(worldDir);
        instance.loadFromDatabase();
    }

    private void initDefaultRules() {
        addDefaultRuleIfAbsent("test_int", "0", "int");
        addDefaultRuleIfAbsent("test_string", "vbuser", "string");
        addDefaultRuleIfAbsent("test_boolean", "false", "boolean");
    }

    private void addDefaultRuleIfAbsent(String rule, String value, String type) {
        if (!rules.containsKey(rule)) {
            SqlExecutionResult mergeResult = DatabaseHelper.executeSql(
                    "MERGE INTO gamerule (rule, rule_value, rule_type) KEY(rule) VALUES (?, ?, ?)",
                    Arrays.asList(rule, value, type)
            );
            if (mergeResult != null && mergeResult.isSuccess()) {
                rules.put(rule, new RuleEntry(rule, value, type));
                System.out.println("[CustomGameRule] Default rule '" + rule + "' inserted/merged.");
            } else {
                System.out.println("[CustomGameRule] Failed to merge default rule '" + rule + "': " +
                        (mergeResult != null ? mergeResult.getErrorMessage() : "null"));
            }
        }
    }

    private CustomGameRuleManager(File ignored) {
    }

    private void loadFromDatabase() {
        DatabaseHelper.executeSql(
                "CREATE TABLE IF NOT EXISTS gamerule (rule VARCHAR(255) PRIMARY KEY, rule_value TEXT, rule_type VARCHAR(50))",
                Collections.emptyList()
        );

        SqlExecutionResult result = DatabaseHelper.executeSql(
                "SELECT rule AS \"rule\", rule_value AS \"rule_value\", rule_type AS \"rule_type\" FROM gamerule",
                Collections.emptyList()
        );
        if (result != null && result.isSuccess()) {
            List<Map<String, Object>> rows = result.getRows();
            if (rows != null) {
                for (Map<String, Object> row : rows) {
                    String rule = (String) row.get("rule");
                    String value = (String) row.get("rule_value");
                    String type = (String) row.get("rule_type");
                    rules.put(rule, new RuleEntry(rule, value, type));
                }
            }
        }

        initDefaultRules();
    }

    public RuleEntry getRule(String rule) {
        return rules.get(rule);
    }

    public boolean setRule(String ruleName, String value) {
        RuleEntry old = rules.get(ruleName);
        if (old == null) return false;

        String type = old.getType();

        SqlExecutionResult result = DatabaseHelper.executeSql(
                "UPDATE gamerule SET rule_value = ? WHERE rule = ?",
                Arrays.asList(value, ruleName)
        );
        if (result != null && result.isSuccess()) {
            RuleEntry updated = new RuleEntry(ruleName, value, type);
            rules.put(ruleName, updated);
            broadcastUpdate(updated);
            return true;
        }
        return false;
    }

    private void broadcastUpdate(RuleEntry entry) {
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null) return;
        SyncRuleUpdatePacket packet = new SyncRuleUpdatePacket(
                entry.getRule(), entry.getValue(), entry.getType()
        );
        for (EntityPlayerMP player : server.getPlayerList().getPlayers()) {
            NetworkManager.sendToPlayer(packet, player);
        }
    }

    public void sendAllRulesToPlayer(EntityPlayerMP player) {
        List<RuleEntry> allRules = new ArrayList<>(rules.values());
        NetworkManager.sendToPlayer(new SyncAllRulesPacket(allRules), player);
    }
}