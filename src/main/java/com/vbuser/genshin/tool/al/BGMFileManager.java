package com.vbuser.genshin.tool.al;

import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.common.FMLCommonHandler;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Map;

public class BGMFileManager {
    private static File bgmDir;
    static boolean ignored;

    public static File getBGMDir() {
        if (bgmDir == null) {
            if (FMLCommonHandler.instance().getSide().isClient()) {
                bgmDir = new File(Minecraft.getMinecraft().mcDataDir, "bgm");
            } else {
                MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
                bgmDir = new File(server.getDataDirectory(), "bgm");
            }
            if (!bgmDir.exists()) ignored = bgmDir.mkdirs();
        }
        return bgmDir;
    }

    public static File getFile(String fileName) {
        return new File(getBGMDir(), fileName);
    }

    public static boolean exists(String fileName) {
        return getFile(fileName).exists();
    }

    public static String getMD5(String fileName) throws IOException {
        File file = getFile(fileName);
        if (!file.exists()) return null;
        try (FileInputStream fis = new FileInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] buf = new byte[8192];
            int len;
            while ((len = fis.read(buf)) != -1) md.update(buf, 0, len);
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IOException("MD5 calculation failed", e);
        }
    }

    @SuppressWarnings("unused")
    public static void saveFile(String fileName, byte[] data) throws IOException {
        File target = getFile(fileName);
        ignored = target.getParentFile().mkdirs();
        Files.write(target.toPath(), data);
    }

    public static void saveChunks(String fileName, Map<Integer, byte[]> chunks, int total) throws IOException {
        File target = getFile(fileName);
        ignored = target.getParentFile().mkdirs();
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < total; i++) {
            byte[] chunk = chunks.get(i);
            if (chunk != null) bos.write(chunk);
        }
        Files.write(target.toPath(), bos.toByteArray());
    }
}