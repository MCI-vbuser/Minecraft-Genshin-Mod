# MCGit — 官方基准存档更新

> 模块：`com.vbuser.genshin.tool.mcgit` + `com.vbuser.inf.network`
>
> 目标：把云端发布的最新"原始状态"存档合并进玩家存档，同时保留玩家的游玩进度。

---

## 1. 数据模型

只有一个存档，存在三份数据：

| 名称 | 位置 | 含义 |
|---|---|---|
| base | `<游戏目录>/base_world/` | 上一次成功更新时的官方原始存档（本地基准） |
| remote | 云端 | 最新官方原始存档 |
| world | 实际存档目录 | 玩家游玩后的存档（含游玩进度） |

按区块做三方合并，规则与 git 的自动合并同源：

- `remote == base`：玩家没改过、官方也没改 → 保留 world；
- `world == base`：玩家没改、官方改了 → 采用 remote；
- 两边都改 → 保留 world（玩家进度优先）。

合并完成后，`base_world/` 中对应 region 文件被替换为 remote 版本。

### 1.1 合并范围

- 原版 `Level.Sections`（Y 0..255 的方块）；
- `Level.TileEntities`；
- **无限高度模块的 `Level.inf:ExtendedSections`（Y < 0 / Y > 255）**，按调色板格式解码后逐格三方合并；
- 合并后删除 `Level.inf:Light`，让无限高度模块在下次载入时重新推导扩展区域光照
  （旧光照数组描述的是合并前的方块，不能继续信任）。

> 非 `.mca` 文件（`level.dat`、`data/`、`advancements/` 等）不参与本模块的合并。

---

## 2. 命令

```text
/update [服务器地址]
```

- 不传地址时使用默认云端 `https://mapupdate.mciverse.dpdns.org`；
- 传地址时支持以下写法：

| 写法 | 解析结果 |
|---|---|
| `127.0.0.1:8080` | `http://127.0.0.1:8080`（回环 / 局域网自动用 http） |
| `localhost:8080/` | `http://localhost:8080` |
| `http://127.0.0.1:8080/manifest.json` | `http://127.0.0.1:8080`（粘贴完整清单地址也可以） |
| `mapupdate.mciverse.dpdns.org` | `https://mapupdate.mciverse.dpdns.org` |

命令只操作主世界（维度 0），因为无限高度与官方基准存档都只存在于主世界。

执行流程：

1. 主线程保存主世界并 `flushToDisk()`，确保 region 文件已落盘；
2. 暂停主世界自动保存 / 区块卸载（防止合并期间旧内存区块覆盖新文件）；
3. 后台线程下载清单与变动的 region，逐区块三方合并；
4. 回到主线程：把合并过的区块从内存卸载并重新从 region 读入，
   同时把 `PlayerChunkMapEntry` 指向新区块对象；
5. 对视野内的玩家重发区块：
   - 原版部分：`SPacketChunkData`；
   - 扩展部分：`ExtendedChunkSync.resyncChunk`（见 §4）；
6. 恢复自动保存。

同一时间只允许一个 `/update` 在跑（`AtomicBoolean`）。

---

## 3. 远端协议兼容

客户端同时支持两种服务端布局，按实际返回自动识别：

| 布局 | 清单 | 文件 |
|---|---|---|
| 当前云端 | `GET /manifest.json` | `GET /<相对路径>` |
| 早期独立 Java 服务端（`misc/MCGit` / `Desktop/MCGit`） | `GET /manifest` | `GET /files/<相对路径>` |

识别顺序：

1. 先请求 `/manifest.json`，404 再请求 `/manifest`；
2. 记住命中的布局；
3. 下载文件时若当前布局 404，会再尝试 `/files/` 前缀并切换为旧布局。

因此不需要重新部署旧服务端，新客户端可以直接连 `127.0.0.1:8080` 做本地测试。

---

## 4. 网络兼容（无限高度）

原版 `SPacketChunkData` 只覆盖 Y 0..255，扩展区域必须走模组自己的通道。
`SPacketExtendedSection` 在原有字段后追加了 1 字节 `mode`：

| mode | 含义 |
|---|---|
| `0 MODE_NONE` | 普通增量更新（区块首次发送时使用） |
| `1 MODE_RESET` | 整列重建开始：客户端丢弃该区块的扩展方块、光照和已烘焙网格 |
| `2 MODE_FINISH` | 整列重建结束：清理失效的扩展方块实体，重新推导列顶与光照 |

`/update` 对一个区块按 `RESET → 各 section → FINISH` 的顺序入
`ExtendedPacketQueue`（按玩家 FIFO），因此"官方删掉的扩展方块"也会在客户端消失，
而不是留下幽灵方块。

兼容性：

- 新服务端 → 旧客户端：旧客户端读完原有字段就停止，忽略末尾的 `mode` 字节，
  仍能收到扩展方块（只是没有整列重置）；
- 旧服务端 → 新客户端：没有末尾字节时 `mode` 默认为 `MODE_NONE`。

---

## 5. 自检

```bash
gradlew mcgitSelfTest
```

覆盖：

- 扩展区域三方合并（采用远端修改 / 保留玩家修改 / 冲突时玩家优先 / 远端删除）；
- 合并后 `inf:Light` 被移除；
- 地址归一化；
- 旧 Java 服务端（`/manifest` + `/files/`）与新服务端（`/manifest.json` + 直接路径）。

---

## 6. 本地联调

1. 准备一个作为"官方最新存档"的世界目录（含 `region/`）；
2. 启动旧 Java 服务端（`Desktop/MCGit` 或 `misc/MCGit`）：

   ```bash
   gradlew run
   # 默认监听 8080，服务目录为它工作目录下的 world/
   ```

3. 游戏内执行：

   ```text
   /update 127.0.0.1:8080
   ```

如果临时把 `world/` 换成静态文件服务，也可以直接用 `/update http://127.0.0.1:<端口>`；
只要根路径下同时提供清单与 region 即可。
