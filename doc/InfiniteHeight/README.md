# 无限高度（Infinite Height）

> 模块 id：`inf` · 版本：`0.1.0` · 目标：Minecraft 1.12.2 / Forge 14.23.5.2854
>
> 在**不改动原版 `0..255` 区域**的前提下，为主世界增加 `Y < 0` 与 `Y > 255` 的
> 方块存储、光照、存档、同步与渲染。

本文档描述当前实现（含维度限制、渲染视距、发送队列、可信标记等行为），
是唯一权威说明。目录下的旧版 `Lighting.md` 已废弃。

---

## 1. 范围与设计取舍

### 1.1 做什么

| 能力 | 说明 |
|---|---|
| 方块存取 | `World#getBlockState` / `setBlockState` 在任意 Y 上工作 |
| 光照 | 天空光 / 方块光在扩展区域完整传播，并在 `Y=0/-1`、`Y=255/256` 与原版双向互通 |
| 存档 | 扩展 section 与光照数组随区块一起保存，带版本号与迁移 |
| 同步 | 扩展方块、方块实体同步给客户端；光照不传，两端各自推导 |
| 渲染 | 与原版同源的网格（顶点光照 + AO），跟随原版视距并做视锥裁剪 |
| 原版行为 | 命令、活塞、门、方块事件、`isBlockLoaded` / `isAreaLoaded` 等入口去掉 0..255 假设 |

### 1.2 不做什么

| 不做 | 原因 |
|---|---|
| 修改原版 `0..255` 的光照数组 / `heightMap` | 会牵动 `relightBlock`、`generateSkylightMap`、`heightMap` 等大量原版逻辑，兼容性代价过高 |
| 在扩展区域生成地形 / 结构 / 矿物 | 世界生成器完全保持原样，扩展区域是"玩家建造区" |
| 让扩展方块遮挡原版区域 | 见 §9 已知限制 |
| 支持主世界以外的维度 | 见 §1.3 |
| 转移生物生成、降雨、`getTopSolidOrLiquidBlock` | 这些仍按 `0..255` 计算 |

### 1.3 只在主世界生效

`HeightConstants#isExtendedWorld(World)` 是**所有入口的总闸门**：

```java
public static final int EXTENDED_DIMENSION = 0;
public static boolean isExtendedWorld(World world) {
    return world != null && world.provider != null
            && world.provider.getDimension() == EXTENDED_DIMENSION;
}
```

其它维度：

- 扩展 Y 一律视为"界外"：`World#isOutsideBuildHeight` 返回 `true`，
  `setBlockState` 直接被拒绝，不会碰到 `Chunk.storageArrays`（它对越界下标没有保护，会抛 AIOOBE）；
- `Chunk#getBlockState` 对扩展 Y 返回空气；
- 不读取、不写入 `inf:` 存档标签（旧数据会在下一次保存时被丢弃）；
- 客户端渲染器在非主世界直接清空网格。

这样下界/末地的生成器、传送门查找、生物群系逻辑以及依赖 `0..255` 的 mod 全部保持原样。

### 1.4 三条不变量

1. **原版区域归原版**：`0..255` 的方块与光照数组只由原版维护；扩展引擎只在接缝层
   （`Y=0`、`Y=255`）把它们当光源读写。
2. **隐式值**：没有分配 nibble 数组的 section，每格的值等于 `canSeeSky ? 15 : 0`
   （方块光为 0）。数组只在需要写入不同值时创建。
3. **光照收敛才可信**：`inf:Light` 只在该 column 的全部 section 播种完成、
   且引擎队列为空时才写入存档。

---

## 2. 模块地图

```
com.vbuser.inf
├── HeightConstants           Y 范围、维度闸门
├── InfiniteHeight            @Mod 入口、网络通道
├── chunk/
│   ├── ExtendedColumn        每列（chunk）的扩展方块存储
│   ├── ExtendedColumnHolder  Chunk mixin 实现的取列接口
│   └── ColumnManager         取列的静态入口
├── light/
│   ├── ColumnLight           每列的光照数据 + 列顶 + 增量维护
│   ├── ExtendedLightEngine   每 World 一个的传播引擎（队列 + BFS）
│   ├── ExtendedWorldLight    World mixin 实现的取引擎接口
│   └── LightRenderHook       引擎 → 渲染器的可选回调（客户端安装）
├── network/
│   ├── SPacketExtendedSection        批量方块更新（整 section）
│   ├── SPacketExtendedTileEntity     单个方块实体
│   ├── *Handler                      客户端处理
│   ├── ExtendedPacketQueue           按玩家 FIFO + 每 tick 字节预算
│   └── ExtendedJoinCheck             缺 mod 客户端友好断开
└── client/
    ├── ExtendedBlockRenderHandler      扩展方块网格（VBO）
    └── ExtendedTileEntityRenderHandler 扩展方块实体
```

