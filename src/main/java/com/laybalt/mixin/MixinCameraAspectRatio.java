package com.laybalt.mixin;

import com.laybalt.features.impl.render.AspectRatio;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.Projection;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * AspectRatio: the world projection uses (ratio * height) x height instead of the window size;
 * the culling frustum gets the same ratio so chunks at the screen edges are not culled too early.
 */
@Mixin(Camera.class)
public abstract class MixinCameraAspectRatio {
    @WrapOperation(method = "setupPerspective", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/Projection;setupPerspective(FFFFF)V"))
    private void lbqmod$forceRatio(Projection projection, float zNear, float zFar, float fov, float width, float height, Operation<Void> original) {
        if (AspectRatio.INSTANCE.enabled && height > 0f) width = height * AspectRatio.ratioValue();
        original.call(projection, zNear, zFar, fov, width, height);
    }

    @WrapOperation(method = "createProjectionMatrixForCulling", at = @At(value = "INVOKE", target = "Lorg/joml/Matrix4f;perspective(FFFFZ)Lorg/joml/Matrix4f;"))
    private Matrix4f lbqmod$forceCullingRatio(Matrix4f matrix, float fovY, float aspect, float zNear, float zFar, boolean zZeroToOne, Operation<Matrix4f> original) {
        if (AspectRatio.INSTANCE.enabled) aspect = AspectRatio.ratioValue();
        return original.call(matrix, fovY, aspect, zNear, zFar, zZeroToOne);
    }
}
