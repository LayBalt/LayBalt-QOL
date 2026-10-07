package com.laybalt.mixin;

import com.laybalt.features.impl.render.PlayerSize;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Player Size: scales the player model in the renderer's scale step. */
@Mixin(AvatarRenderer.class)
public abstract class MixinAvatarRendererPlayerSize {
    @Inject(method = "scale(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;)V", at = @At("HEAD"))
    private void lbqmod$playerSize(AvatarRenderState state, PoseStack poseStack, CallbackInfo ci) {
        PlayerSize.scaleHook(state, poseStack);
    }
}
