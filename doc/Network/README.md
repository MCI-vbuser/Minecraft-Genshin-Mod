# 网络包聚合协议

- 核心代码：
  - 模组包聚合：[`AggregatedPacket.java`](../../src/main/java/com/vbuser/genshin/tool/network/AggregatedPacket.java)、[`PacketQueueManager.java`](../../src/main/java/com/vbuser/genshin/tool/network/util/PacketQueueManager.java)
  - 原版包劫持：[`MixinNetworkManager.java`](../../src/main/java/com/vbuser/mixin/MixinNetworkManager.java)、[`VanillaBundlePacket.java`](../../src/main/java/com/vbuser/genshin/tool/network/VanillaBundlePacket.java)、[`VanillaBundleHandler.java`](../../src/main/java/com/vbuser/genshin/tool/network/VanillaBundleHandler.java)
  - 配置常量：[`NetConfig.java`](../../src/main/java/com/vbuser/genshin/tool/network/util/NetConfig.java)

---

## 1. 摘要

本仓库有两条互相独立的聚合路径：

1. **`improved_channel`：模组自定义包聚合**
   - 对象：继承 `ModPacket` 的模组包。
   - 队列：按目标（客户端目标，或每个 `EntityPlayerMP`）分别排队。
   - 批次：默认 32 KiB、4096 包、20 ms 延迟；超限或延迟到期时发送一个 `AggregatedPacket`。
   - 载体：Forge `SimpleNetworkWrapper` 的 `AggregatedPacket`。
   - 自带协议哈希，客户端/服务端注册表不一致时拒绝解码。

2. **`improved_vanilla`：原版 PLAY 包劫持**
   - 对象：`NetworkManager.sendPacket` 中的原版 1.12.2 PLAY 包。
   - 队列：每个 `NetworkManager` 一个批次。
   - 批次：默认 64 KiB、4096 包；在 `processReceivedPackets` 的 tick 末尾、达到大小上限、连接状态变化、或遇到不可聚合包时 flush。
   - 载体：Forge `SimpleNetworkWrapper` 的 `VanillaBundlePacket`，内部是原版包 ID + 原始 payload 的紧凑列表。
   - 压缩：每批在 **raw / zlib / zstd(level 9)** 中选择最小者。
   - `FMLProxyPacket`（Forge/模组自定义通道）不进入这条路径，避免和 `improved_channel` 双重包裹。

两条路径都不改变游戏逻辑包的处理方式：接收端最终仍然通过原来的 `IMessageHandler` / `INetHandler` 处理。

---

## 2. `improved_channel`：模组包聚合

### 2.1 线格式

`AggregatedPacket` 是 Forge `IMessage`，由 `SimpleNetworkWrapper` 包装为 `FMLProxyPacket` 后发送。其 payload 布局为：

```text
offset  size        field
0       1           magic = 0x4E ('N')
1       1           version = 2
2       4           registryHash (int, big-endian)
6       varint      count
...     varint      packetId
...     varint      payloadLength
...     bytes       payload (ModPacket.encode)
```

约束：

- `count`：`0..4096`。
- `packetId`：由 `PacketRegistry` 按**排序后的完整类名**注册；两端必须完全一致。
- `registryHash`：排序类名的 31 进制哈希；不一致时抛出异常并拒绝整批。
- `payloadLength`：必须不超过当前缓冲区可读字节数。
- 最大批次字节：32 KiB（编码后的 id + length + payload 都计入）。

### 2.2 排队与 flush

`PacketQueueManager` 为每个 target 维护一个 `QueueEntry`：

```text
ConcurrentHashMap<target, QueueEntry>
QueueEntry = ConcurrentLinkedQueue<ModPacket> + totalBytes + packetCount + firstTimestamp
```

`target` 在客户端是单个常量对象，在服务端是 `EntityPlayerMP`。每个包在入队前先编码一次以得到真实大小，然后只保存原对象；发送时再编码进 `AggregatedPacket`。

flush 条件：

- 批次达到 `AGGREGATE_MAX_BYTES = 32768`；
- 包数达到 `AGGREGATE_MAX_PACKETS = 4096`；
- 最早入队时间超过 `AGGREGATE_MAX_DELAY_MS = 20`（`tick()` 在每个 client/server tick 的 END 阶段调用）；
- `neverAggregate()` 返回 true 的包：先 flush 当前队列，再立即发送该包；
- 玩家登出、客户端断线、服务器停止等生命周期边界。

**顺序保证**：`neverAggregate` 包会先 flush 旧队列，避免“先入队的包反而后发”。

### 2.3 接收

