package fr.preludedash.data

/** Une "photo" des capteurs, décodée depuis une trame du datalog HTS. */
class EngineData(
    val rpm: Int = 0,
    val ectC: Float = 0f,
    val iatC: Float = 0f,
    val mapMbar: Int = 0,
    val baroMbar: Int = 0,
    val tpsPct: Int = 0,
    val o2Volt: Float = 0f,
    /** AFR échelle essence (comme HTS), null si hors plage. */
    val afr: Float? = null,
    /** % d'éthanol, null tant que l'octet n'est pas réglé. */
    val ethanolPct: Float? = null,
    val batteryV: Float = 0f,
    val ignDeg: Float = 0f,
    val injMs: Float = 0f,
    val dutyPct: Float = 0f,
    val vssKmh: Int = 0,
    val vtec: Boolean = false,
    val fan: Boolean = false,
    val mil: Boolean = false,
    val stftPct: Float = 0f,
    val ltftPct: Float = 0f,
    /** Trame brute reçue, pour l'écran de réglage. */
    val raw: ByteArray = ByteArray(0),
)
