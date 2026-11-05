package com.vbuser.genshin.tool.network;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.vbuser.genshin.tool.network.util.autoregist.ClassScanner;
import io.netty.buffer.ByteBuf;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

public final class PacketRegistry {

    private static final Logger LOGGER = LogManager.getLogger("genshin-network");
    private static final BiMap<Class<? extends ModPacket>, Integer> ID_MAP = HashBiMap.create();
    private static int protocolHash = -1;

    public static void register(Class<? extends ModPacket> clazz) {
        if (ID_MAP.containsKey(clazz)) {
            throw new IllegalArgumentException("Packet already registered: " + clazz);
        }
        int id = ID_MAP.size();
        ID_MAP.put(clazz, id);
        protocolHash = -1;
    }

    public static int getId(Class<? extends ModPacket> clazz) {
        Integer id = ID_MAP.get(clazz);
        if (id == null) {
            throw new IllegalArgumentException("Unregistered packet type: " + clazz.getName());
        }
        return id;
    }

    public static ModPacket create(int id, ByteBuf buf) {
        Class<? extends ModPacket> clazz = ID_MAP.inverse().get(id);
        if (clazz == null) {
            throw new IllegalArgumentException("Unknown packet id: " + id);
        }
        try {
            ModPacket packet = clazz.newInstance();
            packet.decode(buf);
            return packet;
        } catch (InstantiationException | IllegalAccessException e) {
            throw new RuntimeException("Failed to instantiate packet " + clazz.getName(), e);
        }
    }

    /**
     * Hash of the sorted registered class names. Client and server must agree
     * before any aggregated payload can be safely decoded.
     */
    public static int getProtocolHash() {
        int hash = protocolHash;
        if (hash != -1) {
            return hash;
        }
        int computed = 1;
        for (String name : sortedNames()) {
            computed = 31 * computed + name.hashCode();
        }
        protocolHash = computed;
        return computed;
    }

    public static void autoRegister(String packageName) {
        Set<Class<?>> packetClasses = ClassScanner.findSubclasses(packageName, ModPacket.class);
        List<Class<?>> sortedClasses = new ArrayList<>(packetClasses);
        sortedClasses.sort(Comparator.comparing(Class::getName));
        if (sortedClasses.isEmpty()) {
            LOGGER.warn("No ModPacket subclasses found under {}", packageName);
        }
        for (Class<?> clazz : sortedClasses) {
            @SuppressWarnings("unchecked")
            Class<? extends ModPacket> packetClass = (Class<? extends ModPacket>) clazz;
            register(packetClass);
        }
        LOGGER.info("Registered {} mod packet types under {}", sortedClasses.size(), packageName);
    }

    private static List<String> sortedNames() {
        List<String> names = new ArrayList<>(ID_MAP.size());
        for (Class<?> clazz : ID_MAP.keySet()) {
            names.add(clazz.getName());
        }
        names.sort(String::compareTo);
        return names;
    }

    private PacketRegistry() {}
}