`AggregatedPacketHandler` 在客户端/服务端主线程调度 `message.handle(ctx)`；`AggregatedPacket.handle` 按序调用每个 `ModPacket.handle(ctx)`。这保证同一批次里的业务处理顺序和发送顺序一致。

### 2.4 生命周期

`NetworkManager` 初始化时注册 `improved_channel` 的两个方向；`LifecycleHandler` 在玩家登出时 `remove` 对应服务端队列，在客户端断线时 flush；`Main.serverStopping` 调用 `flushAll()`。

---

## 3. `improved_vanilla`：原版 PLAY 包劫持

### 3.1 拦截点

[`MixinNetworkManager`](../../src/main/java/com/vbuser/mixin/MixinNetworkManager.java) 注入 `net.minecraft.network.NetworkManager.sendPacket(Packet)` 的 HEAD，并满足以下条件才接管：

- `NetConfig.ENABLE_VANILLA_HIJACK == true`；
- channel 已打开；
- `EnumConnectionState.PLAY`（登录、握手、状态查询不碰）；
- 不是 `FMLProxyPacket` 的普通自定义包；
- 不在黑名单中。

`NetworkManager.direction` 表示**入站方向**；发送原版包时使用相反方向：

```java
EnumPacketDirection outbound = direction == CLIENTBOUND ? SERVERBOUND : CLIENTBOUND;
```

原版包 ID 由 `EnumConnectionState.PLAY.getPacketId(outbound, packet)` 得到；拿不到 ID 的包会回退到原版路径。

### 3.2 小包直通

为了避免“一个 30 字节的 keepalive/移动包被包成更大的 carrier”，实现做了两级直通：

1. **入队前**：当前批次为空且编码后 payload `< 128` 字节时，返回 `0`，不取消原调用，直接走原版发送。
2. **flush 时**：如果待发批次只有一个包、payload `< 128` 字节、且保存了原始 `Packet` 对象，则通过 `INF_BYPASS` 重入原版 `sendPacket` 发送原包，不构造 carrier。

因此：

- 单包小流量保持原版时延，不增加 carrier 开销；
- 同一 tick 内出现的多个包仍会被聚合；
- 大包（chunk、批量实体更新等）正常走批次。

### 3.3 批次布局

`inf$flushVanilla()` 构造的原始字节序列：

```text
magic    u8    0x56 ('V')
version  u8    1
count    varint
repeat count times:
  packetId     varint   EnumConnectionState.PLAY 的 ID
  payloadLen   varint
  payload      bytes    Packet.writePacketData(...)
```

然后交给 `VanillaBundlePacket`：

```text
method       u8     0 = raw, 1 = zlib, 2 = zstd
if method != 0:
  originalSize varint  原始 raw bundle 长度
  bytes        zlib/zstd 压缩后的内容
else:
  bytes        raw bundle
```

压缩选择规则：

- `raw.length < VANILLA_ZSTD_THRESHOLD (64)`：不压缩；
- 否则同时尝试 `Deflater` 和 `Zstd.compress(raw, 9)`；
- 选三者中最小者；zlib 用于保证某些数据上不劣于 zstd，zstd 用于需要更高压缩率的场景；
- 任何 zstd native 异常都会回退到未压缩/zlib 路径，不会让连接崩溃。

上限：

- `VANILLA_MAX_BYTES = 65536`（批次编码大小）
- `VANILLA_MAX_PACKETS = 4096`
- `VANILLA_MAX_RAW_BYTES = 8 MiB`（编码/解码单批原始上限）
- 单包编码长度不得超过 8 MiB

### 3.4 发送与 flush 时机

`MixinNetworkManager` 在以下时机 flush：

- `processReceivedPackets` 的 TAIL（每个 client/server tick 一次）；
- 批次达到 `VANILLA_MAX_BYTES` 或 `VANILLA_MAX_PACKETS`；
- 遇到 `FMLProxyPacket`（且不是本 carrier 通道）时：先 flush，再放行该自定义包，保证顺序；
- 遇到非 PLAY 状态或黑名单包时：先 flush，再放行；
- `setConnectionState` 离开 PLAY 时；
- `channelInactive` 清空队列。

flush 时通过 `com.vbuser.genshin.tool.network.NetworkManager.sendVanillaBundle` 在 `improved_vanilla` 通道发送：

- 服务端：`NetHandlerPlayServer.player`；
- 客户端：`sendToServer`。

`SimpleNetworkWrapper` 会把 `VanillaBundlePacket` 包装成 `FMLProxyPacket`；Mixin 识别到 `improved_vanilla` 后直接放行，避免递归。

### 3.5 接收与重放

