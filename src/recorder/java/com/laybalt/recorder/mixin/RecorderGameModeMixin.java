package com.laybalt.recorder.mixin;

import com.laybalt.recorder.RecorderHooks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Your clicks in container menus (loadouts, wardrobe, pets), at the same point as LayBalt QOL's MixinMultiPlayerGameMode. */
@Mixin(MultiPlayerGameMode.class)
public abstract class RecorderGameModeMixin {
    @Shadow @Final private Minecraft minecraft;

    @Inject(method = "handleContainerInput", at = @At("HEAD"))
    private void laybaltrecorder$onSlotClick(int containerId, int slotNum, int buttonNum, ContainerInput containerInput, Player player, CallbackInfo ci) {
        if (! (minecraft.gui.screen() instanceof AbstractContainerScreen<?> screen)) return;
        RecorderHooks.slotClick(screen, slotNum, buttonNum, containerInput);
    }
}
