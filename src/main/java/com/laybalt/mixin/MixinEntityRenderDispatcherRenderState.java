package com.laybalt.mixin;

import com.laybalt.event.EventBus;
import com.laybalt.event.impl.EntityRenderStateEvent;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Posts {@link EntityRenderStateEvent} after an entity's render state has been extracted, so features can
 * change how the entity is drawn this frame (used by Ghosts).
 */
@Mixin(EntityRenderDispatcher.class)
public class MixinEntityRenderDispatcherRenderState {
    @Inject(method = "extractEntity", at = @At("RETURN"))
    private <E extends Entity> void laybalt$postRenderStateEvent(E entity, float partialTick, CallbackInfoReturnable<EntityRenderState> cir) {
        if (!EventBus.INSTANCE.getListeners().containsKey(EntityRenderStateEvent.class)) return;
        EntityRenderState state = cir.getReturnValue();
        if (state == null) return;
        EventBus.post(new EntityRenderStateEvent(entity, state));
    }
}
