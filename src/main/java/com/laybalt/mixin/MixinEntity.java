package com.laybalt.mixin;

import com.laybalt.features.impl.render.Box3D;
import com.laybalt.interfaces.IGlowingEntity;
import net.minecraft.world.entity.Entity;
import org.jspecify.annotations.NonNull;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.awt.*;

@Mixin(Entity.class)
public abstract class MixinEntity implements IGlowingEntity {
    @Unique private Color glowColor = Color.WHITE;
    @Unique private boolean glowForced = false;

    @Inject(method = "getTeamColor", at = @At("HEAD"), cancellable = true)
    private void onGetTeamColorValue(CallbackInfoReturnable<Integer> cir) {
        if (lbqmod$isGlowing() && !Box3D.INSTANCE.enabled) {
            cir.setReturnValue(lbqmod$glowColor().getRGB());
        }
    }

    @Override
    public Color lbqmod$glowColor() {
        return glowColor;
    }

    @Override
    public void lbqmod$glowColor(@NonNull Color color) {
        glowColor = color;
    }

    @Override
    public boolean lbqmod$isGlowing() {
        return glowForced;
    }

    @Override
    public void lbqmod$isGlowing(boolean value) {
        glowForced = value;
    }
}