Mixin 侧见 §11 的文件索引。

---

## 3. 坐标与存储

### 3.1 section 与下标

- section 编号：`sectionY = y >> 4`，section 的基准 Y 为 `sectionY << 4`。
  `Y` 是 `int`，因此 section 编号可以取到 `±134217728`（实际受 `BlockPos` 与
  命令边界约束）。
- section 内下标打包（`ExtendedColumn`）：

```text
index = (x & 15) << 8 | (z & 15) << 4 | (y & 15)     // 0..4095
```

与 `ExtendedBlockStorage#get/set` 的 `(x&15, y&15, z&15)` 一致。

### 3.2 ExtendedColumn

每个 `Chunk` 通过 `@Unique` 字段持有一个 `ExtendedColumn`（`MixinChunk`），
不再使用全局 `WeakHashMap`，热路径无锁无查表。

| 字段 | 内容 |
|---|---|
| `Int2ObjectOpenHashMap<ExtendedBlockStorage> sections` | sectionY → 方块存储（与原版 `ExtendedBlockStorage` 同类型） |
| `Int2ObjectOpenHashMap<BitSet> nonAir` | 每 section 4096 位的非空气位图，渲染/存档/发包只遍历置位 |
| `ColumnLight light` | 该列的光照数据 |

不变量：`nonAir` 与 `sections` 同步维护；空 section 会被丢弃，避免长期存档
堆积大量空对象。

`allowFluidToCreateNewSections`（默认 `false`）阻止流体在扩展区域"无中生有"
创建新 section：`Y=-1` 的漏水不应该让整列向下无限长。

### 3.3 写入路径

`World#setBlockState` 是关键入口。`MixinWorld` 用 `@Redirect` 把其中的
`chunk.setBlockState(...)` 改道：

```text
World.setBlockState(pos, state, flags)
├── isValid(pos)            → MixinWorld#isOutsideBuildHeight（扩展 Y 在主世界合法）
├── chunk.setBlockState     → 被 Redirect：
│     ├── Y ∈ 0..255        → 原版 Chunk#setBlockState
│     └── Y ∉ 0..255        → ExtendedColumn#setBlock
├── checkLight(pos)         → 扩展 Y 直接返回 false（阻止原版 BFS 越界/抢格子）
└── markAndNotifyBlock(...) → 原版逻辑（含 notifyBlockUpdate，见 §6.4）
```

`ExtendedColumn#setBlock` 负责：

1. 需要时创建 section；
2. 记录写入前的列顶（天空光需要知道"变高还是变矮"）；
3. 写方块 + 维护 `nonAir`；
4. 调 `ColumnLight#onBlockChanged`（更新列顶、排光照）；
5. 客户端：通知渲染器该 section 需要重建；接缝层额外通知相邻原版 section；
6. 服务端：`breakBlock` / `onBlockAdded` / 液体 `scheduleUpdate` / 方块实体刷新
   （`shouldRefresh` 语义与原版 `Chunk#setBlockState` 一致）；
7. 方块变成空气且 section 空了 → 丢弃 section。

`MixinChunk` 还对**直接调用** `Chunk#setBlockState` 的 mod 做了同等处理（HEAD 注入）。

---

## 4. 光照

### 4.1 数据

`ColumnLight` 挂在 `ExtendedColumn` 上，每个 section 一份 `NibbleArray`：

| 字段 | 内容 |
|---|---|
| `Int2ObjectOpenHashMap<NibbleArray> skyLight` | 每 section 2048 字节 |
| `Int2ObjectOpenHashMap<NibbleArray> blockLight` | 每 section 2048 字节 |
| `int[] topMap` | 每列（`z << 4 | x`）在扩展 Y 上最高的挡光方块 + 1，`NO_TOP = Integer.MIN_VALUE` |
| `BitSet vanillaEmpty` | 镜像 `Chunk.heightMap == 0`，用于检测原版列顶 0 ↔ 非 0 跳变 |
| `suspended` | 客户端批量导入期间跳过该列 |
| `trusted` / `pendingSeeds` | 存档可信状态与重建进度 |

