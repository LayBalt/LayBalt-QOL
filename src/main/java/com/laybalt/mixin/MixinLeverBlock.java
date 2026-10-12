package com.laybalt.mixin;

//#if CHEAT

import com.laybalt.features.impl.dungeon.Secrets;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Secrets "Lever" hitbox. The secret block mixins wrap the whole getShape (@WrapMethod), so the shape is replaced even
 * when another mod HEAD-cancels getShape (a HEAD-cancel ends inside the wrapped original, which our wrapper never calls
 * while the Secrets shape applies). Priority 1500 so the wrapper sits outside other mods' default-priority hooks.
 */
@Mixin(value = LeverBlock.class, priority = 1500)
public class MixinLeverBlock {
    @WrapMethod(method = "getShape")
    private VoxelShape laybalt$secretLeverShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context, Operation<VoxelShape> original) {
        return Secrets.isValidLever(pos) ? Secrets.getLeverShape(state) : original.call(state, level, pos, context);
    }
}
//#endif
