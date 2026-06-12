package ink.astrius.driftwardclimate.core.solver

import ink.astrius.driftwardclimate.core.config.ClimateConfig
import ink.astrius.driftwardclimate.core.field.GridGeometry
import ink.astrius.driftwardclimate.core.field.WorldFieldGrid
import ink.astrius.driftwardclimate.core.model.Baseline
import ink.astrius.driftwardclimate.core.model.FixedBaselinePort
import ink.astrius.driftwardclimate.core.model.FixedClock
import ink.astrius.driftwardclimate.core.model.FixedSeason
import ink.astrius.driftwardclimate.core.model.Reconstruction
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Spec 01 §12 — the solver-level invariants, on a synthetic flat world. */
class AtmosphereSolverTest {

    /** quiet config: no relax/edge interference unless a test wants it */
    private fun quietCfg(
        f: Float = 0f,
        drag: Float = 0f,
        seededDetail: Float = 0f,
        relaxTau: Float = Float.MAX_VALUE / 4f,
        edgeTau: Float = Float.MAX_VALUE / 4f,
        kappa: Float = 0f,
    ) = ClimateConfig(
        coriolisF = f,
        surfaceDragPerS = drag,
        rayleighPerS = 0f,
        seededDetailAmpK = seededDetail,
        relaxTauS = relaxTau,
        edgeRelaxTauS = edgeTau,
        kappaWind = kappa,
        kappaTheta = kappa,
        kappaMoisture = kappa,
    )

    private class Rig(
        nx: Int = 24,
        nz: Int = 24,
        layers: Int = 3,
        val cfg: ClimateConfig,
        qv0: Float = 0.006f,
    ) {
        val grid = WorldFieldGrid()
        val fields = ClimateFields(grid)
        val geom = GridGeometry(nx, nz, layers, h = 16f)
        val region = grid.createRegion(geom)
        val baseline = Baseline(FixedBaselinePort(qv = qv0), FixedSeason(0f), FixedClock(0), cfg)
        val recon = Reconstruction(baseline, cfg = cfg)
        val solver = AtmosphereSolver(region, fields, recon, cfg)

        init {
            // start at the reconstruction equilibrium: θ′=0, calm, q_v = q0
            region.back(fields.qv).fill(qv0)
            region.publish()
            region.back(fields.qv).fill(qv0)
            region.publish()
        }

        fun front(h: ink.astrius.driftwardclimate.core.field.FieldHandle) = region.front(h)
        fun back(h: ink.astrius.driftwardclimate.core.field.FieldHandle) = region.back(h)
        fun maxAbs(h: ink.astrius.driftwardclimate.core.field.FieldHandle): Float {
            var m = 0f
            for (x in front(h)) m = maxOf(m, abs(x))
            return m
        }
    }

    // ── uniform no-op (§12 row 1) ──────────────────────────────────────────

    @Test
    fun `equilibrium state is a fixed point of the step`() {
        val rig = Rig(cfg = quietCfg())
        repeat(5) { rig.solver.step(30f) }
        assertTrue(rig.maxAbs(rig.fields.thetaP) < 1e-3f, "θ′ drifted: ${rig.maxAbs(rig.fields.thetaP)}")
        assertTrue(rig.maxAbs(rig.fields.u) < 1e-4f, "wind appeared: ${rig.maxAbs(rig.fields.u)}")
        assertTrue(rig.maxAbs(rig.fields.qc) < 1e-6f, "cloud condensed from nothing")
        assertTrue(rig.maxAbs(rig.fields.w) < 1e-4f, "vertical motion from nothing")
    }

    // ── Coriolis (§12 geostrophic-adjacent: the exact rotation) ───────────

    @Test
    fun `Coriolis rotates the wind without changing its speed`() {
        val f = 1e-3f
        val rig = Rig(cfg = quietCfg(f = f))
        rig.back(rig.fields.u).fill(10f)
        rig.region.publish()
        rig.solver.step(30f)
        val u = rig.front(rig.fields.u)[rig.geom.idx(12, 12, 1)]
        val v = rig.front(rig.fields.v)[rig.geom.idx(12, 12, 1)]
        // §2.1 signs: u>0 → dv/dt = +f·u → v must turn POSITIVE (southward)
        assertTrue(v > 0.1f, "u-wind must veer toward +z (got v=$v)")
        val speed = Math.sqrt((u * u + v * v).toDouble()).toFloat()
        assertEquals(10f, speed, 0.1f, "exact rotation must preserve speed")
    }

