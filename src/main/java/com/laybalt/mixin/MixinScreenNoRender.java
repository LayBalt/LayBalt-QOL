package com.laybalt.mixin;

import com.laybalt.features.impl.render.NoRender;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * NoRender "Menu Background": while a world is loaded, skips the dark gradient behind in-game UIs
 * (inventories, containers) and the blur + darkened texture behind other menus (pause, options).
 * Title screen / world-less menus are untouched.
 */
@Mixin(Screen.class)
public abstract class MixinScreenNoRender {
    @Shadow @Final protected Minecraft minecraft;

    @Unique
    private boolean lbqmod$hideBackground() {
        return minecraft != null && minecraft.level != null && NoRender.active(NoRender.getMenuBackground());
    }

    @Inject(method = "extractTransparentBackground", at = @At("HEAD"), cancellable = true)
    private void lbqmod$hideTint(GuiGraphicsExtractor graphics, CallbackInfo ci) {
        if (lbqmod$hideBackground()) ci.cancel();
    }

    @Inject(method = "extractBlurredBackground", at = @At("HEAD"), cancellable = true)
    private void lbqmod$hideBlur(GuiGraphicsExtractor graphics, CallbackInfo ci) {
        if (lbqmod$hideBackground()) ci.cancel();
    }

    @Inject(method = "extractMenuBackground(Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V", at = @At("HEAD"), cancellable = true)
    private void lbqmod$hideMenuTexture(GuiGraphicsExtractor graphics, CallbackInfo ci) {
        if (lbqmod$hideBackground()) ci.cancel();
    }
}
