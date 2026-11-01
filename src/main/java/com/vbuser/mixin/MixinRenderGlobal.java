package com.vbuser.mixin;

import com.vbuser.inf.HeightConstants;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.tileentity.TileEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(RenderGlobal.class)
public class MixinRenderGlobal {

    @Redirect(
            method = "renderEntities",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/tileentity/TileEntityRendererDispatcher;render(Lnet/minecraft/tileentity/TileEntity;FI)V"
            )
    )
    private void inf$skipExtendedTERender(TileEntityRendererDispatcher dispatcher,
                                          TileEntity te,
                                          float partialTicks,
                                          int destroyStage) {
        if (te != null && HeightConstants.isExtendedY(te.getPos().getY())) {
            return;
        }
        assert te != null;
        dispatcher.render(te, partialTicks, destroyStage);
    }
}