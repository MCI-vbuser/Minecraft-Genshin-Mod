package com.vbuser.genshin.tool.network.util;

import com.vbuser.genshin.tool.network.AggregatedPacket;
import com.vbuser.genshin.tool.network.ModPacket;
import com.vbuser.genshin.tool.network.PacketRegistry;
import com.vbuser.genshin.tool.network.util.sender.NetworkSender;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class PacketQueueManager {
    private final Map<Object, QueueEntry> queues = new ConcurrentHashMap<>();

    private static class QueueEntry {
        final List<ModPacket> packets = new ArrayList<>();
        int totalBytes = 0;
        long firstTimestamp = 0;
    }

    private final NetworkSender sender;

    public PacketQueueManager(NetworkSender sender) {
        this.sender = sender;
    }

    public void addPacket(Object target, ModPacket packet) {
        if (packet.neverAggregate()) {
            sender.sendImmediately(target, packet);
            return;
        }

        QueueEntry entry = queues.computeIfAbsent(target, k -> new QueueEntry());
        synchronized (entry) {
            ByteBuf temp = Unpooled.buffer();
            int id = PacketRegistry.getId(packet.getClass());
            VarInt.writeVarInt(temp, id);
            packet.encode(temp);
            int size = temp.readableBytes();
            temp.release();

            entry.packets.add(packet);
            entry.totalBytes += size;
            if (entry.firstTimestamp == 0) {
                entry.firstTimestamp = System.currentTimeMillis();
            }
            if (entry.totalBytes >= NetConfig.AGGREGATE_MAX_BYTES) {
                flush(target);
            }
        }
    }

    public void flush(Object target) {
        QueueEntry entry = queues.get(target);
        if (entry == null) return;
        List<ModPacket> toSend;
        synchronized (entry) {
            if (entry.packets.isEmpty()) return;
            toSend = new ArrayList<>(entry.packets);
            entry.packets.clear();
            entry.totalBytes = 0;
            entry.firstTimestamp = 0;
        }
        AggregatedPacket aggregated = new AggregatedPacket(toSend);
        sender.send(target, aggregated);
    }

    public void tick() {
        long now = System.currentTimeMillis();
        for (Map.Entry<Object, QueueEntry> e : queues.entrySet()) {
            Object target = e.getKey();
            QueueEntry entry = e.getValue();
            if (entry.firstTimestamp != 0 && (now - entry.firstTimestamp) >= NetConfig.AGGREGATE_MAX_DELAY_MS) {
                flush(target);
            }
        }
    }

    public void flushAll() {
        for (Object target : queues.keySet()) {
            flush(target);
        }
    }
}
