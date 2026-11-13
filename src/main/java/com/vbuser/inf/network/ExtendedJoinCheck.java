package com.vbuser.inf.network;

import com.vbuser.inf.InfiniteHeight;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.network.handshake.NetworkDispatcher;

/**
 * Refuses clients that do not run this mod.
 *
 * <p>The mod changes the wire format of {@code PacketBuffer#writeBlockPos} so
 * that a block position outside {@code 0..255} survives a packet. A client
 * without the mod decodes that one extra flag byte as the beginning of the next
 * field and synchronizes on the first such packet - a hang or a kick at best,
 * a corrupted world view at worst. Checking the handshake mod list and
 * disconnecting with a readable message is far kinder than letting it
 * desynchronize.</p>
 */
@Mod.EventBusSubscriber(modid = InfiniteHeight.MODID)
public final class ExtendedJoinCheck {

    private static final String MESSAGE =
            "This server uses InfiniteHeight (inf).\n"
                    + "Please install the mod to join.\n"
                    + "本服务器使用模组解除高度限制，请安装对应模组后再进入。";

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.player instanceof EntityPlayerMP)) {
            return;
        }
        EntityPlayerMP player = (EntityPlayerMP) event.player;
        if (player.connection == null || player.connection.netManager == null) {
            return;
        }
        // Single player: the integrated server channel never does a mod-list
        // handshake, and both sides are obviously the same jar.
        if (player.connection.netManager.isLocalChannel()) {
            return;
        }
        if (hasMod(player)) {
            return;
        }
        player.connection.disconnect(new TextComponentString(MESSAGE));
    }

    private static boolean hasMod(EntityPlayerMP player) {
        NetworkDispatcher dispatcher = NetworkDispatcher.get(player.connection.netManager);
        if (dispatcher == null
                || dispatcher.getConnectionType() != NetworkDispatcher.ConnectionType.MODDED) {
            return false;
        }
        return dispatcher.getModList().containsKey(InfiniteHeight.MODID);
    }

    private ExtendedJoinCheck() {}
}
