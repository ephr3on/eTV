package app.etv.launcher

import android.content.Context
import android.graphics.Typeface
import android.os.Build

/** One background mood: every colour the screens need is derived from it. */
class Palette(
    val name: String,
    val bg: Int,
    /** An app's door or cartouche. */
    val tile: Int,
    /** Thin outlines: doors, pills, dividers. */
    val tileStroke: Int,
    /** The fine line inside each door. */
    val tileInner: Int,
    /** Decoration: the background arches, frieze, rail divider. */
    val frame1: Int,
    val frame2: Int,
    val text: Int,
    val muted: Int,
    val label: Int,
    val dot: Int,
    /** Letter and name on an unselected door (differs from [text] when doors are dark on a light wall). */
    val tileText: Int = text,
    val tileLabel: Int = label,
    /** Text and icons on top of the highlight colour. */
    val onAccent: Int = bg,
    /** Soft filled controls (now-playing studs, artwork backdrop). */
    val surface: Int = tileInner,
    /** A selected settings row. */
    val card: Int = tile,
    /** A light frame between a door and its outline (Riad's whitewashed door frames); 0 for none. */
    val ring: Int = 0,
    /** Brass studs on doors; 0 for none. */
    val studs: Int = 0,
)

enum class TileShape { ARCH, CARTOUCHE }

/** How a home screen is put together. */
enum class Layout { CLASSIC, DOORWAY, RIAD, CARTOUCHE, LANTERN }

/** A whole look: structure, colours, shapes and type. */
class Theme(
    val name: String,
    val blurb: String,
    val layout: Layout,
    val palettes: List<Palette>,
    val accents: List<Pair<String, Int>>,
    val displayFont: String,
    val shape: TileShape,
    /** The selected door fills with the highlight colour (else it keeps its colour and gains a ring). */
    val fillOnFocus: Boolean = true,
)

object Look {
    private fun c(rgb: Int): Int = 0xFF000000.toInt() or rgb

    /** Classic's highlight colours (also Doorway's and Lantern's). */
    val accents: List<Pair<String, Int>> = listOf(
        "Whitewash" to c(0xEFE8DA),
        "Sand" to c(0xE8D3A8),
        "Bougainvillea" to c(0xE9A9C2),
        "Sea glass" to c(0xA6D8D2),
    )

    /** Classic's backgrounds (also Doorway's). */
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

    private val riad = Palette(
        "Whitewash", bg = c(0xF3EFE6), tile = c(0x1D4E7E), tileStroke = c(0xCFC6B5),
        tileInner = c(0x2F6195), frame1 = c(0xFBF8F2), frame2 = c(0xD4A64A),
        text = c(0x1E2A38), muted = c(0x5A6774), label = c(0x3A4654), dot = c(0xCFC6B5),
        tileText = c(0xFBF8F2), tileLabel = c(0xDCE6F0), onAccent = c(0xFFFFFF),
        surface = c(0xE4DCCD), card = c(0xFBF8F2), ring = c(0xFBF8F2), studs = c(0xE8D3A8),
    )

    private val cartouche = Palette(
        "Lapis night", bg = c(0x0F1A2E), tile = c(0x16243D), tileStroke = c(0x2A3B5C),
        tileInner = c(0x22314F), frame1 = c(0x22314F), frame2 = c(0x5B5136),
        text = c(0xEADFC8), muted = c(0xA7A08E), label = c(0xCFC5AE), dot = c(0x3A4A68),
    )

    private val lantern = Palette(
        "Lantern dark", bg = c(0x0A111C), tile = c(0x101C2A), tileStroke = c(0x26384D),
        tileInner = c(0x1C2C3F), frame1 = c(0x13233A), frame2 = c(0x101C2A),
        text = c(0xEFE8DA), muted = c(0x8E9AAA), label = c(0xA9B3C0), dot = c(0x34485E),
    )

    val themes: List<Theme> = listOf(
        Theme(
            "Classic", "Today's eTV: the doors in a row, the dock at the bottom",
            Layout.CLASSIC, palettes, accents, "elmessiri.ttf", TileShape.ARCH,
        ),
        Theme(
            "Doorway", "Calmer: buttons in the corner, the selected app named under the doors",
            Layout.DOORWAY, palettes, accents, "elmessiri.ttf", TileShape.ARCH,
        ),
        Theme(
            "Riad", "Daylight: blue doors on a white wall, tabs, and your inputs on home",
            Layout.RIAD, listOf(riad),
            listOf(
                "Bougainvillea" to c(0xB83A6E),
                "Sidi blue" to c(0x1D4E7E),
                "Ochre" to c(0xA9741F),
                "Teal" to c(0x2F7D74),
            ),
            "elmessiri.ttf", TileShape.ARCH, fillOnFocus = false,
        ),
        Theme(
            "Cartouche", "Pharaonic: lapis and gold, apps as cartouches, a side rail",
            Layout.CARTOUCHE, listOf(cartouche),
            listOf(
                "Gold" to c(0xC9A45C),
                "Faience" to c(0x3FA7A0),
                "Terracotta" to c(0xD98C5F),
                "Papyrus" to c(0xEADFC8),
            ),
            "reemkufi.ttf", TileShape.CARTOUCHE,
        ),
        Theme(
            "Lantern", "One door at a time, lit in the dark",
            Layout.LANTERN, listOf(lantern), accents, "elmessiri.ttf", TileShape.ARCH,
        ),
    )

    fun theme(p: Prefs): Theme {
        val t = themes[p.theme.coerceIn(0, themes.size - 1)]
        Fonts.displayFile = t.displayFont
        return t
    }

    fun accent(p: Prefs): Int {
        val list = theme(p).accents
        return list[p.accent.coerceIn(0, list.size - 1)].second
    }

    fun accentName(p: Prefs): String {
        val list = theme(p).accents
        return list[p.accent.coerceIn(0, list.size - 1)].first
    }

    fun palette(p: Prefs): Palette {
        val list = theme(p).palettes
        return list[p.palette.coerceIn(0, list.size - 1)]
    }

    fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)
}

/** El Messiri or Reem Kufi (display) and Readex Pro (text): all cover Arabic and Latin. */
object Fonts {
    private val cache = HashMap<String, Typeface>()

    /** The current theme's display font. */
    var displayFile = "elmessiri.ttf"

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

    fun display(context: Context, weight: Int = 500) = load(context, displayFile, weight)
    fun body(context: Context, weight: Int = 400) = load(context, "readexpro.ttf", weight)
}
