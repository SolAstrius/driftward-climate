package ink.astrius.driftwardclimate.core.runtime

import ink.astrius.driftwardclimate.core.api.ClockPort
import ink.astrius.driftwardclimate.core.config.ClimateConfig
import ink.astrius.driftwardclimate.core.model.FixedBaselinePort
import ink.astrius.driftwardclimate.core.model.FixedSeason
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClimateRuntimeTest {

    private class MutClock(var ticks: Long) : ClockPort {
        override fun worldTimeTicks(): Long = ticks
    }

    private fun cfg(forcingK: Float = 0f) = ClimateConfig(
        seededDetailAmpK = 0f,
        t2Nx = 24,
        t2Nz = 24,
        t2Layers = 2,
        t1Nx = 32,
        t1Nz = 32,
        t1ForcingK = forcingK,
        windowMoveThresholdCells = 4,
        coriolisF = 0f,
        surfaceDragPerS = 0f,
        rayleighPerS = 0f,
        relaxTauS = Float.MAX_VALUE / 4f,
        edgeRelaxTauS = Float.MAX_VALUE / 4f,
        kappaWind = 0f,
        kappaTheta = 0f,
        kappaMoisture = 0f,
    )

    private fun runtime(cfg: ClimateConfig = cfg(), payload: ByteArray? = null) = ClimateRuntime(
        FixedBaselinePort(), FixedSeason(0f), MutClock(8000), cfg, seed = 9, t1Payload = payload,
    )

    // ── window placement & hysteresis ─────────────────────────────────────

    @Test
    fun `first update creates a region centred on the player`() {
        val rt = runtime()
        assertTrue(rt.updateWindow(centreBlockX = 1000.0, centreBlockZ = -500.0))
        val g = rt.region!!.geom
        // centre chunk ≈ 1000/16 = 62.5 → origin ≈ 63 − 12
        assertTrue(abs(g.originCellX - (63 - 12)) <= 1, "originX=${g.originCellX}")
        assertTrue(abs(g.originCellZ - (-31 - 12)) <= 1, "originZ=${g.originCellZ}")
        assertTrue(rt.atmosphere.handles(1000.0, -500.0))
    }

    @Test
    fun `small movement does not thrash the window`() {
        val rt = runtime()
        rt.updateWindow(0.0, 0.0)
        val origin = rt.region!!.geom.originCellX
        assertFalse(rt.updateWindow(40.0, -30.0), "2-3 chunks of drift is inside hysteresis")
        assertEquals(origin, rt.region!!.geom.originCellX)
    }

    @Test
    fun `velocity prefetch leads the window along the motion vector`() {
        val still = runtime()
        still.updateWindow(0.0, 0.0)
        val moving = runtime()
        moving.updateWindow(0.0, 0.0, velXBlocksPerS = 40f) // elytra eastward
        assertTrue(
            moving.region!!.geom.originCellX > still.region!!.geom.originCellX + 10,
            "window should lead a fast flyer (still=${still.region!!.geom.originCellX} moving=${moving.region!!.geom.originCellX})",
        )
    }

    // ── state carry across moves ──────────────────────────────────────────

    @Test
    fun `window move carries world-anchored state in the overlap`() {
        val rt = runtime()
        rt.updateWindow(0.0, 0.0)
        // plant a θ′ blob at a known world position
        val g0 = rt.region!!.geom
        val blobChunkX = g0.originCellX + 15
        val blobChunkZ = g0.originCellZ + 12
        val thetaP = rt.region!!.back(rt.fields.thetaP)
        thetaP[g0.idx(15, 12, 0)] = 7f
        rt.region!!.publish()

        // shift the window east past hysteresis (overlap retains the blob)
        assertTrue(rt.updateWindow(8.0 * 16, 0.0))
        val g1 = rt.region!!.geom
        val lx = blobChunkX - g1.originCellX
        val lz = blobChunkZ - g1.originCellZ
        assertTrue(lx in 0 until g1.nx && lz in 0 until g1.nz, "blob should still be covered")
        val carried = rt.region!!.snapshot.at(rt.fields.thetaP, lx, lz, 0)
        assertEquals(7f, carried, 1e-4f, "world-anchored state must survive the move")
    }

    @Test
    fun `teleport gets fresh T1 weather, not a copied storm`() {
        val rt = runtime()
        rt.updateWindow(0.0, 0.0)
        rt.region!!.back(rt.fields.thetaP).fill(9f)
        rt.region!!.publish()
        // teleport 100k blocks: zero overlap
        assertTrue(rt.updateWindow(100_000.0, 100_000.0))
        var maxTheta = 0f
        for (v in rt.region!!.front(rt.fields.thetaP)) maxTheta = maxOf(maxTheta, abs(v))
        assertEquals(0f, maxTheta, 1e-5f, "no storm summoned on arrival (06 §4)")
        assertTrue(rt.atmosphere.handles(100_000.0, 100_000.0))
        assertFalse(rt.atmosphere.handles(0.0, 0.0))
    }

    // ── the full stack ticking ────────────────────────────────────────────

    @Test
    fun `tick advances T2 and T1 at their own cadences`() {
        val rt = runtime(cfg(forcingK = 3f))
        rt.updateWindow(0.0, 0.0)
        val t1Steps0 = rt.t1.stepCount
        repeat(13) { rt.tick(5f) } // 65 s → one T1 step (60 s cadence)
        assertEquals(t1Steps0 + 1, rt.t1.stepCount)
        // equilibrium T2 stays calm while T1 evolves
        var maxU = 0f
        for (v in rt.region!!.front(rt.fields.u)) maxU = maxOf(maxU, abs(v))
        assertTrue(maxU < 1f, "T2 should stay near-calm at equilibrium (maxU=$maxU)")
    }

    @Test
    fun `T1 anomaly flows into T2 reads immediately through the reconstruction`() {
        // the anomaly is part of the RELAXATION TARGET (recon.theta), so it
        // reaches reads at the next baseline refresh — no slow pull needed,
        // and θ′ stays the *deviation from weather*, not the weather itself.
        val rt = runtime()
        rt.updateWindow(0.0, 0.0)
        rt.tick(5f)
        val before = rt.atmosphere.temperatureC(8.0, 80.0, 8.0)
        rt.t1.thetaA.fill(5f)
        rt.tick(5f) // one tick → refreshBaseline picks up the synoptic warmth
        val after = rt.atmosphere.temperatureC(8.0, 80.0, 8.0)
        assertTrue(after - before in 3.5f..5.5f, "synoptic +5 K should read ≈+4.9 ($before → $after)")
        // and θ′ stayed ≈0 — the anomaly lives in the target, not the field
        var maxP = 0f
        for (v in rt.region!!.front(rt.fields.thetaP)) maxP = maxOf(maxP, abs(v))
        assertTrue(maxP < 1f, "θ′ should remain the deviation (max=$maxP)")
    }

    // ── persistence round trip (the restart story, 01 §12) ───────────────

    @Test
    fun `shutdown folds the T2 storm into T1 and the payload revives it`() {
        val rt = runtime()
        rt.updateWindow(0.0, 0.0)
        rt.region!!.back(rt.fields.thetaP).fill(6f)
        rt.region!!.publish()

        val payload = rt.shutdown()

        // a fresh runtime (same seed/config) loads the payload
        val revived = runtime(payload = payload)
        // BEFORE any window exists: reads fall back to T1+T0 — and carry the storm
        val g = rt.region!!.geom
        val wx = (g.originCellX + g.nx / 2) * 16.0
        val wz = (g.originCellZ + g.nz / 2) * 16.0
        val tCold = ClimateRuntime(
            FixedBaselinePort(), FixedSeason(0f), MutClock(8000), cfg(), seed = 9,
        ).atmosphere.temperatureC(wx, 80.0, wz)
        val tRevived = revived.atmosphere.temperatureC(wx, 80.0, wz)
        assertTrue(
            tRevived > tCold + 3f,
            "restart must keep the storm at synoptic resolution ($tCold → $tRevived)",
        )
    }

    @Test
    fun `fastForward ages the weather while the server was down`() {
        val rt = runtime(cfg(forcingK = 2f))
        rt.t1.thetaA.fill(8f)
        rt.fastForward(3f * 24 * 3600)
        var maxA = 0f
        for (v in rt.t1.thetaA) maxA = maxOf(maxA, abs(v))
        assertTrue(maxA < 4f, "three days should decay an 8 K anomaly (max=$maxA)")
    }
}
