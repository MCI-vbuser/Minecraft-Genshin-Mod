# SqlApi 使用文档

> 包名：`com.vbuser.database.sql`
> 入口：`com.vbuser.database.sql.SqlApi`

---

## 1. 功能概览

`SqlApi` 把一个内嵌 H2 数据库封装成"服务端本地执行 + 客户端按需远程请求"的统一入口：

| 能力 | 方法 |
| --- | --- |
| 初始化 / 关闭 | `initialize(...)` / `shutdown()` |
| 同步执行（服务端） | `execute(String, List)` / `execute(String)` |
| 回调执行（任意端） | `execute(String, List, Consumer)` |
| 异步执行 | `executeAsync(...)` / `query(...)` |
| 插入并取主键 | `executeInsert(...)` |
| 批量执行 | `executeBatch(...)` |
| 事务 | `inTransaction(...)` |
| 客户端可调用的命名查询 | `registerQuery(...)` / `executeRegistered(...)` |
| 权限 | `setRawSqlAuthorizer(...)` / `setReadOnly(...)` |
| 超时 | `getRequestTimeoutMs()` / `setRequestTimeoutMs(...)` |

数据库文件位置：`<世界存档目录>/database/database.mv.db`（由 `ServerEvents` 在服务器启动时初始化）。

---

## 2. 快速开始

```java
import com.vbuser.database.sql.SqlApi;
import com.vbuser.database.sql.SqlExecutionResult;

import java.util.Arrays;

// 建表（服务端，同步）
SqlApi.execute("CREATE TABLE IF NOT EXISTS player_coin (uuid VARCHAR(36) PRIMARY KEY, coin BIGINT)");

// 写入（服务端，同步）
SqlApi.execute("MERGE INTO player_coin (uuid, coin) KEY(uuid) VALUES (?, ?)",
        Arrays.asList("069a79f4-44e9-4726-a5be-fca90e38aaf5", 100L));

// 查询（服务端，同步）
SqlExecutionResult result = SqlApi.execute("SELECT coin FROM player_coin WHERE uuid = ?",
        Arrays.asList("069a79f4-44e9-4726-a5be-fca90e38aaf5"));
if (result.isSuccess()) {
    Object coin = result.getFirstValue();   // 100L
}

// 查询（任意端，回调）
SqlApi.execute("SELECT * FROM player_coin WHERE coin > ?", Arrays.asList(50),
        res -> {
            if (!res.isSuccess()) {
                System.out.println("SQL 失败: " + res.getErrorMessage());
                return;
            }
            for (Map<String, Object> row : res.getRows()) {
                System.out.println(row);
            }
        });
```

---

## 3. 端与执行模型

`SqlApi` **不再用 `FMLCommonHandler.getEffectiveSide()` 判断端**（该方法基于线程组，mod 自建线程里恒返回 CLIENT，会把服务端逻辑送进客户端分支）。现在的规则只依赖环境：

| 环境 | 判定 | 行为 |
| --- | --- | --- |
| 专用服务器 | `hasLocalServer() == true` | 直接在本进程执行 SQL |
| 单人游戏（含客户端线程） | `getMinecraftServerInstance() != null` | 直接在本进程执行 SQL（同一 JVM） |
| 连接远程服务器的客户端 | 无本地服务器 | 发请求包给服务器，结果回来后执行回调 |

因此：

- 同一个方法在单人 / 多人下**语义一致**，只是多人时多一次网络往返；
- **多人游戏**中客户端发起的裸 SQL 必须经过服务器授权（默认权限等级 4），见第 7 节；
- 单人游戏里客户端调用走本地执行，不经过授权器——此时没有权限边界，服务器和客户端都是同一台机器的同一个人；
- 对端没有安装本 mod 时无法探测（没有握手），请求会在超时后以错误结果回调。

---

## 4. 方法语义

### 4.1 生命周期

| 方法 | 端 | 说明 |
| --- | --- | --- |
| `initialize(String jdbcUrl)` | 任意 | 用完整 JDBC URL 初始化；重复调用等价于重设 URL |
| `initialize(File databaseFile)` | 任意 | 用数据库文件初始化，自动建父目录，附加 `DB_CLOSE_DELAY=-1` |
| `shutdown()` | 任意 | 取消所有挂起请求并关闭数据库 |
| `isReady()` | 任意 | 本进程是否已连上数据库 |
| `isAvailable()` | 任意 | 现在能否发起 SQL 调用（有本地服务器，或客户端已连接服务器） |
| `isLocalExecution()` | 任意 | 当前调用是否走本地执行 |

> 正常游玩时不需要手动初始化：`/world` 启动时 `ServerEvents` 已经调用了 `initialize(File)`。

### 4.2 执行

