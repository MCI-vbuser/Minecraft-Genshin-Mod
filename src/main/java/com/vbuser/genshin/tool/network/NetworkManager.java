package com.vbuser.genshin.tool.network;

import com.vbuser.genshin.tool.network.util.*;
import com.vbuser.genshin.tool.network.util.sender.ClientSender;
import com.vbuser.genshin.tool.network.util.sender.ServerSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;

@SuppressWarnings("unused")
public class NetworkManager {
    private static SimpleNetworkWrapper wrapper;
    private static PacketQueueManager clientQueueManager;
    private static PacketQueueManager serverQueueManager;
    private static boolean isClientSide = false;

    public static void initialize(FMLPreInitializationEvent event, String packetPackage) {
        wrapper = NetworkRegistry.INSTANCE.newSimpleChannel(NetConfig.CHANNEL_NAME);
        wrapper.registerMessage(AggregatedPacketHandler.class, AggregatedPacket.class, 0, Side.SERVER);
        wrapper.registerMessage(AggregatedPacketHandler.class, AggregatedPacket.class, 0, Side.CLIENT);

        PacketRegistry.autoRegister(packetPackage);

        if (event.getSide().isClient()) {
            isClientSide = true;
            clientQueueManager = new PacketQueueManager(new ClientSender(wrapper));
            MinecraftForge.EVENT_BUS.register(new ClientTickHandler());
        }
        serverQueueManager = new PacketQueueManager(new ServerSender(wrapper));
        MinecraftForge.EVENT_BUS.register(new ServerTickHandler());
    }

    public static void setupQueues() {
        if (isClientSide) {
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
        if (serverQueueManager != null) serverQueueManager.flush(player);
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
}
