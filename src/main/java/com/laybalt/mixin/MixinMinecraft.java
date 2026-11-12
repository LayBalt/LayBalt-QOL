package com.laybalt.mixin;

import com.laybalt.event.EventBus;
import com.laybalt.event.impl.CheckEntityGlowEvent;
import com.laybalt.event.impl.PlayerInteractEvent;
import com.laybalt.features.impl.visual.InfoDisplay;
import com.laybalt.interfaces.IGlowingEntity;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.authlib.minecraft.UserApiService;
import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.main.GameConfig;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

//#if LEGIT
//$import com.laybalt.utils.render.LegitEntityVisibility;
//#endif

@Mixin(Minecraft.class)
public abstract class MixinMinecraft {
    @Shadow @Nullable public HitResult hitResult;
    @Shadow public LocalPlayer player;
    @Shadow @Nullable public ClientLevel level;
    @Shadow @Final private User user;
    @Shadow public abstract User getUser();
    @Inject(method = "startAttack", at = @At("HEAD"))
    private void onStartAttack(CallbackInfoReturnable<Boolean> cir) {
        InfoDisplay.addLeftClick();
        //#if CHEAT
        com.laybalt.features.impl.fishing.FishingRecorder.onClick(true);
        //#endif
    }

    @Inject(
        method = "handleKeybinds",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/Minecraft;startUseItem()V",
            ordinal = 0
        )
    )
    private void onUseClick(CallbackInfo ci) {
        InfoDisplay.addRightClick();
        //#if CHEAT
        com.laybalt.features.impl.fishing.FishingRecorder.onClick(false);
        //#endif
    }

    //#if CHEAT
    /** The use repeat while the use key is held (every 4 ticks): held abilities are recorded as clicks too. */
    @Inject(
        method = "handleKeybinds",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/Minecraft;startUseItem()V",
            ordinal = 1
        )
    )
    private void onUseHeld(CallbackInfo ci) {
        com.laybalt.features.impl.fishing.FishingRecorder.onHeldUse();
    }
    //#endif

    @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
    private void preUseItem(CallbackInfo ci) {
        handleHitResult(ci, false);
    }

    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void preAttack(CallbackInfoReturnable<Boolean> cir) {
        handleHitResult(cir, true);
    }

    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    private void preWhileAttack(boolean down, CallbackInfo ci) {
        if (! down) return;
        handleHitResult(ci, true);
    }

    @Unique
    private void handleHitResult(CallbackInfo ci, boolean isLeftClick) {
        if (this.player == null || this.level == null) return;
        ItemStack itemStack = player.getMainHandItem();

        PlayerInteractEvent event;

        if (this.hitResult == null || this.hitResult.getType() == HitResult.Type.MISS) {
            event = isLeftClick
                ? new PlayerInteractEvent.LEFT_CLICK.AIR(itemStack)
                : new PlayerInteractEvent.RIGHT_CLICK.AIR(itemStack);
        } else {
            event = switch (this.hitResult.getType()) {
                case ENTITY -> {
                    Entity entity = ((EntityHitResult) this.hitResult).getEntity();
                    yield isLeftClick
                        ? new PlayerInteractEvent.LEFT_CLICK.ENTITY(itemStack, entity)
                        : new PlayerInteractEvent.RIGHT_CLICK.ENTITY(itemStack, entity);
                }
                case BLOCK -> {
                    BlockPos pos = ((BlockHitResult) this.hitResult).getBlockPos();
                    yield isLeftClick
                        ? new PlayerInteractEvent.LEFT_CLICK.BLOCK(itemStack, pos)
                        : new PlayerInteractEvent.RIGHT_CLICK.BLOCK(itemStack, pos);
                }
                default -> isLeftClick
                    ? new PlayerInteractEvent.LEFT_CLICK.AIR(itemStack)
                    : new PlayerInteractEvent.RIGHT_CLICK.AIR(itemStack);
            };
        }

        if (EventBus.post(event)) ci.cancel();
    }

    // Apply our glow after other mods have changed the vanilla glow state
    @ModifyExpressionValue(
        method = "shouldEntityAppearGlowing",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;isCurrentlyGlowing()Z")
    )
    private boolean onShouldEntityAppearGlowing(boolean original, Entity entity) {
        //#if LEGIT
        //$ if (this.player ==null) return original;
        //$ if (!LegitEntityVisibility.isVisible(this.player, entity)) {
        //$ ((IGlowingEntity) entity).lbqmod$isGlowing(false);
        //$ ((IGlowingEntity) entity).lbqmod$glowCheckTick(-1);
        //$ return original;
        //$}
        //#endif

        var glow = (IGlowingEntity) entity;

        // NoammAddons 3549d1fc: post the glow check once per entity per tick (this runs every frame for every
        // rendered entity) and reuse its result for the other frames of the tick.
        if (this.player == null || glow.lbqmod$glowCheckTick() != this.player.tickCount) {
            var event = new CheckEntityGlowEvent(entity);
            var cancelled = EventBus.post(event);

            if (! cancelled) {
                glow.lbqmod$isGlowing(event.getShouldGlow());
                glow.lbqmod$glowColor(event.getColor());
            }

            glow.lbqmod$glowCancelled(cancelled);
            if (this.player != null) glow.lbqmod$glowCheckTick(this.player.tickCount);
        }

        if (glow.lbqmod$glowCancelled()) return false;
        return original || glow.lbqmod$isGlowing();
    }

    @SuppressWarnings("ConstantValue")
    @WrapOperation(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;createUserApiService(Lcom/mojang/authlib/yggdrasil/YggdrasilAuthenticationService;Lnet/minecraft/client/main/GameConfig;)Lcom/mojang/authlib/minecraft/UserApiService;"))
    private UserApiService onCreateUserApiService(YggdrasilAuthenticationService authService, GameConfig config, Operation<UserApiService> original) {
        String token = user.getAccessToken();
        if (token == null || token.equals("0") || token.equals("FabricMC")) return original.call(authService, config);
        return authService.createUserApiService(token);
    }
}