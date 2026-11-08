package fr.preludedash.link

import android.os.SystemClock
import fr.preludedash.data.Calibration
import fr.preludedash.data.EngineData
import kotlin.math.exp
import kotlin.math.sin

/**
 * Fausses données pour voir l'interface sans la voiture, sur un cycle de 40 s :
 * ralenti à l'arrêt avec deux coups de gaz (atelier), départ et montée des rapports,
 * croisière, freinage avec rétrogradages, arrêt.
 */
class DemoSource {
    private val t0 = SystemClock.elapsedRealtime()

    private class Phase(val rpm: Int, val kmh: Float, val tps: Int, val decel: Boolean)

    // km/h pour 1000 tr/min sur chaque rapport (boîte de Prelude, approximatif)
    private val ratio = floatArrayOf(7.6f, 13.2f, 19.0f, 25.0f, 31.0f)

    private fun phase(t: Double, redline: Int): Phase {
        // 0-10 s : ralenti, coups de gaz à 3 s et 6 s
        if (t < 10.0) {
            var rpm = 850.0 + 25 * sin(t * 6)
            var tps = 0
            for ((at, peak) in listOf(3.0 to 4200.0, 6.0 to (redline - 300.0))) {
                val dt = t - at
                if (dt in 0.0..0.35) {
                    rpm += (peak - 850) * (dt / 0.35)
                    tps = 70
                } else if (dt > 0.35 && dt < 2.5) {
                    rpm += (peak - 850) * exp(-(dt - 0.35) * 2.8)
                }
            }
            return Phase(rpm.toInt(), 0f, tps, false)
        }
        // 10-22 s : départ et montée des rapports 1 à 4, passage à 6800 tr/min
        if (t < 22.0) {
            val shift = 6800
            val g = doubleArrayOf(2.0, 2.6, 3.4, 4.0) // durée de chaque rapport
            var start = 10.0
            var kmh = 0f
            for (i in 0..3) {
                val end = start + g[i]
                if (t < end) {
                    val k = ((t - start) / g[i]).toFloat()
                    val rpmStart = if (i == 0) 1600f else kmh / ratio[i] * 1000f
                    val top = if (i == 3) 6200f else shift.toFloat()
                    val rpm = rpmStart + (top - rpmStart) * k
                    val speed = if (i == 0) ratio[0] * shift / 1000f * k else rpm / 1000f * ratio[i]
                    val shifting = t - start < 0.25 && i > 0
                    return Phase(rpm.toInt(), speed, if (shifting) 0 else 100, false)
                }
                kmh = ratio[i] * (if (i == 3) 6200f else shift.toFloat()) / 1000f
                start = end
            }
        }
        // 22-28 s : croisière en 5e
        if (t < 28.0) {
            val kmh = 155f + 3f * sin(t * 0.8).toFloat()
            return Phase((kmh / ratio[4] * 1000f).toInt(), kmh, 22, false)
        }
        // 28-36 s : freinage, rétrogradages
        if (t < 36.0) {
            val k = ((t - 28.0) / 8.0).toFloat()
            val kmh = (155f * (1f - k) * (1f - k * 0.3f)).coerceAtLeast(0f)
            val gear = when {
                kmh > 110 -> 4
                kmh > 75 -> 3
                kmh > 45 -> 2
                kmh > 20 -> 1
                else -> -1
            }
            val rpm = if (gear < 0) 900 else (kmh / ratio[gear] * 1000f).toInt().coerceAtLeast(1100)
            return Phase(rpm, kmh, 0, true)
        }
        // 36-40 s : arrêt au ralenti
        return Phase((850 + 25 * sin(t * 6)).toInt(), 0f, 0, false)
    }

    fun next(cal: Calibration): EngineData {
        val t = (SystemClock.elapsedRealtime() - t0) / 1000.0
        val p = phase(t % 40.0, cal.redline)
        val rpm = p.rpm
        val wot = p.tps >= 90
        val tps = p.tps
        val afr = when {
            wot -> 11.9f + 0.25f * sin(t * 4).toFloat()
            p.decel -> 16.8f
            tps > 40 -> 12.8f
            else -> 14.6f + 0.35f * sin(t * 2).toFloat()
        }
        val map = when {
            wot -> 990
            p.decel -> 260
            tps > 40 -> 800
            tps > 0 -> 520
            else -> 330
        }
        val inj = when {
            wot -> 2.6f + rpm / 2500f
            p.decel -> 0f
            else -> 2.4f + tps / 60f
        }
        val ect = (45.0 + t * 1.5).coerceAtMost(88.0).toFloat()
        val o2v = cal.afrV1 + (afr - cal.afrA1) * (cal.afrV2 - cal.afrV1) / (cal.afrA2 - cal.afrA1)
        return EngineData(
            rpm = rpm,
            ectC = ect,
            iatC = 31f + (if (wot) 2f else 0f),
            mapMbar = map,
            baroMbar = 1013,
            tpsPct = tps,
            o2Volt = o2v,
            afr = afr,
            ethanolPct = 78f,
            batteryV = 14.1f + 0.1f * sin(t).toFloat(),
            ignDeg = if (wot) 22f + rpm / 1000f else 30f,
            injMs = inj,
            dutyPct = (rpm * inj / 1200f).coerceIn(0f, 100f),
            vssKmh = p.kmh.toInt(),
            vtec = false,
            fan = ect > 87f,
            mil = false,
            stftPct = 1.5f * sin(t * 1.3).toFloat(),
            ltftPct = -2.0f,
            raw = ByteArray(0),
        )
    }
}
