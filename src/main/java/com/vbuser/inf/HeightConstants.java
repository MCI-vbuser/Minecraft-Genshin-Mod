package com.vbuser.inf;

import net.minecraft.world.World;

public final class HeightConstants {

    public static final int VANILLA_MIN_Y = 0;
    public static final int VANILLA_MAX_Y = 255;

    public static final int MIN_Y = Integer.MIN_VALUE;
    public static final int MAX_Y = Integer.MAX_VALUE;

    /**
     * Dimension that owns the extended region.
     *
     * <p>Only the overworld gets extended Y. Other dimensions keep vanilla
     * semantics completely: their chunk generators, portal search, biome
     * decoration and every mod that assumes {@code 0..255} stay untouched, and
     * {@code Chunk.storageArrays} can never be indexed with an out of range
     * section. The trade-off is worth it: an extended region in a dimension
     * nobody builds tall in buys nothing but compatibility risk.</p>
     */
    public static final int EXTENDED_DIMENSION = 0;

    public static boolean isVanillaY(int y) {
        return y >= VANILLA_MIN_Y && y <= VANILLA_MAX_Y;
    }

    public static boolean isExtendedY(int y) {
        return !isVanillaY(y);
    }

    /** True when this world owns extended Y at all (see {@link #EXTENDED_DIMENSION}). */
    public static boolean isExtendedWorld(World world) {
        return world != null
                && world.provider != null
                && world.provider.getDimension() == EXTENDED_DIMENSION;
    }

    private HeightConstants() {}
}