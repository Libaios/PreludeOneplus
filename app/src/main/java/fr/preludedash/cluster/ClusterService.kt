package fr.preludedash.cluster

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import fr.preludedash.MainActivity
import fr.preludedash.R

/**
 * Veille du combiné : tourne en permanence (notification discrète) et attend l'arrivée
 * de l'alim. Contact mis = alim branchée : on réveille l'écran et on ouvre le combiné.
 *
 * Android ne prévient plus les applis fermées d'un branchement de chargeur : il faut un
 * service au premier plan pour l'entendre. Il redémarre tout seul avec le téléphone.
 */
class ClusterService : Service() {
    companion object {
        private const val TAG = "ClusterService"
        private const val CH_IDLE = "cluster_idle"
        private const val CH_WAKE = "cluster_wake"
        private const val ID_IDLE = 41
        private const val ID_WAKE = 42
        private const val EXTRA_LAUNCH = "launch"

        /** [launchIfPlugged] : ouvrir le combiné tout de suite si l'alim est déjà là (démarrage du tel). */
        fun start(ctx: Context, launchIfPlugged: Boolean = false) {
            val i = Intent(ctx, ClusterService::class.java).putExtra(EXTRA_LAUNCH, launchIfPlugged)
            try {
                ContextCompat.startForegroundService(ctx, i)
            } catch (e: Exception) {
                Log.w(TAG, "service de veille impossible à lancer", e)
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, ClusterService::class.java))
        }

        /** Réveille l'écran et met le combiné devant. */
        fun launchCluster(ctx: Context) {
            wakeScreen(ctx)
            val i = Intent(ctx, ClusterActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            // Ouverture directe : permise avec l'autorisation « affichage par-dessus les autres applis »
            if (Build.VERSION.SDK_INT < 29 || Settings.canDrawOverlays(ctx)) {
                try {
                    ctx.startActivity(i)
                } catch (e: Exception) {
                    Log.w(TAG, "ouverture directe refusée", e)
                }
            }
            // Filet de sécurité : notification plein écran, qui ouvre le combiné même écran verrouillé.
            // Le combiné l'efface dès qu'il s'affiche.
            showWakeNotice(ctx, i)
        }

        fun cancelWakeNotice(ctx: Context) {
            ctx.getSystemService(NotificationManager::class.java)?.cancel(ID_WAKE)
        }

        @Suppress("DEPRECATION")
        private fun wakeScreen(ctx: Context) {
            try {
                val pm = ctx.getSystemService(PowerManager::class.java) ?: return
                pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or
                        PowerManager.ON_AFTER_RELEASE,
                    "preludedash:wake",
                ).acquire(10_000L)
            } catch (e: Exception) {
                Log.w(TAG, "réveil de l'écran impossible", e)
            }
        }

        private fun showWakeNotice(ctx: Context, activity: Intent) {
            createChannels(ctx)
            val pi = PendingIntent.getActivity(
                ctx, 1, activity, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = NotificationCompat.Builder(ctx, CH_WAKE)
                .setSmallIcon(R.drawable.ic_launcher_fg)
                .setContentTitle("Prelude Combiné")
                .setContentText("Contact mis : ouverture du combiné")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
                .setFullScreenIntent(pi, true)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setTimeoutAfter(60_000L)
                .build()
            try {
                ctx.getSystemService(NotificationManager::class.java)?.notify(ID_WAKE, n)
            } catch (e: Exception) {
                Log.w(TAG, "notification plein écran refusée", e)
            }
        }

        private fun createChannels(ctx: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CH_IDLE) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CH_IDLE, "Combiné en veille", NotificationManager.IMPORTANCE_MIN).apply {
                        description = "Notification permanente : le combiné attend le contact"
                        setShowBadge(false)
                    },
                )
            }
            if (nm.getNotificationChannel(CH_WAKE) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CH_WAKE, "Allumage du combiné", NotificationManager.IMPORTANCE_HIGH).apply {
                        description = "Ouvre le combiné quand le contact est mis"
                        setSound(null, null)
                        enableVibration(false)
                        setShowBadge(false)
                    },
                )
            }
        }
    }

    private var registered = false

    private val power = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == Intent.ACTION_POWER_CONNECTED) launchCluster(this@ClusterService)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannels(this)
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, ID_IDLE, idleNotification(), type)
        ContextCompat.registerReceiver(
            this, power, IntentFilter(Intent.ACTION_POWER_CONNECTED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        registered = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!ClusterPrefs.enabled(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.getBooleanExtra(EXTRA_LAUNCH, false) == true && Power.isPlugged(this)) {
            launchCluster(this)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (registered) {
            try {
                unregisterReceiver(power)
            } catch (_: Exception) {
            }
            registered = false
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun idleNotification() = NotificationCompat.Builder(this, CH_IDLE)
        .setSmallIcon(R.drawable.ic_launcher_fg)
        .setContentTitle("Combiné prêt")
        .setContentText("S'allume quand le contact est mis")
        .setPriority(NotificationCompat.PRIORITY_MIN)
        .setOngoing(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .build()
}
