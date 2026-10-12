package com.laybalt.mixin;

//#if CHEAT

import com.laybalt.features.impl.dungeon.Secrets;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;

/** Secrets "Button" hitbox, wraps getShape (see {@link MixinLeverBlock}). */
@Mixin(value = ButtonBlock.class, priority = 1500)
public class MixinButtonBlock {
    @WrapMethod(method = "getShape")
    private VoxelShape laybalt$secretButtonShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context, Operation<VoxelShape> original) {
        return Secrets.shouldModifyButton() ? Secrets.getButtonShape(state) : original.call(state, level, pos, context);
    }
}
//#endif
