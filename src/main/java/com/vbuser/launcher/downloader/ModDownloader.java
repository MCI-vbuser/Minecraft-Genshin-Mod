package com.vbuser.launcher.downloader;

import com.vbuser.launcher.ProgressServer;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

public class ModDownloader {
    private static final String GAME_DIR = ".minecraft";
    private static final String MODS_DIR = "mods";

    private static final Map<String, String> MOD_URLS = new HashMap<>();

    static {
        MOD_URLS.put("geckolib-forge-1.12.2-3.0.31.jar","https://cdn.modrinth.com/data/8BmcQJ2H/versions/PePYVhnE/geckolib-forge-1.12.2-3.0.31.jar");
        MOD_URLS.put("mcef-1.12.2-1.33.jar","https://github.com/CinemaMod/mcef/releases/download/1.12.2-1.33/mcef-1.12.2-1.33.jar");
    }

    public static void downloadAdditionalMods() throws Exception {
        if (MOD_URLS.isEmpty()) {
            System.out.println("没有配置需要下载的模组");
            ProgressServer.updateProgressDetail("mods", "无额外模组");
            return;
        }

        Path modsDir = Paths.get(GAME_DIR, MODS_DIR);
        Files.createDirectories(modsDir);

        int downloaded = 0;
        int skipped = 0;

        for (Map.Entry<String, String> entry : MOD_URLS.entrySet()) {
            String modFileName = entry.getKey();
            String modUrl = entry.getValue();
            Path modPath = modsDir.resolve(modFileName);

            if (Files.exists(modPath)) {
                System.out.println("模组已存在，跳过: " + modFileName);
                skipped++;
                continue;
            }

            try {
                System.out.println("下载模组: " + modFileName);
                downloadMod(modUrl, modPath.toString());
                downloaded++;

                int progress = 85 + (int)((downloaded * 10.0) / MOD_URLS.size());
                ProgressServer.updateProgress("下载模组 (" + downloaded + "/" + MOD_URLS.size() + ")", progress);

            } catch (Exception e) {
                System.err.println("下载模组失败: " + modFileName + " - " + e.getMessage());
            }
        }

        System.out.println("模组下载完成: " + downloaded + " 个新下载, " + skipped + " 个已跳过");
        ProgressServer.updateProgressDetail("mods", downloaded + "新/" + skipped + "跳过");
    }

    private static void downloadMod(String modUrl, String savePath) throws IOException {
        URL url = new URL(modUrl);
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

}