光照 section 与方块 section **分开存**：一个只有空气的 section 也可能有光照值
（洞穴、屋檐下），不能因为 section 里没有方块就丢掉。

### 4.2 列顶

```text
top(x, z) = max(topMap[x, z], vanillaHeightMap[x, z])
canSeeSky(y) = (top == NO_TOP) || y >= top
```

- 原版高度上的屋顶会遮住 `Y < 0`；
- 扩展区域的方块会遮住它下方的扩展空气；
- `vanillaHeightMap == 0` 表示"原版范围没有挡光方块"，此时只看扩展列顶。

列顶的增量维护（`ColumnLight#onBlockChanged`）：

- 新方块挡光 → `top = max(top, y + 1)`；
- 拆掉的正好是列顶方块 → `scanTop()` 向下重扫；
- 跳变时调 `ExtendedLightEngine#onColumnTopChanged`。

`scanTop` 只探**本列**的 16 格（每个 section 从高到低、命中即停），
不是遍历整个 section 的位图——旧实现会读到隔壁列，导致局部无故变暗。

### 4.3 惰性数组

没分配数组的 section 读取时返回隐式值：

```java
skyLight.get(sectionY) == null  → canSeeSky(x, y, z) ? 15 : 0
blockLight.get(sectionY) == null → 0
```

第一次写入与隐式值不同的值时分配数组，并用 `initSkyArray` 把当前列顶以上的格子
填成 15。因此"全在列顶之上"或"全在遮挡之下"的 section 永远不占 4 KiB。

### 4.4 引擎

`ExtendedLightEngine` 每个 `World` 一个（`MixinWorld` 的 `@Unique` 字段，
volatile 双检创建）。

- 四套队列：`skyAdd` / `skyRemove` / `blockAdd` / `blockRemove`，外加 `dirty`；
- 节点是 `int[4]`（x, y, z, value），数组实现，无对象分配；
- **加光**：`target = value - max(1, opacity)`；天空光在可见天空格直接取 15；
  方块光在发光方块上直接取其 `getLightValue`；
- **减光**：先把格子清零，邻格 `< 旧值` 的清零并继续传播，`>= 旧值` 的作为边界
  重新喂回加光队列；
- 加光节点出队时用**当前存量值**校正携带值，避免"先入队、后被减光清零"的陈旧节点
  把光又点亮；
- `tick()`：最多 8 轮 `减天空 → 减方块 → 加天空 → 加方块 → 若 dirty 非空则重算`，
  直到没有 dirty；**光队列每 tick 全部排空，不做预算分摊**（分帧会让玩家看到亮度
  在若干帧里爬向目标值，比一次略长的 tick 更难接受）。
- 引擎只处理扩展 Y：所有入口都有 `isExtendedY` 判断，纯原版 section 不会被播种。

`onColumnTopChanged(x, z, oldTop, newTop)` 处理"整列可见性变化"：

- 只对**本列 + 四个水平邻列**在 `[旧列顶, 新列顶)` 区间内的格子做"源"处理：
  变成可见天空的置 15 并入加光队列，失去天空的清零并入减光队列；
- **不看邻居**：一次列顶变化会让一整段格子同时改变，此时按邻居的旧值算 `rawLight`
  会让加减光互相打架，结果就是"该黑的不黑、数值忽高忽低"。

### 4.5 section 播种与级联收敛

`initSection(column, sectionY)` 做三件事：

1. **天空源**：该 section 内每列的第一个可见天空格置 15（仅扩展 Y）；
2. **方块光源**：遍历 `nonAir` 位图，重新播种发光方块（读档后火把不会"再被放置"
   一次，不重播就会整片缺失）；
3. **外壳**：section 的六个面各拉一次邻居光（`before > 0` 直接入队，
   `== 0` 时按邻居重算）。

