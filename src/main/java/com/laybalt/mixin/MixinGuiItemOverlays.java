package com.laybalt.mixin;

import com.laybalt.features.impl.general.ItemOverlays;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Draws the Item Overlays usage bars (fuel, durability, charges) on top of hotbar slots. */
@Mixin(Hud.class)
public abstract class MixinGuiItemOverlays {
    @Inject(method = "extractSlot", at = @At("TAIL"))
    private void lbqmod$onRenderHotbarSlotPost(GuiGraphicsExtractor graphics, int x, int y, DeltaTracker deltaTracker, Player player, ItemStack itemStack, int seed, CallbackInfo ci) {
        if (! ItemOverlays.INSTANCE.enabled) return;
        ItemOverlays.onHotbarSlotDraw(graphics, itemStack, x, y);
    }
}
