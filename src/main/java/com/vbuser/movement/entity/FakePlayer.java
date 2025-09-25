package com.vbuser.movement.entity;

import com.vbuser.movement.data.InputData;
import com.vbuser.movement.event.FakeInput;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.Vec2f;
import net.minecraft.world.World;
import software.bernie.geckolib3.core.IAnimatable;
import software.bernie.geckolib3.core.IAnimationTickable;
import software.bernie.geckolib3.core.PlayState;
import software.bernie.geckolib3.core.controller.AnimationController;
import software.bernie.geckolib3.core.event.predicate.AnimationEvent;
import software.bernie.geckolib3.core.manager.AnimationData;
import software.bernie.geckolib3.core.manager.AnimationFactory;

import java.util.UUID;

public class FakePlayer extends EntityLiving implements IAnimatable, IAnimationTickable {

    private UUID targetPlayerUUID;
    private EntityPlayer p;
    private int waitTicks = 0;
    private static final int MAX_WAIT_TICKS = 100;

    private float yaw_temp;

    @SuppressWarnings("unused")
    public FakePlayer(World worldIn) {
        super(worldIn);
        if (world.isRemote) {
            targetPlayerUUID = Minecraft.getMinecraft().player.getUniqueID();
        } else {
            setDead();
        }
    }

    public FakePlayer(World world, UUID uuid) {
        super(world);
        this.targetPlayerUUID = uuid;
        setSize(0.6f, 1.8f);

        if (!world.isRemote) {
            this.setNoGravity(true);
            this.setSilent(true);
        }

        setPosition(0, 0, 0);
    }

    public UUID getTargetPlayerUUID() {
        return targetPlayerUUID;
    }

    public void setRenderer() {
        if (targetPlayerUUID == null || world == null) return;
        if (p == null) {
            return;
        }

        Vec2f input = world.isRemote ?
                new Vec2f(FakeInput.get(p).moveForward, FakeInput.get(p).moveStrafing) :
                new Vec2f(InputData.get(p).moveForward, InputData.get(p).moveStrafing);
        float targetYaw;

        if (input.x == 0 && input.y == 0) {
            targetYaw = yaw_temp;
        } else {
            double delta = Math.atan2(input.y, input.x) * 180 / Math.PI;
            targetYaw = (float) (p.rotationYawHead - delta) % 360;
            yaw_temp = targetYaw;
        }

        float yawDiff = (targetYaw - this.rotationYaw) % 360;
        if (yawDiff > 180) yawDiff -= 360;
        if (yawDiff < -180) yawDiff += 360;
        float fact = 0.4f;
        this.rotationYaw += yawDiff * fact;
        this.rotationYawHead = this.rotationYaw;
        this.renderYawOffset = this.rotationYaw;
        this.prevRotationYaw += (this.rotationYaw - this.prevRotationYaw) * fact;
        this.prevRotationYawHead += (this.rotationYawHead - this.prevRotationYawHead) * fact;
        this.prevRenderYawOffset += (this.renderYawOffset - this.prevRenderYawOffset) * fact;

        this.setPosition(p.posX, p.posY, p.posZ);
    }

    @Override
    public void onLivingUpdate() {
        if (targetPlayerUUID == null) {
            setDead();
            return;
        }

        if (p == null) {
            p = world.getPlayerEntityByUUID(targetPlayerUUID);
            if (p == null) {
                waitTicks++;
                if (waitTicks > MAX_WAIT_TICKS) {
                    setDead();
                }
                return;
            }
        }

        if (!p.isEntityAlive()) {
            setDead();
            return;
        }

        setRenderer();
    }

    @Override
    public void setDead() {
        super.setDead();
    }

    private final AnimationFactory factory = new AnimationFactory(this);

    private <E extends IAnimatable> PlayState predicate(AnimationEvent<E> event) {
        return PlayState.CONTINUE;
    }

    @Override
    public void registerControllers(AnimationData animationData) {
        AnimationController<FakePlayer> controller = new AnimationController<>(
                this,
                "controller",
                0,
                this::predicate
        );
        animationData.addAnimationController(controller);
    }

    @Override
    public AnimationFactory getFactory() {
        return this.factory;
    }

    @Override
    public void tick() {

    }

    @Override
    public int tickTimer() {
        return 0;
    }
}