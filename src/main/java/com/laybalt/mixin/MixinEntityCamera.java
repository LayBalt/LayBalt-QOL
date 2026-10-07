package com.laybalt.mixin;

import com.laybalt.features.impl.misc.Camera;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public abstract class MixinEntityCamera {
    @Shadow
    public abstract float getYRot();

    /**
     * Fixes high mouse input delay when riding an entity (MC-206540).
     *
     * @author isXander
     * @license LGPL-3.0
     * @see <a href="https://github.com/isXander/Debugify/blob/11bcb3c53dd6cda7475fe3738df64d3835ebd6d1/src/client/java/dev/isxander/debugify/client/mixins/basic/mc206540/EntityMixin.java">Original Debugify Implementation</a>
     */
    @Inject(method = "onPassengerTurned", at = @At("HEAD"))
    private void fixCameraMovement(Entity passenger, CallbackInfo ci) {
        if (!Camera.INSTANCE.enabled || !Camera.getInputFix().getValue()) return;
        if (!passenger.isAlwaysTicking()) return;
        passenger.setYBodyRot(getYRot());
        float f = Mth.wrapDegrees(passenger.getYRot() - getYRot());
        float g = Mth.clamp(f, -180.0F, 180.0F);
        passenger.yRotO += g - f;
        passenger.setYRot(passenger.getYRot() + g - f);
        passenger.setYHeadRot(passenger.getYRot());
    }
}
