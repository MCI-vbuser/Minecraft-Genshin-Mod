package com.vbuser.database.network;

import com.vbuser.database.sql.DatabaseHelper;
import com.vbuser.database.sql.SqlExecutionResult;
import com.vbuser.genshin.tool.network.ModPacket;
import com.vbuser.genshin.tool.network.NetworkManager;
import io.netty.buffer.ByteBuf;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.ArrayList;
import java.util.List;

public class SqlExecutePacket extends ModPacket {
    private String sql;
    private List<Object> parameters;
    private long requestId;

    @SuppressWarnings("unused")
    public SqlExecutePacket() {}

    public SqlExecutePacket(String sql, List<Object> parameters, long requestId) {
        this.sql = sql;
        this.parameters = parameters;
        this.requestId = requestId;
    }

    @Override
    public void encode(ByteBuf buf) {
        byte[] sqlBytes = sql.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        buf.writeShort(sqlBytes.length);
        buf.writeBytes(sqlBytes);

        buf.writeShort(parameters.size());
        for (Object param : parameters) {
            writeObject(buf, param);
        }

        buf.writeLong(requestId);
    }

    @Override
    public void decode(ByteBuf buf) {
        int sqlLen = buf.readUnsignedShort();
        byte[] sqlBytes = new byte[sqlLen];
        buf.readBytes(sqlBytes);
        sql = new String(sqlBytes, java.nio.charset.StandardCharsets.UTF_8);

        int paramCount = buf.readUnsignedShort();
        parameters = new ArrayList<>(paramCount);
        for (int i = 0; i < paramCount; i++) {
            parameters.add(readObject(buf));
        }

        requestId = buf.readLong();
    }

    @Override
    public void handle(MessageContext ctx) {
        if (ctx.side.isServer()) {
            SqlExecutionResult result = DatabaseHelper.executeSql(sql, parameters);
            SqlResultPacket resultPacket = new SqlResultPacket(requestId, result);
            NetworkManager.sendToPlayer(resultPacket, ctx.getServerHandler().player);
        }
    }

    private void writeObject(ByteBuf buf, Object obj) {
        if (obj instanceof String) {
            buf.writeByte(0);
            byte[] bytes = ((String) obj).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            buf.writeShort(bytes.length);
            buf.writeBytes(bytes);
        } else if (obj instanceof Integer) {
            buf.writeByte(1);
            buf.writeInt((Integer) obj);
        } else if (obj instanceof Long) {
            buf.writeByte(2);
            buf.writeLong((Long) obj);
        } else if (obj instanceof Double) {
            buf.writeByte(3);
            buf.writeDouble((Double) obj);
        } else if (obj instanceof Boolean) {
            buf.writeByte(4);
            buf.writeBoolean((Boolean) obj);
        } else {
            throw new IllegalArgumentException("Unsupported parameter type: " + obj.getClass());
        }
    }

    private Object readObject(ByteBuf buf) {
        int type = buf.readByte();
        switch (type) {
            case 0:
                int len = buf.readUnsignedShort();
                byte[] bytes = new byte[len];
                buf.readBytes(bytes);
                return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            case 1:
                return buf.readInt();
            case 2:
                return buf.readLong();
            case 3:
                return buf.readDouble();
            case 4:
                return buf.readBoolean();
            default:
                throw new IllegalStateException("Unknown type id: " + type);
        }
    }
}
