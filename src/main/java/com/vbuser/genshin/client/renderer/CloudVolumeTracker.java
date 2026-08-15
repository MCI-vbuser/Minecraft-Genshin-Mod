package com.vbuser.genshin.client.renderer;

import com.vbuser.genshin.block.BlockCloud;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class CloudVolumeTracker {

    private static class VolumeData {
        final Map<BlockPos, Integer> clouds = new HashMap<>();
        final Map<Long, Set<BlockPos>> cloudsByChunk = new HashMap<>();
        BlockPos min;
        BlockPos max;
        long totalDensity;
        int count;

        synchronized void add(BlockPos pos, int density) {
            BlockPos p = pos.toImmutable();
            Integer old = clouds.put(p, density);
            if (old == null) {
                count++;
            } else {
                totalDensity -= old;
            }
            totalDensity += density;

            long key = chunkKey(p.getX() >> 4, p.getZ() >> 4);
            cloudsByChunk.computeIfAbsent(key, k -> new HashSet<>()).add(p);
            updateBounds(p);
        }

        synchronized void remove(BlockPos pos) {
            BlockPos p = pos.toImmutable();
            Integer old = clouds.remove(p);
            if (old == null) return;

            totalDensity -= old;
            count--;

            long key = chunkKey(p.getX() >> 4, p.getZ() >> 4);
            Set<BlockPos> set = cloudsByChunk.get(key);
            if (set != null) {
                set.remove(p);
                if (set.isEmpty()) {
                    cloudsByChunk.remove(key);
                }
            }

            removeFromBounds(p);
        }

        synchronized void removeChunk(ChunkPos chunkPos) {
            long key = chunkKey(chunkPos.x, chunkPos.z);
            Set<BlockPos> set = cloudsByChunk.remove(key);
            if (set == null || set.isEmpty()) return;

            for (BlockPos p : set) {
                Integer old = clouds.remove(p);
                if (old != null) {
                    totalDensity -= old;
                    count--;
                }
            }
            recomputeBounds();
        }

        private void updateBounds(BlockPos p) {
            if (min == null) {
                min = p;
                max = p;
                return;
            }
            min = new BlockPos(
                    Math.min(min.getX(), p.getX()),
                    Math.min(min.getY(), p.getY()),
                    Math.min(min.getZ(), p.getZ())
            );
            max = new BlockPos(
                    Math.max(max.getX(), p.getX()),
                    Math.max(max.getY(), p.getY()),
                    Math.max(max.getZ(), p.getZ())
            );
        }

        private void removeFromBounds(BlockPos p) {
            if (clouds.isEmpty()) {
                min = null;
                max = null;
                return;
            }
            boolean touchesBoundary =
                    p.getX() == min.getX() || p.getX() == max.getX() ||
                            p.getY() == min.getY() || p.getY() == max.getY() ||
                            p.getZ() == min.getZ() || p.getZ() == max.getZ();

            // 只有删除了包围盒边界上的点才需要重算，避免 O(n) 开销
            if (touchesBoundary) {
                recomputeBounds();
            }
        }

        private void recomputeBounds() {
            if (clouds.isEmpty()) {
                min = null;
                max = null;
                return;
            }
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
            for (BlockPos p : clouds.keySet()) {
                minX = Math.min(minX, p.getX());
                minY = Math.min(minY, p.getY());
                minZ = Math.min(minZ, p.getZ());
                maxX = Math.max(maxX, p.getX());
                maxY = Math.max(maxY, p.getY());
                maxZ = Math.max(maxZ, p.getZ());
            }
            min = new BlockPos(minX, minY, minZ);
            max = new BlockPos(maxX, maxY, maxZ);
        }
    }

    private static long chunkKey(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }

    private static final Map<Integer, VolumeData> DATA = new ConcurrentHashMap<>();

    public static void addCloud(int dimension, BlockPos pos, int density) {
        DATA.computeIfAbsent(dimension, k -> new VolumeData()).add(pos, density);
    }

    public static void removeCloud(int dimension, BlockPos pos) {
        VolumeData data = DATA.get(dimension);
        if (data != null) data.remove(pos);
    }

    public static void removeChunk(int dimension, ChunkPos chunkPos) {
        VolumeData data = DATA.get(dimension);
        if (data != null) data.removeChunk(chunkPos);
    }

    public static void clearDimension(int dimension) {
        DATA.remove(dimension);
    }

    public static BlockPos getMin(int dimension) {
        VolumeData data = DATA.get(dimension);
        return data == null ? null : data.min;
    }

    public static BlockPos getMax(int dimension) {
        VolumeData data = DATA.get(dimension);
        return data == null ? null : data.max;
    }

    public static float getAverageDensity(int dimension) {
        VolumeData data = DATA.get(dimension);
        if (data == null || data.count == 0) return 8.0f;
        return (float) data.totalDensity / data.count;
    }

    public static void scanChunkSection(World world, Chunk chunk, int sectionIndex) {
        if (!world.isRemote) return;
        int dim = world.provider.getDimension();

        ExtendedBlockStorage storage = chunk.getBlockStorageArray()[sectionIndex];
        if (storage == null) return;

        int yBase = sectionIndex << 4;
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = 0; y < 16; y++) {
                    IBlockState state = storage.get(x, y, z);
                    if (state.getBlock() instanceof BlockCloud) {
                        mutable.setPos(chunk.x * 16 + x, yBase + y, chunk.z * 16 + z);
                        addCloud(dim, mutable.toImmutable(), state.getValue(BlockCloud.DENSITY));
                    }
                }
            }
        }
    }
}