package com.vbuser.database.sql;

/**
 * One column of a {@link SqlExecutionResult} query result.
 *
 * <p>The column order is preserved from the JDBC metadata, so a client can render
 * a result table without falling back to hash map ordering.</p>
 */
public final class SqlColumn {

    private final String name;
    private final String typeName;
    private final int sqlType;

    public SqlColumn(String name, String typeName, int sqlType) {
        this.name = name == null ? "" : name;
        this.typeName = typeName == null ? "" : typeName;
        this.sqlType = sqlType;
    }

    /** Column label as returned by the driver (the {@code AS} alias when present). */
    public String getName() {
        return name;
    }

    /** Vendor type name, e.g. {@code VARCHAR} or {@code TIMESTAMP}. */
    public String getTypeName() {
        return typeName;
    }

    /** {@link java.sql.Types} constant. */
    public int getSqlType() {
        return sqlType;
    }

    @Override
    public String toString() {
        return name + " " + typeName;
    }
}
