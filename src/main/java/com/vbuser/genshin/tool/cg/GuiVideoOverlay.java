package com.vbuser.genshin.tool.cg;

import com.vbuser.genshin.tool.cg.decoder.VideoPlaybackManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;

/**
 * Full screen cg overlay.
 *
 * <p>The actual video quad is drawn by {@link PlayerEventHandler}; this screen only owns
 * the lifecycle and the "restore whatever GUI was open before" behavior, so a cg started
 * from a menu no longer dumps the player into the world with no GUI.</p>
 */
public class GuiVideoOverlay extends GuiScreen {

    private final GuiScreen previous;

    public GuiVideoOverlay(GuiScreen previous) {
        this.previous = previous;
        this.allowUserInput = false;
        this.mc = Minecraft.getMinecraft();
    }

    @Override
    public void initGui() {
        super.initGui();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        // The frame is rendered by PlayerEventHandler.
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    /** Closes the overlay and restores the GUI that was open before it. */
    public void closeOverlay() {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.currentScreen == this) {
            minecraft.displayGuiScreen(previous);
        }
    }

    @Override
    public void onGuiClosed() {
        super.onGuiClosed();
        VideoPlaybackManager.getInstance().onOverlayClosed();
    }
}
