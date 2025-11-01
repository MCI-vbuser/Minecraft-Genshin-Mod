package com.vbuser.launcher.downloader;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.vbuser.launcher.ProgressServer;
import org.json.JSONArray;
import org.json.JSONObject;

public class MinecraftLibraryDownloader {

    private static final String VERSION_MANIFEST_URL = "https://launchermeta.mojang.com/mc/game/version_manifest.json";

    public static void downloadMinecraft1122() {
        try {
            String version = "1.12.2";
            String gameDir = ".minecraft";

            System.out.println("Start downloading Minecraft " + version + " ...");
            ProgressServer.updateProgress("开始下载 Minecraft " + version, 45);
            ProgressServer.updateProgressDetail("currentStep", "初始化下载");

            Path baseDir = Paths.get(gameDir);
            Path versionDir = baseDir.resolve("versions").resolve(version);
            Path librariesDir = baseDir.resolve("libraries");
            Path nativesDir = versionDir.resolve("natives");

            Files.createDirectories(versionDir);
            Files.createDirectories(librariesDir);
            Files.createDirectories(nativesDir);

            ProgressServer.updateProgress("获取版本清单...", 50);
            ProgressServer.updateProgressDetail("currentStep", "获取版本信息");
            String versionManifest = downloadString(VERSION_MANIFEST_URL);
            String versionUrl = getVersionUrl(versionManifest, version);

            ProgressServer.updateProgress("下载版本JSON...", 55);
            ProgressServer.updateProgressDetail("currentStep", "下载版本配置");
            String versionJsonStr = downloadString(versionUrl);
            Path versionJsonFile = versionDir.resolve(version + ".json");
            Files.write(versionJsonFile, versionJsonStr.getBytes());
            System.out.println("Save JSON file: " + versionJsonFile);

            JSONObject versionJson = new JSONObject(versionJsonStr);

            ProgressServer.updateProgress("下载客户端JAR...", 60);
            ProgressServer.updateProgressDetail("currentStep", "下载游戏主文件");
            JSONObject downloads = versionJson.getJSONObject("downloads");
            JSONObject client = downloads.getJSONObject("client");
            String clientUrl = client.getString("url");
            Path clientJar = versionDir.resolve(version + ".jar");
            downloadFile(clientUrl, clientJar.toString());
            System.out.println("Save Client JAR file: " + clientJar);

            ProgressServer.updateProgress("下载库文件...", 65);
            ProgressServer.updateProgressDetail("currentStep", "下载依赖库");
            downloadLibraries(versionJson, gameDir);

            ProgressServer.updateProgress("下载资源文件...", 90);
            ProgressServer.updateProgressDetail("currentStep", "下载游戏资源");
            downloadAssets(versionJson, gameDir);

            System.out.println("Minecraft " + version + " downloaded successfully.");
            ProgressServer.updateProgress("下载完成", 95);
            ProgressServer.updateProgressDetail("currentStep", "准备启动游戏");

        } catch (Exception e) {
            ProgressServer.updateProgress("下载出错: " + e.getMessage(), 0);
            ProgressServer.updateProgressDetail("error", e.getMessage());
            throw new RuntimeException("下载失败", e);
        }
    }

    public static void downloadForgeLibraries(String gameDir) {
        try {
            String forgeVersion = "1.12.2-forge-14.23.5.2854";
            Path forgeVersionDir = Paths.get(gameDir, "versions", forgeVersion);
            Path forgeJsonFile = forgeVersionDir.resolve(forgeVersion + ".json");

            if (!Files.exists(forgeJsonFile)) {
                throw new RuntimeException("Forge版本配置文件不存在: " + forgeJsonFile);
            }

            String forgeJsonStr = new String(Files.readAllBytes(forgeJsonFile));
            JSONObject forgeJson = new JSONObject(forgeJsonStr);

            ProgressServer.updateProgress("下载Forge库文件...", 65);
            ProgressServer.updateProgressDetail("currentStep", "下载Forge依赖库");

            downloadLibraries(forgeJson, gameDir);

        } catch (Exception e) {
            ProgressServer.updateProgress("下载Forge库文件出错: " + e.getMessage(), 0);
            ProgressServer.updateProgressDetail("error", e.getMessage());
            throw new RuntimeException("下载Forge库文件失败", e);
        }
    }

