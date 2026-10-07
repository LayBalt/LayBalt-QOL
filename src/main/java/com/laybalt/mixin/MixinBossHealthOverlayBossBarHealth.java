package com.laybalt.mixin;

import com.laybalt.features.impl.dungeon.BossBarHealth;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.world.BossEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Bossbar Health "Hide BossBar": skips the bar texture but keeps the boss name (and its health text). */
@Mixin(BossHealthOverlay.class)
public abstract class MixinBossHealthOverlayBossBarHealth {
    @WrapOperation(method = "extractRenderState", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/BossHealthOverlay;extractBar(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IILnet/minecraft/world/BossEvent;)V"))
    private void lbqmod$hideBar(BossHealthOverlay instance, GuiGraphicsExtractor graphics, int x, int y, BossEvent event, Operation<Void> original) {
        if (BossBarHealth.shouldHideBar()) return;
        original.call(instance, graphics, x, y, event);
    }
}
