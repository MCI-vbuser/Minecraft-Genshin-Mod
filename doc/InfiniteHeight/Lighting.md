# 拓展区域光照（Infinite Height Lighting）

拓展区域指 Y 不在 `0..255` 内的方块空间。原版的光照引擎把每个区块的 16 个
16 格高 section 直接存进 `Chunk.storageArrays`，并且 `World` / `Chunk` 的多处
光照入口都会把 Y 夹回 `0..255`，所以拓展区域不能复用原版数组，必须单独实现。

本实现的目标：

1. 拓展区域的天空光、方块光、遮挡关系与原版 `0..255` 区域一致；
2. 光在 `Y=255/256`、`Y=0/-1` 两个交界处可以双向正常传播；
3. 与原版一致地持久化、同步给客户端，并能在旧存档上自动迁移。

---

## 1. 数据

### 1.1 每个 column 一份光照数据

[`ColumnLight`](../../src/main/java/com/vbuser/inf/light/ColumnLight.java)
挂在 [`ExtendedColumn`](../../src/main/java/com/vbuser/inf/chunk/ExtendedColumn.java)
上（也就是每个 `Chunk` 一个）：

- `Int2ObjectOpenHashMap<NibbleArray> skyLight`：按 section Y 存天空光，每节 2048 字节；
- `Int2ObjectOpenHashMap<NibbleArray> blockLight`：按 section Y 存方块光；
- `int[] topMap`：每个 `(x, z)` 列在拓展 Y 上最高的**挡光**方块 + 1。

光照 section 与方块 section 分开存：一个全是空气的 section 也可能有光照值
（例如矿洞、屋檐下），必须能独立保存/同步，不能因为 section 里没有方块就丢掉。

### 1.2 列顶（column top）

原版用 `Chunk.heightMap`（0..255）判断 `canSeeSky`。拓展光照需要"整列"的列顶：

```text
top(x, z) = max(topMap[x, z], vanillaHeightMap[x, z] /* 0 表示没有挡光方块 */)
```

因此：

- 原版高度上的屋顶会遮住 `Y < 0` 的拓展区域；
- `Y > 255` 的方块会遮住它下方的拓展空气；
- 没有挡光方块的列返回 `Integer.MIN_VALUE`（`NO_TOP`），整列都算"能看见天空"。

`topMap` 在区块从磁盘读入时由 `rebuildTop()` 扫描重建，之后随方块变化增量维护。

### 1.3 数组默认值（惰性分配）

没有分配 nibble 数组的 section 读取时按 `canSeeSky ? 15 : 0` 处理。数组只在"要写的
值与当前隐式值不同"时才创建，创建时由 `initSkyArray` 把当前列顶以上的格子填成 15。

由此得到一条贯穿始终的不变式：

> **没有数组的 section，每个格子的值都等于它的隐式值。**

因此整列重建时不需要给"全在列顶之上"或"全在遮挡之下"的 section 分配 4 KiB 数组，
一次重建只在真正有梯度的十几格上分配内存。

### 1.4 方块光

方块光没有列顶概念，缺失数组的默认值是 0，写 0 不会分配数组。

---

## 2. 传播

### 2.1 自己的光照引擎，而不是原版 `checkLightFor`

[`ExtendedLightEngine`](../../src/main/java/com/vbuser/inf/light/ExtendedLightEngine.java)
是拓展区域唯一的传播实现，按 World 各一个：

- **加光 BFS**：队列里的节点携带"该格当前的亮度"，向六邻扩散时
  `target = value - max(1, opacity)`；天空光在可见天空的格子上直接取 15，
  方块光在发光方块上直接取它的 `getLightValue`。
- **减光 BFS**：先把格子清零，再把旧亮度丢进队列；邻格亮度 `< 旧值` 的一律清零并继续传播，
  `>= 旧值` 的作为"边界"重新喂回加光队列。
- 两套队列都有 **每 tick 预算**（4096 节点），一次大范围重算分摊到若干 tick，
  不会出现单 tick 卡顿。
- 加光节点出队时会用**当前存量值**校正携带值，避免"先入队、后被减光清零"的
  陈旧节点把光又点亮。

原版 `World.checkLightFor` 的 BFS 半径被硬编码为 17 格（`updateRange`），
它只能配合"对两个高度之间的每个格子都调用一次"才能覆盖整个柱体；
在无限高度下这意味着上千次调用，而且**深处仍然算不到**——这正是旧实现里
"把唯一的漏光口封住之后下面依然亮着"、以及"亮度断层"的原因。
`MixinWorld` 现在对拓展 Y 直接屏蔽 `checkLight`，两套引擎不会互相踩。

### 2.2 列顶变化：只处理"源"，其余交给 BFS

天空光的源就是"能看见天空"（`canSeeSky`）的格子。列顶变化时，
[`onColumnTopChanged`](../../src/main/java/com/vbuser/inf/light/ExtendedLightEngine.java)
对**本列和四个水平邻列**在 `[旧列顶, 新列顶)` 区间内的格子做一件事：

- 变成能看天的格子：置 15 并入加光队列；
- 失去天空的格子：清零并入减光队列。

关键是**不看邻居**：一次列顶变化会让一整段格子同时改变，
如果这时按邻居的旧亮度算 `rawLight`，减光和加光会互相打架，
结果就是"该黑的不黑、数值忽高忽低"。
清零之后的加光由减光 BFS 的边界重新发起，所以光真正从别处来的格子会被恢复，
只是来自这条被堵住的路的光会消失。

### 2.3 方块光

方块光同样走这套 BFS：`World.setBlockState` 触发 `onBlockChanged`，
引擎重算该格与其六邻的 `rawLight`；发光方块是源，移除发光方块就是一次减光。

