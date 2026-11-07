# CGAPI 使用文档

> 包名：`com.vbuser.genshin.tool.cg`
> 入口：`com.vbuser.genshin.tool.cg.CGAPI`

---

## 1. 功能概览

| 能力 | 方法 |
| --- | --- |
| 本地播放 / 停止 / 暂停 | `playLocal(...)` / `stopLocal()` / `pauseLocal()` / `resumeLocal()` |
| 对指定玩家播放 / 停止 | `playTo(...)` / `stopTo(...)` |
| 全员播放 / 停止 | `playToAll(...)` / `stopToAll()` |
| 状态 / 进度 | `isPlaying()` / `isPaused()` / `getProgress()` / `getDurationMs()` / `getCurrent()` |
| 下载进度 / 预下载 | `getDownloadProgress(...)` / `preload(...)` |
| 自定义渲染 | `setAutoOverlay(...)` / `getTextureId()` / `updateTexture()` / `getVideoWidth()` / `getVideoHeight()` |
| 文件管理 | `getCGDir()` / `has(...)` / `list()` / `install(...)` |
| 事件 | `addListener(CgListener)` |

---

## 2. 文件放在哪

| 端 | 目录 | 获取方式 |
| --- | --- | --- |
| 服务端 | `<服务器工作目录>/cg/` | `CGAPI.getCGDir()` |
| 客户端 | `<minecraft 目录>/cg/` | `CGAPI.getCGDir()` |

- `fileName` 是**相对路径**，例如 `"story/chapter1.mp4"`；
- 含 `..`、盘符、反斜杠的名字在**服务端和客户端两端都会被拒绝**；
- 客户端本地没有文件时向服务器请求，服务器 16 KiB 分块下发并缓存到本地 `cg/`。

> **安全修复**：旧版本 `RequestCGFilePacket` 完全不校验文件名，任何装了 mod 的客户端都能让服务器把 `../../server.properties` 之类的文件按块发给自己。现在 `CGFileServerHandler` 统一走 `CGFileManager.resolve(...)`，解析后的规范路径必须落在 `cg/` 目录内，否则拒绝并记录 WARN。

用 mod 自带资源安装：

```java
try (InputStream in = MyMod.class.getResourceAsStream("/assets/mymod/cg/opening.mp4")) {
    CGAPI.install(in, "mymod/opening.mp4");
}
```

---

## 3. 端语义（重要）

| 方法 | 允许的端 | 违规时 |
| --- | --- | --- |
| `playLocal` / `stopLocal` / `pauseLocal` / `resumeLocal` / 渲染与状态查询 | 仅客户端 | 抛 `IllegalStateException` |
| `playTo` / `playToAll` / `stopTo` / `stopToAll` | 需要本地服务器（专用服务器或单人） | 抛 `IllegalStateException` |

旧版 `playCG(String)` 在客户端是本地播放、在服务端是静默广播，容易误用；现在每个方法都显式声明端，端判定使用环境信息而不是 `getEffectiveSide()`。

---

## 4. 方法表

### 4.1 播放

| 方法 | 返回 | 说明 |
| --- | --- | --- |
| `playLocal(String fileName)` | `void` | 本机播放；本地没有文件时先下载，完成后自动播放 |
| `stopLocal()` | `void` | 本机停止，恢复播放前的 GUI |
| `pauseLocal()` / `resumeLocal()` | `void` | 暂停 / 继续（画面保留最后一帧） |
| `playTo(EntityPlayerMP player, String fileName)` | `boolean` | 文件不存在时返回 `false`，不发送 |
| `playTo(Collection<EntityPlayerMP> players, String fileName)` | `int` | 返回实际发送人数 |
| `playToAll(String fileName)` | `int` | |
| `stopTo(EntityPlayerMP)` / `stopTo(Collection)` / `stopToAll()` | `void` / `int` | |

### 4.2 状态与进度

| 方法 | 说明 |
| --- | --- |
| `isPlaying()` | 当前是否有视频会话（暂停时仍为 `true`） |
| `isPaused()` | 是否暂停 |
| `getProgress()` | `0.0 ~ 1.0` |
| `getDurationMs()` | 总时长（毫秒），未知为 `0` |
| `getCurrent()` | 当前文件名，或 `null` |
| `getDownloadProgress(String)` | 下载进度 `0.0 ~ 1.0` |
| `isAvailable()` | JavaFX 解码器是否已初始化 |

### 4.3 自定义渲染

默认会打开一个全屏覆盖层并铺满屏幕（等比裁剪）。如果你想在自己的 GUI 里画（书本、小窗、画中画）：

```java
CGAPI.setAutoOverlay(false);      // 关闭内置全屏层
CGAPI.playLocal("story/chapter1.mp4");

// 在你的 GuiScreen.drawScreen 里：
CGAPI.updateTexture();            // 每帧上传最新一帧
int tex = CGAPI.getTextureId();   // -1 表示还没准备好
if (tex != -1) {
    GlStateManager.bindTexture(tex);
    // 用 Tessellator 画一个带纹理的四边形：
    // 纹理坐标 v 同样需要翻转/裁剪，参见 PlayerEventHandler 的 cover 算法
}
```

