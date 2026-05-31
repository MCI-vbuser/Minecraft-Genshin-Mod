package com.vbuser.genshin.tool.network;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.vbuser.genshin.tool.network.util.autoregist.ClassScanner;
import io.netty.buffer.ByteBuf;

import java.util.*;

public class PacketRegistry {
    private static final BiMap<Class<? extends ModPacket>, Integer> idMap = HashBiMap.create();

    public static void register(Class<? extends ModPacket> clazz) {
        if (idMap.containsKey(clazz)) {
            throw new IllegalArgumentException("Packet already registered: " + clazz);
        }
        int id = idMap.size();
        idMap.put(clazz, id);
    }

    public static int getId(Class<? extends ModPacket> clazz) {
        Integer id = idMap.get(clazz);
        if (id == null) {
            throw new IllegalArgumentException("Unregistered packet type: " + clazz);
        }
        return id;
    }

    public static ModPacket create(int id, ByteBuf buf) {
        Class<? extends ModPacket> clazz = idMap.inverse().get(id);
        if (clazz == null) {
            throw new IllegalArgumentException("Unknown packet id: " + id);
        }
        try {
            ModPacket packet = clazz.newInstance();
            packet.decode(buf);
            return packet;
        } catch (InstantiationException | IllegalAccessException e) {
            throw new RuntimeException("Failed to instantiate packet " + clazz, e);
        }
    }

    public static void autoRegister(String packageName) {
        Set<Class<?>> packetClasses = ClassScanner.findSubclasses(packageName, ModPacket.class);
        List<Class<?>> sortedClasses = new ArrayList<>(packetClasses);
        sortedClasses.sort(Comparator.comparing(Class::getName));
        for (Class<?> clazz : sortedClasses) {
            @SuppressWarnings("unchecked")
            Class<? extends ModPacket> packetClass = (Class<? extends ModPacket>) clazz;
            register(packetClass);
        }
    }
}