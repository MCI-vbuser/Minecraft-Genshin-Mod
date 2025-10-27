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
            String versionUrl = getString(versionManifest, version);

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
            JSONArray libraries = versionJson.getJSONArray("libraries");
            int totalLibraries = libraries.length();
            int downloadedLibraries = 0;

            for (int i = 0; i < libraries.length(); i++) {
                JSONObject library = libraries.getJSONObject(i);

                if (library.has("rules")) {
                    JSONArray rules = library.getJSONArray("rules");
                    if (!shouldDownloadLibrary(rules)) {
                        downloadedLibraries++;
                        continue;
                    }
                }

                JSONObject downloadsObj = library.getJSONObject("downloads");

                if (downloadsObj.has("artifact")) {
                    JSONObject artifact = downloadsObj.getJSONObject("artifact");
                    String path = artifact.getString("path");
                    String url = artifact.getString("url");
                    Path libraryPath = librariesDir.resolve(path);
                    downloadLibrary(url, libraryPath.toString());
                }

                if (downloadsObj.has("classifiers")) {
                    JSONObject classifiers = downloadsObj.getJSONObject("classifiers");
                    String nativeClassifier = getNativeClassifier();
                    if (classifiers.has(nativeClassifier)) {
                        JSONObject nativeArtifact = classifiers.getJSONObject(nativeClassifier);
                        String nativePath = nativeArtifact.getString("path");
                        String nativeUrl = nativeArtifact.getString("url");
                        Path nativeLibraryPath = librariesDir.resolve(nativePath);
                        downloadLibrary(nativeUrl, nativeLibraryPath.toString());

                        extractNativeLibrary(nativeLibraryPath.toString(), nativesDir.toString());
                    }
                }

                downloadedLibraries++;
                int progress = 65 + (int)((downloadedLibraries * 25.0) / totalLibraries);
                ProgressServer.updateProgress(
                        "下载库文件 (" + downloadedLibraries + "/" + totalLibraries + ")",
                        progress
                );
                ProgressServer.updateProgressDetail("librariesProgress", downloadedLibraries + "/" + totalLibraries);
            }

            ProgressServer.updateProgress("下载资源文件...", 90);
            ProgressServer.updateProgressDetail("currentStep", "下载游戏资源");
            downloadAssets(versionJson, gameDir);

            System.out.println("Minecraft " + version + " native downloaded successfully.");
            ProgressServer.updateProgress("下载完成", 95);
            ProgressServer.updateProgressDetail("currentStep", "准备启动游戏");

        } catch (Exception e) {
            ProgressServer.updateProgress("下载出错: " + e.getMessage(), 0);
            ProgressServer.updateProgressDetail("error", e.getMessage());
            throw new RuntimeException("Err", e);
        }
    }

    private static String getString(String versionManifest, String version) {
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
            throw new RuntimeException("Couldn't found info of " + version + ".");
        }
        return versionUrl;
    }

    private static boolean shouldDownloadLibrary(JSONArray rules) {
        try {
            for (int i = 0; i < rules.length(); i++) {
                JSONObject rule = rules.getJSONObject(i);
                if (rule.has("action")) {
                    String action = rule.getString("action");
                    if ("disallow".equals(action)) {
                        return false;
                    }
                }
            }
            return true;
        } catch (Exception e) {
            return true;
        }
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
        downloadFile(assetIndexUrl, assetIndexFile.toString());

        System.out.println("Downloaded index file: " + assetIndexFile);

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
                    System.out.println("Extract native file: " + outputPath);
                }
                zis.closeEntry();
            }
        } catch (IOException e) {
            System.err.println("Error occurred when extracting native file: " + e.getMessage());
        }
    }

    private static void downloadLibrary(String url, String filePath) throws IOException {
        File file = new File(filePath);
        if (file.exists()) {
            System.out.println("File skipped: " + filePath);
            return;
        }

        Files.createDirectories(Paths.get(filePath).getParent());
        downloadFile(url, filePath);
        System.out.println("Downloaded library file: " + filePath);
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