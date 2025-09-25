package com.vbuser.movement.event;

import com.vbuser.movement.Movement;
import com.vbuser.movement.data.InputData;
import com.vbuser.movement.entity.FakePlayer;
import com.vbuser.movement.network.C2SInput;
import com.vbuser.movement.network.S2CSpawnFakePlayer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ClassInheritanceMultiMap;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.event.world.ChunkEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class FakeInput {

    private InputData.State state;
    public static Map<EntityPlayer, InputData.State> clientState = new ConcurrentHashMap<>();
    public static Map<UUID, FakePlayer> f_pMap = new HashMap<>();
    public static Map<UUID, FakePlayer> serverFakePlayers = new HashMap<>();

    @SubscribeEvent
    public void inputHandler(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.world == null || mc.player == null) return;
        EntityPlayerSP p = mc.player;
        InputData.State s = new InputData.State();
        s.moveStrafing = p.moveStrafing;
        s.moveForward = p.moveForward;
        if (!s.equals(state)) {
            state = s;
            Movement.network.sendToServer(new C2SInput(state));
            clientState.put(p, s);
        }
    }

    public static InputData.State get(EntityPlayer p) {
        return clientState.computeIfAbsent(p, k -> new InputData.State());
    }

    @SubscribeEvent
    public void fakePlayer(TickEvent.PlayerTickEvent event){
        if (event.phase != TickEvent.Phase.START) return;
        if (event.player.world.isRemote) return;

        UUID playerUUID = event.player.getUniqueID();

        if (!f_pMap.containsKey(playerUUID)) {
            FakePlayer fp = new FakePlayer(event.player.world, playerUUID);
            f_pMap.put(playerUUID, fp);
            serverFakePlayers.put(playerUUID, fp);
            event.player.world.spawnEntity(fp);
            syncFakePlayerToClients(fp, true);
        }

        //syncFakePlayers(event.player.world);
        event.player.setInvisible(true);
    }

    private void syncFakePlayerToClients(FakePlayer fakePlayer, boolean spawn) {
        if (fakePlayer.world.isRemote) return;
        Movement.network.sendToAll(new S2CSpawnFakePlayer(
                fakePlayer.getTargetPlayerUUID(),
                spawn,
                fakePlayer.posX,
                fakePlayer.posY,
                fakePlayer.posZ
        ));
    }

    @SubscribeEvent
    public void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.player.world.isRemote) return;

        for (FakePlayer fakePlayer : serverFakePlayers.values()) {
            if (fakePlayer != null && !fakePlayer.isDead) {
                syncFakePlayerToClients(fakePlayer, true);
            }
        }
    }

    @SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload event) {
        Chunk chunk = event.getChunk();
        for (ClassInheritanceMultiMap<Entity> entityList : chunk.getEntityLists()) {
            for (Entity entity : entityList) {
                if (entity instanceof FakePlayer) {
                    UUID targetUUID = ((FakePlayer) entity).getTargetPlayerUUID();
                    entity.setDead();
                    f_pMap.remove(targetUUID);
                    serverFakePlayers.remove(targetUUID);
                }
            }
        }
    }

    @SubscribeEvent
    public void remove(PlayerEvent.PlayerLoggedOutEvent event) {
        InputData.remove(event.player);
        UUID playerUUID = event.player.getUniqueID();

        if (f_pMap.containsKey(playerUUID)) {
            FakePlayer fp = f_pMap.get(playerUUID);
            fp.setDead();
            f_pMap.remove(playerUUID);
            serverFakePlayers.remove(playerUUID);
            syncFakePlayerToClients(fp, false);
        }
    }
}