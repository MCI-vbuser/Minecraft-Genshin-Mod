package com.vbuser.database.sql;

import io.netty.buffer.ByteBuf;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.UUID;

/**
 * Wire codec shared by the SQL request/response packets and by {@link SqlExecutionResult}.
 *
 * <p>This is the single place where "which Java types can travel over the SQL API"
 * is defined. Supported types are:
 * {@code null, String, Integer, Long, Double, Float, Short, Byte, Boolean,
 * BigDecimal, byte[], java.sql.Timestamp, java.sql.Date, java.sql.Time,
 * java.util.Date, UUID}.</p>
 *
 * <p>Every other JDBC type is degraded to its {@link String#valueOf(Object)} text
 * instead of throwing, so a single exotic column can never kill a whole result
 * (the old implementation threw {@code IllegalArgumentException} while the response
 * packet was being encoded, which made the packet silently disappear and left the
 * client callback pending forever).</p>
 *
 * <p>Text and binary values are capped at {@link #MAX_TEXT_BYTES} /
 * {@link #MAX_BLOB_BYTES} because a single mod packet cannot exceed the vanilla
 * custom payload limit. {@link DatabaseHelper} clips oversized values before they
 * reach this codec.</p>
 */
public final class SqlValueCodec {

    /** Maximum size of a single text value on the wire. */
    public static final int MAX_TEXT_BYTES = 30 * 1024;
    /** Maximum size of a single byte[] value on the wire. */
    public static final int MAX_BLOB_BYTES = 30 * 1024;

    private static final byte T_NULL = 0;
    private static final byte T_STRING = 1;
    private static final byte T_INT = 2;
    private static final byte T_LONG = 3;
    private static final byte T_DOUBLE = 4;
    private static final byte T_BOOLEAN = 5;
    private static final byte T_FLOAT = 6;
    private static final byte T_SHORT = 7;
    private static final byte T_BYTE = 8;
    private static final byte T_BIG_DECIMAL = 9;
    private static final byte T_BYTES = 10;
    private static final byte T_TIMESTAMP = 11;
    private static final byte T_DATE = 12;
    private static final byte T_TIME = 13;
    private static final byte T_UUID = 14;

    private SqlValueCodec() {
    }

    // ------------------------------------------------------------------ strings

    public static void writeString(ByteBuf buf, String value) {
        if (value == null) {
            buf.writeInt(-1);
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_TEXT_BYTES) {
            throw new IllegalArgumentException(
                    "Text value is " + bytes.length + " bytes, the SQL API limit is " + MAX_TEXT_BYTES);
        }
        buf.writeInt(bytes.length);
        buf.writeBytes(bytes);
    }

    public static String readString(ByteBuf buf) {
        int length = buf.readInt();
        if (length < 0) {
            return null;
        }
        if (length > MAX_TEXT_BYTES) {
            throw new IllegalArgumentException("Illegal text length on the wire: " + length);
        }
        byte[] bytes = new byte[length];
        buf.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ values

    /**
     * Encodes one scalar. Unknown types are sent as text, never as an error.
     */
    public static void writeValue(ByteBuf buf, Object value) {
        if (value == null) {
            buf.writeByte(T_NULL);
            return;
        }
        if (value instanceof String) {
            buf.writeByte(T_STRING);
            writeString(buf, (String) value);
        } else if (value instanceof Integer) {
            buf.writeByte(T_INT);
            buf.writeInt((Integer) value);
        } else if (value instanceof Long) {
            buf.writeByte(T_LONG);
            buf.writeLong((Long) value);
        } else if (value instanceof Double) {
            buf.writeByte(T_DOUBLE);
            buf.writeDouble((Double) value);
        } else if (value instanceof Boolean) {
            buf.writeByte(T_BOOLEAN);
            buf.writeBoolean((Boolean) value);
        } else if (value instanceof Float) {
            buf.writeByte(T_FLOAT);
            buf.writeFloat((Float) value);
        } else if (value instanceof Short) {
            buf.writeByte(T_SHORT);
            buf.writeShort((Short) value);
        } else if (value instanceof Byte) {
            buf.writeByte(T_BYTE);
            buf.writeByte((Byte) value);
        } else if (value instanceof BigDecimal) {
            buf.writeByte(T_BIG_DECIMAL);
            writeString(buf, ((BigDecimal) value).toPlainString());
        } else if (value instanceof byte[]) {
            byte[] bytes = (byte[]) value;
            if (bytes.length > MAX_BLOB_BYTES) {
                throw new IllegalArgumentException(
                        "Binary value is " + bytes.length + " bytes, the SQL API limit is " + MAX_BLOB_BYTES);
            }
            buf.writeByte(T_BYTES);
            buf.writeInt(bytes.length);
            buf.writeBytes(bytes);
        } else if (value instanceof Timestamp) {
            buf.writeByte(T_TIMESTAMP);
            buf.writeLong(((Timestamp) value).getTime());
        } else if (value instanceof java.sql.Date) {
            buf.writeByte(T_DATE);
            buf.writeLong(((java.sql.Date) value).getTime());
        } else if (value instanceof java.sql.Time) {
            buf.writeByte(T_TIME);
            buf.writeLong(((java.sql.Time) value).getTime());
        } else if (value instanceof java.util.Date) {
            buf.writeByte(T_TIMESTAMP);
            buf.writeLong(((java.util.Date) value).getTime());
        } else if (value instanceof UUID) {
            UUID uuid = (UUID) value;
            buf.writeByte(T_UUID);
            buf.writeLong(uuid.getMostSignificantBits());
            buf.writeLong(uuid.getLeastSignificantBits());
        } else {
            // Graceful degradation: an exotic JDBC type must not break the response.
            buf.writeByte(T_STRING);
            writeString(buf, String.valueOf(value));
        }
    }

    public static Object readValue(ByteBuf buf) {
        byte tag = buf.readByte();
        switch (tag) {
            case T_NULL:
                return null;
            case T_STRING:
                return readString(buf);
            case T_INT:
                return buf.readInt();
            case T_LONG:
                return buf.readLong();
            case T_DOUBLE:
                return buf.readDouble();
            case T_BOOLEAN:
                return buf.readBoolean();
            case T_FLOAT:
                return buf.readFloat();
            case T_SHORT:
                return buf.readShort();
            case T_BYTE:
                return buf.readByte();
            case T_BIG_DECIMAL:
                return new BigDecimal(String.valueOf(readString(buf)));
            case T_BYTES: {
                int length = buf.readInt();
                if (length < 0 || length > MAX_BLOB_BYTES) {
                    throw new IllegalArgumentException("Illegal binary length on the wire: " + length);
                }
                byte[] bytes = new byte[length];
                buf.readBytes(bytes);
                return bytes;
            }
            case T_TIMESTAMP:
                return new Timestamp(buf.readLong());
            case T_DATE:
                return new java.sql.Date(buf.readLong());
            case T_TIME:
                return new java.sql.Time(buf.readLong());
            case T_UUID:
                return new UUID(buf.readLong(), buf.readLong());
            default:
                throw new IllegalArgumentException("Unknown SQL value tag on the wire: " + tag);
        }
    }

    /**
     * Human readable type name, used by diagnostics and by {@link SqlColumn}.
     */
    public static String typeNameOf(Object value) {
        return value == null ? "NULL" : value.getClass().getSimpleName();
    }
}
