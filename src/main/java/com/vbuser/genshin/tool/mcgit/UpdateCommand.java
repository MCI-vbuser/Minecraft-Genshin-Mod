package com.vbuser.genshin.tool.mcgit;

import com.vbuser.inf.HeightConstants;
import com.vbuser.inf.network.ExtendedChunkSync;
import com.vbuser.mixin.PlayerChunkMapEntryAccessor;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.play.server.SPacketChunkData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.PlayerChunkMapEntry;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ClassInheritanceMultiMap;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.MinecraftException;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraftforge.common.DimensionManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

@SuppressWarnings("all")
public class UpdateCommand extends CommandBase {

    /** Only one /update may rewrite the region files at a time. */
    private static final AtomicBoolean RUNNING = new AtomicBoolean();

    @Override
    public String getName() {
        return "update";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/update [server address] - Update the save file from a base-world server "
                + "(default: " + MapUpdater.DEFAULT_BASE_URL + ")";
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) {
        if (args.length > 1) {
            sender.sendMessage(new TextComponentString(TextFormatting.RED + getUsage(sender)));
            return;
        }

        final String baseUrl;
        try {
            baseUrl = MapUpdater.normalizeBaseUrl(
                    args.length == 1 ? args[0] : MapUpdater.DEFAULT_BASE_URL);
        } catch (IllegalArgumentException e) {
            sender.sendMessage(new TextComponentString(TextFormatting.RED + e.getMessage()));
            return;
        }

        if (!RUNNING.compareAndSet(false, true)) {
            sender.sendMessage(new TextComponentString(
                    TextFormatting.RED + "An update is already running."));
            return;
        }

        sender.sendMessage(new TextComponentString(TextFormatting.YELLOW
                + "Saving world and performing update from " + baseUrl + "..."));

        server.addScheduledTask(() -> {
            // Only the overworld has a base-world counterpart: it is the one
            // dimension the infinite-height module extends and the only save
            // the official world ships.
            WorldServer world = DimensionManager.getWorld(HeightConstants.EXTENDED_DIMENSION);
            if (world == null) {
                RUNNING.set(false);
                sender.sendMessage(new TextComponentString(
                        TextFormatting.RED + "The overworld is not loaded."));
                return;
            }

            try {
                world.saveAllChunks(true, null);
                // saveAllChunks only queues the NBT writes on the threaded IO
                // executor; wait for the region files to be on disk before the
                // merge thread opens and rewrites them.
                world.flushToDisk();
            } catch (MinecraftException e) {
                RUNNING.set(false);
                sender.sendMessage(new TextComponentString(
                        TextFormatting.RED + "Fail to update: cannot save the world: " + e.getMessage()));
                return;
            }

            // The merge thread rewrites region files directly. Keep the server
            // from autosaving or unloading chunks over the same files until the
            // refreshed chunks are back in memory.
            world.disableLevelSaving = true;

            Thread worker = new Thread(() -> {
                try {
                    Set<ChunkPos> modifiedChunks = new MapUpdater(server, baseUrl).performUpdate();

                    server.addScheduledTask(() -> {
                        try {
                            Map<Long, Chunk> chunks = refreshChunks(world, modifiedChunks);
                            resyncPlayers(server, world, modifiedChunks, chunks);
                            sender.sendMessage(new TextComponentString(TextFormatting.GREEN
                                    + "All update finished. Refreshed " + modifiedChunks.size() + " chunk(s)."));
                        } catch (Throwable t) {
                            sender.sendMessage(new TextComponentString(TextFormatting.RED
                                    + "Fail to apply update: " + t.getMessage()));
                        } finally {
                            world.disableLevelSaving = false;
                            RUNNING.set(false);
                        }
                    });
                } catch (Throwable t) {
                    server.addScheduledTask(() -> {
                        sender.sendMessage(new TextComponentString(TextFormatting.RED
                                + "Fail to update: " + t.getMessage()));
                        world.disableLevelSaving = false;
                        RUNNING.set(false);
                    });
                }
            }, "MapUpdateThread");
            worker.setDaemon(true);
            worker.start();
        });
    }