播种按 section 排队，每 tick 最多 `MAX_SECTIONS_PER_TICK = 48` 个。
section 的加载顺序是任意的：先播种的 section 可能从"还没初始化"的邻居那里拉到 0。
因此 `initSection` 返回"这次是否真的让某格变化"，只要变化就把**六个邻接 section
重新排队**（`scheduledSections` 去重）。光值单调不减且上限 15，所以一定收敛；
这就是"重新进存档后不用放方块、光也正确"的原因。

### 4.6 接缝

两处接缝：`Y=0/-1` 与 `Y=255/256`。

- 扩展引擎读原版光：`getLight` 对 `0..255` 直接读 `Chunk#getLightFor`；
- 扩展引擎写原版光：跨接缝传播时 `setLight` 写 `Chunk#setLightFor` 并
  `World#notifyLightSet`，让接缝处的原版网格重新采样；
- 扩展方块放在 `Y=-1` / `Y=256` 时，额外对相邻原版 section 调
  `markBlockRangeForRenderUpdate`；
- 客户端 `MixinChunkCache` 让 `ChunkCache`（网格构建用的 `IBlockAccess`）在扩展 Y
  返回真实方块与光照，而不是"空气 / 15 / 0"；
- 客户端 `MixinWorldLight` 去掉 `getLightFromNeighborsFor` 里 `Y<0 → Y=0` 的夹取，
  否则渲染取光会拿到接缝另一侧的值。

### 4.7 客户端

客户端**不接收光照数组**，只接收方块，然后用同一套引擎、同一份方块、同一份原版光
推导出同样的结果。好处是没有"客户端拿着一份过期数组"的问题，也省掉每 section 4 KiB。

客户端收到 section 包后：`setSuspended(true)` → 逐格 `importBlock` →
`setSuspended(false)` → `rebuildTop` → `onSectionCreated`。

### 4.8 可信标记与存档时机

`ColumnLight#trusted` 的语义是"这些数组与方块数据一致，可以直接信任"。

- 新建 column：`true`（初始为空，自洽）；
- 读档：`inf:Light >= 3` → `true`；否则 `false`，丢弃数组、按 §4.5 重新播种；
- 重建过程中 `pendingSeeds` 计数，引擎在**队列彻底排空**的那一 tick 把已播种完的
  column 标回 `true`（`resolveTrust`）。

写档时的条件：

```java
light.isTrusted() && light.isSettled() && !engine.hasPendingWork()
```

不满足就不写 `inf:Light`，下次载入重新推导——这保证"重算一次"只发生在真正需要时，
而不是像旧实现那样一旦掉到 `false` 就永远写不回去。

---

## 5. 持久化

### 5.1 NBT 布局

扩展数据写在**区块自身的 NBT** 里（`AnvilChunkLoader` 的 `@Inject`），
不额外建 region 文件，因此复制/备份一个世界仍然只需复制 `region/`。

```text
Level (chunk)
├── inf:ExtendedSections : List<TagCompound>
│   └── [section]
│       ├── Y          : int
│       ├── Palette    : int[]     // 方块状态 id 调色板（[0] 恒为空气）
│       ├── Indices    : byte[4096]  // 调色板 ≤ 256 项时
│       │                int[4096]   // 否则
│       ├── SkyLight   : byte[2048]  // 可选
│       └── BlockLight : byte[2048]  // 可选
├── inf:Format : int     // 当前 3（= Palette 格式）
└── inf:Light  : int     // 当前 3，仅当光照已收敛时写入
```

要点：

- 只有光照、没有方块的 section 也会写（`SkyLight`/`BlockLight` 独立于 `Palette`）；
- `inf:ExtendedSections` 为空时三个标签都不写；
- 旧格式兼容读：`Format >= 2` 的 `Data : int[4096]`，以及最老的
  `Blocks : List<{P, S}>`（每方块一个 compound）。

### 5.2 版本与迁移

`INF_LIGHT_VERSION = 3`。任何低于当前版本（或缺失）的 `inf:Light` 都视为不可信，
数组丢弃重算。

版本历史：

| 版本 | 含义 |
|---|---|
| 1 | 早期"一律 15 / 有界 BFS"实现，数组看似合理但错误，必须作废 |
| 2 | 第一版自洽引擎（有界传播仍有缺陷） |
| 3 | 当前实现（完整 BFS + section 播种级联） |

升级后第一次进入旧存档会触发一次全量重算（按 section 排队，48 个/tick），
之后正常直读。

---

## 6. 网络

