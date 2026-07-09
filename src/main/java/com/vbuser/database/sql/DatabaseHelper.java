package com.vbuser.database.sql;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.*;
import java.util.*;

public class DatabaseHelper {
    private static final Logger LOGGER = LogManager.getLogger("H2DataMod");
    private static String currentDbUrl = null;

    public static void initialize(String dbUrl) {
        currentDbUrl = dbUrl;
        try (Connection ignored = DriverManager.getConnection(dbUrl, "sa", "")) {
            LOGGER.info("Connected to H2 database at: {}", dbUrl);
            LOGGER.info("Database tables ready.");
        } catch (SQLException e) {
            LOGGER.error("Failed to initialize H2 database", e);
        }
    }

    public static SqlExecutionResult executeSql(String sql, List<Object> params) {
        if (params == null) params = Collections.emptyList();
            // LLM may assume it is null-safe when only providing SqlApi.java, so we add this line.
        if (currentDbUrl == null) {
            return SqlExecutionResult.error("Database not initialized. Call initialize() first.");
        }
        try (Connection conn = DriverManager.getConnection(currentDbUrl, "sa", "")) {
            String trimmed = sql.trim().toUpperCase();
            boolean isSelect = trimmed.startsWith("SELECT") || trimmed.startsWith("WITH");

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (int i = 0; i < params.size(); i++) {
                    ps.setObject(i + 1, params.get(i));
                }

                if (isSelect) {
                    try (ResultSet rs = ps.executeQuery()) {
                        ResultSetMetaData meta = rs.getMetaData();
                        int colCount = meta.getColumnCount();
                        List<Map<String, Object>> rows = new ArrayList<>();
                        while (rs.next()) {
                            Map<String, Object> row = new LinkedHashMap<>();
                            for (int i = 1; i <= colCount; i++) {
                                row.put(meta.getColumnLabel(i), rs.getObject(i));
                            }
                            rows.add(row);
                        }
                        return SqlExecutionResult.query(rows);
                    }
                } else {
                    int affected = ps.executeUpdate();
                    return SqlExecutionResult.update(affected);
                }
            }
        } catch (SQLException e) {
            return SqlExecutionResult.error(e.getMessage());
        }
    }

    public static void shutdown() {
        if (currentDbUrl == null) return;
        try (Connection conn = DriverManager.getConnection(currentDbUrl, "sa", "")) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("SHUTDOWN");
            }
            LOGGER.info("H2 database shutdown successfully.");
        } catch (SQLException e) {
            LOGGER.error("Failed to shutdown H2 database", e);
        }
    }

}