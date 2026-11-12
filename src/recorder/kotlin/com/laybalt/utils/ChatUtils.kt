package com.laybalt.utils

import com.laybalt.LayBaltQOL
import com.laybalt.LayBaltQOL.mc
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor
import java.util.Optional

/**
 * The recorder-only jar's chat helpers: LayBalt QOL's text helpers (same code, so recordings read text the same way)
 * and client-side chat output. Messages shown here never reach the server, and are not recorded as chat lines.
 */
object ChatUtils {
    fun String.removeFormatting(): String {
        if (isEmpty()) return this

        val len = length
        val out = CharArray(len)
        var outPos = 0
        var i = 0

        while (i < len) {
            val c = this[i]

            if ((c == '§' || c == '&') && i + 1 < len) {
                val next = this[i + 1]

                if ((next == 'x' || next == 'X') && i + 7 < len) {
                    val end = i + 7
                    var k = i + 2
                    while (k <= end) {
                        val h = this[k]
                        if (h !in '0' .. '9' && h !in 'a' .. 'f' && h !in 'A' .. 'F') break
                        k ++
                    }
                    if (k > end) {
                        i += 8
                        continue
                    }
                }

                if (next in '0' .. '9'
                    || next in 'a' .. 'f' || next in 'A' .. 'F'
                    || next in 'k' .. 'o' || next in 'K' .. 'O'
                    || next == 'r' || next == 'R'
                ) {
                    i += 2
                    continue
                }
            }

            out[outPos ++] = c
            i ++
        }

        return if (outPos == len) this else String(out, 0, outPos)
    }

    fun modMessage(msg: Any?) = chat(LayBaltQOL.PREFIX.copy().append(Component.literal(" $msg".addColor())))

    fun chat(msg: Any?) = chat(Component.literal(msg.toString().addColor()))
    fun chat(comp: Component) = mc.execute { mc.gui.hud.chat.addClientSystemMessage(comp) }

    fun String.addColor() = replace("&", "§")

    val Component.unformattedText get() = string.removeFormatting()
    val Component.formattedText get() = formatted(this)
    private val formatted = fun(comp: Component): String {
        val sb = StringBuilder()

        comp.visit({ style, string ->
            style.color?.let { textColor ->
                val colorMatch = ChatFormatting.entries.firstOrNull {
                    TextColor.fromLegacyFormat(it)?.value == textColor.value
                }

                if (colorMatch != null) {
                    sb.append(colorMatch)
                }
            }

            if (style.isBold) sb.append(ChatFormatting.BOLD)
            if (style.isItalic) sb.append(ChatFormatting.ITALIC)
            if (style.isUnderlined) sb.append(ChatFormatting.UNDERLINE)
            if (style.isStrikethrough) sb.append(ChatFormatting.STRIKETHROUGH)
            if (style.isObfuscated) sb.append(ChatFormatting.OBFUSCATED)

            sb.append(string)

            Optional.empty<String>()
        }, Style.EMPTY)

        return sb.toString()
    }
}
