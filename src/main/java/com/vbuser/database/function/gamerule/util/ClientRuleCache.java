package com.vbuser.database.function.gamerule.util;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ClientRuleCache {
    private static final ConcurrentHashMap<String, RuleEntry> rules = new ConcurrentHashMap<>();

    public static void putAll(Map<String, RuleEntry> entries) {
        rules.putAll(entries);
    }

    public static void update(RuleEntry entry) {
        rules.put(entry.getRule(), entry);
    }

    public static RuleEntry get(String rule) {
        return rules.get(rule);
    }

    public static String getValue(String rule) {
        RuleEntry entry = rules.get(rule);
        return entry == null ? null : entry.getValue();
    }

    public static void clear() {
        rules.clear();
    }
}