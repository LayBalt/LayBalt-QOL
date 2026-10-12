package com.laybalt.mixin;

//#if CHEAT

import com.laybalt.features.impl.dungeon.Secrets;
import com.laybalt.utils.dungeons.DungeonUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.AbstractSkullBlock;
import net.minecraft.world.level.block.SkullBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

@Mixin(value = SkullBlock.class, priority = 1500)
abstract class MixinSkullBlock extends AbstractSkullBlock {
    @Shadow @Final private static VoxelShape SHAPE_PIGLIN;

    @Shadow @Final private static VoxelShape SHAPE;

    public MixinSkullBlock(SkullBlock.Type type, Properties properties) {
        super(type, properties);
    }

    @WrapMethod(method = "getShape")
    private VoxelShape laybalt$secretSkullShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context, Operation<VoxelShape> original) {
        return Secrets.shouldModifySkull() && DungeonUtils.isSecret(pos) ? Shapes.block() : original.call(state, level, pos, context);
    }

    @Override
    public @NotNull VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return this.getType() == SkullBlock.Types.PIGLIN ? SHAPE_PIGLIN : SHAPE;
    }
}
//#endif