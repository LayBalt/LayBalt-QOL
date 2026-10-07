package com.laybalt.mixin;

import com.laybalt.features.impl.render.NoRender;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** NoRender HUD toggles: vignette, boss bar, scoreboard, held item name and titles (drawing only). */
@Mixin(Hud.class)
public abstract class MixinHudNoRender {
    @Inject(method = "extractVignette", at = @At("HEAD"), cancellable = true)
    private void lbqmod$hideVignette(GuiGraphicsExtractor graphics, Entity camera, CallbackInfo ci) {
        if (NoRender.active(NoRender.getVignette())) ci.cancel();
    }

    @Inject(method = "extractBossOverlay", at = @At("HEAD"), cancellable = true)
    private void lbqmod$hideBossBar(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        if (NoRender.active(NoRender.getBossBar())) ci.cancel();
    }

    @Inject(method = "extractScoreboardSidebar", at = @At("HEAD"), cancellable = true)
    private void lbqmod$hideScoreboard(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        if (NoRender.active(NoRender.getScoreboard())) ci.cancel();
    }

    @Inject(method = "extractSelectedItemName", at = @At("HEAD"), cancellable = true)
    private void lbqmod$hideHeldItemName(GuiGraphicsExtractor graphics, CallbackInfo ci) {
        if (NoRender.active(NoRender.getHeldItemName())) ci.cancel();
    }

    @Inject(method = "extractTitle", at = @At("HEAD"), cancellable = true)
    private void lbqmod$hideTitles(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        if (NoRender.active(NoRender.getTitles())) ci.cancel();
    }
}
