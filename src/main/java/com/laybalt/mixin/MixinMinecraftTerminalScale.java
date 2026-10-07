// Ported from Odin (https://github.com/odtheking/Odin), BSD-3-Clause, Copyright (c) 2025 odtheking
package com.laybalt.mixin;

import com.laybalt.features.impl.floor7.terminals.TerminalSolver;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Terminal Solver "Vanilla Term Size": forces a GUI scale while a terminal is open in a vanilla render mode.
 * Ported from Odin (https://github.com/odtheking/Odin), BSD-3-Clause, Copyright (c) 2025 odtheking
 */
@Mixin(Minecraft.class)
public abstract class MixinMinecraftTerminalScale {
    @ModifyExpressionValue(method = "resizeGui", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/OptionInstance;get()Ljava/lang/Object;"))
    private Object lbqmod$terminalGuiScale(Object original) {
        int override = TerminalSolver.guiScaleOverride();
        return override < 0 || !(original instanceof Integer) ? original : (Object) override;
    }
}
