package com.vbuser.genshin.math;

import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The CPU side cloud shape: the field that {@code /cloudfill} bakes into cloud
 * blocks and that the volume renderer then reconstructs on the GPU.
 *
 * <p>Pure Java on purpose (no Minecraft imports): it is also compiled by the
 * standalone shader preview tool in {@code tools/cloudpreview}, so the preview
 * renders the same volume the game does.</p>
 */
public final class CloudShape {

    /** Domain warp strength, in noise units. */
    private static final float WARP = 12.0f;
    /** Noise cells per block for the base field. */
    private static final float FREQ_XZ = 0.04f;
    private static final float FREQ_Y = 0.05f;
    /** Below this weighted value the cell is empty. */
    public static final float THRESHOLD = 0.40f;

    /** Per-seed offsets so different worlds get different clouds. */
    private static final class Offsets {
        final float x, y, z;

        Offsets(long seed) {
            Random random = new Random(seed);
            this.x = random.nextFloat() * 10000.0f;
            this.y = random.nextFloat() * 10000.0f;
            this.z = random.nextFloat() * 10000.0f;
        }
    }

    private static final Map<Long, Offsets> OFFSETS = new ConcurrentHashMap<>();

    private CloudShape() {
    }

    private static Offsets offsets(long seed) {
        Offsets cached = OFFSETS.get(seed);
        if (cached != null) {
            return cached;
        }
        Offsets created = new Offsets(seed);
        if (OFFSETS.size() > 64) {
            OFFSETS.clear();
        }
        OFFSETS.put(seed, created);
        return created;
    }

    /**
     * Density of the cloud field at a block position, in {@code [0, 1]}.
     * {@code 0} means empty.
     */
    public static float density(int x, int y, int z, long seed, int minY, int maxY) {
        return density(x, y, z, offsets(seed), minY, maxY);
    }

    private static float density(int x, int y, int z, Offsets off, int minY, int maxY) {
        float nx = (x + off.x) * FREQ_XZ;
        float ny = (y + off.y) * FREQ_Y;
        float nz = (z + off.z) * FREQ_XZ;

        float wx = fbm(nx * 2.0f, ny * 2.0f, nz * 2.0f, 2) * WARP;
        float wy = fbm(nx * 2.0f + 100.0f, ny * 2.0f, nz * 2.0f, 2) * WARP;
        float wz = fbm(nx * 2.0f, ny * 2.0f, nz * 2.0f + 100.0f, 2) * WARP;

        float n = fbm(nx + wx * 0.05f, ny + wy * 0.05f, nz + wz * 0.05f, 4);
        n = (n + 1.0f) * 0.5f;

        float yRatio = (float) (y - minY) / Math.max(1.0f, (maxY - minY));
        float t = Math.abs(yRatio * 2.0f - 1.0f);
        float heightFactor = 1.0f - t * t * t;

        float weighted = n * heightFactor;
        if (weighted < THRESHOLD) {
            return 0.0f;
        }
        float mapped = (weighted - THRESHOLD) / (1.0f - THRESHOLD);
        return mapped * mapped * (3.0f - 2.0f * mapped);
    }

    /**
     * Bakes one chunk section worth of cloud blocks.
     *
     * @return a 4096 byte array indexed {@code (y << 8) | (z << 4) | x} holding
     * densities 1..15, or {@code null} when the section is completely empty.
     */
    public static byte[] bakeSection(int sectionX, int sectionY, int sectionZ, long seed,
                                     int minY, int maxY, int baseDensity) {
        Offsets off = offsets(seed);
        int baseX = sectionX << 4;
        int baseY = sectionY << 4;
        int baseZ = sectionZ << 4;
        byte[] out = null;
        for (int y = 0; y < 16; y++) {
            int worldY = baseY + y;
            for (int z = 0; z < 16; z++) {
                int worldZ = baseZ + z;
                for (int x = 0; x < 16; x++) {
                    float d = density(baseX + x, worldY, worldZ, off, minY, maxY);
                    if (d < 0.001f) {
                        continue;
                    }
                    int density = (int) (d * baseDensity);
                    if (density < 1) {
                        density = 1;
                    } else if (density > 15) {
                        density = 15;
                    }
                    if (out == null) {
                        out = new byte[4096];
                    }
                    out[(y << 8) | (z << 4) | x] = (byte) density;
                }
            }
        }
        return out;
    }

    private static float fbm(float x, float y, float z, int octaves) {
        float value = 0.0f;
        float amplitude = 1.0f;
        float frequency = 1.0f;
        float maxValue = 0.0f;
        for (int i = 0; i < octaves; i++) {
            value += amplitude * PerlinNoise.noise3d(x * frequency, y * frequency, z * frequency);
            maxValue += amplitude;
            amplitude *= 0.5f;
            frequency *= 2.0f;
        }
        return value / maxValue;
    }
}
