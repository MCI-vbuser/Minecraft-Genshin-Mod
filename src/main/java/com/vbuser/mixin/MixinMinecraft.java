package com.vbuser.mixin;

import net.minecraft.client.Minecraft;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.LWJGLException;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.PixelFormat;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(Minecraft.class)
public abstract class MixinMinecraft {

    @Shadow
    private boolean fullscreen;

    @Shadow@Final
    private static final Logger LOGGER = LogManager.getLogger();


    /**
     * @author vbuser
     * @reason To check whether Mixin run normally.
     * 修改Minecraft窗口标题 用于检测Mixin是否正常运行
     */
    @Overwrite
    private void createDisplay() throws LWJGLException {
        Display.setResizable(true);
        Display.setTitle("Minecraft Impact");

        try {
            Display.create((new PixelFormat()).withDepthBits(24));
        } catch (LWJGLException lwjglexception) {
            LOGGER.error("Couldn't set pixel format", lwjglexception);

            try {
                Thread.sleep(1000L);
            } catch (InterruptedException var3) {
                throw new RuntimeException(var3);
            }

            if (this.fullscreen) {
                this.updateDisplayMode();
            }

            Display.create();
        }
    }

    @Shadow
    private void updateDisplayMode() throws LWJGLException {
    }
}