package com.auraos.music.next

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Discord draws rich-presence text itself (its own font, its own colour; an app cannot set either), so the only way to make the
 * two lines look different is in the characters themselves: Unicode has whole alphabets of bold, italic, script, monospace and
 * so on, and an emoji such as a coloured dot in front gives a splash of colour.
 */
object DiscordLook {
    val styles = listOf("Normal", "Bold", "Italic", "Bold italic", "Monospace", "Script", "Small caps", "Wide", "UPPERCASE")
    val marks = listOf("None", "♪", "🎵", "❤️", "🔴", "🟠", "🟡", "🟢", "🔵", "🟣", "⚪")
    private val markText = listOf("", "♪ ", "🎵 ", "❤️ ", "🔴 ", "🟠 ", "🟡 ", "🟢 ", "🔵 ", "🟣 ", "⚪ ")

    fun mark(i: Int): String = markText.getOrElse(i) { "" }

    // first code point of the capital letters, lower-case letters and digits in each style (0 = leave as is)
    private class Alpha(val up: Int, val low: Int, val digit: Int)
    private val alphas = mapOf(
        1 to Alpha(0x1D5D4, 0x1D5EE, 0x1D7EC),     // sans bold
        2 to Alpha(0x1D608, 0x1D622, 0),            // sans italic
        3 to Alpha(0x1D63C, 0x1D656, 0),            // sans bold italic
        4 to Alpha(0x1D670, 0x1D68A, 0x1D7F6),      // monospace
        5 to Alpha(0x1D4D0, 0x1D4EA, 0),            // bold script
        7 to Alpha(0xFF21, 0xFF41, 0xFF10),         // full width
    )
    private const val SMALL = "ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘǫʀsᴛᴜᴠᴡxʏᴢ"

    fun style(s: String, i: Int): String {
        if (i <= 0 || s.isEmpty()) return s
        if (i == 8) return s.uppercase()
        val sb = StringBuilder(s.length * 2)
        var k = 0
        while (k < s.length) {
            val c = s.codePointAt(k); k += Character.charCount(c)
            val out: Int = when {
                i == 6 && c in 'a'.code..'z'.code -> SMALL[c - 'a'.code].code
                i == 6 && c in 'A'.code..'Z'.code -> SMALL[c - 'A'.code].code
                else -> {
                    val a = alphas[i]
                    when {
                        a == null -> c
                        c in 'A'.code..'Z'.code && a.up != 0 -> a.up + (c - 'A'.code)
                        c in 'a'.code..'z'.code && a.low != 0 -> a.low + (c - 'a'.code)
                        c in '0'.code..'9'.code && a.digit != 0 -> a.digit + (c - '0'.code)
                        else -> c
                    }
                }
            }
            sb.appendCodePoint(out)
        }
        return sb.toString()
    }

    /** a small sketch of how the two lines will read in Discord */
    @Composable
    fun Preview(look: List<Int>) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF1E1F22)).padding(14.dp)) {
            T("Listening to Strata Music", 12.sp, W650, color = Color(0xFFB5BAC1))
            Spacer(Modifier.size(6.dp))
            T(mark(look[2]) + style("Song Title", look[0]), 15.sp, W700, color = Color.White, lines = 1)
            T(mark(look[3]) + style("Artist Name", look[1]), 13.sp, color = Color(0xFFDBDEE1), lines = 1)
        }
    }
}
