package com.laybalt.ui.text

import com.laybalt.LayBaltQOL
import com.laybalt.LayBaltQOL.MOD_ID
import com.laybalt.LayBaltQOL.mc
import com.laybalt.init.types.ISelfInit
import com.laybalt.mixin.IFontManager
import com.laybalt.mixin.IMinecraft
import com.mojang.blaze3d.font.GlyphProvider
import com.mojang.blaze3d.font.TrueTypeGlyphProvider
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.font.FontOption
import net.minecraft.client.gui.font.FontSet
import net.minecraft.client.gui.font.GlyphStitcher
import net.minecraft.client.gui.font.providers.FreeTypeUtil
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FontDescription
import net.minecraft.network.chat.Style
import net.minecraft.resources.Identifier
import org.joml.Matrix3x2f
import org.lwjgl.util.freetype.FT_Face
import org.lwjgl.util.freetype.FT_MM_Var
import org.lwjgl.util.freetype.FreeType
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import java.awt.Color
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Inter text for LayBalt screens (main window, settings panel, tooltips, HUD editor, notifications, sub-screens).
 *
 * Inter (SIL OFL 1.1) ships as `assets/lbqmod/font/inter.ttf`; `assets/lbqmod/font/inter.json` is a plain TTF
 * provider (`lbqmod:inter`, size 8) that works anywhere a [Style] font is accepted.
 *
 * The click GUI is drawn on a scaled virtual canvas, so a font rasterised once at a fixed oversample would be
 * resampled (Minecraft samples font atlases with NEAREST filtering) and look grainy or blocky. Instead every
 * (size, weight, on-screen pixel scale) combination gets its own font set, rasterised by FreeType at exactly the
 * physical pixel size it is drawn at, with the variable font's `wght`/`opsz` axes set for real weights. Those
 * font sets live in the vanilla FontManager map under `lbqmod:inter_dyn/...` ids, are rebuilt after a resource
 * reload and evicted when unused.
 *
 * Coordinates: [draw] takes the top-left of a line box [lineHeight] tall; the cap height is centred in that box,
 * so text drawn at `rowY + (rowH - lineHeight(size)) / 2` is optically centred in a row.
 */
object UIFont: ISelfInit {
    enum class Weight(val wght: Float) {
        REGULAR(400f), MEDIUM(500f), SEMIBOLD(600f), BOLD(700f)
    }

    /** Body text size in GUI units (vanilla text is ~7 units cap-to-baseline at size 9 line height). */
    const val DEFAULT_SIZE = 8f

    /** Static Inter font from `inter.json` (size 8, oversample 4); usable in any [Component] style. */
    @JvmField val STATIC: FontDescription = FontDescription.Resource(Identifier.fromNamespaceAndPath(MOD_ID, "inter"))
    private const val STATIC_SIZE = 8f

    private val TTF_FILE = Identifier.fromNamespaceAndPath(MOD_ID, "font/inter.ttf")
    private const val CAP_HEIGHT = 1490f / 2048f
    private const val LINE_FACTOR = 1.25f
    /** Minecraft puts the TTF baseline 7 units below the draw origin. */
    private const val MC_BASELINE = 7f
    private const val MAX_IDLE_MS = 15_000L
    private const val KEEP_ALIVE = 8

    private const val TAG_WGHT = 0x77676874L // 'wght'
    private const val TAG_OPSZ = 0x6F70737AL // 'opsz'

    private data class Key(val sizeQ: Int, val weight: Weight, val scaleQ: Int)

    private class Variant(val id: Identifier, val set: FontSet, val provider: GlyphProvider) {
        val style: Style = Style.EMPTY.withFont(FontDescription.Resource(id))
        var lastUsed = System.currentTimeMillis()
    }

    private val variants = HashMap<Key, Variant>()
    private var ttfBytes: ByteArray? = null
    private var skipList: String? = null
    private var counter = 0
    private var broken = false
    private var lastScale = 2f

    override fun init() {
        ClientTickEvents.END_CLIENT_TICK.register { evictIdle() }
    }

    fun lineHeight(size: Float = DEFAULT_SIZE) = size * LINE_FACTOR

    /** Distance from the top of the line box to the baseline. */
    fun baseline(size: Float = DEFAULT_SIZE) = (lineHeight(size) + CAP_HEIGHT * size) / 2f

