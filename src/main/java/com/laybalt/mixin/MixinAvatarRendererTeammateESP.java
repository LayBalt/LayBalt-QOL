package com.laybalt.mixin;

import com.laybalt.features.impl.dungeon.TeammateESP;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.world.entity.Avatar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hides the vanilla nametag of dungeon teammates while Teammate ESP draws its own class-coloured label.
 * Priority 900 so this HEAD hook runs before the NameTagTweaks one in {@link MixinAvatarRenderer}.
 */
@Mixin(value = AvatarRenderer.class, priority = 900)
public class MixinAvatarRendererTeammateESP {
    @Inject(method = "shouldShowName(Lnet/minecraft/world/entity/Avatar;D)Z", at = @At("HEAD"), cancellable = true)
    private void lbqmod$hideTeammateName(Avatar entity, double distanceToCameraSq, CallbackInfoReturnable<Boolean> cir) {
        if (TeammateESP.shouldHideNametag(entity)) cir.setReturnValue(false);
    }
}
