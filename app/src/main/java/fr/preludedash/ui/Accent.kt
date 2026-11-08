package fr.preludedash.ui

import android.graphics.Color
import fr.preludedash.shared.Palette

/** Couleur d'ambiance qui suit le régime, commune au décor 3D et au tableau de bord. */
object Accent {
    /** Turquoise au repos, orange à 1000 tr/min de la zone rouge, rouge au rupteur. Bleu sans données. */
    fun color(rpm: Int, redline: Int, live: Boolean): Int {
        if (!live) return Palette.BLUE
        val warn = redline - 1000
        return when {
            rpm >= redline -> Palette.RED
            rpm >= warn -> mix(Palette.AMBER, Palette.RED, (rpm - warn) / 1000f)
            rpm >= warn - 1500 -> mix(Palette.TEAL, Palette.AMBER, (rpm - (warn - 1500)) / 1500f)
            else -> Palette.TEAL
        }
    }

    fun mix(a: Int, b: Int, k: Float): Int {
        val f = k.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(a) + (Color.red(b) - Color.red(a)) * f).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * f).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f).toInt(),
        )
    }
}
