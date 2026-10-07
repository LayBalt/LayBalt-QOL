package com.laybalt.mixin;

//#if CHEAT

import com.laybalt.features.impl.dungeon.IHateDoors;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Vanilla chunk meshing: lets I Hate Doors swap dungeon door blocks for glass in the rendered mesh only. */
@Mixin(RenderSectionRegion.class)
public abstract class MixinRenderSectionRegion {
    @ModifyReturnValue(method = "getBlockState", at = @At("RETURN"))
    private BlockState replaceDoorRenderState(BlockState original, BlockPos pos) {
        return IHateDoors.getRenderState(pos.getX(), pos.getY(), pos.getZ(), original);
    }
}
//#endif
