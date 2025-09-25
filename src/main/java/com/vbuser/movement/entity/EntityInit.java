package com.vbuser.movement.entity;

import com.vbuser.movement.Movement;
import net.minecraft.entity.Entity;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.registry.EntityRegistry;

public class EntityInit {

    public static void registerEntities() {
        registerEntity("fake_player", FakePlayer.class, 31, 1, 1145141, 1919810);
    }

    public static void registerEntity(String name, Class<? extends Entity> entity, int id, int range, int color1, int color2) {
        EntityRegistry.registerModEntity(new ResourceLocation("movement" + ":" + name),
                entity, name, id, Movement.instance, range, 1, true
                , color1, color2);
    }
}
