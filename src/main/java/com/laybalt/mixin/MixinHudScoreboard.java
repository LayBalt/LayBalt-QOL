package com.laybalt.mixin;

import com.laybalt.features.impl.visual.Scoreboard;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Custom Scoreboard: the vanilla sidebar is skipped while the custom one is drawn as a HUD element. */
@Mixin(Hud.class)
public abstract class MixinHudScoreboard {
    @Inject(method = "extractScoreboardSidebar", at = @At("HEAD"), cancellable = true)
    private void lbqmod$hideVanillaSidebar(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        if (Scoreboard.replacesVanilla()) ci.cancel();
    }
}
