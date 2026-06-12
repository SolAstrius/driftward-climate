package ink.astrius.driftwardclimate.core.model

import ink.astrius.driftwardclimate.core.config.ClimateConfig
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReconstructionTest {

    private fun t0(cfg: ClimateConfig = ClimateConfig()) =
        Baseline(FixedBaselinePort(), FixedSeason(0f), FixedClock(8000), cfg)

    @Test
    fun `with no T1 and detail off, reconstruction equals the baseline`() {
        val cfg = ClimateConfig(seededDetailAmpK = 0f)
        val r = Reconstruction(t0(cfg), cfg = cfg)
        assertEquals(t0(cfg).theta0(5, 9), r.theta(5, 9), 1e-4f)
        assertEquals(t0(cfg).pressure0(120f), r.pressure(120f), 1e-6f)
    }

    @Test
    fun `T1 anomaly is added when a source is plugged in`() {
        val cfg = ClimateConfig(seededDetailAmpK = 0f)
        val r = Reconstruction(t0(cfg), { _, _ -> 4.5f }, cfg)
        assertEquals(t0(cfg).theta0(0, 0) + 4.5f, r.theta(0, 0), 1e-3f)
    }

    @Test
    fun `seeded detail is bounded, deterministic, and varies across cells`() {
        val cfg = ClimateConfig(seededDetailAmpK = 0.4f)
        val r = Reconstruction(t0(cfg), cfg = cfg)
        var distinct = HashSet<Float>()
        for (cx in -20..20) for (cz in -20..20) {
            val d = r.seededDetailK(cx, cz)
            assertTrue(abs(d) <= 0.4f + 1e-5f, "detail $d out of bounds at ($cx,$cz)")
            assertEquals(d, r.seededDetailK(cx, cz), "must be deterministic")
            distinct.add(d)
        }
        assertTrue(distinct.size > 100, "detail should vary across cells (got ${distinct.size})")
    }

    @Test
    fun `OQ10 - pressure NEVER carries anomaly or seeded detail`() {
        val cfg = ClimateConfig(seededDetailAmpK = 5f) // absurd detail amplitude
        val r = Reconstruction(t0(cfg), { _, _ -> 99f }, cfg) // absurd anomaly
        // pressure must still be the pure Sable-calibrated curve
        assertEquals(t0(cfg).pressure0(100f), r.pressure(100f), 1e-6f)
    }

    @Test
    fun `reconstructed temperature responds to the anomaly through the Exner function`() {
        val cfg = ClimateConfig(seededDetailAmpK = 0f)
        val warm = Reconstruction(t0(cfg), { _, _ -> 10f }, cfg)
        val cold = Reconstruction(t0(cfg), { _, _ -> -10f }, cfg)
        val tw = warm.temperature(0, 0, 100f)
        val tc = cold.temperature(0, 0, 100f)
        assertTrue(tw > tc, "θ anomaly must show up in diagnosed T")
        // and both still lapse with altitude
        assertTrue(warm.temperature(0, 0, 200f) < tw)
    }
}