### 6.1 协议改动意味着 mod 是必需的

原版 `PacketBuffer#writeBlockPos` 用 `BlockPos.toLong()`，而它的 Y 只有 **12 位**
（`-2048..2047`），扩展坐标会被静默截断。`MixinPacketBuffer` 改成：

```text
y ∈ 0..255  →  writeBoolean(false) + writeLong(pos.toLong())
其它        →  writeBoolean(true)  + writeInt(x) + writeInt(y) + writeInt(z)
```

`BlockPos.toLong()` 本身**不改**（其它 mod 与原版内部逻辑依赖它的打包格式）。

代价：所有含方块坐标的包格式都变了，客户端必须装 mod。
`ExtendedJoinCheck` 在 `PlayerLoggedInEvent` 检查 FML 握手的 mod 列表，
缺 mod 就带提示断开，而不是让对方解包错位后卡死。单机（local channel）跳过检查。

### 6.2 数据包

| id | 包 | 内容 |
|---|---|---|
| 0 | `SPacketExtendedTileEntity` | `int x, y, z` + NBT（`getUpdateTag`） |
| 1 | `SPacketExtendedSection` | `int chunkX, chunkZ, sectionY, count` + `count × (short index, int stateId)` |

`SPacketExtendedSection` 的 `index` 就是 §3.1 的 0..4095 打包下标，一个包最多
4096 格（≈ 24.5 KiB），远小于 32 KiB 上限。

两个包都只注册在 `Side.CLIENT`。

### 6.3 发送队列

`ExtendedPacketQueue` 是**按玩家的 FIFO**，每 tick 按 256 KiB 预算发送
（约 5 MiB/s）。`PlayerChunkMapEntry` 发区块时只入队，不再同步发送。

为什么必须 FIFO：section 包是**入队那一刻的快照**。如果之后的方块变更绕过队列
直接发出，客户端会先收到新方块、再收到旧 section 快照，最后状态就回退了。
section / 方块变更 / 方块实体走同一条队列，时序由队列保证，而不是靠两条通道碰运气。

安全阀：单玩家队列超过 64 MiB 时整体丢弃并记一次错误日志（该玩家会在下一次
区块发送时重新拿到数据）。

### 6.4 单方块变更与方块实体

- `MixinWorld#notifyBlockUpdate` 对扩展 Y 完全接管：跳过 `IWorldEventListener`
  分发，直接给视距内玩家入队 `SPacketBlockChange`（它走的是被改过的
  `writeBlockPos`，扩展 Y 安全）；
- 方块实体的创建/更新走 `SPacketExtendedTileEntity`（`World#setTileEntity` 的 TAIL）；
- 客户端 `SPacketExtendedTileEntityHandler` 若本地没有该 TE，会按 NBT 重建；
- 破坏方块导致 TE 失效时，由客户端 `shouldRefresh` 语义清理。

### 6.5 客户端 section 处理

`SPacketExtendedSectionHandler` 在客户端主线程（`addScheduledTask`）执行：

1. 目标 chunk 不存在或还是 `EmptyChunk` → 丢弃（区块到达时会重发）；
2. `setSuspended(true)` → 逐格 `importBlock` → `setSuspended(false)`；
3. `rebuildTop` + `onSectionCreated`（进入 §4.5 的播种队列）。

---

## 7. 渲染

### 7.1 网格构建

`ExtendedBlockRenderHandler` 为每个 `(chunkX, chunkZ, sectionY)` 维护两个
`VertexBuffer`（不透明 / 半透明），格式与原版区块网格完全相同：

```java
new VertexBuffer(DefaultVertexFormats.BLOCK)   // 3f pos, 4ub color, 2f uv, 2s lightmap = 28B
```

重建时：

```java
ChunkCache cache = new ChunkCache(world, (x, 0, z), (x+15, 255, z+15), 1);
BlockRendererDispatcher dispatcher = Minecraft.getMinecraft().getBlockRendererDispatcher();
buf.begin(GL_QUADS, DefaultVertexFormats.BLOCK);
dispatcher.renderBlock(state, pos, cache, buf);
```

因为用的是和原版 `RenderChunk` 同一个 `BlockRendererDispatcher` 和同一个
`ChunkCache`（`MixinChunkCache` 补上了扩展 Y），顶点里的**平滑光照与 AO 天然与原版一致**。

