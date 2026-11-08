package fr.preludedash.cluster

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/** Le téléphone sert-il de combiné ? Actif par défaut : cette appli est faite pour ça. */
object ClusterPrefs {
    private const val PREFS = "cluster"
    private const val KEY_ON = "enabled"

    fun enabled(ctx: Context): Boolean =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ON, true)

    fun setEnabled(ctx: Context, on: Boolean) {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ON, on)
            .apply()
    }
}

/** Alimentation : branchée = contact mis (l'alim USB est sur un + après contact). */
object Power {
    fun isPlugged(ctx: Context): Boolean {
        val battery = try {
            ctx.applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (_: Exception) {
            null
        } ?: return false
        return battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
    }
}
