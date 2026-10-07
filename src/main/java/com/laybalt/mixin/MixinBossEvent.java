package com.laybalt.mixin;

import com.laybalt.event.EventBus;
import com.laybalt.event.impl.BossBarUpdateEvent;
import net.minecraft.client.gui.components.LerpingBossEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.BossEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BossEvent.class)
public class MixinBossEvent {
    @Shadow protected Component name;
    @Shadow protected float progress;

    @Inject(method = "setName", at = @At("HEAD"))
    private void onSetName(Component name, CallbackInfo ci) {
        if ((Object) this instanceof LerpingBossEvent self) {
            EventBus.post(new BossBarUpdateEvent(name, ((ILerpingBossEvent) self).getTargetPrecent()));
        }
    }
}