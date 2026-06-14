package com.vbuser.genshin.tool.mcgit;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.play.server.SPacketChunkData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.MinecraftException;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraftforge.common.DimensionManager;

import java.util.HashSet;
import java.util.Set;

@SuppressWarnings("all")
public class UpdateCommand extends CommandBase {
    @Override
    public String getName() {
        return "update";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/update - Update Save File";
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) {
        server.addScheduledTask(() -> {
            for (WorldServer world : DimensionManager.getWorlds()) {
                try {
                    world.saveAllChunks(true, null);
                } catch (MinecraftException e) {
                    throw new RuntimeException(e);
                }
            }

            new Thread(() -> {
                try {
                    MapUpdater updater = new MapUpdater(server);
                    Set<ChunkPos> modifiedChunks = updater.performUpdate();

                    server.addScheduledTask(() -> {
                        for (WorldServer world : DimensionManager.getWorlds()) {
                            ChunkProviderServer provider = world.getChunkProvider();
                            Set<Long> playerChunkKeys = new HashSet<>();
                            for (EntityPlayer player : world.playerEntities) {
                                int cx = (int) player.posX >> 4;
                                int cz = (int) player.posZ >> 4;
                                playerChunkKeys.add(ChunkPos.asLong(cx, cz));
                            }
                            for (long key : playerChunkKeys) {
                                provider.id2ChunkMap.remove(key);
                            }
                            for (EntityPlayer player : world.playerEntities) {
                                int cx = (int) player.posX >> 4;
                                int cz = (int) player.posZ >> 4;
                                provider.provideChunk(cx, cz);
                            }
                        }

                        for (WorldServer world : DimensionManager.getWorlds()) {
                            for (EntityPlayer player : world.playerEntities) {
                                if (player instanceof EntityPlayerMP) {
                                    EntityPlayerMP mp = (EntityPlayerMP) player;
                                    for (ChunkPos pos : modifiedChunks) {
                                        Chunk chunk = world.getChunkProvider().provideChunk(pos.x, pos.z);
                                        mp.connection.sendPacket(new SPacketChunkData(chunk, 65535));
                                    }
                                }
                            }
                        }

                        sender.sendMessage(new TextComponentString(TextFormatting.GREEN + "All update finished."));
                    });
                } catch (Exception e) {
                    server.addScheduledTask(() ->
                            sender.sendMessage(new TextComponentString(TextFormatting.RED + "Fail to update: " + e.getMessage()))
                    );
                }
            }, "MapUpdateThread").start();
        });

        sender.sendMessage(new TextComponentString("Saving world and performing update..."));
    }
}
