# BGMAPI 使用文档

> 包名：`com.vbuser.genshin.tool.al`
> 入口：`com.vbuser.genshin.tool.al.BGMAPI`

---

## 1. 功能概览

| 能力 | 方法 |
| --- | --- |
| 本地播放 / 停止 | `playLocal(...)` / `stopLocal()` |
| 对指定玩家播放 / 停止 | `playTo(...)` / `stopTo(...)` |
| 全员播放 / 停止 | `playToAll(...)` / `stopToAll()` |
| 状态查询 | `isPlaying()` / `getCurrent()` / `isWaiting(...)` |
| 音量 | `setVolume(...)` / `getVolume()` |
| 预下载 | `preload(...)` / `getDownloadProgress(...)` |
| 文件管理 | `getAssetDir()` / `has(...)` / `list()` / `install(...)` |
| 事件 | `addListener(BgmListener)` |

---

## 2. 文件放在哪

| 端 | 目录 | 获取方式 |
| --- | --- | --- |
| 服务端 | `<服务器工作目录>/bgm/` | `BGMAPI.getAssetDir()` |
| 客户端 | `<minecraft 目录>/bgm/` | `BGMAPI.getAssetDir()` |

- `fileName` 是**相对路径**，例如 `"genshin/liyue.ogg"`；
- 含 `..`、盘符、反斜杠、以 `/` 开头/结尾的名字会**在两端都被拒绝**（旧版本只有请求包做了检查）；
- 客户端本地没有文件时会自动向服务器请求，服务器分块下发后缓存到客户端 `bgm/` 目录；
- 后缀名与解码由 paulscode `SoundSystem` 的流式源决定，推荐 `.ogg` / `.wav`。

用 mod 自带资源安装：

```java
try (InputStream in = MyMod.class.getResourceAsStream("/assets/mymod/bgm/theme.ogg")) {
    BGMAPI.install(in, "mymod/theme.ogg");   // 返回 false 表示名字非法或写入失败
}
```

---

## 3. 端语义（重要）

旧版的 `playBGM(String)` 在客户端是"本地播放"、在服务端是"广播给所有人"——同一个调用换端就换语义。现在每个方法都显式声明了端：

| 方法 | 允许的端 | 违规时 |
| --- | --- | --- |
| `playLocal` / `stopLocal` / `setVolume` / `preload` 等客户端方法 | 仅客户端 | 抛 `IllegalStateException` |
| `playTo` / `playToAll` / `stopTo` / `stopToAll` | 需要本地服务器（专用服务器或单人） | 抛 `IllegalStateException` |

端判定使用环境信息（物理端 / 是否存在本地服务器），**不使用** `getEffectiveSide()`。后者依赖线程组，在 mod 自己创建的线程里恒为 CLIENT，会让服务端逻辑误入客户端代码路径。

单人游戏里两种都可用：`playLocal` 直接播给本机，`playToAll` 走服务器广播（结果对本机相同）。

---

## 4. 方法表

### 4.1 播放

| 方法 | 返回 | 说明 |
| --- | --- | --- |
| `playLocal(String fileName)` | `void` | 本机播放；文件缺失时自动下载，下载完成后自动开始 |
| `playLocal(String fileName, float volume)` | `void` | 同上，并设置并记住音量 |
| `stopLocal()` | `void` | 本机停止；不会触发 `onEnd` |
| `playTo(EntityPlayerMP player, String fileName)` | `boolean` | 送给单个玩家；文件不存在 / 校验和算不出来时返回 `false` 且不发送 |
| `playTo(Collection<EntityPlayerMP> players, String fileName)` | `int` | 送给一批玩家，返回实际发送人数 |
| `playToAll(String fileName)` | `int` | 送给所有在线玩家，返回实际发送人数 |
| `stopTo(EntityPlayerMP player)` | `void` | |
| `stopTo(Collection<EntityPlayerMP> players)` | `void` | |
| `stopToAll()` | `int` | 返回目标人数 |

服务端方法发送前会计算 MD5 并随包下发，客户端据此判断本地缓存是否过期；MD5 计算失败时**不会**再发送一个"无校验和"的包（旧版本会静默使用过期缓存）。

### 4.2 状态

