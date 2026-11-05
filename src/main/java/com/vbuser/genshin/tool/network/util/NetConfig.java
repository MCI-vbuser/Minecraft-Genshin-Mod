package com.vbuser.genshin.tool.network.util;

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
    public static int VANILLA_MAX_BYTES = 65536;
    public static int VANILLA_MAX_PACKETS = 4096;
    public static int VANILLA_ZSTD_THRESHOLD = 64;
    public static int VANILLA_SMALL_PACKET_BYTES = 128;
    public static int VANILLA_MAX_RAW_BYTES = 8 * 1024 * 1024;

    /** Set false to disable the experimental vanilla-packet interception. */
    public static boolean ENABLE_VANILLA_HIJACK = true;

    private NetConfig() {}
}
