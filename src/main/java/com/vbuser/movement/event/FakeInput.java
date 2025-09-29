package com.vbuser.movement.event;

import com.vbuser.movement.Movement;
import com.vbuser.movement.data.InputData;
import com.vbuser.movement.entity.FakePlayer;
import com.vbuser.movement.network.motion.C2SInput;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class FakeInput {

    private InputData.State state;
    public static Map<EntityPlayer, InputData.State> clientState = new ConcurrentHashMap<>();

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

    public Map<EntityPlayer, FakePlayer> fp_client = new ConcurrentHashMap<>();
    private boolean flag = false;
    private boolean sync_trigger = false;
    private List<EntityPlayer> list = new ArrayList<>();

    @SubscribeEvent
    public void fake_client(TickEvent.ClientTickEvent event) {
        if (flag && Minecraft.getMinecraft().player == null) {
            fp_client.clear();
            flag = Minecraft.getMinecraft().player != null;
            return;
        }
        if (!flag && Minecraft.getMinecraft().player != null) {
            sync_trigger = true;
            System.out.println("Sync triggered, reason: player joined");
            flag = Minecraft.getMinecraft().player != null;
            return;
        }
        if (!flag) return;
        World world = Minecraft.getMinecraft().world;
        if (world == null) return;
        List<EntityPlayer> players = world.getPlayers(EntityPlayer.class, player -> true);
        if (sync_trigger) {
            for (EntityPlayer p : players) {
                if (!fp_client.containsKey(p)) {
                    FakePlayer fp = new FakePlayer(world, p.getUniqueID());
                    Minecraft.getMinecraft().world.spawnEntity(fp);
                    fp_client.put(p, fp);
                }
            }
            for (EntityPlayer p : fp_client.keySet()) {
                if (!players.contains(p)) {
                    fp_client.get(p).setDead();
                    System.out.println("Clear FP, reason: player left");
                    fp_client.remove(p);
                }
            }
            sync_trigger = false;
        }
        for (EntityPlayer p : players) {
            if (fp_client.containsKey(p)) {
                fp_client.get(p).setPosition(p.posX, p.posY, p.posZ);
                fp_client.get(p).setRenderer();
            }
        }
        if (!eqt(list, players)) {
            System.out.println("Player List Changed: "+list+" -> "+players);
            sync_trigger = true;
            list = players;
            System.out.println("Sync triggered, reason: player list changed");
        }
    }

    public static <T> boolean eqt(List<T> list1, List<T> list2) {
        if (list1 == null && list2 == null) return true;
        if (list1 == null || list2 == null) return false;
        if (list1.size() != list2.size()) return false;

        return new HashSet<>(list1).equals(new HashSet<>(list2));
    }
}