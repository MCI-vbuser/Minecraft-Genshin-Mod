package com.vbuser.database.sql;

import io.netty.buffer.ByteBuf;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable-ish result of one SQL call.
 *
 * <p>Three mutually exclusive shapes exist:</p>
 * <ul>
 *   <li>query result: {@code isSuccess() && isQuery()}, rows are ordered maps whose
 *       key order is {@link #getColumns()};</li>
 *   <li>update result: {@code isSuccess() && !isQuery()}, see {@link #getUpdateCount()};</li>
 *   <li>failure: {@code !isSuccess()}, see {@link #getErrorMessage()}.</li>
 * </ul>
 */
@SuppressWarnings("unused")
public class SqlExecutionResult {

    private static final int MAX_ROWS_ON_THE_WIRE = 1 << 20;

    private boolean success;
    private String errorMessage;
    private int updateCount;
    private List<SqlColumn> columns;
    private List<Map<String, Object>> rows;

    /** Required by the packet decoder. */
    public SqlExecutionResult() {
    }

    public static SqlExecutionResult update(int count) {
        SqlExecutionResult result = new SqlExecutionResult();
        result.success = true;
        result.updateCount = count;
        result.rows = null;
        result.columns = Collections.emptyList();
        return result;
    }

    public static SqlExecutionResult query(List<SqlColumn> columns, List<Map<String, Object>> rows) {
        SqlExecutionResult result = new SqlExecutionResult();
        result.success = true;
        result.updateCount = -1;
        result.columns = columns == null ? Collections.emptyList() : new ArrayList<>(columns);
        result.rows = rows == null ? new ArrayList<>() : new ArrayList<>(rows);
        return result;
    }

    /** Backwards compatible factory without column metadata. */
    public static SqlExecutionResult query(List<Map<String, Object>> rows) {
        return query(Collections.emptyList(), rows);
    }

    public static SqlExecutionResult error(String message) {
        SqlExecutionResult result = new SqlExecutionResult();
        result.success = false;
        result.errorMessage = message == null ? "Unknown SQL error" : message;
        result.updateCount = -1;
        return result;
    }

    // ------------------------------------------------------------------ codec

    public void encode(ByteBuf buf) {
        buf.writeBoolean(success);
        if (!success) {
            SqlValueCodec.writeString(buf, errorMessage);
            return;
        }
        boolean isQuery = rows != null;
        buf.writeBoolean(isQuery);
        if (!isQuery) {
            buf.writeInt(updateCount);
            return;
        }
        List<SqlColumn> cols = columns == null ? Collections.emptyList() : columns;
        buf.writeInt(cols.size());
        for (SqlColumn column : cols) {
            SqlValueCodec.writeString(buf, column.getName());
            SqlValueCodec.writeString(buf, column.getTypeName());
            buf.writeInt(column.getSqlType());
        }
        buf.writeInt(rows.size());
        for (Map<String, Object> row : rows) {
            writeRow(buf, row);
        }
    }

    public void decode(ByteBuf buf) {
        success = buf.readBoolean();
        if (!success) {
            errorMessage = SqlValueCodec.readString(buf);
            updateCount = -1;
            return;
        }
        boolean isQuery = buf.readBoolean();
        if (!isQuery) {
            updateCount = buf.readInt();
            rows = null;
            columns = Collections.emptyList();
            return;
        }
        int columnCount = buf.readInt();
        if (columnCount < 0 || columnCount > 4096) {
            throw new IllegalArgumentException("Illegal column count: " + columnCount);
        }
        List<SqlColumn> cols = new ArrayList<>(columnCount);
        for (int i = 0; i < columnCount; i++) {
            String name = SqlValueCodec.readString(buf);
            String typeName = SqlValueCodec.readString(buf);
            int sqlType = buf.readInt();
            cols.add(new SqlColumn(name, typeName, sqlType));
        }
        columns = cols;
        int rowCount = buf.readInt();
        if (rowCount < 0 || rowCount > MAX_ROWS_ON_THE_WIRE) {
            throw new IllegalArgumentException("Illegal row count: " + rowCount);
        }
        rows = new ArrayList<>(rowCount);
        for (int i = 0; i < rowCount; i++) {
            rows.add(readRow(buf));
        }
    }

    private void writeRow(ByteBuf buf, Map<String, Object> row) {
        buf.writeInt(row.size());
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            SqlValueCodec.writeString(buf, entry.getKey());
            SqlValueCodec.writeValue(buf, entry.getValue());
        }
    }

    private Map<String, Object> readRow(ByteBuf buf) {
        int columnCount = buf.readInt();
        if (columnCount < 0 || columnCount > 4096) {
            throw new IllegalArgumentException("Illegal row width: " + columnCount);
        }
        // LinkedHashMap: keep the column order the server produced.
        Map<String, Object> row = new LinkedHashMap<>(Math.max(4, columnCount * 2));
        for (int i = 0; i < columnCount; i++) {
            String key = SqlValueCodec.readString(buf);
            row.put(key, SqlValueCodec.readValue(buf));
        }
        return row;
    }

    // ------------------------------------------------------------------ accessors

    public boolean isSuccess() {
        return success;
    }

    public boolean isQuery() {
        return success && rows != null;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public int getUpdateCount() {
        return updateCount;
    }

    /** Never {@code null}; empty for update results. */
    public List<SqlColumn> getColumns() {
        return columns == null ? Collections.emptyList() : Collections.unmodifiableList(columns);
    }

    /** {@code null} for update results, possibly empty for queries without hits. */
    public List<Map<String, Object>> getRows() {
        return rows;
    }

    /** Convenience for {@code SELECT ... LIMIT 1} style calls. */
    public Map<String, Object> getFirstRow() {
        return rows == null || rows.isEmpty() ? null : rows.get(0);
    }

    /** Convenience for single-value queries, e.g. {@code SELECT COUNT(*)}. */
    public Object getFirstValue() {
        Map<String, Object> row = getFirstRow();
        if (row == null || row.isEmpty()) {
            return null;
        }
        return row.values().iterator().next();
    }
}