| 方法 | 端 | 说明 |
| --- | --- | --- |
| `isPlaying()` | 客户端 | 当前确实有声音在播（不是"已请求"） |
| `getCurrent()` | 客户端 | 当前曲目名，或 `null` |
| `isWaiting(String fileName)` | 客户端 | 该文件是否正在下载等待播放 |
| `getDownloadProgress(String fileName)` | 客户端 | `0.0 ~ 1.0`，已存在时为 `1.0` |
| `isAvailable()` | 客户端 | 播放引擎是否可用（反射取 `SoundSystem` 失败时为 `false`；每次调用都会重试，音效系统就绪后自动变为可用） |
| `hasServer()` / `isClient()` | 任意 | 端查询 |

### 4.3 文件

| 方法 | 说明 |
| --- | --- |
| `getAssetDir()` | 本端 bgm 目录 |
| `has(String fileName)` | 本端是否有该文件（服务端=服务器资源，客户端=本地缓存） |
| `list()` | 本端所有曲目名（相对路径，已排序） |
| `install(InputStream, String)` | 把流写入 bgm 目录，适合 mod 内置资源 |

### 4.4 预下载

```java
BGMAPI.preload("genshin/liyue.ogg", ok -> {
    if (!ok) {
        player.sendMessage(new TextComponentString("§cBGM 下载失败"));
    }
});
```

- 文件已存在：立即以 `true` 回调；
- 否则发请求，完成时 `true`、失败/超时 `false`；
- 等待期间可用 `getDownloadProgress(name)` 画进度条。

---

## 5. 事件

```java
public final class MyBgmHud implements BgmListener {
    @Override public void onStart(String fileName) { }                 // 真正开始播放
    @Override public void onReady(String fileName) { }                 // 下载完成/本地已存在
    @Override public void onProgress(String fileName, float progress) { } // 百分比变化时
    @Override public void onFail(String fileName, String reason) { }   // 失败原因
    @Override public void onEnd(String fileName) { }                   // 自然播放结束
}

BGMAPI.addListener(new MyBgmHud());
```

所有回调都在**客户端主线程**执行，可以直接操作 GUI。

`onEnd` 只在自然播完时触发，显式 `stopLocal()` 不会触发；检测依赖客户端 tick，最长延迟约 1 tick。

---

## 6. 失败行为

| 场景 | 现在的行为 |
| --- | --- |
| 文件名非法 | 两端都拒绝；客户端触发 `onFail` |
| 客户端没有文件 | 自动请求服务器；服务器没有该文件时，客户端 15 秒内收不到数据 → `onFail("timed out ...")` |
| 播放引擎不可用 | `isAvailable()` 为 `false`，播放请求触发 `onFail` |
| 服务端文件读取失败 | `playTo*` 返回 `false` / `0`，并记录 WARN 日志 |
| 下载超时 | `onFail` + 清理缓冲，不再永久卡在 `isWaiting` |
| 并发请求过多 | 服务端拒绝多余请求并记录 WARN（有界线程池） |

---

## 7. 音量

```java
BGMAPI.setVolume(0.5f);   // 0.0 ~ 1.0，超出会被截断
float v = BGMAPI.getVolume();
```

音量是**客户端本地状态**，会应用到当前曲目并保留给下一首；服务端下发曲目时不会覆盖它。

---

## 8. 迁移指南

| 旧写法 | 新写法 | 说明 |
| --- | --- | --- |
| `playBGM(name)`（端相关语义） | `playLocal(name)` / `playToAll(name)` | 旧方法保留为 `@Deprecated` |
| `playBGM(player, name)` | `playTo(player, name)` | 现在返回 `boolean` |
| `playBGMToAll(name)` | `playToAll(name)` | 现在返回 `int` |
| `stopBGM()` / `stopBGM(player)` / `stopBGMToAll()` | `stopLocal()` / `stopTo(...)` / `stopToAll()` | |
| 自己拿 `BGMFileManager.getFile(...)` 拼路径 | `BGMAPI.getAssetDir()` + `has/list/install` | |
| 自己在 GUI 里判断"是否在播" | `isPlaying()` / `getCurrent()` | |
| 自己统计下载进度 | `getDownloadProgress` / `onProgress` | |

`BGMFileManager` 仍是底层实现，但正常情况下不需要直接使用；它现在会在解析路径时做目录逃逸校验。

---

## 9. 已知限制

- 不支持播放列表 / 队列 / 循环：需要循环请在 `onEnd` 里再次 `playLocal`。
- 不支持定位（seek）。
- 一次只有一个流式源在播，重复调用会先停旧的。
- 客户端必须装本 mod；服务端没有本 mod 时不会提供下载。
- 下载分块为 16 KiB，服务端每块间隔 50 ms（避免挤占游戏流量），大文件下载会明显慢于局域网带宽。
