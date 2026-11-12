package com.laybalt.mixin;

import com.laybalt.features.impl.general.ItemRarity;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Draws the Item Rarity background behind hotbar slots. */
@Mixin(Hud.class)
public abstract class MixinGuiItemRarity {
    @Inject(method = "extractSlot", at = @At("HEAD"))
    private void lbqmod$onRenderHotbarSlot(GuiGraphicsExtractor graphics, int x, int y, DeltaTracker deltaTracker, Player player, ItemStack itemStack, int seed, CallbackInfo ci) {
        if (! ItemRarity.INSTANCE.enabled) return;
        if (ItemRarity.getDrawOnHotbar().getValue()) ItemRarity.onHotbarSlotDraw(graphics, itemStack, x, y);
    }
}
