package com.vbuser.inf.chunk;

import net.minecraft.world.chunk.Chunk;

import java.util.Map;
import java.util.WeakHashMap;

public final class ColumnManager {

    private static final Map<Chunk, ExtendedColumn> COLUMNS = new WeakHashMap<>();

    public static ExtendedColumn get(Chunk chunk) {
        synchronized (COLUMNS) {
            return COLUMNS.computeIfAbsent(chunk, c -> new ExtendedColumn());
        }
    }

    public static ExtendedColumn getIfPresent(Chunk chunk) {
        synchronized (COLUMNS) {
            return COLUMNS.get(chunk);
        }
    }

    private ColumnManager() {}
}