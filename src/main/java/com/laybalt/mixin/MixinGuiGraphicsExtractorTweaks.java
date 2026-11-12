package com.laybalt.mixin;

import com.laybalt.features.impl.misc.Tweaks;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Tweaks "Hide Item Cooldowns": skips the white cooldown overlay drawn over item icons. */
@Mixin(GuiGraphicsExtractor.class)
public abstract class MixinGuiGraphicsExtractorTweaks {
    @WrapOperation(
        method = "itemDecorations(Lnet/minecraft/client/gui/Font;Lnet/minecraft/world/item/ItemStack;IILjava/lang/String;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;itemCooldown(Lnet/minecraft/world/item/ItemStack;II)V")
    )
    private void lbqmod$hideItemCooldown(GuiGraphicsExtractor instance, ItemStack itemStack, int x, int y, Operation<Void> original) {
        if (Tweaks.shouldHideItemCooldownOverlay()) return;
        original.call(instance, itemStack, x, y);
    }
}
