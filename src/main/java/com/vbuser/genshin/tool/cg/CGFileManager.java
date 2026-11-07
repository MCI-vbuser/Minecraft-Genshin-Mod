package com.vbuser.genshin.tool.cg;

import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Owns the cg (video) asset directory and every path that touches it.
 *
 * <p>Directory layout:</p>
 * <ul>
 *   <li>client: {@code <minecraft dir>/cg}</li>
 *   <li>server: {@code <server data dir>/cg} (the server working directory)</li>
 * </ul>
 *
 * <p>This is the single validation point for cg file names. Before this class existed the
 * server resolved request names with {@code new File("cg", name)} and no check at all, so a
 * modified client could stream any file the server process could read.</p>
 */
public class CGFileManager {

    private static volatile File cgDir;

    @SideOnly(Side.CLIENT)
    private static final class ClientDir {
        static File get() {
            return new File(Minecraft.getMinecraft().mcDataDir, "cg");
        }
    }

    public static File getCGDir() {
        File dir = cgDir;
        if (dir == null) {
            if (FMLCommonHandler.instance().getSide().isClient()) {
                dir = ClientDir.get();
            } else {
                MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
                dir = new File(server != null ? server.getDataDirectory() : new File("."), "cg");
            }
            if (!dir.exists()) {
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
            }
            cgDir = dir;
        }
        return dir;
    }

    /** @return true when {@code fileName} is a plain relative path inside the cg directory */
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
        return true;
    }

    /**
     * Resolves a cg file name to an absolute file inside the cg directory.
     *
     * @throws IllegalArgumentException when the name is illegal or escapes the directory
     */
    public static File resolve(String fileName) {
        if (!isValidName(fileName)) {
            throw new IllegalArgumentException("Illegal cg file name: " + fileName);
        }
        File dir = getCGDir();
        File file = new File(dir, fileName.trim());
        try {
            String root = dir.getCanonicalPath();
            String target = file.getCanonicalPath();
            if (!target.equals(root) && !target.startsWith(root + File.separator)) {
                throw new IllegalArgumentException("cg file escapes the cg directory: " + fileName);
            }
            // Return the canonical form on purpose. In a dev run the game directory is
            // ".", so getAbsolutePath() yields "...\run\.\cg\x.mp4" and JavaFX builds its
            // media URI from that path ("file:/.../run/./cg/x.mp4"). Canonicalizing keeps
            // the URI identical to the one the old (relative) code produced.
            return new File(target);
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot resolve cg file: " + fileName, e);
        }
    }

    public static File getFile(String fileName) {
        return resolve(fileName);
    }

    public static boolean exists(String fileName) {
        return isValidName(fileName) && resolve(fileName).isFile();
    }

    /** All cg file names, relative to the cg directory and sorted. */
    public static List<String> list() {
        File dir = getCGDir();
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
                out.add(root.toPath().relativize(child.toPath()).toString().replace('\\', '/'));
            }
        }
    }

    /** Copies a stream shipped with a mod into the cg directory. */
    public static File install(InputStream stream, String fileName) throws IOException {
        if (stream == null) {
            throw new IOException("CG source stream is null");
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
