package ink.astrius.driftwardclimate.core.tier

import ink.astrius.driftwardclimate.core.config.ClimateConfig
import ink.astrius.driftwardclimate.core.field.FieldDef
import ink.astrius.driftwardclimate.core.field.FieldRegistry
import ink.astrius.driftwardclimate.core.field.FieldRegion
import ink.astrius.driftwardclimate.core.field.GridGeometry
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SynopticTierTest {

    private val cfg = ClimateConfig(t1Nx = 32, t1Nz = 32)

    @Test
    fun `weather happens - anomalies grow from zero under forcing, bounded`() {
        val t1 = SynopticTier(cfg, seed = 42)
        repeat(720) { t1.step(60f) } // 12 h
        var maxA = 0f
        for (v in t1.thetaA) maxA = maxOf(maxA, abs(v))
        assertTrue(maxA > 0.5f, "synoptic systems should have developed (max=$maxA)")
        assertTrue(maxA < cfg.t1ForcingK * 2f, "and stay bounded by the forcing (max=$maxA)")
    }

    @Test
    fun `determinism - same seed, same weather history`() {
        val a = SynopticTier(cfg, seed = 7)
        val b = SynopticTier(cfg, seed = 7)
        repeat(200) {
            a.step(60f)
            b.step(60f)
        }
        for (i in a.thetaA.indices) assertEquals(a.thetaA[i], b.thetaA[i], "diverged at $i")
        // different seed → different weather
        val c = SynopticTier(cfg, seed = 8)
        repeat(200) { c.step(60f) }
        var diff = 0f
        for (i in a.thetaA.indices) diff = maxOf(diff, abs(a.thetaA[i] - c.thetaA[i]))
        assertTrue(diff > 0.1f, "different seeds must give different weather")
    }

    @Test
    fun `anomaly read is smooth across chunk coords and zero outside coverage`() {
        val t1 = SynopticTier(cfg, seed = 3)
        repeat(360) { t1.step(60f) }
        // smoothness: adjacent chunks differ by less than the coarse-cell scale allows
        var prev = t1.thetaAnomalyK(0, 0)
        for (chunkX in 1..100) {
            val v = t1.thetaAnomalyK(chunkX, 0)
            assertTrue(abs(v - prev) < 0.5f, "anomaly jump ${abs(v - prev)} at chunk $chunkX")
            prev = v
        }
        // far outside the T1 window → exactly 0 (T0 takes over)
        assertEquals(0f, t1.thetaAnomalyK(100_000, 100_000))
    }

    @Test
    fun `edge taper - anomalies fade to zero at the window frontier`() {
        val t1 = SynopticTier(cfg, seed = 5)
        t1.thetaA.fill(4f)
        // window spans chunks [origin, origin + 32*16); frontier chunk reads ~0
        val edgeChunk = t1.originChunkX
        val v = t1.thetaAnomalyK(edgeChunk, t1.originChunkZ + 16 * 16)
        assertTrue(abs(v) < 1f, "frontier anomaly should be tapered (got $v)")
        // centre reads the full value
        val centre = t1.thetaAnomalyK(t1.originChunkX + 16 * 16, t1.originChunkZ + 16 * 16)
        assertEquals(4f, centre, 0.1f)
    }

    @Test
    fun `serialize-deserialize roundtrip is exact`() {
        val t1 = SynopticTier(cfg, seed = 11)
        repeat(100) { t1.step(60f) }
        val payload = t1.serialize()
        val t2 = SynopticTier(cfg, seed = 11)
        assertTrue(t2.deserialize(payload))
        assertEquals(t1.stepCount, t2.stepCount)
        for (i in t1.thetaA.indices) assertEquals(t1.thetaA[i], t2.thetaA[i])
        // mismatched shape is rejected, not corrupted
        val other = SynopticTier(ClimateConfig(t1Nx = 16, t1Nz = 16), seed = 11)
        assertTrue(!other.deserialize(payload))
    }

    @Test
    fun `fast-forward decays the old state and lands on current forcing`() {
        val t1 = SynopticTier(cfg, seed = 13)
        t1.thetaA.fill(10f) // strong stale anomaly
        t1.fastForward(7f * 24 * 3600) // a week away
        var maxA = 0f
        for (v in t1.thetaA) maxA = maxOf(maxA, abs(v))
        // the stale 10 K is gone; what's left is bounded by the forcing
        assertTrue(maxA < cfg.t1ForcingK * 1.5f, "stale weather must have died (max=$maxA)")
        // and a short sleep keeps most of the state
        val t2 = SynopticTier(cfg, seed = 13)
        t2.thetaA.fill(2f)
        t2.fastForward(600f)
        assertTrue(t2.thetaA[0] > 1.5f, "10 minutes shouldn't erase the weather")
    }

    @Test
    fun `systems drift - the anomaly pattern moves under its own wind`() {
        // pure drift: no forcing, no relax decay. The blob must be SMOOTH —
        // a sharp step makes a 20 m/s self-wind (CFL≈5) that semi-Lagrangian
        // advection legitimately smears away; real synoptic systems aren't steps.
        val pure = cfg.copy(t1ForcingK = 0f, t1ForcingTauS = Float.MAX_VALUE / 4f)
        val t1 = SynopticTier(pure, seed = 1)
        val cx = 16f
        val cz = 16f
        for (z in 0 until 32) for (x in 0 until 32) {
            val r2 = ((x - cx) * (x - cx) + (z - cz) * (z - cz)) / 16f // radius ~4 cells
            if (r2 < 1f) t1.thetaA[z * 32 + x] = 3f * (1f - r2) * (1f - r2)
        }
        val before = t1.thetaA.copyOf()
        repeat(60) { t1.step(60f) }
        var moved = 0f
        for (i in before.indices) moved = maxOf(moved, abs(t1.thetaA[i] - before[i]))
        assertTrue(moved > 0.15f, "blob should have rotated/sheared (moved=$moved)")
        // total anomaly roughly conserved under pure advection
        var sumB = 0.0
        var sumA = 0.0
        for (i in before.indices) {
            sumB += before[i]
            sumA += t1.thetaA[i]
        }
        assertTrue(abs(sumA - sumB) / sumB < 0.12, "drift shouldn't create/destroy anomaly ($sumB → $sumA)")
    }

    @Test
    fun `foldFromT2 - a T2 storm survives into the coarse tier`() {
        val t1 = SynopticTier(cfg, seed = 0)
        // a T2 region inside the T1 window with θ′ = 6 everywhere
        val reg = FieldRegistry()
        val h = reg.register(FieldDef("theta_prime"))
        val geom = GridGeometry(
            nx = 32, nz = 32, layers = 2, h = 16f,
            originCellX = t1.originChunkX + 200,
            originCellZ = t1.originChunkZ + 200,
        )
        val region = FieldRegion(geom, reg)
        region.back(h).fill(6f)
        region.publish()

        t1.foldFromT2(region, h)
        // the coarse cells under the region picked up the anomaly
        val chunkMid = t1.originChunkX + 200 + 16
        val v = t1.thetaAnomalyK(chunkMid, t1.originChunkZ + 200 + 16)
        assertTrue(v > 3f, "folded anomaly should read back (got $v)")
        // cells far away untouched
        assertEquals(0f, t1.thetaAnomalyK(t1.originChunkX + 30, t1.originChunkZ + 30), 1e-4f)
    }
}
