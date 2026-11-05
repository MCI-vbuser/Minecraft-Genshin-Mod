package com.vbuser.genshin.tool.network.benchmark;

import com.vbuser.genshin.tool.network.AggregatedPacket;
import com.vbuser.genshin.tool.network.ModPacket;
import com.vbuser.genshin.tool.network.PacketRegistry;
import com.vbuser.genshin.tool.network.VanillaBundlePacket;
import com.vbuser.genshin.tool.network.util.NetConfig;
import com.vbuser.genshin.tool.network.util.PacketQueueManager;
import com.vbuser.genshin.tool.network.util.VarInt;
import com.vbuser.genshin.tool.network.util.sender.NetworkSender;
import com.github.luben.zstd.Zstd;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.zip.Deflater;

/**
 * Standalone experiment for the mod packet aggregation layer.
 *
 * <p>It compares three wire models for the same packet stream:</p>
 * <ul>
 *   <li>baseline: every ModPacket is its own FMLProxyPacket, compressed by
 *       vanilla only when the frame reaches 256 bytes;</li>
 *   <li>ours: the actual {@link PacketQueueManager} + {@link AggregatedPacket}
 *       implementation, then the same vanilla frame compression;</li>
 *   <li>NEB-like: NotEnoughBandwidth's aggregation layout (type prefix +
 *       length + payload, one compressed frame) with zlib as the available
 *       proxy for its Zstd compressor.</li>
 * </ul>
 *
 * <p>The benchmark is intentionally standalone: no Minecraft world or client
 * is needed, and the real aggregation/queue classes are exercised.</p>
 */
public final class NetworkBenchmark {

    private static final int PACKET_COUNT = 3000;
    private static final int VANILLA_COMPRESSION_THRESHOLD = 256;
    private static final int NEB_COMPRESSION_THRESHOLD = 32;

    public static void main(String[] args) {
        registerBenchmarkPackets();

        List<ModPacket> packets = buildWorkload(PACKET_COUNT);
        long rawPayload = payloadBytes(packets);

        long baseline = baselineWireBytes(packets);
        long oursOneBatch = oneBatchOurs(packets);
        long oursVanillaBundle = vanillaBundleWireBytes(packets);
        byte[] vanillaRawBytes = vanillaRaw(packets);
        long vanillaZstdOnly = wrappedFrameSize(zstd(vanillaRawBytes), NEB_COMPRESSION_THRESHOLD);
        long zstd3 = wrappedFrameSize(zstd(vanillaRawBytes, 3), NEB_COMPRESSION_THRESHOLD);
        long zstd9 = wrappedFrameSize(zstd(vanillaRawBytes, 9), NEB_COMPRESSION_THRESHOLD);
        long zstd19 = wrappedFrameSize(zstd(vanillaRawBytes, 19), NEB_COMPRESSION_THRESHOLD);
        long nebLike = nebLikeWireBytes(packets);
        long oursQueueDefault = queueWireBytes(packets);

        System.out.println("=== Network aggregation benchmark ===");
        System.out.println("packets=" + packets.size()
                + " rawPayload=" + rawPayload
                + " registryHash=" + Integer.toHexString(PacketRegistry.getProtocolHash()));
        System.out.println();
        System.out.printf("baseline (per-packet frames): %8d bytes  (%.1f%% of raw payload)%n",
                baseline, 100.0 * baseline / rawPayload);
        System.out.printf("ours (one aggregated frame):  %8d bytes  (%.1f%% of raw payload, %.1f%% of baseline)%n",
                oursOneBatch, 100.0 * oursOneBatch / rawPayload, 100.0 * oursOneBatch / baseline);
        System.out.printf("ours (vanilla bundle, best):  %8d bytes  (%.1f%% of raw payload, %.1f%% of baseline)%n",
                oursVanillaBundle, 100.0 * oursVanillaBundle / rawPayload, 100.0 * oursVanillaBundle / baseline);
        System.out.printf("vanilla aggregate, zstd-only: %8d bytes  (%.1f%% of raw payload, %.1f%% of baseline)%n",
                vanillaZstdOnly, 100.0 * vanillaZstdOnly / rawPayload, 100.0 * vanillaZstdOnly / baseline);
        System.out.printf("vanilla zstd levels 3/9/19:    %8d / %8d / %8d bytes%n", zstd3, zstd9, zstd19);
        System.out.printf("NEB-like (zstd):               %8d bytes  (%.1f%% of raw payload, %.1f%% of baseline)%n",
                nebLike, 100.0 * nebLike / rawPayload, 100.0 * nebLike / baseline);
        System.out.printf("ours (default 32KiB queue):    %8d bytes  (%.1f%% of raw payload, %.1f%% of baseline)%n",
                oursQueueDefault, 100.0 * oursQueueDefault / rawPayload, 100.0 * oursQueueDefault / baseline);
        System.out.printf("ours vanilla / NEB-like:       %.2f%%%n", 100.0 * oursVanillaBundle / nebLike);
        System.out.printf("ours app / NEB-like:           %.2f%%%n", 100.0 * oursOneBatch / nebLike);

        boolean decodeOk = verifyOneBatchDecode(packets);
        System.out.println("decode one aggregated batch: " + (decodeOk ? "OK" : "FAILED"));
        System.out.println("decode one vanilla bundle: " + (verifyVanillaBundleDecode(packets) ? "OK" : "FAILED"));
        System.out.println("ordering (queued -> immediate): " + (verifyOrdering() ? "OK" : "FAILED"));
        System.out.println("batch count (32KiB default queue): " + queueBatches(packets));
        System.out.println("queued targets after flush: " + queueTargetsAfterFlush(packets));
    }

