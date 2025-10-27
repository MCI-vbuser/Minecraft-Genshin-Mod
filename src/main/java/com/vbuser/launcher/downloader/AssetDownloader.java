package com.vbuser.launcher.downloader;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.json.JSONObject;

public class AssetDownloader {
    private static final String RESOURCES_BASE_URL = "https://resources.download.minecraft.net/";

    public static void downloadAssets(JSONObject versionJson, String gameDir) throws IOException {
        if (!versionJson.has("assetIndex")) {
            System.out.println("Json file doesn't include index.");
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
        System.out.println("Downloaded assets: " + assetIndexFile);

        downloadAssetFiles(assetIndexFile, objectsDir);
    }

    private static void downloadAssetFiles(Path assetIndexFile, Path objectsDir) throws IOException {
        String assetIndexContent = new String(Files.readAllBytes(assetIndexFile));
        JSONObject assetsJson = new JSONObject(assetIndexContent);

        if (!assetsJson.has("objects")) {
            System.out.println("Json file doesn't include objects.");
            return;
        }

        JSONObject objects = assetsJson.getJSONObject("objects");
        System.out.println(objects.length() + " required to be downloaded.");

        int downloaded = 0;
        int skipped = 0;

        for (String key : objects.keySet()) {
            JSONObject asset = objects.getJSONObject(key);
            String hash = asset.getString("hash");
            String assetUrl = RESOURCES_BASE_URL + hash.substring(0, 2) + "/" + hash;
            Path assetPath = objectsDir.resolve(hash.substring(0, 2)).resolve(hash);

            if (!Files.exists(assetPath)) {
                Files.createDirectories(assetPath.getParent());
                try {
                    downloadFile(assetUrl, assetPath.toString());
                    downloaded++;
                    if (downloaded % 100 == 0) {
                        System.out.println("Downloaded " + downloaded + " source files...");
                    }
                } catch (IOException e) {
                    System.err.println("Failed to download: " + key + " - " + e.getMessage());
                }
            } else {
                skipped++;
            }
        }

        System.out.println("Finish downloading with: " + downloaded + " new files, " + skipped + " skipped.");
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
}