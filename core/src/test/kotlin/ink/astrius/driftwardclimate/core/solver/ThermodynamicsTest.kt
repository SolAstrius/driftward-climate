package ink.astrius.driftwardclimate.core.solver

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ThermodynamicsTest {

    @Test
    fun `theta equals temperature at sea level`() {
        assertEquals(288.15f, Thermodynamics.temperature(288.15f, 1f), 1e-3f)
        assertEquals(288.15f, Thermodynamics.theta(288.15f, 1f), 1e-3f)
    }

    @Test
    fun `theta-temperature roundtrip at altitude`() {
        val p = 0.6f
        val theta = 290f
        val t = Thermodynamics.temperature(theta, p)
        assertEquals(theta, Thermodynamics.theta(t, p), 1e-2f)
        assertTrue(t < theta, "actual T must be colder than θ aloft (p<1)")
    }

    @Test
    fun `constant theta gives a monotone lapse - colder with altitude`() {
        val theta = 288f
        var prev = Float.MAX_VALUE
        var p = 1.0f
        while (p > 0.2f) {
            val t = Thermodynamics.temperature(theta, p)
            assertTrue(t < prev, "T must fall as P falls")
            prev = t
            p -= 0.05f
        }
    }

    @Test
    fun `qSat at 20C and sea level is about 14,7 g per kg`() {
        val q = Thermodynamics.qSat(293.15f, Thermodynamics.SEA_LEVEL_KPA)
        assertTrue(q in 0.013f..0.016f, "qSat(20°C) = $q, expected ≈0.0147")
    }

    @Test
    fun `qSat roughly doubles per 10K (Clausius-Clapeyron)`() {
        val q10 = Thermodynamics.qSat(283.15f, Thermodynamics.SEA_LEVEL_KPA)
        val q20 = Thermodynamics.qSat(293.15f, Thermodynamics.SEA_LEVEL_KPA)
        val q30 = Thermodynamics.qSat(303.15f, Thermodynamics.SEA_LEVEL_KPA)
        assertTrue(q20 / q10 in 1.7f..2.1f, "10→20°C ratio ${q20 / q10}")
        assertTrue(q30 / q20 in 1.6f..2.0f, "20→30°C ratio ${q30 / q20}")
    }

    @Test
    fun `qSat increases as pressure drops - mountain air saturates easier per kg`() {
        val sea = Thermodynamics.qSat(283.15f, 101.325f)
        val mountain = Thermodynamics.qSat(283.15f, 60f)
        assertTrue(mountain > sea)
    }

    @Test
    fun `relative humidity is 1 exactly at saturation`() {
        val tK = 290f
        val q = Thermodynamics.qSat(tK, 101.325f)
        assertEquals(1f, Thermodynamics.relativeHumidity(q, tK, 101.325f), 1e-4f)
    }

    @Test
    fun `virtual theta - vapour lightens, cloud water loads`() {
        val theta = 300f
        assertTrue(Thermodynamics.virtualTheta(theta, 0.01f, 0f) > theta, "moist air must be lighter")
        assertTrue(Thermodynamics.virtualTheta(theta, 0f, 0.002f) < theta, "cloudy air must be heavier")
        assertEquals(theta, Thermodynamics.virtualTheta(theta, 0f, 0f), 1e-4f)
    }

    @Test
    fun `density - colder is denser, higher is thinner`() {
        val warm = Thermodynamics.densityNorm(1f, 303.15f)
        val cold = Thermodynamics.densityNorm(1f, 263.15f)
        assertTrue(cold > warm, "cold air must be denser (the balloon-lift premise)")
        val high = Thermodynamics.densityNorm(0.5f, 288.15f)
        val low = Thermodynamics.densityNorm(1f, 288.15f)
        assertTrue(high < low)
        // normalization anchor: ρ_norm = 1 at (P=1, T=ref)
        assertEquals(1f, Thermodynamics.densityNorm(1f, 288.15f), 1e-4f)
    }

    @Test
    fun `latent heat constant is in the physical ballpark`() {
        // condensing 1 g/kg should warm ~2.5 K
        assertTrue(abs(Thermodynamics.LATENT_HEAT_K_PER_QV * 0.001f - 2.49f) < 0.1f)
    }
}
