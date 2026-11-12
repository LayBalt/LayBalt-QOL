package com.laybalt.mixin;

import com.laybalt.features.impl.misc.Tweaks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tweaks "Close Recipe Book": an open recipe book is closed whenever an inventory with one opens. */
@Mixin(RecipeBookComponent.class)
public abstract class MixinRecipeBookComponentTweaks {
    @Shadow
    protected abstract void setVisible(boolean visible);

    @Inject(method = "init", at = @At("TAIL"))
    private void lbqmod$closeRecipeBook(int width, int height, Minecraft minecraft, boolean widthTooNarrow, CallbackInfo ci) {
        if (Tweaks.shouldCloseRecipeBook()) setVisible(false);
    }
}
