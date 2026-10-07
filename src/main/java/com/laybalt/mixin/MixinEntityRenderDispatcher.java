package com.laybalt.mixin;

import com.laybalt.event.EventBus;
import com.laybalt.event.impl.CheckEntityRenderEvent;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityRenderDispatcher.class)
public class MixinEntityRenderDispatcher {
    @Inject(method = "shouldRender", at = @At("RETURN"), cancellable = true)
    private <T extends Entity> void onShouldRender(T entity, Frustum culler, double camX, double camY, double camZ, CallbackInfoReturnable<Boolean> cir) {
        if (! cir.getReturnValue()) return;
        if (EventBus.post(new CheckEntityRenderEvent(entity))) {
            cir.setReturnValue(false);
        }
    }
}
