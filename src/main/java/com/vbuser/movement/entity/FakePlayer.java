package com.vbuser.movement.entity;

import com.vbuser.movement.event.FakeInput;
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

    private UUID pid;
    private EntityPlayer p;
    private int count = 0;

    private float yaw_temp;

    @SuppressWarnings("unused")
    public FakePlayer(World worldIn) {
        super(worldIn);
        setSize(0.6f, 1.8f);
    }

    public FakePlayer(World world, UUID uuid) {
        super(world);
        this.pid = uuid;
        if (world.getPlayerEntityByUUID(pid) != null) p = world.getPlayerEntityByUUID(pid);
        setSize(0.6f, 1.8f);
    }

    public void setRenderer() {
        Vec2f input = new Vec2f(FakeInput.get(p).moveForward, FakeInput.get(p).moveStrafing);
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
    }

    @Override
    public void onLivingUpdate() {
        if(!world.isRemote){
            this.setDead();
        }
        if (count > 100) {
            this.setDead();
            System.out.println("Clear FP, reason: fail to match player");
        }
        if (world != null) {
            if (pid == null || world.getPlayerEntityByUUID(pid) == null) {
                count++;
            }
            if (pid != null && p == null) {
                try {
                    p = world.getPlayerEntityByUUID(pid);
                } catch (Exception e) {
                    count++;
                }
            }
        }
    }

    @Override
    public void setDead() {
        super.setDead();
        if (!world.isRemote) System.out.println("Killed FP as Spawned on Server Side, as it's not necessary.");
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