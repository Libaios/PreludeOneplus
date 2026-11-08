package fr.preludedash.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Analyseur de spectre façon chaîne hi-fi des années 80 (afficheur fluorescent, segments néon,
 * crêtes qui retombent lentement).
 *
 * Il n'écoute pas vraiment la musique (Spotify ne donne pas le son aux autres applis) :
 * c'est une animation plausible, avec un tempo, des basses qui tapent et des aigus nerveux.
 * Il ne s'anime que si un morceau est en lecture, sinon les barres retombent.
 */
class HifiMeter(private val bands: Int = 14, private val segments: Int = 7) {

    private val level = FloatArray(bands)
    private val peak = FloatArray(bands)
    private val peakHold = FloatArray(bands)
    private val f1 = FloatArray(bands) { 1.3f + 2.7f * hash(it * 3 + 1) }
    private val f2 = FloatArray(bands) { 3.1f + 5.3f * hash(it * 7 + 2) }
    private val p1 = FloatArray(bands) { 6.28f * hash(it * 11 + 3) }
    private val p2 = FloatArray(bands) { 6.28f * hash(it * 13 + 4) }
    private var lastMs = 0L
    private var time = 0f

    private val seg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    /** Couleurs de l'afficheur : cyan en bas, magenta en haut, crêtes rose pâle. */
    private val low = Color.rgb(70, 240, 225)
    private val high = Color.rgb(255, 60, 190)
    private val peakColor = Color.rgb(255, 190, 235)

    fun draw(c: Canvas, left: Float, top: Float, width: Float, height: Float, playing: Boolean) {
        val now = SystemClock.elapsedRealtime()
        val dt = if (lastMs == 0L) 0.033f else ((now - lastMs) / 1000f).coerceIn(0f, 0.1f)
        lastMs = now
        if (playing) time += dt
        update(dt, playing)

        val gap = 2f
        val bw = (width - gap * (bands - 1)) / bands
        val sh = (height - gap * (segments - 1)) / segments
        for (b in 0 until bands) {
            val x = left + b * (bw + gap)
            val lit = (level[b] * segments).toInt()
            val pk = min(segments - 1, (peak[b] * segments).toInt())
            for (s in 0 until segments) {
                val y = top + height - (s + 1) * sh - s * gap
                r.set(x, y, x + bw, y + sh)
                val col = Accent.mix(low, high, s / (segments - 1f))
                when {
                    s < lit -> {
                        // Segment allumé, avec un halo comme un vrai afficheur fluorescent
                        glow.color = Color.argb(70, Color.red(col), Color.green(col), Color.blue(col))
                        c.drawRoundRect(r.left - 1.5f, r.top - 1.5f, r.right + 1.5f, r.bottom + 1.5f, 2.5f, 2.5f, glow)
                        seg.color = col
                    }
                    s == pk && peak[b] > 0.05f -> seg.color = peakColor
                    // Segment éteint : on le devine, comme sur les vieux afficheurs
                    else -> seg.color = Color.argb(34, Color.red(col), Color.green(col), Color.blue(col))
                }
                c.drawRoundRect(r, 1.5f, 1.5f, seg)
            }
        }
    }

    private fun update(dt: Float, playing: Boolean) {
        // Tempo de 118 BPM : grosse caisse sur chaque temps, un peu de caisse claire à contretemps
        val beat = time * 118f / 60f
        val frac = beat - beat.toInt()
        val kick = exp(-frac * 7f)
        val snare = if (beat.toInt() % 2 == 1) exp(-frac * 9f) else 0f
        for (b in 0 until bands) {
            val k = b / (bands - 1f)
            val target = if (!playing) 0f else {
                val body = 0.82f - 0.38f * k
                val wobble = 0.55f + 0.30f * sin(time * f1[b] + p1[b]) + 0.15f * sin(time * f2[b] + p2[b])
                val hit = kick * (1f - k) * 0.45f + snare * (0.3f + 0.4f * k) * 0.35f
                (body * wobble + hit).coerceIn(0f, 1f)
            }
            // Montée rapide, retombée plus douce
            val speed = if (target > level[b]) 18f else 5f
            level[b] += (target - level[b]) * min(1f, dt * speed)
            if (level[b] >= peak[b]) {
                peak[b] = level[b]
                peakHold[b] = 0.45f
            } else if (peakHold[b] > 0f) {
                peakHold[b] -= dt
            } else {
                peak[b] = max(0f, peak[b] - dt * 0.6f)
            }
        }
    }

    private fun hash(i: Int): Float {
        var x = i * 374761393 + 668265263
        x = (x xor (x ushr 13)) * 1274126177
        return ((x xor (x ushr 16)) and 0xffff) / 65535f
    }
}
