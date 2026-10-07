package com.laybalt.mixin;

import com.laybalt.features.impl.render.NoGlow;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * No Glow: client-side entities never report the vanilla glowing state. LayBalt's own forced glow
 * (CheckEntityGlowEvent / IGlowingEntity) is OR-ed in MixinMinecraft.shouldEntityAppearGlowing and keeps working.
 */
@Mixin(Entity.class)
public abstract class MixinEntityNoGlow {
    @Inject(method = "isCurrentlyGlowing", at = @At("HEAD"), cancellable = true)
    private void laybalt$noGlow(CallbackInfoReturnable<Boolean> cir) {
        if (!NoGlow.INSTANCE.enabled) return;
        if (!((Entity) (Object) this).level().isClientSide()) return;
        cir.setReturnValue(false);
    }
}
