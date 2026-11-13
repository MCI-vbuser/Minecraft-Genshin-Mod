package com.vbuser.genshin.tool.network.util;

import com.vbuser.genshin.tool.network.AggregatedPacket;
import com.vbuser.genshin.tool.network.ModPacket;
import com.vbuser.genshin.tool.network.PacketRegistry;
import com.vbuser.genshin.tool.network.util.sender.NetworkSender;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Per-target packet aggregation queue.
 *
 * <p>Packets are encoded once to measure their wire size, then the original
 * packet objects are queued. A batch is flushed on size/count limits or the
 * configured delay. A non-aggregatable packet flushes the pending queue first
 * so packet ordering is preserved.</p>
 */
public class PacketQueueManager {

    private static final Logger LOGGER = LogManager.getLogger("genshin-network");

    private final Map<Object, QueueEntry> queues = new ConcurrentHashMap<>();
    private final NetworkSender sender;

    private static final class QueueEntry {
        final Queue<ModPacket> packets = new ConcurrentLinkedQueue<>();
        int totalBytes;
        int packetCount;
        long firstTimestamp;
    }

    public PacketQueueManager(NetworkSender sender) {
        this.sender = sender;
    }

    public void addPacket(Object target, ModPacket packet) {
        if (target == null || packet == null) {
            return;
        }

        if (packet.neverAggregate()) {
            // Preserve ordering: everything queued before this packet must go
            // out before the immediate packet.
            flush(target);
            try {
                sender.sendImmediately(target, packet);
            } catch (Throwable t) {
                LOGGER.error("Failed to send immediate packet {}", packet.getClass().getName(), t);
            }
            return;
        }

        int size = encodedSize(packet);
        if (size < 0) {
            return;
        }

        QueueEntry entry = queues.computeIfAbsent(target, key -> new QueueEntry());
        boolean full;
        synchronized (entry) {
            entry.packets.add(packet);
            entry.totalBytes += size;
            entry.packetCount++;
            if (entry.firstTimestamp == 0) {
                entry.firstTimestamp = System.currentTimeMillis();
            }
            full = entry.totalBytes >= NetConfig.AGGREGATE_MAX_BYTES
                    || entry.packetCount >= NetConfig.AGGREGATE_MAX_PACKETS;
        }
        if (full) {
            flush(target);
        }
    }

    public void flush(Object target) {
        QueueEntry entry = queues.get(target);
        if (entry == null) {
            return;
        }

        List<ModPacket> toSend;
        synchronized (entry) {
            if (entry.packets.isEmpty()) {
                return;
            }
            toSend = new ArrayList<>(entry.packets);
            entry.packets.clear();
            entry.totalBytes = 0;
            entry.packetCount = 0;
            entry.firstTimestamp = 0;
        }

        try {
            sender.send(target, new AggregatedPacket(toSend));
        } catch (Throwable t) {
            LOGGER.error("Failed to flush {} aggregated packets to {}", toSend.size(), target, t);
        }

        synchronized (entry) {
            if (entry.packets.isEmpty()) {
                queues.remove(target, entry);
            }
        }
    }

    public void tick() {
        long now = System.currentTimeMillis();
        for (Map.Entry<Object, QueueEntry> mapEntry : new ArrayList<>(queues.entrySet())) {
            QueueEntry entry = mapEntry.getValue();
            boolean due;
            synchronized (entry) {
                due = entry.firstTimestamp != 0
                        && now - entry.firstTimestamp >= NetConfig.AGGREGATE_MAX_DELAY_MS;
            }
            if (due) {
                flush(mapEntry.getKey());
            }
        }
    }

    public void flushAll() {
        for (Object target : new ArrayList<>(queues.keySet())) {
            flush(target);
        }
    }

    /**
     * Flush and forget a target (used on disconnect/logout).
     */
    public void remove(Object target) {
        flush(target);
        queues.remove(target);
    }

    public int queuedTargets() {
        return queues.size();
    }

    private int encodedSize(ModPacket packet) {
        ByteBuf temp = Unpooled.buffer();
        try {
            int id = PacketRegistry.getId(packet.getClass());
            packet.encode(temp);
            int payloadSize = temp.readableBytes();
            return VarInt.sizeOf(id) + VarInt.sizeOf(payloadSize) + payloadSize;
        } catch (Throwable t) {
            LOGGER.error("Failed to encode packet {}", packet.getClass().getName(), t);
            return -1;
        } finally {
            temp.release();
        }
    }
}
