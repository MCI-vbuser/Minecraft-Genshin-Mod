package com.vbuser.genshin.util.handler;

import com.vbuser.movement.entity.FakePlayer;
import com.vbuser.movement.entity.geckolib.FakePlayerRender;
import net.minecraftforge.fml.client.registry.RenderingRegistry;

public class RenderHandler {
    public static void registerRenders(){
        RenderingRegistry.registerEntityRenderingHandler(FakePlayer.class, FakePlayerRender::new);
    }
}
