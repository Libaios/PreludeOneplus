package fr.preludedash.data

import android.content.Context

/** Lecture / sauvegarde des réglages, avec un cache mémoire (lu à chaque image). */
object CalStore {
    private const val PREFS = "dash"

    @Volatile
    private var cached: Calibration? = null

    fun get(ctx: Context): Calibration = cached ?: load(ctx).also { cached = it }

    fun save(ctx: Context, c: Calibration) {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("deviceAddress", c.deviceAddress)
            .putString("deviceName", c.deviceName)
            .putBoolean("demo", c.demo)
            .putInt("afrByte", c.afrByte)
            .putFloat("afrV1", c.afrV1)
            .putFloat("afrA1", c.afrA1)
            .putFloat("afrV2", c.afrV2)
            .putFloat("afrA2", c.afrA2)
            .putInt("ethByte", c.ethByte)
            .putInt("ethScale", c.ethScale)
            .putFloat("ethGain", c.ethGain)
            .putFloat("ethOffset", c.ethOffset)
            .putInt("mapMin", c.mapMin)
            .putInt("mapMax", c.mapMax)
            .putInt("redline", c.redline)
            .putInt("frameLen", c.frameLen)
            .apply()
        cached = c
    }

    private fun load(ctx: Context): Calibration {
        val p = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val d = Calibration()
        return Calibration(
            deviceAddress = p.getString("deviceAddress", d.deviceAddress),
            deviceName = p.getString("deviceName", d.deviceName),
            demo = p.getBoolean("demo", d.demo),
            afrByte = p.getInt("afrByte", d.afrByte),
            afrV1 = p.getFloat("afrV1", d.afrV1),
            afrA1 = p.getFloat("afrA1", d.afrA1),
            afrV2 = p.getFloat("afrV2", d.afrV2),
            afrA2 = p.getFloat("afrA2", d.afrA2),
            ethByte = p.getInt("ethByte", d.ethByte),
            ethScale = p.getInt("ethScale", d.ethScale),
            ethGain = p.getFloat("ethGain", d.ethGain),
            ethOffset = p.getFloat("ethOffset", d.ethOffset),
            mapMin = p.getInt("mapMin", d.mapMin),
            mapMax = p.getInt("mapMax", d.mapMax),
            redline = p.getInt("redline", d.redline),
            frameLen = p.getInt("frameLen", d.frameLen),
        )
    }
}
