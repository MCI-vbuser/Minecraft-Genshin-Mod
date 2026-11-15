package com.vbuser.genshin.tool.mcgit;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.storage.RegionFile;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Pulls the latest official (base) world from a remote server and merges it
 * into the player's world, keeping the player's changes.
 *
 * <p>The local copy of the base world lives in {@code base_world/}. For every
 * region file that changed remotely it does a three-way merge per chunk
 * (base / remote / player world) and then replaces the local base region with
 * the remote one.</p>
 *
 * <p>Two remote layouts are supported:</p>
 * <ul>
 *   <li>current: {@code GET /manifest.json} and {@code GET /<relative path>};</li>
 *   <li>the original standalone Java server: {@code GET /manifest} and
 *       {@code GET /files/<relative path>}.</li>
 * </ul>
 * The layout is detected from what the server answers, so old deployments keep
 * working without being redeployed.
 */
public class MapUpdater {

    public static final String DEFAULT_BASE_URL = "https://mapupdate.mciverse.dpdns.org";

    private static final int CONNECT_TIMEOUT_MS = 8_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    private static final Type MANIFEST_TYPE = new TypeToken<Map<String, String>>() {}.getType();

    private final MinecraftServer server;
    private final String remoteBaseUrl;
    private final Path baseWorldPath = Paths.get("base_world");
    private final Path tempRemotePath = Paths.get("temp_remote");
    private final Gson gson = new Gson();

    private final Set<ChunkPos> modifiedChunks = new HashSet<>();

    /**
     * True when the server uses the original Java layout ({@code /manifest}
     * plus {@code /files/...}). Set while the manifest is fetched and used for
     * the file downloads that follow.
     */
    private boolean legacyLayout;

    public MapUpdater(MinecraftServer server) {
        this(server, DEFAULT_BASE_URL);
    }

    public MapUpdater(MinecraftServer server, String remoteBaseUrl) {
        this.server = server;
        this.remoteBaseUrl = remoteBaseUrl;
    }