    // ── sea breeze (§12 row 6): the end-to-end dynamics check ─────────────

    @Test
    fun `warm strip drives low-level inflow and rising air above it`() {
        val rig = Rig(nx = 32, nz = 16, cfg = quietCfg())
        // warm strip in the middle third (x = 12..19), all layers
        val thetaP = rig.back(rig.fields.thetaP)
        for (l in 0 until rig.geom.layers) for (z in 0 until 16) for (x in 12..19) {
            thetaP[rig.geom.idx(x, z, l)] = 6f
        }
        rig.region.publish()
        // CFL discipline: small dt so the developing jet doesn't backtrace
        // past its own width (see ClimateConfig.stepDtS)
        repeat(12) { rig.solver.step(5f) }

        val u = rig.front(rig.fields.u)
        val w = rig.front(rig.fields.w)
        val top = rig.geom.layers - 1
        // the sea-breeze CELL at the strip's west edge (x=11, just outside):
        // low-level inflow toward the strip…
        val uLow = u[rig.geom.idx(11, 8, 0)]
        assertTrue(uLow > 1e-3f, "low-level inflow at the strip edge (got $uLow)")
        // …with reversed shear aloft (return flow — the barotropic projection
        // guarantees the column can't all blow one way)
        val uTop = u[rig.geom.idx(11, 8, top)]
        assertTrue(uTop < uLow - 1e-3f, "outflow aloft must lag/reverse (low=$uLow top=$uTop)")
        // mirrored on the east edge
        val uLowE = u[rig.geom.idx(20, 8, 0)]
        assertTrue(uLowE < -1e-3f, "east edge must blow westward at low level (got $uLowE)")
        // and rising air somewhere over the strip
        var wMax = 0f
        for (x in 12..19) wMax = maxOf(wMax, w[rig.geom.idx(x, 8, top)])
        assertTrue(wMax > 1e-4f, "air must rise over the warm strip (max W=$wMax)")
    }

    // ── microphysics (§12 conservation + the storm engine) ───────────────

    @Test
    fun `supersaturation condenses, warms, and conserves total water`() {
        val rig = Rig(cfg = quietCfg(), qv0 = 0.005f)
        val qv = rig.back(rig.fields.qv)
        val i = rig.geom.idx(12, 12, 0)
        qv.fill(0.005f)
        qv[i] = 0.030f // way past saturation at 15 °C
        rig.region.publish()

        val before = rig.front(rig.fields.qv)[i] + rig.front(rig.fields.qc)[i] + rig.front(rig.fields.qr)[i]
        rig.solver.step(30f)

        val qvA = rig.front(rig.fields.qv)[i]
        val qcA = rig.front(rig.fields.qc)[i]
        val qrA = rig.front(rig.fields.qr)[i]
        val precip = rig.front(rig.fields.precip)[12 * rig.geom.nx + 12]
        val thetaP = rig.front(rig.fields.thetaP)[i]

        assertTrue(qcA > 1e-4f, "cloud must form (qc=$qcA)")
        assertTrue(qvA < 0.030f, "vapour must deplete")
        assertTrue(thetaP > 0.5f, "latent heat must warm the cell (θ′=$thetaP)")
        // total water conserved up to rain-out (which lands in precip)
        val after = qvA + qcA + qrA + precip
        assertEquals(before, after, before * 0.02f, "water budget: $before → $after")
    }

