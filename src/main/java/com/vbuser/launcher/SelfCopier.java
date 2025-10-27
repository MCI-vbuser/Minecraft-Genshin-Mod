package com.vbuser.launcher;

import java.io.*;
import java.nio.file.*;

public class SelfCopier {
    private static final String GAME_DIR = ".minecraft";
    private static final String MODS_DIR = "mods";

    public static void copySelfToMods() throws Exception {
        String selfPath = getCurrentJarPath();
        if (selfPath == null) {
            System.out.println("无法确定当前jar文件路径，跳过自复制");
            return;
        }

        String currentDir = new File(".").getCanonicalPath();
        Path modsDir = Paths.get(currentDir, GAME_DIR, MODS_DIR);
        Files.createDirectories(modsDir);

        File selfFile = new File(selfPath);
        String targetFileName = selfFile.getName();
        Path targetPath = modsDir.resolve(targetFileName);

        System.out.println("复制源: " + selfFile.getAbsolutePath());
        System.out.println("复制目标: " + targetPath.toAbsolutePath());

        if (!Files.isWritable(modsDir)) {
            throw new IOException("没有写入权限: " + modsDir.toAbsolutePath());
        }

        if (Files.exists(targetPath) && filesAreIdentical(selfFile, targetPath.toFile())) {
            System.out.println("启动器已在mods文件夹中且内容相同，跳过复制");
            ProgressServer.updateProgressDetail("selfCopy", "已存在");
            return;
        }

        try {
            Files.copy(selfFile.toPath(), targetPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            System.out.println("启动器已复制到mods文件夹: " + targetPath);
            ProgressServer.updateProgressDetail("selfCopy", "复制成功");
        } catch (java.nio.file.AccessDeniedException e) {
            throw new IOException("文件访问被拒绝，请检查权限: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new IOException("文件复制失败: " + e.getMessage(), e);
        }
    }

    private static String getCurrentJarPath() {
        try {
            return Launcher.class.getProtectionDomain().getCodeSource().getLocation().toURI().getPath();
        } catch (Exception e) {
            System.err.println("获取当前jar路径失败: " + e.getMessage());
            String classpath = System.getProperty("java.class.path");
            if (classpath != null) {
                String[] paths = classpath.split(File.pathSeparator);
                for (String path : paths) {
                    if (path.endsWith(".jar") && new File(path).exists()) {
                        return path;
                    }
                }
            }
            return null;
        }
    }

    private static boolean filesAreIdentical(File file1, File file2) throws Exception {
        if (file1.length() != file2.length()) {
            return false;
        }

        String hash1 = calculateMD5(file1);
        String hash2 = calculateMD5(file2);

        return hash1.equals(hash2);
    }

    private static String calculateMD5(File file) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
        try (InputStream is = Files.newInputStream(file.toPath())) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = is.read(buffer)) > 0) {
                md.update(buffer, 0, read);
            }
        }

        byte[] digest = md.digest();
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}