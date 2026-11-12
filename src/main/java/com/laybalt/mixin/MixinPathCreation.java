package com.laybalt.mixin;

import com.laybalt.features.impl.misc.Tweaks;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.item.context.UseOnContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Tweaks > No AOTV Paths (NoammAddons c346e539): the Aspect of the Void is a diamond shovel, so right clicking grass
 * client side flattens it into a ghost dirt path and plays the shovel sound. Stops right before that sound.
 */
@Mixin(ShovelItem.class)
public class MixinPathCreation {
    @Inject(method = "useOn", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;playSound(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/core/BlockPos;Lnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FF)V"), cancellable = true)
    private void lbqmod$preventAotvPaths(UseOnContext context, CallbackInfoReturnable<InteractionResult> cir) {
        if (context.getLevel().isClientSide() && Tweaks.shouldPreventAotvPaths(context.getItemInHand())) {
            cir.setReturnValue(InteractionResult.SUCCESS);
        }
    }
}
