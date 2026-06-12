package ink.astrius.driftwardclimate.core

import ink.astrius.driftwardclimate.core.config.ClimateConfig
import ink.astrius.driftwardclimate.core.field.FieldRegion
import ink.astrius.driftwardclimate.core.field.PlaneSampler
import ink.astrius.driftwardclimate.core.field.WorldFieldGrid
import ink.astrius.driftwardclimate.core.model.Reconstruction
import ink.astrius.driftwardclimate.core.solver.ClimateFields
import ink.astrius.driftwardclimate.core.solver.Thermodynamics

/**
 * The public query API (spec 01 §9) — what Thermoo and Sable ultimately read.
 * World coordinates in blocks (1 block = 1 m); the NeoForge adapter maps
 * Level/BlockPos onto one Atmosphere per dimension.
 *
 * Every read resolves to the **finest tier covering the position** (D13):
 * inside the T2 region → interpolated snapshot + baseline; outside → the
 * T1+T0 [Reconstruction]. The same formulas run on both paths with the
 * anomaly = 0 outside, so crossing the coverage boundary is seamless.
 *
 * Interpolation discipline (§9): value consumers get C0; **pressure is C1 in
 * BOTH terms** — the θ′ snapshot (Catmull–Rom) *and* the θ0 baseline plane.
 * A C1 anomaly on a stepwise biome baseline would smuggle the border-descent
 * bug right back in.
 *
 * **Sable parity:** `pressureAt` = `p0(y) · θ0/θ` (the Exner factors cancel
 * exactly), so wherever θ′ = 0 the answer IS Sable's curve — the 02 §B
 * calibration constraint, enforced by test.
 *
 * Thread-safety: reads are lock-free against the published snapshot.
 * [refreshBaseline] must be called from the stepping thread (same cadence as
 * solver steps — season/diurnal drift is slow).
 */
