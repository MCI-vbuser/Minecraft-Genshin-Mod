package com.vbuser.inf.network;

import com.vbuser.inf.HeightConstants;
import com.vbuser.inf.InfiniteHeight;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.SPacketBlockChange;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.event.world.ChunkWatchEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

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
 *
 * <p>Every entry belongs to one world/dimension session. The session is bumped
 * whenever the player changes world, and all entries of the old session are
 * dropped: a snapshot of the old overworld must never be imported into a chunk
 * of a new world that happens to sit at the same coordinates. The session id is
 * also carried by the section and tile entity packets, so the client can
 * recognise a late packet of an earlier session. Unsubscribed chunks are
 * dropped from the tracking set as well, and the queue never silently forgets
 * the chunks it dropped: an overflow schedules a full resync of everything the
 * player was subscribed to, spread over the following ticks.</p>
 */
@Mod.EventBusSubscriber(modid = InfiniteHeight.MODID)
public final class ExtendedPacketQueue {

    private static final Logger LOGGER = LogManager.getLogger("infiniteheight");

    /** Roughly 5 MiB/s; a 1.4 MiB chunk backlog drains in a few ticks. */
    private static final int BYTES_PER_TICK = 256 * 1024;

    /** Pathological guard: a single player should never queue more than this. */
    private static final long MAX_BYTES_PER_PLAYER = 64L * 1024 * 1024;

    /** Chunks re-sent per tick after an overflow, so recovery is incremental. */
    private static final int RESYNC_CHUNKS_PER_TICK = 2;

    /**
     * Upper bound of resync entries inspected per tick. A watched chunk can be
     * between unload and reload for a tick; its entry is kept (dropping it would
     * lose the only recovery attempt), so without this bound a backlog of
     * unloaded chunks would make every tick scan the whole set.
     */
    private static final int RESYNC_INSPECT_PER_TICK = 16;

    private static final Map<EntityPlayerMP, PlayerQueue> QUEUES = new LinkedHashMap<>();

    private static final class Entry {
        final Object payload;
        final int size;
        final int chunkX;
        final int chunkZ;

        Entry(Object payload, int size, int chunkX, int chunkZ) {
            this.payload = payload;
            this.size = size;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
        }
    }

    private static final class PlayerQueue {
        final Deque<Entry> entries = new ArrayDeque<>();
        /** Chunks whose current extended content the client has been sent. */
        final Set<Long> syncedChunks = new HashSet<>();
        /** Chunks that have to be sent again after an overflow. */
        final Set<Long> resync = new HashSet<>();
        long bytes;
        int dimension = Integer.MIN_VALUE;
        int epoch;
        boolean overflowReported;
    }

    public static void sendBlockChange(EntityPlayerMP player, SPacketBlockChange packet) {
        enqueue(player, packet, 32,
                packet.getBlockPosition().getX() >> 4, packet.getBlockPosition().getZ() >> 4);
    }

    public static void sendSection(EntityPlayerMP player, SPacketExtendedSection packet) {
        if (player == null) {
            return;
        }
        // Copy at the final per-player send boundary. Callers may reuse a
        // packet object for another player; mutating it after it was queued
        // would also rewrite the earlier player's pending session.
        int epoch = sessionEpoch(player);
        SPacketExtendedSection queued = new SPacketExtendedSection(
                packet.getChunkX(), packet.getChunkZ(), packet.getSectionY(),
                packet.getOffsets(), packet.getStates(), packet.getMode(), epoch);
        queued.setDimension(dimensionOf(player));
        enqueue(player, queued, 28 + packet.getOffsets().length * 6,
                packet.getChunkX(), packet.getChunkZ());
    }

    public static void sendTileEntity(EntityPlayerMP player, SPacketExtendedTileEntity packet) {
        if (player == null) {
            return;
        }
        int epoch = sessionEpoch(player);
        SPacketExtendedTileEntity queued = new SPacketExtendedTileEntity(
                packet.getPos(), packet.getNbt(), epoch);
        queued.setDimension(dimensionOf(player));
        enqueue(player, queued, 512,
                packet.getPos().getX() >> 4, packet.getPos().getZ() >> 4);
    }

    /**
     * Session id of the packets being built for this player right now.
     *
     * <p>It also creates the queue and rolls the session over if the player
     * already changed dimension, so a caller that builds packets before it
     * queues them cannot accidentally stamp an old session.</p>
     */
    public static int sessionEpoch(EntityPlayerMP player) {
        PlayerQueue queue = queueFor(player);
        int dimension = dimensionOf(player);
        if (queue.dimension != dimension) {
            resetSession(queue, dimension);
        }
        return queue.epoch;
    }

    private static void enqueue(EntityPlayerMP player, Object payload, int size,
                                int chunkX, int chunkZ) {
        if (player == null) {
            return;
        }
        PlayerQueue queue = queueFor(player);
        int dimension = dimensionOf(player);
        if (queue.dimension != dimension) {
            resetSession(queue, dimension);
        }
        // Only the extended dimension has extended data at all. Anything that
        // is still queued belongs to a world this player just left.
        if (!HeightConstants.isExtendedWorld(player.world)) {
            return;
        }

        // Track the chunk before the guard below so an overflow resync also
        // covers the chunk whose packet did not fit.
        queue.syncedChunks.add(chunkKey(chunkX, chunkZ));
        if (queue.bytes + size > MAX_BYTES_PER_PLAYER) {
            overflow(player, queue);
            return;
        }

        queue.entries.add(new Entry(payload, size, chunkX, chunkZ));
        queue.bytes += size;
    }