    private static void registerBenchmarkPackets() {
        PacketRegistry.register(BenchA.class);
        PacketRegistry.register(BenchB.class);
        PacketRegistry.register(BenchC.class);
        PacketRegistry.register(ImmediatePacket.class);
    }

    private static List<ModPacket> buildWorkload(int count) {
        List<ModPacket> packets = new ArrayList<>(count);
        Random random = new Random(0x5EEDBEEFL);
        for (int i = 0; i < count; i++) {
            int kind = i % 10;
            if (kind < 7) {
                packets.add(new BenchA(bytes(payloadA(i, random))));
            } else if (kind < 9) {
                packets.add(new BenchB(bytes(payloadB(i, random))));
            } else {
                packets.add(new BenchC(bytes(payloadC(i, random))));
            }
        }
        return packets;
    }

    private static String payloadA(int i, Random random) {
        return pad("{\"rule\":\"rule_" + (i % 32) + "\",\"value\":\"" + (i % 3) + "\",\"v\":" + random.nextInt(100) + "}", 24 + random.nextInt(40));
    }

    private static String payloadB(int i, Random random) {
        return pad("{\"player\":\"Player" + (i % 64) + "\",\"x\":" + random.nextInt(30000000)
                + ",\"y\":" + (random.nextInt(256)) + "}", 96 + random.nextInt(160));
    }

    private static String payloadC(int i, Random random) {
        StringBuilder builder = new StringBuilder();
        while (builder.length() < 400 + random.nextInt(600)) {
            builder.append("column_").append(i % 17).append('|').append(i % 5).append(';');
        }
        return builder.toString();
    }

