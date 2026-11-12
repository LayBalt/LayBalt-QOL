package com.laybalt.mixin;

import com.laybalt.features.impl.visual.PlayerHud;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** PlayerHud "Hide XP Level": hides the experience level number drawn by the hotbar. */
@Mixin(Hud.class)
public abstract class MixinHudPlayerHud {
    @ModifyExpressionValue(method = "extractHotbarAndDecorations", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;hasExperience()Z"))
    private boolean lbq$hideXpLevel(boolean original) {
        if (PlayerHud.shouldHideXp()) return false;
        return original;
    }
}
