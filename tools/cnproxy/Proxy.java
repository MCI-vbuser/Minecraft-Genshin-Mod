import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedReader;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Local record / replay cache proxy for ForgeGradle.
 *
 * Modes
 *   server (default) : MITM the listed hosts, serve from cache, fetch and store 2xx responses.
 *   --replay         : serve only cached entries; everything else is reset (RST) and logged
 *                      to cache/miss.log. This mode can never break a build.
 *   --warm <list>    : fetch every URL in the given miss.log without touching Gradle,
 *                      so recording never depends on a build succeeding.
 *
 * Failure policy (learned the hard way):
 *   - a normal close (FIN) makes Gradle think the artifact does not exist and it falls
 *     through to Maven Central, which then fails with PKIX. Always reset (RST) instead.
 *   - never store non-2xx responses: a recorded 404 becomes "truth" forever.
 *   - never store a response whose Content-Length does not match (truncated download).
 */
public class Proxy {

    private static final Charset ASCII = Charset.forName("ISO-8859-1");
    private static final byte[] HEAD_END = {'\r', '\n', '\r', '\n'};

    private static File cacheDir;
    private static SSLContext serverCtx;
    private static int connectTimeout = 3000;
    private static int readTimeout = 60000;
    private static boolean verbose = true;
    private static boolean replayOnly = false;
    private static String warmList = null;
    private static final Set<String> mitm = new HashSet<String>();

    public static void main(String[] args) throws Exception {
        int port = 8899;
        String cachePath = "cache";
        String ksPath = null;
        String ksPass = "changeit";
        String mitmList = "files.minecraftforge.net,maven.minecraftforge.net,repo.maven.apache.org,repo1.maven.org,launchermeta.mojang.com,"
                + "launcher.mojang.com,libraries.minecraft.net,resources.download.minecraft.net";

        for (int i = 0; i < args.length; i++) {
            if ("--port".equals(args[i])) {
                port = Integer.parseInt(args[++i]);
            } else if ("--cache".equals(args[i])) {
                cachePath = args[++i];
            } else if ("--keystore".equals(args[i])) {
                ksPath = args[++i];
            } else if ("--storepass".equals(args[i])) {
                ksPass = args[++i];
            } else if ("--mitm".equals(args[i])) {
                mitmList = args[++i];
            } else if ("--connect-timeout".equals(args[i])) {
                connectTimeout = Integer.parseInt(args[++i]);
            } else if ("--read-timeout".equals(args[i])) {
                readTimeout = Integer.parseInt(args[++i]);
            } else if ("--quiet".equals(args[i])) {
                verbose = false;
            } else if ("--replay".equals(args[i])) {
                replayOnly = true;
            } else if ("--warm".equals(args[i])) {
                warmList = args[++i];
            }
        }

        cacheDir = new File(cachePath);
        if (!cacheDir.isDirectory() && !cacheDir.mkdirs()) {
            System.err.println("cannot create cache dir: " + cacheDir);
            System.exit(2);
        }
        for (String h : mitmList.split(",")) {
            if (h.trim().length() > 0) {
                mitm.add(h.trim().toLowerCase(Locale.ROOT));
            }
        }
        if (warmList != null) {
            warm(new File(warmList));
            return;
        }
        if (ksPath == null) {
            System.err.println("missing --keystore");
            System.exit(2);
        }

        KeyStore ks = KeyStore.getInstance("PKCS12");
        FileInputStream fin = new FileInputStream(ksPath);
        try {
            ks.load(fin, ksPass.toCharArray());
        } finally {
            fin.close();
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, ksPass.toCharArray());
        serverCtx = SSLContext.getInstance("TLS");
        serverCtx.init(kmf.getKeyManagers(), null, null);

        final ServerSocket server = new ServerSocket(port, 100, InetAddress.getByName("127.0.0.1"));
        log("listening 127.0.0.1:" + port + (replayOnly ? "  (replay only)" : "  (record)"));
        log("cache     " + cacheDir.getAbsolutePath());
        while (true) {
            final Socket s = server.accept();
            Thread t = new Thread(new Runnable() {
                public void run() {
                    handle(s);
                }
            }, "conn");
            t.setDaemon(true);
            t.start();
        }
    }

    // ---------------------------------------------------------------- warm

