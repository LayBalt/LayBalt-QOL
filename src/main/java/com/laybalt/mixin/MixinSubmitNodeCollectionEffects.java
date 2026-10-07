package com.laybalt.mixin;

import com.laybalt.utils.render.world.EffectRenderTypes;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.feature.CustomFeatureRenderer;
import net.minecraft.client.renderer.feature.phase.SimpleFeatureRenderPhase;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Same routing as {@link MixinSubmitNodeCollection}, for the effect render types (particles, rings, ribbons):
 * depth-tested ones after terrain, see-through ones on top of everything.
 */
@Mixin(SubmitNodeCollection.class)
public class MixinSubmitNodeCollectionEffects {
    @Shadow @Final public SimpleFeatureRenderPhase afterTerrain;
    @Shadow @Final public SimpleFeatureRenderPhase alwaysOnTop;

    @Inject(method = "submitCustomGeometry", at = @At("HEAD"), cancellable = true)
    private void lbqmod$routeEffectRenderTypes(PoseStack poseStack, RenderType renderType, SubmitNodeCollector.CustomGeometryRenderer customGeometryRenderer, CallbackInfo ci) {
        SimpleFeatureRenderPhase phase;
        if (EffectRenderTypes.alwaysOnTopTypes.contains(renderType)) phase = alwaysOnTop;
        else if (EffectRenderTypes.afterTerrainTypes.contains(renderType)) phase = afterTerrain;
        else return;

        phase.submit(new CustomFeatureRenderer.Submit(poseStack.last().copy(), renderType, customGeometryRenderer));
        ci.cancel();
    }
}
