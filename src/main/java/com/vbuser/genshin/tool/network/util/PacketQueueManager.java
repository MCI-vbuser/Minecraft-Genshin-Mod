package com.vbuser.genshin.tool.network.util;

import com.vbuser.genshin.tool.network.AggregatedPacket;
import com.vbuser.genshin.tool.network.ModPacket;
import com.vbuser.genshin.tool.network.PacketRegistry;
import com.vbuser.genshin.tool.network.util.sender.NetworkSender;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-target packet aggregation queue.
 *
 * <p>Packets are encoded once to measure their wire size, then the original
 * packet objects are queued. A batch is flushed on size/count limits or the
 * configured delay. A non-aggregatable packet flushes the pending queue first
 * so packet ordering is preserved.</p>
 *
 * <p>One queue entry exists per target for the whole lifetime of the connection
 * and is only dropped by {@link #remove(Object)}. Removing an empty entry from
 * the map while a producer already held it was a lost-packet window: the
 * producer appended to an object the tick could no longer find. A single
 * drainer per entry now also serializes the actual sends, so two flushes from
 * different threads (the server tick and a background media transfer) can no
 * longer overtake each other, and the never-aggregate packets go through the
 * same ordering boundary instead of jumping the queue.</p>
 */
public class PacketQueueManager {

    private static final Logger LOGGER = LogManager.getLogger("genshin-network");

    private final Map<Object, QueueEntry> queues = new ConcurrentHashMap<>();
    private final NetworkSender sender;

    /** One closed batch, ready to be written to the network. */
    private static final class Batch {
        final List<ModPacket> packets;
        final boolean immediate;

        Batch(List<ModPacket> packets, boolean immediate) {
            this.packets = packets;
            this.immediate = immediate;
        }
    }

    private static final class QueueEntry {
        /** Closed batches, FIFO. Guarded by {@code this}. */
        final Deque<Batch> ready = new ArrayDeque<>();
        /** The batch that is still collecting packets. Guarded by {@code this}. */
        final List<ModPacket> open = new ArrayList<>();
        int openBytes;
        int openCount;
        long firstTimestamp;
        /** True while one thread is inside the send loop. Guarded by {@code this}. */
        boolean draining;
        /** Set by {@link #remove(Object)}; late producers are dropped. */
        boolean removed;
    }

    public PacketQueueManager(NetworkSender sender) {
        this.sender = sender;
    }

    public void addPacket(Object target, ModPacket packet) {
        if (target == null || packet == null) {
            return;
        }

        int size = 0;
        if (!packet.neverAggregate()) {
            size = encodedSize(packet);
            if (size < 0) {
                return;
            }
        }

        QueueEntry entry = queues.get(target);
        if (entry == null) {
            entry = queues.computeIfAbsent(target, key -> new QueueEntry());
        }

        synchronized (entry) {
            if (entry.removed) {
                return;
            }
            if (packet.neverAggregate()) {
                // Preserve ordering: everything queued before this packet goes
                // out before the immediate packet, through the same drainer.
                closeOpen(entry);
                entry.ready.add(new Batch(Collections.singletonList(packet), true));
            } else {
                entry.open.add(packet);
                entry.openBytes += size;
                entry.openCount++;
                if (entry.firstTimestamp == 0) {
                    entry.firstTimestamp = System.currentTimeMillis();
                }
                if (entry.openBytes >= NetConfig.AGGREGATE_MAX_BYTES
                        || entry.openCount >= NetConfig.AGGREGATE_MAX_PACKETS) {
                    closeOpen(entry);
                }
            }
        }
        drain(target, entry);
    }

    /**
     * Close the open batch and hand it to the drainer. Also used by the tick
     * when the batch deadline expired.
     */
    private static void closeOpen(QueueEntry entry) {
        if (entry.open.isEmpty()) {
            entry.firstTimestamp = 0;
            return;
        }
        entry.ready.add(new Batch(new ArrayList<>(entry.open), false));
        entry.open.clear();
        entry.openBytes = 0;
        entry.openCount = 0;
        entry.firstTimestamp = 0;
    }

    /**
     * Single-consumer send loop. Only one thread may be inside at a time, so
     * the batches leave in FIFO order even when several threads flush.
     */
    private void drain(Object target, QueueEntry entry) {
        synchronized (entry) {
            if (entry.draining) {
                return;
            }
            entry.draining = true;
        }
        while (true) {
            Batch batch;
            synchronized (entry) {
                batch = entry.ready.poll();
                if (batch == null) {
                    // Releasing the flag under the same lock is what makes a
                    // producer that appends right now re-trigger a drain.
                    entry.draining = false;
                    return;
                }
            }
            dispatch(target, batch);
        }
    }

    private void dispatch(Object target, Batch batch) {
        try {
            if (batch.immediate) {
                this.sender.sendImmediately(target, batch.packets.get(0));
            } else {
                this.sender.send(target, new AggregatedPacket(batch.packets));
            }
        } catch (Throwable t) {
            LOGGER.error("Failed to flush {} aggregated packets", batch.packets.size(), t);
        }
    }

    public void flush(Object target) {
        QueueEntry entry = queues.get(target);
        if (entry == null) {
            return;
        }
        synchronized (entry) {
            if (entry.removed) {
                return;
            }
            closeOpen(entry);
        }
        drain(target, entry);
    }

    public void tick() {
        long now = System.currentTimeMillis();
        for (Map.Entry<Object, QueueEntry> mapEntry : new ArrayList<>(queues.entrySet())) {
            QueueEntry entry = mapEntry.getValue();
            boolean due;
            synchronized (entry) {
                due = !entry.removed
                        && entry.firstTimestamp != 0
                        && now - entry.firstTimestamp >= NetConfig.AGGREGATE_MAX_DELAY_MS;
                if (due) {
                    closeOpen(entry);
                }
            }
            if (due) {
                drain(mapEntry.getKey(), entry);
            }
        }
    }

    public void flushAll() {
        for (Map.Entry<Object, QueueEntry> mapEntry : new ArrayList<>(queues.entrySet())) {
            QueueEntry entry = mapEntry.getValue();
            synchronized (entry) {
                closeOpen(entry);
            }
            drain(mapEntry.getKey(), entry);
        }
    }

    /**
     * Flush and forget a target (used on disconnect/logout). The entry stays
     * reachable to producers that already hold it, but it is marked removed so
     * nothing new is accepted.
     */
    public void remove(Object target) {
        QueueEntry entry = queues.remove(target);
        if (entry == null) {
            return;
        }
        synchronized (entry) {
            entry.removed = true;
            closeOpen(entry);
        }
        drain(target, entry);
    }

    /** Targets with work still waiting; the connection itself stays cached. */
    public int queuedTargets() {
        int count = 0;
        for (QueueEntry entry : queues.values()) {
            synchronized (entry) {
                if (!entry.open.isEmpty() || !entry.ready.isEmpty()) {
                    count++;
                }
            }
        }
        return count;
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
