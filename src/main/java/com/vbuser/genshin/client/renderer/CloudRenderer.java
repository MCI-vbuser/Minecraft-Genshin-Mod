package com.vbuser.genshin.client.renderer;

import com.vbuser.genshin.block.BlockCloud;
import com.vbuser.genshin.block.ModBlocks;
import net.minecraftforge.client.event.ColorHandlerEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

public class CloudRenderer {
    @SubscribeEvent
    public void onBlockColorHandler(ColorHandlerEvent.Block event) {
        event.getBlockColors().registerBlockColorHandler((state, world, pos, tintIndex) -> {
            if (state.getBlock() instanceof BlockCloud) {
                int density = state.getValue(BlockCloud.DENSITY);

                float alpha = 0.4f + (density / 15.0f) * 0.55f;

                float brightness = 0.9f - (density / 15.0f) * 0.4f;
                int r = (int) (255 * brightness * 0.98f);
                int g = (int) (255 * brightness * 0.97f);
                int b = (int) (255 * brightness * 1.0f);

                int a = (int) (alpha * 255);
                return (a << 24) | (r << 16) | (g << 8) | b;
            }
            return 0xFFFFFFFF;
        }, ModBlocks.CLOUD_BLOCK);
    }
}