### 7.2 可见性与生命周期

- 视距：每帧读 `Minecraft.gameSettings.renderDistanceChunks`（不写死、不加配置项）；
- 水平半径外直接跳过；**垂直方向不做距离裁剪**——玩家在 `y=400` 往下看 `y=64`
  的建筑必须还在，垂直方向交给视锥；
- 每个 section 做视锥裁剪后再绘制；
- 半透明层按距离**从远到近**排序后绘制（否则水/玻璃互相遮挡会闪）；
- 每帧最多重建 `MAX_REBUILDS_PER_FRAME = 6` 个 section；
- 每 20 帧清理一次：区块卸载、section 消失、或漂出视距的网格会被
  `deleteGlBuffers`；
- `WorldEvent.Unload`（客户端世界）时整表清空，避免切维度/退世界泄漏 VBO 或串网格。

### 7.3 绘制状态

扩展网格在 `RenderWorldLastEvent` 里绘制，需要手动接管一部分 GL 状态：

- 启用手动顶点数组（`GL_VERTEX_ARRAY` / `GL_COLOR_ARRAY` / 两套 `GL_TEXTURE_COORD_ARRAY`）；
- `glVertexPointer(3, FLOAT, 28, 0)`、`glColorPointer(4, UNSIGNED_BYTE, 28, 12)`、
  `glTexCoordPointer(2, FLOAT, 28, 16)`、lightmap `glTexCoordPointer(2, SHORT, 28, 24)`；
- **`GlStateManager.shadeModel(GL_SMOOTH)`**：前面的 pass（手、粒子、描边）可能留下
  `GL_FLAT`，那会把每个 quad 压成一个颜色，表现为"平面 + 硬边断层"。

### 7.4 方块实体

`MixinRenderGlobal` 跳过原版对扩展 Y 的 TE 渲染，改由
`ExtendedTileEntityRenderHandler` 在 `RenderWorldLastEvent` 里绘制：

- 只遍历相机附近 chunk 的 `tileEntityMap`，不扫全世界；
- 距离判定放宽到 2 倍半径（"往下看"是常态，且 TE 不做视锥裁剪）；
- 单个 TE 抛异常不影响其它 TE。

---

## 8. 原版行为扩展

| 位置 | 处理 |
|---|---|
| `World#isOutsideBuildHeight` | 扩展 Y 在主世界返回 `false`，其它维度返回 `true` |
| `World#isValid` / `isBlockLoaded` / `isAreaLoaded` | 扩展 Y 不再因 0..255 被拒 |
| `World#checkLight` | 扩展 Y 直接返回 `false`，原版 BFS 不参与 |
| `World#getLightFor` / `getLight` / `getLightFromNeighborsFor` | 扩展 Y 从 column 取 |
| `World#notifyLightSet` | 扩展 Y 不通知（没有对应的原版 render section） |
| `Chunk#getBlockState` / `setBlockState` | 扩展 Y 走 column；其它维度返回空气 / `null` |
| `Chunk#getLightFor` / `setLightFor` / `getLightSubtracted` / `canSeeSky` | 扩展 Y 走 column；其它维度返回安全默认值（原版这几个方法对越界 Y 没有保护） |
| `World#addBlockEvent` | 客户端：扩展 Y 进延迟队列，在 `updateEntities` 末尾统一触发；服务端仍走 `WorldServer` 队列（会发 `SPacketBlockAction`） |
| `ChunkCache#getBlockState` / `getLightFor` / `getLightForExt` | 网格构建时扩展 Y 返回真实值 |
| `Biome#getTemperature` | 扩展 Y 回退到生物群系默认温度 |
| `BlockDoor#canPlaceBlockAt` | 扩展世界把 `world.getHeight()` 视为无上限 |
| `BlockPistonBase#canPush` | 重新实现，不依赖 `0 < y < 255` |
| `CommandBase#parseBlockPos` | Y 允许 `Integer.MIN_VALUE..Integer.MAX_VALUE` |
| `CommandTeleport` / `CommandTP` | `±4096` 限制改为 int 范围 |
| `MinecraftServer#getBuildLimit` | 返回 `Integer.MAX_VALUE` |
| `PacketBuffer#writeBlockPos` / `readBlockPos` | 见 §6.1 |

---

## 9. 已知限制

