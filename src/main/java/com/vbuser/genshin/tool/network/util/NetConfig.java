package com.vbuser.genshin.tool.network.util;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public final class NetConfig {

    /** Maximum encoded payload bytes held in one aggregation batch. */
    public static int AGGREGATE_MAX_BYTES = 32768;

    /** Maximum time a batch may wait before it is flushed. */
    public static long AGGREGATE_MAX_DELAY_MS = 20;

    /** Hard upper bound on the number of packets in one batch. */
    public static int AGGREGATE_MAX_PACKETS = 4096;

    /** Wire magic/version; reject batches from a different build. */
    public static final byte PROTOCOL_MAGIC = 0x4E;
    public static final byte PROTOCOL_VERSION = 2;

    public static final String CHANNEL_NAME = "improved_channel";

    /** Dedicated channel for hijacked vanilla play packets. */
    public static final String VANILLA_CHANNEL_NAME = "improved_vanilla";

    /** Vanilla packet bundle format. */
    public static final byte VANILLA_MAGIC = 0x56;
    public static final byte VANILLA_VERSION = 1;

    /** Soft size/count targets of one batch, before compression. */
    public static int VANILLA_MAX_BYTES = 65536;
    public static int VANILLA_MAX_PACKETS = 4096;
    public static int VANILLA_ZSTD_THRESHOLD = 64;

    /**
     * Hard limit of the whole decoded bundle, header and every entry included.
     * Both sides use this exact limit: the receiver rejects anything larger, so
     * the sender must never build a batch that crosses it. A single entry that
     * cannot fit the limit even alone goes out on the vanilla path instead.
     */
    public static int VANILLA_MAX_RAW_BYTES = 8 * 1024 * 1024;

    /**
     * Fixed prefix every raw bundle starts with: magic, version and at least a
     * one byte packet count.
     */
    public static int VANILLA_BUNDLE_HEADER_BYTES = 3;

    /** Exact decoded size, including the variable-width packet count. */
    public static int vanillaBundleBytes(int packetCount, int entriesBytes) {
        return 2 + VarInt.sizeOf(packetCount) + entriesBytes;
    }

    /**
     * Below this size a single queued packet is cheaper to send directly than
     * to wrap in a bundle carrier. Only used when exactly one packet ended up in
     * the batch; latency critical types never reach the queue at all.
     */
    public static int VANILLA_DIRECT_SMALL_BYTES = 64;

    /**
     * Packet types that are small, latency critical and must not wait for a
     * batch. They are recognised by simple class name before serialization, so
     * they never pay for an encode that is then thrown away.
     */
    private static final Set<String> VANILLA_DIRECT_NAMES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    "SPacketKeepAlive",
                    "CPacketKeepAlive",
                    "SPacketPlayerPosLook",
                    "CPacketConfirmTeleport")));

    /** Movement/action packets share the {@code CPacketPlayer} name family. */
    private static final String VANILLA_DIRECT_PREFIX = "CPacketPlayer";

    /** Set false to disable the experimental vanilla-packet interception. */
    public static boolean ENABLE_VANILLA_HIJACK = true;

    /** True when this packet must skip the aggregation queue entirely. */
    public static boolean isVanillaDirect(String simpleClassName) {
        return VANILLA_DIRECT_NAMES.contains(simpleClassName)
                || simpleClassName.startsWith(VANILLA_DIRECT_PREFIX);
    }

    /** Exact wire size of one entry inside a decoded bundle. */
    public static int vanillaEntryBytes(int packetId, int payloadLength) {
        return VarInt.sizeOf(packetId) + VarInt.sizeOf(payloadLength) + payloadLength;
    }

    private NetConfig() {}
}
