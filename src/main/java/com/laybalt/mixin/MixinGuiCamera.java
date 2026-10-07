package com.laybalt.mixin;

import com.laybalt.features.impl.misc.Camera;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Camera Helper portal / nausea overlay hooks (restored from NoammAddons' MixinGui). */
@Mixin(Hud.class)
public abstract class MixinGuiCamera {
    @Inject(method = "extractPortalOverlay", at = @At("HEAD"), cancellable = true)
    public void onRenderPortalOverlay(GuiGraphicsExtractor graphics, float alpha, CallbackInfo ci) {
        if (Camera.INSTANCE.enabled && Camera.getHidePortalOverlay().getValue()) ci.cancel();
    }

    @Inject(method = "extractConfusionOverlay", at = @At("HEAD"), cancellable = true)
    public void onRenderConfusionOverlay(GuiGraphicsExtractor graphics, float strength, CallbackInfo ci) {
        if (Camera.INSTANCE.enabled && Camera.getDisableNausea().getValue()) ci.cancel();
    }
}