1. **扩展方块不会让原版区域变暗**。`Y=300` 的整片平台不会让 `Y=63` 的地面变黑。
   反向是完整的：原版高度的屋顶会遮住 `Y<0`，扩展高度的方块会遮住它下方的扩展空气。
   火把天空光等跨接缝传播不受影响。
2. **接缝层没有仲裁**。扩展引擎会往 `Y=0` / `Y=255` 的原版数组写光（跨接缝传播需要），
   原版后续重算可能覆盖这些值，而扩展侧不会被告知。表现是接缝处偶发的亮度不一致。
   这是"两套引擎共享一层"的结构性代价。
3. **不支持主世界以外的维度**（§1.3）。
4. **`World#getHeight()` 仍是 256**。依赖它的第三方 mod 会把扩展区域当不存在；
   目前只补了 `getBuildLimit`、`BlockDoor`、活塞等少数入口。
5. **`getPrecipitationHeight` / 降雨 / `getTopSolidOrLiquidBlock`** 仍按 0..255，
   扩展区域不会被雨淋到，也不会参与"地表"搜索。
6. **扩展区域没有地形生成与生物生成**。
7. **mod 必须装在客户端**（§6.1）。缺 mod 会被 `ExtendedJoinCheck` 断开。
8. **`BlockPos.toLong()` 仍是 12 位 Y**。只要不走 `PacketBuffer#writeBlockPos`，
   `|y| > 2048` 会被截断；自己写代码时不要用它序列化扩展坐标。
9. **没有配置文件**。所有阈值是代码常量（视距除外，用原版设置）。
10. **未做 OptiFine / 大型优化 mod 的兼容性验证**。本模块 patch 的都是热点类
    （`Chunk`、`World`、`ChunkCache`、`RenderGlobal`、`PacketBuffer`），
    与同类 patch 的 mod 冲突风险高。

---

## 10. 验证清单

改完光照/渲染相关代码后，至少过一遍：

| 场景 | 预期 |
|---|---|
| `/setblock ~ ~300 ~ stone` | 方块出现，可破坏，掉落正常 |
| `Y=300` 搭平台后站在下面 | 下方是暗的；平台边缘有 1..15 的梯度，无"断层" |
| 从 `Y=255` 向下挖竖井到 `Y<0` 的密封房间 | 房间随竖井打通变亮，封住后逐格为 0 |
| 房间内放/拆火把 | 亮起/熄灭，边缘 1..3 的fringe 正确 |
| 重新进入存档 | 光照与退出前一致，不需要放方块触发 |
| 切换视距 8 → 16 | 扩展方块的可见距离随原版一起变化，无突然消失的边界 |
| 切到下界再回主世界 | 扩展方块正常重建，不出现别的维度的方块 |
| 两个客户端（一个不装 mod） | 缺 mod 的客户端收到提示后断开，不是卡死 |
| `/tp ~ ~600 ~` 后往下看 | 下方建筑仍然渲染 |

调试手段：`/setblock`、`/fill`、`/clone` 都已支持扩展 Y（见 §8）。

---

## 11. 文件索引

### `com.vbuser.inf`

| 文件 | 作用 |
|---|---|
| [`HeightConstants`](../../src/main/java/com/vbuser/inf/HeightConstants.java) | Y 范围、维度闸门 |
| [`InfiniteHeight`](../../src/main/java/com/vbuser/inf/InfiniteHeight.java) | `@Mod` 入口、网络通道注册 |
| [`chunk/ExtendedColumn`](../../src/main/java/com/vbuser/inf/chunk/ExtendedColumn.java) | 扩展方块存储与写入生命周期 |
| [`chunk/ColumnManager`](../../src/main/java/com/vbuser/inf/chunk/ColumnManager.java) | 从 `Chunk` 取 column |
| [`light/ColumnLight`](../../src/main/java/com/vbuser/inf/light/ColumnLight.java) | 光照数据、列顶、增量维护、存档数组 |
| [`light/ExtendedLightEngine`](../../src/main/java/com/vbuser/inf/light/ExtendedLightEngine.java) | 传播引擎、section 播种、可信收敛 |
| [`light/LightRenderHook`](../../src/main/java/com/vbuser/inf/light/LightRenderHook.java) | 引擎 → 渲染器的可选回调 |
| [`network/ExtendedPacketQueue`](../../src/main/java/com/vbuser/inf/network/ExtendedPacketQueue.java) | 按玩家 FIFO + 预算发送 |
| [`network/ExtendedJoinCheck`](../../src/main/java/com/vbuser/inf/network/ExtendedJoinCheck.java) | 缺 mod 客户端断开 |
| [`client/ExtendedBlockRenderHandler`](../../src/main/java/com/vbuser/inf/client/ExtendedBlockRenderHandler.java) | 扩展方块 VBO 渲染 |
| [`client/ExtendedTileEntityRenderHandler`](../../src/main/java/com/vbuser/inf/client/ExtendedTileEntityRenderHandler.java) | 扩展方块实体渲染 |

