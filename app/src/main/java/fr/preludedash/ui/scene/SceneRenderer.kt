package fr.preludedash.ui.scene

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.os.SystemClock
import fr.preludedash.data.EngineData
import fr.preludedash.ui.Accent
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Scène 3D de la page d'accueil : la Prelude tourne sur un sol néon à l'arrêt, et passe
 * en caméra de poursuite quand elle roule. L'ambiance suit le régime.
 *
 * Rendu logiciel : projection en perspective, faces triées de la plus lointaine à la plus
 * proche, ombrage plat avec reflet et liseré néon. Dessiné dans le repère 800 x 480.
 * Sert de secours si la 3D (Filament) ne démarre pas sur le téléphone.
 */
class SceneRenderer {
    companion object {
        private const val VW = 800f
        private const val VH = 480f
        private const val FOCAL = 690f
        private const val HORIZON_Y = 236f

        private val BODY_BLUE = floatArrayOf(36f, 96f, 230f)
        private val GLASS_C = floatArrayOf(14f, 20f, 32f)
        private val UNDER_C = floatArrayOf(10f, 12f, 14f)
        private val GRILLE_C = floatArrayOf(16f, 18f, 22f)
        private val HEAD_C = floatArrayOf(215f, 228f, 245f)
        private val TAIL_C = floatArrayOf(210f, 28f, 40f)
        private val TIRE_C = floatArrayOf(20f, 21f, 24f)
        private val RIM_C = floatArrayOf(190f, 196f, 206f)
        private val RIM_DARK_C = floatArrayOf(52f, 56f, 62f)

        // Lumière principale (normalisée), venant d'en haut à gauche
        private val L = normalize(floatArrayOf(-0.35f, 0.85f, 0.40f))

        private fun normalize(v: FloatArray): FloatArray {
            val l = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
            return floatArrayOf(v[0] / l, v[1] / l, v[2] / l)
        }
    }

    // État de l'animation
    private var lastMs = 0L
    private var spin = 0.6f
    private var drive = 0f
    private var odo = 0f
    private var wheelAngle = 0f

    // Caméra courante
    private val cam = FloatArray(3)
    private val target = FloatArray(3)
    private val right = FloatArray(3)
    private val up = FloatArray(3)
    private val fwd = FloatArray(3)

    // Tampons de rendu, réutilisés à chaque image
    private val total = CarModel.count + CarModel.WHEEL_TRIS
    private val wPos = FloatArray(CarModel.WHEEL_TRIS * 9)
    private val wNrm = FloatArray(CarModel.WHEEL_TRIS * 3)
    private val wMat = IntArray(CarModel.WHEEL_TRIS)
    private val keys = LongArray(total)
    private val sx = FloatArray(total * 3)
    private val sy = FloatArray(total * 3)
    private val col = IntArray(total)
    private val verts = FloatArray(total * 6)
    private val colors = IntArray(total * 3)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val vertexPaint = Paint()
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val path = Path()

    /** Dessine la scène. [redline] sert à la couleur d'ambiance. */
    fun draw(c: Canvas, d: EngineData, live: Boolean, redline: Int) {
        val now = SystemClock.elapsedRealtime()
        val dt = if (lastMs == 0L) 0.05f else ((now - lastMs) / 1000f).coerceIn(0f, 0.2f)
        lastMs = now
        val t = now / 1000f

        val rpm = if (live) d.rpm else 0
        val speedKmh = if (live) d.vssKmh else 0
        val speed = speedKmh / 3.6f
        val driving = live && speedKmh >= 3
        val rpmFrac = (rpm / redline.toFloat()).coerceIn(0f, 1.2f)

        // Transition douce entre plateau tournant et poursuite
        drive += ((if (driving) 1f else 0f) - drive) * min(1f, dt * 1.5f)
        spin += dt * 0.35f * (1f - drive)
        odo += speed * dt
        wheelAngle -= speed / CarModel.WHEEL_R * dt

        val accent = accentColor(rpm, redline, live)

        drawBackground(c, accent, rpmFrac)
        setupCamera(t, rpmFrac)
        drawGrid(c, accent)
        drawShadow(c, accent, rpmFrac)
        drawCar(c, accent, rpmFrac, t)
        if (driving && speedKmh > 70) drawSpeedLines(c, t, (speedKmh - 70) / 90f)
        if (live && rpm >= redline) drawShiftFlash(c, t)
    }

    // ---------------------------------------------------------------- caméra