### 2.4 区块 / section 载入：按 section 播种

[`ColumnLight.onChunkLoaded`](../../src/main/java/com/vbuser/inf/light/ColumnLight.java)
只重建列顶。光照是否可信由存档里的 `inf:Light` 版本号决定：

- 版本匹配：直接使用存档里的数组（它们由本引擎维护，是自洽的）；
- 版本不匹配或缺失：**丢弃数组**，为每个有方块的 section 排一次
  [`initSection`](../../src/main/java/com/vbuser/inf/light/ExtendedLightEngine.java)——
  把可见天空的格子设成 15、其余格子清零，再由 BFS 从 section 边界重新拉光进来。

**版本号是必须的**：旧版本（以及更早那次"一律 15"的实现）写下的数组会带上旧版本号，
如果沿用，看起来"很合理"的光照数据其实是错的——表现就是"改了代码却看不到任何变化"。
当前版本为 `2`。

初始化按 section 排队、每 tick 处理若干个，不会因为一次载入大量区块而卡住。
客户端的 section 由 `SPacketExtendedSection` 送达后走同一条 `initSection` 路径，
两端用同样的方块、同样的原版光圈，算出同样的结果。

### 2.5 渲染侧

`ChunkCache` 是原版构建区块网格时使用的 `IBlockAccess`，它对 `0..255` 以外的 Y
一律返回"空气 / 天空光 15 / 方块光 0"。客户端 mixin
[`MixinChunkCache`](../../src/main/java/com/vbuser/mixin/MixinChunkCache.java)
在 Y 越界时从缓存的 chunk 里取真实方块和光照（`getBlockState` / `getLightFor` /
`getLightForExt`），不额外访问世界/区块表。

`ExtendedBlockRenderHandler` 每帧渲染拓展方块时也**改用 `ChunkCache`** 作为
`IBlockAccess`，而不是 `World`：平滑光照每个面要采样 4 个邻格，
用 `World` 每次都要查区块表，方块一多帧率就塌了。

接缝层（`Y=-1` / `Y=256`）的光照变化会影响相邻原版方块的亮度贴图，
`ColumnLight` 在改动这两层的格子时会额外 `notifyLightSet` 原版侧那一格；
拓展方块在接缝处的放置/破坏（`ExtendedColumn.setBlock`）也会对相邻原版 section
调用 `markBlockRangeForRenderUpdate`。

### 2.6 客户端

`World.getLightFromNeighborsFor` 是渲染取光的入口（`getCombinedLight` 会调用它），
客户端 mixin 只去掉 `Y < 0 → 0` 的夹取。拓展方块由
`ExtendedBlockRenderHandler` 每帧渲染，顶点亮度直接来自这套光照值。

---

## 3. 持久化与网络

### 3.1 存档

拓展 section 的 NBT 结构（`inf:ExtendedSections`）在原有 `Y` / `Palette` /
`Indices` 之外新增：

- `SkyLight`：2048 字节天空光 nibble 数组；
- `BlockLight`：2048 字节方块光 nibble 数组。

只有光照、没有方块的 section 也会写入（不再要求 `Palette` 存在）。
区块级新增 `inf:Light`（int，当前为 2），见 2.4。

### 3.2 旧存档迁移

旧存档没有 `inf:Light`，或者带的是旧版本号，一律按不可信处理：
丢弃数组、重新按 section 播种，下一次保存就会带上当前版本号。
旧版本那份"看起来正常"的错误光照不会再有第二次机会。

### 3.3 客户端同步

`SPacketExtendedSection` 只发方块，不发光照：两端跑同一套引擎、同一份方块和
同一份原版光，算出来的结果一致，也就不存在"客户端拿着一份过期数组"的问题。
没有方块、只有光照的 section 不需要发送，客户端自己会算。

客户端先静默导入方块（`ColumnLight.setSuspended` 期间引擎跳过这一列，
并直接写 section 数组而不是逐格 `setBlockState`），导入完成后重建列顶、
把该 section 排进初始化队列。

客户端自己也会跑同一套光照代码：原版 `SPacketBlockChange`（拓展 Y 由
`MixinWorld` 自行发送）触发的 `World.setBlockState` 会在客户端本地重算光照，
与原版客户端行为一致。

---

## 4. 验证方式

- **对照暴力实现**：在专用服务器上挖一条竖井通到 `Y<0` 的密封房间里，
  分别比较"竖井开着 / 封住 / 放火把 / 重新打开"四种状态下
  整个 41×37×41 区域的天空光和方块光与"暴力洪水填充"的结果——
  逐格比较，四种状态全部 0 处不一致；封住后房间内部逐格为 0。
- **性能**：同一场景下 `tick` 平均 0.3 ms、最大 3.4 ms（50 个 tick）；
  真实客户端里 25×25 的平台 + 竖井房间，帧率 160~300。


---

## 4. 已知边界

原版 `0..255` 区域的光照数组仍然由原版逻辑维护：拓展区域中的方块**不会**
让原版区域变暗（例如在 `Y=300` 搭一整片平台，`Y=63` 的地面仍然是 15）。
反向是完整的：原版高度的屋顶会遮住 `Y<0`，拓展高度的方块会遮住它下方的拓展
空气。这样做的原因是原版区域的光照/列顶/重光照路径必须保持原样，否则会牵动
`heightMap`、`generateSkylightMap`、`relightBlock` 等大量原版行为；而
"光在拓展区域与原版区域之间双向传播"（火把、天空光、洞穴开口）不受影响。

`Y < 0` 的空白空间是真正连通的空气，所以一列被挖空到基岩以下时，光会在
`Y<0` 的水平面上扩散（与原版"洞穴开口"同样的行为），不会出现"只有一列是亮的"。
