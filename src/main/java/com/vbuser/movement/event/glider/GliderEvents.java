package com.vbuser.movement.event.glider;

import com.vbuser.movement.capability.GliderUtils;
import com.vbuser.movement.data.GliderData;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;

import static com.vbuser.movement.math.GliderConst.*;

public class GliderEvents {
    @SubscribeEvent
    public void onLivingUpdate(LivingEvent.LivingUpdateEvent e) {
        if (!(e.getEntityLiving() instanceof EntityPlayer)) return;
        EntityPlayer p = (EntityPlayer) e.getEntityLiving();
        if (p.world.isRemote) return;
        GliderData.State s = GliderData.get(p);
        if (!GliderUtils.isEnabled()) {
            if (s.open) {
                s.open = false;
                p.fallDistance = 0.0F;
            }
            return;
        }
        if (s.open && !GliderUtils.isTwoBlocksAirBelow(p)) {
            s.open = false;
            p.fallDistance = 0.0F;
        }
        if (!s.open) return;
        p.motionY = lerp(p.motionY, VERTICAL, ALPHA);
        double tx = 0.0, tz = 0.0;
        if (s.hasInput) {
            double len = Math.sqrt(s.dirX * s.dirX + s.dirZ * s.dirZ);
            if (len > 1e-6) {
                tx = (s.dirX / len) * HORIZONTAL;
                tz = (s.dirZ / len) * HORIZONTAL;
            }
        }
        p.motionX = lerp(p.motionX, tx, ALPHA);
        p.motionZ = lerp(p.motionZ, tz, ALPHA);
        p.velocityChanged = true;
    }
    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        GliderData.remove(e.player.getUniqueID());
    }
}