    public Set<ChunkPos> performUpdate() throws Exception {
        modifiedChunks.clear();

        if (Files.exists(tempRemotePath)) {
            deleteRecursively(tempRemotePath);
        }
        Files.createDirectories(tempRemotePath);

        try {
            Map<String, String> remoteManifest = downloadManifest();
            Map<String, String> baseManifest = buildLocalManifest(baseWorldPath);

            for (Map.Entry<String, String> entry : remoteManifest.entrySet()) {
                String relPath = entry.getKey();
                if (!relPath.endsWith(".mca")) {
                    continue;
                }
                String baseMd5 = baseManifest.get(relPath);
                if (baseMd5 == null || !baseMd5.equals(entry.getValue())) {
                    downloadToTemp(relPath);
                }
            }

            Path worldDir = server.getEntityWorld().getSaveHandler().getWorldDirectory().toPath();

            for (String relPath : remoteManifest.keySet()) {
                if (!relPath.endsWith(".mca")) {
                    continue;
                }
                String baseMd5 = baseManifest.get(relPath);
                if (baseMd5 != null && baseMd5.equals(remoteManifest.get(relPath))) {
                    continue;
                }

                Path baseFile = baseWorldPath.resolve(relPath);
                Path remoteFile = tempRemotePath.resolve(relPath);
                Path worldFile = worldDir.resolve(relPath);

                if (!Files.isRegularFile(remoteFile)) {
                    throw new IOException("Downloaded region is missing: " + relPath);
                }

                String regionName = relPath.substring(relPath.lastIndexOf('/') + 1);
                String[] parts = regionName.split("\\.");
                if (parts.length < 4) {
                    continue;
                }
                int regionX;
                int regionZ;
                try {
                    regionX = Integer.parseInt(parts[1]);
                    regionZ = Integer.parseInt(parts[2]);
                } catch (NumberFormatException e) {
                    continue;
                }

                RegionFile baseRegion = null;
                RegionFile remoteRegion = null;
                RegionFile worldRegion = null;
                try {
                    baseRegion = openRegionFile(baseFile);
                    remoteRegion = openRegionFile(remoteFile);
                    if (remoteRegion == null) {
                        throw new IOException("Downloaded region is missing: " + relPath);
                    }
                    Files.createDirectories(worldFile.getParent());
                    // Deliberately a separate handle from RegionFileCache:
                    // every chunk of this file is rewritten below and the
                    // cached vanilla handle would otherwise keep stale offsets.
                    worldRegion = new RegionFile(worldFile.toFile());

                    for (int cx = 0; cx < 32; cx++) {
                        for (int cz = 0; cz < 32; cz++) {
                            int globalCx = (regionX << 5) + cx;
                            int globalCz = (regionZ << 5) + cz;

                            if (!remoteRegion.isChunkSaved(cx, cz)) {
                                continue;
                            }
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

            return modifiedChunks;
        } finally {
            deleteRecursively(tempRemotePath);
        }
    }

    // ------------------------------------------------------------------
    // Remote layout / protocol
    // ------------------------------------------------------------------

    /**
     * Accepts what a player would naturally type: a full URL, a bare
     * {@code host:port} (which defaults to http for loopback/LAN addresses and
     * https otherwise), an optional trailing slash, or a pasted manifest URL.
     */
    public static String normalizeBaseUrl(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            throw new IllegalArgumentException("Empty server address");
        }
        String value = raw.trim();
        if (!value.contains("://")) {
            String hostPart = value;
            int slash = hostPart.indexOf('/');
            if (slash >= 0) {
                hostPart = hostPart.substring(0, slash);
            }
            int colon = hostPart.lastIndexOf(':');
            String host = colon > 0 ? hostPart.substring(0, colon) : hostPart;
            host = host.replace("[", "").replace("]", "");
            boolean local = host.equalsIgnoreCase("localhost")
                    || host.equals("127.0.0.1")
                    || host.equals("0.0.0.0")
                    || host.equals("::1")
                    || host.startsWith("10.")
                    || host.startsWith("192.168.")
                    || host.startsWith("172.");
            value = (local ? "http://" : "https://") + value;
        }

        if (value.endsWith("/manifest.json")) {
            value = value.substring(0, value.length() - "/manifest.json".length());
        } else if (value.endsWith("/manifest")) {
            value = value.substring(0, value.length() - "/manifest".length());
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }

        URL url;
        try {
            url = new URL(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid server address: " + raw);
        }
        String protocol = url.getProtocol();
        if (!"http".equals(protocol) && !"https".equals(protocol)) {
            throw new IllegalArgumentException("Only http/https servers are supported");
        }
        if (url.getHost() == null || url.getHost().isEmpty()) {
            throw new IllegalArgumentException("Invalid server address: " + raw);
        }
        return value;
    }

    private Map<String, String> downloadManifest() throws IOException {
        IOException lastError = null;
        for (String path : new String[]{"/manifest.json", "/manifest"}) {
            byte[] body = tryFetch(remoteBaseUrl + path);
            if (body == null) {
                continue;
            }
            try {
                Map<String, String> manifest = gson.fromJson(
                        new String(body, StandardCharsets.UTF_8), MANIFEST_TYPE);
                if (manifest == null) {
                    throw new IOException("empty manifest");
                }
                legacyLayout = "/manifest".equals(path);
                return manifest;
            } catch (RuntimeException e) {
                lastError = new IOException(
                        "Invalid manifest from " + remoteBaseUrl + path, e);
            }
        }
        if (lastError != null) {
            throw lastError;
        }
        throw new IOException("No manifest found at " + remoteBaseUrl
                + " (tried /manifest.json and /manifest)");
    }

    private byte[] tryFetch(String urlText) throws IOException {
        HttpURLConnection conn = createConnection(new URL(urlText));
        try {
            int code = conn.getResponseCode();
            if (code == HttpURLConnection.HTTP_NOT_FOUND) {
                drainQuietly(conn);
                return null;
            }
            if (code != HttpURLConnection.HTTP_OK) {
                drainQuietly(conn);
                throw new IOException("HTTP " + code + " from " + urlText);
            }
            try (InputStream in = conn.getInputStream()) {
                return readAll(in);
            }
        } finally {
            conn.disconnect();
        }
    }

    private HttpURLConnection createConnection(URL url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        conn.setRequestProperty("Accept", "application/json,text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        conn.setRequestProperty("Accept-Encoding", "identity");
        conn.setRequestProperty("Connection", "keep-alive");
        conn.setInstanceFollowRedirects(true);
        return conn;
    }

    private void downloadToTemp(String relPath) throws IOException {
        String primary = legacyLayout
                ? remoteBaseUrl + "/files/" + relPath
                : remoteBaseUrl + "/" + relPath;
        byte[] data = tryFetch(primary);
        if (data == null && !legacyLayout) {
            // A base URL without a manifest.json may still expose the files at
            // the old /files/ prefix (mixed or partially migrated setups).
            String fallback = remoteBaseUrl + "/files/" + relPath;
            data = tryFetch(fallback);
            if (data != null) {
                legacyLayout = true;
            }
        }
        if (data == null) {
            throw new IOException("HTTP 404 for " + relPath + " under " + remoteBaseUrl);
        }
        Path dest = tempRemotePath.resolve(relPath);
        Files.createDirectories(dest.getParent());
        Files.write(dest, data);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int len;
        while ((len = in.read(buffer)) != -1) {
            out.write(buffer, 0, len);
        }
        return out.toByteArray();
    }

    private static void drainQuietly(HttpURLConnection conn) {
        try {
            InputStream error = conn.getErrorStream();
            if (error != null) {
                try (InputStream in = error) {
                    readAll(in);
                }
            }
        } catch (IOException ignored) {
            // The status code already tells the caller what happened.
        }
    }

    // ------------------------------------------------------------------
    // Region / NBT helpers
    // ------------------------------------------------------------------

    private RegionFile openRegionFile(Path file) {
        if (!Files.exists(file)) {
            return null;
        }
        return new RegionFile(file.toFile());
    }

    private NBTTagCompound readChunkNBT(RegionFile region, int x, int z) throws IOException {
        if (region == null || !region.isChunkSaved(x, z)) {
            return null;
        }
        DataInputStream dis = region.getChunkDataInputStream(x, z);
        if (dis == null) {
            return null;
        }
        try {
            return CompressedStreamTools.read(dis);
        } finally {
            dis.close();
        }
    }

    private void writeChunkNBT(RegionFile region, int x, int z, NBTTagCompound nbt) throws IOException {
        try (DataOutputStream dos = region.getChunkDataOutputStream(x, z)) {
            CompressedStreamTools.write(nbt, dos);
        }
    }

    @SuppressWarnings("all")
    private Map<String, String> buildLocalManifest(Path root) throws IOException {
        Map<String, String> map = new TreeMap<>();
        if (!Files.exists(root)) {
            return map;
        }
        Files.walk(root).filter(Files::isRegularFile).forEach(f -> {
            String rel = root.relativize(f).toString().replace('\\', '/');
            try {
                map.put(rel, fileMd5(f));
            } catch (IOException e) {
                System.out.println(e.getMessage());
            }
        });
        return map;
    }

    private String fileMd5(Path file) throws IOException {
        try (InputStream is = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] buf = new byte[8192];
            int len;
            while ((len = is.read(buf)) != -1) {
                md.update(buf, 0, len);
            }
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    private void closeSilently(RegionFile region) {
        if (region != null) {
            try {
                region.close();
            } catch (IOException ignored) {
            }
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
