package com.vbuser.launcher.downloader;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import org.json.JSONObject;
import com.vbuser.launcher.ProgressServer;

public class ForgeInstaller {

    private static final String FORGE_VERSION = "1.12.2-forge-14.23.5.2854";
    private static final String BASE_VERSION = "1.12.2";
    private static final String FORGE_INSTALLER_URL = "https://files.minecraftforge.net/maven/net/minecraftforge/forge/1.12.2-14.23.5.2854/forge-1.12.2-14.23.5.2854-installer.jar";
    private static final String FORGE_INSTALLER_ALTERNATE_URL = "https://bmclapi2.bangbang93.com/maven/net/minecraftforge/forge/1.12.2-14.23.5.2854/forge-1.12.2-14.23.5.2854-installer.jar";

    public static void installForge(String gameDir) throws Exception {
        Path baseDir = Paths.get(gameDir);
        Path tempDir = baseDir.resolve("temp");
        Path installerPath = tempDir.resolve("forge_installer.jar");

        Files.createDirectories(tempDir);

        try {
            ProgressServer.updateProgress("下载Forge安装器...", 50);
            ProgressServer.updateProgressDetail("currentStep", "下载Forge安装器");

            downloadForgeInstaller(installerPath);

            ProgressServer.updateProgress("安装Forge...", 60);
            ProgressServer.updateProgressDetail("currentStep", "安装Forge");

            installForgeLegacy(installerPath, gameDir);

            ProgressServer.updateProgress("Forge安装完成", 70);
            ProgressServer.updateProgressDetail("currentStep", "Forge安装完成");

        } finally {
            try {
                if (Files.exists(installerPath)) {
                    Files.delete(installerPath);
                }
                if (Files.exists(tempDir)) {
                    Files.delete(tempDir);
                }
            } catch (IOException e) {
                System.err.println("清理临时文件时出错: " + e.getMessage());
            }
        }
    }

    private static void downloadForgeInstaller(Path installerPath) throws IOException {
        if (Files.exists(installerPath)) {
            System.out.println("Forge安装器已存在，跳过下载");
            return;
        }

        try {
            downloadFile(FORGE_INSTALLER_URL, installerPath.toString());
            System.out.println("从主URL下载Forge安装器成功");
            return;
        } catch (IOException e) {
            System.err.println("主URL下载失败: " + e.getMessage());
        }

        try {
            downloadFile(FORGE_INSTALLER_ALTERNATE_URL, installerPath.toString());
            System.out.println("从备用URL下载Forge安装器成功");
        } catch (IOException e) {
            throw new IOException("所有Forge安装器下载URL均失败", e);
        }
    }

    private static void installForgeLegacy(Path installerPath, String gameDir) throws Exception {
        try (ZipFile installer = new ZipFile(installerPath.toFile())) {
            ProgressServer.updateProgress("读取安装配置文件...", 62);

            ZipEntry profileEntry = installer.getEntry("install_profile.json");
            if (profileEntry == null) {
                throw new RuntimeException("install_profile.json not found in forge installer");
            }

            String profileContent = readZipEntry(installer, profileEntry);
            JSONObject profileJson = new JSONObject(profileContent);

            Path versionDir = Paths.get(gameDir, "versions", FORGE_VERSION);
            Files.createDirectories(versionDir);

            if (profileJson.has("install")) {
                System.out.println("[Download] 开始进行Forge安装，Legacy方式2");
                installForgeLegacyMethod2(installer, profileJson, versionDir, gameDir);
            } else {
                System.out.println("[Download] 开始进行Forge安装，Legacy方式1");
                installForgeLegacyMethod1(installer, profileJson, versionDir, gameDir, installerPath);
            }

        }
    }

