package com.laybalt.mixin;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.component.CustomData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * NoammAddons ce2d84dd: ItemUtils.customData reads the tag without copying it. The tag is the item's own: read it only,
 * never change it.
 */
@Mixin(CustomData.class)
public interface ICustomData {
    @Accessor("tag")
    CompoundTag getTagDirect();
}
