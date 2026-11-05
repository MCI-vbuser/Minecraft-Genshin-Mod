package com.vbuser.inf.chunk;

import net.minecraft.world.chunk.Chunk;

/**
 * Access to the extended column attached to a chunk.
 *
 * <p>The column now lives in an {@code @Unique} field on the chunk mixin, so
 * hot block accesses no longer synchronize on a global {@code WeakHashMap}.</p>
 */
public final class ColumnManager {

    public static ExtendedColumn get(Chunk chunk) {
        ExtendedColumnHolder holder = (ExtendedColumnHolder) chunk;
        ExtendedColumn column = holder.inf$getExtendedColumn(false);
        return column != null ? column : holder.inf$getExtendedColumn(true);
    }

    public static ExtendedColumn getIfPresent(Chunk chunk) {
        return ((ExtendedColumnHolder) chunk).inf$getExtendedColumn(false);
    }

    private ColumnManager() {}
}