    private static void installForgeLegacyMethod1(ZipFile installer, JSONObject profileJson,
                                                  Path versionDir, String gameDir, Path installerPath) throws Exception {
        ProgressServer.updateProgress("Legacy方式1安装...", 65);

        String jsonPath = profileJson.getString("json");
        ZipEntry jsonEntry = installer.getEntry(jsonPath.startsWith("/") ? jsonPath.substring(1) : jsonPath);
        if (jsonEntry == null) {
            throw new RuntimeException("JSON file not found in installer: " + jsonPath);
        }

        String jsonContent = readZipEntry(installer, jsonEntry);
        JSONObject jsonVersion = new JSONObject(jsonContent);
        jsonVersion.put("id", FORGE_VERSION);

        Path versionJsonFile = versionDir.resolve(FORGE_VERSION + ".json");
        Files.write(versionJsonFile, jsonVersion.toString(4).getBytes());
        System.out.println("创建版本JSON文件: " + versionJsonFile);

        ProgressServer.updateProgress("解压支持库文件...", 68);

        Path extractDir = Paths.get(installerPath.toString() + "_unrar");
        extractZip(installerPath.toString(), extractDir.toString());

        Path mavenDir = extractDir.resolve("maven");
        if (Files.exists(mavenDir)) {
            copyDirectory(mavenDir, Paths.get(gameDir, "libraries"));
            System.out.println("复制库文件到libraries目录");
        }

        deleteDirectory(extractDir);
    }

    private static void installForgeLegacyMethod2(ZipFile installer, JSONObject profileJson,
                                                  Path versionDir, String gameDir) throws Exception {
        ProgressServer.updateProgress("Legacy方式2安装...", 65);

        JSONObject install = profileJson.getJSONObject("install");
        String jarPath = install.getString("path");
        String filePath = install.getString("filePath");

        Path jarAddress = Paths.get(gameDir, "libraries", jarPath);
        Files.createDirectories(jarAddress.getParent());

        ZipEntry jarEntry = installer.getEntry(filePath);
        if (jarEntry == null) {
            throw new RuntimeException("JAR file not found in installer: " + filePath);
        }

        try (InputStream is = installer.getInputStream(jarEntry);
             OutputStream os = Files.newOutputStream(jarAddress)) {
            byte[] buffer = new byte[1024];
            int length;
            while ((length = is.read(buffer)) > 0) {
                os.write(buffer, 0, length);
            }
        }
        System.out.println("提取JAR文件: " + jarAddress);

        ProgressServer.updateProgress("创建版本配置...", 68);

        JSONObject versionInfo = profileJson.getJSONObject("versionInfo");
        versionInfo.put("id", FORGE_VERSION);
        if (!versionInfo.has("inheritsFrom")) {
            versionInfo.put("inheritsFrom", BASE_VERSION);
        }

        Path versionJsonFile = versionDir.resolve(FORGE_VERSION + ".json");
        Files.write(versionJsonFile, versionInfo.toString(4).getBytes());
        System.out.println("创建版本JSON文件: " + versionJsonFile);
    }

    private static String readZipEntry(ZipFile zipFile, ZipEntry entry) throws IOException {
        try (InputStream is = zipFile.getInputStream(entry);
             BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
            StringBuilder content = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line);
            }
            return content.toString();
        }
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

    private static void extractZip(String zipPath, String extractTo) throws IOException {
        Path extractDir = Paths.get(extractTo);
        Files.createDirectories(extractDir);

        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(Paths.get(zipPath)))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path outputPath = extractDir.resolve(entry.getName());

                if (entry.isDirectory()) {
                    Files.createDirectories(outputPath);
                } else {
                    Files.createDirectories(outputPath.getParent());

                    try (FileOutputStream fos = new FileOutputStream(outputPath.toFile())) {
                        byte[] buffer = new byte[1024];
                        int length;
                        while ((length = zis.read(buffer)) > 0) {
                            fos.write(buffer, 0, length);
                        }
                    }
                }
                zis.closeEntry();
            }
        }
    }

    private static void copyDirectory(Path source, Path target) throws IOException {
        Files.walk(source)
                .forEach(sourcePath -> {
                    try {
                        Path targetPath = target.resolve(source.relativize(sourcePath));
                        if (Files.isDirectory(sourcePath)) {
                            Files.createDirectories(targetPath);
                        } else {
                            Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
                        }
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
    }

    private static void deleteDirectory(Path path) throws IOException {
        if (Files.exists(path)) {
            Files.walk(path)
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        }
    }

}