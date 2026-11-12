package com.laybalt.mixin;

//#if CHEAT

import com.laybalt.features.impl.floor7.M7Relics;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Map;

/**
 * M7 Relics "Full Block Anvil" (NoammAddons 936b52c2, a096a6ee): during P5 the anvil's outline and hit shape is a full
 * block, while its collision shape stays the vanilla one, so movement is unchanged.
 */
@Mixin(value = AnvilBlock.class, priority = 1500)
abstract class MixinAnvilBlock extends FallingBlock {
    @Shadow @Final private static Map<Direction.Axis, VoxelShape> SHAPES;

    protected MixinAnvilBlock(Properties properties) {
        super(properties);
    }

    @WrapMethod(method = "getShape")
    private VoxelShape laybalt$fullBlockAnvilShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context, Operation<VoxelShape> original) {
        return M7Relics.isFullBlockAnvil() ? Shapes.block() : original.call(state, level, pos, context);
    }

    @Override
    protected @NotNull VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES.get(state.getValue(AnvilBlock.FACING).getAxis());
    }
}
//#endif
