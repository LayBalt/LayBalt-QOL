package com.laybalt.mixin;

//#if CHEAT

import com.laybalt.features.impl.dungeon.Secrets;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.AbstractChestBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Secrets "Chests" hitbox: full block outline / ray-trace shape for (trapped) chests in dungeons.
 * Collision is kept vanilla by overriding getCollisionShape with the original chest shapes.
 */
@Mixin(value = ChestBlock.class, priority = 1500)
abstract class MixinChestBlock extends AbstractChestBlock<ChestBlockEntity> {
    @Shadow @Final private static VoxelShape SHAPE;

    @Shadow @Final private static Map<Direction, VoxelShape> HALF_SHAPES;

    protected MixinChestBlock(Properties properties, Supplier<BlockEntityType<? extends ChestBlockEntity>> supplier) {
        super(properties, supplier);
    }

    @WrapMethod(method = "getShape")
    private VoxelShape laybalt$expandChestShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context, Operation<VoxelShape> original) {
        return Secrets.shouldExpandChest(state) ? Shapes.block() : original.call(state, level, pos, context);
    }

    @Override
    protected @NotNull VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return state.getValue(ChestBlock.TYPE) == ChestType.SINGLE ? SHAPE : HALF_SHAPES.get(ChestBlock.getConnectedDirection(state));
    }
}
//#endif
