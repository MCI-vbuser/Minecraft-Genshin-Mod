# 真正的原因：JDK 的根证书太旧了

## 结论

你机器上的 JDK 是 **8u191（2018 年）**，它自带的 `cacerts` 里**没有 Let's Encrypt 的
ISRG Root X1 / X2**。而 Maven Central、Forge、Mojang 现在都用 Let's Encrypt 签证书，
于是 Java 里所有正常的 HTTPS 请求都会报：

```
sun.security.validator.ValidatorException: PKIX path building failed
```

手机能用、PC 不能用，就是因为手机的系统根证书是新的。

之前所有"网络炸了"的表现（构建失败、卡几分钟、`PKIX`、`Connection reset`）都是这一个原因
推出来的：Gradle 拿不到任何东西，只能靠本地缓存活着；缓存一缺就报错。

## 已做的修复

**1. 给 Gradle 换一份现代信任库**（scoped，不动 JDK）

```
tools/cnproxy/cacerts-modern.jks     ← 从 CLion 2025 自带 JBR 的 cacerts 转成 JKS
```

`gradle.properties`：

```properties
systemProp.javax.net.ssl.trustStore=C:/Users/ASUS/Desktop/mdk/tools/cnproxy/cacerts-modern.jks
systemProp.javax.net.ssl.trustStorePassword=changeit
systemProp.javax.net.ssl.trustStoreType=JKS
```

只对这个项目的 Gradle 生效，不影响系统里其它 Java 程序。

**2. 把 ForgeGradle 裸 HTTP 的超时调短**

```properties
systemProp.sun.net.client.defaultConnectTimeout=2000
systemProp.sun.net.client.defaultReadTimeout=5000
```

ForgeGradle 会绕开 Gradle 直接探测一批不存在的 URL，默认要等 15 秒；调短后这些探测
2 秒就放弃并回退到本地缓存。这是构建从 2~4 分钟降到 **40 多秒** 的关键。

**3. 撤掉了本地代理**

`tools/cnproxy` 里的中间人缓存代理已经停用（配置在 `gradle.properties` 里注释掉了）。
它和 ForgeGradle 的"探测 → 期望 404 → 本地生成"逻辑冲突，无法做到透明。
另外已把之前装进 JDK 的 `jssecacerts` 删除，JDK 恢复原样。

## 现在的结果

```
gradlew --refresh-dependencies compileJava   BUILD SUCCESSFUL   （全量重新校验，联网）
gradlew compileJava                          BUILD SUCCESSFUL in 43s / 46s
gradlew jar                                  BUILD SUCCESSFUL in 1m 19s
```

**构建已经不再依赖"网络刚好能用"**：网络差时探测快速失败并回退缓存，网络好时正常下载。

## 以后如果还慢

`cacerts-modern.jks` 也可以给整个 JDK 用（复制成
`%JAVA_HOME%\jre\lib\security\jssecacerts`），这样 IDEA 之外的 Java 工具也正常。
但没必要，Gradle 这边已经够了。

真要继续提速，只有挂一个能用的代理：

```properties
systemProp.http.proxyHost=127.0.0.1
systemProp.http.proxyPort=7897
systemProp.https.proxyHost=127.0.0.1
systemProp.https.proxyPort=7897
```

## 目录里剩下的东西

`Proxy.java` / `phone-fetch.ps1` / `fetch.bat` / `start.bat` 是这轮实验的产物，
现在都不需要了，可以整个删除 —— **但 `cacerts-modern.jks` 要保留**，
`gradle.properties` 正在用它。
