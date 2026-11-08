package fr.preludedash.cluster

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Droit d'éteindre l'écran : Android ne le donne qu'aux applis "d'administration de l'appareil".
 * On ne demande que ce droit-là (verrouiller l'écran), rien d'autre.
 */
class ScreenOffAdmin : DeviceAdminReceiver()

object ScreenOff {
    private const val TAG = "ScreenOff"

    fun component(ctx: Context) = ComponentName(ctx.applicationContext, ScreenOffAdmin::class.java)

    private fun dpm(ctx: Context) = ctx.getSystemService(DevicePolicyManager::class.java)

    fun isActive(ctx: Context): Boolean = dpm(ctx)?.isAdminActive(component(ctx)) == true

    /** Écran d'Android qui demande l'autorisation, avec l'explication. */
    fun requestIntent(ctx: Context): Intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
        .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component(ctx))
        .putExtra(
            DevicePolicyManager.EXTRA_ADD_EXPLANATION,
            "Permet au combiné d'éteindre l'écran 15 s après la coupure du contact. Rien d'autre.",
        )

    /** Retire le droit (sinon Android empêche de désinstaller l'appli). */
    fun remove(ctx: Context) {
        try {
            if (isActive(ctx)) dpm(ctx)?.removeActiveAdmin(component(ctx))
        } catch (e: Exception) {
            Log.w(TAG, "retrait impossible", e)
        }
    }

    /** Éteint l'écran tout de suite. false si le droit n'est pas donné. */
    fun turnOff(ctx: Context): Boolean {
        if (!isActive(ctx)) return false
        return try {
            dpm(ctx)?.lockNow()
            true
        } catch (e: Exception) {
            Log.w(TAG, "extinction impossible", e)
            false
        }
    }
}
