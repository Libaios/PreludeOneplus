package fr.preludedash

import fr.preludedash.data.Calibration
import fr.preludedash.data.IsrDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IsrDecoderTest {

    private fun frame(vararg pairs: Pair<Int, Int>): ByteArray {
        val f = ByteArray(IsrDecoder.FRAME_LEN)
        for ((i, v) in pairs) f[i] = v.toByte()
        return f
    }

    @Test
    fun rpm_from_16bit_period() {
        // 617 = 0x0269 -> octet bas 0x69, octet haut 0x02
        val f = frame(6 to 0x69, 7 to 0x02)
        assertEquals(3000.0, IsrDecoder.decode(f, Calibration()).rpm.toDouble(), 2.0)
    }

    @Test
    fun rpm_zero_when_engine_off() {
        val f = frame(6 to 0xFF, 7 to 0xFF)
        assertEquals(0, IsrDecoder.decode(f, Calibration()).rpm)
    }

    @Test
    fun temperatures() {
        val d = IsrDecoder.decode(frame(0 to 40, 1 to 150), Calibration())
        assertEquals(76.5, d.ectC.toDouble(), 0.5)
        assertEquals(20.0, d.iatC.toDouble(), 0.5)
    }

    @Test
    fun map_battery_tps() {
        val d = IsrDecoder.decode(frame(4 to 148, 25 to 140, 5 to 229), Calibration())
        assertEquals(1009, d.mapMbar)
        assertEquals(13.48, d.batteryV.toDouble(), 0.01)
        assertEquals(100, d.tpsPct)
    }

    @Test
    fun afr_with_aem_defaults() {
        // 102 / 255 * 5 = 2.0 V -> 2.375 * 2 + 7.3125 = 12.06
        val d = IsrDecoder.decode(frame(2 to 102), Calibration())
        assertNotNull(d.afr)
        assertEquals(12.06, d.afr!!.toDouble(), 0.02)
    }

    @Test
    fun ethanol_off_when_no_byte_then_scaled() {
        val f = frame(51 to 199)
        assertNull(IsrDecoder.decode(f, Calibration(ethByte = -1)).ethanolPct)
        val cal = Calibration(ethByte = 51, ethScale = Calibration.ETH_255, ethOffset = 0f)
        assertEquals(78.0, IsrDecoder.decode(f, cal).ethanolPct!!.toDouble(), 0.1)
    }

    @Test
    fun ethanol_default_is_byte_32_plus_14() {
        // La Prelude : 66 % brut dans l'octet 32 = 80 % dans HTS
        val f = frame(32 to 66)
        assertEquals(80.0, IsrDecoder.decode(f, Calibration()).ethanolPct!!.toDouble(), 0.01)
    }

    @Test
    fun ethanol_offset_and_gain_clamped() {
        val f = frame(32 to 95)
        val cal = Calibration(ethOffset = 14f, ethGain = 1f)
        assertEquals(100.0, IsrDecoder.decode(f, cal).ethanolPct!!.toDouble(), 0.01)
        val cal2 = Calibration(ethOffset = 0f, ethGain = 1.2f)
        assertEquals(79.2, IsrDecoder.decode(frame(32 to 66), cal2).ethanolPct!!.toDouble(), 0.01)
    }

    @Test
    fun offset_calibration_matches_hts() {
        val f = frame(32 to 66)
        val off = IsrDecoder.offsetFor(f, Calibration(), 80f)!!
        assertEquals(14.0, off.toDouble(), 0.01)
    }

    @Test
    fun ethanol_finder_spots_the_right_byte() {
        val f = frame(0 to 40, 44 to 78)
        val found = IsrDecoder.findEthanolCandidates(f, 78f)
        assertTrue(found.contains(44 to Calibration.ETH_PERCENT))
        // Avec l'offset de HTS : 80 % affiché = 66 brut
        val f2 = frame(32 to 66)
        assertTrue(IsrDecoder.findEthanolCandidates(f2, 80f, 1f, 14f).contains(32 to Calibration.ETH_PERCENT))
    }

    @Test
    fun flags() {
        val f = frame(23 to 0b1000_0000, 39 to 0b0100_0000)
        val d = IsrDecoder.decode(f, Calibration())
        assertTrue(d.vtec)
        assertTrue(d.fan)
        assertFalse(d.mil)
    }

    @Test
    fun short_frame_does_not_crash() {
        val d = IsrDecoder.decode(ByteArray(5), Calibration())
        assertEquals(0, d.vssKmh)
        assertNull(d.ethanolPct)
    }
}
