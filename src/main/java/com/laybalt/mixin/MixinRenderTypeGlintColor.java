package com.laybalt.mixin;

import com.laybalt.features.impl.render.GlintColor;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import net.minecraft.client.renderer.DynamicUniforms;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Glint Color: the glint shader multiplies the glint texture by the ColorModulator uniform, which vanilla always
 * writes as white for render types. For the four glint render types (all on {@code RenderPipelines.GLINT}) the
 * modulator is replaced with the colour computed by {@link GlintColor}.
 */
@Mixin(RenderType.class)
public abstract class MixinRenderTypeGlintColor {
    @WrapOperation(
        method = "writeDynamicTransforms",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/DynamicUniforms;writeTransform(Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;)Lcom/mojang/blaze3d/buffers/GpuBufferSlice;")
    )
    private GpuBufferSlice lbqmod$glintColor(DynamicUniforms instance, Matrix4f modelView, Matrix4f textureMatrix, Operation<GpuBufferSlice> original) {
        RenderType self = (RenderType) (Object) this;
        if (self.pipeline() == RenderPipelines.GLINT) {
            Vector4f modulator = GlintColor.modulator(self == RenderTypes.armorEntityGlint());
            if (modulator != null) return instance.writeTransform(modelView, modulator, new Vector3f(), textureMatrix);
        }
        return original.call(instance, modelView, textureMatrix);
    }
}
