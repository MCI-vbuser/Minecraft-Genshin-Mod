package com.vbuser.genshin.tool.mcgit;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.storage.RegionFile;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;

import java.io.*;
import java.lang.reflect.Type;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

public class MapUpdater {
    private final MinecraftServer server;
    private final String remoteBaseUrl = "https://mapupdate.mciverse.dpdns.org";
    private final Path baseWorldPath = Paths.get("base_world");
    private final Path tempRemotePath = Paths.get("temp_remote");
    private final Gson gson = new Gson();
    private static final Type MANIFEST_TYPE = new TypeToken<Map<String, String>>(){}.getType();

    private final Set<ChunkPos> modifiedChunks = new HashSet<>();

    public MapUpdater(MinecraftServer server) {
        this.server = server;
    }

    public Set<ChunkPos> performUpdate() throws Exception {
        modifiedChunks.clear();

        if (Files.exists(tempRemotePath)) {
            deleteRecursively(tempRemotePath);
        }
        Files.createDirectories(tempRemotePath);

        Map<String, String> remoteManifest = downloadManifest();
        Map<String, String> baseManifest = buildLocalManifest(baseWorldPath);

        for (Map.Entry<String, String> entry : remoteManifest.entrySet()) {
            String relPath = entry.getKey();
            if (!relPath.endsWith(".mca")) continue;
            String baseMd5 = baseManifest.get(relPath);
            if (baseMd5 == null || !baseMd5.equals(entry.getValue())) {
                downloadToTemp(relPath);
            }
        }

        Path worldDir = server.getEntityWorld().getSaveHandler().getWorldDirectory().toPath();

        for (String relPath : remoteManifest.keySet()) {
            if (!relPath.endsWith(".mca")) continue;
            String baseMd5 = baseManifest.get(relPath);
            if (baseMd5 != null && baseMd5.equals(remoteManifest.get(relPath))) {
                continue;
            }

            Path baseFile = baseWorldPath.resolve(relPath);
            Path remoteFile = tempRemotePath.resolve(relPath);
            Path worldFile = worldDir.resolve(relPath);

            String regionName = relPath.substring(relPath.lastIndexOf('/') + 1);
            String[] parts = regionName.split("\\.");
            if (parts.length < 4) continue;
            int regionX = Integer.parseInt(parts[1]);
            int regionZ = Integer.parseInt(parts[2]);

            RegionFile baseRegion = null;
            RegionFile remoteRegion = null;
            RegionFile worldRegion = null;
            try {
                baseRegion = openRegionFile(baseFile);
                remoteRegion = openRegionFile(remoteFile);
                Files.createDirectories(worldFile.getParent());
                worldRegion = new RegionFile(worldFile.toFile());

                for (int cx = 0; cx < 32; cx++) {
                    for (int cz = 0; cz < 32; cz++) {
                        int globalCx = (regionX << 5) + cx;
                        int globalCz = (regionZ << 5) + cz;

                        assert remoteRegion != null;
                        if (!remoteRegion.isChunkSaved(cx, cz)) continue;
                        NBTTagCompound baseNBT = readChunkNBT(baseRegion, cx, cz);
                        NBTTagCompound remoteNBT = readChunkNBT(remoteRegion, cx, cz);
                        NBTTagCompound worldNBT = readChunkNBT(worldRegion, cx, cz);
                        if (baseNBT != null && baseNBT.equals(remoteNBT)) {
                            continue;
                        }
                        ChunkMerger merger = new ChunkMerger(baseNBT, remoteNBT, worldNBT);
                        NBTTagCompound merged = merger.merge();
                        if (merged != null) {
                            writeChunkNBT(worldRegion, cx, cz, merged);
                            modifiedChunks.add(new ChunkPos(globalCx, globalCz));
                        }
                    }
                }
            } finally {
                closeSilently(baseRegion);
                closeSilently(remoteRegion);
                closeSilently(worldRegion);
            }
            Files.createDirectories(baseFile.getParent());
            Files.copy(remoteFile, baseFile, StandardCopyOption.REPLACE_EXISTING);
        }

        deleteRecursively(tempRemotePath);

        return modifiedChunks;
    }

    private RegionFile openRegionFile(Path file) {
        if (!Files.exists(file)) return null;
        return new RegionFile(file.toFile());
    }

    private HttpURLConnection createConnection(URL url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8");
        conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        conn.setRequestProperty("Connection", "keep-alive");
        conn.setInstanceFollowRedirects(true);
        return conn;
    }

    private NBTTagCompound readChunkNBT(RegionFile region, int x, int z) throws IOException {
        if (region == null || !region.isChunkSaved(x, z)) return null;
        DataInputStream dis = region.getChunkDataInputStream(x, z);
        if (dis == null) return null;
        try {
            return CompressedStreamTools.read(dis);
        } finally {
            dis.close();
        }
    }

    private void writeChunkNBT(RegionFile region, int x, int z, NBTTagCompound nbt) throws IOException {
        try (DataOutputStream dos = region.getChunkDataOutputStream(x, z)) {
            assert dos != null;
            CompressedStreamTools.write(nbt, dos);
        }
    }

    private Map<String, String> downloadManifest() throws IOException {
        URL url = new URL(remoteBaseUrl + "/manifest.json");
        HttpURLConnection conn = createConnection(url);
        try (InputStreamReader reader = new InputStreamReader(conn.getInputStream())) {
            return gson.fromJson(reader, MANIFEST_TYPE);
        } finally {
            conn.disconnect();
        }
    }

    private void downloadToTemp(String relPath) throws IOException {
        Path dest = tempRemotePath.resolve(relPath);
        Files.createDirectories(dest.getParent());
        URL url = new URL(remoteBaseUrl + "/" + relPath);
        HttpURLConnection conn = createConnection(url);
        try (ReadableByteChannel rbc = Channels.newChannel(conn.getInputStream());
             FileOutputStream fos = new FileOutputStream(dest.toFile());
             FileChannel fch = fos.getChannel()) {
            fch.transferFrom(rbc, 0, Long.MAX_VALUE);
        } finally {
            conn.disconnect();
        }
    }

    @SuppressWarnings("all")
    private Map<String, String> buildLocalManifest(Path root) throws IOException {
        Map<String, String> map = new TreeMap<>();
        if (!Files.exists(root)) return map;
        Files.walk(root).filter(Files::isRegularFile).forEach(f -> {
            String rel = root.relativize(f).toString().replace('\\', '/');
            try {
                map.put(rel, fileMd5(f));
            } catch (IOException e) { System.out.println(e.getMessage()); }
        });
        return map;
    }

    private String fileMd5(Path file) throws IOException {
        try (InputStream is = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] buf = new byte[4096];
            int len;
            while ((len = is.read(buf)) != -1) md.update(buf, 0, len);
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    private void closeSilently(RegionFile region) {
        if (region != null) {
            try { region.close(); } catch (IOException ignored) {}
        }
    }

    @SuppressWarnings("all")
    private void deleteRecursively(Path path) throws IOException {
        if (Files.exists(path)) {
            Files.walk(path)
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        }
    }
}