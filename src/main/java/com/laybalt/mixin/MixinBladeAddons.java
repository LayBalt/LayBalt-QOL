package com.laybalt.mixin;

import com.laybalt.features.impl.floor7.F7Titles;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * NoammAddons fd7ae535: BladeAddons' Section.parseMessage re-forces Hypixel's terminal/device/lever title and subtitle
 * (https://github.com/BladeMasterGabe/blade-addons/blob/ca40d031a42ca18a7f095b48187580f06586e467/src/main/java/blade/addon/utils/dungeon/Section.java#L181),
 * which undoes the F7 Titles replacement. require = 0 keeps this a no-op when BladeAddons is absent or changed.
 */
@Pseudo
@Mixin(targets = "blade.addon.utils.dungeon.Section", remap = false)
public class MixinBladeAddons {
    @Dynamic
    @WrapWithCondition(method = "parseMessage", at = @At(value = "INVOKE", target = "Lblade/addon/utils/Misc;forceTitle(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/Component;)V"), require = 0)
    private static boolean allowTerminalTitle(Component title, Component subtitle) {
        return ! F7Titles.INSTANCE.enabled || ! F7Titles.terminalTitlesEnabled();
    }
}