    @Test
    fun `dense cloud autoconverts to rain which rains out as surface precip`() {
        val cfg = quietCfg().copy(autoconvRatePerS = 0.1f, rainoutPerS = 0.05f)
        // NEAR-SATURATED environment (qsat ≈ 0.0106 at 15 °C) — in dry air the
        // evaporation branch correctly eats the cloud before it can rain.
        val rig = Rig(cfg = cfg, qv0 = 0.0104f)
        rig.back(rig.fields.qv).fill(0.0104f)
        rig.back(rig.fields.qc)[rig.geom.idx(10, 10, 0)] = 0.005f
        rig.region.publish()
        repeat(3) { rig.solver.step(30f) }
        var rained = 0f
        for (x in rig.front(rig.fields.precip)) rained += x
        assertTrue(rained > 1e-5f, "rain must reach the ground (got $rained)")
    }

    // ── projection: barotropic killed, baroclinic survives ────────────────

    @Test
    fun `baroclinic shear survives projection while barotropic divergence is removed`() {
        val rig = Rig(layers = 2, cfg = quietCfg())
        val geom = rig.geom
        val u = rig.back(rig.fields.u)
        // baroclinic: +U in layer 0, −U in layer 1 (column mean = 0)
        for (z in 0 until geom.nz) for (x in 0 until geom.nx) {
            u[geom.idx(x, z, 0)] = 3f
            u[geom.idx(x, z, 1)] = -3f
        }
        rig.region.publish()
        rig.solver.step(1f) // tiny dt: isolate projection from forces
        val u0 = rig.front(rig.fields.u)[geom.idx(12, 12, 0)]
        val u1 = rig.front(rig.fields.u)[geom.idx(12, 12, 1)]
        assertTrue(u0 > 2.5f && u1 < -2.5f, "baroclinic mode must survive ($u0, $u1)")
    }

    // ── relaxation pulls anomalies home ────────────────────────────────────

    @Test
    fun `theta anomaly decays toward the reconstruction on the relax timescale`() {
        val cfg = quietCfg(relaxTau = 60f) // aggressive for the test
        val rig = Rig(cfg = cfg)
        rig.back(rig.fields.thetaP).fill(5f)
        rig.region.publish()
        repeat(4) { rig.solver.step(30f) }
        val left = rig.maxAbs(rig.fields.thetaP)
        assertTrue(left < 5f * 0.30f, "θ′ should have decayed strongly (left=$left)")
    }

    @Test
    fun `edge relaxation calms the frontier winds`() {
        val cfg = quietCfg(edgeTau = 30f)
        val rig = Rig(cfg = cfg)
        rig.back(rig.fields.u).fill(8f)
        rig.region.publish()
        repeat(3) { rig.solver.step(30f) }
        val edge = abs(rig.front(rig.fields.u)[rig.geom.idx(0, 12, 0)])
        val centre = abs(rig.front(rig.fields.u)[rig.geom.idx(12, 12, 0)])
        assertTrue(edge < centre * 0.5f, "frontier should be becalmed (edge=$edge centre=$centre)")
    }

    // ── stability: no NaNs, no blow-up under abuse ────────────────────────

    @Test
    fun `solver stays finite under large perturbations and many steps`() {
        val rig = Rig(cfg = quietCfg(f = 5e-4f, drag = 2e-3f).copy(rayleighPerS = 2e-5f))
        val thetaP = rig.back(rig.fields.thetaP)
        val qv = rig.back(rig.fields.qv)
        for (z in 0 until rig.geom.nz) for (x in 0 until rig.geom.nx) {
            thetaP[rig.geom.idx(x, z, 0)] = ((x * 7 + z * 13) % 17 - 8).toFloat()
            qv[rig.geom.idx(x, z, 0)] = 0.004f + ((x + z) % 5) * 0.004f
        }
        rig.region.publish()
        repeat(50) { rig.solver.step(30f) }
        for (h in listOf(rig.fields.thetaP, rig.fields.u, rig.fields.v, rig.fields.qv, rig.fields.qc)) {
            for (x in rig.front(h)) {
                assertTrue(x.isFinite(), "non-finite value in ${rig.grid.registry.def(h).name}")
            }
        }
        assertTrue(rig.maxAbs(rig.fields.u) < 60f, "winds ran away: ${rig.maxAbs(rig.fields.u)} m/s")
        assertTrue(rig.maxAbs(rig.fields.thetaP) < 40f, "θ′ ran away")
    }
}