    private static void warm(File list) throws Exception {
        if (!list.isFile()) {
            System.err.println("no such list: " + list);
            return;
        }
        BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(list), "UTF-8"));
        Set<String> seen = new HashSet<String>();
        int saved = 0;
        int skip = 0;
        int fail = 0;
        String line;
        while ((line = r.readLine()) != null) {
            line = line.trim();
            if (line.length() == 0 || line.startsWith("#")) {
                continue;
            }
            if (!seen.add(line)) {
                continue;
            }
            String[] p = line.split(" ");
            if (p.length < 3) {
                continue;
            }
            String method = p[0];
            String host = p[1];
            String path = p[2];
            File cf = cacheFile(host, method, path);
            if (cf.isFile()) {
                skip++;
                continue;
            }
            String head = method + " " + path + " HTTP/1.1\r\nHost: " + host + "\r\nUser-Agent: mdk-cnproxy\r\n";
            byte[] data = fetch(host, head);
            if (data != null && isCacheable(data)) {
                writeFile(cf, data);
                saved++;
                log("WARM " + method + " https://" + host + path + "  (" + data.length + " bytes)");
            } else {
                fail++;
                log("WARM-FAIL " + method + " https://" + host + path);
            }
        }
        r.close();
        log("warm done: saved=" + saved + ", already-cached=" + skip + ", failed=" + fail);
        if (fail > 0) {
            log("some URLs failed - run warm again when the network is better");
        }
    }

    private static synchronized void appendMiss(String method, String host, String path) {
        try {
            FileWriter w = new FileWriter(new File(cacheDir, "miss.log"), true);
            try {
                w.write(method + " " + host + " " + path + "\n");
            } finally {
                w.close();
            }
        } catch (IOException ignored) {
            // ignore
        }
    }

    // ---------------------------------------------------------------- server

    private static void handle(Socket client) {
        String host = "?";
        try {
            client.setSoTimeout(180000);
            InputStream in = new BufferedInputStream(client.getInputStream());
            String head = readHead(in);
            if (head == null) {
                return;
            }
            String[] parts = firstLine(head).split(" ");
            if (parts.length < 2 || !"CONNECT".equalsIgnoreCase(parts[0])) {
                OutputStream o = client.getOutputStream();
                o.write("HTTP/1.1 405 Method Not Allowed\r\nConnection: close\r\n\r\n".getBytes(ASCII));
                o.flush();
                return;
            }
            String hostPort = parts[1];
            int colon = hostPort.lastIndexOf(':');
            host = (colon > 0 ? hostPort.substring(0, colon) : hostPort).toLowerCase(Locale.ROOT);

            OutputStream out = client.getOutputStream();
            out.write("HTTP/1.1 200 Connection established\r\n\r\n".getBytes(ASCII));
            out.flush();

            if (!mitm.contains(host)) {
                tunnel(client, in, out, host, 443);
                return;
            }
            SSLSocket ssl = (SSLSocket) serverCtx.getSocketFactory()
                    .createSocket(client, host, 443, true);
            ssl.setUseClientMode(false);
            ssl.setSoTimeout(180000);
            ssl.startHandshake();
            serve(ssl, host);
        } catch (Exception e) {
            if (verbose) {
                log("ERR  [" + host + "] " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        } finally {
            closeQuietly(client);
        }
    }

    private static void serve(SSLSocket ssl, String host) throws IOException {
        InputStream in = new BufferedInputStream(ssl.getInputStream());
        OutputStream out = new BufferedOutputStream(ssl.getOutputStream());
        String head = readHead(in);
        if (head == null) {
            return;
        }
        String[] parts = firstLine(head).split(" ");
        if (parts.length < 2) {
            return;
        }
        String method = parts[0];
        String path = parts[1];
        File cf = cacheFile(host, method, path);

        byte[] data = null;
        if (cf.isFile()) {
            data = readFile(cf);
            if (isCacheable(data)) {
                log("HIT  " + method + " https://" + host + path);
            } else {
                // 非 2xx（404/301/304）绝对不能回放：
                // 回放 404 等于告诉 Gradle "这个工件不存在"，它会转去 Maven Central，
                // 然后因为 TLS 不通报 PKIX。真实网络下这些请求是超时失败，
                // Gradle 会当成"仓库不可用"并回退到自己的缓存。
                cf.delete();
                data = null;
            }
        }

        if (data == null) {
            if (replayOnly) {
                log("MISS " + method + " https://" + host + path + "  (replay only, not recorded)");
                appendMiss(method, host, path);
                reset(ssl);
                return;
            }
            log("MISS " + method + " https://" + host + path);
            data = fetch(host, head);
            if (data == null) {
                sleepQuietly(500);
                data = fetch(host, head);
            }
            if (data == null) {
                log("DROP " + method + " https://" + host + path + "  (upstream unreachable)");
                reset(ssl);
                return;
            }
            if (isCacheable(data)) {
                writeFile(cf, data);
                log("SAVE " + method + " https://" + host + path + "  (" + data.length + " bytes)");
            } else {
                log("SKIP " + method + " https://" + host + path + "  (non-2xx, reset)");
                reset(ssl);
                return;
            }
        }
        out.write(normalize(data));
        out.flush();
    }

    /** RST instead of FIN so Gradle treats the repo as unreachable and falls back to its cache. */
    private static void reset(SSLSocket ssl) {
        try {
            ssl.setSoLinger(true, 0);
        } catch (Exception ignored) {
            // ignore
        }
        closeQuietly(ssl);
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------------------------------------------------------------- upstream

    private static byte[] fetch(String host, String clientHead) {
        SSLSocket up = null;
        try {
            SSLSocketFactory f = (SSLSocketFactory) SSLSocketFactory.getDefault();
            up = (SSLSocket) f.createSocket();
            up.connect(new java.net.InetSocketAddress(host, 443), connectTimeout);
            up.setSoTimeout(connectTimeout);
            javax.net.ssl.SSLParameters params = up.getSSLParameters();
            params.setServerNames(java.util.Collections.singletonList(new javax.net.ssl.SNIHostName(host)));
            up.setSSLParameters(params);
            up.startHandshake();
            up.setSoTimeout(readTimeout);

            String reqHead = stripConnectionHeaders(clientHead);
            OutputStream o = up.getOutputStream();
            o.write(reqHead.getBytes(ASCII));
            o.write("Connection: close\r\n\r\n".getBytes(ASCII));
            o.flush();

            return readAll(up.getInputStream());
        } catch (Exception e) {
            if (verbose) {
                log("UPSTREAM FAIL " + host + " : " + e.getClass().getSimpleName() + " " + e.getMessage());
            }
            return null;
        } finally {
            closeQuietly(up);
        }
    }

    private static void tunnel(Socket client, InputStream in, OutputStream out, String host, int port) {
        Socket up = null;
        try {
            up = new Socket();
            up.connect(new java.net.InetSocketAddress(host, port), connectTimeout);
            final Socket target = up;
            final InputStream uin = up.getInputStream();
            final OutputStream uout = up.getOutputStream();

            Thread pump = new Thread(new Runnable() {
                public void run() {
                    byte[] buf = new byte[8192];
                    try {
                        int n;
                        while ((n = uin.read(buf)) > 0) {
                            uout.write(buf, 0, n);
                            uout.flush();
                        }
                    } catch (IOException ignored) {
                        // ignore
                    } finally {
                        closeQuietly(target);
                    }
                }
            });
            pump.setDaemon(true);
            pump.start();

            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                uout.write(buf, 0, n);
                uout.flush();
            }
        } catch (IOException e) {
            if (verbose) {
                log("TUNNEL FAIL " + host + " : " + e.getMessage());
            }
        } finally {
            closeQuietly(up);
            closeQuietly(client);
        }
    }

    // ---------------------------------------------------------------- helpers

    private static String stripConnectionHeaders(String head) {
        StringBuilder sb = new StringBuilder();
        for (String line : head.split("\r\n")) {
            String l = line.toLowerCase(Locale.ROOT);
            if (l.startsWith("connection:") || l.startsWith("proxy-connection:") || l.startsWith("keep-alive:")) {
                continue;
            }
            if (line.length() > 0) {
                sb.append(line).append("\r\n");
            }
        }
        return sb.toString();
    }

    private static byte[] normalize(byte[] resp) {
        int end = indexOf(resp, HEAD_END);
        if (end < 0) {
            return resp;
        }
        String headers = new String(resp, 0, end, ASCII);
        StringBuilder sb = new StringBuilder();
        for (String line : headers.split("\r\n")) {
            String l = line.toLowerCase(Locale.ROOT);
            if (l.startsWith("connection:") || l.startsWith("keep-alive:")) {
                continue;
            }
            sb.append(line).append("\r\n");
        }
        sb.append("Connection: close\r\n\r\n");
        byte[] h = sb.toString().getBytes(ASCII);

        int bodyLen = resp.length - end - HEAD_END.length;
        byte[] out = new byte[h.length + bodyLen];
        System.arraycopy(h, 0, out, 0, h.length);
        System.arraycopy(resp, end + HEAD_END.length, out, h.length, bodyLen);
        return out;
    }

    private static boolean isCacheable(byte[] resp) {
        if (resp == null || resp.length < 16) {
            return false;
        }
        String line = new String(resp, 0, Math.min(64, resp.length), ASCII);
        if (!line.startsWith("HTTP/")) {
            return false;
        }
        int sp = line.indexOf(' ');
        if (sp <= 0 || line.length() <= sp + 2 || line.charAt(sp + 1) != '2') {
            return false;
        }
        int end = indexOf(resp, HEAD_END);
        if (end < 0) {
            return false;
        }
        String headers = new String(resp, 0, end, ASCII);
        boolean chunked = headers.toLowerCase(Locale.ROOT).contains("\r\ntransfer-encoding: chunked");
        long contentLength = -1;
        for (String h : headers.split("\r\n")) {
            String l = h.toLowerCase(Locale.ROOT);
            if (l.startsWith("content-length:")) {
                try {
                    contentLength = Long.parseLong(h.substring(h.indexOf(':') + 1).trim());
                } catch (NumberFormatException ignored) {
                    contentLength = -1;
                }
            }
        }
        if (!chunked && contentLength >= 0) {
            long body = resp.length - (end + HEAD_END.length);
            if (body != contentLength) {
                return false;
            }
        }
        return true;
    }

    private static String firstLine(String head) {
        int i = head.indexOf("\r\n");
        return i < 0 ? head : head.substring(0, i);
    }

    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(512);
        int state = 0;
        int b;
        while ((b = in.read()) >= 0) {
            buf.write(b);
            if (b == HEAD_END[state]) {
                state++;
                if (state == HEAD_END.length) {
                    return new String(buf.toByteArray(), ASCII);
                }
            } else {
                state = (b == HEAD_END[0]) ? 1 : 0;
            }
            if (buf.size() > 1024 * 1024) {
                throw new IOException("request head too large");
            }
        }
        return null;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(64 * 1024);
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0) {
            buf.write(b, 0, n);
        }
        return buf.toByteArray();
    }

    private static int indexOf(byte[] data, byte[] needle) {
        outer:
        for (int i = 0; i <= data.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (data[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static File cacheFile(String host, String method, String path) {
        String key = host + " " + method + " " + path;
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-1");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        byte[] d = md.digest(key.getBytes(Charset.forName("UTF-8")));
        StringBuilder sb = new StringBuilder();
        for (byte x : d) {
            sb.append(String.format("%02x", x & 0xff));
        }
        return new File(cacheDir, sb.toString() + ".bin");
    }

    private static byte[] readFile(File f) throws IOException {
        byte[] data = new byte[(int) f.length()];
        FileInputStream in = new FileInputStream(f);
        try {
            int off = 0;
            while (off < data.length) {
                int n = in.read(data, off, data.length - off);
                if (n < 0) {
                    break;
                }
                off += n;
            }
        } finally {
            in.close();
        }
        return data;
    }

    private static void writeFile(File f, byte[] data) throws IOException {
        File tmp = new File(f.getAbsolutePath() + ".tmp");
        FileOutputStream out = new FileOutputStream(tmp);
        try {
            out.write(data);
            out.flush();
            out.getFD().sync();
        } finally {
            out.close();
        }
        if (!tmp.renameTo(f)) {
            f.delete();
            tmp.renameTo(f);
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
                // ignore
            }
        }
    }

    private static void log(String msg) {
        String time = new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date());
        System.out.println("[" + time + "] " + msg);
        System.out.flush();
    }
}
