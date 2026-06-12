package ink.astrius.driftwardclimate.core.model

import ink.astrius.driftwardclimate.core.config.ClimateConfig
import ink.astrius.driftwardclimate.core.solver.Thermodynamics

/**
 * T1 anomaly source seam — the SynopticTier (spec 06 §2) plugs in here when
 * it exists; until then reconstruction is pure T0 (+ seeded detail).
 */
fun interface SynopticAnomalySource {
    /** Large-scale θ anomaly (K) at the cell, 0 where T1 has no coverage. */
    fun thetaAnomalyK(cellX: Int, cellZ: Int): Float
}

/**
 * The downscaling read path (spec 06 §3):
 * ```
 * field(pos) = T1.interpolate(pos)   // synoptic anomaly (0 until T1 lands)
 *            + T0.baseline(pos)      // deterministic climatology
 *            + seededDetail(pos)     // sub-grid texture, stateless hash
 * ```
 * Loaded or not, simulated or not → a consistent, continuous value. T2
 * regions initialise FROM this and retire INTO it (one-way nesting).
 *
 * **OQ10 discipline:** seeded detail applies to θ only — NEVER to pressure —
 * so no fake ∇P gradients can mislead balloons (or radio/sound rays later).
 */
class Reconstruction(
    val t0: Baseline,
    private val t1: SynopticAnomalySource = SynopticAnomalySource { _, _ -> 0f },
    private val cfg: ClimateConfig = ClimateConfig(),
) {

    /** Reconstructed θ (K) at the cell. */
    fun theta(cellX: Int, cellZ: Int): Float =
        t0.theta0(cellX, cellZ) + t1.thetaAnomalyK(cellX, cellZ) + seededDetailK(cellX, cellZ)

    /** Reconstructed actual temperature (K) at (cell, y). */
    fun temperature(cellX: Int, cellZ: Int, y: Float): Float {
        val p = t0.pressure0(y)
        if (p <= 0f) return 0f
        return theta(cellX, cellZ) * Thermodynamics.exner(p)
    }

    /** Pressure passes straight through T0 — no anomaly, no detail (OQ10). */
    fun pressure(y: Float): Float = t0.pressure0(y)

    /**
     * Deterministic per-cell θ texture in [−amp, +amp]: splitmix-style hash
     * of the cell coords — stateless, reproducible, free.
     */
    fun seededDetailK(cellX: Int, cellZ: Int): Float {
        if (cfg.seededDetailAmpK == 0f) return 0f
        var x = cellX * -0x61c8864680b583ebL + cellZ * -0x3d4d51c2d82b14b1L
        x = (x xor (x ushr 30)) * -0x4b6d499041498d8dL
        x = (x xor (x ushr 27)) * -0x7ee3623a03d4dd23L
        x = x xor (x ushr 31)
        // top 24 bits → [−1, 1) → scale
        val unit = ((x ushr 40).toInt() / 8388608.0f) - 1.0f
        return unit * cfg.seededDetailAmpK
    }
}
