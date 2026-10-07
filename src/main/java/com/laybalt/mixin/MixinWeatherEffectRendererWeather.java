package com.laybalt.mixin;

import com.laybalt.features.impl.render.Weather;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Weather "Precipitation": forces rain or snow columns regardless of the biome. */
@Mixin(WeatherEffectRenderer.class)
public abstract class MixinWeatherEffectRendererWeather {
    @WrapOperation(method = "extractRenderState", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;getPrecipitationAt(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/biome/Biome$Precipitation;"))
    private Biome.Precipitation lbqmod$forcePrecipitation(ClientLevel level, BlockPos pos, Operation<Biome.Precipitation> original) {
        return Weather.modifyPrecipitation(original.call(level, pos));
    }
}
