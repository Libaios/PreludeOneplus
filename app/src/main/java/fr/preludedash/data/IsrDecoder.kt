package fr.preludedash.data

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Décodage du protocole de datalog ISR V3 (eCtune / HTS) sur ECU OBD1 Honda.
 *
 * Fonctionnement : on envoie un espace (0x20) sur le CN2, l'ECU répond une trame
 * de 52 octets. Positions et conversions reprises du projet open source
 * BMDatalogger (bouletmarc), qui lit ce même protocole.
 */
object IsrDecoder {
    const val REQUEST: Byte = 0x20
    const val FRAME_LEN = 52

    // Positions dans la trame
    const val B_ECT = 0
    const val B_IAT = 1
    const val B_O2 = 2
    const val B_BARO = 3
    const val B_MAP = 4
    const val B_TPS = 5
    const val B_RPM = 6      // 16 bits, octet bas puis haut
    const val B_VSS = 16
    const val B_INJ = 17     // 16 bits
    const val B_IGN = 19
    const val B_FLAGS_A = 23 // bit 7 = VTEC, bit 5 = MIL
    const val B_BATT = 25
    const val B_STFT = 27    // 16 bits
    const val B_LTFT = 29    // 16 bits
    const val B_FLAGS_B = 39 // bit 6 = ventilo

    fun u8(f: ByteArray, i: Int): Int = if (i in f.indices) f[i].toInt() and 0xFF else 0

    fun u16(f: ByteArray, i: Int): Int = u8(f, i) or (u8(f, i + 1) shl 8)

    fun bit(f: ByteArray, i: Int, n: Int): Boolean = (u8(f, i) shr n) and 1 == 1

    /** Thermistance Honda (eau / admission) : polynôme en °F converti en °C. */
    fun tempC(raw: Int): Float {
        val x = raw / 51.0
        val f = 0.1423 * x.pow(6) - 2.4938 * x.pow(5) + 17.837 * x.pow(4) -
            68.698 * x.pow(3) + 154.69 * x.pow(2) - 232.75 * x + 284.24
        return ((f - 32.0) * 5.0 / 9.0).toFloat().coerceIn(-40f, 140f)
    }

    fun rpm(raw16: Int): Int {
        if (raw16 == 0 || raw16 >= 0xFF00) return 0 // moteur arrêté
        return (1851562 / raw16).coerceIn(0, 11000)
    }

    fun volts(raw: Int): Float = raw * 5f / 255f

    fun mapMbar(raw: Int, min: Int, max: Int): Int = min + raw * (max - min) / 255

    fun tps(raw: Int): Int = ((raw - 25) / 2.04).roundToInt().coerceIn(0, 100)

    fun baro(raw: Int): Int = ((raw / 2 + 24) * 7.221 - 59.0).roundToInt()

    fun injMs(raw16: Int): Float = raw16 * 3.2f / 1000f

    fun trimPct(raw16: Int): Float = if (raw16 == 0) 0f else (raw16 / 32768f - 1f) * 100f

    fun afr(volts: Float, cal: Calibration): Float? {
        if (cal.afrByte < 0) return null
        val dv = cal.afrV2 - cal.afrV1
        if (abs(dv) < 0.001f) return null
        val a = cal.afrA1 + (volts - cal.afrV1) * (cal.afrA2 - cal.afrA1) / dv
        return if (a in 5f..30f) a else null
    }

    /** % d'éthanol brut, avant correction (ce que HTS afficherait sans offset). */
    fun ethanolBase(f: ByteArray, cal: Calibration): Float? {
        if (cal.ethByte < 0 || cal.ethByte >= f.size) return null
        val v = u8(f, cal.ethByte)
        return scaleEth(v, cal.ethScale)
    }

    /** % d'éthanol corrigé : brut x gain + offset, comme le réglage de HTS. */
    fun ethanol(f: ByteArray, cal: Calibration): Float? {
        val base = ethanolBase(f, cal) ?: return null
        return (base * cal.ethGain + cal.ethOffset).coerceIn(0f, 100f)
    }

    /** Offset à mettre pour que l'appli affiche [targetPct] (le % lu dans HTS). */
    fun offsetFor(f: ByteArray, cal: Calibration, targetPct: Float): Float? {
        val base = ethanolBase(f, cal) ?: return null
        return targetPct - base * cal.ethGain
    }

    private fun scaleEth(v: Int, scale: Int): Float = when (scale) {
        Calibration.ETH_255 -> v / 2.55f
        Calibration.ETH_HALF -> v / 2f
        else -> v.toFloat()
    }

    fun decode(f: ByteArray, cal: Calibration): EngineData {
        val rpm = rpm(u16(f, B_RPM))
        val inj = injMs(u16(f, B_INJ))
        val o2v = volts(u8(f, if (cal.afrByte >= 0) cal.afrByte else B_O2))
        return EngineData(
            rpm = rpm,
            ectC = tempC(u8(f, B_ECT)),
            iatC = tempC(u8(f, B_IAT)),
            mapMbar = mapMbar(u8(f, B_MAP), cal.mapMin, cal.mapMax),
            baroMbar = baro(u8(f, B_BARO)),
            tpsPct = tps(u8(f, B_TPS)),
            o2Volt = o2v,
            afr = afr(o2v, cal),
            ethanolPct = ethanol(f, cal),
            batteryV = 26f * u8(f, B_BATT) / 270f,
            ignDeg = 0.25f * u8(f, B_IGN) - 6f,
            injMs = inj,
            dutyPct = (rpm * inj / 1200f).coerceIn(0f, 100f),
            vssKmh = u8(f, B_VSS),
            vtec = bit(f, B_FLAGS_A, 7),
            mil = bit(f, B_FLAGS_A, 5),
            fan = bit(f, B_FLAGS_B, 6),
            stftPct = trimPct(u16(f, B_STFT)),
            ltftPct = trimPct(u16(f, B_LTFT)),
            raw = f,
        )
    }

    /**
     * Cherche les octets qui pourraient porter l'éthanol, à partir du % lu dans HTS.
     * Le % de HTS inclut sa correction : on la retire avant de comparer aux valeurs brutes.
     */
    fun findEthanolCandidates(
        f: ByteArray,
        targetPct: Float,
        gain: Float = 1f,
        offset: Float = 0f,
    ): List<Pair<Int, Int>> {
        val g = if (abs(gain) < 0.01f) 1f else gain
        val raw = (targetPct - offset) / g
        val out = mutableListOf<Pair<Int, Int>>()
        for (i in f.indices) {
            val v = u8(f, i)
            if (abs(v - raw) <= 1f) out += i to Calibration.ETH_PERCENT
            if (abs(v / 2.55f - raw) <= 1.5f) out += i to Calibration.ETH_255
            if (abs(v / 2f - raw) <= 1f) out += i to Calibration.ETH_HALF
        }
        return out
    }
}
