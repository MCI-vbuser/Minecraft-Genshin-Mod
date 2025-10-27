package com.vbuser.launcher;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.json.JSONObject;
import org.json.JSONArray;

public class MinecraftLauncher {

    private static final String GAME_DIR = ".minecraft";
    private static final String VERSION = "1.12.2";

    public static void launchMinecraft(String playerName, String uuid) {
        try {
            ProgressServer.updateProgress("准备启动Minecraft...", 98);
            ProgressServer.updateProgressDetail("currentStep", "启动游戏进程");

            Path baseDir = Paths.get(GAME_DIR);
            Path versionDir = baseDir.resolve("versions").resolve(VERSION);
            Path versionJsonFile = versionDir.resolve(VERSION + ".json");

            if (!Files.exists(versionJsonFile)) {
                throw new RuntimeException("版本配置文件不存在: " + versionJsonFile);
            }

            String javaExecutable = getJavaExecutable();
            List<String> command = buildLaunchCommand(javaExecutable, versionJsonFile, playerName, uuid);

            ProgressServer.updateProgressDetail("javaPath", javaExecutable);
            ProgressServer.updateProgressDetail("playerName", playerName);
            ProgressServer.updateProgressDetail("version", VERSION);

            System.out.println("启动命令: " + String.join(" ", command));

            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.directory(new File("."));
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
        command.add("-Xmx2G");
        command.add("-Xms1G");

        Path nativesDir = Paths.get(GAME_DIR, "versions", VERSION, "natives");
        command.add("-Djava.library.path=" + nativesDir.toAbsolutePath());

        command.add("-Dminecraft.launcher.brand=vbuser-launcher");
        command.add("-Dminecraft.launcher.version=1.0");

        if (versionJson.has("arguments") && versionJson.getJSONObject("arguments").has("jvm")) {
            JSONArray jvmArgs = versionJson.getJSONObject("arguments").getJSONArray("jvm");
            for (int i = 0; i < jvmArgs.length(); i++) {
                String arg = jvmArgs.getString(i);
                arg = arg.replace("${libraryDirectory}", Paths.get(GAME_DIR, "libraries").toAbsolutePath().toString())
                        .replace("${version_name}", VERSION)
                        .replace("${classpath_separator}", File.pathSeparator)
                        .replace("${natives_directory}", nativesDir.toAbsolutePath().toString());
                command.add(arg);
            }
        }
    }

    private static String buildClassPath(JSONObject versionJson) {
        Set<String> classPathEntries = new LinkedHashSet<>();

        Path mainJar = Paths.get(GAME_DIR, "versions", VERSION, VERSION + ".jar");
        if (Files.exists(mainJar)) {
            classPathEntries.add(mainJar.toAbsolutePath().toString());
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
                    }
                }
            }
        }

        String classPath = String.join(File.pathSeparator, classPathEntries);
        System.out.println("类路径构建完成，包含 " + classPathEntries.size() + " 个jar文件");
        return classPath;
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
                    continue;
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
        command.add(VERSION);
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

        if (versionJson.has("arguments") && versionJson.getJSONObject("arguments").has("game")) {
            JSONArray gameArgs = versionJson.getJSONObject("arguments").getJSONArray("game");
            for (int i = 0; i < gameArgs.length(); i++) {
                String arg = gameArgs.getString(i);
                arg = arg.replace("${auth_player_name}", playerName)
                        .replace("${version_name}", VERSION)
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

    @SuppressWarnings("all")
    public static void test() {
        String testPlayerName = "TestPlayer";
        String testUUID = "12345678-1234-1234-1234-123456789012";

        launchMinecraft(testPlayerName, testUUID);
    }
}