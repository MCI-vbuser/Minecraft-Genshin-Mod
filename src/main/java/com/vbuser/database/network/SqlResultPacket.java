package com.vbuser.database.network;

import com.vbuser.database.sql.SqlApi;
import com.vbuser.database.sql.SqlExecutionResult;
import com.vbuser.genshin.tool.network.ModPacket;
import io.netty.buffer.ByteBuf;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

public class SqlResultPacket extends ModPacket {
    private long requestId;
    private SqlExecutionResult result;

    @SuppressWarnings("unused")
    public SqlResultPacket() {}

    public SqlResultPacket(long requestId, SqlExecutionResult result) {
        this.requestId = requestId;
        this.result = result;
    }

    @Override
    public void encode(ByteBuf buf) {
        buf.writeLong(requestId);
        result.encode(buf);
    }

    @Override
    public void decode(ByteBuf buf) {
        requestId = buf.readLong();
        result = new SqlExecutionResult();
        result.decode(buf);
    }

    @Override
    public void handle(MessageContext ctx) {
        if (ctx.side.isClient()) {
            SqlApi.completeRequest(requestId, result);
        }
    }
}