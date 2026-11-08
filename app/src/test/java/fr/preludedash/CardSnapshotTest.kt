package fr.preludedash

import fr.preludedash.card.CardSnapshots
import fr.preludedash.shared.CardSnapshot
import fr.preludedash.data.Calibration
import fr.preludedash.data.EngineData
import fr.preludedash.link.DataHub
import fr.preludedash.music.MusicRelay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CardSnapshotTest {

    private val cal = Calibration()

    private fun data(
        afr: Float? = 14.6f,
        ect: Float = 88f,
        tps: Int = 10,
        eth: Float? = 80f,
        batt: Float = 14.1f,
        mil: Boolean = false,
        duty: Float = 20f,
    ) = EngineData(
        rpm = 2500, ectC = ect, afr = afr, tpsPct = tps, ethanolPct = eth,
        batteryV = batt, mil = mil, dutyPct = duty,
    )

    @Test
    fun normal_values_are_short() {
        val s = CardSnapshots.from(data(), DataHub.State.CONNECTED, 100, cal, music = null)
        assertTrue(s.live)
        assertEquals("14.6", s.afr)
        assertEquals("88", s.ect)
        assertEquals("80", s.eth)
        assertNull(s.alert)
        assertEquals("Prelude Dash", s.title)
        assertEquals("ECU connecté", s.subtitle)
        assertEquals("2500", s.rpm)
    }

    @Test
    fun music_goes_in_the_text_lines() {
        val s = CardSnapshots.from(data(), DataHub.State.CONNECTED, 100, cal, music = MusicRelay.Now("Titre", "▶  Artiste"))
        assertEquals("Titre", s.title)
        assertEquals("▶  Artiste", s.subtitle)
    }

    @Test
    fun alert_takes_the_title_over_music() {
        val s = CardSnapshots.from(data(ect = 107f), DataHub.State.CONNECTED, 100, cal, music = MusicRelay.Now("Titre", "▶  Artiste"))
        assertEquals("⚠ EAU 107°C", s.title)
        assertEquals("▶  Artiste", s.subtitle)
    }

    @Test
    fun lean_under_load_is_the_top_alert() {
        val s = CardSnapshots.from(data(afr = 15.8f, tps = 90, ect = 107f), DataHub.State.CONNECTED, 100, cal, music = null)
        assertEquals("PAUVRE", s.alert)
        assertEquals(CardSnapshot.Level.ALERT, s.afrLevel)
        assertEquals("⚠ PAUVRE", s.title)
    }

    @Test
    fun lean_at_part_throttle_is_not_an_alert() {
        val s = CardSnapshots.from(data(afr = 15.8f, tps = 20), DataHub.State.CONNECTED, 100, cal, music = null)
        assertNull(s.alert)
    }

    @Test
    fun overheat_battery_mil_alerts() {
        assertEquals("EAU 107°C", CardSnapshots.from(data(ect = 107f), DataHub.State.CONNECTED, 100, cal, null).alert)
        assertEquals("BATT 11.6 V", CardSnapshots.from(data(batt = 11.6f), DataHub.State.CONNECTED, 100, cal, null).alert)
        assertEquals("VOYANT MOTEUR", CardSnapshots.from(data(mil = true), DataHub.State.CONNECTED, 100, cal, null).alert)
    }

    @Test
    fun stale_data_shows_dashes() {
        val s = CardSnapshots.from(data(), DataHub.State.CONNECTED, 5000, cal, music = null)
        assertFalse(s.live)
        assertEquals("--.-", s.afr)
        assertEquals("ECU non connecté", s.subtitle)
    }

    @Test
    fun same_values_give_equal_snapshots() {
        // La carte ne republie une image que si ce qui est affiché change
        val a = CardSnapshots.from(data(afr = 14.61f), DataHub.State.CONNECTED, 100, cal, null)
        val b = CardSnapshots.from(data(afr = 14.64f), DataHub.State.CONNECTED, 200, cal, null)
        assertEquals(a, b)
    }
}
