package fr.preludedash.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import fr.preludedash.data.Calibration
import fr.preludedash.data.EngineData
import fr.preludedash.link.DataHub
import fr.preludedash.music.MusicRelay
import fr.preludedash.shared.Palette
import fr.preludedash.ui.scene.SceneRenderer
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Dessine le tableau de bord plein écran. Le même code sert pour l'aperçu du téléphone
 * et pour la surface Android Auto, donc les deux affichent exactement la même chose.
 * Tout est dessiné dans un repère virtuel de 800 x 480, mis à l'échelle de l'écran.
 */
class DashRenderer {

    class Snapshot(
        val data: EngineData,
        val state: DataHub.State,
        val message: String,
        val hz: Float,
        val ageMs: Long,
        val page: Int,
        val cal: Calibration,
    )

    private enum class Level { NORMAL, COLD, WARN, ALERT }

    private class Tile(
        val label: String,
        val value: String,
        val unit: String,
        val frac: Float?,
        val level: Level = Level.NORMAL,
    )

    companion object {
        const val VW = 800f
        const val VH = 480f
        const val PAGES = 3

        /** Nom de la page suivante, pour le bouton. */
        fun nextPageName(page: Int): String = when (page % PAGES) {
            0 -> "Jauges"
            1 -> "Détails"
            else -> "3D"
        }

        val BG = Palette.BG
        val PANEL = Palette.PANEL
        val TRACK = Palette.TRACK
        val TEXT = Palette.TEXT
        val MUTED = Palette.MUTED
        val GREEN = Palette.GREEN
        val AMBER = Palette.AMBER
        val RED = Palette.RED
        val BLUE = Palette.BLUE
        val TEAL = Palette.TEAL

        /** Données fraîches (liaison active ou démo) : sinon tout s'affiche en tirets. */
        fun isLive(s: Snapshot): Boolean {
            val stale = s.state != DataHub.State.DEMO && s.ageMs > 1500
            return !stale && (s.state == DataHub.State.CONNECTED || s.state == DataHub.State.DEMO)
        }

        fun snapshot(page: Int, cal: Calibration) = Snapshot(
            data = DataHub.data,
            state = DataHub.state,
            message = DataHub.message,
            hz = DataHub.hz,
            ageMs = DataHub.ageMs(),
            page = page,
            cal = cal,
        )
    }

