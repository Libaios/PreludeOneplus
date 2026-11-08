package fr.preludedash.ui.scene

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Prelude 5G (BB6, 1997-2001) en low-poly, construite par sections le long de la voiture.
 * Repère : x vers l'avant, y vers le haut, z vers la droite, origine au sol au milieu
 * de l'empattement. Cotes réelles : longueur 4,52 m, largeur 1,75 m, hauteur 1,31 m,
 * empattement 2,58 m, roues de 0,62 m.
 */
object CarModel {
    // Matériaux
    const val BODY = 0
    const val GLASS = 1
    const val UNDER = 2
    const val GRILLE = 3
    const val HEADLIGHT = 4
    const val TAILLIGHT = 5
    const val TIRE = 6
    const val RIM = 7
    const val RIM_DARK = 8

    const val WHEEL_R = 0.31f
    const val WHEEL_HALF_W = 0.10f
    const val WHEEL_Z = 0.76f
    const val AXLE_F = 1.29f
    const val AXLE_R = -1.29f

    /** Triangles fixes de la carrosserie : 9 coordonnées par triangle. */
    val pos: FloatArray

    /** Normale extérieure de chaque triangle. */
    val nrm: FloatArray

    /** Matériau de chaque triangle. */
    val mat: IntArray

    val count: Int

    // Sections le long de la voiture, de l'avant vers l'arrière
    private val STATIONS = floatArrayOf(
        2.26f, 2.18f, 2.10f, 1.95f, 1.71f, 1.59f, 1.45f, 1.29f, 1.13f, 0.99f, 0.87f,
        0.55f, 0.30f, 0.00f, -0.30f, -0.60f, -0.87f, -0.99f, -1.13f, -1.29f, -1.45f,
        -1.59f, -1.71f, -1.90f, -2.10f, -2.20f, -2.26f,
    )

    // Profils (x, valeur), x décroissant
    private val TOP = arrayOf(
        2.26f to .60f, 2.18f to .67f, 2.10f to .71f, 1.71f to .77f, 0.87f to .83f, 0.55f to .86f,
        0.00f to 1.29f, -0.60f to 1.31f, -0.92f to 1.24f, -1.50f to .99f, -2.10f to .97f, -2.26f to .92f,
    )
    private val BELT = arrayOf(
        2.26f to .50f, 2.10f to .60f, 1.71f to .70f, 0.87f to .76f, 0.55f to .80f, 0.00f to .84f,
        -0.92f to .87f, -1.50f to .88f, -2.10f to .88f, -2.26f to .84f,
    )
    private val W_TOP = arrayOf(
        2.26f to .48f, 2.10f to .68f, 1.71f to .72f, 0.87f to .72f, 0.55f to .70f, 0.00f to .56f,
        -0.92f to .56f, -1.50f to .66f, -2.10f to .68f, -2.26f to .62f,
    )
    private val W_BELT = arrayOf(2.26f to .62f, 2.10f to .82f, 1.71f to .85f, -1.71f to .86f, -2.10f to .84f, -2.26f to .76f)
    private val W_MAX = arrayOf(2.26f to .66f, 2.10f to .84f, 1.71f to .87f, -1.71f to .88f, -2.10f to .86f, -2.26f to .78f)
    private val Y_MID = arrayOf(2.26f to .40f, 2.10f to .42f, -1.90f to .48f, -2.10f to .56f, -2.26f to .64f)
    private val Y_BOTTOM = arrayOf(2.26f to .24f, 2.10f to .18f, -2.10f to .20f, -2.26f to .30f)
    private val W_BOTTOM = arrayOf(2.26f to .50f, 2.10f to .70f, 1.71f to .80f, -1.71f to .80f, -2.10f to .72f, -2.26f to .60f)

    private fun lerp(table: Array<Pair<Float, Float>>, x: Float): Float {
        if (x >= table.first().first) return table.first().second
        if (x <= table.last().first) return table.last().second
        for (i in 0 until table.size - 1) {
            val (x0, v0) = table[i]
            val (x1, v1) = table[i + 1]
            if (x <= x0 && x >= x1) return v0 + (v1 - v0) * (x0 - x) / (x0 - x1)
        }
        return table.last().second
    }

    /** Bas de caisse, relevé en arc de cercle au-dessus de chaque roue. */
    private fun bottom(x: Float): Float {
        var y = lerp(Y_BOTTOM, x)
        for (a in floatArrayOf(AXLE_F, AXLE_R)) {
            val d = abs(x - a)
            val r = 0.37f
            if (d < r) y = maxOf(y, WHEEL_R + sqrt(r * r - d * d))
        }
        return y
    }

