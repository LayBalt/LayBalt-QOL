package com.laybalt.mixin;

import com.laybalt.features.impl.misc.Tweaks;
import net.minecraft.client.gui.screens.inventory.AbstractRecipeBookScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tweaks "Hide Recipe Book": the recipe book button is the last thing init adds, so skipping it removes only the button. */
@Mixin(AbstractRecipeBookScreen.class)
public abstract class MixinAbstractRecipeBookScreenTweaks {
    @Inject(method = "init", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/inventory/AbstractRecipeBookScreen;initButton()V"), cancellable = true)
    private void lbqmod$hideRecipeBookButton(CallbackInfo ci) {
        if (Tweaks.shouldHideRecipeBook()) ci.cancel();
    }
}
