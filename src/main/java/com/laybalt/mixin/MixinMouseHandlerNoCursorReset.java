// Adapted from Odin (https://github.com/odtheking/Odin), BSD-3-Clause, Copyright (c) 2025 odtheking
package com.laybalt.mixin;

import com.laybalt.features.impl.misc.NoCursorReset;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.MouseHandler;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** No Cursor Reset: remembers the cursor before the mouse is grabbed and puts it back when the next menu releases it. */
@Mixin(MouseHandler.class)
public abstract class MixinMouseHandlerNoCursorReset {
    @Shadow private double xpos;
    @Shadow private double ypos;

    @Unique private double lbqmod$beforeX;
    @Unique private double lbqmod$beforeY;
    @Unique private boolean lbqmod$hasBefore;

    @Inject(method = "grabMouse", at = @At(value = "FIELD", target = "Lnet/minecraft/client/MouseHandler;mouseGrabbed:Z", opcode = Opcodes.PUTFIELD))
    private void lbqmod$saveCursor(CallbackInfo ci) {
        this.lbqmod$beforeX = this.xpos;
        this.lbqmod$beforeY = this.ypos;
        this.lbqmod$hasBefore = true;
    }

    @WrapOperation(method = "releaseMouse", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/InputConstants;grabOrReleaseMouse(Lcom/mojang/blaze3d/platform/Window;IDD)V"))
    private void lbqmod$restoreCursor(Window window, int mode, double x, double y, Operation<Void> original) {
        if (this.lbqmod$hasBefore && NoCursorReset.shouldRestoreCursor()) {
            this.xpos = this.lbqmod$beforeX;
            this.ypos = this.lbqmod$beforeY;
            original.call(window, mode, this.lbqmod$beforeX, this.lbqmod$beforeY);
            return;
        }
        original.call(window, mode, x, y);
    }
}
