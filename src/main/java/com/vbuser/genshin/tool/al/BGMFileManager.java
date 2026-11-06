package com.vbuser.genshin.tool.al;

import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Owns the bgm asset directory and every path that touches it.
 *
 * <p>Directory layout:</p>
 * <ul>
 *   <li>client: {@code <minecraft dir>/bgm}</li>
 *   <li>server: {@code <server data dir>/bgm} (the server working directory)</li>
 * </ul>
 *
 * <p>All file names are validated by {@link #resolve(String)}, so callers never have to
 * know (or re-implement) the traversal rules.</p>
 */
public class BGMFileManager {

    private static volatile File bgmDir;

    /** Client-only access to the Minecraft directory, kept out of the common class body. */
    @SideOnly(Side.CLIENT)
    private static final class ClientDir {
        static File get() {
            return new File(Minecraft.getMinecraft().mcDataDir, "bgm");
        }
    }

    public static File getBGMDir() {
        File dir = bgmDir;
        if (dir == null) {
            if (FMLCommonHandler.instance().getSide().isClient()) {
                dir = ClientDir.get();
            } else {
                MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
                dir = new File(server != null ? server.getDataDirectory() : new File("."), "bgm");
            }
            if (!dir.exists()) {
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
            }
            bgmDir = dir;
        }
        return dir;
    }

    /**
     * @return true when {@code fileName} is a plain relative path that stays inside the
     * bgm directory.
     */
    public static boolean isValidName(String fileName) {
        if (fileName == null) {
            return false;
        }
        String name = fileName.trim();
        if (name.isEmpty() || name.length() > 200) {
            return false;
        }
        if (name.contains("..") || name.contains("\\") || name.indexOf(':') >= 0) {
            return false;
        }
        if (name.startsWith("/") || name.endsWith("/")) {
            return false;
        }
        return !name.isEmpty();
    }

    /**
     * Resolves a bgm file name to an absolute file, rejecting traversal attempts.
     *
     * @throws IllegalArgumentException when the name is not a safe relative path
     */
    public static File resolve(String fileName) {
        if (!isValidName(fileName)) {
            throw new IllegalArgumentException("Illegal bgm file name: " + fileName);
        }
        File dir = getBGMDir();
        File file = new File(dir, fileName.trim());
        try {
            String root = dir.getCanonicalPath();
            String target = file.getCanonicalPath();
            if (!target.equals(root) && !target.startsWith(root + File.separator)) {
                throw new IllegalArgumentException("bgm file escapes the bgm directory: " + fileName);
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot resolve bgm file: " + fileName, e);
        }
        return file;
    }

    public static File getFile(String fileName) {
        return resolve(fileName);
    }

    public static boolean exists(String fileName) {
        return isValidName(fileName) && resolve(fileName).isFile();
    }

    /** All bgm file names, relative to the bgm directory and sorted. */
    public static List<String> list() {
        File dir = getBGMDir();
        List<String> names = new ArrayList<>();
        collect(dir, dir, names);
        Collections.sort(names);
        return names;
    }

    private static void collect(File root, File current, List<String> out) {
        File[] children = current.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                collect(root, child, out);
            } else {
                String relative = root.toPath().relativize(child.toPath()).toString().replace('\\', '/');
                out.add(relative);
            }
        }
    }

    public static String getMD5(String fileName) throws IOException {
        File file = resolve(fileName);
        if (!file.isFile()) {
            return null;
        }
        try (FileInputStream fis = new FileInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] buffer = new byte[8192];
            int length;
            while ((length = fis.read(buffer)) != -1) {
                md.update(buffer, 0, length);
            }
            StringBuilder builder = new StringBuilder();
            for (byte b : md.digest()) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (Exception e) {
            throw new IOException("MD5 calculation failed", e);
        }
    }

    public static void saveFile(String fileName, byte[] data) throws IOException {
        File target = resolve(fileName);
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        Files.write(target.toPath(), data);
    }

    public static void saveChunks(String fileName, Map<Integer, byte[]> chunks, int total) throws IOException {
        File target = resolve(fileName);
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < total; i++) {
            byte[] chunk = chunks.get(i);
            if (chunk != null) {
                buffer.write(chunk);
            }
        }
        Files.write(target.toPath(), buffer.toByteArray());
    }

    /**
     * Copies a stream shipped inside a mod into the bgm directory, so mods can install
     * their own tracks without touching the file system layout.
     *
     * @return the written file
     */
    public static File install(InputStream stream, String fileName) throws IOException {
        if (stream == null) {
            throw new IOException("BGM source stream is null");
        }
        File target = resolve(fileName);
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        try (InputStream in = stream) {
            Files.copy(in, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }
}