    private static String pad(String value, int length) {
        if (value.length() >= length) {
            return value.substring(0, length);
        }
        char[] chars = new char[length];
        for (int i = 0; i < length; i++) {
            chars[i] = i < value.length() ? value.charAt(i) : ' ';
        }
        return new String(chars);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static long payloadBytes(List<ModPacket> packets) {
        long total = 0;
        for (ModPacket packet : packets) {
            total += encodedPayload(packet).length;
        }
        return total;
    }

    private static byte[] encodedPayload(ModPacket packet) {
        ByteBuf buffer = Unpooled.buffer();
        try {
            packet.encode(buffer);
            byte[] data = new byte[buffer.readableBytes()];
            buffer.readBytes(data);
            return data;
        } finally {
            buffer.release();
        }
    }

    private static int packetId(ModPacket packet) {
        return PacketRegistry.getId(packet.getClass());
    }

    private static long baselineWireBytes(List<ModPacket> packets) {
        long total = 0;
        for (ModPacket packet : packets) {
            ByteBuf single = Unpooled.buffer();
            try {
                VarInt.writeVarInt(single, packetId(packet));
                single.writeBytes(encodedPayload(packet));
                byte[] data = new byte[single.readableBytes()];
                single.readBytes(data);
                total += wrappedFrameSize(data, VANILLA_COMPRESSION_THRESHOLD);
            } finally {
                single.release();
            }
        }
        return total;
    }

    private static long oneBatchOurs(List<ModPacket> packets) {
        AggregatedPacket aggregated = new AggregatedPacket(new ArrayList<>(packets));
        ByteBuf buffer = Unpooled.buffer();
        try {
            aggregated.toBytes(buffer);
            byte[] data = new byte[buffer.readableBytes()];
            buffer.readBytes(data);
            return wrappedFrameSize(data, NEB_COMPRESSION_THRESHOLD);
        } finally {
            buffer.release();
        }
    }

    private static long vanillaBundleWireBytes(List<ModPacket> packets) {
        byte[] raw = vanillaRaw(packets);
        VanillaBundlePacket bundle = new VanillaBundlePacket(raw);
        ByteBuf buffer = Unpooled.buffer();
        try {
            bundle.toBytes(buffer);
            byte[] data = new byte[buffer.readableBytes()];
            buffer.readBytes(data);
            return wrappedFrameSize(data, NEB_COMPRESSION_THRESHOLD);
        } finally {
            buffer.release();
        }
    }

    private static long nebLikeWireBytes(List<ModPacket> packets) {
        ByteBuf raw = Unpooled.buffer();
        try {
            for (ModPacket packet : packets) {
                byte[] payload = encodedPayload(packet);
                VarInt.writeVarInt(raw, packetId(packet));
                VarInt.writeVarInt(raw, payload.length);
                raw.writeBytes(payload);
            }
            byte[] data = new byte[raw.readableBytes()];
            raw.readBytes(data);
            return wrappedFrameSize(zstd(data), NEB_COMPRESSION_THRESHOLD);
        } finally {
            raw.release();
        }
    }

    private static byte[] vanillaRaw(List<ModPacket> packets) {
        ByteBuf raw = Unpooled.buffer();
        try {
            raw.writeByte(NetConfig.VANILLA_MAGIC);
            raw.writeByte(NetConfig.VANILLA_VERSION);
            VarInt.writeVarInt(raw, packets.size());
            for (ModPacket packet : packets) {
                byte[] payload = encodedPayload(packet);
                VarInt.writeVarInt(raw, packetId(packet));
                VarInt.writeVarInt(raw, payload.length);
                raw.writeBytes(payload);
            }
            byte[] data = new byte[raw.readableBytes()];
            raw.readBytes(data);
            return data;
        } finally {
            raw.release();
        }
    }

    private static boolean verifyVanillaBundleDecode(List<ModPacket> packets) {
        byte[] raw = vanillaRaw(packets);
        VanillaBundlePacket bundle = new VanillaBundlePacket(raw);
        ByteBuf buffer = Unpooled.buffer();
        try {
            bundle.toBytes(buffer);
            VanillaBundlePacket decoded = new VanillaBundlePacket();
            decoded.fromBytes(buffer);
            return Arrays.equals(raw, decoded.getRaw());
        } finally {
            buffer.release();
        }
    }

    private static byte[] zstd(byte[] data) {
        return zstd(data, 3);
    }

    private static byte[] zstd(byte[] data, int level) {
        try {
            byte[] compressed = Zstd.compress(data, level);
            return compressed.length < data.length ? compressed : data;
        } catch (Throwable ignored) {
            return data;
        }
    }

    private static long queueWireBytes(List<ModPacket> packets) {
        CapturingSender sender = new CapturingSender();
        PacketQueueManager queue = new PacketQueueManager(sender);
        for (ModPacket packet : packets) {
            queue.addPacket("benchmark", packet);
        }
        queue.flushAll();
        return sender.bytes;
    }

    private static int queueBatches(List<ModPacket> packets) {
        CapturingSender sender = new CapturingSender();
        PacketQueueManager queue = new PacketQueueManager(sender);
        for (ModPacket packet : packets) {
            queue.addPacket("benchmark", packet);
        }
        queue.flushAll();
        return sender.batches;
    }

    private static int queueTargetsAfterFlush(List<ModPacket> packets) {
        CapturingSender sender = new CapturingSender();
        PacketQueueManager queue = new PacketQueueManager(sender);
        for (ModPacket packet : packets) {
            queue.addPacket("benchmark", packet);
        }
        queue.flushAll();
        return queue.queuedTargets();
    }

    private static boolean verifyOrdering() {
        RecordingSender sender = new RecordingSender();
        PacketQueueManager queue = new PacketQueueManager(sender);
        queue.addPacket("ordering", new BenchA(bytes("queued")));
        queue.addPacket("ordering", new ImmediatePacket());
        queue.flushAll();
        return sender.events.size() == 2
                && sender.events.get(0).startsWith("batch")
                && sender.events.get(1).startsWith("immediate");
    }

    private static boolean verifyOneBatchDecode(List<ModPacket> packets) {
        AggregatedPacket aggregated = new AggregatedPacket(new ArrayList<>(packets));
        ByteBuf buffer = Unpooled.buffer();
        try {
            aggregated.toBytes(buffer);
            AggregatedPacket decoded = new AggregatedPacket();
            decoded.fromBytes(buffer);
            if (decoded.getPackets().size() != packets.size()) {
                return false;
            }
            for (int i = 0; i < packets.size(); i++) {
                ModPacket expected = packets.get(i);
                ModPacket actual = decoded.getPackets().get(i);
                if (expected.getClass() != actual.getClass()) {
                    return false;
                }
                if (!Arrays.equals(encodedPayload(expected), encodedPayload(actual))) {
                    return false;
                }
            }
            return true;
        } finally {
            buffer.release();
        }
    }

    private static int wrappedFrameSize(byte[] payload, int compressionThreshold) {
        byte[] channel = NetConfig.CHANNEL_NAME.getBytes(StandardCharsets.UTF_8);
        int channelHeader = VarInt.sizeOf(channel.length) + channel.length;
        byte[] framed = new byte[channelHeader + 1 + payload.length];
        int cursor = writeVarInt(channel.length, framed, 0);
        System.arraycopy(channel, 0, framed, cursor, channel.length);
        cursor += channel.length;
        framed[cursor++] = 0; // SimpleNetworkWrapper discriminator
        System.arraycopy(payload, 0, framed, cursor, payload.length);

        if (framed.length < compressionThreshold) {
            return 4 + framed.length;
        }
        int compressed = deflate(framed);
        return 4 + VarInt.sizeOf(framed.length) + compressed;
    }

    private static int writeVarInt(int value, byte[] target, int offset) {
        int cursor = offset;
        while ((value & ~0x7F) != 0) {
            target[cursor++] = (byte) ((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        target[cursor++] = (byte) value;
        return cursor;
    }

    /**
     * 1.12.2 vanilla compression framing: outer length + uncompressed-size
     * varint + zlib bytes. This is a byte-count comparison, not a live
     * NetworkManager interception.
     */
    private static int deflate(byte[] payload) {
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);
        try {
            deflater.setInput(payload);
            deflater.finish();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            while (!deflater.finished()) {
                int count = deflater.deflate(buffer);
                output.write(buffer, 0, count);
            }
            return output.size();
        } finally {
            deflater.end();
        }
    }

    private static final class CapturingSender implements NetworkSender {
        long bytes;
        int batches;

        @Override
        public void sendImmediately(Object target, ModPacket packet) {
            ByteBuf buffer = Unpooled.buffer();
            try {
                VarInt.writeVarInt(buffer, packetId(packet));
                buffer.writeBytes(encodedPayload(packet));
                byte[] data = new byte[buffer.readableBytes()];
                buffer.readBytes(data);
                bytes += wrappedFrameSize(data, NEB_COMPRESSION_THRESHOLD);
                batches++;
            } finally {
                buffer.release();
            }
        }

        @Override
        public void send(Object target, AggregatedPacket aggregated) {
            ByteBuf buffer = Unpooled.buffer();
            try {
                aggregated.toBytes(buffer);
                byte[] data = new byte[buffer.readableBytes()];
                buffer.readBytes(data);
                bytes += wrappedFrameSize(data, NEB_COMPRESSION_THRESHOLD);
                batches++;
            } finally {
                buffer.release();
            }
        }
    }

    private static final class RecordingSender implements NetworkSender {
        final List<String> events = new ArrayList<>();

        @Override
        public void sendImmediately(Object target, ModPacket packet) {
            events.add("immediate:" + packet.getClass().getSimpleName());
        }

        @Override
        public void send(Object target, AggregatedPacket aggregated) {
            events.add("batch:" + aggregated.getPackets().size());
        }
    }

    public static final class ImmediatePacket extends ModPacket {
        public ImmediatePacket() {}

        @Override
        public void encode(ByteBuf buf) {
            buf.writeByte(0x5A);
        }

        @Override
        public void decode(ByteBuf buf) {
            buf.readByte();
        }

        @Override
        public void handle(MessageContext ctx) {}

        @Override
        public boolean neverAggregate() {
            return true;
        }
    }

    public static final class BenchA extends ModPacket {
        private byte[] payload;

        public BenchA() {}

        BenchA(byte[] payload) {
            this.payload = payload;
        }

        @Override
        public void encode(ByteBuf buf) {
            buf.writeShort(payload.length);
            buf.writeBytes(payload);
        }

        @Override
        public void decode(ByteBuf buf) {
            payload = new byte[buf.readUnsignedShort()];
            buf.readBytes(payload);
        }

        @Override
        public void handle(MessageContext ctx) {}
    }

    public static final class BenchB extends ModPacket {
        private byte[] payload;

        public BenchB() {}

        BenchB(byte[] payload) {
            this.payload = payload;
        }

        @Override
        public void encode(ByteBuf buf) {
            buf.writeShort(payload.length);
            buf.writeBytes(payload);
        }

        @Override
        public void decode(ByteBuf buf) {
            payload = new byte[buf.readUnsignedShort()];
            buf.readBytes(payload);
        }

        @Override
        public void handle(MessageContext ctx) {}
    }

    public static final class BenchC extends ModPacket {
        private byte[] payload;

        public BenchC() {}

        BenchC(byte[] payload) {
            this.payload = payload;
        }

        @Override
        public void encode(ByteBuf buf) {
            buf.writeShort(payload.length);
            buf.writeBytes(payload);
        }

        @Override
        public void decode(ByteBuf buf) {
            payload = new byte[buf.readUnsignedShort()];
            buf.readBytes(payload);
        }

        @Override
        public void handle(MessageContext ctx) {}
    }

    private NetworkBenchmark() {}
}
