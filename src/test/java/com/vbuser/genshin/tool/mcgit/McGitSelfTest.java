package com.vbuser.genshin.tool.mcgit;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.nbt.NBTTagByteArray;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagIntArray;
import net.minecraft.nbt.NBTTagList;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

/**
 * Standalone checks for the parts of mcgit that are easy to get wrong without
 * a running game: the infinite-height section merge, the base URL handling and
 * the two remote server layouts.
 *
 * <p>Run with {@code gradlew mcgitSelfTest}.</p>
 */
public final class McGitSelfTest {

    private static int AIR;
    private static int STONE;
    private static int DIRT;
    private static int GOLD;

    public static void main(String[] args) throws Exception {
        Bootstrap.register();
        AIR = Block.getStateId(Blocks.AIR.getDefaultState());
        STONE = Block.getStateId(Blocks.STONE.getDefaultState());
        DIRT = Block.getStateId(Blocks.DIRT.getDefaultState());
        GOLD = Block.getStateId(Blocks.GOLD_BLOCK.getDefaultState());

        testUrls();
        testExtendedMerge();
        testLegacyServerProtocol();
        testCurrentServerProtocol();
        System.out.println("MCGIT SELF TEST OK");
    }

    private static void testUrls() {
        check("http://127.0.0.1:8080", MapUpdater.normalizeBaseUrl("127.0.0.1:8080"));
        check("http://localhost:8080", MapUpdater.normalizeBaseUrl("localhost:8080/"));
        check("https://example.com", MapUpdater.normalizeBaseUrl("https://example.com/"));
        check("https://example.com", MapUpdater.normalizeBaseUrl("example.com"));
        check("http://127.0.0.1:8080", MapUpdater.normalizeBaseUrl("http://127.0.0.1:8080/manifest.json"));
        check("http://127.0.0.1:8080", MapUpdater.normalizeBaseUrl("http://127.0.0.1:8080/manifest"));
        check("http://192.168.1.5:8080", MapUpdater.normalizeBaseUrl("192.168.1.5:8080"));
    }

    private static void testExtendedMerge() {
        // Y=16 is above the vanilla height limit.
        Map<Integer, Integer> base = new HashMap<>();
        base.put(0, STONE);
        base.put(3, STONE);
        base.put(5, STONE);
        Map<Integer, Integer> remote = new HashMap<>();
        remote.put(0, DIRT);
        remote.put(1, STONE);
        remote.put(3, DIRT);
        Map<Integer, Integer> world = new HashMap<>();
        world.put(0, STONE);
        world.put(2, GOLD);
        world.put(3, GOLD);
        world.put(5, STONE);

        NBTTagCompound merged = new ChunkMerger(
                root(base, 16), root(remote, 16), root(world, 16)).merge();
        NBTTagCompound level = merged.getCompoundTag("Level");

        checkState(level, 16, 0, DIRT, "remote change accepted");
        checkState(level, 16, 1, STONE, "remote addition accepted");
        checkState(level, 16, 2, GOLD, "player addition kept");
        checkState(level, 16, 3, GOLD, "player wins conflict");
        checkState(level, 16, 5, AIR, "remote removal applied");
        if (level.hasKey("inf:Light")) {
            throw new AssertionError("inf:Light must be dropped after merging");
        }

        // Remote removed a whole section; the player's own block in it stays.
        Map<Integer, Integer> base2 = new HashMap<>();
        base2.put(0, STONE);
        Map<Integer, Integer> world2 = new HashMap<>();
        world2.put(0, STONE);
        world2.put(1, DIRT);
        NBTTagCompound merged2 = new ChunkMerger(
                root(base2, 20), root(new HashMap<>(), 20), root(world2, 20)).merge();
        NBTTagCompound level2 = merged2.getCompoundTag("Level");
        checkState(level2, 20, 0, AIR, "removed base block");
        checkState(level2, 20, 1, DIRT, "player block in removed section kept");

        // A chunk with no extended data at all must not gain tags.
        NBTTagCompound plain = new NBTTagCompound();
        plain.setTag("Level", new NBTTagCompound());
        NBTTagCompound remotePlain = new NBTTagCompound();
        remotePlain.setTag("Level", new NBTTagCompound());
        remotePlain.getCompoundTag("Level").setInteger("xPos", 0);
        NBTTagCompound merged3 = new ChunkMerger(plain, remotePlain, plain.copy()).merge();
        if (merged3.getCompoundTag("Level").hasKey("inf:ExtendedSections")) {
            throw new AssertionError("plain chunk gained extended sections");
        }
    }