    private fun setupCamera(t: Float, rpmFrac: Float) {
        // Plateau : la caméra tourne autour. Poursuite : 3/4 arrière gauche.
        val turnAngle = spin
        val chaseAngle = (PI * 1.18).toFloat()
        val angle = lerpAngle(turnAngle, chaseAngle, smooth(drive))
        val elev = lerp(0.30f, 0.20f, drive)
        val dist = lerp(6.6f, 6.0f, drive)
        val shake = if (rpmFrac > 0.85f) (rpmFrac - 0.85f) * 0.12f else 0f

        val tx = lerp(0f, 0.4f, drive)
        val ty = 0.55f
        target[0] = tx
        target[1] = ty
        target[2] = 0f
        cam[0] = tx + dist * cos(elev) * cos(angle)
        cam[1] = ty + dist * sin(elev) + shake * sin(t * 47f)
        cam[2] = dist * cos(elev) * sin(angle) + shake * cos(t * 39f)

        fwd[0] = tx - cam[0]
        fwd[1] = ty - cam[1]
        fwd[2] = 0f - cam[2]
        norm(fwd)
        // droite = avant x haut
        right[0] = -fwd[2]
        right[1] = 0f
        right[2] = fwd[0]
        norm(right)
        up[0] = right[1] * fwd[2] - right[2] * fwd[1]
        up[1] = right[2] * fwd[0] - right[0] * fwd[2]
        up[2] = right[0] * fwd[1] - right[1] * fwd[0]
    }

    /** Projette un point monde ; renvoie false s'il est derrière la caméra. */
    private val tmp = FloatArray(3)

    private fun toCam(x: Float, y: Float, z: Float, out: FloatArray) {
        val dx = x - cam[0]
        val dy = y - cam[1]
        val dz = z - cam[2]
        out[0] = dx * right[0] + dy * right[1] + dz * right[2]
        out[1] = dx * up[0] + dy * up[1] + dz * up[2]
        out[2] = dx * fwd[0] + dy * fwd[1] + dz * fwd[2]
    }

    private fun px(cx: Float, cz: Float) = VW / 2f + FOCAL * cx / cz

    private fun py(cy: Float, cz: Float) = VH / 2f - FOCAL * cy / cz

    // ----------------------------------------------------------------- décor

    private fun drawBackground(c: Canvas, accent: Int, rpmFrac: Float) {
        paint.shader = LinearGradient(
            0f, 0f, 0f, VH,
            intArrayOf(Color.rgb(6, 8, 12), mix(Color.rgb(12, 16, 24), accent, 0.10f + 0.15f * rpmFrac), Color.rgb(5, 6, 9)),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP,
        )
        c.drawRect(0f, 0f, VW, VH, paint)
        paint.shader = null
    }

    private fun drawGrid(c: Canvas, accent: Int) {
        val a = FloatArray(3)
        val b = FloatArray(3)
        line.strokeWidth = 1.4f
        // Lignes dans l'axe de la route
        var k = -8
        while (k <= 8) {
            val z = k * 1.3f
            gridLine(c, -40f, z, 40f, z, accent, a, b)
            k++
        }
        // Lignes en travers, qui défilent quand on roule
        val step = 2.6f
        val off = odo % step
        var j = -14
        while (j <= 14) {
            val x = j * step - off
            gridLine(c, x, -11f, x, 11f, accent, a, b)
            j++
        }
    }

    private fun gridLine(c: Canvas, x0: Float, z0: Float, x1: Float, z1: Float, accent: Int, a: FloatArray, b: FloatArray) {
        toCam(x0, 0f, z0, a)
        toCam(x1, 0f, z1, b)
        val near = 0.6f
        if (a[2] < near && b[2] < near) return
        if (a[2] < near) clip(a, b, near)
        if (b[2] < near) clip(b, a, near)
        val dist = (a[2] + b[2]) / 2f
        val alpha = ((1f - dist / 26f) * 150f).toInt().coerceIn(0, 150)
        if (alpha <= 4) return
        line.color = withAlpha(accent, alpha)
        c.drawLine(px(a[0], a[2]), py(a[1], a[2]), px(b[0], b[2]), py(b[1], b[2]), line)
    }

    private fun clip(p: FloatArray, q: FloatArray, near: Float) {
        val k = (near - p[2]) / (q[2] - p[2])
        p[0] += (q[0] - p[0]) * k
        p[1] += (q[1] - p[1]) * k
        p[2] = near
    }