    fun capHeight(size: Float = DEFAULT_SIZE) = CAP_HEIGHT * size

    fun draw(ctx: GuiGraphicsExtractor, text: String, x: Number, y: Number, color: Color, size: Float = DEFAULT_SIZE, weight: Weight = Weight.REGULAR, shadow: Boolean = false) {
        if (text.isEmpty() || color.alpha <= 3) return
        val pose = ctx.pose()
        val scale = physicalScale(pose)
        lastScale = scale
        val variant = variant(size, weight, scale)

        val fontScale = if (variant == null) size / STATIC_SIZE else 1f
        val originX = x.toFloat()
        val originY = y.toFloat() + baseline(size) - MC_BASELINE * fontScale

        pose.pushMatrix()
        // Snap the baseline to the physical pixel grid so glyph texels map 1:1 onto screen pixels.
        val snapped = snap(pose, originX, originY + MC_BASELINE * fontScale)
        pose.translate(snapped.first, snapped.second - MC_BASELINE * fontScale)
        if (variant == null) {
            pose.scale(fontScale, fontScale)
            ctx.text(mc.font, Component.literal(text).withStyle(Style.EMPTY.withFont(STATIC)), 0, 0, color.rgb, shadow)
        }
        else ctx.text(mc.font, Component.literal(text).withStyle(variant.style), 0, 0, color.rgb, shadow)
        pose.popMatrix()
    }

    fun drawCentered(ctx: GuiGraphicsExtractor, text: String, centerX: Number, y: Number, color: Color, size: Float = DEFAULT_SIZE, weight: Weight = Weight.REGULAR, shadow: Boolean = false) =
        draw(ctx, text, centerX.toFloat() - width(text, size, weight) / 2f, y, color, size, weight, shadow)

    fun drawRight(ctx: GuiGraphicsExtractor, text: String, rightX: Number, y: Number, color: Color, size: Float = DEFAULT_SIZE, weight: Weight = Weight.REGULAR, shadow: Boolean = false) =
        draw(ctx, text, rightX.toFloat() - width(text, size, weight), y, color, size, weight, shadow)

    /** Draws [text] with its line box vertically centred on [centerY]. */
    fun drawMiddle(ctx: GuiGraphicsExtractor, text: String, x: Number, centerY: Number, color: Color, size: Float = DEFAULT_SIZE, weight: Weight = Weight.REGULAR, shadow: Boolean = false) =
        draw(ctx, text, x, centerY.toFloat() - lineHeight(size) / 2f, color, size, weight, shadow)

    /** Draws [text] cut to [maxWidth] with an ellipsis. */
    fun drawTrimmed(ctx: GuiGraphicsExtractor, text: String, x: Number, y: Number, maxWidth: Float, color: Color, size: Float = DEFAULT_SIZE, weight: Weight = Weight.REGULAR) =
        draw(ctx, trim(text, maxWidth, size, weight), x, y, color, size, weight)

    /** Draws wrapped lines (at most [maxLines], the last one ellipsised) and returns the height used. */
    fun drawWrapped(ctx: GuiGraphicsExtractor, text: String, x: Number, y: Number, maxWidth: Float, color: Color, size: Float = DEFAULT_SIZE, weight: Weight = Weight.REGULAR, maxLines: Int = Int.MAX_VALUE, lineSpacing: Float = 0f): Float {
        val lines = wrap(text, maxWidth, size, weight, maxLines)
        val step = lineHeight(size) + lineSpacing
        lines.forEachIndexed { i, line -> draw(ctx, line, x, y.toFloat() + i * step, color, size, weight) }
        return lines.size * step - if (lines.isEmpty()) 0f else lineSpacing
    }

    /** Text width in GUI units (measured with the font set of the most recently drawn pixel scale). */
    fun width(text: String, size: Float = DEFAULT_SIZE, weight: Weight = Weight.REGULAR): Float {
        if (text.isEmpty()) return 0f
        val variant = variant(size, weight, lastScale)
        return if (variant == null) mc.font.splitter.stringWidth(Component.literal(text).withStyle(Style.EMPTY.withFont(STATIC))) * size / STATIC_SIZE
        else mc.font.splitter.stringWidth(Component.literal(text).withStyle(variant.style))
    }

