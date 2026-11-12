package com.laybalt.mixin;

import com.laybalt.features.impl.render.NoRender;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.client.renderer.state.level.WeatherRenderState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** NoRender "Weather": skips drawing the rain and snow columns. */
@Mixin(WeatherEffectRenderer.class)
public abstract class MixinWeatherEffectRendererNoRender {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void lbqmod$hideWeather(Vec3 cameraPos, WeatherRenderState state, CallbackInfo ci) {
        if (NoRender.active(NoRender.getWeather())) ci.cancel();
    }
}