`VanillaBundleHandler` 在 Forge 的 `IMessageHandler` 回调中拿到 `MessageContext`：

1. 根据 `ctx.side` 选择 `INetHandler` 和方向：
   - 客户端：`CLIENTBOUND`；
   - 服务端：`SERVERBOUND`。
2. 校验 magic/version/count/length；
3. 对每个条目：
   - `EnumConnectionState.PLAY.getPacket(direction, id)`；
   - `readPacketData(new PacketBuffer(slice))`；
   - `processPacket(handler)`。
4. 捕获 `ThreadQuickExitException`：这是 `PacketThreadUtil` 在非世界线程上“已调度到主线程”的正常控制流，不应作为错误记录；其他异常按单包记录，避免整批丢弃。

处理器直接在网络线程执行，和原版 `NetworkManager.channelRead0` 的线程语义一致；需要主线程的包由 `PacketThreadUtil` 自行入队。

### 3.6 黑名单

当前硬编码黑名单：

| 类 | 原因 |
|---|---|
| `SPacketJoinGame` | 客户端要在后续批次前建立 `NetHandlerPlayClient`/世界状态 |
| `SPacketDisconnect` | 断开必须立即、可靠地送达 |
| `SPacketCustomPayload` | 原版自定义 payload，避免和 Forge/模组通道混在一起 |
| `CPacketCustomPayload` | 同上 |

另外所有 `FMLProxyPacket` 都不进入原版包聚合；其中 `improved_vanilla` 是本协议自己的 carrier，直接放行。

### 3.7 顺序与线程

- 发送端：同一 `NetworkManager` 的批次按入队顺序保存；flush 时按序写入。
- 接收端：`VanillaBundleHandler` 按条目顺序处理；`ThreadQuickExitException` 只影响当前包的线程调度，不会跳过后续条目。
- 小包直通：直通包在当前批次 flush 前/后立即走原版，具体由 `inf$queueVanilla`/`inf$flushVanilla` 的顺序决定；`FMLProxyPacket` 和黑名单包会先 flush 旧批次。
- 断线：`channelInactive` 丢弃未发送批次，避免向已关闭连接写入。

### 3.8 与模组包路径的关系

```text
ModPacket
  └─ improved_channel (AggregatedPacket)
       └─ SimpleNetworkWrapper → FMLProxyPacket → NetworkManager.sendPacket
                                                        │
                                                        └─ Mixin 看到 FMLProxyPacket
                                                           → 不进入 improved_vanilla

原版 PLAY Packet
  └─ NetworkManager.sendPacket
       └─ MixinNetworkManager → 原版批次 → improved_vanilla (VanillaBundlePacket)
```

两条路径互不嵌套，避免 `FMLProxyPacket` 被当成原版包序列化造成递归或双重压缩。

---

## 4. 与 NEB 的关系

### 4.1 关系定位

本项目**参考了 NEB 的设计目标与协议思路**，但不是 NEB 的 1.12.2 移植，也不与 NEB 线格式兼容：

- NEB 的目标：在 `Connection` 层拦截原版/模组网络包，20 ms 聚合成一个大包，再用 zstd 上下文压缩，减少小包与重复包头。
- 本项目目标：在 1.12.2 Forge 的 `NetworkManager.sendPacket` 层做同样的事；由于 1.12.2 没有 NEB 使用的 `Connection.send`/`ProtocolInfo`/`CustomPacketPayload` API，必须改用 `EnumConnectionState`、`FMLProxyPacket` 和 Forge `SimpleNetworkWrapper` 实现。
- 本项目的 `improved_channel` 是额外的模组包聚合层；NEB 没有这一层（NEB 直接接管所有包）。
- 本项目当前**没有采用** NEB 的 per-connection `ZstdCompressCtx` 上下文压缩，而是每批独立的 zstd level 9；也没有 NEB 的 20 ms 独立调度线程、4 MB 可配置帧上限、包类型索引、延迟区块缓存。


### 4.2 对照表

