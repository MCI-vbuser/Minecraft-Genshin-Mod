package com.vbuser.database.sql;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Low level H2 facade.
 *
 * <p>This class never throws to the caller: every failure is reported through
 * {@link SqlExecutionResult#error(String)}. Mod authors should normally talk to
 * {@link SqlApi} instead, which adds side handling, timeouts and permissions.</p>
 */
@SuppressWarnings("unused")
public class DatabaseHelper {

    private static final Logger LOGGER = LogManager.getLogger("H2DataMod");
    private static final Object LIFECYCLE_LOCK = new Object();

    private static volatile String currentDbUrl = null;
    private static volatile boolean readOnly = false;
    private static volatile int queryTimeoutSeconds = 30;

    /** Work unit executed inside a transaction; see {@link #inTransaction(TransactionWork)}. */
    public interface TransactionWork {
        void work(SqlSession session) throws Exception;
    }

    /** Statement executor bound to one transaction. */
    public interface SqlSession {
        SqlExecutionResult execute(String sql, List<Object> params);
    }

    // ------------------------------------------------------------- lifecycle

    public static void initialize(String dbUrl) {
        if (dbUrl == null || dbUrl.trim().isEmpty()) {
            LOGGER.error("Cannot initialize the database with an empty JDBC URL");
            return;
        }
        synchronized (LIFECYCLE_LOCK) {
            currentDbUrl = dbUrl;
            try (Connection ignored = DriverManager.getConnection(dbUrl, "sa", "")) {
                LOGGER.info("Connected to H2 database at: {}", dbUrl);
            } catch (SQLException e) {
                LOGGER.error("Failed to initialize H2 database", e);
            }
        }
    }

    public static void initialize(File databaseFile) {
        if (databaseFile == null) {
            LOGGER.error("Cannot initialize the database with a null file");
            return;
        }
        File parent = databaseFile.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        initialize("jdbc:h2:" + databaseFile.getAbsolutePath() + ";DB_CLOSE_DELAY=-1");
    }

    public static boolean isInitialized() {
        return currentDbUrl != null;
    }

    public static String getJdbcUrl() {
        return currentDbUrl;
    }

    public static void shutdown() {
        String url = currentDbUrl;
        if (url == null) {
            return;
        }
        synchronized (LIFECYCLE_LOCK) {
            try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("SHUTDOWN");
                }
                LOGGER.info("H2 database shutdown successfully.");
            } catch (SQLException e) {
                LOGGER.error("Failed to shutdown H2 database", e);
            } finally {
                currentDbUrl = null;
            }
        }
    }

    // ------------------------------------------------------------- options

    /** Guard rail, not a security boundary: rejects statements that are not SELECT/WITH. */
    public static void setReadOnly(boolean value) {
        readOnly = value;
    }

    public static boolean isReadOnly() {
        return readOnly;
    }

    /** JDBC query timeout in seconds; 0 disables it. */
    public static void setQueryTimeoutSeconds(int seconds) {
        queryTimeoutSeconds = Math.max(0, seconds);
    }

    public static int getQueryTimeoutSeconds() {
        return queryTimeoutSeconds;
    }

    // ------------------------------------------------------------- statements

    /**
     * Executes one statement. {@code params} may be {@code null} (treated as empty),
     * and may be shorter than the placeholder count.
     */
    public static SqlExecutionResult executeSql(String sql, List<Object> params) {
        if (!isInitialized()) {
            return SqlExecutionResult.error("Database is not initialized. Call SqlApi.initialize() "
                    + "(or SqlApi.initialize(File)) first.");
        }
        if (sql == null || sql.trim().isEmpty()) {
            return SqlExecutionResult.error("SQL statement must not be empty.");
        }
        if (readOnly && isReadStatement(sql)) {
            return SqlExecutionResult.error("Database is read only: only SELECT/WITH statements are allowed.");
        }
        try (Connection connection = openConnection()) {
            return executeOn(connection, sql, params);
        } catch (Exception e) {
            LOGGER.warn("SQL failed: {}", sql, e);
            return SqlExecutionResult.error(describe(e));
        }
    }

    /** Executes an INSERT and returns the generated key, or {@code -1} when none was produced. */
    public static long executeInsert(String sql, List<Object> params) {
        if (!isInitialized()) {
            return -1L;
        }
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(statement, params);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys != null && keys.next()) {
                    Object key = keys.getObject(1);
                    return key instanceof Number ? ((Number) key).longValue() : -1L;
                }
            }
            return -1L;
        } catch (Exception e) {
            LOGGER.warn("SQL insert failed: {}", sql, e);
            return -1L;
        }
    }

    /** Executes the same statement once per parameter set and returns the per-set update counts. */
    public static int[] executeBatch(String sql, List<List<Object>> parameterSets) {
        if (!isInitialized() || parameterSets == null || parameterSets.isEmpty()) {
            return new int[0];
        }
        if (readOnly) {
            return new int[0];
        }
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                for (List<Object> params : parameterSets) {
                    bind(statement, params);
                    statement.addBatch();
                }
                int[] counts = statement.executeBatch();
                connection.commit();
                return counts;
            } catch (Exception e) {
                connection.rollback();
                throw e;
            }
        } catch (Exception e) {
            LOGGER.warn("SQL batch failed: {}", sql, e);
            return new int[0];
        }
    }

    /**
     * Runs several statements in one transaction. The returned result reports how many
     * statements ran; on failure everything is rolled back and the error is returned.
     */
    public static SqlExecutionResult inTransaction(TransactionWork work) {
        if (!isInitialized()) {
            return SqlExecutionResult.error("Database is not initialized.");
        }
        if (work == null) {
            return SqlExecutionResult.error("Transaction work must not be null.");
        }
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            final int[] counter = {0};
            SqlSession session = (sql, params) -> {
                counter[0]++;
                try {
                    return executeOn(connection, sql, params);
                } catch (Exception e) {
                    throw new SqlRuntimeException(describe(e), e);
                }
            };
            try {
                work.work(session);
                connection.commit();
                return SqlExecutionResult.update(counter[0]);
            } catch (Exception e) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackError) {
                    LOGGER.warn("Rollback failed", rollbackError);
                }
                LOGGER.warn("SQL transaction rolled back", e);
                return SqlExecutionResult.error(describe(e));
            }
        } catch (Exception e) {
            LOGGER.warn("SQL transaction failed", e);
            return SqlExecutionResult.error(describe(e));
        }
    }

    // ------------------------------------------------------------- internals

    private static Connection openConnection() throws SQLException {
        String url = currentDbUrl;
        if (url == null) {
            throw new SQLException("Database is not initialized");
        }
        return DriverManager.getConnection(url, "sa", "");
    }

    private static SqlExecutionResult executeOn(Connection connection, String sql, List<Object> params) throws SQLException {
        List<Object> bound = params == null ? Collections.emptyList() : params;
        if (readOnly && isReadStatement(sql)) {
            return SqlExecutionResult.error("Database is read only: only SELECT/WITH statements are allowed.");
        }
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            if (queryTimeoutSeconds > 0) {
                try {
                    statement.setQueryTimeout(queryTimeoutSeconds);
                } catch (Throwable ignored) {
                    // Driver does not support query timeouts.
                }
            }
            bind(statement, bound);
            // execute() is used instead of executeUpdate()/executeQuery() so SELECT,
            // VALUES, SHOW, CALL and DDL all work without a textual heuristic.
            boolean hasResultSet = statement.execute();
            if (hasResultSet) {
                try (ResultSet resultSet = statement.getResultSet()) {
                    return readResultSet(resultSet);
                }
            }
            return SqlExecutionResult.update(Math.max(statement.getUpdateCount(), 0));
        }
    }

    private static void bind(PreparedStatement statement, List<Object> params) throws SQLException {
        List<Object> bound = params == null ? Collections.emptyList() : params;
        for (int i = 0; i < bound.size(); i++) {
            Object value = bound.get(i);
            if (value instanceof java.util.Date && !(value instanceof java.sql.Date)
                    && !(value instanceof java.sql.Time) && !(value instanceof Timestamp)) {
                value = new Timestamp(((java.util.Date) value).getTime());
            }
            statement.setObject(i + 1, value);
        }
    }

    private static SqlExecutionResult readResultSet(ResultSet resultSet) throws SQLException {
        ResultSetMetaData meta = resultSet.getMetaData();
        int columnCount = meta.getColumnCount();
        List<SqlColumn> columns = new ArrayList<>(columnCount);
        for (int i = 1; i <= columnCount; i++) {
            columns.add(new SqlColumn(meta.getColumnLabel(i), meta.getColumnTypeName(i), meta.getColumnType(i)));
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        while (resultSet.next()) {
            Map<String, Object> row = new LinkedHashMap<>(Math.max(4, columnCount * 2));
            for (int i = 1; i <= columnCount; i++) {
                row.put(columns.get(i - 1).getName(), wireValue(resultSet.getObject(i)));
            }
            rows.add(row);
        }
        return SqlExecutionResult.query(columns, rows);
    }

    /**
     * Clips values that cannot travel over the wire so a single oversized column
     * cannot make the whole response unencodable.
     */
    private static Object wireValue(Object value) {
        if (value instanceof String) {
            String text = (String) value;
            if (text.length() > 8 && text.getBytes(StandardCharsets.UTF_8).length > SqlValueCodec.MAX_TEXT_BYTES) {
                return truncateUtf8(text)
                        + "...<truncated " + text.length() + " chars>";
            }
            return text;
        }
        if (value instanceof byte[]) {
            byte[] bytes = (byte[]) value;
            if (bytes.length > SqlValueCodec.MAX_BLOB_BYTES) {
                return "<binary " + bytes.length + " bytes: exceeds the wire limit of "
                        + SqlValueCodec.MAX_BLOB_BYTES + ">";
            }
            return value;
        }
        return value;
    }

    private static String truncateUtf8(String text) {
        StringBuilder builder = new StringBuilder(Math.min(text.length(), 30688));
        int bytes = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int size = c < 0x80 ? 1 : (c < 0x800 ? 2 : 3);
            if (bytes + size > 30688) {
                break;
            }
            builder.append(c);
            bytes += size;
        }
        return builder.toString();
    }

    static boolean isReadStatement(String sql) {
        if (sql == null) {
            return true;
        }
        String trimmed = sql.trim().toUpperCase();
        return !trimmed.startsWith("SELECT") && !trimmed.startsWith("WITH")
                && !trimmed.startsWith("SHOW") && !trimmed.startsWith("EXPLAIN");
    }

    private static String describe(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isEmpty()) {
            message = e.getClass().getSimpleName();
        }
        return message;
    }

    /** Wraps a failure inside a transaction session so the outer block can roll back. */
    public static final class SqlRuntimeException extends RuntimeException {
        public SqlRuntimeException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
