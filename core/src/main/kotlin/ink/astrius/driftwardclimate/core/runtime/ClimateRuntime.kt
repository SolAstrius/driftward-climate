package ink.astrius.driftwardclimate.core.runtime

import ink.astrius.driftwardclimate.core.Atmosphere
import ink.astrius.driftwardclimate.core.api.BaselinePort
import ink.astrius.driftwardclimate.core.api.ClockPort
import ink.astrius.driftwardclimate.core.api.SeasonPort
import ink.astrius.driftwardclimate.core.config.ClimateConfig
import ink.astrius.driftwardclimate.core.field.FieldRegion
import ink.astrius.driftwardclimate.core.field.GridGeometry
import ink.astrius.driftwardclimate.core.field.WorldFieldGrid
import ink.astrius.driftwardclimate.core.model.Baseline
import ink.astrius.driftwardclimate.core.model.Reconstruction
import ink.astrius.driftwardclimate.core.solver.AtmosphereSolver
import ink.astrius.driftwardclimate.core.solver.ClimateFields
import ink.astrius.driftwardclimate.core.tier.SynopticTier
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The per-dimension orchestrator the adapter drives (one instance per
 * handled Level, owned by the stepping thread). Wires the full D13 stack:
 *
 *   T0 [Baseline] → T1 [SynopticTier] → [Reconstruction] → T2 window
 *   ([AtmosphereSolver] over a moving [FieldRegion]) → [Atmosphere] reads.
 *
 * Adapter contract:
 *  - `updateWindow(centre, velocity)` whenever player positions change
 *    (every few seconds is plenty — hysteresis absorbs jitter);
 *  - `tick(dtS)` at the solver cadence (off the main server thread);
 *  - `shutdown()` on world save/stop → persist the returned T1 payload;
 *  - `fastForward(elapsedS)` on world load after downtime;
 *  - read everything through [atmosphere] (lock-free snapshots).
 *
 * T2 window movement (spec 06 §4): desired origin = player centre + velocity
 * lead, moved only past a hysteresis threshold. On move, overlapping state is
 * **carried at world-anchored positions**; fresh cells initialise from the
 * T1+T0 reconstruction (θ′ = 0 *is* "equal to T1+T0") with T1's drift wind.
 * A teleport simply carries nothing — destination weather comes from T1,
 * so no "storm summoned on arrival".
 */