    /** Demi-profil d'une section : 6 points (z, y) du milieu bas au milieu haut, côté droit. */
    private fun profile(x: Float): FloatArray {
        val yb = bottom(x)
        val inArch = yb > lerp(Y_BOTTOM, x) + 0.05f
        val wMax = lerp(W_MAX, x)
        val yMid = maxOf(lerp(Y_MID, x), yb)
        val w0 = if (inArch) wMax else lerp(W_BOTTOM, x)
        val yBelt = maxOf(lerp(BELT, x), yMid + 0.02f)
        val top = maxOf(lerp(TOP, x), yBelt + 0.02f)
        val crown = if (top > 1.1f) 0.02f else 0.03f
        return floatArrayOf(
            0f, yb,
            w0, yb,
            wMax, yMid,
            lerp(W_BELT, x), yBelt,
            lerp(W_TOP, x), top,
            0f, top + crown,
        )
    }

    private fun isWindshield(x0: Float, x1: Float) = x0 <= 0.56f && x1 >= -0.01f
    private fun isRearWindow(x0: Float, x1: Float) = x0 <= -0.91f && x1 >= -1.51f

    init {
        val p = ArrayList<Float>()
        val m = ArrayList<Int>()

        fun tri(a: FloatArray, b: FloatArray, c: FloatArray, material: Int) {
            for (v in arrayOf(a, b, c)) for (k in 0..2) p.add(v[k])
            m.add(material)
        }

        fun quad(a: FloatArray, b: FloatArray, c: FloatArray, d: FloatArray, material: Int) {
            tri(a, b, c, material)
            tri(a, c, d, material)
        }

        // Coque : quadrilatères entre deux sections, des deux côtés
        for (i in 0 until STATIONS.size - 1) {
            val x0 = STATIONS[i]
            val x1 = STATIONS[i + 1]
            val a = profile(x0)
            val b = profile(x1)
            val sideGlass = (a[9] - a[7] > 0.25f) && (b[9] - b[7] > 0.25f)
            for (s in 0 until 5) {
                val material = when (s) {
                    0 -> UNDER
                    2 -> if (x0 <= -2.19f) TAILLIGHT else BODY
                    3 -> when {
                        sideGlass -> GLASS
                        x1 >= 2.09f -> HEADLIGHT
                        else -> BODY
                    }
                    4 -> if (isWindshield(x0, x1) || isRearWindow(x0, x1)) GLASS else BODY
                    else -> BODY
                }
                for (side in floatArrayOf(1f, -1f)) {
                    val p00 = floatArrayOf(x0, a[s * 2 + 1], a[s * 2] * side)
                    val p01 = floatArrayOf(x0, a[s * 2 + 3], a[s * 2 + 2] * side)
                    val p10 = floatArrayOf(x1, b[s * 2 + 1], b[s * 2] * side)
                    val p11 = floatArrayOf(x1, b[s * 2 + 3], b[s * 2 + 2] * side)
                    quad(p00, p10, p11, p01, material)
                }
            }
        }

        // Faces avant et arrière, en bandes horizontales sur toute la largeur :
        // bandeau de feux arrière, phares aux coins avec calandre au milieu
        val cuts = floatArrayOf(1f, 0.38f, -0.38f, -1f)
        for ((x, front) in listOf(STATIONS.first() to true, STATIONS.last() to false)) {
            val pr = profile(x)
            for (k in 0 until 5) {
                for (part in 0 until 3) {
                    val outer = part != 1
                    val material = if (front) {
                        when (k) {
                            0 -> UNDER
                            1 -> GRILLE
                            3 -> if (outer) HEADLIGHT else GRILLE
                            else -> BODY
                        }
                    } else {
                        when (k) {
                            0 -> UNDER
                            2 -> TAILLIGHT
                            else -> BODY
                        }
                    }
                    val s0 = cuts[part]
                    val s1 = cuts[part + 1]
                    val a = floatArrayOf(x, pr[k * 2 + 1], pr[k * 2] * s0)
                    val b = floatArrayOf(x, pr[k * 2 + 1], pr[k * 2] * s1)
                    val c = floatArrayOf(x, pr[k * 2 + 3], pr[k * 2 + 2] * s1)
                    val d = floatArrayOf(x, pr[k * 2 + 3], pr[k * 2 + 2] * s0)
                    quad(a, b, c, d, material)
                }
            }
        }

        count = m.size
        pos = FloatArray(p.size) { p[it] }
        mat = IntArray(m.size) { m[it] }
        nrm = FloatArray(count * 3)

        // Normales orientées vers l'extérieur (loin de l'axe de la voiture)
        for (t in 0 until count) {
            val o = t * 9
            val ux = pos[o + 3] - pos[o]
            val uy = pos[o + 4] - pos[o + 1]
            val uz = pos[o + 5] - pos[o + 2]
            val vx = pos[o + 6] - pos[o]
            val vy = pos[o + 7] - pos[o + 1]
            val vz = pos[o + 8] - pos[o + 2]
            var nx = uy * vz - uz * vy
            var ny = uz * vx - ux * vz
            var nz = ux * vy - uy * vx
            val len = sqrt(nx * nx + ny * ny + nz * nz)
            if (len < 1e-6f) continue
            nx /= len
            ny /= len
            nz /= len
            val cx = (pos[o] + pos[o + 3] + pos[o + 6]) / 3f
            val cy = (pos[o + 1] + pos[o + 4] + pos[o + 7]) / 3f
            val cz = (pos[o + 2] + pos[o + 5] + pos[o + 8]) / 3f
            // Point de référence à l'intérieur de la voiture
            val rx = cx.coerceIn(-2.0f, 2.0f)
            val dot = nx * (cx - rx) + ny * (cy - 0.6f) + nz * cz
            val sign = if (dot < 0f) -1f else 1f
            nrm[t * 3] = nx * sign
            nrm[t * 3 + 1] = ny * sign
            nrm[t * 3 + 2] = nz * sign
        }
    }

