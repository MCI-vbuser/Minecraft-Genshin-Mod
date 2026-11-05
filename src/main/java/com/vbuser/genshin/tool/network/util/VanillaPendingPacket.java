package com.vbuser.genshin.tool.network.util;

import net.minecraft.network.Packet;

public final class VanillaPendingPacket {

    public final int id;
    public final byte[] data;
    public final Packet<?> original;

    public VanillaPendingPacket(int id, byte[] data, Packet<?> original) {
        this.id = id;
        this.data = data;
        this.original = original;
    }
}
