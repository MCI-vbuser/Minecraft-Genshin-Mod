package com.vbuser.movement.capability;

import com.vbuser.genshin.data.save.RuleManager;
import com.vbuser.movement.event.glider.GliderInputHandler;
import com.vbuser.movement.event.glider.GliderEvents;
import com.vbuser.movement.network.glider.GliderNetwork;
import net.minecraft.block.material.Material;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;

public final class GliderUtils {
    public static boolean isTwoBlocksAirBelow(EntityPlayer player) {
        World w = player.world;
        BlockPos base = new BlockPos(player);
        BlockPos d1 = base.down(1);
        BlockPos d2 = base.down(2);
        return isAir(w, d1) && isAir(w, d2);
    }
    public static boolean isEnabled() {
        return RuleManager.getBoolean("glider_enabled",true);
    }
    private static boolean isAir(World w, BlockPos p) {
        return w.isAirBlock(p) || w.getBlockState(p).getMaterial() == Material.AIR;
    }

    public static void initGlider(FMLPreInitializationEvent e) {
        GliderNetwork.init();
        MinecraftForge.EVENT_BUS.register(new GliderEvents());
        if (e.getSide().isClient()) {
            MinecraftForge.EVENT_BUS.register(new GliderInputHandler());
        }
    }
    private GliderUtils() {}
}
