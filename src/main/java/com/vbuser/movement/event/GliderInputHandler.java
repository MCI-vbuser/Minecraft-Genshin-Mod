package com.vbuser.movement.event;

import com.vbuser.movement.capability.GliderUtils;
import com.vbuser.movement.network.glider.C2SGliderInput;
import com.vbuser.movement.network.glider.C2SToggleGlider;
import com.vbuser.movement.network.glider.GliderNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import static com.vbuser.movement.math.GliderConst.*;

public class GliderInputHandler {
    private boolean lastJump = false;
    private boolean clientOpen = false;

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.world == null || mc.player == null) return;
        EntityPlayerSP p = mc.player;
        if (p.hasNoGravity()) return;
        if (p.capabilities.isFlying || p.isSpectator()) {
            if (clientOpen) {
                clientOpen = false;
                p.fallDistance = 0.0F;
                GliderNetwork.CHANNEL.sendToServer(new C2SToggleGlider(false));
            }
            return;
        }
        if (!GliderUtils.isEnabled()) {
            if (clientOpen) {
                clientOpen = false;
                p.fallDistance = 0.0F;
                GliderNetwork.CHANNEL.sendToServer(new C2SToggleGlider(false));
            }
            return;
        }
        boolean twoAir = GliderUtils.isTwoBlocksAirBelow(p);
        KeyBinding jump = mc.gameSettings.keyBindJump;
        boolean jumpDown = jump.isKeyDown();
        if (jumpDown && !lastJump && twoAir) {
            clientOpen = !clientOpen;
            if (clientOpen) {
                System.out.println("glide start");  //todo:Add Sound Event.
            }
            GliderNetwork.CHANNEL.sendToServer(new C2SToggleGlider(clientOpen));
            if (!clientOpen) {
                p.fallDistance = 0.0F;
            }
        }
        lastJump = jumpDown;
        if (clientOpen && !twoAir) {
            clientOpen = false;
            p.fallDistance = 0.0F;
            GliderNetwork.CHANNEL.sendToServer(new C2SToggleGlider(false));
        }
        float forward = p.movementInput.moveForward;
        float strafe = p.movementInput.moveStrafe;
        boolean hasInput = Math.abs(forward) > 1e-4 || Math.abs(strafe) > 1e-4;
        float dirX = 0f, dirZ = 0f;
        if (hasInput) {
            float yaw = p.rotationYaw;
            double yawRad = Math.toRadians(yaw);
            double sin = Math.sin(yawRad);
            double cos = Math.cos(yawRad);
            double x = (-forward * sin) + (strafe * cos);
            double z = (forward * cos) + (strafe * sin);
            double len = Math.sqrt(x * x + z * z);
            if (len > 1e-6) {
                x /= len;
                z /= len;
            }
            dirX = (float) x;
            dirZ = (float) z;
        }
        if (clientOpen) {
            GliderNetwork.CHANNEL.sendToServer(new C2SGliderInput(hasInput, dirX, dirZ));
            p.motionY = lerp(p.motionY, VERTICAL, ALPHA);
            double tx = 0.0, tz = 0.0;
            if (hasInput) {
                tx = dirX * HORIZONTAL;
                tz = dirZ * HORIZONTAL;
            }
            p.motionX = lerp(p.motionX, tx, ALPHA);
            p.motionZ = lerp(p.motionZ, tz, ALPHA);
        }
    }
}