### `com.vbuser.mixin`

| 文件 | 作用 |
|---|---|
| [`MixinWorld`](../../src/main/java/com/vbuser/mixin/MixinWorld.java) | `setBlockState` 改道、光照入口、方块事件、方块实体同步 |
| [`MixinChunk`](../../src/main/java/com/vbuser/mixin/MixinChunk.java) | 方块/光照入口、列持有者 |
| [`MixinAnvilChunkLoader`](../../src/main/java/com/vbuser/mixin/MixinAnvilChunkLoader.java) | 扩展数据读写 |
| [`MixinChunkCache`](../../src/main/java/com/vbuser/mixin/MixinChunkCache.java) | 网格构建时的方块与光照 |
| [`MixinWorldLight`](../../src/main/java/com/vbuser/mixin/MixinWorldLight.java) | 渲染取光（去掉 Y 夹取） |
| [`MixinPlayerChunkMapEntry`](../../src/main/java/com/vbuser/mixin/MixinPlayerChunkMapEntry.java) | 区块发送时附带扩展 section |
| [`MixinPacketBuffer`](../../src/main/java/com/vbuser/mixin/MixinPacketBuffer.java) | 方块坐标协议 |
| [`MixinRenderGlobal`](../../src/main/java/com/vbuser/mixin/MixinRenderGlobal.java) | 跳过原版对扩展 Y 的 TE 渲染 |
| [`MixinBlockDoor`](../../src/main/java/com/vbuser/mixin/MixinBlockDoor.java) | 门的高度上限 |
| [`MixinBlockPistonBase`](../../src/main/java/com/vbuser/mixin/MixinBlockPistonBase.java) | 活塞推动判定 |
| [`MixinCommandBase`](../../src/main/java/com/vbuser/mixin/MixinCommandBase.java) | 命令坐标解析 |
| [`MixinCommandTeleport`](../../src/main/java/com/vbuser/mixin/MixinCommandTeleport.java) / [`MixinCommandTP`](../../src/main/java/com/vbuser/mixin/MixinCommandTP.java) | `/tp` 高度限制 |
| [`MixinMinecraftServer`](../../src/main/java/com/vbuser/mixin/MixinMinecraftServer.java) | `getBuildLimit` |
| [`MixinBiome`](../../src/main/java/com/vbuser/mixin/MixinBiome.java) | 扩展 Y 的温度采样 |

> 同目录下的 `MixinNetworkManager`、`MixinEntityPlayerSP` 属于其它功能
> （原版包聚合、强制疾跑），与无限高度无关。

---

## 12. 常量速查

| 常量 | 位置 | 值 |
|---|---|---|
| `MAX_SECTIONS_PER_TICK` | `ExtendedLightEngine` | 48 |
| `NO_TOP` | `ColumnLight` | `Integer.MIN_VALUE` |
| `INF_LIGHT_VERSION` | `MixinAnvilChunkLoader` | 3 |
| `INF_FORMAT_PALETTE` | `MixinAnvilChunkLoader` | 3 |
| `BYTES_PER_TICK` | `ExtendedPacketQueue` | 256 KiB |
| `MAX_BYTES_PER_PLAYER` | `ExtendedPacketQueue` | 64 MiB |
| `MAX_REBUILDS_PER_FRAME` | `ExtendedBlockRenderHandler` | 6 |
| `DISTANCE_MARGIN` | `ExtendedBlockRenderHandler` | 16 格 |
| `BLOCK_STRIDE` | `ExtendedBlockRenderHandler` | 28 字节 |
| `EXTENDED_DIMENSION` | `HeightConstants` | 0（主世界） |
