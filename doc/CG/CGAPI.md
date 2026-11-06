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
| 解码器未就绪 / 文件损坏 | `onFail`（旧版只打印到 stderr） |
| 服务端并发请求过多 | 拒绝多余请求并记录 WARN（有界线程池） |

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
