package com.vbuser.inf.network;

import com.vbuser.inf.InfiniteHeight;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.SPacketBlockChange;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per player, ordered send queue for everything the extended region sends.
 *
 * <p>Sending a whole chunk's extended sections inline in
 * {@code PlayerChunkMapEntry.sendToPlayers} meant one message per section, up
 * to 24.5 KiB each: a tall build costs megabytes in a single tick and can
 * overflow the Netty write queue for the very player who is still loading in.
 * The queue drains with a fixed byte budget per tick instead.</p>
 *
 * <p>It is also a FIFO on purpose. A section snapshot is taken when it is
 * queued, so any block change that happens afterward must reach the client
 * <b>after</b> that snapshot, or the client would end up with the block the
 * section used to contain. Both the section packets and the vanilla block
 * change packets go through the same queue, which makes that ordering a
 * property of the queue instead of an accident of two different channels.</p>
 */
@Mod.EventBusSubscriber(modid = InfiniteHeight.MODID)
public final class ExtendedPacketQueue {

    private static final Logger LOGGER = LogManager.getLogger("infiniteheight");

    /** Roughly 5 MiB/s; a 1.4 MiB chunk backlog drains in a few ticks. */
    private static final int BYTES_PER_TICK = 256 * 1024;

    /** Pathological guard: a single player should never queue more than this. */
    private static final long MAX_BYTES_PER_PLAYER = 64L * 1024 * 1024;

    private static final Map<EntityPlayerMP, PlayerQueue> QUEUES = new LinkedHashMap<>();

    private static final class Entry {
        final Object payload;
        final int size;

        Entry(Object payload, int size) {
            this.payload = payload;
            this.size = size;
        }
    }

    private static final class PlayerQueue {
        final Deque<Entry> entries = new ArrayDeque<>();
        long bytes;
        boolean overflowReported;
    }

    public static void sendBlockChange(EntityPlayerMP player, SPacketBlockChange packet) {
        enqueue(player, packet, 32);
    }

    public static void sendSection(EntityPlayerMP player, SPacketExtendedSection packet) {
        // 16 bytes of header + one short offset and one int state per block.
        enqueue(player, packet, 20 + packet.getOffsets().length * 6);
    }

    public static void sendTileEntity(EntityPlayerMP player, SPacketExtendedTileEntity packet) {
        enqueue(player, packet, 512);
    }

    private static void enqueue(EntityPlayerMP player, Object payload, int size) {
        if (player == null) {
            return;
        }
        PlayerQueue queue = QUEUES.get(player);
        if (queue == null) {
            queue = new PlayerQueue();
            QUEUES.put(player, queue);
        }
        if (queue.bytes > MAX_BYTES_PER_PLAYER) {
            if (!queue.overflowReported) {
                queue.overflowReported = true;
                LOGGER.error("Dropping extended send queue of {} ({} KiB queued); the client will "
                        + "receive the affected chunks again on the next chunk send.",
                        player.getName(), queue.bytes / 1024);
            }
            queue.entries.clear();
            queue.bytes = 0;
            return;
        }
        queue.entries.add(new Entry(payload, size));
        queue.bytes += size;
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || QUEUES.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<EntityPlayerMP, PlayerQueue>> it = QUEUES.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<EntityPlayerMP, PlayerQueue> entry = it.next();
            EntityPlayerMP player = entry.getKey();
            PlayerQueue queue = entry.getValue();
            if (!isConnected(player)) {
                it.remove();
                continue;
            }
            int sent = 0;
            while (!queue.entries.isEmpty() && sent < BYTES_PER_TICK) {
                Entry next = queue.entries.poll();
                queue.bytes -= next.size;
                sent += next.size;
                dispatch(player, next.payload);
            }
            if (queue.entries.isEmpty()) {
                it.remove();
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.player instanceof EntityPlayerMP) {
            QUEUES.remove(event.player);
        }
    }

    private static boolean isConnected(EntityPlayerMP player) {
        return player.connection != null
                && player.connection.netManager != null
                && player.connection.netManager.isChannelOpen();
    }

    private static void dispatch(EntityPlayerMP player, Object payload) {
        try {
            if (payload instanceof Packet) {
                player.connection.sendPacket((Packet<?>) payload);
            } else if (payload instanceof IMessage) {
                InfiniteHeight.NETWORK.sendTo((IMessage) payload, player);
            }
        } catch (Throwable t) {
            LOGGER.error("Failed to send {} to {}", payload.getClass().getName(), player.getName(), t);
        }
    }

    private ExtendedPacketQueue() {}
}