| 方法 | 端 | 说明 |
| --- | --- | --- |
| `execute(String, List<Object>)` | 需要本地服务器 | **阻塞**执行，返回结果；客户端调用会抛 `IllegalStateException` |
| `execute(String)` | 同上 | 无参数重载 |
| `execute(String, List<Object>, Consumer<SqlExecutionResult>)` | 任意 | 回调形式；`params` 可为 `null` |
| `executeAsync(String, List<Object>)` | 任意 | 返回 `CompletableFuture<SqlExecutionResult>`，**一定会完成** |
| `query(String, List<Object>, RowMapper<T>)` | 任意 | 查询并映射，返回 `CompletableFuture<List<T>>` |

回调约定：

1. **恰好调用一次**：成功、失败、超时都会回调，不会"静默消失"；
2. 服务端本地执行时在**调用线程**上回调；
3. 远程请求在**客户端主线程**上回调（可以直接操作 GUI）；
4. 回调内部抛异常会被记录日志，不影响其他请求。

```java
SqlApi.execute("UPDATE player_coin SET coin = coin + ? WHERE uuid = ?",
        Arrays.asList(10, uuid), result -> {
            if (!result.isSuccess()) {
                player.sendMessage(new TextComponentString("§c" + result.getErrorMessage()));
            }
        });
```

### 4.3 写入 / 事务

| 方法 | 端 | 说明 |
| --- | --- | --- |
| `executeInsert(String, List<Object>)` | 需要本地服务器 | 返回自增主键，无主键返回 `-1` |
| `executeBatch(String, List<List<Object>>)` | 需要本地服务器 | 一个语句多组参数，返回每组影响行数 |
| `inTransaction(TransactionWork)` | 需要本地服务器 | 事务；失败自动回滚并返回错误结果 |

```java
SqlExecutionResult tx = SqlApi.inTransaction(session -> {
    session.execute("UPDATE account SET coin = coin - ? WHERE uuid = ?", Arrays.asList(100, from));
    session.execute("UPDATE account SET coin = coin + ? WHERE uuid = ?", Arrays.asList(100, to));
});
if (!tx.isSuccess()) {
    // 已回滚
}
```

事务回调里 `session.execute(...)` 抛出的异常会被包装成 `DatabaseHelper.SqlRuntimeException`，由外层回滚；业务上也可以通过返回值判断（`SqlExecutionResult.isSuccess()`）。

### 4.4 命名查询（推荐给玩家可触发的操作）

裸 SQL 从客户端发来是很危险的；凡是"玩家点一下就会执行"的逻辑，都应该由服务端注册语句，客户端只传名字和参数：

```java
// 服务端，mod 初始化阶段
SqlApi.registerQuery("player.buy",
        "UPDATE player_coin SET coin = coin - ? WHERE uuid = ? AND coin >= ?");

// 客户端（或服务端），任何时候
SqlApi.executeRegistered("player.buy", Arrays.asList(price, uuid, price), result -> {
    if (!result.isSuccess()) { /* 余额不足等错误文本 */ }
});
```

| 方法 | 端 | 说明 |
| --- | --- | --- |
| `registerQuery(String name, String sql)` | 服务端 | 同名重复注册会覆盖 |
| `unregisterQuery(String name)` | 服务端 | |
| `getRegisteredQueries()` | 服务端 | 只读集合 |
| `executeRegistered(String, List<Object>, Consumer<...>)` | 任意 | 客户端只发送名字，不发送 SQL |

命名查询默认允许所有玩家调用（因为语句是服务端写死的、参数化的）；如需进一步限制，可在 `registerQuery` 前把玩家校验做在业务层，或改用 `setRawSqlAuthorizer` + 裸 SQL（不推荐）。

---

## 5. 结果对象 `SqlExecutionResult`

| 方法 | 说明 |
| --- | --- |
| `isSuccess()` | 是否成功 |
| `isQuery()` | 是否为查询（与 `getRows() != null` 等价） |
| `getRows()` | `List<Map<String,Object>>`，更新语句时为 `null`；行内键顺序与 `getColumns()` 一致 |
| `getColumns()` | `List<SqlColumn>`，含列名、类型名、`java.sql.Types` |
| `getUpdateCount()` | 更新语句影响行数；查询为 `-1` |
| `getErrorMessage()` | 失败原因 |
| `getFirstRow()` / `getFirstValue()` | 单行/单值查询的便捷方法 |

`SqlColumn`：

```java
String name     = column.getName();     // 列标签（有 AS 时是别名）
String typeName = column.getTypeName(); // 例如 VARCHAR / TIMESTAMP
int    sqlType  = column.getSqlType();  // java.sql.Types.*
```

---

## 6. 支持的参数与结果类型

以下类型可以安全地跨越网络（客户端 ⇄ 服务端）：

