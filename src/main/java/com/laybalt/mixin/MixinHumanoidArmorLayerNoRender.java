package com.laybalt.mixin;

import com.laybalt.features.impl.render.NoRender;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** NoRender "Armor": skips every armor piece of humanoid entities (players, zombies, skeletons...). */
@Mixin(HumanoidArmorLayer.class)
public abstract class MixinHumanoidArmorLayerNoRender {
    @Inject(method = "renderArmorPiece", at = @At("HEAD"), cancellable = true)
    private void lbqmod$hideArmor(PoseStack poseStack, SubmitNodeCollector collector, ItemStack stack, EquipmentSlot slot, int lightCoords, HumanoidRenderState state, CallbackInfo ci) {
        if (NoRender.active(NoRender.getArmor())) ci.cancel();
    }
}