| 维度 | NEB | 本项目 |
|---|---|---|
| 平台 | NeoForge / Minecraft 26.x、1.21.1 分支 | Forge 1.12.2 |
| 拦截点 | `Connection.send(Packet, listener, flush)` | `NetworkManager.sendPacket(Packet)` Mixin |
| 对象 | 所有 PLAY 包（含 modded custom payload） | 原版 PLAY 包；`FMLProxyPacket` 单独走 `improved_channel` |
| 聚合器 | `AggregationManager` + 20 ms 调度线程 | `MixinNetworkManager` 每连接批次 + tick TAIL |
| 延迟 | 固定 20 ms | 最多约 1 tick（50 ms），小包直通 |
| 批次上限 | 默认 4 MB（2–64 MB 可配） | 64 KiB（批次）/8 MiB（单批 raw 硬上限） |
| 子包头 | 类型 prefix + varint size + data | 原版 packet ID varint + size varint + data |
| 类型编码 | `Identifier` 或 namespace/path 索引 | 1.12.2 `EnumConnectionState` 的 packet ID |
| 压缩 | zstd 上下文，level 3，magicless，windowLog 23 | raw/zlib/zstd level 9 三者取最小；无上下文 |
| 载体 | `CustomPacketPayload` | `SimpleNetworkWrapper` + `FMLProxyPacket` |
| 帧上限 | 自定义 `NebVarintFrameDecoder/Prepender`，默认 4 MB | 使用原版 Forge 帧；批次小，不扩展帧 |
| 额外优化 | 包类型索引、延迟区块缓存、流量统计 UI | 协议哈希、注册表校验、应用层聚合、小包直通 |


### 4.3 NEB 协议副本

`doc/Network/neb-protocol/` 保存了 NEB 的协议副本：

相关代码文件**不参与本仓库编译**。NEB 采用 GPL-3.0；由于未把 NEB 源码并入产品代码，未按 GPL-3.0 处理。

---

## 5. NEB 协议摘要（复制说明）

