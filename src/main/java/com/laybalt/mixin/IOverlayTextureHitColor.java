package com.laybalt.mixin;

import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Hit Color: the 16x16 dynamic texture behind the entity hurt / white flash overlay. */
@Mixin(OverlayTexture.class)
public interface IOverlayTextureHitColor {
    @Accessor("texture")
    DynamicTexture lbqmod$getTexture();
}
