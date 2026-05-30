package com.vbuser.database.sql;

import io.netty.buffer.ByteBuf;

import java.util.*;

public class SqlExecutionResult {
    private boolean success;
    private String errorMessage;
    private int updateCount;
    private List<Map<String, Object>> rows;

    public SqlExecutionResult() {}

    public static SqlExecutionResult update(int count) {
        SqlExecutionResult res = new SqlExecutionResult();
        res.success = true;
        res.updateCount = count;
        res.rows = null;
        return res;
    }

    public static SqlExecutionResult query(List<Map<String, Object>> rows) {
        SqlExecutionResult res = new SqlExecutionResult();
        res.success = true;
        res.rows = rows;
        res.updateCount = -1;
        return res;
    }

    public static SqlExecutionResult error(String message) {
        SqlExecutionResult res = new SqlExecutionResult();
        res.success = false;
        res.errorMessage = message;
        return res;
    }

    public void encode(ByteBuf buf) {
        buf.writeBoolean(success);
        if (!success) {
            byte[] msgBytes = errorMessage.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            buf.writeShort(msgBytes.length);
            buf.writeBytes(msgBytes);
            return;
        }
        boolean isUpdate = (rows == null);
        buf.writeBoolean(isUpdate);
        if (isUpdate) {
            buf.writeInt(updateCount);
        } else {
            buf.writeInt(rows.size());
            for (Map<String, Object> row : rows) {
                writeRow(buf, row);
            }
        }
    }

    public void decode(ByteBuf buf) {
        success = buf.readBoolean();
        if (!success) {
            int len = buf.readUnsignedShort();
            byte[] bytes = new byte[len];
            buf.readBytes(bytes);
            errorMessage = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            return;
        }
        boolean isUpdate = buf.readBoolean();
        if (isUpdate) {
            updateCount = buf.readInt();
            rows = null;
        } else {
            int rowCount = buf.readInt();
            rows = new ArrayList<>(rowCount);
            for (int i = 0; i < rowCount; i++) {
                rows.add(readRow(buf));
            }
        }
    }

    private void writeRow(ByteBuf buf, Map<String, Object> row) {
        buf.writeShort(row.size());
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            byte[] keyBytes = entry.getKey().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            buf.writeShort(keyBytes.length);
            buf.writeBytes(keyBytes);
            writeObject(buf, entry.getValue());
        }
    }

    private Map<String, Object> readRow(ByteBuf buf) {
        int colCount = buf.readUnsignedShort();
        Map<String, Object> row = new HashMap<>(colCount);
        for (int i = 0; i < colCount; i++) {
            int keyLen = buf.readUnsignedShort();
            byte[] keyBytes = new byte[keyLen];
            buf.readBytes(keyBytes);
            String key = new String(keyBytes, java.nio.charset.StandardCharsets.UTF_8);
            Object value = readObject(buf);
            row.put(key, value);
        }
        return row;
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
        } else if (obj == null) {
            buf.writeByte(5);
        } else {
            throw new IllegalArgumentException("Unsupported type: " + obj.getClass());
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
            case 5:
                return null;
            default:
                throw new IllegalStateException("Unknown type: " + type);
        }
    }

    public boolean isSuccess() { return success; }
    public String getErrorMessage() { return errorMessage; }
    public int getUpdateCount() { return updateCount; }
    public List<Map<String, Object>> getRows() { return rows; }
}
