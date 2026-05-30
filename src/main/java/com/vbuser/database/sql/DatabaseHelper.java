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
        try (Connection conn = DriverManager.getConnection(dbUrl, "sa", "")) {
            LOGGER.info("Connected to H2 database at: {}", dbUrl);
            createGameruleTableIfNotExists(conn);
            createInventoryTableIfNotExists(conn);
            LOGGER.info("Database tables ready.");
        } catch (SQLException e) {
            LOGGER.error("Failed to initialize H2 database", e);
        }
    }

    private static void createGameruleTableIfNotExists(Connection conn) throws SQLException {
        DatabaseMetaData meta = conn.getMetaData();
        try (ResultSet rs = meta.getTables(null, null, "GAMERULE", null)) {
            if (rs.next()) {
                LOGGER.info("Table 'gamerule' already exists.");
                return;
            }
        }
        String sql = "CREATE TABLE gamerule (" +
                "rule VARCHAR(255) PRIMARY KEY, " +
                "value VARCHAR(255) NOT NULL, " +
                "type VARCHAR(50) NOT NULL" +
                ")";
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
            LOGGER.info("Created table 'gamerule'.");
        }
    }

    private static void createInventoryTableIfNotExists(Connection conn) throws SQLException {
        DatabaseMetaData meta = conn.getMetaData();
        try (ResultSet rs = meta.getTables(null, null, "INVENTORY", null)) {
            if (rs.next()) {
                LOGGER.info("Table 'inventory' already exists.");
                return;
            }
        }
        String sql = "CREATE TABLE inventory (" +
                "item VARCHAR(255) PRIMARY KEY, " +
                "count INT NOT NULL" +
                ")";
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
            LOGGER.info("Created table 'inventory'.");
        }
    }

    public static SqlExecutionResult executeSql(String sql, List<Object> params) {
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

}