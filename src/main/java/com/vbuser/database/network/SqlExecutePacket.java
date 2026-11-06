package com.vbuser.database.network;

import com.vbuser.database.sql.DatabaseHelper;
import com.vbuser.database.sql.SqlApi;
import com.vbuser.database.sql.SqlExecutionResult;
import com.vbuser.database.sql.SqlValueCodec;
import com.vbuser.genshin.tool.network.ModPacket;
import com.vbuser.genshin.tool.network.NetworkManager;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Client to server SQL request.
 *
 * <p>Two modes exist:</p>
 * <ul>
 *   <li>registered query: {@code queryName != null}. The server resolves it from the
 *       {@link SqlApi#registerQuery(String, String) registry}, so a client can only run
 *       statements the server explicitly exposed.</li>
 *   <li>raw SQL: {@code sql != null}. Requires
 *       {@link SqlApi.Authorizer#canExecute(EntityPlayerMP, String)} to pass; the default
 *       authorizer demands permission level 4.</li>
 * </ul>
 */
public class SqlExecutePacket extends ModPacket {

    /** Hard cap on a statement sent by a client. */
    public static final int MAX_SQL_LENGTH = 100_000;

    private String sql;
    private String queryName;
    private List<Object> parameters;
    private long requestId;

    @SuppressWarnings("unused")
    public SqlExecutePacket() {
    }

    public SqlExecutePacket(String sqlOrQueryName, boolean registered, List<Object> parameters, long requestId) {
        if (registered) {
            this.queryName = sqlOrQueryName;
        } else {
            this.sql = sqlOrQueryName;
        }
        this.parameters = parameters == null ? Collections.emptyList() : parameters;
        this.requestId = requestId;
    }

    @Override
    public void encode(ByteBuf buf) {
        SqlValueCodec.writeString(buf, sql);
        SqlValueCodec.writeString(buf, queryName);
        List<Object> params = parameters == null ? Collections.emptyList() : parameters;
        buf.writeInt(params.size());
        for (Object param : params) {
            SqlValueCodec.writeValue(buf, param);
        }
        buf.writeLong(requestId);
    }

    @Override
    public void decode(ByteBuf buf) {
        sql = SqlValueCodec.readString(buf);
        queryName = SqlValueCodec.readString(buf);
        int paramCount = buf.readInt();
        if (paramCount < 0 || paramCount > 4096) {
            throw new IllegalArgumentException("Illegal SQL parameter count: " + paramCount);
        }
        parameters = new ArrayList<>(paramCount);
        for (int i = 0; i < paramCount; i++) {
            parameters.add(SqlValueCodec.readValue(buf));
        }
        requestId = buf.readLong();
    }

    @Override
    public void handle(MessageContext ctx) {
        if (!ctx.side.isServer()) {
            return;
        }
        EntityPlayerMP player = ctx.getServerHandler().player;
        SqlExecutionResult result = execute(player);
        NetworkManager.sendToPlayer(new SqlResultPacket(requestId, result), player);
    }

    private SqlExecutionResult execute(EntityPlayerMP player) {
        List<Object> params = parameters == null ? Collections.emptyList() : parameters;
        if (queryName != null) {
            return SqlApi.runRegisteredQuery(queryName, params);
        }
        if (sql == null || sql.trim().isEmpty()) {
            return SqlExecutionResult.error("SQL statement must not be empty.");
        }
        if (sql.length() > MAX_SQL_LENGTH) {
            return SqlExecutionResult.error("SQL statement exceeds " + MAX_SQL_LENGTH + " characters.");
        }
        if (!SqlApi.authorizeRawSql(player, sql)) {
            return SqlExecutionResult.error(
                    "Permission denied: raw SQL from a client requires the server to authorize it.");
        }
        return DatabaseHelper.executeSql(sql, params);
    }
}
