package com.vbuser.launcher;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ProgressServer {
    private static HttpServer server;
    private static final int PORT = 19198;
    private static final AtomicReference<String> currentStatus = new AtomicReference<>("初始化中...");
    private static final AtomicReference<Double> currentProgress = new AtomicReference<>(0.0);
    private static final Map<String, String> progressDetails = new ConcurrentHashMap<>();
    private static boolean serverRunning = false;
    private static ScheduledExecutorService heartbeatExecutor;

    public static void startServer() {
        try {
            server = HttpServer.create(new InetSocketAddress(PORT), 0);
            server.createContext("/", new ProgressHandler());
            server.createContext("/progress", new ProgressDataHandler());
            server.createContext("/health", new HealthHandler());
            server.setExecutor(Executors.newFixedThreadPool(10));
            server.start();
            serverRunning = true;
            System.out.println("进度服务器已在端口 " + PORT + " 启动");

            startHeartbeat();

            openBrowser();

        } catch (IOException e) {
            System.err.println("无法启动进度服务器: " + e.getMessage());
        }
    }

    public static void stopServer() {
        if (server != null) {
            serverRunning = false;
            server.stop(1);
            System.out.println("进度服务器已停止");
        }
        if (heartbeatExecutor != null) {
            heartbeatExecutor.shutdown();
        }
    }

    private static void startHeartbeat() {
        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor();
        heartbeatExecutor.scheduleAtFixedRate(() -> {
        }, 0, 30, TimeUnit.SECONDS);
    }

    public static void setMinecraftProcess(Process process) {
        new Thread(() -> {
            try {
                int exitCode = process.waitFor();
                System.out.println("Minecraft进程已退出，代码: " + exitCode);

                updateProgress("Minecraft已关闭", 100);
                updateProgressDetail("exitCode", String.valueOf(exitCode));

                Thread.sleep(5000);
                stopServer();
                System.exit(0);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();
    }

    public static void updateProgress(String status, double progress) {
        currentStatus.set(status);
        currentProgress.set(Math.max(0, Math.min(100, progress)));
        System.out.println("进度更新: " + status + " (" + progress + "%)");
    }

    public static void updateProgressDetail(String key, String value) {
        progressDetails.put(key, value);
        System.out.println("详情更新: " + key + " = " + value);
    }

    private static void openBrowser() {
        try {
            String os = System.getProperty("os.name").toLowerCase();
            String url = "http://localhost:" + PORT;

            System.out.println("尝试打开浏览器: " + url);

            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop desktop = java.awt.Desktop.getDesktop();
                if (desktop.isSupported(java.awt.Desktop.Action.BROWSE)) {
                    try {
                        desktop.browse(new java.net.URI(url));
                        System.out.println("使用 Desktop 类打开浏览器成功");
                        return;
                    } catch (Exception e) {
                        System.err.println("Desktop 方式失败: " + e.getMessage());
                    }
                }
            }

            ProcessBuilder pb = getProcessBuilder(os, url);

            if (pb != null) {
                try {
                    Process process = pb.start();
                    new Thread(() -> {
                        try {
                            int exitCode = process.waitFor();
                            if (exitCode == 0) {
                                System.out.println("命令行方式打开浏览器成功");
                            } else {
                                System.err.println("命令行方式打开浏览器失败，退出码: " + exitCode);
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }).start();

                    Thread.sleep(1000);

                } catch (IOException e) {
                    System.err.println("命令行执行失败: " + e.getMessage());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            System.out.println("请手动访问: " + url);

        } catch (Exception e) {
            System.err.println("无法自动打开浏览器: " + e.getMessage());
            System.out.println("请手动访问: http://localhost:" + PORT);
        }
    }

    private static ProcessBuilder getProcessBuilder(String os, String url) {
        ProcessBuilder pb = null;
        if (os.contains("win")) {
            pb = new ProcessBuilder("cmd", "/c", "start", url);
        } else if (os.contains("mac")) {
            pb = new ProcessBuilder("open", url);
        } else if (os.contains("nix") || os.contains("nux")) {
            String[] browsers = {"xdg-open", "gnome-open", "kde-open", "firefox", "chromium", "chrome"};
            for (String browser : browsers) {
                try {
                    pb = new ProcessBuilder(browser, url);
                    break;
                } catch (Exception e) {
                    // emm...
                }
            }
        }
        return pb;
    }

    static class ProgressHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!serverRunning) {
                String response = "服务器已关闭";
                exchange.sendResponseHeaders(503, response.getBytes(StandardCharsets.UTF_8).length);
                exchange.getResponseBody().write(response.getBytes(StandardCharsets.UTF_8));
                exchange.close();
                return;
            }

            String response = getHTMLPage();
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, response.getBytes(StandardCharsets.UTF_8).length);

            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes(StandardCharsets.UTF_8));
            }
        }

        private String getHTMLPage() {
            return "                <!DOCTYPE html>\n" +
                    "                <html lang=\"zh-CN\">\n" +
                    "                <head>\n" +
                    "                    <meta charset=\"UTF-8\">\n" +
                    "                    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
                    "                    <title>Minecraft 启动器 - 进度</title>\n" +
                    "                    <style>\n" +
                    "                        * {\n" +
                    "                            margin: 0;\n" +
                    "                            padding: 0;\n" +
                    "                            box-sizing: border-box;\n" +
                    "                        }\n" +
                    "                        \n" +
                    "                        body {\n" +
                    "                            font-family: 'JetBrains Mono', 'Consolas', 'Monaco', monospace;\n" +
                    "                            background: #2B2B2B;\n" +
                    "                            color: #A9B7C6;\n" +
                    "                            line-height: 1.6;\n" +
                    "                            padding: 20px;\n" +
                    "                            min-height: 100vh;\n" +
                    "                        }\n" +
                    "                        \n" +
                    "                        .container {\n" +
                    "                            max-width: 800px;\n" +
                    "                            margin: 0 auto;\n" +
                    "                            background: #3C3F41;\n" +
                    "                            border-radius: 8px;\n" +
                    "                            padding: 30px;\n" +
                    "                            box-shadow: 0 4px 20px rgba(0, 0, 0, 0.3);\n" +
                    "                            border: 1px solid #4E5254;\n" +
                    "                        }\n" +
                    "                        \n" +
                    "                        .header {\n" +
                    "                            text-align: center;\n" +
                    "                            margin-bottom: 30px;\n" +
                    "                            padding-bottom: 20px;\n" +
                    "                            border-bottom: 2px solid #4E5254;\n" +
                    "                        }\n" +
                    "                        \n" +
                    "                        .header h1 {\n" +
                    "                            color: #A9B7C6;\n" +
                    "                            font-size: 28px;\n" +
                    "                            font-weight: 600;\n" +
                    "                            margin-bottom: 8px;\n" +
                    "                        }\n" +
                    "                        \n" +
                    "                        .header .subtitle {\n" +
                    "                            color: #808080;\n" +
                    "                            font-size: 14px;\n" +
                    "                        }\n" +
                    "                        \n" +
                    "                        .progress-section {\n" +
                    "                            margin-bottom: 30px;\n" +
                    "                        }\n" +
                    "                        \n" +
                    "                        .progress-info {\n" +
                    "                            display: flex;\n" +
                    "                            justify-content: space-between;\n" +
                    "                            align-items: center;\n" +
                    "                            margin-bottom: 15px;\n" +
                    "                        }\n" +
                    "                        \n" +
                    "                        .progress-status {\n" +
                    "                            font-size: 16px;\n" +
                    "                            font-weight: 500;\n" +
                    "                            color: #A9B7C6;\n" +
                    "                        }\n" +
                    "                        \n" +
                    "                        .progress-percent {\n" +
                    "                            font-size: 16px;\n" +
                    "                            font-weight: 600;\n" +
                    "                            color: #CC7832;\n" +
                    "                        }\n" +
                    "                        \n" +
                    "                        .progress-bar {\n" +
                    "                            width: 100%;\n" +
                    "                            height: 12px;\n" +
                    "                            background: #4E5254;\n" +
                    "                            border-radius: 6px;\n" +
                    "                            overflow: hidden;\n" +
                    "                            position: relative;\n" +
                    "                        }\n" +
                    "                        \n" +
                    "                        .progress-fill {\n" +
                    "                            height: 100%;\n" +
                    "                            background: linear-gradient(90deg, #387A40, #6A8759);\n" +
                    "                            border-radius: 6px;\n" +
                    "                            transition: width 0.5s ease;\n" +
                    "                            width: 0%;\n" +
                    "                        }\n" +
                    "                        \n" +
                    "                        .details-section {\n" +
                    "                            background: #2B2B2B;\n" +
                    "                            border-radius: 6px;\n" +
                    "                            padding: 20px;\n" +
                    "                            border: 1px solid #4E5254;\n" +
                    "                            margin-bottom: 20px;\n" +
                    "                        }\n" +
                    "                        \n" +
                    "                        .details-title {\n" +
                    "                            font-size: 14px;\n" +
                    "                            font-weight: 600;\n" +
                    "                            color: #808080;\n" +
                    "                            margin-bottom: 15px;\n" +
                    "                            text-transform: uppercase;\n" +
                    "                            letter-spacing: 1px;\n" +
                    "                        }\n" +
                    "                        \n" +
                    "                        .details-grid {\n" +
                    "                            display: grid;\n" +
                    "                            grid-template-columns: 1fr;\n" +
                    "                            gap: 10px;\n" +
                    "                        }\n" +
                    "                        \n" +
                    "                        .detail-item {\n" +
                    "                            display: flex;\n" +
                    "                            justify-content: space-between;\n" +
                    "                            padding: 8px 0;\n" +
                    "                            border-bottom: 1px solid #3C3F41;\n" +
                    "                        }\n" +
                    "                        \n" +
                    "                        .detail-item:last-child {\n" +
                    "                            border-bottom: none;\n" +
                    "                        }\n" +
                    "                        \n" +
                    "                        .detail-label {\n" +
                    "                            color: #A9B7C6;\n" +
                    "                            font-weight: 500;\n" +
                    "                        }\n" +
                    "                        \n" +
                    "                        .detail-value {\n" +
                    "                            color: #6A8759;\n" +
                    "                            font-family: 'JetBrains Mono', monospace;\n" +
                    "                            max-width: 60%;\n" +
                    "                            text-align: right;\n" +
                    "                            word-break: break-all;\n" +
                    "                        }\n" +
                    "                        \n" +
                    "                        .console-section {\n" +
                    "                            background: #2B2B2B;\n" +
                    "                            border-radius: 6px;\n" +
                    "                            padding: 20px;\n" +
                    "                            border: 1px solid #4E5254;\n" +
                    "                            max-height: 300px;\n" +
                    "                            overflow-y: auto;\n" +
                    "                        }\n" +
                    "                        \n" +
                    "                        .console-title {\n" +
                    "                            font-size: 14px;\n" +
                    "                            font-weight: 600;\n" +
                    "                            color: #808080;\n" +
                    "                            margin-bottom: 15px;\n" +
                    "                            text-transform: uppercase;\n" +
                    "                            letter-spacing: 1px;\n" +
                    "                        }\n" +
                    "                        \n" +
                    "                        .console-output {\n" +
                    "                            font-family: 'JetBrains Mono', monospace;\n" +
                    "                            font-size: 12px;\n" +
                    "                            color: #A9B7C6;\n" +
                    "                            line-height: 1.4;\n" +
                    "                        }\n" +
                    "                        \n" +
                    "                        .console-line {\n" +
                    "                            margin-bottom: 4px;\n" +
                    "                        }\n" +
                    "                        \n" +
                    "                        .footer {\n" +
                    "                            text-align: center;\n" +
                    "                            margin-top: 30px;\n" +
                    "                            padding-top: 20px;\n" +
                    "                            border-top: 1px solid #4E5254;\n" +
                    "                            color: #808080;\n" +
                    "                            font-size: 12px;\n" +
                    "                        }\n" +
                    "                        \n" +
                    "                        .loading-spinner {\n" +
                    "                            display: inline-block;\n" +
                    "                            width: 20px;\n" +
                    "                            height: 20px;\n" +
                    "                            border: 2px solid #4E5254;\n" +
                    "                            border-top: 2px solid #6A8759;\n" +
                    "                            border-radius: 50%;\n" +
                    "                            animation: spin 1s linear infinite;\n" +
                    "                            margin-right: 10px;\n" +
                    "                        }\n" +
                    "                        \n" +
                    "                        @keyframes spin {\n" +
                    "                            0% { transform: rotate(0deg); }\n" +
                    "                            100% { transform: rotate(360deg); }\n" +
                    "                        }\n" +
                    "                        \n" +
                    "                        .status-indicator {\n" +
                    "                            display: inline-block;\n" +
                    "                            width: 8px;\n" +
                    "                            height: 8px;\n" +
                    "                            border-radius: 50%;\n" +
                    "                            margin-right: 8px;\n" +
                    "                        }\n" +
                    "                        \n" +
                    "                        .status-online {\n" +
                    "                            background: #6A8759;\n" +
                    "                        }\n" +
                    "                        \n" +
                    "                        .status-offline {\n" +
                    "                            background: #CC7832;\n" +
                    "                        }\n" +
                    "                    </style>\n" +
                    "                </head>\n" +
                    "                <body>\n" +
                    "                    <div class=\"container\">\n" +
                    "                        <div class=\"header\">\n" +
                    "                            <h1>\uD83D\uDE80 Minecraft 启动器</h1>\n" +
                    "                            <div class=\"subtitle\">正在准备启动 Minecraft 1.12.2</div>\n" +
                    "                        </div>\n" +
                    "                        \n" +
                    "                        <div class=\"progress-section\">\n" +
                    "                            <div class=\"progress-info\">\n" +
                    "                                <span class=\"progress-status\">\n" +
                    "                                    <span class=\"status-indicator status-online\" id=\"statusIndicator\"></span>\n" +
                    "                                    <span id=\"status\">初始化中...</span>\n" +
                    "                                </span>\n" +
                    "                                <span class=\"progress-percent\" id=\"percent\">0%</span>\n" +
                    "                            </div>\n" +
                    "                            <div class=\"progress-bar\">\n" +
                    "                                <div class=\"progress-fill\" id=\"progressFill\"></div>\n" +
                    "                            </div>\n" +
                    "                        </div>\n" +
                    "                        \n" +
                    "                        <div class=\"details-section\">\n" +
                    "                            <div class=\"details-title\">启动详情</div>\n" +
                    "                            <div class=\"details-grid\" id=\"detailsGrid\">\n" +
                    "                                <div class=\"detail-item\">\n" +
                    "                                    <span class=\"detail-label\">当前状态:</span>\n" +
                    "                                    <span class=\"detail-value\" id=\"currentStatus\">初始化中...</span>\n" +
                    "                                </div>\n" +
                    "                                <div class=\"detail-item\">\n" +
                    "                                    <span class=\"detail-label\">Java 版本:</span>\n" +
                    "                                    <span class=\"detail-value\" id=\"javaVersion\">检测中...</span>\n" +
                    "                                </div>\n" +
                    "                                <div class=\"detail-item\">\n" +
                    "                                    <span class=\"detail-label\">游戏目录:</span>\n" +
                    "                                    <span class=\"detail-value\" id=\"gameDir\">.minecraft</span>\n" +
                    "                                </div>\n" +
                    "                                <div class=\"detail-item\">\n" +
                    "                                    <span class=\"detail-label\">服务器状态:</span>\n" +
                    "                                    <span class=\"detail-value\" id=\"serverStatus\">运行中</span>\n" +
                    "                                </div>\n" +
                    "                            </div>\n" +
                    "                        </div>\n" +
                    "                        \n" +
                    "                        <div class=\"console-section\">\n" +
                    "                            <div class=\"console-title\">控制台输出</div>\n" +
                    "                            <div class=\"console-output\" id=\"consoleOutput\">\n" +
                    "                                <div class=\"console-line\">> 启动器初始化...</div>\n" +
                    "                            </div>\n" +
                    "                        </div>\n" +
                    "                        \n" +
                    "                        <div class=\"footer\">\n" +
                    "                            <span class=\"loading-spinner\"></span>\n" +
                    "                            实时更新中 - Minecraft 启动完成后此页面将自动关闭\n" +
                    "                        </div>\n" +
                    "                    </div>\n" +
                    "                    \n" +
                    "                    <script>\n" +
                    "                        let consoleLines = [];\n" +
                    "                        \n" +
                    "                        function updateProgress() {\n" +
                    "                            fetch('/progress')\n" +
                    "                                .then(response => {\n" +
                    "                                    if (!response.ok) {\n" +
                    "                                        throw new Error('服务器响应异常');\n" +
                    "                                    }\n" +
                    "                                    return response.json();\n" +
                    "                                })\n" +
                    "                                .then(data => {\n" +
                    "                                    document.getElementById('status').textContent = data.status;\n" +
                    "                                    document.getElementById('percent').textContent = data.progress + '%';\n" +
                    "                                    document.getElementById('progressFill').style.width = data.progress + '%';\n" +
                    "                                    document.getElementById('currentStatus').textContent = data.status;\n" +
                    "                                    \n" +
                    "                                    if (data.details) {\n" +
                    "                                        for (const [key, value] of Object.entries(data.details)) {\n" +
                    "                                            const element = document.getElementById(key);\n" +
                    "                                            if (element) {\n" +
                    "                                                element.textContent = value;\n" +
                    "                                            }\n" +
                    "                                        }\n" +
                    "                                    }\n" +
                    "                                    \n" +
                    "                                    document.getElementById('serverStatus').textContent = '运行中';\n" +
                    "                                    document.getElementById('statusIndicator').className = 'status-indicator status-online';\n" +
                    "                                })\n" +
                    "                                .catch(error => {\n" +
                    "                                    console.error('获取进度失败:', error);\n" +
                    "                                    document.getElementById('serverStatus').textContent = '连接中断';\n" +
                    "                                    document.getElementById('statusIndicator').className = 'status-indicator status-offline';\n" +
                    "                                    document.getElementById('status').textContent = '服务器连接失败';\n" +
                    "                                });\n" +
                    "                        }\n" +
                    "                        \n" +
                    "                        function addConsoleLine(line) {\n" +
                    "                            consoleLines.push(line);\n" +
                    "                            if (consoleLines.length > 50) {\n" +
                    "                                consoleLines.shift();\n" +
                    "                            }\n" +
                    "                            const consoleOutput = document.getElementById('consoleOutput');\n" +
                    "                            consoleOutput.innerHTML = consoleLines.map(l => `<div class=\"console-line\">> ${l}</div>`).join('');\n" +
                    "                            consoleOutput.scrollTop = consoleOutput.scrollHeight;\n" +
                    "                        }\n" +
                    "                        \n" +
                    "                        setInterval(updateProgress, 1000);\n" +
                    "                        \n" +
                    "                        updateProgress();\n" +
                    "                        \n" +
                    "                        setTimeout(() => addConsoleLine('进度服务器已启动'), 100);\n" +
                    "                        setTimeout(() => addConsoleLine('开始检测Java环境...'), 1500);\n" +
                    "                        \n" +
                    "                        setInterval(() => {\n" +
                    "                            addConsoleLine('系统运行中... ' + new Date().toLocaleTimeString());\n" +
                    "                        }, 30000);\n" +
                    "                    </script>\n" +
                    "                </body>\n" +
                    "                </html>";
        }
    }

    static class ProgressDataHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!serverRunning) {
                String response = "{\"error\":\"服务器已关闭\"}";
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
                exchange.sendResponseHeaders(503, response.getBytes(StandardCharsets.UTF_8).length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes(StandardCharsets.UTF_8));
                }
                return;
            }

            String response = getProgressJSON();
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(200, response.getBytes(StandardCharsets.UTF_8).length);

            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes(StandardCharsets.UTF_8));
            }
        }

        private String getProgressJSON() {
            StringBuilder json = new StringBuilder();
            json.append("{")
                    .append("\"status\":\"").append(escapeJSON(currentStatus.get())).append("\",")
                    .append("\"progress\":").append(currentProgress.get()).append(",")
                    .append("\"details\":{");

            boolean first = true;
            for (Map.Entry<String, String> entry : progressDetails.entrySet()) {
                if (!first) {
                    json.append(",");
                }
                json.append("\"").append(entry.getKey()).append("\":\"").append(escapeJSON(entry.getValue())).append("\"");
                first = false;
            }
            json.append("}}");

            return json.toString();
        }

        private String escapeJSON(String text) {
            return text.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t");
        }
    }

    static class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String response = serverRunning ? "OK" : "DOWN";
            exchange.sendResponseHeaders(serverRunning ? 200 : 503, response.getBytes(StandardCharsets.UTF_8).length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes(StandardCharsets.UTF_8));
            }
        }
    }
}