package com.laybalt.ui.render

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier

/**
 * Draws vanilla item textures straight from their PNG (e.g. `textures/item/nether_star.png`) instead of rendering an
 * ItemStack. Works before a world is loaded (item components are not bound on the title screen, so no ItemStack can be
 * built there) and always looks the same, which suits flat GUI icons and HUD editor previews.
 */
object ItemTextures {
    private val cache = HashMap<String, Identifier>()

    /** `minecraft:textures/item/<name>.png` (cached). */
    fun item(name: String): Identifier = cache.getOrPut(name) { Identifier.withDefaultNamespace("textures/item/$name.png") }

    /** Draws the whole 16x16 [texture] at ([x], [y]) scaled to [size] GUI units. */
    fun draw(ctx: GuiGraphicsExtractor, texture: Identifier, x: Float, y: Float, size: Float = 16f, color: Int = -1) =
        drawRegion(ctx, texture, x, y, size, 0f, 0f, 16, 16, 16, 16, color)

    /** Draws the [regionW] x [regionH] region at ([u], [v]) of a [texW] x [texH] texture as a [size] x [size] square. */
    fun drawRegion(
        ctx: GuiGraphicsExtractor, texture: Identifier, x: Float, y: Float, size: Float,
        u: Float, v: Float, regionW: Int, regionH: Int, texW: Int, texH: Int, color: Int = -1
    ) {
        val pose = ctx.pose()
        pose.pushMatrix()
        pose.translate(x, y)
        val s = size / 16f
        if (s != 1f) pose.scale(s, s)
        ctx.blit(RenderPipelines.GUI_TEXTURED, texture, 0, 0, u, v, 16, 16, regionW, regionH, texW, texH, color)
        pose.popMatrix()
    }
}
