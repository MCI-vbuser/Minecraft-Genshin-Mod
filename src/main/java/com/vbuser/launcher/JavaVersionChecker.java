package com.vbuser.launcher;

import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

@SuppressWarnings("unused")
public class JavaVersionChecker {
    public static boolean isJava8() {
        try {
            String javaVersion = System.getProperty("java.version");
            return javaVersion != null && javaVersion.startsWith("1.8");
        }catch (Exception e) {
            return false;
        }
    }

    public static List<String> findJava8Paths() {
        Set<String> javaPaths = new LinkedHashSet<>();
        checkJavaHome(javaPaths);
        checkPathEnvironment(javaPaths);
        checkCommonInstallationDirs(javaPaths);
        return filterJava8Versions(javaPaths);
    }

    private static void checkJavaHome(Set<String> javaPaths) {
        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome != null && !javaHome.trim().isEmpty()) {
            javaPaths.add(javaHome);
            File javaHomeFile = new File(javaHome);
            if (javaHomeFile.getParentFile() != null) {
                checkDirectoryForJavaInstallations(javaHomeFile.getParentFile(), javaPaths);
            }
        }
    }

    private static void checkPathEnvironment(Set<String> javaPaths) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            String[] pathDirs = pathEnv.split(File.pathSeparator);
            for (String pathDir : pathDirs) {
                if (pathDir.toLowerCase().contains("java") ||
                        pathDir.toLowerCase().contains("jdk") ||
                        pathDir.toLowerCase().contains("jre")) {
                    File dir = new File(pathDir);
                    if (dir.exists() && dir.isDirectory()) {
                        findJavaInDirectory(dir, javaPaths);
                    }
                }
            }
        }
    }

    private static void checkCommonInstallationDirs(Set<String> javaPaths) {
        String os = System.getProperty("os.name").toLowerCase();

        if (os.contains("win")) {
            checkWindowsInstallations(javaPaths);
        } else if (os.contains("mac")) {
            checkMacInstallations(javaPaths);
        } else if (os.contains("nix") || os.contains("nux")) {
            checkLinuxInstallations(javaPaths);
        }
    }

    private static void checkWindowsInstallations(Set<String> javaPaths) {
        String[] programFilesDirs = {
                System.getenv("ProgramFiles"),
                System.getenv("ProgramFiles(x86)"),
                System.getenv("ProgramW6432")
        };

        for (String programFiles : programFilesDirs) {
            if (programFiles != null) {
                File javaDir = new File(programFiles, "Java");
                checkDirectoryForJavaInstallations(javaDir, javaPaths);
            }
        }

        String userHome = System.getProperty("user.home");
        File userJavaDir = new File(userHome, "AppData\\Local\\Programs\\Java");
        checkDirectoryForJavaInstallations(userJavaDir, javaPaths);
    }

    private static void checkMacInstallations(Set<String> javaPaths) {
        String[] macPaths = {
                "/Library/Java/JavaVirtualMachines",
                "/System/Library/Java/JavaVirtualMachines",
                "/usr/local/opt/openjdk@8",
                "/usr/local/Cellar/openjdk@8",
                System.getProperty("user.home") + "/Library/Java/JavaVirtualMachines"
        };

        for (String path : macPaths) {
            checkDirectoryForJavaInstallations(new File(path), javaPaths);
        }

        try {
            Process process = Runtime.getRuntime().exec(new String[]{"brew", "--prefix", "openjdk@8"});
            Scanner scanner = new Scanner(process.getInputStream());
            if (scanner.hasNextLine()) {
                String brewPath = scanner.nextLine().trim();
                checkDirectoryForJavaInstallations(new File(brewPath), javaPaths);
            }
            scanner.close();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void checkLinuxInstallations(Set<String> javaPaths) {
        String[] linuxPaths = {
                "/usr/lib/jvm",
                "/usr/java",
                "/opt/java",
                "/opt/jdk",
                "/usr/local/java",
                "/usr/local/jdk"
        };

        for (String path : linuxPaths) {
            checkDirectoryForJavaInstallations(new File(path), javaPaths);
        }

        try {
            Process process = Runtime.getRuntime().exec(new String[]{"update-alternatives", "--list", "java"});
            Scanner scanner = new Scanner(process.getInputStream());
            while (scanner.hasNextLine()) {
                String javaPath = scanner.nextLine().trim();
                File javaFile = new File(javaPath);
                if (javaFile.exists()) {
                    File jdkHome = javaFile.getParentFile().getParentFile();
                    if (jdkHome != null && jdkHome.exists()) {
                        javaPaths.add(jdkHome.getAbsolutePath());
                    }
                }
            }
            scanner.close();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void checkDirectoryForJavaInstallations(File directory, Set<String> javaPaths) {
        if (directory == null || !directory.exists() || !directory.isDirectory()) {
            return;
        }

        File[] subDirs = directory.listFiles(File::isDirectory);
        if (subDirs != null) {
            for (File subDir : subDirs) {
                if (isLikelyJavaInstallation(subDir)) {
                    javaPaths.add(subDir.getAbsolutePath());
                }
            }
        }
    }

    private static void findJavaInDirectory(File directory, Set<String> javaPaths) {
        if (directory == null || !directory.exists()) {
            return;
        }

        String[] executables = {"java", "javac", "java.exe", "javac.exe"};
        for (String exec : executables) {
            File execFile = new File(directory, exec);
            if (execFile.exists() && execFile.isFile() && execFile.canExecute()) {
                File jdkHome = directory.getParentFile();
                if (jdkHome != null && jdkHome.exists()) {
                    javaPaths.add(jdkHome.getAbsolutePath());
                }
                break;
            }
        }
    }

    private static boolean isLikelyJavaInstallation(File dir) {
        String dirName = dir.getName().toLowerCase();
        if (dirName.contains("jdk") || dirName.contains("jre") || dirName.contains("java")) {
            File binDir = new File(dir, "bin");
            File libDir = new File(dir, "lib");
            return binDir.exists() && binDir.isDirectory() &&
                    libDir.exists() && libDir.isDirectory();
        }
        return false;
    }

    private static List<String> filterJava8Versions(Set<String> javaPaths) {
        return javaPaths.stream()
                .filter(JavaVersionChecker::isJava8Installation)
                .collect(Collectors.toList());
    }

    private static boolean isJava8Installation(String javaPath) {
        try {
            File javaHome = new File(javaPath);
            File javaExec = new File(javaHome, "bin/java");
            if (!javaExec.exists()) {
                javaExec = new File(javaHome, "bin/java.exe");
            }

            if (javaExec.exists() && javaExec.canExecute()) {
                ProcessBuilder pb = new ProcessBuilder(javaExec.getAbsolutePath(), "-version");
                Process process = pb.start();

                Scanner scanner = new Scanner(process.getErrorStream());
                StringBuilder output = new StringBuilder();
                while (scanner.hasNextLine()) {
                    output.append(scanner.nextLine()).append("\n");
                }
                scanner.close();

                process.waitFor();

                String versionOutput = output.toString().toLowerCase();
                return versionOutput.contains("1.8") ||
                        versionOutput.contains("java 8") ||
                        (versionOutput.contains("version") &&
                                versionOutput.contains("\"8"));
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return false;
    }
}
