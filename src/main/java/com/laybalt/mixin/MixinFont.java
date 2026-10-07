package com.laybalt.mixin;

import com.laybalt.LayBaltQOL;
import com.laybalt.features.impl.dev.Cosmetics;
import com.laybalt.features.impl.dev.text.TextReplacer;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(Font.class)
public class MixinFont {
    @Unique
    private static boolean lbqmod$shouldReplace() {
        return LayBaltQOL.isLoaded && Cosmetics.INSTANCE.enabled && Cosmetics.getCustomNames().getValue()
            && TextReplacer.ready && ! TextReplacer.drawingTooltip;
    }

    @ModifyVariable(method = "prepareText(Lnet/minecraft/util/FormattedCharSequence;FFIZZI)Lnet/minecraft/client/gui/Font$PreparedText;", at = @At("HEAD"), argsOnly = true)
    private FormattedCharSequence onDrawSequence(FormattedCharSequence text) {
        if (! lbqmod$shouldReplace()) return text;
        return TextReplacer.INSTANCE.fn(text);
    }

    @ModifyVariable(method = "width(Lnet/minecraft/util/FormattedCharSequence;)I", at = @At("HEAD"), argsOnly = true)
    private FormattedCharSequence onWidthSequence(FormattedCharSequence text) {
        if (! lbqmod$shouldReplace()) return text;
        return TextReplacer.INSTANCE.fn(text);
    }

    @ModifyVariable(method = "width(Lnet/minecraft/network/chat/FormattedText;)I", at = @At("HEAD"), argsOnly = true)
    private FormattedText onWidthSequence(FormattedText text) {
        if (! lbqmod$shouldReplace()) return text;
        if (! (text instanceof Component)) return text;
        return TextReplacer.INSTANCE.fn((Component) text);
    }

    @ModifyVariable(method = "width(Ljava/lang/String;)I", at = @At("HEAD"), argsOnly = true)
    private String onWidthSequence(String str) {
        if (! lbqmod$shouldReplace()) return str;
        return TextReplacer.INSTANCE.fn(str);
    }
}