    /**
     * Replace every loaded chunk whose region file was rewritten.
     *
     * <p>Chunks that are not loaded are simply read from the merged file the
     * next time they are needed. Loaded ones have to be dropped first, otherwise
     * {@code provideChunk} would hand back the pre-merge object, and the
     * {@link PlayerChunkMapEntry} of a watching player has to be pointed at the
     * fresh object or its later multi-block updates would come from stale data.</p>
     */
    private static Map<Long, Chunk> refreshChunks(WorldServer world, Set<ChunkPos> modifiedChunks) {
        ChunkProviderServer provider = world.getChunkProvider();
        Map<Long, Chunk> chunks = new HashMap<>();
        for (ChunkPos pos : modifiedChunks) {
            long key = ChunkPos.asLong(pos.x, pos.z);
            PlayerChunkMapEntry entry = world.getPlayerChunkMap().getEntry(pos.x, pos.z);
            Chunk previous = provider.id2ChunkMap.get(key);
            if (previous == null && entry == null) {
                // Not in memory and nobody is watching it: the merged file is
                // picked up the next time the chunk is actually needed.
                continue;
            }
            if (previous != null) {
                provider.id2ChunkMap.remove(key);
                detachChunk(world, previous);
            }
            Chunk chunk = provider.provideChunk(pos.x, pos.z);
            if (chunk == null) {
                continue;
            }
            if (entry != null) {
                ((PlayerChunkMapEntryAccessor) entry).inf$setChunk(chunk);
            }
            chunks.put(key, chunk);
        }
        return chunks;
    }

    /**
     * Unregister a chunk that is about to be replaced by a fresh copy read from
     * the merged region file.
     *
     * <p>{@code Chunk#onUnload} would also detach the players standing in the
     * chunk, which vanilla never does; instead only entities and tile entities
     * are released. Without this the freshly loaded chunk would register a
     * second copy of every entity and tile entity in the world.</p>
     */
    private static void detachChunk(WorldServer world, Chunk chunk) {
        for (TileEntity tileEntity : chunk.getTileEntityMap().values()) {
            world.markTileEntityForRemoval(tileEntity);
        }
        for (ClassInheritanceMultiMap<Entity> entities : chunk.getEntityLists()) {
            List<Entity> removable = new ArrayList<>();
            for (Entity entity : entities) {
                if (!(entity instanceof EntityPlayer)) {
                    removable.add(entity);
                }
            }
            if (!removable.isEmpty()) {
                world.unloadEntities(removable);
            }
        }
        chunk.markLoaded(false);
    }

    /**
     * Re-send every affected chunk to the players that can see it.
     *
     * <p>Vanilla {@code SPacketChunkData} covers Y 0..255 only. The extended
     * part is replaced through the infinite-height channel, which also clears
     * blocks that the new base world removed and re-derives the client light.</p>
     */
    private static void resyncPlayers(MinecraftServer server, WorldServer world,
                                      Set<ChunkPos> modifiedChunks, Map<Long, Chunk> chunks) {
        if (modifiedChunks.isEmpty()) {
            return;
        }
        int viewDistance = server.getPlayerList().getViewDistance();

        for (EntityPlayer player : world.playerEntities) {
            if (!(player instanceof EntityPlayerMP)) {
                continue;
            }
            EntityPlayerMP mp = (EntityPlayerMP) player;
            int playerChunkX = MathHelper.floor(mp.posX) >> 4;
            int playerChunkZ = MathHelper.floor(mp.posZ) >> 4;

            for (ChunkPos pos : modifiedChunks) {
                if (Math.abs(pos.x - playerChunkX) > viewDistance
                        || Math.abs(pos.z - playerChunkZ) > viewDistance) {
                    continue;
                }
                Chunk chunk = chunks.get(ChunkPos.asLong(pos.x, pos.z));
                if (chunk == null) {
                    continue;
                }
                mp.connection.sendPacket(new SPacketChunkData(chunk, 65535));
                ExtendedChunkSync.resyncChunk(mp, chunk);
            }
        }
    }
}
