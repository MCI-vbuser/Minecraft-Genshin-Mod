package com.vbuser.genshin.tool.cv;
import net.minecraft.client.gui.GuiScreen;

public class GuiVideoOverlay extends GuiScreen {

    public GuiVideoOverlay() {
        this.allowUserInput = false;
        this.mc = net.minecraft.client.Minecraft.getMinecraft();
    }

    @Override
    public void initGui() {
        super.initGui();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
    }


    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    public void closeOverlay() {
        this.mc.displayGuiScreen(null);
    }
}
