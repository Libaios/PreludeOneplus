package fr.preludedash

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.net.Uri
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import fr.preludedash.card.CardSnapshots
import fr.preludedash.cluster.ClusterActivity
import fr.preludedash.cluster.ClusterPrefs
import fr.preludedash.cluster.ClusterService
import fr.preludedash.cluster.Power
import fr.preludedash.cluster.ScreenOff
import fr.preludedash.data.CalStore
import fr.preludedash.data.Calibration
import fr.preludedash.data.IsrDecoder
import fr.preludedash.link.DataHub
import fr.preludedash.link.LinkManager
import fr.preludedash.music.MusicRelay
import fr.preludedash.shared.CardRenderer
import fr.preludedash.ui.DashRenderer
import fr.preludedash.ui.DashView
import java.util.Locale

/**
 * Écran téléphone : choix du module Bluetooth, aperçu du tableau de bord,
 * réglages (AFR, éthanol...) et trame brute pour trouver les bons octets.
 */
class MainActivity : Activity() {

    private lateinit var dash: DashView
    private lateinit var statusText: TextView
    private lateinit var deviceText: TextView
    private lateinit var demoButton: Button
    private lateinit var rawText: TextView
    private lateinit var candidatesBox: LinearLayout
    private lateinit var ethScaleGroup: RadioGroup
    private lateinit var cardPreview: ImageView
    private lateinit var cardText: TextView
    private lateinit var musicText: TextView
    private lateinit var clusterButton: Button
    private lateinit var clusterText: TextView
    private var cardBitmap: Bitmap? = null
    private val fields = mutableMapOf<String, EditText>()

