package com.laybalt.mixin;

import com.laybalt.features.impl.render.Fullbright;
import com.laybalt.features.impl.render.NoRender;
import net.minecraft.client.renderer.Lightmap;
import net.minecraft.client.renderer.state.LightmapRenderState;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Fullbright (minimum brightness floor) and NoRender "Darkness" (darkness light-map pulse).
 * The light-map shader adds sky and block light on top of the ambient colour, so raising the ambient colour
 * to the floor guarantees that no light level renders darker than it.
 */
@Mixin(Lightmap.class)
public abstract class MixinLightmapFullbright {
    @ModifyVariable(method = "render", at = @At("HEAD"), argsOnly = true)
    private LightmapRenderState lbqmod$fullbright(LightmapRenderState state) {
        if (NoRender.active(NoRender.getDarkness())) state.darknessEffectScale = 0f;
        if (!Fullbright.INSTANCE.enabled) return state;

        float floor = Fullbright.floor();
        Vector3fc ambient = state.ambientColor;
        if (ambient == null) state.ambientColor = new Vector3f(floor);
        else if (ambient.x() < floor || ambient.y() < floor || ambient.z() < floor) {
            state.ambientColor = new Vector3f(Math.max(ambient.x(), floor), Math.max(ambient.y(), floor), Math.max(ambient.z(), floor));
        }
        if (Fullbright.getMaxGamma().getValue()) state.brightness = 1f;
        return state;
    }
}
