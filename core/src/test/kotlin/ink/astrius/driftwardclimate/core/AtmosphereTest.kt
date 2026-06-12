package ink.astrius.driftwardclimate.core

import ink.astrius.driftwardclimate.core.api.ClockPort
import ink.astrius.driftwardclimate.core.config.ClimateConfig
import ink.astrius.driftwardclimate.core.field.GridGeometry
import ink.astrius.driftwardclimate.core.field.NearFieldIndex
import ink.astrius.driftwardclimate.core.field.WorldFieldGrid
import ink.astrius.driftwardclimate.core.model.Baseline
import ink.astrius.driftwardclimate.core.model.FixedBaselinePort
import ink.astrius.driftwardclimate.core.model.FixedSeason
import ink.astrius.driftwardclimate.core.model.Reconstruction
import ink.astrius.driftwardclimate.core.solver.ClimateFields
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The query façade (spec 01 §9): Sable parity & seamlessness (02 §B), C1
 * pressure, the felt-temperature near-field, and tier fallthrough.
 */
class AtmosphereTest {

    private class MutClock(var ticks: Long) : ClockPort {
        override fun worldTimeTicks(): Long = ticks
    }

    private class Rig(
        val cfg: ClimateConfig = ClimateConfig(seededDetailAmpK = 0f),
        qv0: Float = 0.006f,
        val clock: MutClock = MutClock(8000),
    ) {
        val grid = WorldFieldGrid()
        val fields = ClimateFields(grid)
        // origin away from zero to exercise world→cell mapping
        val geom = GridGeometry(nx = 8, nz = 8, layers = 3, h = 16f, originCellX = 10, originCellZ = -5)
        val region = grid.createRegion(geom)
        val baseline = Baseline(FixedBaselinePort(qv = qv0), FixedSeason(0f), clock, cfg)
        val recon = Reconstruction(baseline, cfg = cfg)
        val atmosphere = Atmosphere(grid, fields, recon, cfg)

        init {
            region.back(fields.qv).fill(qv0)
            region.publish()
            region.back(fields.qv).fill(qv0)
            region.publish()
            atmosphere.refreshBaseline()
        }

        /** world-x of the centre of local cell x. */
        fun worldX(x: Int): Double = (10 + x) * 16.0 + 8.0
        fun worldZ(z: Int): Double = (-5 + z) * 16.0 + 8.0
    }

    // ── Sable parity & seamlessness (the 02 §B calibration, enforced) ─────

    @Test
    fun `pressure equals Sable's curve exactly when anomalies are zero`() {
        val rig = Rig()
        var y = 64.0
        while (y < 250.0) {
            val ours = rig.atmosphere.pressureAt(rig.worldX(4), y, rig.worldZ(4))
            assertEquals(rig.baseline.pressure0(y.toFloat()), ours, 1e-5f, "at y=$y")
            y += 13.0
        }
    }

    @Test
    fun `crossing the coverage boundary is seamless at zero anomaly`() {
        val rig = Rig()
        // region west edge: fx = −0.5 at world x = 10·16 = 160
        val inside = rig.atmosphere.pressureAt(160.5, 120.0, rig.worldZ(4))
        val outside = rig.atmosphere.pressureAt(159.5, 120.0, rig.worldZ(4))
        assertEquals(outside, inside, 1e-5f, "the original bug class: a seam at handles() boundary")
        // and temperature too
        val tIn = rig.atmosphere.temperatureC(160.5, 120.0, rig.worldZ(4))
        val tOut = rig.atmosphere.temperatureC(159.5, 120.0, rig.worldZ(4))
        assertEquals(tOut, tIn, 0.05f)
    }

    @Test
    fun `cold anomaly lifts more, warm lifts less`() {
        val rig = Rig()
        rig.region.back(rig.fields.thetaP).fill(-10f)
        rig.region.publish()
        val cold = rig.atmosphere.pressureAt(rig.worldX(4), 100.0, rig.worldZ(4))
        rig.region.back(rig.fields.thetaP).fill(10f)
        rig.region.publish()
        val warm = rig.atmosphere.pressureAt(rig.worldX(4), 100.0, rig.worldZ(4))
        val neutral = rig.baseline.pressure0(100f)
        assertTrue(cold > neutral && warm < neutral, "cold=$cold neutral=$neutral warm=$warm")
    }

    @Test
    fun `pressure is C1 across cell boundaries under a curved anomaly`() {
        val rig = Rig()
        val thetaP = rig.region.back(rig.fields.thetaP)
        for (l in 0 until 3) for (z in 0 until 8) for (x in 0 until 8) {
            thetaP[rig.geom.idx(x, z, l)] = (x * x).toFloat() * 0.3f
        }
        rig.region.publish()
        val y = 110.0
        val wz = rig.worldZ(4)
        val d = 0.8 // blocks
        // kink detector at interior cell boundaries (world x of cell edges)
        for (cellEdge in 2..6) {
            val b = (10 + cellEdge) * 16.0 // boundary between cells cellEdge−1 and cellEdge
            val pm = rig.atmosphere.pressureAt(b - d, y, wz)
            val p0 = rig.atmosphere.pressureAt(b, y, wz)
            val pp = rig.atmosphere.pressureAt(b + d, y, wz)
            val kink = abs(pm - 2 * p0 + pp) / d.toFloat()
            assertTrue(kink < 2e-4f, "∇P kink $kink at cell edge $cellEdge — the balloon bug, one level up")
        }
    }

