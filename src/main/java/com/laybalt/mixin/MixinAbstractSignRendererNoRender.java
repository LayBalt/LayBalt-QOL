package com.laybalt.mixin;

import com.laybalt.features.impl.render.NoRender;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.AbstractSignRenderer;
import net.minecraft.client.renderer.blockentity.state.SignRenderState;
import net.minecraft.world.level.block.entity.SignText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** NoRender "Sign Text": skips the text of standing, wall and hanging signs. */
@Mixin(AbstractSignRenderer.class)
public abstract class MixinAbstractSignRendererNoRender {
    @Inject(method = "submitSignText", at = @At("HEAD"), cancellable = true)
    private void lbqmod$hideSignText(SignRenderState state, PoseStack poseStack, SubmitNodeCollector collector, SignText text, CallbackInfo ci) {
        if (NoRender.active(NoRender.getSignText())) ci.cancel();
    }
}
