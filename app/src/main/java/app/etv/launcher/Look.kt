package app.etv.launcher

import android.content.Context
import android.graphics.Typeface
import android.os.Build

/** One background mood: every colour the screens need is derived from it. */
class Palette(
    val name: String,
    val bg: Int,
    val tile: Int,
    val tileStroke: Int,
    val tileInner: Int,
    val frame1: Int,
    val frame2: Int,
    val text: Int,
    val muted: Int,
    val label: Int,
    val dot: Int,
)

object Look {
    private fun c(rgb: Int): Int = 0xFF000000.toInt() or rgb

    /** Colour of the selected app and other highlights. */
    val accents: List<Pair<String, Int>> = listOf(
        "Whitewash" to c(0xEFE8DA),
        "Sand" to c(0xE8D3A8),
        "Bougainvillea" to c(0xE9A9C2),
        "Sea glass" to c(0xA6D8D2),
    )

    val palettes: List<Palette> = listOf(
        Palette(
            "Sea blue", bg = c(0x0E2238), tile = c(0x143052), tileStroke = c(0x2D4D72),
            tileInner = c(0x24446A), frame1 = c(0x1A3553), frame2 = c(0x16304C),
            text = c(0xF1ECE2), muted = c(0xA9B6C6), label = c(0xB7C2D0), dot = c(0x3E5C7E),
        ),
        Palette(
            "Night", bg = c(0x09111B), tile = c(0x101C2A), tileStroke = c(0x26384D),
            tileInner = c(0x1C2C3F), frame1 = c(0x14212F), frame2 = c(0x111D2A),
            text = c(0xE9E5DC), muted = c(0x9AA6B4), label = c(0xA9B3C0), dot = c(0x34485E),
        ),
        Palette(
            "Dusk", bg = c(0x1B1A30), tile = c(0x24233F), tileStroke = c(0x3D3B60),
            tileInner = c(0x323052), frame1 = c(0x272641), frame2 = c(0x222139),
            text = c(0xF0EBE3), muted = c(0xAEABC6), label = c(0xBCB9D0), dot = c(0x4A4870),
        ),
    )

    fun accent(p: Prefs): Int = accents[p.accent.coerceIn(0, accents.size - 1)].second
    fun palette(p: Prefs): Palette = palettes[p.palette.coerceIn(0, palettes.size - 1)]

    fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)
}

/** El Messiri (display) and Readex Pro (text): both cover Arabic and Latin. */
object Fonts {
    private val cache = HashMap<String, Typeface>()

    private fun load(context: Context, file: String, weight: Int): Typeface {
        val key = "$file:$weight"
        cache[key]?.let { return it }
        val tf: Typeface = try {
            if (Build.VERSION.SDK_INT >= 26) {
                Typeface.Builder(context.assets, "fonts/$file")
                    .setFontVariationSettings("'wght' $weight")
                    .build() ?: Typeface.DEFAULT
            } else {
                Typeface.createFromAsset(context.assets, "fonts/$file")
            }
        } catch (e: Exception) {
            Typeface.DEFAULT
        }
        cache[key] = tf
        return tf
    }

    fun display(context: Context, weight: Int = 500) = load(context, "elmessiri.ttf", weight)
    fun body(context: Context, weight: Int = 400) = load(context, "readexpro.ttf", weight)
}
