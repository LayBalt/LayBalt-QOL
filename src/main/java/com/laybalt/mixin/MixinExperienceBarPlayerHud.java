package com.laybalt.mixin;

import com.laybalt.features.impl.visual.PlayerHud;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.contextualbar.ExperienceBar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** PlayerHud "Hide XP Level": hides the experience bar background. */
@Mixin(ExperienceBar.class)
public abstract class MixinExperienceBarPlayerHud {
    @Inject(method = "extractBackground", at = @At("HEAD"), cancellable = true)
    private void lbq$hideXpBar(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        if (PlayerHud.shouldHideXp()) ci.cancel();
    }
}
