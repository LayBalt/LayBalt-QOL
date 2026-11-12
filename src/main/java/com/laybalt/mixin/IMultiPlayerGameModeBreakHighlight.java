package com.laybalt.mixin;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Break Highlight: smooth local mining progress (0..1) and the block being mined. */
@Mixin(MultiPlayerGameMode.class)
public interface IMultiPlayerGameModeBreakHighlight {
    @Accessor("destroyProgress")
    float lbqmod$getDestroyProgress();

    @Accessor("destroyBlockPos")
    BlockPos lbqmod$getDestroyBlockPos();
}