更完整的 NEB 仓库见 [`GitHub`](https://github.com/USS-Shenzhou/NotEnoughBandwidth)。核心协议如下。

### 5.1 聚合包布局

NEB 的 `PacketAggregationPacket.encode`：

```text
B = boolean compressed
S = varint size of compressed buf（仅 compressed 时存在）
p = prefix（Identifier 或 namespace/path 索引）
s = varint size of this subpacket
d = bytes data of this subpacket
```

```text
┌---┬-----┬-----┬----┬----┬----┬----┬----...
│ B │ (S) │  p0 │ s0 │ d0 │ p1 │ s1 │ d1 ...
└---┴-----┴-----┴----┴----┴----┴----┴----...
          └--packet 1---┘└--packet 2---┘
          └----------compressed----------┘
```

要点：

- `rawSize >= 32` 时尝试压缩；
- 压缩后写入 `S` 和压缩字节；否则直接写 `d`；
- `p` 在 NEB 中不是固定 2 字节；`CustomPacketPrefixHelper` 会优先写 **namespace-id/path-id 索引**（各 varint），对未注册类型回退到完整 `Identifier`；
- 子包既可以是原版包，也可以是 `CustomPacketPayload`；`AggregatedEncodePacket` 分别走 `IdDispatchCodec` 或 NeoForge `NetworkRegistry` codec。

### 5.2 聚合管理

- `ConnectionMixin` 注入 `Connection.send`，仅处理：
  - 非 `LocalAddress`；
  - `packetListener.protocol() == PLAY`；
  - 不在 `COMMON_BLACK_LIST`/兼容黑名单；
  - 不是自身 `PacketAggregationPacket`。
- `BundlePacket` 会被拆成子包后重新发送；
- 其他包交给 `AggregationManager.takeOver`；
- `AggregationManager` 使用 `WeakHashMap<Connection, ArrayList<AggregatedEncodePacket>>`；
- 独立单线程 `ScheduledExecutorService` 每 20 ms flush；
- flush 时构造 `ClientboundCustomPayloadPacket` 或 `ServerboundCustomPayloadPacket`，条件是 `connection.getSending() == PacketFlow.CLIENTBOUND/SERVERBOUND`。

### 5.3 zstd 上下文

`ZstdHelper`：

- 每个 `Connection` 一个 `Context`，`CacheBuilder.weakKeys()`；连接断开后 `removalListener` 关闭 context；
- `Context`：
  - `ZstdCompressCtx` level 3；
  - `setContentSize(false)`；
  - `setMagicless(true)`；
  - `setWindowLog(contextLevel)`，默认 23（8 MB），范围 21–25；
  - `ZstdDecompressCtx.setMagicless(true)`；
  - 压缩使用 `compressDirectByteBufferStream(..., EndDirective.FLUSH)`，解压使用 `decompressDirectByteBufferStream(...)`；
  - 是否使用 context 由 `useContext` 决定（大部分连接为 true）。
- 这使 NEB 可以跨批次复用 zstd 历史窗口，而不是每批独立压缩。

### 5.4 黑名单与帧上限

- `COMMON_BLACK_LIST` 包含：
  - `minecraft:finish_configuration`、`minecraft:login`；
  - 自身 `packet_aggregation_packet`；
  - NeoForge 的注册/版本握手 payload（`minecraft:register`、`minecraft:unregister`、`modded_network_query` 等）。
- 兼容模式黑名单默认包含命令补全、命令、聊天命令、玩家信息更新/移除等。
- `NotEnoughBandwidthConfig.maxPacketSize` 默认 `4MB`，通过 `NebVarintFrameDecoder` / `NebVarintLengthFieldPrepender` 把帧长度上限从原版 2 MB 提高到 2–64 MB。
- `contextLevel` 默认 23；`debugLog` 关闭；`compatibleMode` 默认 false。

---

## 6. 基准与验证

### 6.1 基准

命令：

```bash
./gradlew networkBenchmark --offline
```

最终结果（3000 个模拟 ModPacket，raw payload 354,589 B）：

```text
baseline（每包单独）:              265,877 B  (75.0% raw)
ours（模组包聚合，一帧）:            29,125 B  (8.2% raw, 11.0% baseline)
ours（原版包，best）:                24,690 B  (7.0% raw, 9.3% baseline)
vanilla zstd-only（level 3）:         35,531 B  (10.0% raw, 13.4% baseline)
vanilla zstd levels 3/9/19:          35,531 / 24,686 / 21,914 B
NEB-like（zstd level 3 模型）:         35,529 B  (10.0% raw, 13.4% baseline)
ours（32 KiB 队列）:                 33,210 B  (9.4% raw, 12.5% baseline)
ours vanilla / NEB-like:             69.49%
decode one aggregated batch:         OK
decode one vanilla bundle:           OK
ordering（queued -> immediate）:     OK
queued targets after flush:          0
```

说明：

- `ours（原版包，best）` 使用 **zstd level 9**，在每个批次上与 zlib 比较后取最小；
- `NEB-like` 是**模型**，不是运行 NEB 得到的真实数字；它按 NEB 的“一个聚合包 + zstd”思路估算，用于层级对照；
- 真实 NEB 使用上下文压缩，真实比例会随数据/窗口变化；
- 本基准是合成的模组包，不代表所有 Minecraft 流量；真实 chunk/实体流量需以实际连接统计为准。

### 6.2 客户端验证

`runClient` 进入世界后观察到：

```text
Starting integrated minecraft server version 1.12.2
Player275 logged in with entity id 21
[genshin-network]: Vanilla bundle: 11 packets, 70667 raw bytes
[genshin-network]: Vanilla bundle: 20 packets, 57905 raw bytes
```

验证点：

- `MixinNetworkManager` 正常注入；
- 64 KiB 批次和 zstd level 9 能编码/解码；
- 客户端能正常进入世界，无 `Failed to process`/`Failed to handle`/崩溃报告；
- `ThreadQuickExitException` 作为正常控制流被忽略，不刷错误；
- `HelloPacket` 的模组通道仍正常。

---

## 7. 边界与已知限制

| 限制 | 说明 |
|---|---|
| 双端安装 | `improved_channel`/`improved_vanilla` 都是 Forge 通道；只装一端会不识别或断开 |
| 仅 PLAY | 登录、握手、状态查询、Forge 握手不走聚合 |
| 第三方自定义包 | 不劫持 `FMLProxyPacket`；第三方模组包不会被 `improved_vanilla` 聚合，也不会被 `improved_channel` 自动接管（除非它继承 `ModPacket`） |
| 无 zstd 上下文 | 当前每批独立 zstd level 9；NEB 的跨批上下文可能进一步降低比例 |
| tick flush | 最多约 50 ms 延迟；小包直通降低时延，但不是 NEB 的独立 20 ms 调度 |
| 压缩双重 | carrier 先做 zlib/zstd，外层仍会经过原版连接压缩；基准包含这一层 |
| 帧上限 | 未修改 1.12.2 的 Varint21 帧上限；64 KiB 批次远低于上限 |
| 原版包注册表 | 使用 1.12.2 固定的 `EnumConnectionState` ID，不做版本协商；跨版本不兼容 |
| 统计 | 目前没有 NEB 的 Alt+N 流量 UI；只有 benchmark 和 debug 日志 |

---

## 8. NEB 协议副本清单与授权

副本目录：[`doc/Network/neb-protocol/`](neb-protocol/)

- 参考项目：[USS-Shenzhou/NotEnoughBandwidth](https://github.com/USS-Shenzhou/NotEnoughBandwidth)
- 许可证：GNU General Public License v3.0（见 [`neb-protocol/LICENSE`](neb-protocol/LICENSE)）
- 用途：仅用于协议对照、代码阅读；**不参与本仓库编译**。
- 说明：NEB 源码副本不是本项目的依赖，也不代表本项目采用了 NEB 的全部实现；两者的线格式不兼容。