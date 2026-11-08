package fr.preludedash.card

import fr.preludedash.data.Calibration
import fr.preludedash.data.EngineData
import fr.preludedash.link.DataHub
import fr.preludedash.music.MusicRelay
import fr.preludedash.shared.CardSnapshot
import fr.preludedash.shared.CardSnapshot.Level
import java.util.Locale
import kotlin.math.roundToInt

/** Construit la carte à partir des données moteur : l'essentiel, l'alerte la plus grave, et la musique. */
object CardSnapshots {
    fun from(
        d: EngineData,
        state: DataHub.State,
        ageMs: Long,
        cal: Calibration,
        music: MusicRelay.Now? = MusicRelay.now(),
    ): CardSnapshot {
        val demo = state == DataHub.State.DEMO
        val live = demo || (state == DataHub.State.CONNECTED && ageMs < 1500)
        if (!live) {
            return CardSnapshot.offline(
                title = music?.title ?: "Prelude Dash",
                subtitle = music?.artistLine ?: "ECU non connecté",
            )
        }

        val afrV = d.afr
        val lean = afrV != null && afrV > 15.0f && d.tpsPct > 60
        val afrText = afrV?.let { String.format(Locale.US, "%.1f", it) } ?: "--.-"
        val ect = d.ectC.roundToInt()
        val eth = d.ethanolPct?.roundToInt()

        // Une seule alerte à la fois, la plus grave d'abord
        val alert = when {
            lean -> "PAUVRE"
            ect > 105 -> "EAU $ect°C"
            d.mil -> "VOYANT MOTEUR"
            d.batteryV < 12.0f || d.batteryV > 15.2f ->
                "BATT " + String.format(Locale.US, "%.1f", d.batteryV) + " V"
            d.dutyPct > 90f -> "INJ ${d.dutyPct.roundToInt()} %"
            else -> null
        }
        val ectLevel = when {
            ect > 105 -> Level.ALERT
            ect > 100 -> Level.WARN
            ect < 60 -> Level.COLD
            else -> Level.NORMAL
        }
        val status = if (demo) "Mode démo" else "ECU connecté"
        return CardSnapshot(
            live = true,
            demo = demo,
            afr = afrText,
            afrLevel = if (lean) Level.ALERT else Level.NORMAL,
            ect = ect.toString(),
            ectLevel = ectLevel,
            eth = eth?.toString() ?: "--",
            alert = alert,
            // Les valeurs sont dans l'image : le texte sert à l'alerte, sinon à la musique
            title = when {
                alert != null -> "⚠ $alert"
                music != null -> music.title
                else -> "Prelude Dash"
            },
            subtitle = music?.artistLine ?: status,
            rpm = ((d.rpm + 25) / 50 * 50).toString(),
            rpmLevel = when {
                d.rpm >= cal.redline -> Level.ALERT
                d.rpm >= cal.redline - 1000 -> Level.WARN
                else -> Level.NORMAL
            },
            map = d.mapMbar.toString(),
            iat = d.iatC.roundToInt().toString() + "°",
            batt = String.format(Locale.US, "%.1f", d.batteryV),
            ign = d.ignDeg.roundToInt().toString() + "°",
            duty = d.dutyPct.roundToInt().toString() + "%",
        )
    }
}
