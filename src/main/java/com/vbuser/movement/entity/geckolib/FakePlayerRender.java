package com.vbuser.movement.entity.geckolib;

import com.vbuser.movement.entity.FakePlayer;
import net.minecraft.client.renderer.entity.RenderManager;
import software.bernie.geckolib3.renderers.geo.GeoEntityRenderer;

public class FakePlayerRender extends GeoEntityRenderer<FakePlayer> {
    public FakePlayerRender(RenderManager renderManager) {
        super(renderManager, new FakePlayerModel());
    }
}