    private val ui = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            updateTexts()
            ui.postDelayed(this, 300)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(buildUi())
        askPermissionIfNeeded()
    }

    override fun onStart() {
        super.onStart()
        LinkManager.acquire(this, "phone")
        if (ClusterPrefs.enabled(this)) ClusterService.start(this)
        MusicRelay.start(this)
        MusicRelay.refresh()
        ui.post(refresh)
    }

    override fun onStop() {
        ui.removeCallbacks(refresh)
        LinkManager.release("phone")
        super.onStop()
    }

    // ------------------------------------------------------------ permissions

    private fun hasBtPermission() = LinkManager.hasBtPermission(this)

    private fun askPermissionIfNeeded() {
        val wanted = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31 && !hasBtPermission()) wanted += Manifest.permission.BLUETOOTH_CONNECT
        // Notifications : c'est par elles que le combiné rallume l'écran quand le tel est verrouillé
        if (Build.VERSION.SDK_INT >= 33 && ClusterPrefs.enabled(this) && !hasNotifPermission()) {
            wanted += Manifest.permission.POST_NOTIFICATIONS
        }
        if (wanted.isNotEmpty()) requestPermissions(wanted.toTypedArray(), 1)
    }

    private fun hasNotifPermission(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Android 14+ : droit d'ouvrir le combiné en plein écran par-dessus le verrouillage. */
    private fun canFullScreen(): Boolean =
        Build.VERSION.SDK_INT < 34 ||
            getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() ?: true

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        LinkManager.restart(this)
    }

    // ----------------------------------------------------------------- écran

    private fun buildUi(): View {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(DashRenderer.BG)
            clipToPadding = false
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(col)
        val pad = dp(16)
        col.setPadding(pad, pad, pad, pad)
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val b = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(b.left, b.top, b.right, b.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(
                    insets.systemWindowInsetLeft,
                    insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight,
                    insets.systemWindowInsetBottom,
                )
            }
            insets
        }

        col.addView(text("Prelude Combiné  ${appVersion()}", 26f, DashRenderer.TEXT, bold = true))
        statusText = text("", 14f, DashRenderer.MUTED)
        col.addView(statusText)

        dash = DashView(this)
        col.addView(dash, lp(top = 12))

        demoButton = button("") { toggleDemo() }
        col.addView(row(button("Page suivante") { dash.page = (dash.page + 1) % DashRenderer.PAGES }, demoButton))

        // Module Bluetooth
        col.addView(section("Module Bluetooth de l'ECU"))
        deviceText = text("", 15f, DashRenderer.TEXT)
        col.addView(deviceText)
        col.addView(row(button("Choisir le module") { pickDevice() }, button("Reconnecter") { LinkManager.restart(this) }))

        // Combiné : 2e téléphone fixé dans la casquette
        col.addView(section("Combiné (2e téléphone)"))
        col.addView(hint(
            "Uniquement sur le téléphone qui sert de combiné. Il s'allume dès que l'alim arrive (contact mis), " +
                "se connecte à l'ECU, et se met en veille 15 s après la coupure.",
        ))
        clusterButton = button("") { toggleCluster() }
        col.addView(clusterButton, lp(top = 4))
        clusterText = text("", 15f, DashRenderer.TEXT)
        col.addView(clusterText, lp(top = 10))
        col.addView(row(
            button("Affichage par-dessus") { openOverlaySettings() },
            button("Batterie sans limite") { askBatteryExemption() },
        ))
        col.addView(row(
            button("Notifications plein écran") { fixNotifications() },
            button("Extinction de l'écran") { toggleScreenOff() },
        ))
        col.addView(button("Ouvrir le combiné") { startActivity(Intent(this, ClusterActivity::class.java)) }, lp(top = 8))
        col.addView(hint(
            "Réglages Android du téléphone combiné : verrouillage de l'écran sur « Aucun », mise en veille de " +
                "l'écran après 15 ou 30 s. Sur le combiné, un appui change de page, un appui long revient ici.",
        ))

        // Réglages
        val cal = CalStore.get(this)
        col.addView(section("Réglages"))
        col.addView(hint("Les valeurs par défaut collent à ta config : wideband AEM sur D14, capteur MAP d'origine."))
        col.addView(field("afrByte", "Octet de la wideband (D14 = 2)", cal.afrByte.toString(), decimal = false))
        col.addView(row(
            field("afrV1", "Tension basse (V)", fmt(cal.afrV1), decimal = true),
            field("afrA1", "AFR à cette tension", fmt(cal.afrA1), decimal = true),
        ))
        col.addView(row(
            field("afrV2", "Tension haute (V)", fmt(cal.afrV2), decimal = true),
            field("afrA2", "AFR à cette tension", fmt(cal.afrA2), decimal = true),
        ))
        col.addView(field("ethByte", "Octet éthanol (-1 = pas réglé)", cal.ethByte.toString(), decimal = false, signed = true))
        ethScaleGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        listOf(Calibration.ETH_PERCENT, Calibration.ETH_255, Calibration.ETH_HALF).forEach { scale ->
            ethScaleGroup.addView(RadioButton(this).apply {
                id = 1000 + scale
                text = Calibration.scaleName(scale)
                setTextColor(DashRenderer.TEXT)
            })
        }
        ethScaleGroup.check(1000 + cal.ethScale)
        col.addView(ethScaleGroup)
        col.addView(row(
            field("ethOffset", "Offset éthanol (%)", fmt(cal.ethOffset), decimal = true, signed = true),
            field("ethGain", "Gain éthanol (x)", fmt(cal.ethGain), decimal = true),
        ))
        col.addView(hint("Mets le même offset que dans HTS. Gain à 1 tant qu'un 2e point de mesure ne dit pas le contraire."))
        col.addView(row(
            field("redline", "Zone rouge (tr/min)", cal.redline.toString(), decimal = false),
            field("frameLen", "Taille de trame", cal.frameLen.toString(), decimal = false),
        ))
        col.addView(row(
            field("mapMin", "MAP à 0 V (mbar)", cal.mapMin.toString(), decimal = false, signed = true),
            field("mapMax", "MAP à 5 V (mbar)", cal.mapMax.toString(), decimal = false),
        ))
        col.addView(button("Enregistrer les réglages") { saveSettings() }, lp(top = 8))

        // Éthanol
        col.addView(section("Éthanol : caler sur HTS"))
        col.addView(hint(
            "ECU connecté, tape le % d'éthanol affiché dans HTS. Caler sur HTS règle l'offset pour afficher " +
                "la même valeur. Chercher retrouve l'octet si tu changes de config.",
        ))
        val ethInput = EditText(this).apply {
            hint = "ex : 78"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setTextColor(DashRenderer.TEXT)
            setHintTextColor(DashRenderer.MUTED)
        }
        col.addView(row(
            ethInput,
            button("Caler sur HTS") { calibrateEthanol(ethInput.text.toString()) },
            button("Chercher") { findEthanol(ethInput.text.toString()) },
        ))
        candidatesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(candidatesBox)

        // Trame brute
        col.addView(section("Trame brute (octet : valeur)"))
        rawText = text("", 13f, DashRenderer.TEXT).apply { typeface = Typeface.MONOSPACE }
        col.addView(rawText)

        // Carte à côté de Waze + Spotify
        col.addView(section("Carte à côté de Waze"))
        col.addView(hint(
            "La carte s'installe à part : appli Prelude Carte (même page Releases). Dans Android Auto, " +
                "elle prend la place de la carte musique à côté de Waze et ses boutons pilotent Spotify. Aperçu :",
        ))
        cardPreview = ImageView(this).apply { adjustViewBounds = true }
        col.addView(cardPreview, LinearLayout.LayoutParams(dp(200), dp(200)).apply {
            topMargin = dp(8)
            gravity = Gravity.CENTER_HORIZONTAL
        })
        cardText = text("", 13f, DashRenderer.TEXT)
        col.addView(cardText, lp(top = 6))
        musicText = text("", 14f, DashRenderer.TEXT)
        col.addView(musicText, lp(top = 10))
        col.addView(button("Autoriser l'accès aux notifications") { openNotificationAccess() }, lp(top = 6))
        col.addView(hint(
            "Indispensable pour piloter Spotify. Si l'option est grisée (« paramètre restreint ») : " +
                "Paramètres > Applications > Prelude Dash > menu ⋮ > Autoriser les paramètres restreints, puis recommence.",
        ))

        col.addView(section("Crédits"))
        col.addView(hint(
            "Modèle 3D : « Honda Prelude 1997 Free » par Black Snow (sketchfab.com/BlackSnow02), " +
                "licence CC BY 4.0, mis à l'échelle réelle, peinture retouchée. Décors (atelier, rue de nuit) " +
                "générés pour l'appli ; néon en police Poppins (SIL OFL). Rendu 3D : Google Filament (Apache 2.0).",
        ))

        col.addView(section("Android Auto"))
        col.addView(hint(
            "Plein écran : ouvre Prelude Dash depuis le lanceur Android Auto, le bouton Détails change de page. " +
                "Carte : ouvre Prelude Carte (source musique), puis lance Waze.",
        ))
        return scroll
    }

    private fun updateTexts() {
        val cal = CalStore.get(this)
        val st = when (DataHub.state) {
            DataHub.State.CONNECTED -> "${DataHub.message}   ${String.format(Locale.US, "%.0f", DataHub.hz)} trames/s"
            else -> DataHub.message
        }
        statusText.text = st
        deviceText.text = when {
            cal.demo -> "Mode démo actif (aucune liaison avec l'ECU)"
            cal.deviceAddress == null -> "Aucun module choisi"
            else -> "${cal.deviceName ?: "Module"}  (${cal.deviceAddress})"
        }
        demoButton.text = if (cal.demo) "Arrêter la démo" else "Mode démo"
        updateCluster(cal)

        // Aperçu de la carte et état du relais musique
        val snap = CardSnapshots.from(DataHub.data, DataHub.state, DataHub.ageMs(), cal)
        val bmp = CardRenderer.render(snap, 400)
        cardPreview.setImageBitmap(bmp)
        cardBitmap?.recycle()
        cardBitmap = bmp
        cardText.text = "${snap.title}\n${snap.subtitle}"
        musicText.text = when {
            !MusicRelay.hasAccess(this) -> "Spotify : accès aux notifications pas encore donné"
            MusicRelay.title == null -> "Spotify : accès OK, aucune musique en cours"
            else -> "Spotify : ${MusicRelay.line()}"
        }

        val raw = DataHub.data.raw
        rawText.text = if (raw.isEmpty()) {
            "Pas encore de trame reçue." + if (DataHub.lastFrameLen > 0) " Dernière réponse : ${DataHub.lastFrameLen} octets." else ""
        } else {
            val sb = StringBuilder()
            for (i in raw.indices) {
                sb.append(String.format(Locale.US, "%2d:%4d   ", i, IsrDecoder.u8(raw, i)))
                if (i % 4 == 3) sb.append('\n')
            }
            sb.append("\n${raw.size} octets reçus")
            sb.toString()
        }
    }

    /** État du combiné : ce qui est prêt et ce qui reste à autoriser. */
    private fun updateCluster(cal: Calibration) {
        val on = ClusterPrefs.enabled(this)
        clusterButton.text = if (on) "Tout couper (fin du test)" else "Réactiver le combiné"
        val lines = mutableListOf(
            check(on, "Mode combiné actif", "Mode combiné coupé"),
            check(cal.deviceAddress != null || cal.demo, "Module Bluetooth choisi", "Module Bluetooth à choisir (ou mode démo)"),
            check(hasBtPermission(), "Bluetooth autorisé", "Bluetooth à autoriser"),
            check(canOverlay(), "Affichage par-dessus autorisé", "Affichage par-dessus à autoriser"),
            check(batteryUnrestricted(), "Batterie sans limite", "Optimisation de la batterie à couper"),
        )
        if (Build.VERSION.SDK_INT >= 33) {
            lines += check(hasNotifPermission(), "Notifications autorisées", "Notifications à autoriser")
        }
        if (Build.VERSION.SDK_INT >= 34) {
            lines += check(canFullScreen(), "Notifications plein écran autorisées", "Notifications plein écran à autoriser")
        }
        lines += check(ScreenOff.isActive(this), "Extinction de l'écran autorisée", "Extinction de l'écran à autoriser")
        lines += if (Power.isPlugged(this)) "⚡ Alim branchée" else "🔋 Sur batterie"
        clusterText.text = lines.joinToString("\n")
    }

    private fun check(ok: Boolean, yes: String, no: String) = if (ok) "✓  $yes" else "✗  $no"

    private fun canOverlay() = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this)

    private fun batteryUnrestricted(): Boolean =
        getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) ?: true

    // --------------------------------------------------------------- actions

    private fun toggleCluster() {
        val on = !ClusterPrefs.enabled(this)
        ClusterPrefs.setEnabled(this, on)
        if (on) {
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
            }
            ClusterService.start(this)
            toast("Mode combiné activé : il s'allumera au branchement de l'alim")
        } else {
            // Tout couper : plus d'allumage au branchement ni au démarrage du tel, plus d'extinction
            ClusterService.stop(this)
            ClusterService.cancelWakeNotice(this)
            ScreenOff.remove(this) // sinon Android empêche aussi de désinstaller l'appli
            ClusterActivity.closeIfOpen()
            toast("Tout est coupé : le tel se comporte normalement. « Réactiver le combiné » pour reprendre.")
        }
        updateTexts()
    }

    /** Droit d'éteindre l'écran : le donner, ou le retirer s'il est déjà donné. */
    private fun toggleScreenOff() {
        if (ScreenOff.isActive(this)) {
            ScreenOff.remove(this)
            toast("Extinction retirée : l'écran baissera juste sa luminosité")
            return
        }
        try {
            startActivity(ScreenOff.requestIntent(this))
        } catch (_: Exception) {
            toast("Paramètres > Sécurité > Applis d'administration > Prelude Combiné")
        }
    }

    /** Notifications, puis (Android 14+) notifications plein écran : les deux servent à rallumer l'écran. */
    private fun fixNotifications() {
        when {
            !hasNotifPermission() -> {
                if (shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) ||
                    getSharedPreferences("cluster", MODE_PRIVATE).getBoolean("notifAsked", false).not()
                ) {
                    getSharedPreferences("cluster", MODE_PRIVATE).edit().putBoolean("notifAsked", true).apply()
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
                } else {
                    openAppNotificationSettings()
                }
            }
            !canFullScreen() -> try {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
            } catch (_: Exception) {
                toast("Paramètres > Applications > Accès spécial > Notifications plein écran")
            }
            else -> toast("Notifications déjà autorisées")
        }
    }

    private fun openAppNotificationSettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
            )
        } catch (_: Exception) {
            toast("Paramètres > Applications > Prelude Combiné > Notifications")
        }
    }

    private fun openOverlaySettings() {
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            toast("Paramètres > Applications > Prelude Combiné > Afficher par-dessus les autres applis")
        }
    }

    @SuppressLint("BatteryLife")
    private fun askBatteryExemption() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
                toast("Paramètres > Batterie > Optimisation : Prelude Combiné sur « Ne pas optimiser »")
            }
        }
    }

    private fun toggleDemo() {
        val cal = CalStore.get(this)
        CalStore.save(this, cal.copy(demo = !cal.demo))
        LinkManager.restart(this)
        updateTexts()
    }

    @SuppressLint("MissingPermission")
    private fun pickDevice() {
        if (!hasBtPermission()) {
            askPermissionIfNeeded()
            return
        }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) {
            toast("Pas de Bluetooth sur ce téléphone")
            return
        }
        if (!adapter.isEnabled) {
            toast("Active le Bluetooth du téléphone")
            return
        }
        val devices = adapter.bondedDevices.toList().sortedBy { it.name ?: "" }
        if (devices.isEmpty()) {
            AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Aucun appareil appairé")
                .setMessage(
                    "Contact mis, appaire d'abord le module dans les réglages Bluetooth du téléphone " +
                        "(code 1234), puis reviens ici.",
                )
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val names = devices.map { "${it.name ?: "Sans nom"}\n${it.address}" }.toTypedArray()
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Module Bluetooth de l'ECU")
            .setItems(names) { _, i ->
                val d = devices[i]
                CalStore.save(this, CalStore.get(this).copy(deviceAddress = d.address, deviceName = d.name, demo = false))
                LinkManager.restart(this)
                updateTexts()
            }
            .show()
    }

    private fun saveSettings() {
        val c = CalStore.get(this)
        val scaleId = ethScaleGroup.checkedRadioButtonId
        val updated = c.copy(
            afrByte = int("afrByte", c.afrByte).coerceIn(-1, 255),
            afrV1 = float("afrV1", c.afrV1),
            afrA1 = float("afrA1", c.afrA1),
            afrV2 = float("afrV2", c.afrV2),
            afrA2 = float("afrA2", c.afrA2),
            ethByte = int("ethByte", c.ethByte).coerceIn(-1, 255),
            ethScale = if (scaleId >= 1000) scaleId - 1000 else c.ethScale,
            ethOffset = float("ethOffset", c.ethOffset).coerceIn(-100f, 100f),
            ethGain = float("ethGain", c.ethGain).let { if (it in 0.2f..5f) it else c.ethGain },
            redline = int("redline", c.redline).coerceIn(3000, 11000),
            frameLen = int("frameLen", c.frameLen).coerceIn(8, 255),
            mapMin = int("mapMin", c.mapMin),
            mapMax = int("mapMax", c.mapMax),
        )
        CalStore.save(this, updated)
        toast("Réglages enregistrés")
    }

    private fun findEthanol(input: String) {
        val target = input.replace(',', '.').trim().toFloatOrNull()
        if (target == null) {
            toast("Tape le % d'éthanol affiché dans HTS")
            return
        }
        val raw = DataHub.data.raw
        candidatesBox.removeAllViews()
        if (raw.isEmpty()) {
            candidatesBox.addView(hint("Pas de trame reçue : connecte d'abord l'ECU (contact mis)."))
            return
        }
        val cal = CalStore.get(this)
        val found = IsrDecoder.findEthanolCandidates(raw, target, cal.ethGain, cal.ethOffset)
        if (found.isEmpty()) {
            candidatesBox.addView(hint("Aucun octet ne correspond. Vérifie que la sortie éthanol vers le datalog est activée dans CobraRTP Utility."))
            return
        }
        found.take(15).forEach { (index, scale) ->
            val v = IsrDecoder.u8(raw, index)
            candidatesBox.addView(button("Octet $index = $v  (${Calibration.scaleName(scale)})") {
                CalStore.save(this, CalStore.get(this).copy(ethByte = index, ethScale = scale))
                fields["ethByte"]?.setText(index.toString())
                ethScaleGroup.check(1000 + scale)
                toast("Éthanol réglé sur l'octet $index")
            }, lp(top = 4))
        }
    }

    /** "v11" pour la version 1.0.11 : permet de vérifier une mise à jour d'un coup d'oeil. */
    private fun appVersion(): String = try {
        "v" + (packageManager.getPackageInfo(packageName, 0).versionName ?: "?").substringAfterLast('.')
    } catch (_: Exception) {
        ""
    }

    private fun openNotificationAccess() {
        try {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } catch (_: Exception) {
            toast("Ouvre Paramètres > Notifications > Accès aux notifications")
        }
    }

    private fun calibrateEthanol(input: String) {
        val target = input.replace(',', '.').trim().toFloatOrNull()
        if (target == null) {
            toast("Tape le % d'éthanol affiché dans HTS")
            return
        }
        val cal = CalStore.get(this)
        val raw = DataHub.data.raw
        if (raw.isEmpty()) {
            toast("Connecte d'abord l'ECU (contact mis)")
            return
        }
        val offset = IsrDecoder.offsetFor(raw, cal, target)
        if (offset == null) {
            toast("Règle d'abord l'octet éthanol")
            return
        }
        CalStore.save(this, cal.copy(ethOffset = offset))
        fields["ethOffset"]?.setText(fmt(offset))
        toast("Offset éthanol réglé à ${fmt(offset)} %")
    }

    // ---------------------------------------------------------------- outils

    private fun int(key: String, def: Int): Int = fields[key]?.text?.toString()?.trim()?.toIntOrNull() ?: def

    private fun float(key: String, def: Float): Float =
        fields[key]?.text?.toString()?.replace(',', '.')?.trim()?.toFloatOrNull() ?: def

    private fun fmt(v: Float) = String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun lp(top: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(top) }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }

    private fun hint(s: String) = text(s, 13f, DashRenderer.MUTED).apply { setPadding(0, dp(4), 0, dp(4)) }

    private fun section(title: String) = text(title.uppercase(Locale.FRENCH), 13f, DashRenderer.TEAL, bold = true).apply {
        letterSpacing = 0.1f
        setPadding(0, dp(24), 0, dp(6))
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(DashRenderer.TEXT)
        background = GradientDrawable().apply {
            setColor(DashRenderer.PANEL)
            cornerRadius = dp(10).toFloat()
        }
        setOnClickListener { onClick() }
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        views.forEachIndexed { i, v ->
            val p = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            p.topMargin = dp(8)
            if (i > 0) p.leftMargin = dp(8)
            addView(v, p)
        }
    }

    private fun field(key: String, label: String, value: String, decimal: Boolean, signed: Boolean = false): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(text(label, 12f, DashRenderer.MUTED))
        val edit = EditText(this).apply {
            setText(value)
            setTextColor(DashRenderer.TEXT)
            var type = InputType.TYPE_CLASS_NUMBER
            if (decimal) type = type or InputType.TYPE_NUMBER_FLAG_DECIMAL
            if (signed) type = type or InputType.TYPE_NUMBER_FLAG_SIGNED
            inputType = type
        }
        fields[key] = edit
        box.addView(edit)
        return box
    }
}
