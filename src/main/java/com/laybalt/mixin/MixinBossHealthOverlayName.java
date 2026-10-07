package com.laybalt.mixin;

import com.laybalt.event.EventBus;
import com.laybalt.event.impl.BossBarRenderEvent;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.client.gui.components.LerpingBossEvent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(BossHealthOverlay.class)
public class MixinBossHealthOverlayName {
    @WrapOperation(method = "extractRenderState", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/LerpingBossEvent;getName()Lnet/minecraft/network/chat/Component;"))
    private Component lbq$onRenderBossName(LerpingBossEvent instance, Operation<Component> original) {
        Component name = original.call(instance);
        BossBarRenderEvent event = new BossBarRenderEvent(instance, name);
        EventBus.post(event);
        return event.getName();
    }
}
