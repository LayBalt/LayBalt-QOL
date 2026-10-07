package com.laybalt.mixin;

import com.laybalt.features.impl.misc.Camera;
import net.minecraft.client.renderer.Lightmap;
import net.minecraft.client.renderer.state.LightmapRenderState;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(Lightmap.class)
public abstract class MixinLightmap {
    @Unique private static final Vector3fc LBQMOD$WHITE = new Vector3f(1f, 1f, 1f);

    @ModifyVariable(method = "render", at = @At("HEAD"), argsOnly = true)
    private LightmapRenderState lbqmod$fullbright(LightmapRenderState renderState) {
        if (!Camera.INSTANCE.enabled || !Camera.getFullBright().getValue()) return renderState;

        renderState.needsUpdate = Camera.flashFullFright;
        Camera.flashFullFright = false;
        renderState.skyFactor = 1f;
        renderState.blockFactor = 1f;
        renderState.nightVisionEffectIntensity = 0f;
        renderState.darknessEffectScale = 0f;
        renderState.bossOverlayWorldDarkening = 0f;
        renderState.brightness = 1f;
        renderState.blockLightTint = LBQMOD$WHITE;
        renderState.skyLightColor = LBQMOD$WHITE;
        renderState.ambientColor = LBQMOD$WHITE;
        renderState.nightVisionColor = LBQMOD$WHITE;

        return renderState;
    }
}