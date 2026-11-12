package com.laybalt.recorder.mixin;

import com.laybalt.features.impl.fishing.FishingRecorder;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Your attack and use clicks, at the same points as LayBalt QOL's MixinMinecraft. */
@Mixin(Minecraft.class)
public abstract class RecorderMinecraftMixin {
    @Inject(method = "startAttack", at = @At("HEAD"))
    private void laybaltrecorder$onStartAttack(CallbackInfoReturnable<Boolean> cir) {
        FishingRecorder.onClick(true);
    }

    @Inject(
        method = "handleKeybinds",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;startUseItem()V", ordinal = 0)
    )
    private void laybaltrecorder$onUseClick(CallbackInfo ci) {
        FishingRecorder.onClick(false);
    }

    /** The use repeat while the use key is held (every 4 ticks). */
    @Inject(
        method = "handleKeybinds",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;startUseItem()V", ordinal = 1)
    )
    private void laybaltrecorder$onUseHeld(CallbackInfo ci) {
        FishingRecorder.onHeldUse();
    }
}
