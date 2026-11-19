package com.vbuser.genshin.tool.cg;

/**
 * Wire constants shared by the cg download request, the chunk stream and the client cache.
 *
 * <p>They live in one place so the server and the client agree on the limits that are
 * checked before a chunk or a file is allocated, and on the meaning of the content
 * version strings carried by {@code PlayCGPacket}, {@code RequestCGFilePacket} and
 * {@code CGFileChunkPacket}.</p>
 */
public final class CGProtocol {

    /** Payload size of every chunk except the last one. */
    public static final int CHUNK_SIZE = 16 * 1024;
    /** Hard cap for one chunk, checked before a chunk array is allocated. */
    public static final int MAX_CHUNK_BYTES = 64 * 1024;
    /** Hard cap for a cg file; larger requests are rejected on both sides. */
    public static final long MAX_FILE_BYTES = 512L * 1024L * 1024L;
    /** Hard cap for the chunk count implied by {@link #MAX_FILE_BYTES}. */
    public static final int MAX_CHUNKS = (int) ((MAX_FILE_BYTES + CHUNK_SIZE - 1) / CHUNK_SIZE);
    /** Hard cap for a cg file name on the wire (200 characters, up to 4 bytes each). */
    public static final int MAX_NAME_BYTES = 1024;
    /** Suffix of a partially downloaded file; such files are never listed or played. */
    public static final String PART_SUFFIX = ".part";

    /**
     * Version of a resource that is trusted as-is: a hand-installed local file, or a
     * sender that does not know a content version. Any local copy satisfies it.
     */
    public static final String VERSION_LOCAL = "local";
    /** No version information at all (older senders, or a plain local request). */
    public static final String VERSION_NONE = "";

    /** True for the versions that mean "play whatever is on disk". */
    public static boolean isLocalVersion(String version) {
        return version == null || version.isEmpty() || VERSION_LOCAL.equals(version);
    }

    /**
     * Validates the transfer metadata declared by the first chunk.
     *
     * @return true when the chunk count matches the declared byte size and all values are
     *         inside the hard caps
     */
    public static boolean isValidLayout(int totalChunks, int chunkSize, long totalBytes) {
        if (chunkSize <= 0 || chunkSize > MAX_CHUNK_BYTES) {
            return false;
        }
        if (totalBytes <= 0L || totalBytes > MAX_FILE_BYTES) {
            return false;
        }
        if (totalChunks <= 0 || totalChunks > MAX_CHUNKS) {
            return false;
        }
        long expectedChunks = (totalBytes + chunkSize - 1L) / chunkSize;
        return expectedChunks == totalChunks;
    }

    /** Declared payload size of the chunk at {@code index}. */
    public static int chunkLength(int index, int chunkSize, long totalBytes) {
        long offset = (long) index * (long) chunkSize;
        long remaining = totalBytes - offset;
        return (int) Math.min((long) chunkSize, remaining);
    }

    private CGProtocol() {
    }
}