`null`、`String`、`Integer`、`Long`、`Double`、`Float`、`Short`、`Byte`、`Boolean`、`BigDecimal`、`byte[]`、`java.sql.Timestamp`、`java.sql.Date`、`java.sql.Time`、`java.util.Date`、`UUID`

规则：

- **参数**：客户端发请求前会校验，不支持的参数类型会直接以错误结果回调（不会发出请求）；
- **结果**：任何不认识的 JDBC 类型都会**降级为字符串**（`String.valueOf`），不会像旧实现那样抛 `IllegalArgumentException` 并导致整个响应包被静默丢弃；
- **长度**：单条文本/二进制上限 `SqlValueCodec.MAX_TEXT_BYTES`（30 KiB），超长文本会被截断并追加 `...<truncated N chars>`，超长二进制会被替换为一段说明文字；
- 大 BLOB 不适合走 SQL API（单包上限限制），需要存文件请用 `BGMAPI` / `CGAPI` 的资产接口。

---

## 7. 权限与只读

裸 SQL 是"任意语句执行"的能力，因此默认**只允许权限等级 4 的玩家**从客户端发送：

```java
// 完全禁用客户端裸 SQL
SqlApi.setRawSqlAuthorizer((player, sql) -> false);

// 只允许某类语句
SqlApi.setRawSqlAuthorizer((player, sql) -> sql.trim().toUpperCase().startsWith("SELECT"));

// 放行（危险，仅限你自己的受控客户端逻辑）
SqlApi.setRawSqlAuthorizer((player, sql) -> true);
```

服务端本地调用（`hasLocalServer()` 为真）不经过授权器——此时你就在服务器进程里，本身就是受信代码。

只读模式：

```java
SqlApi.setReadOnly(true);   // 之后只允许 SELECT / WITH / SHOW / EXPLAIN
```

> 只读是"护栏"不是安全边界，真正的安全边界是第 4.4 节的命名查询和第 7 节的授权器。

---

## 8. 超时、取消与断线

| 项 | 默认 | 说明 |
| --- | --- | --- |
| 远程请求超时 | 30 000 ms | `setRequestTimeoutMs(long)`，超时后回调收到错误结果 |
| JDBC 查询超时 | 30 s | `DatabaseHelper.setQueryTimeoutSeconds(int)`，0 表示不限制 |
| 断线清理 | 自动 | 客户端掉线时 `SqlApi.Lifecycle` 会把所有挂起请求以错误结果结束 |

```java
SqlApi.addListener(new SqlApi.Listener() {
    @Override public void onRequestSent(long id, String sqlOrName) { }
    @Override public void onRequestFailed(long id, String error) { }
});
```

`callbacks` 不再会无限增长：成功、失败、超时、断线四种路径都会移除条目。

---

## 9. 线程模型

| 调用场景 | 回调线程 |
| --- | --- |
| 服务端本地执行（`execute(sql, params, cb)`） | 调用者线程（同步） |
| 服务端异步执行（`executeAsync`） | `SqlApi-io` 线程池 |
| 客户端远程请求 | 客户端主线程 |

`execute(String, List)`（无回调、阻塞版）在服务端主线程上执行会在查询期间卡住 tick；长查询请用 `executeAsync`。

`query(...)` 返回的 future 在以下情况失败：

- 语句失败 → `CompletionException(SqlApi.SqlException)`
- mapper 抛异常 → `CompletionException(原异常)`

---

## 10. 迁移指南（旧 API → 新 API）

| 旧写法 | 新写法 | 说明 |
| --- | --- | --- |
| `SqlApi.executeSql(sql, params, cb)` | `SqlApi.execute(sql, params, cb)` | 旧方法保留为 `@Deprecated` 别名 |
| `SqlApi.executeSql(sql, null, cb)` | 同左 | 旧版本客户端会 NPE，现已修复 |
| `DatabaseHelper.initialize(url)` | `SqlApi.initialize(File)` | `DatabaseHelper` 仍然可用，但不再需要直接接触 |
| `DatabaseHelper.executeSql(...)`（服务端命令） | `SqlApi.execute(...)` | |
| 手写 `PlaySQL` 包 / 自己校验权限 | `registerQuery` + `executeRegistered` | 推荐做法 |

内部方法（`completeRequest`、`runRegisteredQuery`、`authorizeRawSql`）是给数据包处理器用的，mod 代码不要调用。

---

## 11. 已知限制

- 一个请求对应一条语句；多语句请用 `inTransaction`。
- 结果集一次性读入内存，超大表请加 `LIMIT`。
- 每条语句独立建连接（H2 内嵌模式，开销很小）；没有连接池。
- 客户端与服务器必须使用同一版本的 mod（请求/响应字段随版本变化）。
- 数据库是**世界存档级**的，多人服务器共享；不要在客户端做写操作假设。