| 方法 | 说明 |
| --- | --- |
| `setAutoOverlay(boolean)` / `isAutoOverlay()` | 是否使用内置全屏覆盖层 |
| `getTextureId()` | 当前帧 GL 纹理 id，未就绪为 `-1` |
| `updateTexture()` | 上传最新解码帧；每帧调用一次 |
| `isTextureReady()` | 纹理是否已创建 |
| `getVideoWidth()` / `getVideoHeight()` | 视频原始分辨率 |

### 4.4 文件

| 方法 | 说明 |
| --- | --- |
| `getCGDir()` | 本端 cg 目录 |
| `has(String)` | 本端是否有该文件 |
| `list()` | 本端所有 cg 文件名（已排序） |
| `install(InputStream, String)` | 把流写入 cg 目录 |

### 4.5 预下载

```java
CGAPI.preload("story/chapter2.mp4", ok -> {
    if (!ok) { player.sendMessage(new TextComponentString("§cCG 下载失败")); }
});
```

---

## 5. 事件

```java
CGAPI.addListener(new CgListener() {
    @Override public void onLoading(String f, float p) { }  // 下载进度
    @Override public void onReady(String f) { }             // 文件就绪
    @Override public void onStart(String f) { }             // 开始播放
    @Override public void onFail(String f, String reason) { } // 失败原因
    @Override public void onEnd(String f) { }               // 自然播放结束
});
```

所有回调都在**客户端主线程**执行。`onEnd` 只在自然播完时触发，显式 `stopLocal()` 不触发。

---

## 6. 失败行为

| 场景 | 现在的行为 |
| --- | --- |
| 文件名非法 | 两端拒绝；客户端 `onFail` |
| 本地无文件 | 请求服务器；服务器没有该文件时，客户端 20 秒无新分块 → `onFail("the server stopped sending the file")` |
| 保存失败 | `onFail` |
| 解码器未就绪 / 文件损坏 | `onFail`，失败文本里带 `[source=<URI>]`（旧版只打印到 stderr） |
| 服务端并发请求过多 | 拒绝多余请求并记录 WARN（有界线程池） |

### 排查用日志

每次播放都会先打一行 INFO：

```
[FXVideoPlayer] Opening file:/C:/.../run/cg/test.mp4 (10802752 bytes)
[FXVideoPlayer] Ready: 1280x720 (xxxxx ms)
```

如果只看到 `Opening` 然后 `MediaPlayer error: ... ERROR_MEDIA_INVALID`，说明文件路径没问题、是 JavaFX 打不开这个流。

> **路径必须规范化**：dev 运行时游戏目录是 `"."`（dev 启动器不传 `--gameDir`），所以 `<mcDataDir>/cg` 的原始绝对路径是 `...\run\.\cg\x.mp4`，`File.toURI()` 会把它变成 `file:/.../run/./cg/x.mp4`。旧版传的是相对路径 `cg/x.mp4`，URI 里没有 `/./` 段。`CGFileManager.resolve()` 现在统一返回规范化路径（`getCanonicalFile`），保证交给 JavaFX 的 URI 与旧版一致。

> **`MOD HAS DIRECT REFERENCE System.exit()` 警告是既有现象**：JavaFX 在第一次 `Scene.pulse()` 时会尝试退出工具包，Forge 拦截并打印该警告。它从旧版本起就存在，**不影响播放**，不要把它当成 `ERROR_MEDIA_INVALID` 的原因。

### 已知环境问题：JavaFX 8 视频管线

`Media` 能读出容器信息（`-Djfxmedia.loglevel=debug` 会打印 `** Frame size changed (1280, 720)`），但一旦挂上 `MediaView`（即需要视频 sink）就报 `ERROR_MEDIA_INVALID`。原因是 JRE 自带的 JavaFX 媒体栈里没有可用的视频 sink：

- `jfxmedia.dll` 只链接 `gstreamer-lite.dll`；
- `gstreamer-lite.dll` 里只有音频 sink（`directsoundsink` / `pulsesink` / `gstappsink`），没有视频 sink，也没有 H.264 解码器。

这与 mod 无关：用仓库里的 [`tools/cgprobe/CgProbe.java`](../../tools/cgprobe/CgProbe.java) 可以在**不含任何 mod 代码**的纯 JavaFX 程序里复现同样的错误：

```
java -cp tools/cgprobe/out CgProbe run/cg/test.mp4
# RESULT: ERROR MediaException: ... ERROR_MEDIA_INVALID
```

要恢复 CG 播放，只能换掉解码路径（例如引入纯 Java 的 H.264 解码器），或修复运行环境的 JavaFX 媒体栈。

### 解码后端（Windows 走 Media Foundation）

从这一版开始，CG 的解码后端按平台选择，接口仍是 `IVideoFrameProvider`：

