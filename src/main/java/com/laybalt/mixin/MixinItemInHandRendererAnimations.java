package com.laybalt.mixin;

import com.laybalt.LayBaltQOL;
import com.laybalt.features.impl.visual.Animations;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Animations additions kept apart from {@link MixinItemInHandRenderer}:
 * swing styles (replace vanilla's {@code swingArm} transform) and per-hand equip animation toggles.
 */
@Mixin(ItemInHandRenderer.class)
public abstract class MixinItemInHandRendererAnimations {
    @Shadow private ItemStack mainHandItem;
    @Shadow private ItemStack offHandItem;
    @Shadow private float oMainHandHeight;
    @Shadow private float mainHandHeight;
    @Shadow private float oOffHandHeight;
    @Shadow private float offHandHeight;

    @WrapOperation(
        method = "submitArmWithItem",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;swingArm(FLcom/mojang/blaze3d/vertex/PoseStack;ILnet/minecraft/world/entity/HumanoidArm;)V")
    )
    private void lbqmod$swingStyle(ItemInHandRenderer instance, float attack, PoseStack poseStack, int invert, HumanoidArm arm, Operation<Void> original) {
        if (Animations.hasCustomSwingStyle()) {
            Animations.applySwingStyle(poseStack, attack, invert);
            return;
        }
        original.call(instance, attack, poseStack, invert, arm);
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void lbqmod$skipSwapHead(CallbackInfo ci) {
        if (!Animations.INSTANCE.enabled || Animations.getDisableEquip().getValue()) return;
        LocalPlayer player = LayBaltQOL.getMc().player;
        if (player == null) return;
        if (Animations.getDisableSwapMain().getValue()) mainHandItem = player.getMainHandItem();
        if (Animations.getDisableSwapOff().getValue()) offHandItem = player.getOffhandItem();
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void lbqmod$skipSwapTail(CallbackInfo ci) {
        if (!Animations.INSTANCE.enabled || Animations.getDisableEquip().getValue()) return;
        if (Animations.getDisableSwapMain().getValue()) {
            oMainHandHeight = 1f;
            mainHandHeight = 1f;
        }
        if (Animations.getDisableSwapOff().getValue()) {
            oOffHandHeight = 1f;
            offHandHeight = 1f;
        }
    }
}
