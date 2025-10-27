package com.vbuser.launcher;

import com.vbuser.launcher.downloader.MinecraftLibraryDownloader;
import com.vbuser.launcher.downloader.ModDownloader;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.util.*;

public class Launcher {

    public static void main(String[] args) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("正在关闭启动器...");
            ProgressServer.stopServer();
        }));

        ProgressServer.startServer();
        ProgressServer.updateProgress("启动器中...", 5);
        ProgressServer.updateProgressDetail("javaVersion", System.getProperty("java.version"));
        ProgressServer.updateProgressDetail("gameDir", ".minecraft");

        try {
            if (JavaVersionChecker.isJava8()) {
                System.out.println("Running in Java 8.");
                ProgressServer.updateProgress("Java 8 环境检测通过", 10);
                ProgressServer.updateProgressDetail("javaDetection", "Java 8 已检测");
                launch();
            } else {
                List<String> java8Paths = JavaVersionChecker.findJava8Paths();
                System.out.println("Running in Java " + System.getProperty("java.version") + ". Trying to restart with Java 8.");
                ProgressServer.updateProgress("检测到非Java 8环境，尝试重启", 15);
                if (!java8Paths.isEmpty()) {
                    ProgressServer.updateProgressDetail("javaDetection", "找到Java 8，准备重启");
                    restartWithJava8(java8Paths.get(0), args);
                } else {
                    System.out.println("Java 8 not found. Launching with current Java version.");
                    ProgressServer.updateProgress("未找到Java 8，使用当前版本启动", 20);
                    ProgressServer.updateProgressDetail("javaDetection", "使用当前Java版本: " + System.getProperty("java.version"));
                    launch();
                }
            }
        } catch (Exception e) {
            ProgressServer.updateProgress("启动失败: " + e.getMessage(), 0);
            ProgressServer.updateProgressDetail("error", e.getMessage());
        }
    }

    private static void restartWithJava8(String java8Path, String[] args) {
        try {
            ProgressServer.updateProgress("正在重启到Java 8环境", 25);
            String javaExec = new File(java8Path, "bin/java").getAbsolutePath();
            if (!new File(javaExec).exists()) {
                javaExec = new File(java8Path, "bin/java.exe").getAbsolutePath();
            }

            String classpath = System.getProperty("java.class.path");
            String mainClass = getMainClassName();

            List<String> command = new ArrayList<>();
            command.add(javaExec);

            List<String> jvmArgs = getJVMArguments();
            command.addAll(jvmArgs);

            command.add("-cp");
            command.add(classpath);
            command.add(mainClass);

            Collections.addAll(command, args);

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.inheritIO();

            ProgressServer.stopServer();
            pb.start();

            System.exit(0);

        } catch (Exception e) {
            System.out.println("Restart failed. Launching with current Java version.");
            ProgressServer.updateProgress("重启失败，使用当前版本启动", 30);
            launch();
        }
    }

    private static String getMainClassName() {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        for (StackTraceElement element : stack) {
            if ("main".equals(element.getMethodName())) {
                return element.getClassName();
            }
        }
        return Launcher.class.getName();
    }

    private static List<String> getJVMArguments() {
        List<String> jvmArgs = ManagementFactory.getRuntimeMXBean().getInputArguments();
        List<String> filteredArgs = new ArrayList<>();

        for (String arg : jvmArgs) {
            if (!arg.contains("-version") &&
                    !arg.startsWith("-Djava.version") &&
                    !arg.startsWith("-Djava.home")) {
                filteredArgs.add(arg);
            }
        }

        return filteredArgs;
    }

    public static void launch() {
        try {
            ProgressServer.updateProgress("开始下载Minecraft资源", 40);
            MinecraftLibraryDownloader.downloadMinecraft1122();

            ProgressServer.updateProgress("下载其他模组", 70);
            ModDownloader.downloadAdditionalMods();

            ProgressServer.updateProgress("复制启动器到mods文件夹", 80);
            SelfCopier.copySelfToMods();

            ProgressServer.updateProgress("启动Minecraft游戏", 95);
            MinecraftLauncher.test();
        } catch (Exception e) {
            ProgressServer.updateProgress("启动过程出错: " + e.getMessage(), 0);
            ProgressServer.updateProgressDetail("error", e.getMessage());
            throw new RuntimeException(e);
        }
    }
}