package com.vbuser.genshin.debug;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public class ClientDebugHandler {
    private static boolean debugEnabled = false;
    private static String entityFilter = "";

    public static void setDebugEnabled(boolean enabled, String filter) {
        debugEnabled = enabled;
        entityFilter = filter;
    }

    @SubscribeEvent
    public void onRenderOverlay(RenderGameOverlayEvent.Post event) {
        if (!debugEnabled || event.getType() != RenderGameOverlayEvent.ElementType.TEXT) {
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();
        if (mc.world == null) return;

        int count = 0;
        ResourceLocation filterResource = null;

        if (!entityFilter.isEmpty()) {
            try {
                filterResource = new ResourceLocation(entityFilter);
            } catch (Exception e) {
                return;
            }
        }

        for (Entity entity : mc.world.loadedEntityList) {
            if (filterResource == null) {
                count++;
            } else {
                ResourceLocation entityKey = EntityList.getKey(entity);
                if (entityKey != null && entityKey.equals(filterResource)) {
                    count++;
                }
            }
        }

        int x = 10;
        int y = 10;

        Gui.drawRect(x - 2, y - 2, x + 200, y + 40, 0x80000000);

        String title = "实体调试信息";
        String filterText = entityFilter.isEmpty() ? "所有实体" : "实体: " + entityFilter;
        String countText = "数量: " + count;
        String dimensionText = "维度: " + (mc.world != null ? mc.world.provider.getDimension() : "未知");

        mc.fontRenderer.drawStringWithShadow(title, x, y, 0xFFFFFF);
        mc.fontRenderer.drawStringWithShadow(filterText, x, y + 10, 0xAAAAAA);
        mc.fontRenderer.drawStringWithShadow(countText, x, y + 20, 0x55FF55);
        mc.fontRenderer.drawStringWithShadow(dimensionText, x, y + 30, 0xAAAAAA);
    }
}
