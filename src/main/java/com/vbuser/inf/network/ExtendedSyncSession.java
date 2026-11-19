package com.vbuser.inf.network;

import com.vbuser.inf.HeightConstants;
import com.vbuser.inf.InfiniteHeight;
import net.minecraft.world.World;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * Client side guard for extended-region snapshots.
 *
 * <p>The server stamps every section and tile entity packet with the session of
 * the world it was built in. Packets can still be in flight when the player
 * leaves that world, so the client only accepts a packet when the session is
 * not older than the one it already accepted <b>for the same world instance</b>.
 * A new world starts a new epoch comparison; the server bumps the epoch and
 * drops the old queue when the player changes dimension, so an older epoch can
 * only mean a late packet.</p>
 */
@SideOnly(Side.CLIENT)
@Mod.EventBusSubscriber(modid = InfiniteHeight.MODID, value = Side.CLIENT)
public final class ExtendedSyncSession {

    /** Last client world the epoch below belongs to (identity, not coordinates). */
    private static World world;

    /** Newest session already accepted in {@link #world}. */
    private static int epoch = -1;

    /**
     * @return true when the packet belongs to the world the client is in and
     *         is not older than what was already applied there
     */
    public static boolean accept(int packetEpoch, int packetDimension, World currentWorld) {
        if (currentWorld == null || !HeightConstants.isExtendedWorld(currentWorld)
                || currentWorld.provider.getDimension() != packetDimension) {
            // Reject both non-extended worlds and snapshots of another dimension.
            return false;
        }
        if (currentWorld != world) {
            world = currentWorld;
            epoch = -1;
        }
        if (packetEpoch < epoch) {
            return false;
        }
        if (packetEpoch > epoch) {
            epoch = packetEpoch;
        }
        return true;
    }

    @SubscribeEvent
    public static void onWorldUnload(WorldEvent.Unload event) {
        if (event.getWorld() == world) {
            world = null;
            epoch = -1;
        }
    }

    private ExtendedSyncSession() {}
}