    private fun drawShadow(c: Canvas, accent: Int, rpmFrac: Float) {
        // Lueur néon sous la voiture, puis ombre portée
        ellipse(c, 2.9f, 1.25f, withAlpha(accent, (40 + 60 * rpmFrac).toInt().coerceAtMost(110)))
        ellipse(c, 2.45f, 0.98f, Color.argb(170, 0, 0, 0))
    }

    private fun ellipse(c: Canvas, rx: Float, rz: Float, color: Int) {
        path.reset()
        val n = 28
        for (i in 0..n) {
            val a = i * 2f * PI.toFloat() / n
            toCam(rx * cos(a), 0.005f, rz * sin(a), tmp)
            if (tmp[2] < 0.6f) return
            val x = px(tmp[0], tmp[2])
            val y = py(tmp[1], tmp[2])
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        paint.color = color
        c.drawPath(path, paint)
    }

    // --------------------------------------------------------------- voiture

    private fun drawCar(c: Canvas, accent: Int, rpmFrac: Float, t: Float) {
        // Roues de l'image courante
        var wn = 0
        for ((ax, side) in listOf(
            CarModel.AXLE_F to 1f, CarModel.AXLE_F to -1f, CarModel.AXLE_R to 1f, CarModel.AXLE_R to -1f,
        )) {
            wn += CarModel.wheel(ax, side, wheelAngle, wPos, wNrm, wMat, wn)
        }

        val ar = Color.red(accent) / 255f
        val ag = Color.green(accent) / 255f
        val ab = Color.blue(accent) / 255f
        val tailGlow = 0.85f + 0.15f * sin(t * 6f) * rpmFrac

        var n = 0
        val ca = FloatArray(3)
        for (tIdx in 0 until CarModel.count + wn) {
            val body = tIdx < CarModel.count
            val i = if (body) tIdx else tIdx - CarModel.count
            val p = if (body) CarModel.pos else wPos
            val nr = if (body) CarModel.nrm else wNrm
            val material = if (body) CarModel.mat[i] else wMat[i]
            val o = i * 9
            val nx = nr[i * 3]
            val ny = nr[i * 3 + 1]
            val nz = nr[i * 3 + 2]

            // Vecteur vers la caméra depuis le centre de la face
            val gx = (p[o] + p[o + 3] + p[o + 6]) / 3f
            val gy = (p[o + 1] + p[o + 4] + p[o + 7]) / 3f
            val gz = (p[o + 2] + p[o + 5] + p[o + 8]) / 3f
            var vx = cam[0] - gx
            var vy = cam[1] - gy
            var vz = cam[2] - gz
            val vl = sqrt(vx * vx + vy * vy + vz * vz)
            vx /= vl
            vy /= vl
            vz /= vl
            val ndv = nx * vx + ny * vy + nz * vz
            if (ndv <= 0.01f) continue // face cachée

            // Projection des 3 sommets
            var depth = 0f
            var behind = false
            for (k in 0..2) {
                toCam(p[o + k * 3], p[o + k * 3 + 1], p[o + k * 3 + 2], ca)
                if (ca[2] < 0.3f) behind = true
                sx[n * 3 + k] = px(ca[0], ca[2])
                sy[n * 3 + k] = py(ca[1], ca[2])
                depth += ca[2]
            }
            if (behind) continue

            col[n] = shade(material, nx, ny, nz, vx, vy, vz, ndv, ar, ag, ab, tailGlow)
            // Clé de tri : profondeur (positive, donc ordre des bits = ordre des valeurs) + index
            keys[n] = (java.lang.Float.floatToIntBits(depth).toLong() shl 32) or n.toLong()
            n++
        }

        java.util.Arrays.sort(keys, 0, n)
        // Du plus loin au plus proche
        var v = 0
        for (r in n - 1 downTo 0) {
            val idx = (keys[r] and 0xffffffffL).toInt()
            for (k in 0..2) {
                verts[v * 6 + k * 2] = sx[idx * 3 + k]
                verts[v * 6 + k * 2 + 1] = sy[idx * 3 + k]
                colors[v * 3 + k] = col[idx]
            }
            v++
        }
        if (v > 0) {
            c.drawVertices(
                Canvas.VertexMode.TRIANGLES, v * 6, verts, 0, null, 0,
                colors, 0, null, 0, 0, vertexPaint,
            )
        }
    }

    private fun shade(
        material: Int,
        nx: Float, ny: Float, nz: Float,
        vx: Float, vy: Float, vz: Float,
        ndv: Float,
        ar: Float, ag: Float, ab: Float,
        tailGlow: Float,
    ): Int {
        val base: FloatArray
        var specK = 0.5f
        var rimK = 0.45f
        var emissive = false
        when (material) {
            CarModel.BODY -> base = BODY_BLUE
            CarModel.GLASS -> { base = GLASS_C; specK = 0.9f; rimK = 0.35f }
            CarModel.UNDER -> { base = UNDER_C; specK = 0f; rimK = 0f }
            CarModel.GRILLE -> { base = GRILLE_C; specK = 0.2f; rimK = 0.1f }
            CarModel.HEADLIGHT -> { base = HEAD_C; emissive = true }
            CarModel.TAILLIGHT -> { base = TAIL_C; emissive = true }
            CarModel.TIRE -> { base = TIRE_C; specK = 0.05f; rimK = 0.1f }
            CarModel.RIM -> { base = RIM_C; specK = 0.6f; rimK = 0.2f }
            else -> { base = RIM_DARK_C; specK = 0.3f; rimK = 0.1f }
        }
        if (emissive) {
            val g = if (material == CarModel.TAILLIGHT) tailGlow else 1f
            return Color.rgb(
                (base[0] * g).toInt().coerceIn(0, 255),
                (base[1] * g).toInt().coerceIn(0, 255),
                (base[2] * g).toInt().coerceIn(0, 255),
            )
        }
        val diff = max(0f, nx * L[0] + ny * L[1] + nz * L[2])
        // Demi-vecteur pour le reflet
        var hx = L[0] + vx
        var hy = L[1] + vy
        var hz = L[2] + vz
        val hl = sqrt(hx * hx + hy * hy + hz * hz)
        hx /= hl
        hy /= hl
        hz /= hl
        val spec = max(0f, nx * hx + ny * hy + nz * hz).pow(28) * specK * 255f
        val fres = (1f - ndv).pow(3) * rimK * 255f
        val light = 0.22f + 0.78f * diff
        return Color.rgb(
            (base[0] * light + spec + fres * ar).toInt().coerceIn(0, 255),
            (base[1] * light + spec + fres * ag).toInt().coerceIn(0, 255),
            (base[2] * light + spec + fres * ab).toInt().coerceIn(0, 255),
        )
    }

    // ---------------------------------------------------------------- effets

    private fun drawSpeedLines(c: Canvas, t: Float, k: Float) {
        val strength = k.coerceIn(0f, 1f)
        line.strokeWidth = 2f
        for (i in 0 until 14) {
            val ang = (i * 2.399f) % (2f * PI.toFloat())
            val phase = (t * (1.6f + strength * 1.4f) + i * 0.37f) % 1f
            val r0 = 170f + phase * 380f
            val len = 30f + 90f * phase * strength
            val cx = VW / 2f
            val cy = HORIZON_Y
            val dx = cos(ang)
            val dy = sin(ang) * 0.6f
            line.color = Color.argb((140 * (1f - phase) * strength).toInt().coerceIn(0, 140), 230, 240, 255)
            c.drawLine(cx + dx * r0, cy + dy * r0, cx + dx * (r0 + len), cy + dy * (r0 + len), line)
        }
    }

    private fun drawShiftFlash(c: Canvas, t: Float) {
        val on = (t * 8f).toInt() % 2 == 0
        if (!on) return
        line.strokeWidth = 10f
        line.color = Color.argb(200, 255, 60, 60)
        c.drawRect(5f, 5f, VW - 5f, VH - 5f, line)
    }

    // ---------------------------------------------------------------- outils

    fun accentColor(rpm: Int, redline: Int, live: Boolean): Int = Accent.color(rpm, redline, live)

    private fun norm(v: FloatArray) {
        val l = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        if (l > 1e-6f) {
            v[0] /= l
            v[1] /= l
            v[2] /= l
        }
    }

    private fun lerp(a: Float, b: Float, k: Float) = a + (b - a) * k

    private fun smooth(k: Float) = k * k * (3f - 2f * k)

    private fun lerpAngle(a: Float, b: Float, k: Float): Float {
        val twoPi = 2f * PI.toFloat()
        var d = (b - a) % twoPi
        if (d > PI) d -= twoPi
        if (d < -PI) d += twoPi
        return a + d * k
    }

    private fun mix(a: Int, b: Int, k: Float): Int {
        val f = k.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(a) + (Color.red(b) - Color.red(a)) * f).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * f).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f).toInt(),
        )
    }

    private fun withAlpha(color: Int, alpha: Int) =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
}