| 平台 | 后端 | 说明 |
| --- | --- | --- |
| Windows | `MFVideoPlayer`（Media Foundation + JNA） | 与 Windows Media Player 同一个解码引擎，支持 DXVA 硬解；不经过 JavaFX/DirectShow/GStreamer |
| 其他平台 | `FXVideoPlayer`（JavaFX） | 保持原样 |

选择逻辑在 `VideoPlaybackManager.createPlayer`：Windows 上先试 MF，`start()` 抛异常（打不开文件 / 初始化失败）就自动回退到 JavaFX，并打一条 WARN。日志里会明确写出用了哪个：

```
[VideoPlayback] Decoder: Media Foundation
[MFVideoPlayer] Opened file:/.../run/cg/test.mp4 (1280x720, 269167 ms)
```

或：

```
[VideoPlayback] Decoder: JavaFX
```

依赖：`libs/jna-5.14.0.jar`（1.8 MB，已在 `build.gradle` 里 `embed`）。

**JavaFX 不再在启动时初始化。** `Main.init` 里原来会 `new JFXPanel()`，在 JavaFX 原生栈起不来的机器上（典型：Linux 缺 GTK2）会直接 NPE 并把整个游戏拖垮：

```
java.lang.NullPointerException
    at com.sun.glass.ui.Application.run(Application.java:146)
    at com.sun.javafx.tk.quantum.QuantumToolkit.startup(...)
    at javafx.embed.swing.JFXPanel.<init>(...)
```

现在 JavaFX 只在**第一次真正要播放 CG** 时才启动，失败会被捕获、只记一次日志、不再重试，游戏继续运行：

```
[FXVideoPlayer] JavaFX toolkit is unavailable on this platform
[VideoPlayback] CG xxx failed: JavaFX is not available on this platform (no JavaFX natives?)
```

Linux 上要让 JavaFX 路径可用，需要 GTK2 和几个 X 库（Ubuntu 24.04）：

```
sudo apt install libgtk2.0-0t64 libxtst6 libxxf86vm1 libgl1
```

装完仍不行的话，Linux 需要独立的解码后端（GStreamer/FFmpeg），目前没有实现。

### 音频（MF 后端）

音频由 `MFAudioPlayer` 负责，和视频同一个文件、同一条时间线：

- 用 Media Foundation 把音轨解成 **PCM**（16bit），通过 Java Sound 的 `SourceDataLine` 播放；
- 音频线程等视频的**第一帧时间戳**确定后才开始写数据，并把早于该时间戳的采样丢掉，因此音画从同一时间点起步；
- `pause()` / `resume()` / `stop()` 会同步作用到音频；
- 音频是**可选**的：没有音轨、没有声卡、格式不支持、MF 打不开，都只打一条 WARN，画面照常播放。

日志：

```
[MFAudioPlayer] Playing audio 44100Hz 2ch 16bit
[MFAudioPlayer] First PCM packet: 4096 bytes
```

**已知取舍**：音频走的是独立的 Java Sound 输出，音量跟 Minecraft 的"主音量"设置无关（固定 100%）。要接进 MC 的音量系统需要改成 OpenAL 流式源，可作为后续改进。




---

## 7. 覆盖层与 GUI 恢复

`GuiVideoOverlay` 会记住打开前的界面，关闭时恢复：

- 从 GUI 里启动 CG，结束后回到原来的 GUI（旧版会直接关到 `null`）；
- 玩家按 ESC 关闭覆盖层 → 停止视频并触发 `onOverlayClosed`；
- `doesGuiPauseGame()` 为 `false`，单人游戏里视频不会暂停游戏。

---

## 8. 迁移指南

| 旧写法 | 新写法 | 说明 |
| --- | --- | --- |
| `playCG(name)`（端相关语义） | `playLocal(name)` / `playToAll(name)` | 旧方法保留为 `@Deprecated` |
| `playCG(player, name)` | `playTo(player, name)` | 现在返回 `boolean` |
| `playCGToAll(name)` | `playToAll(name)` | 现在返回 `int` |
| `stopCG()` / `stopCG(player)` / `stopCGToAll()` | `stopLocal()` / `stopTo(...)` / `stopToAll()` | |
| 直接使用 `VideoPlaybackManager.playVideo(...)` | `CGAPI.playLocal(...)` | `playVideo` 仍保留为兼容别名 |
| 自己处理 `GuiVideoOverlay` | 保持默认，或用 `setAutoOverlay(false)` 自绘 | |

---

## 9. 已知限制

- 不支持跳转（seek）、倍速、音量控制。
- 不支持同时播放多个 CG。
- 依赖 JavaFX（`jfxrt.jar` 已内嵌）；服务端不会初始化 JavaFX（`Main.init` 只在客户端初始化解码器）。
- 下载为 16 KiB 分块、无节流，大文件会占用较多带宽；建议 CG 使用短片段。
- 客户端与服务器必须使用同一版本 mod。
