package com.vbuser.genshin.tool.network;

import com.vbuser.genshin.tool.network.util.*;
import com.vbuser.genshin.tool.network.util.sender.ClientSender;
import com.vbuser.genshin.tool.network.util.sender.ServerSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.EnumPacketDirection;
import net.minecraft.network.INetHandler;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;

@SuppressWarnings("unused")
public class NetworkManager {

    private static SimpleNetworkWrapper wrapper;
    private static SimpleNetworkWrapper vanillaWrapper;
    private static PacketQueueManager clientQueueManager;
    private static PacketQueueManager serverQueueManager;
    private static boolean isClientSide = false;
    private static boolean initialized = false;

    public static void initialize(FMLPreInitializationEvent event, String packetPackage) {
        if (initialized) {
            return;
        }
        initialized = true;

        wrapper = NetworkRegistry.INSTANCE.newSimpleChannel(NetConfig.CHANNEL_NAME);
        wrapper.registerMessage(AggregatedPacketHandler.class, AggregatedPacket.class, 0, Side.SERVER);
        wrapper.registerMessage(AggregatedPacketHandler.class, AggregatedPacket.class, 0, Side.CLIENT);

        vanillaWrapper = NetworkRegistry.INSTANCE.newSimpleChannel(NetConfig.VANILLA_CHANNEL_NAME);
        vanillaWrapper.registerMessage(VanillaBundleHandler.class, VanillaBundlePacket.class, 0, Side.SERVER);
        vanillaWrapper.registerMessage(VanillaBundleHandler.class, VanillaBundlePacket.class, 0, Side.CLIENT);

        PacketRegistry.autoRegister(packetPackage);

        if (event.getSide().isClient()) {
            isClientSide = true;
            clientQueueManager = new PacketQueueManager(new ClientSender(wrapper));
            MinecraftForge.EVENT_BUS.register(new ClientTickHandler());
        }
        serverQueueManager = new PacketQueueManager(new ServerSender(wrapper));
        MinecraftForge.EVENT_BUS.register(new ServerTickHandler());
        MinecraftForge.EVENT_BUS.register(new LifecycleHandler());
    }

    /**
     * Kept for compatibility with older callers. Initialization is idempotent.
     */
    public static void setupQueues() {
        if (serverQueueManager != null) {
            return;
        }
        if (isClientSide && clientQueueManager == null) {
            clientQueueManager = new PacketQueueManager(new ClientSender(wrapper));
            MinecraftForge.EVENT_BUS.register(new ClientTickHandler());
        }
        serverQueueManager = new PacketQueueManager(new ServerSender(wrapper));
        MinecraftForge.EVENT_BUS.register(new ServerTickHandler());
    }

    public static void setClientSide() {
        isClientSide = true;
    }

    public static void sendToServer(ModPacket packet) {
        if (clientQueueManager == null) {
            throw new IllegalStateException("Client queue manager not initialized");
        }
        clientQueueManager.addPacket(CLIENT_TARGET, packet);
    }

    public static void sendToPlayer(ModPacket packet, EntityPlayerMP player) {
        if (serverQueueManager == null) {
            throw new IllegalStateException("Server queue manager not initialized");
        }
        serverQueueManager.addPacket(player, packet);
    }

    public static void flushPlayer(EntityPlayerMP player) {
        if (serverQueueManager != null) {
            serverQueueManager.flush(player);
        }
    }

    public static void flushAll() {
        if (clientQueueManager != null) {
            clientQueueManager.flushAll();
        }
        if (serverQueueManager != null) {
            serverQueueManager.flushAll();
        }
    }

    public static boolean isInitialized() {
        return initialized;
    }

    /**
     * Used by the vanilla NetworkManager mixin. Sends a Zstd-compressed bundle
     * on the dedicated vanilla channel in the correct direction.
     */
    public static void sendVanillaBundle(byte[] raw, EnumPacketDirection direction, INetHandler handler) {
        if (vanillaWrapper == null || raw == null || raw.length == 0 || handler == null) {
            return;
        }
        VanillaBundlePacket packet = new VanillaBundlePacket(raw);
        if (direction == EnumPacketDirection.CLIENTBOUND) {
            if (handler instanceof NetHandlerPlayServer) {
                EntityPlayerMP player = ((NetHandlerPlayServer) handler).player;
                if (player != null) {
                    vanillaWrapper.sendTo(packet, player);
                }
            }
        } else {
            vanillaWrapper.sendToServer(packet);
        }
    }

    private static final Object CLIENT_TARGET = new Object();

    public static class ClientTickHandler {
        @SubscribeEvent
        public void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase == TickEvent.Phase.END && clientQueueManager != null) {
                clientQueueManager.tick();
            }
        }
    }

    public static class ServerTickHandler {
        @SubscribeEvent
        public void onServerTick(TickEvent.ServerTickEvent event) {
            if (event.phase == TickEvent.Phase.END && serverQueueManager != null) {
                serverQueueManager.tick();
            }
        }
    }

    /**
     * Flush queued traffic on lifecycle boundaries and drop per-player queues
     * so a long-running server does not retain logged-out players.
     */
    public static class LifecycleHandler {
        @SubscribeEvent
        public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
            if (serverQueueManager != null) {
                serverQueueManager.remove(event.player);
            }
        }

        @SubscribeEvent
        public void onClientDisconnected(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
            if (clientQueueManager != null) {
                // Flush what is left and drop the connection queue; the next
                // connection gets a fresh one.
                clientQueueManager.remove(CLIENT_TARGET);
            }
        }
    }
}
