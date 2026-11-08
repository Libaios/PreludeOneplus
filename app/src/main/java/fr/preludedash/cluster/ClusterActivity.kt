package fr.preludedash.cluster

import android.app.Activity
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import fr.preludedash.MainActivity
import fr.preludedash.link.LinkManager
import fr.preludedash.ui.DashRenderer
import fr.preludedash.ui.DashView

/**
 * Le combiné : tableau de bord plein écran, en paysage, sur le téléphone fixé dans la casquette.
 *
 * Alim branchée (contact mis) : écran toujours allumé, liaison ECU active.
 * Alim coupée : 15 s de grâce (le démarreur coupe souvent l'alim une seconde), puis veille :
 * liaison coupée, écran au minimum, Android l'éteint à la fin de son délai de mise en veille.
 * Un appui change de page, un appui long ouvre les réglages.
 */
class ClusterActivity : Activity() {
    companion object {
        private const val SLEEP_DELAY_MS = 15_000L
    }

    private lateinit var dash: DashView
    private lateinit var banner: TextView
    private val ui = Handler(Looper.getMainLooper())

    private var plugged = false
    private var asleep = false
    private var linked = false
    private var sleepAt = 0L
    private var registered = false

    private val power = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_POWER_CONNECTED -> onPower(true)
                Intent.ACTION_POWER_DISCONNECTED -> onPower(false)
            }
        }
    }

    private val goToSleep = Runnable { sleep() }

    private val tick = object : Runnable {
        override fun run() {
            updateBanner()
            if (sleepAt > 0L) ui.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // S'affiche par-dessus l'écran de verrouillage et rallume l'écran
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
        dismissKeyguardIfInsecure()

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        dash = DashView(this, "cluster", fill = true)
        dash.setOnLongClickListener {
            openSettings()
            true
        }
        root.addView(dash, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        banner = TextView(this).apply {
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(DashRenderer.TEXT)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(28), dp(18), dp(28), dp(18))
            background = GradientDrawable().apply {
                setColor(Color.argb(215, 10, 12, 16))
                cornerRadius = dp(18).toFloat()
            }
            visibility = View.GONE
        }
        root.addView(banner, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER,
        ))
        setContentView(root)
        hideBars()
        ClusterService.cancelWakeNotice(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        ClusterService.cancelWakeNotice(this)
        if (Power.isPlugged(this)) onPower(true)
    }

    override fun onStart() {
        super.onStart()
        ClusterService.cancelWakeNotice(this)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        ContextCompat.registerReceiver(this, power, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        registered = true
        plugged = Power.isPlugged(this)
        // Ouvert à la main sur batterie (essai) : on reste allumé, seule une coupure d'alim met en veille
        wakeUp()
    }

    override fun onStop() {
        if (registered) {
            try {
                unregisterReceiver(power)
            } catch (_: Exception) {
            }
            registered = false
        }
        ui.removeCallbacks(goToSleep)
        ui.removeCallbacks(tick)
        sleepAt = 0L
        setLink(false)
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideBars()
    }

    // ---------------------------------------------------------- contact / veille

    private fun onPower(on: Boolean) {
        plugged = on
        if (on) wakeUp() else startCountdown()
    }

    private fun wakeUp() {
        ui.removeCallbacks(goToSleep)
        ui.removeCallbacks(tick)
        sleepAt = 0L
        asleep = false
        if (plugged) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        setBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
        dash.paused = false
        setLink(true)
        updateBanner()
    }

    private fun startCountdown() {
        if (asleep) return
        sleepAt = SystemClock.elapsedRealtime() + SLEEP_DELAY_MS
        ui.removeCallbacks(goToSleep)
        ui.postDelayed(goToSleep, SLEEP_DELAY_MS)
        ui.removeCallbacks(tick)
        ui.post(tick)
    }

    private fun sleep() {
        ui.removeCallbacks(tick)
        sleepAt = 0L
        asleep = true
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setLink(false)
        dash.paused = true
        updateBanner()
        // Écran éteint tout de suite si le droit est donné, sinon au minimum
        // (Android l'éteint alors à la fin de son délai de mise en veille)
        if (!ScreenOff.turnOff(this)) setBrightness(0f)
    }

    private fun updateBanner() {
        val text = when {
            asleep -> "Contact coupé\nEn veille"
            sleepAt > 0L -> {
                val left = ((sleepAt - SystemClock.elapsedRealtime() + 999) / 1000).coerceAtLeast(0)
                "Contact coupé : veille dans $left s\nRemets le contact pour annuler"
            }
            else -> null
        }
        if (text == null) {
            banner.visibility = View.GONE
        } else {
            banner.text = text
            banner.visibility = View.VISIBLE
        }
    }

    private fun setLink(on: Boolean) {
        if (on && !linked) {
            LinkManager.acquire(this, "cluster")
            linked = true
        } else if (!on && linked) {
            LinkManager.release("cluster")
            linked = false
        }
    }

    // ------------------------------------------------------------------ outils

    private fun setBrightness(v: Float) {
        val lp = window.attributes
        lp.screenBrightness = v
        window.attributes = lp
    }

    private fun hideBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val c = WindowCompat.getInsetsController(window, window.decorView)
        c.hide(WindowInsetsCompat.Type.systemBars())
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    /** Écran verrouillé sans code : on le déverrouille, pour sortir du combiné sans balayer. */
    private fun dismissKeyguardIfInsecure() {
        if (Build.VERSION.SDK_INT < 26) return
        val km = getSystemService(KeyguardManager::class.java) ?: return
        if (!km.isKeyguardSecure) km.requestDismissKeyguard(this, null)
    }

    private fun openSettings() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
}
