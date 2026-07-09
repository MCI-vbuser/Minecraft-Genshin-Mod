package com.vbuser.database.function.gamerule;

import com.vbuser.database.function.gamerule.network.SetRuleRequestPacket;
import com.vbuser.database.function.gamerule.util.ClientRuleCache;
import com.vbuser.database.function.gamerule.util.RuleEntry;
import com.vbuser.genshin.tool.network.NetworkManager;
import net.minecraftforge.fml.common.FMLCommonHandler;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

@SuppressWarnings("unused")
public class CustomGameRuleApi {
    private static long nextRequestId = 0;
    private static final ConcurrentHashMap<Long, Consumer<Boolean>> setRuleCallbacks = new ConcurrentHashMap<>();

    public static String getRuleValue(String rule) {
        if (FMLCommonHandler.instance().getEffectiveSide().isServer()) {
            RuleEntry entry = CustomGameRuleManager.getInstance().getRule(rule);
            return entry == null ? null : entry.getValue();
        } else {
            return ClientRuleCache.getValue(rule);
        }
    }

    public static RuleEntry getRule(String rule) {
        if (FMLCommonHandler.instance().getEffectiveSide().isServer()) {
            return CustomGameRuleManager.getInstance().getRule(rule);
        } else {
            return ClientRuleCache.get(rule);
        }
    }

    public static void setRule(String rule, String value, Consumer<Boolean> callback) {
        if (FMLCommonHandler.instance().getEffectiveSide().isServer()) {
            boolean success = CustomGameRuleManager.getInstance().setRule(rule, value);
            if (callback != null) callback.accept(success);
        } else {
            long requestId = nextRequestId++;
            setRuleCallbacks.put(requestId, callback);
            SetRuleRequestPacket packet = new SetRuleRequestPacket(rule, value, requestId);
            NetworkManager.sendToServer(packet);
        }
    }

    public static void completeSetRule(long requestId, boolean success, String newValue) {
        Consumer<Boolean> callback = setRuleCallbacks.remove(requestId);
        if (callback != null) {
            callback.accept(success);
        }
    }
}