package com.vbuser.inf.light;

import net.minecraft.world.World;

/**
 * Optional client side hook so the light engine can tell the baked-section
 * renderer that a section has to be re-meshed. Empty on a dedicated server.
 */
public final class LightRenderHook {

    public interface Sink {
        void cellChanged(World world, int x, int y, int z);
    }

    private static volatile Sink sink;

    public static void install(Sink newSink) {
        sink = newSink;
    }

    public static void cellChanged(World world, int x, int y, int z) {
        Sink current = sink;
        if (current != null) {
            current.cellChanged(world, x, y, z);
        }
    }

    private LightRenderHook() {}
}