    /**
     * Écrit les triangles d'une roue (bande de roulement, flanc, jante à 5 branches tournée
     * de [angle]) dans les tableaux fournis, à partir de [start]. Renvoie le nombre écrit.
     */
    fun wheel(
        cx: Float,
        side: Float,
        angle: Float,
        outPos: FloatArray,
        outNrm: FloatArray,
        outMat: IntArray,
        start: Int,
    ): Int {
        val seg = 15
        var t = start
        val zOut = (WHEEL_Z + WHEEL_HALF_W) * side
        val zIn = (WHEEL_Z - WHEEL_HALF_W) * side
        val rimR = 0.20f

        fun put(a: FloatArray, b: FloatArray, c: FloatArray, n: FloatArray, material: Int) {
            val o = t * 9
            for ((k, v) in arrayOf(a, b, c).withIndex()) {
                outPos[o + k * 3] = v[0]
                outPos[o + k * 3 + 1] = v[1]
                outPos[o + k * 3 + 2] = v[2]
            }
            outNrm[t * 3] = n[0]
            outNrm[t * 3 + 1] = n[1]
            outNrm[t * 3 + 2] = n[2]
            outMat[t] = material
            t++
        }

        for (i in 0 until seg) {
            val a0 = angle + i * (2 * Math.PI.toFloat() / seg)
            val a1 = angle + (i + 1) * (2 * Math.PI.toFloat() / seg)
            val c0 = cos(a0)
            val s0 = sin(a0)
            val c1 = cos(a1)
            val s1 = sin(a1)
            val am = (a0 + a1) / 2f
            // Bande de roulement
            val n = floatArrayOf(cos(am), sin(am), 0f)
            val o0 = floatArrayOf(cx + WHEEL_R * c0, WHEEL_R + WHEEL_R * s0, zOut)
            val o1 = floatArrayOf(cx + WHEEL_R * c1, WHEEL_R + WHEEL_R * s1, zOut)
            val i0 = floatArrayOf(cx + WHEEL_R * c0, WHEEL_R + WHEEL_R * s0, zIn)
            val i1 = floatArrayOf(cx + WHEEL_R * c1, WHEEL_R + WHEEL_R * s1, zIn)
            put(o0, i0, i1, n, TIRE)
            put(o0, i1, o1, n, TIRE)
            // Flanc (anneau) et jante (secteur), face extérieure
            val side3 = floatArrayOf(0f, 0f, side)
            val r0 = floatArrayOf(cx + rimR * c0, WHEEL_R + rimR * s0, zOut)
            val r1 = floatArrayOf(cx + rimR * c1, WHEEL_R + rimR * s1, zOut)
            put(o0, r0, r1, side3, TIRE)
            put(o0, r1, o1, side3, TIRE)
            val hub = floatArrayOf(cx, WHEEL_R, zOut + 0.01f * side)
            put(hub, r0, r1, side3, if (i % 3 == 0) RIM else RIM_DARK)
        }
        return t - start
    }

    /** Nombre maximal de triangles pour les 4 roues. */
    const val WHEEL_TRIS = 4 * 15 * 5
}
