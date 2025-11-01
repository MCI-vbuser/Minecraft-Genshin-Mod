// MinecraftLauncher.java - 修复版本
package com.vbuser.launcher;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.json.JSONObject;
import org.json.JSONArray;

public class MinecraftLauncher {

    private static final String GAME_DIR = ".minecraft";
    private static final String FORGE_VERSION = "1.12.2-forge-14.23.5.2854";
    private static final String BASE_VERSION = "1.12.2";

    public static void launchMinecraft(String playerName, String uuid) {
        try {
            ProgressServer.updateProgress("准备启动Minecraft...", 98);
            ProgressServer.updateProgressDetail("currentStep", "启动游戏进程");

            Path baseDir = Paths.get(GAME_DIR);
            Path versionDir = baseDir.resolve("versions").resolve(FORGE_VERSION);
            Path versionJsonFile = versionDir.resolve(FORGE_VERSION + ".json");

            if (!Files.exists(versionJsonFile)) {
                throw new RuntimeException("版本配置文件不存在: " + versionJsonFile);
            }

            String javaExecutable = getJavaExecutable();
            List<String> command = buildLaunchCommand(javaExecutable, versionJsonFile, playerName, uuid);

            ProgressServer.updateProgressDetail("javaPath", javaExecutable);
            ProgressServer.updateProgressDetail("playerName", playerName);
            ProgressServer.updateProgressDetail("version", FORGE_VERSION);

            System.out.println("启动命令: " + String.join(" ", command));

            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.directory(baseDir.toFile());
            processBuilder.redirectErrorStream(true);

            Process process = processBuilder.start();

            ProgressServer.setMinecraftProcess(process);
            ProgressServer.updateProgress("Minecraft已启动!", 100);
            ProgressServer.updateProgressDetail("currentStep", "游戏运行中");

            Thread outputThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        System.out.println("[Minecraft] " + line);
                    }
                } catch (IOException e) {
                    System.err.println("输出读取错误: " + e.getMessage());
                }
            });
            outputThread.start();

            System.out.println("Minecraft launched.");

        } catch (Exception e) {
            ProgressServer.updateProgress("启动失败: " + e.getMessage(), 0);
            ProgressServer.updateProgressDetail("error", e.getMessage());
            throw new RuntimeException("启动Minecraft时发生错误", e);
        }
    }

    private static String getJavaExecutable() {
        String javaHome = System.getProperty("java.home");
        String os = System.getProperty("os.name").toLowerCase();

        Path javaPath = Paths.get(javaHome, "bin", "java");
        if (os.contains("win")) {
            javaPath = Paths.get(javaHome, "bin", "java.exe");
        }

        if (!Files.exists(javaPath)) {
            throw new RuntimeException("找不到Java可执行文件: " + javaPath);
        }

        return javaPath.toAbsolutePath().toString();
    }

    private static List<String> buildLaunchCommand(String javaExecutable, Path versionJsonFile, String playerName, String uuid) throws IOException {
        String jsonContent = new String(Files.readAllBytes(versionJsonFile));
        JSONObject versionJson = new JSONObject(jsonContent);

        List<String> command = new ArrayList<>();
        command.add(javaExecutable);

        addJvmArguments(command, versionJson);

        command.add("-cp");
        command.add(buildClassPath(versionJson));

        String mainClass = versionJson.getString("mainClass");
        command.add(mainClass);

        addGameArguments(command, versionJson, playerName, uuid);

        return command;
    }

    private static void addJvmArguments(List<String> command, JSONObject versionJson) {
        command.add("-XX:+UseG1GC");
        command.add("-XX:-UseAdaptiveSizePolicy");
        command.add("-XX:-OmitStackTraceInFastThrow");
        command.add("-Djdk.lang.Process.allowAmbiguousCommands=true");

        command.add("-Dfml.ignoreInvalidMinecraftCertificates=true");
        command.add("-Dfml.ignorePatchDiscrepancies=true");
        command.add("-Dlog4j2.formatMsgNoLookups=true");

        command.add("-XX:HeapDumpPath=MojangTricksIntelDriversForPerformance_javaw.exe_minecraft.exe.heapdump");
        command.add("-Xmx2G");
        command.add("-Xms1G");

        Path nativesDir = Paths.get(GAME_DIR, "versions", BASE_VERSION, "natives");
        command.add("-Djava.library.path=" + nativesDir.toAbsolutePath());

        command.add("-Dminecraft.launcher.brand=vbuser-launcher");
        command.add("-Dminecraft.launcher.version=1.0");

        if (versionJson.has("arguments") && versionJson.getJSONObject("arguments").has("jvm")) {
            JSONArray jvmArgs = versionJson.getJSONObject("arguments").getJSONArray("jvm");
            for (int i = 0; i < jvmArgs.length(); i++) {
                String arg = jvmArgs.getString(i);
                arg = arg.replace("${libraryDirectory}", Paths.get(GAME_DIR, "libraries").toAbsolutePath().toString())
                        .replace("${version_name}", FORGE_VERSION)
                        .replace("${classpath_separator}", File.pathSeparator)
                        .replace("${natives_directory}", nativesDir.toAbsolutePath().toString());
                command.add(arg);
            }
        }
    }

    private static String buildClassPath(JSONObject versionJson) {
        Set<String> classPathEntries = new LinkedHashSet<>();

        Path baseJar = Paths.get(GAME_DIR, "versions", BASE_VERSION, BASE_VERSION + ".jar");
        if (Files.exists(baseJar)) {
            classPathEntries.add(baseJar.toAbsolutePath().toString());
            System.out.println("添加原版JAR: " + baseJar);
        } else {
            System.err.println("警告: 原版JAR文件不存在: " + baseJar);
        }

        Path forgeJar = Paths.get(GAME_DIR, "libraries", "net", "minecraftforge", "forge", "1.12.2-14.23.5.2854", "forge-1.12.2-14.23.5.2854.jar");
        if (Files.exists(forgeJar)) {
            classPathEntries.add(forgeJar.toAbsolutePath().toString());
            System.out.println("添加Forge JAR: " + forgeJar.getFileName());
        }

        if (versionJson.has("libraries")) {
            JSONArray libraries = versionJson.getJSONArray("libraries");
            for (int i = 0; i < libraries.length(); i++) {
                JSONObject library = libraries.getJSONObject(i);

                if (library.has("rules") && !shouldUseLibrary(library.getJSONArray("rules"))) {
                    continue;
                }

                if (library.has("downloads") && library.getJSONObject("downloads").has("artifact")) {
                    JSONObject artifact = library.getJSONObject("downloads").getJSONObject("artifact");
                    String path = artifact.getString("path");
                    Path fullPath = Paths.get(GAME_DIR, "libraries", path);
                    if (Files.exists(fullPath)) {
                        classPathEntries.add(fullPath.toAbsolutePath().toString());
                        System.out.println("添加库文件: " + fullPath.getFileName());
                    } else {
                        System.err.println("错误: 库文件不存在: " + fullPath);
                        throw new RuntimeException("必需的库文件不存在: " + fullPath);
                    }
                }
            }
        }

        addAdditionalLibraries(classPathEntries);

        String classPath = String.join(File.pathSeparator, classPathEntries);
        System.out.println("类路径构建完成，包含 " + classPathEntries.size() + " 个jar文件");
        return classPath;
    }

    private static void addAdditionalLibraries(Set<String> classPathEntries) {
        String[] additionalLibraries = {
                "org/ow2/asm/asm-debug-all/5.2/asm-debug-all-5.2.jar",
                "net/minecraft/launchwrapper/1.12/launchwrapper-1.12.jar",
                "org/jline/jline/3.5.1/jline-3.5.1.jar",
                "com/typesafe/akka/akka-actor_2.11/2.3.3/akka-actor_2.11-2.3.3.jar",
                "com/typesafe/config/1.2.1/config-1.2.1.jar",
                "org/scala-lang/scala-actors-migration_2.11/1.1.0/scala-actors-migration_2.11-1.1.0.jar",
                "org/scala-lang/scala-compiler/2.11.1/scala-compiler-2.11.1.jar",
                "org/scala-lang/plugins/scala-continuations-library_2.11/1.0.2_mc/scala-continuations-library_2.11-1.0.2_mc.jar",
                "org/scala-lang/plugins/scala-continuations-plugin_2.11.1/1.0.2_mc/scala-continuations-plugin_2.11.1-1.0.2_mc.jar",
                "org/scala-lang/scala-library/2.11.1/scala-library-2.11.1.jar",
                "org/scala-lang/scala-parser-combinators_2.11/1.0.1/scala-parser-combinators_2.11-1.0.1.jar",
                "org/scala-lang/scala-reflect/2.11.1/scala-reflect-2.11.1.jar",
                "org/scala-lang/scala-swing_2.11/1.0.1/scala-swing_2.11-1.0.1.jar",
                "org/scala-lang/scala-xml_2.11/1.0.2/scala-xml_2.11-1.0.2.jar",
                "lzma/lzma/0.0.1/lzma-0.0.1.jar",
                "java3d/vecmath/1.5.2/vecmath-1.5.2.jar",
                "net/sf/trove4j/trove4j/3.0.3/trove4j-3.0.3.jar",
                "org/apache/maven/maven-artifact/3.5.3/maven-artifact-3.5.3.jar",
                "net/sf/jopt-simple/jopt-simple/5.0.3/jopt-simple-5.0.3.jar",
                "com/mojang/patchy/1.3.9/patchy-1.3.9.jar",
                "oshi-project/oshi-core/1.1/oshi-core-1.1.jar",
                "net/java/dev/jna/jna/4.4.0/jna-4.4.0.jar",
                "net/java/dev/jna/platform/3.4.0/platform-3.4.0.jar",
                "com/ibm/icu/icu4j-core-mojang/51.2/icu4j-core-mojang-51.2.jar",
                "com/paulscode/codecjorbis/20101023/codecjorbis-20101023.jar",
                "com/paulscode/codecwav/20101023/codecwav-20101023.jar",
                "com/paulscode/libraryjavasound/20101123/libraryjavasound-20101123.jar",
                "com/paulscode/librarylwjglopenal/20100824/librarylwjglopenal-20100824.jar",
                "com/paulscode/soundsystem/20120107/soundsystem-20120107.jar",
                "io/netty/netty-all/4.1.9.Final/netty-all-4.1.9.Final.jar",
                "com/google/guava/guava/21.0/guava-21.0.jar",
                "org/apache/commons/commons-lang3/3.5/commons-lang3-3.5.jar",
                "commons-io/commons-io/2.5/commons-io-2.5.jar",
                "commons-codec/commons-codec/1.10/commons-codec-1.10.jar",
                "net/java/jinput/jinput/2.0.5/jinput-2.0.5.jar",
                "net/java/jutils/jutils/1.0.0/jutils-1.0.0.jar",
                "com/google/code/gson/gson/2.8.0/gson-2.8.0.jar",
                "com/mojang/authlib/1.5.25/authlib-1.5.25.jar",
                "com/mojang/realms/1.10.22/realms-1.10.22.jar",
                "org/apache/commons/commons-compress/1.8.1/commons-compress-1.8.1.jar",
                "org/apache/httpcomponents/httpclient/4.3.3/httpclient-4.3.3.jar",
                "commons-logging/commons-logging/1.1.3/commons-logging-1.1.3.jar",
                "org/apache/httpcomponents/httpcore/4.3.2/httpcore-4.3.2.jar",
                "it/unimi/dsi/fastutil/7.1.0/fastutil-7.1.0.jar",
                "org/apache/logging/log4j/log4j-api/2.8.1/log4j-api-2.8.1.jar",
                "org/apache/logging/log4j/log4j-core/2.8.1/log4j-core-2.8.1.jar",
                "org/lwjgl/lwjgl/lwjgl/2.9.4-nightly-20150209/lwjgl-2.9.4-nightly-20150209.jar",
                "org/lwjgl/lwjgl/lwjgl_util/2.9.4-nightly-20150209/lwjgl_util-2.9.4-nightly-20150209.jar",
                "com/mojang/text2speech/1.10.3/text2speech-1.10.3.jar"
        };

        for (String libPath : additionalLibraries) {
            Path fullPath = Paths.get(GAME_DIR, "libraries", libPath);
            if (Files.exists(fullPath)) {
                classPathEntries.add(fullPath.toAbsolutePath().toString());
                System.out.println("添加额外库: " + fullPath.getFileName());
            } else {
                System.err.println("警告: 额外库文件不存在: " + fullPath);
            }
        }
    }

    private static boolean shouldUseLibrary(JSONArray rules) {
        String os = System.getProperty("os.name").toLowerCase();

        for (int i = 0; i < rules.length(); i++) {
            JSONObject rule = rules.getJSONObject(i);
            String action = rule.getString("action");

            if (rule.has("os")) {
                JSONObject osRule = rule.getJSONObject("os");
                String ruleOs = osRule.getString("name");
                boolean osMatches = false;

                if (ruleOs.equals("windows") && os.contains("win")) {
                    osMatches = true;
                } else if (ruleOs.equals("osx") && os.contains("mac")) {
                    osMatches = true;
                } else if (ruleOs.equals("linux") && (os.contains("nix") || os.contains("nux"))) {
                    osMatches = true;
                }

                if (!osMatches) {
                    if ("allow".equals(action)) {
                        return false;
                    } else if ("disallow".equals(action)) {
                        continue;
                    }
                }
            }

            if ("allow".equals(action)) {
                return true;
            } else if ("disallow".equals(action)) {
                return false;
            }
        }

        return true;
    }

    private static void addGameArguments(List<String> command, JSONObject versionJson, String playerName, String uuid) {
        command.add("--username");
        command.add(playerName);
        command.add("--version");
        command.add(FORGE_VERSION);
        command.add("--gameDir");
        command.add(new File(GAME_DIR).getAbsolutePath());
        command.add("--assetsDir");
        command.add(new File(GAME_DIR, "assets").getAbsolutePath());
        command.add("--assetIndex");
        command.add("1.12");
        command.add("--uuid");
        command.add(uuid);
        command.add("--accessToken");
        command.add("0");
        command.add("--userType");
        command.add("mojang");
        command.add("--versionType");
        command.add("release");

        command.add("--tweakClass");
        command.add("net.minecraftforge.fml.common.launcher.FMLTweaker");

        if (versionJson.has("arguments") && versionJson.getJSONObject("arguments").has("game")) {
            JSONArray gameArgs = versionJson.getJSONObject("arguments").getJSONArray("game");
            for (int i = 0; i < gameArgs.length(); i++) {
                String arg = gameArgs.getString(i);
                if (!arg.contains("tweakClass")) { // 避免重复添加tweakClass
                    arg = arg.replace("${auth_player_name}", playerName)
                            .replace("${version_name}", FORGE_VERSION)
                            .replace("${game_directory}", new File(GAME_DIR).getAbsolutePath())
                            .replace("${assets_root}", new File(GAME_DIR, "assets").getAbsolutePath())
                            .replace("${assets_index_name}", "1.12")
                            .replace("${auth_uuid}", uuid)
                            .replace("${auth_access_token}", "0")
                            .replace("${user_type}", "mojang")
                            .replace("${version_type}", "release");
                    command.add(arg);
                }
            }
        }
    }

    @SuppressWarnings("all")
    public static void test() {
        String testPlayerName = "TestPlayer";
        String testUUID = "12345678-1234-1234-1234-123456789012";

        launchMinecraft(testPlayerName, testUUID);
    }
}