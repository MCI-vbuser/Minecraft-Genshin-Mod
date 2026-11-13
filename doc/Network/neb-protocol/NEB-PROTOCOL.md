# NEB 聚合协议摘要

本文档描述 Not Enough Bandwidth（NEB）本地源码副本中的聚合/压缩协议，用于与本仓库的 `improved_channel` / `improved_vanilla` 对照。

- 上游：<https://github.com/USS-Shenzhou/NotEnoughBandwidth>
- 许可证：GPL-3.0，见 [`LICENSE`](LICENSE)
- 平台：NeoForge；本副本按 26.x/1.21.x 分支整理，具体版本以源码为准

> 这不是本仓库的线格式。本仓库的 `improved_vanilla` 使用 magic/version/count + packetId/length/payload，且没有 NEB 的 per-connection zstd 上下文。

---

## 1. 入口：ConnectionMixin

`source/.../mixin/ConnectionMixin.java` 注入 `Connection.send(Packet, ChannelFutureListener, boolean)` 的 HEAD：

```java
@Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V",
        at = @At("HEAD"), cancellable = true)
private void nebPacketAggregate(Packet<?> packet, ... , CallbackInfo ci) { ... }
```

处理条件：

1. 远程地址不是 `LocalAddress`（本地连接不聚合）；
2. `packetListener != null` 且 `packetListener.protocol() == PLAY`（登录/配置/握手不聚合）；
3. 不在 `NotEnoughBandwidthConfig.skipType(type)` 的公共/兼容黑名单中；
4. 不是 NEB 自己的 `PacketAggregationPacket`（防止递归）；
5. 如果 packet 是 `BundlePacket`，拆成子包递归 `send`，取消原发送；
6. 否则 `AggregationManager.takeOver(packet, connection)`，取消原发送。

黑名单/直通包会先调用 `AggregationManager.flushConnection(connection)`，保证旧批次先出。

同时 `ConnectionMixin` 替换帧编解码器：

- `NebVarintLengthFieldPrepender` 允许最大 `maxPacketSize`（默认 4 MB，可配 2–64 MB）；
- `NebVarintFrameDecoder` 用 4 字节 varint 长度头，放宽原版 2 MB 限制。

---

## 2. 聚合管理：AggregationManager

`source/.../aggregation/AggregationManager.java`：

- `WeakHashMap<Connection, ArrayList<AggregatedEncodePacket>> PACKET_BUFFER`
- 单线程 daemon `ScheduledExecutorService`，名称 `NEB-Flush-thread`
- 每 20 ms 执行 `flush()`
- `flush()`：
  1. 移除已断开连接；
  2. 对每个连接 `flushInternal`；
  3. 取 `PacketEncoder` 的 `ProtocolInfo`；
  4. `connection.send(...)` 发送一个 `ClientboundCustomPayloadPacket` 或 `ServerboundCustomPayloadPacket`；
  5. 清空该连接的 buffer。
- `flushConnection(connection)`：在调度线程上只 flush 指定连接（用于黑名单/直通包保证顺序）。

---

## 3. 聚合包布局

`source/.../aggregation/PacketAggregationPacket.java`：

```text
┌---┬-----┬-----┬----┬----┬----┬----┬----...
│ B │ (S) │  p0 │ s0 │ d0 │ p1 │ s1 │ d1 ...
└---┴-----┴-----┴----┴----┴----┴----┴----...
          └--packet 1---┘└--packet 2---┘
          └----------compressed----------┘
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `B` | boolean | 是否压缩 |
| `S` | varint | 压缩前 raw 大小；仅 `B=true` 时存在 |
| `p` | prefix | 子包类型；Identifier 或 namespace/path 索引 |
| `s` | varint | 子包 data 长度 |
| `d` | bytes | 子包 data |

编码流程：

1. 对每个 `AggregatedEncodePacket`：
   - 写类型 `p`（`CustomPacketPrefixHelper.write`）；
   - 用该类型的 codec 编码到临时 buffer；
   - 写 `s` 和 `d`。
2. `rawSize = raw.readableBytes()`；
3. `compress = rawSize >= 32`；
4. 写 `B`；
5. 如果压缩：
   - 写 `S = rawSize`；
   - `ZstdHelper.compress(connection, rawBuf)`；
   - 写压缩字节；
6. 否则直接写 raw。

解码时 `handler(IPayloadContext)`：

- 读 `B`；
- 如果压缩，读 `S`，`ZstdHelper.decompress(connection, data, S)`；
- 循环读 `p/s/d`，构造 `AggregatedDecodePacket`；
- 每个子包 `handle(protocolInfo, context)`：
  - 原版：按 `IdDispatchCodec` 的 packet ID 解码，`context.enqueueWork(() -> packet.handle(listener))`；
  - 自定义：按 `NetworkRegistry.getCodec(...)` 解码，再构造对应 `Clientbound/ServerboundCustomPayloadPacket` 交给 listener。

---

## 4. 包类型前缀

`source/.../indextype/CustomPacketPrefixHelper.java`：

- 如果类型在 `NamespaceIndexManager` 中注册：
  - 写 `namespace-id`（varint）
  - 写 `path-id`（varint）
- 否则写完整 `Identifier`（UTF-8）。
- 读取时先尝试把数据解析为合法 `Identifier`；合法就用完整 Identifier，否则读两个 varint 索引。
- 26.1.2 起没有单独的 1 字节功能标志；通过“能否解析为已注册 Identifier”区分索引/非索引。
- 索引来自 `PayloadRegistration` / 原版 packet type 字段；典型索引 2 字节，而不是完整字符串。

---

## 5. zstd 上下文压缩

`source/.../zstd/ZstdHelper.java`：

- `Cache<Connection, Context>`，`weakKeys()`；
- `removalListener` 在连接被回收/移除时关闭 `Context`；
- `get(connection)` 先清理 `!isConnected()` 的 entry，再按需创建；
- `useContext(connection)`：
  - 接收方向 `CLIENTBOUND` 时返回 true；
  - 服务端找不到玩家时返回 true；
  - 否则根据配置 `playersDoNotUseContext` 决定。

`source/.../zstd/Context.java`：

```java
compressCtx = new ZstdCompressCtx();
compressCtx.setLevel(3);
compressCtx.setContentSize(false);
compressCtx.setMagicless(true);
compressCtx.setWindowLog(config.getContextLevel()); // default 23, range 21..25