class Atmosphere(
    private val grid: WorldFieldGrid,
    private val fields: ClimateFields,
    private val recon: Reconstruction,
    private val cfg: ClimateConfig = ClimateConfig(),
) {
    private var cachedRegion: FieldRegion? = null
    private var theta0Plane = FloatArray(0)
    private var nx = 0
    private var nz = 0

    /** Rebuild the baseline cache for the current region (stepping thread). */
    fun refreshBaseline() {
        val region = grid.region ?: return
        if (cachedRegion !== region) {
            cachedRegion = region
            nx = region.geom.nx
            nz = region.geom.nz
            theta0Plane = FloatArray(nx * nz)
        }
        val geom = region.geom
        for (z in 0 until nz) for (x in 0 until nx) {
            theta0Plane[z * nx + x] = recon.theta(geom.originCellX + x, geom.originCellZ + z)
        }
    }

    /** True when the T2 region covers this column (the Sable mixin's gate). */
    fun handles(wx: Double, wz: Double): Boolean {
        val region = cachedRegion ?: return false
        val geom = region.geom
        return geom.contains(geom.toCellX(wx), geom.toCellZ(wz))
    }

    // ── temperature ────────────────────────────────────────────────────────

    /** Ambient air temperature, Kelvin. */
    fun temperatureK(wx: Double, y: Double, wz: Double): Float {
        val p = recon.t0.pressure0(y.toFloat())
        if (p <= 0f) return 0f
        return thetaTotal(wx, y, wz) * Thermodynamics.exner(p)
    }

    /** Ambient °C — what the Thermoo provider emits (spec 02 §A). */
    fun temperatureC(wx: Double, y: Double, wz: Double): Float =
        temperatureK(wx, y, wz) - Thermodynamics.CELSIUS_ZERO_K

    /**
     * Felt temperature °C = ambient + the sub-grid radiant near-field
     * (campfires, ice — spec 05 §2.3, OQ11 "augment").
     */
    fun feltTemperatureC(wx: Double, y: Double, wz: Double): Float =
        temperatureC(wx, y, wz) + grid.nearFieldSum(fields.thetaP, wx, y, wz)

    // ── pressure & density (Sable, spec 02 §B) ────────────────────────────

    /**
     * Effective buoyant pressure for Sable's `getAirPressure` (02 §B.3):
     * the barometric base state times the density factor θ0/θ — cold/dense
     * columns lift more. ≡ Sable's native curve wherever θ′ = 0. C1 in
     * the horizontal (both terms), smooth in y.
     */
    fun pressureAt(wx: Double, y: Double, wz: Double): Float {
        val p0 = recon.t0.pressure0(y.toFloat())
        if (p0 <= 0f) return 0f
        val region = cachedRegion ?: return p0
        val geom = region.geom
        val fx = geom.toCellX(wx)
        val fz = geom.toCellZ(wz)
        if (!geom.contains(fx, fz)) return p0
        val theta0 = PlaneSampler.catmullRom(theta0Plane, nx, nz, fx, fz)
        val theta = theta0 + thetaPrimeC1(region, fx, fz, y)
        if (theta <= 1f) return p0
        val ratio = (theta0 / theta).coerceIn(0.5f, 2f)
        return p0 * ratio
    }

    /** Normalised air density (1 at sea level, 15 °C) — buoyancy-true. */
    fun densityAt(wx: Double, y: Double, wz: Double): Float {
        val p = recon.t0.pressure0(y.toFloat())
        if (p <= 0f) return 0f
        return Thermodynamics.densityNorm(p, temperatureK(wx, y, wz), cfg.referenceTemperatureK)
    }

    // ── moisture & clouds ─────────────────────────────────────────────────

    /** Relative humidity 0..1 — Thermoo's RELATIVE_HUMIDITY (spec 02 §A.4). */
    fun relativeHumidity(wx: Double, y: Double, wz: Double): Float {
        val pKPa = recon.t0.pressure0(y.toFloat()) * Thermodynamics.SEA_LEVEL_KPA
        if (pKPa <= 0f) return 0f
        val qv = sampleOrNull(fields.qv, wx, y, wz, c1 = false)
            ?: recon.t0.humidity0(cellX(wx), cellZ(wz))
        val rh = Thermodynamics.relativeHumidity(qv, temperatureK(wx, y, wz), pKPa)
        return rh.coerceIn(0f, 1f)
    }

    /** Cloud condensate q_c (kg/kg) at the point; 0 outside the region. */
    fun cloudAt(wx: Double, y: Double, wz: Double): Float =
        sampleOrNull(fields.qc, wx, y, wz, c1 = false) ?: 0f

    /** Column-integrated cloud (optical-depth proxy): Σ q_c·Δy over layers. */
    fun cloudColumnAt(wx: Double, wz: Double): Float {
        val region = cachedRegion ?: return 0f
        val geom = region.geom
        val fx = geom.toCellX(wx)
        val fz = geom.toCellZ(wz)
        if (!geom.contains(fx, fz)) return 0f
        val snap = region.snapshot
        var sum = 0f
        for (l in 0 until geom.layers) {
            val dy = if (geom.layers == 1) 32f else thickness(geom.layerY, l)
            sum += snap.sampleC0(fields.qc, fx, fz, l) * dy
        }
        return sum
    }

    /** Precipitation reaching the ground this step (kg/kg rained out). */
    fun precipAt(wx: Double, wz: Double): Float =
        sampleOrNull(fields.precip, wx, 0.0, wz, c1 = false, layerOverride = 0) ?: 0f

    // ── wind ───────────────────────────────────────────────────────────────

    /** Wind into [out] = (u, v, w) m/s (§2.1 axes); calm outside the region. */
    fun windAt(wx: Double, y: Double, wz: Double, out: FloatArray) {
        out[0] = sampleOrNull(fields.u, wx, y, wz, c1 = false) ?: 0f
        out[1] = sampleOrNull(fields.v, wx, y, wz, c1 = false) ?: 0f
        out[2] = sampleOrNull(fields.w, wx, y, wz, c1 = false) ?: 0f
    }

    // ── internals ─────────────────────────────────────────────────────────

    private fun cellX(wx: Double): Int = Math.floorDiv(wx.toInt(), 16)
    private fun cellZ(wz: Double): Int = Math.floorDiv(wz.toInt(), 16)

    /** Total θ at the point: C1 baseline + C1 anomaly inside; recon outside. */
    private fun thetaTotal(wx: Double, y: Double, wz: Double): Float {
        val region = cachedRegion
        if (region != null) {
            val geom = region.geom
            val fx = geom.toCellX(wx)
            val fz = geom.toCellZ(wz)
            if (geom.contains(fx, fz)) {
                val theta0 = PlaneSampler.catmullRom(theta0Plane, nx, nz, fx, fz)
                return theta0 + thetaPrimeC1(region, fx, fz, y)
            }
        }
        return recon.theta(cellX(wx), cellZ(wz))
    }

    /** θ′ sampled C1 horizontally, lerped vertically between layer centres. */
    private fun thetaPrimeC1(region: FieldRegion, fx: Float, fz: Float, y: Double): Float {
        val snap = region.snapshot
        val geom = region.geom
        if (geom.layers == 1) return snap.sampleC1(fields.thetaP, fx, fz, 0)
        val ly = layerCoord(geom.layerY, y.toFloat())
        val l0 = ly.toInt().coerceAtMost(geom.layers - 2)
        val t = ly - l0
        val a = snap.sampleC1(fields.thetaP, fx, fz, l0)
        val b = snap.sampleC1(fields.thetaP, fx, fz, l0 + 1)
        return a + (b - a) * t
    }

    private fun sampleOrNull(
        handle: ink.astrius.driftwardclimate.core.field.FieldHandle,
        wx: Double,
        y: Double,
        wz: Double,
        c1: Boolean,
        layerOverride: Int = -1,
    ): Float? {
        val region = cachedRegion ?: return null
        val geom = region.geom
        val fx = geom.toCellX(wx)
        val fz = geom.toCellZ(wz)
        if (!geom.contains(fx, fz)) return null
        val snap = region.snapshot
        if (layerOverride >= 0) {
            return if (c1) snap.sampleC1(handle, fx, fz, layerOverride)
            else snap.sampleC0(handle, fx, fz, layerOverride)
        }
        if (geom.layers == 1) {
            return if (c1) snap.sampleC1(handle, fx, fz, 0) else snap.sampleC0(handle, fx, fz, 0)
        }
        val ly = layerCoord(geom.layerY, y.toFloat())
        val l0 = ly.toInt().coerceAtMost(geom.layers - 2)
        val t = ly - l0
        val a = if (c1) snap.sampleC1(handle, fx, fz, l0) else snap.sampleC0(handle, fx, fz, l0)
        val b = if (c1) snap.sampleC1(handle, fx, fz, l0 + 1) else snap.sampleC0(handle, fx, fz, l0 + 1)
        return a + (b - a) * t
    }

    private companion object {
        /** Continuous layer coordinate of altitude [y] over [layerY]. */
        fun layerCoord(layerY: FloatArray, y: Float): Float {
            if (y <= layerY[0]) return 0f
            val last = layerY.size - 1
            if (y >= layerY[last]) return last.toFloat()
            var l = 0
            while (y >= layerY[l + 1]) l++
            return l + (y - layerY[l]) / (layerY[l + 1] - layerY[l])
        }

        fun thickness(layerY: FloatArray, l: Int): Float = when {
            l == 0 -> layerY[1] - layerY[0]
            l == layerY.size - 1 -> layerY[l] - layerY[l - 1]
            else -> (layerY[l + 1] - layerY[l - 1]) * 0.5f
        }
    }
}