    private val condensedBold: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    private val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val value = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = condensedBold
        fontFeatureSettings = "tnum"
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = medium
    }
    private val rect = RectF()

    /** Largeur virtuelle de l'image en cours : VW, ou plus sur la page 3D d'un écran large. */
    private var vw = VW

    /**
     * [area] = zone utile (hors barres d'Android Auto). Le fond est peint sur tout le canevas.
     * [overlay] : la page 3D est rendue à part (carte graphique), on ne dessine que les infos
     * par-dessus, sur fond transparent.
     * [wide] : écran plus allongé que 800 x 480 (combiné) ; la page 3D s'étale jusqu'aux bords
     * au lieu de rester centrée. Les pages jauges et détails restent centrées.
     */
    fun draw(canvas: Canvas, area: Rect, s: Snapshot, overlay: Boolean = false, wide: Boolean = false) {
        val transparent = overlay && s.page % PAGES == 0
        if (transparent) {
            canvas.drawColor(0, PorterDuff.Mode.CLEAR)
            drawShade(canvas)
        } else {
            canvas.drawColor(BG)
        }
        if (area.width() <= 0 || area.height() <= 0) return
        val scale = min(area.width() / VW, area.height() / VH)
        // Seulement par-dessus la vraie 3D (le décor 2D de secours reste au format 800 x 480)
        val spread = wide && transparent && area.width() > VW * scale + 1f
        vw = if (spread) area.width() / scale else VW
        val ox = if (spread) area.left.toFloat() else area.left + (area.width() - VW * scale) / 2f
        val oy = area.top + (area.height() - VH * scale) / 2f
        canvas.save()
        canvas.translate(ox, oy)
        canvas.scale(scale, scale)
        val stale = s.state != DataHub.State.DEMO && s.ageMs > 1500
        when (s.page % PAGES) {
            0 -> drawScenePage(canvas, s, stale, transparent)
            1 -> {
                drawHeader(canvas, s, stale)
                drawMain(canvas, s, stale)
            }
            else -> {
                drawHeader(canvas, s, stale)
                drawDetails(canvas, s, stale)
            }
        }
        canvas.restore()
    }

    // ---------------------------------------------------------------- en-tête

    private fun drawHeader(c: Canvas, s: Snapshot, stale: Boolean) {
        val live = s.state == DataHub.State.CONNECTED && !stale
        fill.color = when {
            s.state == DataHub.State.DEMO -> BLUE
            live -> GREEN
            s.state == DataHub.State.ERROR -> RED
            else -> AMBER
        }
        c.drawCircle(10f, 16f, 6f, fill)

        val text = when {
            s.state == DataHub.State.DEMO -> "Mode démo"
            live -> "ECU connecté   ${s.hz.roundToInt()} Hz"
            s.state == DataHub.State.CONNECTED -> "Connecté, en attente de données"
            else -> s.message
        }
        setLabel(17f, MUTED, 0.02f)
        // Sur la page 3D, le bandeau musique occupe le haut au centre
        val maxW = if (s.page % PAGES == 0 && MusicRelay.title != null) vw / 2f - 208f else 470f
        c.drawText(ellipsize(text, maxW), 24f, 22f, label)

        if (!stale) {
            var right = vw
            if (s.data.mil) right = lamp(c, "MIL", right, AMBER)
            if (s.data.fan) lamp(c, "VENTILO", right, BLUE)
        }
    }

    private fun lamp(c: Canvas, text: String, right: Float, color: Int): Float {
        setLabel(13f, BG, 0.1f)
        val w = label.measureText(text) + 20f
        rect.set(right - w, 5f, right, 27f)
        fill.color = color
        c.drawRoundRect(rect, 11f, 11f, fill)
        c.drawText(text, right - w + 10f, 21f, label)
        return right - w - 8f
    }

    // --------------------------------------------------------------- page 3D

    private val scene = SceneRenderer()

    private val shade = Paint()
    private var shadeH = -1
    private var topShader: Shader? = null
    private var bottomShader: Shader? = null

    /** Dégradés sombres en haut et en bas, pour lire les chiffres par-dessus la 3D. */
    private fun drawShade(c: Canvas) {
        val w = c.width.toFloat()
        val h = c.height.toFloat()
        if (shadeH != c.height) {
            shadeH = c.height
            topShader = LinearGradient(0f, 0f, 0f, h * 0.22f, Color.argb(160, 0, 0, 0), 0, Shader.TileMode.CLAMP)
            bottomShader = LinearGradient(0f, h * 0.62f, 0f, h, 0, Color.argb(185, 0, 0, 0), Shader.TileMode.CLAMP)
        }
        shade.shader = topShader
        c.drawRect(0f, 0f, w, h * 0.22f, shade)
        shade.shader = bottomShader
        c.drawRect(0f, h * 0.62f, w, h, shade)
        shade.shader = null
    }

    /** Page d'accueil : la Prelude en 3D, avec régime, vitesse et AFR par-dessus. */
    private fun drawScenePage(c: Canvas, s: Snapshot, stale: Boolean, overlay: Boolean) {
        val d = s.data
        val live = isLive(s)
        if (!overlay) scene.draw(c, d, live, s.cal.redline)
        drawHeader(c, s, stale)
        val accent = Accent.color(d.rpm, s.cal.redline, live)
        drawMusic(c, if (live) accent else TEAL)
        // Rupteur : cadre rouge qui clignote (la version 2D le dessine elle-même)
        if (overlay && live && d.rpm >= s.cal.redline && (SystemClock.elapsedRealtime() / 125) % 2 == 0L) {
            stroke.strokeWidth = 10f
            stroke.color = Color.argb(210, 255, 60, 60)
            c.drawRect(5f, 5f, vw - 5f, VH - 5f, stroke)
            stroke.strokeCap = Paint.Cap.ROUND
        }

        // Régime, en bas à gauche, avec une barre fine
        value.textSize = 72f
        value.color = if (!live) MUTED else if (d.rpm >= s.cal.redline) RED else TEXT
        val rpmText = if (live) d.rpm.toString() else "----"
        c.drawText(rpmText, 20f, 430f, value)
        setLabel(15f, MUTED, 0.14f)
        c.drawText("TR/MIN", 24f + value.measureText(rpmText), 430f, label)
        rect.set(20f, 446f, 300f, 452f)
        fill.color = TRACK
        c.drawRoundRect(rect, 3f, 3f, fill)
        if (live) {
            rect.right = 20f + 280f * (d.rpm / (s.cal.redline + 1000f)).coerceIn(0f, 1f)
            fill.color = accent
            c.drawRoundRect(rect, 3f, 3f, fill)
        }

        // Vitesse, en bas à droite
        value.textSize = 64f
        value.color = if (live) TEXT else MUTED
        val sp = if (live) d.vssKmh.toString() else "--"
        val spW = value.measureText(sp)
        setLabel(15f, MUTED, 0.14f)
        val unitW = label.measureText("KM/H")
        c.drawText(sp, vw - 24f - unitW - 6f - spW, 444f, value)
        c.drawText("KM/H", vw - 24f - unitW, 444f, label)

        // AFR, en haut à droite
        val afr = d.afr
        val lean = live && afr != null && afr > 15.0f && d.tpsPct > 60
        setLabel(14f, MUTED, 0.14f)
        c.drawText("AFR", vw - 24f - label.measureText("AFR"), 58f, label)
        value.textSize = 46f
        value.color = when {
            !live || afr == null -> MUTED
            lean -> RED
            else -> TEXT
        }
        val afrText = if (live && afr != null) fmt1(afr) else "--.-"
        c.drawText(afrText, vw - 24f - value.measureText(afrText), 104f, value)
    }

    private val artPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val clip = Path()

    private val meter = HifiMeter()
    private val panel = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * Bandeau musique en haut au centre, façon platine hi-fi : pochette, titre, artiste,
     * analyseur de spectre néon (animé seulement pendant la lecture) et avancement du morceau.
     */
    private fun drawMusic(c: Canvas, accent: Int) {
        val title = MusicRelay.title ?: return
        val playing = MusicRelay.playing
        // Centré sur la largeur réelle (le combiné grand écran est plus large que 800)
        val l = vw / 2f - 174f
        val t = 4f
        val r = vw / 2f + 174f
        val b = 50f
        rect.set(l, t, r, b)
        panel.style = Paint.Style.FILL
        panel.color = Color.argb(150, 8, 6, 16)
        c.drawRoundRect(rect, 10f, 10f, panel)
        panel.style = Paint.Style.STROKE
        panel.strokeWidth = 1.5f
        panel.color = Color.argb(if (playing) 120 else 60, 255, 60, 190)
        c.drawRoundRect(rect, 10f, 10f, panel)
        panel.style = Paint.Style.FILL

        // Pochette
        val size = 38f
        rect.set(l + 6f, t + 4f, l + 6f + size, t + 4f + size)
        val art = MusicRelay.art
        if (art != null) {
            clip.reset()
            clip.addRoundRect(rect, 5f, 5f, Path.Direction.CW)
            c.save()
            c.clipPath(clip)
            c.drawBitmap(art, null, rect, artPaint)
            c.restore()
        } else {
            fill.color = PANEL
            c.drawRoundRect(rect, 5f, 5f, fill)
            setLabel(22f, accent, 0f)
            label.textAlign = Paint.Align.CENTER
            c.drawText("♪", rect.centerX(), rect.centerY() + 8f, label)
            label.textAlign = Paint.Align.LEFT
        }

        // Titre et artiste
        val tx = l + 6f + size + 8f
        val tw = 146f
        value.textSize = 17f
        value.color = TEXT
        c.drawText(fit(value, title, tw), tx, t + 20f, value)
        setLabel(12.5f, MUTED, 0.02f)
        val sub = if (playing) MusicRelay.artist.orEmpty() else "En pause"
        if (sub.isNotBlank()) c.drawText(fit(label, sub, tw), tx, t + 37f, label)

        // Analyseur de spectre
        meter.draw(c, r - 140f, t + 6f, 132f, 34f, playing)

        // Avancement du morceau
        val p = MusicRelay.progress() ?: return
        rect.set(l + 10f, b - 4f, r - 10f, b - 2.5f)
        fill.color = TRACK
        c.drawRect(rect, fill)
        rect.right = rect.left + (r - l - 20f) * p
        fill.color = accent
        c.drawRect(rect, fill)
    }

    // ---------------------------------------------------------- page principale

    private fun drawMain(c: Canvas, s: Snapshot, stale: Boolean) {
        val d = s.data
        drawTach(c, s, stale, cx = 192f, cy = 268f, r = 168f)

        // AFR
        val left = 392f
        val afr = d.afr
        val lean = !stale && afr != null && afr > 15.0f && d.tpsPct > 60
        setLabel(15f, MUTED, 0.12f)
        c.drawText("AFR", left, 60f, label)
        value.textSize = 92f
        value.color = when {
            stale || afr == null -> MUTED
            lean -> RED
            else -> TEXT
        }
        val afrText = if (stale || afr == null) "--.-" else fmt1(afr)
        c.drawText(afrText, left, 140f, value)
        if (!stale && afr != null) {
            setLabel(22f, MUTED, 0.02f)
            val lam = "λ " + fmt2(afr / 14.7f)
            c.drawText(lam, VW - label.measureText(lam), 60f, label)
        }
        if (lean) {
            setLabel(14f, RED, 0.1f)
            val warn = "PAUVRE EN CHARGE"
            c.drawText(warn, VW - label.measureText(warn), 140f, label)
        }

        // Échelle AFR 10 -> 18 avec repère
        val sy = 160f
        rect.set(left, sy, VW, sy + 6f)
        fill.color = TRACK
        c.drawRoundRect(rect, 3f, 3f, fill)
        val stoX = left + (14.7f - 10f) / 8f * (VW - left)
        fill.color = MUTED
        c.drawRect(stoX - 1f, sy - 4f, stoX + 1f, sy + 10f, fill)
        if (!stale && afr != null) {
            val mx = left + ((afr - 10f) / 8f).coerceIn(0f, 1f) * (VW - left)
            fill.color = if (lean) RED else TEAL
            c.drawCircle(mx, sy + 3f, 8f, fill)
        }
        setLabel(12f, MUTED, 0f)
        c.drawText("10", left, sy + 26f, label)
        c.drawText("14.7", stoX - label.measureText("14.7") / 2f, sy + 26f, label)
        c.drawText("18", VW - label.measureText("18"), sy + 26f, label)

        val tiles = listOf(
            Tile("EAU", fmt0(d.ectC), "°C", frac(d.ectC, -20f, 120f), ectLevel(d.ectC)),
            Tile("ADMISSION", fmt0(d.iatC), "°C", frac(d.iatC, 0f, 80f), iatLevel(d.iatC)),
            ethanolTile(d, s.cal),
            Tile("MAP", d.mapMbar.toString(), "mbar", frac(d.mapMbar.toFloat(), 0f, 1100f)),
            Tile("BATTERIE", fmt1(d.batteryV), "V", frac(d.batteryV, 10f, 16f), battLevel(d.batteryV)),
            Tile("AVANCE", fmt0(d.ignDeg), "°", frac(d.ignDeg, -6f, 50f)),
        )
        grid(c, tiles, left = left, top = 202f, width = VW - left, rows = 2, cols = 3,
            stale = stale, valueSize = 46f, compact = true)
    }

    /** Compte-tours en arc de 270°, ouvert en bas, comme un vrai cadran. */
    private fun drawTach(c: Canvas, s: Snapshot, stale: Boolean, cx: Float, cy: Float, r: Float) {
        val redline = s.cal.redline
        val maxRpm = ((redline + 1999) / 1000) * 1000 // 7000 -> 8000
        val rpm = if (stale) 0 else s.data.rpm
        val start = 135f
        val sweep = 270f
        fun angle(v: Float) = start + sweep * (v / maxRpm).coerceIn(0f, 1f)

        rect.set(cx - r, cy - r, cx + r, cy + r)
        stroke.strokeWidth = 20f
        stroke.strokeCap = Paint.Cap.BUTT
        // Piste, puis zone rouge en sourdine
        stroke.color = TRACK
        c.drawArc(rect, start, sweep, false, stroke)
        stroke.color = dim(RED, 110)
        c.drawArc(rect, angle(redline.toFloat()), start + sweep - angle(redline.toFloat()), false, stroke)

        // Aiguille "remplie" jusqu'au régime actuel
        if (rpm > 0) {
            stroke.color = when {
                rpm >= redline -> RED
                rpm >= redline - 1000 -> AMBER
                else -> TEAL
            }
            c.drawArc(rect, start, angle(rpm.toFloat()) - start, false, stroke)
            val a = Math.toRadians(angle(rpm.toFloat()).toDouble())
            fill.color = TEXT
            c.drawCircle(cx + (r * cos(a)).toFloat(), cy + (r * sin(a)).toFloat(), 7f, fill)
        }

        // Graduations
        stroke.strokeCap = Paint.Cap.ROUND
        setLabel(19f, MUTED, 0f)
        label.textAlign = Paint.Align.CENTER
        for (h in 0..maxRpm / 500) {
            val v = h * 500f
            val a = Math.toRadians(angle(v).toDouble())
            val major = h % 2 == 0
            val r1 = r - 18f
            val r2 = if (major) r - 34f else r - 26f
            stroke.strokeWidth = if (major) 3f else 2f
            stroke.color = if (v >= redline) RED else MUTED
            c.drawLine(
                cx + (r1 * cos(a)).toFloat(), cy + (r1 * sin(a)).toFloat(),
                cx + (r2 * cos(a)).toFloat(), cy + (r2 * sin(a)).toFloat(), stroke,
            )
            if (major) {
                val rt = r - 52f
                label.color = if (v >= redline) RED else MUTED
                c.drawText((h / 2).toString(), cx + (rt * cos(a)).toFloat(), cy + (rt * sin(a)).toFloat() + 7f, label)
            }
        }

        // Régime en chiffres
        value.textAlign = Paint.Align.CENTER
        value.textSize = 74f
        value.color = when {
            stale -> MUTED
            rpm >= redline -> RED
            else -> TEXT
        }
        c.drawText(if (stale) "----" else rpm.toString(), cx, cy + 22f, value)
        setLabel(14f, MUTED, 0.14f)
        label.textAlign = Paint.Align.CENTER
        c.drawText("TR/MIN", cx, cy + 48f, label)

        // VTEC au-dessus
        if (!stale && s.data.vtec) {
            setLabel(15f, BG, 0.14f)
            label.textAlign = Paint.Align.CENTER
            rect.set(cx - 38f, cy - 76f, cx + 38f, cy - 50f)
            fill.color = AMBER
            c.drawRoundRect(rect, 13f, 13f, fill)
            c.drawText("VTEC", cx, cy - 57f, label)
        }

        // Vitesse dans l'ouverture du cadran
        value.textSize = 34f
        value.color = if (stale) MUTED else TEXT
        c.drawText(if (stale) "--" else s.data.vssKmh.toString(), cx, cy + r - 14f, value)
        setLabel(13f, MUTED, 0.1f)
        label.textAlign = Paint.Align.CENTER
        c.drawText("KM/H", cx, cy + r + 4f, label)

        value.textAlign = Paint.Align.LEFT
        label.textAlign = Paint.Align.LEFT
    }

    // ------------------------------------------------------------ page détails

    private fun drawDetails(c: Canvas, s: Snapshot, stale: Boolean) {
        val d = s.data
        val tiles = listOf(
            Tile("VITESSE", d.vssKmh.toString(), "km/h", null),
            Tile("PAPILLON", d.tpsPct.toString(), "%", d.tpsPct / 100f),
            Tile("INJECTION", fmt2(d.injMs), "ms", null),
            Tile("INJECTEURS", fmt0(d.dutyPct), "%", d.dutyPct / 100f, dutyLevel(d.dutyPct)),
            Tile("CORR. COURT", signed(d.stftPct), "%", null),
            Tile("CORR. LONG", signed(d.ltftPct), "%", null),
            Tile("BARO", d.baroMbar.toString(), "mbar", null),
            Tile("SONDE O2", fmt2(d.o2Volt), "V", d.o2Volt / 5f),
        )
        grid(c, tiles, left = 0f, top = 44f, width = VW, rows = 2, cols = 4,
            stale = stale, valueSize = 60f, compact = false)
    }

    // --------------------------------------------------------------- tuiles

    private fun ethanolTile(d: EngineData, cal: Calibration): Tile {
        val e = d.ethanolPct
        return if (cal.ethByte < 0 && e == null) {
            Tile("ÉTHANOL", "--", "", null)
        } else {
            Tile("ÉTHANOL", e?.let { fmt0(it) } ?: "--", "%", e?.let { it / 100f })
        }
    }

    private fun grid(
        c: Canvas,
        tiles: List<Tile>,
        left: Float,
        top: Float,
        width: Float,
        rows: Int,
        cols: Int,
        stale: Boolean,
        valueSize: Float,
        compact: Boolean,
    ) {
        val gap = 12f
        val w = (width - gap * (cols - 1)) / cols
        val h = (VH - top - gap * (rows - 1)) / rows
        tiles.forEachIndexed { i, t ->
            if (i >= rows * cols) return@forEachIndexed
            val x = left + (i % cols) * (w + gap)
            val y = top + (i / cols) * (h + gap)
            drawTile(c, t, x, y, w, h, stale, valueSize, compact)
        }
    }

    /** [compact] : l'unité passe en haut à droite, pour les tuiles étroites. */
    private fun drawTile(
        c: Canvas,
        t: Tile,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        stale: Boolean,
        valueSize: Float,
        compact: Boolean,
    ) {
        rect.set(x, y, x + w, y + h)
        fill.color = PANEL
        c.drawRoundRect(rect, 14f, 14f, fill)

        setLabel(13f, MUTED, 0.1f)
        c.drawText(ellipsize(t.label, w - 28f - if (compact) 34f else 0f), x + 14f, y + 25f, label)

        val (valueColor, barColor) = colors(t.level)
        value.textSize = valueSize
        value.color = if (stale) MUTED else valueColor
        val v = if (stale) "--" else t.value
        val baseline = y + h * 0.5f + valueSize * 0.38f
        c.drawText(v, x + 14f, baseline, value)
        if (t.unit.isNotEmpty()) {
            if (compact) {
                setLabel(13f, MUTED, 0.02f)
                c.drawText(t.unit, x + w - 14f - label.measureText(t.unit), y + 25f, label)
            } else {
                setLabel(16f, MUTED, 0.02f)
                c.drawText(t.unit, x + 14f + value.measureText(v) + 6f, baseline, label)
            }
        }

        val f = t.frac
        if (f != null) {
            val by = y + h - 16f
            rect.set(x + 14f, by, x + w - 14f, by + 5f)
            fill.color = TRACK
            c.drawRoundRect(rect, 3f, 3f, fill)
            if (!stale) {
                rect.right = rect.left + (w - 28f) * f.coerceIn(0f, 1f)
                fill.color = barColor
                c.drawRoundRect(rect, 3f, 3f, fill)
            }
        }
    }

    // ---------------------------------------------------------- seuils couleur

    private fun ectLevel(t: Float) = when {
        t > 105f -> Level.ALERT
        t > 100f -> Level.WARN
        t < 60f -> Level.COLD
        else -> Level.NORMAL
    }

    private fun iatLevel(t: Float) = when {
        t > 60f -> Level.ALERT
        t > 45f -> Level.WARN
        else -> Level.NORMAL
    }

    private fun battLevel(v: Float) = when {
        v < 12.0f || v > 15.2f -> Level.ALERT
        v < 13.0f -> Level.WARN
        else -> Level.NORMAL
    }

    private fun dutyLevel(d: Float) = when {
        d > 90f -> Level.ALERT
        d > 80f -> Level.WARN
        else -> Level.NORMAL
    }

    private fun colors(l: Level): Pair<Int, Int> = when (l) {
        Level.NORMAL -> TEXT to TEAL
        Level.COLD -> BLUE to BLUE
        Level.WARN -> AMBER to AMBER
        Level.ALERT -> RED to RED
    }

    // ---------------------------------------------------------------- outils

    private fun setLabel(size: Float, color: Int, spacing: Float) {
        label.textSize = size
        label.color = color
        label.letterSpacing = spacing
        label.textAlign = Paint.Align.LEFT
    }

    private fun fit(p: Paint, text: String, maxWidth: Float): String {
        if (p.measureText(text) <= maxWidth) return text
        var t = text
        while (t.length > 1 && p.measureText("$t...") > maxWidth) t = t.dropLast(1)
        return "$t..."
    }

    private fun ellipsize(text: String, maxWidth: Float): String {
        if (label.measureText(text) <= maxWidth) return text
        var t = text
        while (t.length > 1 && label.measureText("$t...") > maxWidth) t = t.dropLast(1)
        return "$t..."
    }

    private fun dim(color: Int, alpha: Int = 48): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    private fun frac(v: Float, lo: Float, hi: Float): Float = ((v - lo) / (hi - lo)).coerceIn(0f, 1f)

    private fun fmt0(v: Float) = v.roundToInt().toString()

    private fun fmt1(v: Float) = String.format(Locale.US, "%.1f", v)

    private fun fmt2(v: Float) = String.format(Locale.US, "%.2f", v)

    private fun signed(v: Float) = String.format(Locale.US, "%+.1f", v)
}
