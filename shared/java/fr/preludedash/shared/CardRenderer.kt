package fr.preludedash.shared

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface

/**
 * Dessine la pochette de Prelude Carte. Une seule image sert à deux affichages :
 *
 * - **Colonne à côté de Waze** : Android Auto agrandit l'image à toute la hauteur de la
 *   colonne et n'en garde que la bande centrale (x de 123 à 389 sur 512, mesuré sur un
 *   écran 1024x600), assombrie, avec titre et boutons sous y = 360. On y met l'essentiel :
 *   alerte, régime (en gros), AFR, MAP, eau, éthanol.
 * - **Écran musique plein écran** : l'image carrée est affichée en entier. Les deux bandes
 *   latérales, invisibles dans la colonne, ajoutent admission, avance, batterie, injecteurs.
 *
 * Couleurs poussées au maximum de contraste à cause de l'assombrissement d'Android Auto.
 * Repère de dessin : 512 x 512, mis à l'échelle de [size].
 */
object CardRenderer {
    private const val V = 512f

    // Bande centrale visible dans la colonne (avec une petite marge)
    private const val BAND_L = 130f
    private const val BAND_R = 382f
    private const val CX = 256f

    // Bandes latérales, visibles seulement en plein écran
    private const val LEFT_CX = 64f
    private const val RIGHT_CX = 448f

    private val WHITE = Color.WHITE
    private val LABEL = Color.rgb(176, 184, 192)
    private val BLACK = Color.rgb(4, 5, 7)
    private val SIDE_BG = Color.rgb(20, 24, 30)

    private val condensedBold: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    private val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    fun render(s: CardSnapshot, size: Int, version: String = ""): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(size / V, size / V)
        draw(c, s, version)
        return bmp
    }

    fun draw(c: Canvas, s: CardSnapshot, version: String = "") {
        c.drawColor(BLACK)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)

        drawCenter(c, s, text, fill)
        drawSides(c, s, text, fill)

        // Numéro de version, discret, en haut à droite de la bande visible
        if (version.isNotEmpty()) {
            text.typeface = medium
            text.textSize = 15f
            text.letterSpacing = 0.05f
            text.textAlign = Paint.Align.RIGHT
            text.color = Color.rgb(110, 118, 128)
            c.drawText(version, BAND_R - 4f, 22f, text)
            text.textAlign = Paint.Align.CENTER
        }
    }

    private fun drawCenter(c: Canvas, s: CardSnapshot, text: Paint, fill: Paint) {
        // En-tête : alerte en rouge, sinon le libellé AFR avec le point d'état
        val alert = s.alert
        if (alert != null && s.live) {
            fill.color = Palette.RED
            c.drawRoundRect(RectF(BAND_L, 26f, BAND_R, 70f), 22f, 22f, fill)
            text.typeface = condensedBold
            text.textSize = 30f
            text.letterSpacing = 0.02f
            text.color = WHITE
            c.drawText(fit(text, alert, BAND_R - BAND_L - 24f), CX, 58f, text)
        } else {
            text.typeface = medium
            text.textSize = 22f
            text.letterSpacing = 0.18f
            text.color = LABEL
            c.drawText("TR/MIN", CX + 12f, 58f, text)
            fill.color = when {
                s.demo -> Palette.BLUE
                s.live -> Palette.GREEN
                else -> Palette.AMBER
            }
            c.drawCircle(CX - 60f, 51f, 7f, fill)
        }

        // Régime en gros, orange puis rouge à l'approche de la zone rouge
        text.typeface = condensedBold
        text.letterSpacing = -0.02f
        text.textSize = 100f
        text.color = levelColor(s.rpmLevel, s.live)
        c.drawText(fit(text, s.rpm, BAND_R - BAND_L), CX, 150f, text)

        // Séparateur
        fill.color = Color.rgb(60, 68, 78)
        c.drawRect(BAND_L + 10f, 166f, BAND_R - 10f, 169f, fill)

        // Grille 2 x 2 : AFR, MAP, eau, éthanol
        val leftX = (BAND_L + CX) / 2f
        val rightX = (CX + BAND_R) / 2f
        cell(c, text, leftX, 198f, "AFR", s.afr, levelColor(s.afrLevel, s.live))
        cell(c, text, rightX, 198f, "MAP", s.map, if (s.live) WHITE else LABEL)
        cell(c, text, leftX, 288f, "EAU", s.ect + "°", levelColor(s.ectLevel, s.live))
        cell(c, text, rightX, 288f, "E85", s.eth + "%", if (s.live) WHITE else LABEL)
    }

    private fun cell(c: Canvas, text: Paint, cx: Float, top: Float, label: String, value: String, color: Int) {
        text.typeface = medium
        text.textSize = 18f
        text.letterSpacing = 0.12f
        text.color = LABEL
        c.drawText(label, cx, top, text)
        text.typeface = condensedBold
        text.letterSpacing = -0.02f
        text.textSize = 52f
        text.color = color
        c.drawText(fit(text, value, 116f), cx, top + 54f, text)
    }

    private fun levelColor(l: CardSnapshot.Level, live: Boolean): Int = if (!live) LABEL else when (l) {
        CardSnapshot.Level.ALERT -> Palette.RED
        CardSnapshot.Level.WARN -> Palette.AMBER
        CardSnapshot.Level.COLD -> Palette.BLUE
        CardSnapshot.Level.NORMAL -> WHITE
    }

    /** Bandes latérales : visibles uniquement quand l'image est affichée en entier. */
    private fun drawSides(c: Canvas, s: CardSnapshot, text: Paint, fill: Paint) {
        fill.color = SIDE_BG
        c.drawRoundRect(RectF(8f, 40f, 120f, 472f), 18f, 18f, fill)
        c.drawRoundRect(RectF(392f, 40f, 504f, 472f), 18f, 18f, fill)

        side(c, text, LEFT_CX, 100f, "ADM.", s.iat, s.live)
        side(c, text, LEFT_CX, 300f, "AVANCE", s.ign, s.live)
        side(c, text, RIGHT_CX, 100f, "BATT", s.batt, s.live)
        side(c, text, RIGHT_CX, 300f, "INJ.", s.duty, s.live)
    }

    private fun side(c: Canvas, text: Paint, cx: Float, top: Float, label: String, value: String, live: Boolean) {
        text.typeface = medium
        text.textSize = 18f
        text.letterSpacing = 0.1f
        text.color = LABEL
        c.drawText(label, cx, top, text)
        text.typeface = condensedBold
        text.letterSpacing = -0.02f
        text.textSize = 44f
        text.color = if (live) WHITE else LABEL
        c.drawText(fit(text, value, 100f), cx, top + 56f, text)
    }

    private fun fit(p: Paint, t: String, maxW: Float): String {
        if (p.measureText(t) <= maxW) return t
        val original = p.textSize
        while (p.textSize > 12f && p.measureText(t) > maxW) p.textSize -= 2f
        if (p.measureText(t) <= maxW) return t
        p.textSize = original
        var s = t
        while (s.length > 1 && p.measureText(s) > maxW) s = s.dropLast(1)
        return s
    }
}
