package com.laybalt.mixin;

import com.laybalt.debug.DebugHUD;
import com.laybalt.event.EventBus;
import com.laybalt.event.impl.ActionBarMessageEvent;
import com.laybalt.event.impl.RenderOverlayEvent;
import com.laybalt.features.impl.visual.DarkMode;
import com.laybalt.features.impl.visual.PlayerHud;
import com.laybalt.utils.location.LocationUtils;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Hud.class)
public abstract class MixinGui {
    @Shadow
    @Final
    private Minecraft minecraft;

    @Shadow @Nullable private Component title;
    @Shadow @Nullable private Component subtitle;

    @Inject(method = "extractArmor", at = @At("HEAD"), cancellable = true)
    private static void renderArmor(GuiGraphicsExtractor graphics, Player player, int yLineBase, int numHealthRows, int healthRowHeight, int xLeft, CallbackInfo ci) {
        if (PlayerHud.INSTANCE.enabled && PlayerHud.getHideArmorbar().getValue()) ci.cancel();
    }

    @Inject(method = "extractPlayerHealth", at = @At("HEAD"), cancellable = true)
    public void renderPlayerHealth(GuiGraphicsExtractor graphics, CallbackInfo ci) {
        if (PlayerHud.INSTANCE.enabled && PlayerHud.getHideHealthbar().getValue()) ci.cancel();
    }

    @Inject(method = "extractFood", at = @At("HEAD"), cancellable = true)
    public void renderFood(GuiGraphicsExtractor graphics, Player player, int yLineBase, int xRight, CallbackInfo ci) {
        if (PlayerHud.INSTANCE.enabled && PlayerHud.getHideFoodbar().getValue()) ci.cancel();
    }

    @Inject(method = "extractTitle", at = @At(value = "INVOKE", target = "Lorg/joml/Matrix3x2fStack;scale(FF)Lorg/joml/Matrix3x2f;", ordinal = 0, shift = At.Shift.AFTER))
    private void onScaleTitle(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        if (title == null) return;

        float maxWidth = minecraft.getWindow().getGuiScaledWidth() * 0.85f;
        float currentWidth = minecraft.font.width(title) * 4.0f;
        if (currentWidth > maxWidth) {
            float scaleFactor = maxWidth / currentWidth;
            graphics.pose().scale(scaleFactor);
        }
    }

    @Inject(method = "extractTitle", at = @At(value = "INVOKE", target = "Lorg/joml/Matrix3x2fStack;scale(FF)Lorg/joml/Matrix3x2f;", ordinal = 1, shift = At.Shift.AFTER))
    private void onScaleSubtitle(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        if (subtitle == null) return;

        float maxWidth = minecraft.getWindow().getGuiScaledWidth() * 0.85f;
        float currentWidth = minecraft.font.width(subtitle) * 2.0f;
        if (currentWidth > maxWidth) {
            float scaleFactor = maxWidth / currentWidth;
            graphics.pose().scale(scaleFactor);
        }
    }

    @Inject(method = "extractRenderState", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Hud;extractSleepOverlay(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V"))
    public void onRenderHud(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        if (((Hud) (Object) this).isHidden()) return;
        if (this.minecraft.debugEntries.isOverlayVisible()) return;
        EventBus.post(new RenderOverlayEvent(graphics));

        DebugHUD.render(graphics);
    }

    @Inject(method = "extractRenderState", at = @At(value = "HEAD"))
    public void onRenderHudPre(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        if (! DarkMode.getTintHud().getValue()) DarkMode.drawOverlay(graphics);
    }

    @Inject(method = "extractRenderState", at = @At(value = "TAIL"))
    public void onRenderHudPost(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        if (DarkMode.getTintHud().getValue()) DarkMode.drawOverlay(graphics);
    }

    @ModifyVariable(method = "setOverlayMessage", at = @At("HEAD"), argsOnly = true)
    private Component onSetOverlayMessage(Component string) {
        var event = new ActionBarMessageEvent(string);
        if (EventBus.post(event)) return Component.empty();
        return Component.literal(event.getMessage());
    }

    @Inject(method = "extractEffects", at = @At("HEAD"), cancellable = true)
    private void onRenderEffects(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        if (LocationUtils.inSkyblock) ci.cancel();
    }

}