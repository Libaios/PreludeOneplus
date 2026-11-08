package fr.preludedash.data

/** Tous les réglages de l'appli. Valeurs par défaut adaptées à la Prelude. */
data class Calibration(
    val deviceAddress: String? = null,
    val deviceName: String? = null,
    val demo: Boolean = false,
    /** Octet de la trame qui porte la wideband. 2 = entrée sonde O2 (D14). */
    val afrByte: Int = 2,
    /** Conversion linéaire volts -> AFR. Défaut : AEM 30-0300 (0,5 V = 8,5 / 4,5 V = 18,0). */
    val afrV1: Float = 0.5f,
    val afrA1: Float = 8.5f,
    val afrV2: Float = 4.5f,
    val afrA2: Float = 18.0f,
    /** Octet qui porte l'éthanol (-1 = pas encore trouvé). 32 sur la Prelude (sortie Cobra). */
    val ethByte: Int = 32,
    val ethScale: Int = ETH_PERCENT,
    /** Correction comme dans HTS : % affiché = % lu x gain + offset. */
    val ethGain: Float = 1f,
    val ethOffset: Float = 14f,
    /** Plage du capteur MAP d'origine, en mbar, pour 0 V et 5 V. */
    val mapMin: Int = -70,
    val mapMax: Int = 1790,
    val redline: Int = 7000,
    /** Taille minimale d'une trame valide (ISR V3 = 52 octets). */
    val frameLen: Int = 52,
) {
    companion object {
        const val ETH_PERCENT = 0 // la valeur brute est directement le %
        const val ETH_255 = 1     // 0-255 = 0-100 %
        const val ETH_HALF = 2    // valeur / 2

        fun scaleName(scale: Int): String = when (scale) {
            ETH_255 -> "0-255 = 0-100 %"
            ETH_HALF -> "valeur / 2"
            else -> "valeur = %"
        }
    }
}
