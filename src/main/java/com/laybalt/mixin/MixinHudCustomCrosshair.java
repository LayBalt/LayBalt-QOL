package com.laybalt.mixin;

import com.laybalt.features.impl.render.CustomCrosshair;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Custom Crosshair: replaces the vanilla crosshair, or draws over it when "Hide Vanilla" is off. */
@Mixin(Hud.class)
public abstract class MixinHudCustomCrosshair {
    @Inject(method = "extractCrosshair", at = @At("HEAD"), cancellable = true)
    private void lbqmod$replaceCrosshair(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        if (!CustomCrosshair.replacesVanilla()) return;
        CustomCrosshair.render(graphics);
        ci.cancel();
    }

    @Inject(method = "extractCrosshair", at = @At("RETURN"))
    private void lbqmod$overlayCrosshair(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        if (CustomCrosshair.drawsOverVanilla()) CustomCrosshair.render(graphics);
    }
}