    /**
     * The queue outgrew its guard. Dropping the oldest entries would leave the
     * client with a mix of old and new snapshots, so the whole queue is
     * discarded and every chunk the player is subscribed to is scheduled for a
     * full resync instead.
     */
    private static void overflow(EntityPlayerMP player, PlayerQueue queue) {
        if (!queue.overflowReported) {
            queue.overflowReported = true;
            LOGGER.error("Extended send queue of {} overflowed at {} KiB; resyncing {} chunks.",
                    player.getName(), queue.bytes / 1024, queue.syncedChunks.size());
        }
        queue.entries.clear();
        queue.bytes = 0;
        queue.resync.addAll(queue.syncedChunks);
    }

    private static void resetSession(PlayerQueue queue, int dimension) {
        queue.entries.clear();
        queue.bytes = 0;
        queue.syncedChunks.clear();
        queue.resync.clear();
        queue.overflowReported = false;
        queue.dimension = dimension;
        queue.epoch++;
    }

    private static PlayerQueue queueFor(EntityPlayerMP player) {
        PlayerQueue queue = QUEUES.get(player);
        if (queue == null) {
            queue = new PlayerQueue();
            QUEUES.put(player, queue);
        }
        return queue;
    }

    private static int dimensionOf(EntityPlayerMP player) {
        return player.world == null ? Integer.MIN_VALUE : player.world.provider.getDimension();
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
            if (queue.dimension != dimensionOf(player)) {
                resetSession(queue, dimensionOf(player));
            }
            resyncSome(player, queue);

            int sent = 0;
            while (!queue.entries.isEmpty() && sent < BYTES_PER_TICK) {
                Entry next = queue.entries.poll();
                queue.bytes -= next.size;
                sent += next.size;
                dispatch(player, next.payload);
            }
            if (queue.entries.isEmpty() && queue.resync.isEmpty()) {
                queue.overflowReported = false;
                // Keep the session while the player is connected. Removing an
                // idle queue resets epoch to 1 on the next send, while the
                // client still remembers the previous, larger epoch and would
                // reject every later packet as stale.
            }
        }
    }

    /** Re-send a bounded number of chunks lost by an overflow. */
    private static void resyncSome(EntityPlayerMP player, PlayerQueue queue) {
        // Only refill when the queue already drained: the resync must trickle in
        // at the same rate as normal traffic instead of delaying block changes.
        if (queue.resync.isEmpty() || queue.bytes >= BYTES_PER_TICK
                || !(player.world instanceof WorldServer)
                || !HeightConstants.isExtendedWorld(player.world)) {
            return;
        }
        WorldServer world = (WorldServer) player.world;
        Iterator<Long> it = queue.resync.iterator();
        int resent = 0;
        int inspected = 0;
        while (it.hasNext() && resent < RESYNC_CHUNKS_PER_TICK
                && inspected < RESYNC_INSPECT_PER_TICK && queue.bytes < BYTES_PER_TICK) {
            long key = it.next();
            inspected++;
            int chunkX = (int) (key >> 32);
            int chunkZ = (int) key;
            // The player may have walked away from the chunk (or the server
            // unloaded it) while the resync was waiting.
            if (!world.getPlayerChunkMap().isPlayerWatchingChunk(player, chunkX, chunkZ)) {
                it.remove();
                queue.syncedChunks.remove(key);
                continue;
            }
            Chunk chunk = world.getChunkProvider().getLoadedChunk(chunkX, chunkZ);
            if (chunk == null) {
                // Keep the key queued: a watched chunk can be between unload
                // and reload for a tick, and dropping it here loses the only
                // recovery attempt after an overflow.
                continue;
            }
            it.remove();
            ExtendedChunkSync.resyncChunk(player, chunk);
            resent++;
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.player instanceof EntityPlayerMP) {
            QUEUES.remove(event.player);
        }
    }

    /**
     * A chunk left the player's view: its queued snapshots can never be applied
     * (the client unloads the chunk), and keeping them would both waste budget
     * and make the player look subscribed to it again after a resync.
     */
    @SubscribeEvent
    public static void onChunkUnwatch(ChunkWatchEvent.UnWatch event) {
        net.minecraft.world.chunk.Chunk chunk = event.getChunkInstance();
        // A chunk of another dimension cannot have entries in this queue; the
        // queue itself is reset when the player's dimension changes.
        if (chunk != null && !HeightConstants.isExtendedWorld(chunk.getWorld())) {
            return;
        }
        forgetChunk(event.getPlayer(), event.getChunk().x, event.getChunk().z);
    }

    public static void forgetChunk(EntityPlayerMP player, int chunkX, int chunkZ) {
        if (player == null) {
            return;
        }
        PlayerQueue queue = QUEUES.get(player);
        if (queue == null) {
            return;
        }
        long key = chunkKey(chunkX, chunkZ);
        queue.syncedChunks.remove(key);
        queue.resync.remove(key);
        for (Iterator<Entry> it = queue.entries.iterator(); it.hasNext(); ) {
            Entry entry = it.next();
            if (entry.chunkX == chunkX && entry.chunkZ == chunkZ) {
                queue.bytes -= entry.size;
                it.remove();
            }
        }
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
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