    /** Cuts [text] so it fits [maxWidth], appending "…" when anything was removed. */
    fun trim(text: String, maxWidth: Float, size: Float = DEFAULT_SIZE, weight: Weight = Weight.REGULAR, ellipsis: String = "…"): String {
        if (width(text, size, weight) <= maxWidth) return text
        val budget = maxWidth - width(ellipsis, size, weight)
        if (budget <= 0f) return ""
        var lo = 0
        var hi = text.length
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (width(text.substring(0, mid), size, weight) <= budget) lo = mid else hi = mid - 1
        }
        return text.substring(0, lo).trimEnd() + ellipsis
    }

    /** Word-wraps [text] (honouring '\n') into lines no wider than [maxWidth]; the last allowed line is ellipsised. */
    fun wrap(text: String, maxWidth: Float, size: Float = DEFAULT_SIZE, weight: Weight = Weight.REGULAR, maxLines: Int = Int.MAX_VALUE): List<String> {
        val out = ArrayList<String>()
        for (paragraph in text.split('\n')) {
            var line = ""
            for (word in paragraph.split(' ')) {
                val candidate = if (line.isEmpty()) word else "$line $word"
                if (line.isNotEmpty() && width(candidate, size, weight) > maxWidth) {
                    out.add(line)
                    line = word
                }
                else line = candidate
            }
            out.add(line)
        }
        if (out.size <= maxLines) return out
        val kept = out.subList(0, maxLines).toMutableList()
        kept[maxLines - 1] = trim(kept[maxLines - 1] + " " + out[maxLines], maxWidth, size, weight)
        return kept
    }

    /** A literal component styled with the static Inter font (for widgets that only accept components). */
    fun component(text: String): Component = Component.literal(text).withStyle(Style.EMPTY.withFont(STATIC))

    // ------------------------------------------------------------------------------------------------------------

    private fun physicalScale(pose: Matrix3x2f): Float {
        val s = hypot(pose.m00(), pose.m01()) * mc.window.guiScale
        return if (s.isFinite() && s > 0.05f) s else 1f
    }

    /** Rounds a local point so it lands on a whole physical pixel (only for unrotated poses). */
    private fun snap(pose: Matrix3x2f, x: Float, y: Float): Pair<Float, Float> {
        if (pose.m01() != 0f || pose.m10() != 0f || pose.m00() == 0f || pose.m11() == 0f) return x to y
        val gs = mc.window.guiScale.toFloat()
        val px = Math.round((pose.m00() * x + pose.m20()) * gs) / gs
        val py = Math.round((pose.m11() * y + pose.m21()) * gs) / gs
        return (px - pose.m20()) / pose.m00() to (py - pose.m21()) / pose.m11()
    }

    @Suppress("UNCHECKED_CAST")
    private fun fontSets(): MutableMap<Identifier, FontSet> = ((mc as IMinecraft).fontManager as IFontManager).fontSets as MutableMap<Identifier, FontSet>

    private fun variant(size: Float, weight: Weight, scale: Float): Variant? {
        if (broken) return null
        val key = Key((size * 4f).roundToInt().coerceAtLeast(4), weight, (scale.coerceIn(0.5f, 8f) * 100f).roundToInt())
        val sets = fontSets()
        variants[key]?.let { existing ->
            if (sets[existing.id] === existing.set) {
                existing.lastUsed = System.currentTimeMillis()
                return existing
            }
            // A resource reload cleared (and closed) every font set; drop our provider and build a new one.
            variants.remove(key)
            runCatching { existing.provider.close() }
        }

        val created = runCatching { create(key, sets) }.onFailure {
            LayBaltQOL.logger.error("[UIFont] Failed to build Inter font, falling back to the static font", it)
            broken = true
        }.getOrNull() ?: return null
        variants[key] = created
        return created
    }

    private fun create(key: Key, sets: MutableMap<Identifier, FontSet>): Variant {
        val size = key.sizeQ / 4f
        val oversample = key.scaleQ / 100f
        val bytes = ttfBytes ?: mc.resourceManager.open(TTF_FILE).use { it.readAllBytes() }.also { ttfBytes = it }
        val buffer = MemoryUtil.memAlloc(bytes.size).put(bytes).flip()
        var face: FT_Face? = null
        val provider = try {
            synchronized(FreeTypeUtil.LIBRARY_LOCK) {
                MemoryStack.stackPush().use { stack ->
                    val ptr = stack.mallocPointer(1)
                    FreeTypeUtil.assertError(FreeType.FT_New_Memory_Face(FreeTypeUtil.getLibrary(), buffer, 0L, ptr), "Initializing Inter font face")
                    face = FT_Face.create(ptr.get(0))
                }
                val f = face!!
                FreeTypeUtil.assertError(FreeType.FT_Select_Charmap(f, FreeType.FT_ENCODING_UNICODE), "Find unicode charmap")
                setAxes(f, key.weight.wght, (size * 1.5f).coerceIn(14f, 32f))
                TrueTypeGlyphProvider(buffer, f, size, oversample, 0f, 0f, skipList ?: buildSkipList(f).also { skipList = it })
            }
        }
        catch (e: Throwable) {
            face?.let { runCatching { FreeType.FT_Done_Face(it) } }
            MemoryUtil.memFree(buffer)
            throw e
        }

        val id = Identifier.fromNamespaceAndPath(MOD_ID, "inter_dyn/${key.weight.name.lowercase()}_${key.sizeQ}_${key.scaleQ}_${counter++}")
        val set = FontSet(GlyphStitcher(mc.textureManager, id))
        try {
            set.reload(listOf(GlyphProvider.Conditional(provider, FontOption.Filter.ALWAYS_PASS)), emptySet())
        }
        catch (e: Throwable) {
            runCatching { set.close() }
            runCatching { provider.close() }
            throw e
        }
        sets[id] = set
        return Variant(id, set, provider)
    }

    /** Sets the variable font's weight and optical size axes (no-op for static fonts). */
    private fun setAxes(face: FT_Face, wght: Float, opsz: Float) = MemoryStack.stackPush().use { stack ->
        val ptr = stack.mallocPointer(1)
        if (FreeType.FT_Get_MM_Var(face, ptr) != 0) return@use
        val mm = FT_MM_Var.create(ptr.get(0))
        try {
            val count = mm.num_axis()
            val axes = mm.axis()
            val coords = stack.mallocCLong(count)
            for (i in 0 until count) {
                val axis = axes.get(i)
                val wanted = when (axis.tag()) {
                    TAG_WGHT -> wght
                    TAG_OPSZ -> opsz
                    else -> axis.def() / 65536f
                }
                val clamped = wanted.coerceIn(axis.minimum() / 65536f, axis.maximum() / 65536f)
                coords.put(i, (clamped * 65536f).toLong())
            }
            FreeType.FT_Set_Var_Design_Coordinates(face, coords)
        }
        finally {
            FreeType.FT_Done_MM_Var(FreeTypeUtil.getLibrary(), mm)
        }
    }

    /**
     * Codepoints we do not load (Inter covers ~2.5k; FontSet.reload measures every glyph it is given). Keeps Latin,
     * Latin-1/Extended-A/B, IPA/spacing modifiers, Greek, Cyrillic, punctuation, currency, arrows, maths and shapes.
     */
    private fun buildSkipList(face: FT_Face): String = MemoryStack.stackPush().use { stack ->
        val index = stack.mallocInt(1)
        val skip = StringBuilder()
        var code = FreeType.FT_Get_First_Char(face, index)
        while (index.get(0) != 0) {
            val cp = code.toInt()
            val keep = cp <= 0x2FF || cp in 0x370..0x52F || cp in 0x2000..0x2BFF
            if (!keep) skip.appendCodePoint(cp)
            code = FreeType.FT_Get_Next_Char(face, code, index)
        }
        skip.toString()
    }

    private fun evictIdle() {
        if (variants.isEmpty()) return
        val sets = fontSets()
        val now = System.currentTimeMillis()
        val iterator = variants.entries.iterator()
        var alive = variants.size
        while (iterator.hasNext()) {
            val v = iterator.next().value
            val reloaded = sets[v.id] !== v.set
            if (reloaded || (alive > KEEP_ALIVE && now - v.lastUsed > MAX_IDLE_MS)) {
                iterator.remove()
                alive--
                if (!reloaded) {
                    sets.remove(v.id)
                    runCatching { v.set.close() }
                }
                runCatching { v.provider.close() }
            }
        }
    }
}