decompressCtx = new ZstdDecompressCtx();
decompressCtx.setMagicless(true);
```

压缩：

```java
int maxDstSize = (int) Zstd.compressBound(raw.remaining());
ByteBuffer dst = ByteBuffer.allocateDirect(maxDstSize);
compressCtx.compressDirectByteBufferStream(dst, raw, EndDirective.FLUSH);
dst.flip();
```

解压：

```java
ByteBuffer dst = ByteBuffer.allocateDirect(originalSize);
decompressCtx.decompressDirectByteBufferStream(dst, compressed);
dst.flip();
```

要点：

- `compressDirectByteBufferStream` 使用 `EndDirective.FLUSH`，context 在不同批次之间保留历史；
- `magicless` 去掉了每帧 zstd magic；
- `windowLog=23` 表示 8 MB 历史窗口；
- 解压端必须使用**同一个连接、同一顺序**的 `ZstdDecompressCtx`；这是 NEB 能达到更高压缩率的关键。

---

## 6. 黑名单与配置

`source/.../NotEnoughBandwidthConfig.java` 默认值：

```java
public boolean compatibleMode = false;
public HashSet<String> blackList = { command_suggestion, command_suggestions, commands,
    chat_command, chat_command_signed, player_info_update, player_info_remove };
public boolean debugLog = false;
public int contextLevel = 23;
public int dccSizeLimit = 60;
public int dccDistance = 5;
public int dccTimeout = 60;
public String maxPacketSize = "4MB";
```

公共黑名单 `COMMON_BLACK_LIST` 包含：

- `minecraft:finish_configuration`
- `minecraft:login`
- NEB 自己的 `packet_aggregation_packet`
- NeoForge 注册/版本握手 payload：
  - `minecraft:register`
  - `minecraft:unregister`
  - `modded_network_query`
  - `modded_network`
  - `modded_network_setup_failed`
  - `common_version`
  - `common_register`

`skipType` 同时检查公共黑名单和兼容模式黑名单。

---

## 7. 流量统计

NEB 还包含统计/UI（`PacketEncoderMixin`、`PacketDecoderMixin`、`SimpleStatManager`、`StatScreen`），但这些不在本副本的协议核心中。对本仓库的对照而言，关键结论只是：NEB 会分别统计 raw/baked 入站/出站，并有 Alt+N 界面；本仓库目前只有 benchmark 和 debug 日志。

---

## 8. 与本仓库的差异

| 点 | NEB | 本仓库 |
|---|---|---|
| 拦截 | `Connection.send` | `NetworkManager.sendPacket` Mixin |
| 时机 | 独立 20 ms 线程 | tick TAIL + 大小上限 |
| 对象 | 所有 PLAY 包 | 原版 PLAY 包；`FMLProxyPacket` 走另一条 |
| 格式 | B/S/p/s/d | magic/version/count/id/length/data + method |
| 类型 | Identifier / 索引 | 1.12.2 packet ID |
| 压缩 | per-connection zstd context | 每批 raw/zlib/zstd 9 取最小 |
| 上限 | 4 MB 默认 | 64 KiB 批次 / 8 MiB raw |
| 统计 | Alt+N UI | benchmark/debug |

详见本仓库 [`../README.md`](../README.md) 第 4 节。