class ClimateRuntime(
    baselinePort: BaselinePort,
    seasonPort: SeasonPort,
    clockPort: ClockPort,
    private val cfg: ClimateConfig = ClimateConfig(),
    seed: Long = 0L,
    t1Payload: ByteArray? = null,
) {
    val grid = WorldFieldGrid()
    val fields = ClimateFields(grid)
    val t1 = SynopticTier(cfg, seed).also { tier -> t1Payload?.let(tier::deserialize) }
    val baseline = Baseline(baselinePort, seasonPort, clockPort, cfg)
    val recon = Reconstruction(baseline, t1, cfg)
    val atmosphere = Atmosphere(grid, fields, recon, cfg)

    private var solver: AtmosphereSolver? = null
    private var t1Accum = 0f
    private val windScratch = FloatArray(2)

    /** Current T2 region, null until the first [updateWindow]. */
    val region: FieldRegion? get() = grid.region

    // ── T2 window management ──────────────────────────────────────────────

    /**
     * Aim the window at [centreBlockX]/[centreBlockZ] (the players' centroid)
     * with velocity prefetch. Returns true if the region moved/was created.
     */
    fun updateWindow(
        centreBlockX: Double,
        centreBlockZ: Double,
        velXBlocksPerS: Float = 0f,
        velZBlocksPerS: Float = 0f,
    ): Boolean {
        val leadX = centreBlockX + velXBlocksPerS * cfg.prefetchLeadS
        val leadZ = centreBlockZ + velZBlocksPerS * cfg.prefetchLeadS
        val desiredOx = (leadX / 16.0).roundToInt() - cfg.t2Nx / 2
        val desiredOz = (leadZ / 16.0).roundToInt() - cfg.t2Nz / 2

        val current = grid.region
        if (current != null) {
            val dx = abs(desiredOx - current.geom.originCellX)
            val dz = abs(desiredOz - current.geom.originCellZ)
            if (dx <= cfg.windowMoveThresholdCells && dz <= cfg.windowMoveThresholdCells) return false
        }
        move(desiredOx, desiredOz, current)
        return true
    }

    private fun move(newOx: Int, newOz: Int, old: FieldRegion?) {
        val geom = GridGeometry(cfg.t2Nx, cfg.t2Nz, cfg.t2Layers, 16f, newOx, newOz)
        val region = grid.createRegion(geom)
        initialiseRegion(region, old)
        region.publish()
        solver = AtmosphereSolver(region, fields, recon, cfg)
        atmosphere.refreshBaseline()
    }

    /** Fresh-cell defaults from T1+T0; overlapping cells carried from [old]. */
    private fun initialiseRegion(region: FieldRegion, old: FieldRegion?) {
        val g = region.geom
        val plane = g.planeSize

        val thetaP = region.back(fields.thetaP)
        val u = region.back(fields.u)
        val v = region.back(fields.v)
        val qv = region.back(fields.qv)

        // defaults: θ′ = 0 (≡ T1+T0), q_v = baseline, wind = T1 drift
        for (z in 0 until g.nz) for (x in 0 until g.nx) {
            val chunkX = g.originCellX + x
            val chunkZ = g.originCellZ + z
            val q0 = recon.t0.humidity0(chunkX, chunkZ)
            t1.windAt(chunkX, chunkZ, windScratch)
            for (l in 0 until g.layers) {
                val i = l * plane + z * g.nx + x
                thetaP[i] = 0f
                qv[i] = q0
                u[i] = windScratch[0]
                v[i] = windScratch[1]
            }
        }
        // qc/qr/w/precip default to 0 (fresh arrays already are)

        // carry world-anchored overlap from the old region's published state
        if (old != null) {
            val og = old.geom
            val x0 = maxOf(g.originCellX, og.originCellX)
            val z0 = maxOf(g.originCellZ, og.originCellZ)
            val x1 = minOf(g.originCellX + g.nx, og.originCellX + og.nx)
            val z1 = minOf(g.originCellZ + g.nz, og.originCellZ + og.nz)
            if (x1 > x0 && z1 > z0 && g.layers == og.layers) {
                for (ord in 0 until grid.registry.size) {
                    val handle = ink.astrius.driftwardclimate.core.field.FieldHandle(ord)
                    val src = old.front(handle)
                    val dst = region.back(handle)
                    for (l in 0 until g.layers) {
                        for (wz in z0 until z1) {
                            val srcRow = l * og.planeSize + (wz - og.originCellZ) * og.nx
                            val dstRow = l * plane + (wz - g.originCellZ) * g.nx
                            for (wx in x0 until x1) {
                                dst[dstRow + (wx - g.originCellX)] = src[srcRow + (wx - og.originCellX)]
                            }
                        }
                    }
                }
            }
        }
    }

    // ── time ──────────────────────────────────────────────────────────────

    /** Advance the stack by [dtS]: T1 at its slow cadence, then the T2 step. */
    fun tick(dtS: Float = cfg.stepDtS) {
        t1Accum += dtS
        while (t1Accum >= cfg.t1StepDtS) {
            t1.step(cfg.t1StepDtS)
            t1Accum -= cfg.t1StepDtS
        }
        solver?.step(dtS)
        atmosphere.refreshBaseline()
    }

    /** Sleep/wake catch-up (06 §5) — call on load after server downtime. */
    fun fastForward(elapsedS: Float) = t1.fastForward(elapsedS)

    /**
     * Fold T2 into T1 (OQ8) and return the T1 payload to persist. The
     * in-progress storm survives the restart at synoptic resolution.
     */
    fun shutdown(): ByteArray {
        grid.region?.let { t1.foldFromT2(it, fields.thetaP) }
        return t1.serialize()
    }
}