    // ── temperature & altitude ────────────────────────────────────────────

    @Test
    fun `colder aloft - the emergent lapse through the facade`() {
        val rig = Rig()
        val tLow = rig.atmosphere.temperatureC(rig.worldX(4), 70.0, rig.worldZ(4))
        val tHigh = rig.atmosphere.temperatureC(rig.worldX(4), 200.0, rig.worldZ(4))
        assertTrue(tHigh < tLow - 15f, "low=$tLow high=$tHigh")
    }

    @Test
    fun `diurnal cycle flows through refreshBaseline`() {
        val rig = Rig()
        val afternoon = rig.atmosphere.temperatureC(rig.worldX(4), 80.0, rig.worldZ(4))
        rig.clock.ticks = 20000 // pre-dawn trough
        rig.atmosphere.refreshBaseline()
        val night = rig.atmosphere.temperatureC(rig.worldX(4), 80.0, rig.worldZ(4))
        assertTrue(afternoon - night in 9f..14f, "diurnal swing: $afternoon vs $night")
    }

    @Test
    fun `felt temperature adds the campfire near-field`() {
        val rig = Rig()
        val x = rig.worldX(4)
        val z = rig.worldZ(4)
        rig.grid.nearField(rig.fields.thetaP)
            .add(NearFieldIndex.PointSource(x, 70.0, z, strength = 8f, radius = 5f))
        val ambient = rig.atmosphere.temperatureC(x, 70.0, z)
        val felt = rig.atmosphere.feltTemperatureC(x, 70.0, z)
        assertEquals(ambient + 8f, felt, 1e-3f, "at the flame")
        val feltFar = rig.atmosphere.feltTemperatureC(x + 20, 70.0, z)
        assertEquals(rig.atmosphere.temperatureC(x + 20, 70.0, z), feltFar, 1e-4f, "out of radius")
    }

    // ── moisture, cloud, wind ─────────────────────────────────────────────

    @Test
    fun `relative humidity is clamped to 1 and falls back outside coverage`() {
        val rig = Rig(qv0 = 0.006f)
        rig.region.back(rig.fields.qv).fill(0.05f) // far past saturation
        rig.region.publish()
        assertEquals(1f, rig.atmosphere.relativeHumidity(rig.worldX(4), 70.0, rig.worldZ(4)), 1e-5f)
        // outside the region: baseline humidity, sub-saturated
        val rhOut = rig.atmosphere.relativeHumidity(0.0, 70.0, 0.0)
        assertTrue(rhOut in 0.01f..0.99f, "fallback RH should be moderate (got $rhOut)")
    }

    @Test
    fun `cloud column integrates qc over layer thicknesses`() {
        val rig = Rig()
        val qc = rig.region.back(rig.fields.qc)
        for (z in 0 until 8) for (x in 0 until 8) qc[rig.geom.idx(x, z, 1)] = 0.001f
        rig.region.publish()
        val col = rig.atmosphere.cloudColumnAt(rig.worldX(4), rig.worldZ(4))
        assertEquals(0.001f * 32f, col, 1e-5f) // middle layer thickness = 32
        assertEquals(0f, rig.atmosphere.cloudColumnAt(0.0, 0.0), "no cloud outside coverage")
    }

    @Test
    fun `wind reads back with vertical interpolation, calm outside`() {
        val rig = Rig()
        val u = rig.region.back(rig.fields.u)
        for (z in 0 until 8) for (x in 0 until 8) {
            u[rig.geom.idx(x, z, 0)] = 2f
            u[rig.geom.idx(x, z, 1)] = 6f
            u[rig.geom.idx(x, z, 2)] = 10f
        }
        rig.region.publish()
        val out = FloatArray(3)
        // halfway between layer 0 (y=16) and layer 1 (y=48): y = 32 → u = 4
        rig.atmosphere.windAt(rig.worldX(4), 32.0, rig.worldZ(4), out)
        assertEquals(4f, out[0], 0.01f)
        rig.atmosphere.windAt(0.0, 32.0, 0.0, out)
        assertEquals(0f, out[0], "calm outside coverage")
    }

    // ── coverage gating ───────────────────────────────────────────────────

    @Test
    fun `handles reflects region coverage`() {
        val rig = Rig()
        assertTrue(rig.atmosphere.handles(rig.worldX(4), rig.worldZ(4)))
        assertFalse(rig.atmosphere.handles(0.0, 0.0))
        assertFalse(rig.atmosphere.handles(rig.worldX(4) + 10_000, rig.worldZ(4)))
    }

    @Test
    fun `facade without refreshBaseline never claims coverage`() {
        val grid = WorldFieldGrid()
        val fields = ClimateFields(grid)
        val cfg = ClimateConfig(seededDetailAmpK = 0f)
        val recon = Reconstruction(Baseline(FixedBaselinePort(), FixedSeason(0f), MutClock(0), cfg), cfg = cfg)
        val atmo = Atmosphere(grid, fields, recon, cfg)
        assertFalse(atmo.handles(100.0, 100.0))
        // …but still answers with T0 (the field exists everywhere, D13)
        assertEquals(
            recon.t0.pressure0(120f),
            atmo.pressureAt(100.0, 120.0, 100.0),
            1e-6f,
        )
    }
}