    private static void downloadLibraries(JSONObject versionJson, String gameDir) throws IOException {
        Path librariesDir = Paths.get(gameDir, "libraries");
        Path versionDir = Paths.get(gameDir, "versions", versionJson.getString("id"));
        Path nativesDir = versionDir.resolve("natives");

        Files.createDirectories(nativesDir);

        JSONArray libraries = versionJson.getJSONArray("libraries");
        int totalLibraries = libraries.length();
        int downloadedLibraries = 0;
        int failedLibraries = 0;

        System.out.println("需要下载 " + totalLibraries + " 个库文件");

        for (int i = 0; i < libraries.length(); i++) {
            JSONObject library = libraries.getJSONObject(i);

            if (library.has("rules") && !shouldDownloadLibrary(library.getJSONArray("rules"))) {
                downloadedLibraries++;
                continue;
            }

            try {
                JSONObject downloadsObj = library.getJSONObject("downloads");

                if (downloadsObj.has("artifact")) {
                    JSONObject artifact = downloadsObj.getJSONObject("artifact");
                    String path = artifact.getString("path");
                    String url = artifact.getString("url");
                    Path libraryPath = librariesDir.resolve(path);

                    if (!Files.exists(libraryPath)) {
                        downloadLibrary(url, libraryPath.toString());
                        System.out.println("下载库文件: " + libraryPath.getFileName());
                    } else {
                        System.out.println("库文件已存在: " + libraryPath.getFileName());
                    }
                }

                if (downloadsObj.has("classifiers")) {
                    JSONObject classifiers = downloadsObj.getJSONObject("classifiers");
                    String nativeClassifier = getNativeClassifier();
                    if (classifiers.has(nativeClassifier)) {
                        JSONObject nativeArtifact = classifiers.getJSONObject(nativeClassifier);
                        String nativePath = nativeArtifact.getString("path");
                        String nativeUrl = nativeArtifact.getString("url");
                        Path nativeLibraryPath = librariesDir.resolve(nativePath);

                        if (!Files.exists(nativeLibraryPath)) {
                            downloadLibrary(nativeUrl, nativeLibraryPath.toString());
                            System.out.println("下载Native库: " + nativeLibraryPath.getFileName());

                            extractNativeLibrary(nativeLibraryPath.toString(), nativesDir.toString());
                        } else {
                            System.out.println("Native库已存在: " + nativeLibraryPath.getFileName());
                            extractNativeLibrary(nativeLibraryPath.toString(), nativesDir.toString());
                        }
                    }
                }

                downloadedLibraries++;

            } catch (Exception e) {
                failedLibraries++;
                System.err.println("下载库文件失败: " + library + " - " + e.getMessage());
            }

            int progress = 65 + (int)((downloadedLibraries * 25.0) / totalLibraries);
            ProgressServer.updateProgress(
                    "下载库文件 (" + downloadedLibraries + "/" + totalLibraries + ")",
                    progress
            );
            ProgressServer.updateProgressDetail("librariesProgress", downloadedLibraries + "/" + totalLibraries);
        }

        System.out.println("库文件下载完成: " + downloadedLibraries + " 成功, " + failedLibraries + " 失败");
    }

    private static String getVersionUrl(String versionManifest, String version) {
        JSONObject manifest = new JSONObject(versionManifest);
        JSONArray versions = manifest.getJSONArray("versions");

        String versionUrl = null;
        for (int i = 0; i < versions.length(); i++) {
            JSONObject versionObj = versions.getJSONObject(i);
            if (version.equals(versionObj.getString("id"))) {
                versionUrl = versionObj.getString("url");
                break;
            }
        }

        if (versionUrl == null) {
            throw new RuntimeException("找不到版本 " + version + " 的信息");
        }
        return versionUrl;
    }

    private static boolean shouldDownloadLibrary(JSONArray rules) {
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

    private static String getNativeClassifier() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            return "natives-windows";
        } else if (os.contains("mac")) {
            return "natives-osx";
        } else {
            return "natives-linux";
        }
    }

    private static void downloadAssets(JSONObject versionJson, String gameDir) throws IOException {
        if (!versionJson.has("assetIndex")) {
            return;
        }

        JSONObject assetIndex = versionJson.getJSONObject("assetIndex");
        String assetIndexUrl = assetIndex.getString("url");
        String assetIndexId = assetIndex.getString("id");

        Path assetsDir = Paths.get(gameDir, "assets");
        Path indexesDir = assetsDir.resolve("indexes");
        Path objectsDir = assetsDir.resolve("objects");

        Files.createDirectories(indexesDir);
        Files.createDirectories(objectsDir);

        Path assetIndexFile = indexesDir.resolve(assetIndexId + ".json");
        if (!Files.exists(assetIndexFile)) {
            downloadFile(assetIndexUrl, assetIndexFile.toString());
        }

        System.out.println("下载资源索引文件: " + assetIndexFile);

        AssetDownloader.downloadAssets(versionJson, gameDir);
    }

    private static void extractNativeLibrary(String zipPath, String extractTo) {
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(Paths.get(zipPath)))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (!entry.isDirectory() &&
                        (entry.getName().endsWith(".dll") ||
                                entry.getName().endsWith(".so") ||
                                entry.getName().endsWith(".dylib"))) {

                    Path outputPath = Paths.get(extractTo, entry.getName());
                    Files.createDirectories(outputPath.getParent());

                    try (FileOutputStream fos = new FileOutputStream(outputPath.toFile())) {
                        byte[] buffer = new byte[1024];
                        int length;
                        while ((length = zis.read(buffer)) > 0) {
                            fos.write(buffer, 0, length);
                        }
                    }
                    System.out.println("提取native文件: " + outputPath.getFileName());
                }
                zis.closeEntry();
            }
        } catch (IOException e) {
            System.err.println("提取native文件时出错: " + e.getMessage());
        }
    }

    private static void downloadLibrary(String url, String filePath) throws IOException {
        File file = new File(filePath);
        if (file.exists()) {
            return;
        }

        Files.createDirectories(Paths.get(filePath).getParent());
        downloadFile(url, filePath);
    }

    private static void downloadFile(String fileUrl, String savePath) throws IOException {
        URL url = new URL(fileUrl);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(30000);
        connection.setReadTimeout(30000);

        try (InputStream in = connection.getInputStream();
             FileOutputStream out = new FileOutputStream(savePath)) {

            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static String downloadString(String urlString) throws IOException {
        URL url = new URL(urlString);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");

        try (InputStream in = connection.getInputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(in))) {

            StringBuilder content = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line);
            }
            return content.toString();
        } finally {
            connection.disconnect();
        }
    }
}