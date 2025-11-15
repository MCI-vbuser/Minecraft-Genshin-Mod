package com.vbuser.genshin.init;

import com.vbuser.movement.Movement;
import com.vbuser.movement.entity.FakePlayer;
import net.minecraft.entity.Entity;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.registry.EntityRegistry;

public class EntityInit {
    private static int ID = 31;
    public static void registerEntities() {
        registerEntity("fake_player", FakePlayer.class, "movement", ID++, Movement.instance);
    }

    private static void registerEntity(String name, Class<? extends Entity> entity, String modid, int id, Object instance) {
        EntityRegistry.registerModEntity(new ResourceLocation(modid, name), entity, name, id, instance, 64, 1, true);
        ID++;
    }
}
