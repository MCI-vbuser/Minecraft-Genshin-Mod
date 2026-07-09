package com.vbuser.database.function.gamerule.util;

public class RuleEntry {
    private final String rule;
    private final String value;
    private final String type; // e.g "string", "int", "boolean"

    public RuleEntry(String rule, String value, String type) {
        this.rule = rule;
        this.value = value;
        this.type = type;
    }

    public String getRule() { return rule; }
    public String getValue() { return value; }
    public String getType() { return type; }
}