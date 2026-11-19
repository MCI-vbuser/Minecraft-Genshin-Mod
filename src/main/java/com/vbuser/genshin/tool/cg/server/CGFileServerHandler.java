package com.vbuser.genshin.tool.cg.server;

import com.vbuser.genshin.tool.cg.CGFileManager;
import com.vbuser.genshin.tool.cg.CGProtocol;
import com.vbuser.genshin.tool.cg.network.CGFileChunkPacket;
import com.vbuser.genshin.tool.cg.network.CGFileRejectPacket;
import com.vbuser.genshin.tool.network.NetworkManager;
import net.minecraft.entity.player.EntityPlayerMP;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Streams a cg file to one client.
 *
 * <p>Names are resolved by {@link CGFileManager#resolve(String)}, which rejects traversal
 * and absolute paths, and transfers run on a bounded pool so a client cannot exhaust
 * server threads.</p>
 *
 * <p>Every rejection path (illegal name, missing file, version mismatch, too many streams,
 * unreadable file) answers with a {@link CGFileRejectPacket}. A silent return used to leave
 * the client waiting for a first chunk until its stall timeout, so the download failure was
 * never reported to the caller.</p>
 */
public class CGFileServerHandler {

    private static final Logger LOGGER = LogManager.getLogger("CGFileServer");
    public static final int CHUNK_SIZE = CGProtocol.CHUNK_SIZE;
    private static final int MAX_ACTIVE_STREAMS = 4;

    private static final AtomicInteger ACTIVE = new AtomicInteger();
    private static final ExecutorService STREAM_POOL = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "CGFileStream");
        thread.setDaemon(true);
        return thread;
    });

    public static void handleRequest(EntityPlayerMP player, String fileName) {
        handleRequest(player, fileName, CGProtocol.VERSION_NONE);
    }

    public static void handleRequest(EntityPlayerMP player, String fileName, String requestedVersion) {
        handleRequest(player, fileName, requestedVersion, 0L);
    }

    public static void handleRequest(EntityPlayerMP player, String fileName, String requestedVersion,
                                     long sessionId) {
        if (player == null) {
            return;
        }
        if (!CGFileManager.isValidName(fileName)) {
            reject(player, fileName, sessionId, "illegal cg file name");
            return;
        }
        File file;
        try {
            file = CGFileManager.getFile(fileName);
        } catch (IllegalArgumentException e) {
            reject(player, fileName, sessionId, "illegal cg file name");
            return;
        }
        if (!file.isFile()) {
            reject(player, fileName, sessionId, "the server does not have this cg file");
            return;
        }
        long totalBytes = file.length();
        if (totalBytes <= 0L || totalBytes > CGProtocol.MAX_FILE_BYTES) {
            reject(player, fileName, sessionId, "the cg file size is not acceptable");
            return;
        }
        String version = CGFileManager.fingerprint(file);
        if (version == null) {
            reject(player, fileName, sessionId, "the server could not read this cg file");
            return;
        }
        if (!CGProtocol.isLocalVersion(requestedVersion) && !requestedVersion.equals(version)) {
            // The file changed after the client was told which version to expect; never
            // stream bytes that do not match the announced content.
            reject(player, fileName, sessionId, "the cg file changed on the server");
            return;
        }
        if (ACTIVE.incrementAndGet() > MAX_ACTIVE_STREAMS) {
            ACTIVE.decrementAndGet();
            reject(player, fileName, sessionId, "the server is busy sending cg files");
            return;
        }
        try {
            STREAM_POOL.execute(() -> stream(player, file, fileName, version, sessionId));
        } catch (RejectedExecutionException e) {
            ACTIVE.decrementAndGet();
            reject(player, fileName, sessionId, "the server is busy sending cg files");
        }
    }

    private static void stream(EntityPlayerMP player, File file, String fileName, String version,
                               long sessionId) {
        long totalBytes = file.length();
        int totalChunks = (int) ((totalBytes + CHUNK_SIZE - 1L) / CHUNK_SIZE);
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[CHUNK_SIZE];
            for (int index = 0; index < totalChunks; index++) {
                int length = CGProtocol.chunkLength(index, CHUNK_SIZE, totalBytes);
                readFully(input, buffer, length);
                byte[] chunk = new byte[length];
                System.arraycopy(buffer, 0, chunk, 0, length);
                NetworkManager.sendToPlayer(new CGFileChunkPacket(fileName, index, totalChunks,
                        CHUNK_SIZE, totalBytes, version, sessionId, chunk), player);
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to stream cg {} to {}", fileName, player.getName(), e);
            // Fail the transfer explicitly instead of letting the client time out.
            reject(player, fileName, sessionId, "the server could not finish sending this cg file");
        } finally {
            ACTIVE.decrementAndGet();
        }
    }

    private static void readFully(FileInputStream input, byte[] buffer, int length)
            throws java.io.IOException {
        int offset = 0;
        while (offset < length) {
            int read = input.read(buffer, offset, length - offset);
            if (read < 0) {
                throw new EOFException("cg file ended before all declared bytes were read");
            }
            offset += read;
        }
    }

    private static void reject(EntityPlayerMP player, String fileName, long sessionId, String reason) {
        LOGGER.warn("Rejecting cg request '{}' from {}: {}", fileName, player.getName(), reason);
        try {
            NetworkManager.sendToPlayer(new CGFileRejectPacket(fileName, reason, sessionId), player);
        } catch (Throwable t) {
            LOGGER.warn("Could not send the cg rejection for {} to {}", fileName, player.getName(), t);
        }
    }
}
