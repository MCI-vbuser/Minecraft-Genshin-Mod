package com.vbuser.database.sql;

import com.vbuser.database.network.SqlExecutePacket;
import com.vbuser.genshin.tool.network.NetworkManager;
import net.minecraftforge.fml.common.FMLCommonHandler;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public class SqlApi {
    private static long nextRequestId = 0;
    private static final ConcurrentHashMap<Long, Consumer<SqlExecutionResult>> callbacks = new ConcurrentHashMap<>();

    public static void executeSql(String sql, List<Object> params, Consumer<SqlExecutionResult> callback) {
        if (FMLCommonHandler.instance().getEffectiveSide().isServer()) {
            SqlExecutionResult result = DatabaseHelper.executeSql(sql, params);
            callback.accept(result);
        } else {
            long requestId = nextRequestId++;
            callbacks.put(requestId, callback);
            SqlExecutePacket packet = new SqlExecutePacket(sql, params, requestId);
            NetworkManager.sendToServer(packet);
        }
    }

    public static void completeRequest(long requestId, SqlExecutionResult result) {
        Consumer<SqlExecutionResult> callback = callbacks.remove(requestId);
        if (callback != null) {
            callback.accept(result);
        }
    }
}
