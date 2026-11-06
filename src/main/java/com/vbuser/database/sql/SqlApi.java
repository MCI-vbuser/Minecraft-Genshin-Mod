package com.vbuser.database.sql;

import com.vbuser.database.network.SqlExecutePacket;
import com.vbuser.genshin.tool.network.NetworkManager;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;
import net.minecraftforge.fml.relauncher.Side;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * The only SQL entry point a mod author needs.
 *
 * <h2>Side model</h2>
 * <p>The API never guesses the logical side from the calling thread
 * ({@code FMLCommonHandler.getEffectiveSide()} reports CLIENT for every thread a mod
 * creates, which used to route server work into client-only code). Instead:</p>
 * <ul>
 *   <li>if a local server exists ({@code getMinecraftServerInstance() != null}), which is
 *       the case on a dedicated server and in single player, SQL runs locally and
 *       directly against the world database;</li>
 *   <li>otherwise the call is sent to the server as a request and the callback runs on
 *       the client thread once the answer arrives (or when the request times out).</li>
 * </ul>
 *
 * <h2>Permissions</h2>
 * <p>Raw SQL coming from a client requires {@link #setRawSqlAuthorizer(Authorizer)} to
 * accept it (default: permission level 4). Anything a normal player is supposed to run
 * must be registered with {@link #registerQuery(String, String)}; a client can then only
 * ask for a name, never for arbitrary SQL.</p>
 *
 * <h2>Timeouts and cleanup</h2>
 * <p>Remote requests time out after {@link #getRequestTimeoutMs()} and pending callbacks
 * are canceled on disconnect, so a dropped packet can no longer leak callbacks forever.</p>
 */

@SuppressWarnings("unused")
public final class SqlApi {

    /** Maps one result row to a domain object. */
    public interface RowMapper<T> {
        T map(Map<String, Object> row) throws Exception;
    }

    /** Decides whether a client may send raw SQL. */
    public interface Authorizer {
        boolean canExecute(EntityPlayerMP player, String sql);
    }

    /** Thrown by {@link #query(String, List, RowMapper)} when the statement failed. */
    public static final class SqlException extends RuntimeException {
        public SqlException(String message) {
            super(message);
        }

        public SqlException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final Logger LOGGER = LogManager.getLogger("Database-SqlApi");
    private static final AtomicLong REQUEST_IDS = new AtomicLong();
    private static final AtomicInteger THREAD_IDS = new AtomicInteger();
    private static final ConcurrentHashMap<Long, Consumer<SqlExecutionResult>> PENDING = new ConcurrentHashMap<>();
    private static final Map<String, String> REGISTERED_QUERIES = new ConcurrentHashMap<>();
    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    private static final ScheduledExecutorService TIMEOUTS =
            Executors.newSingleThreadScheduledExecutor(daemonFactory("SqlApi-timeout"));
    private static final ExecutorService IO =
            Executors.newFixedThreadPool(2, daemonFactory("SqlApi-io"));

    private static volatile long requestTimeoutMs = 30_000L;
    private static volatile Authorizer rawSqlAuthorizer =
            (player, sql) -> player != null && player.canUseCommand(4, "sql");

    private SqlApi() {
    }

    // ------------------------------------------------------------- lifecycle

    /** Initializes the embedded database. Safe to call more than once. */
    public static void initialize(String jdbcUrl) {
        DatabaseHelper.initialize(jdbcUrl);
    }

    /**
     * Initializes the embedded database for a file, e.g.
     * {@code SqlApi.initialize(new File(worldDir, "database"))}.
     */
    public static void initialize(File databaseFile) {
        DatabaseHelper.initialize(databaseFile);
    }

    /** Closes the database and cancels pending requests. */
    public static void shutdown() {
        cancelAll("database shutdown");
        DatabaseHelper.shutdown();
    }

    /** True when a database is attached to this JVM (server side / single player). */
    public static boolean isReady() {
        return DatabaseHelper.isInitialized();
    }

    /**
     * True when a SQL call can be attempted at all: either a local server exists, or the
     * client is connected to one. This cannot detect whether the peer actually has the mod.
     */
    public static boolean isAvailable() {
        if (hasLocalServer()) {
            return true;
        }
        return isPhysicalClient() && SqlClientBridge.isConnected();
    }

    /** True when calls execute locally instead of over the network. */
    public static boolean isLocalExecution() {
        return hasLocalServer();
    }

    // ------------------------------------------------------------- execution

    /**
     * Blocking execution for server-side code. Throws when there is no local server
     * (a client cannot block on a network round trip; use the callback form there).
     */
    public static SqlExecutionResult execute(String sql, List<Object> params) {
        requireLocalServer("execute(String, List)");
        return DatabaseHelper.executeSql(sql, params);
    }

    /** Convenience overload without parameters. */
    public static SqlExecutionResult execute(String sql) {
        return execute(sql, Collections.emptyList());
    }

    /**
     * @deprecated use {@link #execute(String, List, Consumer)}; kept so existing call
     * sites keep compiling.
     */
    @Deprecated
    public static void executeSql(String sql, List<Object> params, Consumer<SqlExecutionResult> callback) {
        execute(sql, params, callback);
    }

    /**
     * Executes a statement and reports the result through {@code callback}.
     *
     * <p>The callback always runs exactly once: with the result, with an error result, or
     * with a timeout error. On the server it runs on the calling thread; on a client it
     * runs on the client thread. {@code params} may be {@code null}.</p>
     */
    public static void execute(String sql, List<Object> params, Consumer<SqlExecutionResult> callback) {
        Consumer<SqlExecutionResult> safeCallback = callback == null ? result -> { } : callback;
        if (sql == null || sql.trim().isEmpty()) {
            safeCallback.accept(SqlExecutionResult.error("SQL statement must not be empty."));
            return;
        }
        if (hasLocalServer()) {
            deliver(safeCallback, DatabaseHelper.executeSql(sql, params));
            return;
        }
        if (!isPhysicalClient() || !SqlClientBridge.isConnected()) {
            safeCallback.accept(SqlExecutionResult.error(
                    "SqlApi is unavailable: no local server and no connection to a server."));
            return;
        }
        String parameterProblem = validateParameters(params);
        if (parameterProblem != null) {
            safeCallback.accept(SqlExecutionResult.error(parameterProblem));
            return;
        }
        sendRaw(sql, params, safeCallback);
    }

    /** Async variant; the returned future always completes with a result (never hangs). */
    public static CompletableFuture<SqlExecutionResult> executeAsync(String sql, List<Object> params) {
        CompletableFuture<SqlExecutionResult> future = new CompletableFuture<>();
        if (hasLocalServer()) {
            IO.execute(() -> {
                try {
                    future.complete(DatabaseHelper.executeSql(sql, params));
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            });
        } else {
            execute(sql, params, future::complete);
        }
        return future;
    }

    /**
     * Runs a query and maps every row. The future fails with {@link SqlException} when
     * the statement failed, and with a {@link CompletionException} when the mapper threw.
     */
    public static <T> CompletableFuture<List<T>> query(String sql, List<Object> params, RowMapper<T> mapper) {
        if (mapper == null) {
            throw new IllegalArgumentException("RowMapper must not be null");
        }
        return executeAsync(sql, params).thenApply(result -> {
            if (!result.isSuccess()) {
                throw new SqlException(result.getErrorMessage());
            }
            List<Map<String, Object>> rows = result.getRows();
            List<T> mapped = new ArrayList<>(rows == null ? 0 : rows.size());
            if (rows != null) {
                for (Map<String, Object> row : rows) {
                    try {
                        mapped.add(mapper.map(row));
                    } catch (Exception e) {
                        throw new CompletionException(e);
                    }
                }
            }
            return mapped;
        });
    }

    /** Executes an INSERT and returns the generated key, or {@code -1}. Server side only. */
    public static long executeInsert(String sql, List<Object> params) {
        requireLocalServer("executeInsert");
        return DatabaseHelper.executeInsert(sql, params);
    }

    /** Executes one statement per parameter set. Server side only. */
    public static int[] executeBatch(String sql, List<List<Object>> parameterSets) {
        requireLocalServer("executeBatch");
        return DatabaseHelper.executeBatch(sql, parameterSets);
    }

    /** Runs several statements atomically. Server side only. */
    public static SqlExecutionResult inTransaction(DatabaseHelper.TransactionWork work) {
        requireLocalServer("inTransaction");
        return DatabaseHelper.inTransaction(work);
    }

    // ------------------------------------------------------------- named queries

    /**
     * Registers a server-defined statement under a name. Clients may then call
     * {@link #executeRegistered(String, List, Consumer)} without raw SQL permission.
     * Registering the same name twice replaces the previous statement.
     */
    public static void registerQuery(String name, String sql) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Registered query name must not be empty");
        }
        if (sql == null || sql.trim().isEmpty()) {
            throw new IllegalArgumentException("Registered query SQL must not be empty");
        }
        REGISTERED_QUERIES.put(name, sql);
    }

    public static void unregisterQuery(String name) {
        if (name != null) {
            REGISTERED_QUERIES.remove(name);
        }
    }

    public static Set<String> getRegisteredQueries() {
        return Collections.unmodifiableSet(REGISTERED_QUERIES.keySet());
    }

    /** Runs a registered query from either side. */
    public static void executeRegistered(String name, List<Object> params, Consumer<SqlExecutionResult> callback) {
        Consumer<SqlExecutionResult> safeCallback = callback == null ? result -> { } : callback;
        if (name == null || name.trim().isEmpty()) {
            safeCallback.accept(SqlExecutionResult.error("Registered query name must not be empty."));
            return;
        }
        if (hasLocalServer()) {
            deliver(safeCallback, runRegisteredQuery(name, params));
            return;
        }
        if (!isPhysicalClient() || !SqlClientBridge.isConnected()) {
            safeCallback.accept(SqlExecutionResult.error(
                    "SqlApi is unavailable: no local server and no connection to a server."));
            return;
        }
        String parameterProblem = validateParameters(params);
        if (parameterProblem != null) {
            safeCallback.accept(SqlExecutionResult.error(parameterProblem));
            return;
        }
        long requestId = REQUEST_IDS.incrementAndGet();
        try {
            register(requestId, safeCallback, name);
            NetworkManager.sendToServer(new SqlExecutePacket(name, true, params, requestId));
        } catch (RuntimeException e) {
            cancelRequest(requestId, "could not send the request: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------- security

    /**
     * Controls which clients may send raw SQL. The default only allows permission
     * level 4. Pass {@code (player, sql) -> true} to opt out (not recommended), or
     * {@code (player, sql) -> false} to disable raw SQL entirely.
     */
    public static void setRawSqlAuthorizer(Authorizer authorizer) {
        rawSqlAuthorizer = authorizer == null ? (player, sql) -> false : authorizer;
    }

    public static boolean authorizeRawSql(EntityPlayerMP player, String sql) {
        Authorizer authorizer = rawSqlAuthorizer;
        try {
            return authorizer != null && authorizer.canExecute(player, sql);
        } catch (RuntimeException e) {
            LOGGER.warn("SQL authorizer threw, denying the request", e);
            return false;
        }
    }

    /** Guard rail, not a security boundary: only SELECT/WITH/SHOW/EXPLAIN are allowed. */
    public static void setReadOnly(boolean readOnly) {
        DatabaseHelper.setReadOnly(readOnly);
    }

    // ------------------------------------------------------------- options

    public static long getRequestTimeoutMs() {
        return requestTimeoutMs;
    }

    /** How long a remote request may stay unanswered before its callback gets an error. */
    public static void setRequestTimeoutMs(long timeoutMs) {
        requestTimeoutMs = Math.max(1_000L, timeoutMs);
    }

    // ------------------------------------------------------------- listeners

    /** Progress events for observers such as a debug HUD. */
    public interface Listener {
        default void onRequestSent(long requestId, String sqlOrQueryName) {
        }

        default void onRequestFailed(long requestId, String error) {
        }
    }

    public static void addListener(Listener listener) {
        if (listener != null) {
            LISTENERS.addIfAbsent(listener);
        }
    }

    public static void removeListener(Listener listener) {
        LISTENERS.remove(listener);
    }

    // ------------------------------------------------------------- internal

    /**
     * Internal: called by the result packet on the client. Do not call from mod code.
     */
    public static void completeRequest(long requestId, SqlExecutionResult result) {
        Consumer<SqlExecutionResult> callback = PENDING.remove(requestId);
        if (callback == null) {
            LOGGER.warn("Received a SQL result for an unknown or expired request {}", requestId);
            return;
        }
        deliver(callback, result);
    }

    /** Cancels every pending request, e.g. on disconnect. */
    public static void cancelAll(String reason) {
        for (Long requestId : new ArrayList<>(PENDING.keySet())) {
            cancelRequest(requestId, reason);
        }
    }

    /**
     * Internal: resolves and runs a registered query on the server. Do not call from
     * client code; use {@link #executeRegistered(String, List, Consumer)} instead.
     */
    public static SqlExecutionResult runRegisteredQuery(String name, List<Object> params) {
        String sql = name == null ? null : REGISTERED_QUERIES.get(name);
        if (sql == null) {
            return SqlExecutionResult.error("Unknown registered query: " + name);
        }
        return DatabaseHelper.executeSql(sql, params);
    }

    /** Disconnect hook; registered by the database mod during init. */
    public static final class Lifecycle {
        @SubscribeEvent
        public void onClientDisconnected(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
            cancelAll("connection closed");
        }
    }

    // ------------------------------------------------------------- plumbing

    private static void sendRaw(String sql, List<Object> params, Consumer<SqlExecutionResult> callback) {
        long requestId = REQUEST_IDS.incrementAndGet();
        try {
            register(requestId, callback, sql);
            NetworkManager.sendToServer(new SqlExecutePacket(sql, false, params, requestId));
        } catch (RuntimeException e) {
            cancelRequest(requestId, "could not send the request: " + e.getMessage());
        }
    }

    private static void register(long requestId, Consumer<SqlExecutionResult> callback, String label) {
        PENDING.put(requestId, callback);
        TIMEOUTS.schedule(() -> expire(requestId), requestTimeoutMs, TimeUnit.MILLISECONDS);
        for (Listener listener : LISTENERS) {
            try {
                listener.onRequestSent(requestId, label);
            } catch (RuntimeException e) {
                LOGGER.warn("SQL listener threw", e);
            }
        }
    }

    private static void expire(long requestId) {
        cancelRequest(requestId, "request timed out after " + requestTimeoutMs + " ms");
    }

    private static void cancelRequest(long requestId, String reason) {
        Consumer<SqlExecutionResult> callback = PENDING.remove(requestId);
        if (callback == null) {
            return;
        }
        for (Listener listener : LISTENERS) {
            try {
                listener.onRequestFailed(requestId, reason);
            } catch (RuntimeException e) {
                LOGGER.warn("SQL listener threw", e);
            }
        }
        deliver(callback, SqlExecutionResult.error("SQL request cancelled: " + reason));
    }

    private static void deliver(Consumer<SqlExecutionResult> callback, SqlExecutionResult result) {
        try {
            callback.accept(result == null ? SqlExecutionResult.error("No result") : result);
        } catch (RuntimeException e) {
            LOGGER.error("SQL callback threw", e);
        }
    }

    /** @return {@code null} when the parameters are fine, otherwise a human readable reason. */
    private static String validateParameters(Collection<?> params) {
        if (params == null) {
            return null;
        }
        if (params.size() > 4096) {
            return "Too many SQL parameters: " + params.size() + " (max 4096).";
        }
        for (Object param : params) {
            if (param == null) {
                continue;
            }
            if (param instanceof String) {
                int bytes = ((String) param).getBytes(StandardCharsets.UTF_8).length;
                if (bytes > SqlValueCodec.MAX_TEXT_BYTES) {
                    return "SQL parameter text is " + bytes + " bytes, the limit is "
                            + SqlValueCodec.MAX_TEXT_BYTES + ".";
                }
            } else if (param instanceof byte[]) {
                int bytes = ((byte[]) param).length;
                if (bytes > SqlValueCodec.MAX_BLOB_BYTES) {
                    return "SQL parameter binary is " + bytes + " bytes, the limit is "
                            + SqlValueCodec.MAX_BLOB_BYTES + ".";
                }
            } else if (!isSupportedParameterType(param)) {
                return "Unsupported SQL parameter type: " + param.getClass().getName()
                        + ". Supported: String, Integer, Long, Double, Float, Short, Byte, Boolean,"
                        + " BigDecimal, byte[], java.util.Date (incl. sql Date/Time/Timestamp), UUID.";
            }
        }
        return null;
    }

    private static boolean isSupportedParameterType(Object value) {
        return value instanceof Number || value instanceof Boolean || value instanceof java.util.Date
                || value instanceof UUID || value instanceof BigDecimal;
    }

    private static void requireLocalServer(String operation) {
        if (!hasLocalServer()) {
            throw new IllegalStateException(operation + "() requires a local server: call it on the "
                    + "dedicated server or in single player, or use the callback/async variant.");
        }
        if (!DatabaseHelper.isInitialized()) {
            throw new IllegalStateException(operation + "() requires SqlApi.initialize(...) to have been called.");
        }
    }

    private static boolean hasLocalServer() {
        return FMLCommonHandler.instance().getMinecraftServerInstance() != null;
    }

    private static boolean isPhysicalClient() {
        return FMLCommonHandler.instance().getSide() == Side.CLIENT;
    }

    private static ThreadFactory daemonFactory(final String prefix) {
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + THREAD_IDS.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
