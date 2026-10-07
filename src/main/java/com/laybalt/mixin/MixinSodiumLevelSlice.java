package com.laybalt.mixin;

//#if CHEAT

import com.laybalt.features.impl.dungeon.IHateDoors;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Sodium replaces vanilla chunk meshing with its own LevelSlice, so I Hate Doors hooks it as well.
 * Optional: @Pseudo skips the mixin when Sodium is absent, and require = 0 keeps the game loading
 * if a Sodium update renames or changes the method.
 */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.world.LevelSlice", remap = false)
public abstract class MixinSodiumLevelSlice {
    @Dynamic("Sodium LevelSlice#getBlockState(int, int, int)")
    @ModifyReturnValue(method = "getBlockState(III)Lnet/minecraft/world/level/block/state/BlockState;", at = @At("RETURN"), require = 0)
    private BlockState replaceDoorRenderState(BlockState original, int x, int y, int z) {
        return IHateDoors.getRenderState(x, y, z, original);
    }
}
//#endif
