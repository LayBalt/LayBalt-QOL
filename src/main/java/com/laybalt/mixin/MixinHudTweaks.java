package com.laybalt.mixin;

import com.laybalt.features.impl.misc.Tweaks;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tweaks > Hide Hotbar Tooltips: skips the held item name shown above the hotbar (restored from NoammAddons). */
@Mixin(Hud.class)
public abstract class MixinHudTweaks {
    @Inject(method = "extractSelectedItemName", at = @At("HEAD"), cancellable = true)
    private void lbqmod$hideHotbarTooltip(GuiGraphicsExtractor graphics, CallbackInfo ci) {
        if (Tweaks.shouldHideHotbarTooltip()) ci.cancel();
    }
}