    /**
     * The original standalone Java server serves {@code /manifest} and
     * {@code /files/<path>}; the current deployments serve
     * {@code /manifest.json} and {@code /<path>}. Both have to work.
     */
    private static void testLegacyServerProtocol() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if ("/manifest".equals(path)) {
                respond(exchange, 200, "{\"region/r.0.0.mca\":\"abc\"}");
            } else if (path.startsWith("/files/")) {
                respond(exchange, 200, "legacy" + path);
            } else {
                respond(exchange, 404, null);
            }
        });
        server.start();
        try {
            MapUpdater updater = new MapUpdater(null,
                    MapUpdater.normalizeBaseUrl("127.0.0.1:" + server.getAddress().getPort()));
            Map<String, String> manifest = downloadManifest(updater);
            check("abc", manifest.get("region/r.0.0.mca"));

            downloadToTemp(updater, "region/r.0.0.mca");
            check("legacy/files/region/r.0.0.mca", readTempFile("region/r.0.0.mca"));
            if (!legacyLayout(updater)) {
                throw new AssertionError("legacy server was not detected");
            }
        } finally {
            server.stop(0);
            cleanupTemp();
        }
    }

    private static void testCurrentServerProtocol() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if ("/manifest.json".equals(path)) {
                respond(exchange, 200, "{\"region/r.0.0.mca\":\"def\"}");
            } else if (path.startsWith("/region/")) {
                respond(exchange, 200, "current" + path);
            } else {
                respond(exchange, 404, null);
            }
        });
        server.start();
        try {
            MapUpdater updater = new MapUpdater(null,
                    MapUpdater.normalizeBaseUrl("127.0.0.1:" + server.getAddress().getPort()));
            downloadManifest(updater);
            downloadToTemp(updater, "region/r.0.0.mca");
            check("current/region/r.0.0.mca", readTempFile("region/r.0.0.mca"));
            if (legacyLayout(updater)) {
                throw new AssertionError("current server was detected as legacy");
            }
        } finally {
            server.stop(0);
            cleanupTemp();
        }
    }

    // ------------------------------------------------------------------
    // HTTP helpers
    // ------------------------------------------------------------------

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body == null ? null : body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes == null ? -1 : bytes.length);
        if (bytes != null) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
        exchange.close();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> downloadManifest(MapUpdater updater) throws Exception {
        Method method = MapUpdater.class.getDeclaredMethod("downloadManifest");
        method.setAccessible(true);
        return (Map<String, String>) method.invoke(updater);
    }

    private static void downloadToTemp(MapUpdater updater, String relativePath) throws Exception {
        Method method = MapUpdater.class.getDeclaredMethod("downloadToTemp", String.class);
        method.setAccessible(true);
        method.invoke(updater, relativePath);
    }

    private static boolean legacyLayout(MapUpdater updater) throws Exception {
        Field field = MapUpdater.class.getDeclaredField("legacyLayout");
        field.setAccessible(true);
        return field.getBoolean(updater);
    }

    private static String readTempFile(String relativePath) throws IOException {
        Path file = Paths.get("temp_remote").resolve(relativePath);
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private static void cleanupTemp() throws IOException {
        Path temp = Paths.get("temp_remote");
        if (!Files.exists(temp)) {
            return;
        }
        Files.walk(temp)
                .sorted(Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(java.io.File::delete);
    }

    // ------------------------------------------------------------------
    // NBT helpers
    // ------------------------------------------------------------------

    private static NBTTagCompound root(Map<Integer, Integer> blocks, int sectionY) {
        NBTTagCompound root = new NBTTagCompound();
        NBTTagCompound level = new NBTTagCompound();
        root.setTag("Level", level);

        NBTTagList sections = new NBTTagList();
        if (!blocks.isEmpty()) {
            NBTTagCompound section = new NBTTagCompound();
            section.setInteger("Y", sectionY);

            int[] palette = new int[blocks.size() + 1];
            palette[0] = AIR;
            Map<Integer, Integer> lookup = new HashMap<>();
            lookup.put(AIR, 0);
            int size = 1;
            for (int state : blocks.values()) {
                if (!lookup.containsKey(state)) {
                    lookup.put(state, size);
                    palette[size++] = state;
                }
            }

            byte[] indices = new byte[4096];
            for (Map.Entry<Integer, Integer> entry : blocks.entrySet()) {
                indices[entry.getKey()] = (byte) (int) lookup.get(entry.getValue());
            }
            section.setTag("Palette", new NBTTagIntArray(java.util.Arrays.copyOf(palette, size)));
            section.setTag("Indices", new NBTTagByteArray(indices));
            sections.appendTag(section);
        }
        level.setTag("inf:ExtendedSections", sections);
        level.setInteger("inf:Format", 3);
        level.setInteger("inf:Light", 3);
        return root;
    }

    private static void checkState(NBTTagCompound level, int sectionY, int index,
                                   int expected, String message) {
        NBTTagList sections = level.getTagList("inf:ExtendedSections", 10);
        for (int i = 0; i < sections.tagCount(); i++) {
            NBTTagCompound section = sections.getCompoundTagAt(i);
            if (section.getInteger("Y") != sectionY) {
                continue;
            }
            int[] palette = ((NBTTagIntArray) section.getTag("Palette")).getIntArray();
            byte[] indices = section.getByteArray("Indices");
            int actual = palette[indices[index] & 0xFF];
            if (actual != expected) {
                throw new AssertionError(message + ": expected " + expected + " got " + actual);
            }
            return;
        }
        if (expected != AIR) {
            throw new AssertionError(message + ": section " + sectionY + " missing");
        }
    }

    private static void check(String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("expected " + expected + " got " + actual);
        }
    }

    private McGitSelfTest() {}
}
