package fr.preludedash.cluster

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Relance la veille du combiné après un redémarrage du téléphone (ou une mise à jour de l'appli).
 * Au démarrage, si l'alim est déjà là (tel vidé qui repart au contact), le combiné s'ouvre direct.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (!ClusterPrefs.enabled(ctx)) return
        val boot = intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ClusterService.start(ctx, launchIfPlugged = boot)
    }
}
