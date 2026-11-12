package com.laybalt.mixin;

import com.laybalt.features.impl.general.ItemTooltip;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import net.minecraft.resources.Identifier;
import org.joml.Matrix3x2fStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.List;

/** Applies Item Tooltip's scroll offset and scale to tooltips drawn while Scrollable Tooltips is on. */
@Mixin(GuiGraphicsExtractor.class)
public abstract class MixinGuiGraphicsExtractorItemTooltip {
    @Shadow @Final private Matrix3x2fStack pose;

    @WrapMethod(method = "tooltip")
    private void lbqmod$scrollTooltip(Font font, List<ClientTooltipComponent> lines, int xo, int yo, ClientTooltipPositioner positioner, @org.jspecify.annotations.Nullable Identifier style, Operation<Void> original) {
        if (! ItemTooltip.isScrollingEnabled()) {
            original.call(font, lines, xo, yo, positioner, style);
            return;
        }

        pose.pushMatrix();
        pose.translate(xo, yo);
        pose.scale(ItemTooltip.getTooltipScale().getValue().floatValue() / 100f + ItemTooltip.scaleOverride / 10f);
        pose.translate(ItemTooltip.scrollAmountX, ItemTooltip.scrollAmountY);
        pose.translate(- xo, - yo);
        original.call(font, lines, xo, yo, positioner, style);
        pose.popMatrix();
    }
}
