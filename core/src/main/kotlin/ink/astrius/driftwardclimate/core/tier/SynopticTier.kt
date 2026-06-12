package ink.astrius.driftwardclimate.core.tier

import ink.astrius.driftwardclimate.core.config.ClimateConfig
import ink.astrius.driftwardclimate.core.field.FieldHandle
import ink.astrius.driftwardclimate.core.field.FieldRegion
import ink.astrius.driftwardclimate.core.field.GridGeometry
import ink.astrius.driftwardclimate.core.field.Operators
import ink.astrius.driftwardclimate.core.field.PlaneSampler
import ink.astrius.driftwardclimate.core.model.SynopticAnomalySource
import java.nio.ByteBuffer
import kotlin.math.exp
import kotlin.math.min

/**
 * T1 — the synoptic tier (spec 06 §2, D13): large-scale weather on a coarse
 * grid (1 cell = [ClimateConfig.t1CellChunks] chunks), slow clock, persisted.
 * This IS "weather outside sim distance" and "weather while you were away".
 *
 * Dynamics (deliberately cheap, deliberately alive):
 *  - the θ anomaly field relaxes toward a **seeded, smoothly-evolving forcing
 *    field** (value noise over space, cross-faded between epochs in time) —
 *    pressure systems appear and decay with no scripting and full determinism
 *    (same seed ⇒ same weather history);
 *  - a **rotational drift wind** is diagnosed from the anomaly's own gradient
 *    (u = −G·∂θ/∂z, v = +G·∂θ/∂x — flow circles warm/cold pools, the
 *    geostrophic analogue of §2.1) and **self-advects** the anomalies →
 *    systems drift and shear;
 *  - **fast-forward is closed-form** (OQ9): n skipped steps collapse into one
 *    exponential blend toward the forcing at the *wake-time* epoch.
 *
 * Persistence (D14): [serialize]/[deserialize] move an opaque byte payload;
 * the adapter wraps it in SavedData. [foldFromT2] is the OQ8 middle ground —
 * on shutdown the T2 window's θ′ column means are folded in here so an
 * in-progress storm survives the restart at synoptic resolution.
 */
class SynopticTier(
    private val cfg: ClimateConfig = ClimateConfig(),
    private val seed: Long = 0L,
) : SynopticAnomalySource {

    val nx = cfg.t1Nx
    val nz = cfg.t1Nz
    private val span = cfg.t1CellChunks
    val originChunkX = cfg.t1CenterChunkX - nx * span / 2
    val originChunkZ = cfg.t1CenterChunkZ - nz * span / 2

    /** coarse-cell spacing in metres/blocks. */
    private val hCoarse = span * 16f
    private val geom = GridGeometry(nx, nz, 1, hCoarse)

    val thetaA = FloatArray(nx * nz)
    private val windU = FloatArray(nx * nz)
    private val windV = FloatArray(nx * nz)
    private val scratch = FloatArray(nx * nz)
    private val scratchB = FloatArray(nx * nz)
    private val scratchC = FloatArray(nx * nz)
    private val forcing = FloatArray(nx * nz)

    var stepCount: Long = 0L
        private set

    // ── the anomaly source (what Reconstruction reads) ────────────────────

    /** θ anomaly at CHUNK coords — bilinear over coarse cells, edge-tapered. */
    override fun thetaAnomalyK(cellX: Int, cellZ: Int): Float {
        val fcx = (cellX - originChunkX).toFloat() / span - 0.5f
        val fcz = (cellZ - originChunkZ).toFloat() / span - 0.5f
        if (fcx < -0.5f || fcz < -0.5f || fcx > nx - 0.5f || fcz > nz - 0.5f) return 0f
        val v = PlaneSampler.bilinear(thetaA, nx, nz, fcx, fcz)
        return v * edgeTaper(fcx, fcz)
    }

    /** Coarse drift wind at chunk coords (m/s) — T2 wind initialisation. */
    fun windAt(cellX: Int, cellZ: Int, out: FloatArray) {
        val fcx = (cellX - originChunkX).toFloat() / span - 0.5f
        val fcz = (cellZ - originChunkZ).toFloat() / span - 0.5f
        if (fcx < -0.5f || fcz < -0.5f || fcx > nx - 0.5f || fcz > nz - 0.5f) {
            out[0] = 0f
            out[1] = 0f
            return
        }
        val t = edgeTaper(fcx, fcz)
        out[0] = PlaneSampler.bilinear(windU, nx, nz, fcx, fcz) * t
        out[1] = PlaneSampler.bilinear(windV, nx, nz, fcx, fcz) * t
    }

    private fun edgeTaper(fcx: Float, fcz: Float): Float {
        val m = cfg.t1EdgeTaperCells.toFloat()
        if (m <= 0f) return 1f
        val d = minOf(fcx + 0.5f, fcz + 0.5f, nx - 0.5f - fcx, nz - 0.5f - fcz)
        return (d / m).coerceIn(0f, 1f)
    }

    // ── dynamics ───────────────────────────────────────────────────────────

    fun step(dtS: Float = cfg.t1StepDtS) {
        // 1. drift wind from the anomaly's own gradient (rotational)
        Operators.gradient(geom, thetaA, windU, windV) // windU=∂x, windV=∂z (borrow)
        for (i in 0 until nx * nz) {
            val gx = windU[i]
            val gz = windV[i]
            windU[i] = -cfg.t1WindGain * gz
            windV[i] = cfg.t1WindGain * gx
        }

        // 2. self-advection: systems drift. BFECC — anomalies must SURVIVE
        // hours of unforced drift (plain SL loses ~1%/step to the sub-cell
        // divergence of bilinear velocity; see Operators.advectBFECC).
        thetaA.copyInto(scratch)
        Operators.advectBFECC(geom, scratch, windU, windV, dtS, thetaA, scratchB, scratchC)

        // 3. relax toward the evolving forcing target
        buildForcing(timeS() + dtS)
        val a = min(dtS / cfg.t1ForcingTauS, 1f)
        for (i in 0 until nx * nz) {
            thetaA[i] += (forcing[i] - thetaA[i]) * a
        }
        stepCount++
    }

    /**
     * OQ9 sleep/wake: collapse [elapsedS] of un-simulated time into one
     * closed-form blend toward the forcing at the wake-time epoch.
     */
    fun fastForward(elapsedS: Float) {
        if (elapsedS <= 0f) return
        stepCount += (elapsedS / cfg.t1StepDtS).toLong()
        buildForcing(timeS())
        val keep = exp(-elapsedS / cfg.t1ForcingTauS)
        for (i in 0 until nx * nz) {
            thetaA[i] = thetaA[i] * keep + forcing[i] * (1f - keep)
        }
    }

    private fun timeS(): Float = stepCount * cfg.t1StepDtS

    /**
     * Seeded forcing field at absolute time [tS]: spatial value-noise at
     * [ClimateConfig.t1ForcingScaleCells], cross-faded between integer epochs
     * → blobs of warm/cold that morph smoothly. Deterministic in (seed, t).
     */
    private fun buildForcing(tS: Float) {
        val epochF = tS / cfg.t1ForcingPeriodS
        val e0 = epochF.toLong()
        val blend = epochF - e0
        val scale = cfg.t1ForcingScaleCells
        for (z in 0 until nz) for (x in 0 until nx) {
            val a = valueNoise(x, z, scale, e0)
            val b = valueNoise(x, z, scale, e0 + 1)
            forcing[z * nx + x] = (a + (b - a) * blend) * cfg.t1ForcingK
        }
    }

    /** Bilinear value noise over a lattice of [scale]-cell pitch, in [−1, 1]. */
    private fun valueNoise(x: Int, z: Int, scale: Int, epoch: Long): Float {
        val gx = Math.floorDiv(x, scale)
        val gz = Math.floorDiv(z, scale)
        val tx = (x - gx * scale).toFloat() / scale
        val tz = (z - gz * scale).toFloat() / scale
        val v00 = lattice(gx, gz, epoch)
        val v10 = lattice(gx + 1, gz, epoch)
        val v01 = lattice(gx, gz + 1, epoch)
        val v11 = lattice(gx + 1, gz + 1, epoch)
        // smoothstep the fractions — C1 forcing, no lattice creases
        val sx = tx * tx * (3f - 2f * tx)
        val sz = tz * tz * (3f - 2f * tz)
        val top = v00 + (v10 - v00) * sx
        val bot = v01 + (v11 - v01) * sx
        return top + (bot - top) * sz
    }

    private fun lattice(gx: Int, gz: Int, epoch: Long): Float {
        var h = seed
        h = h * -0x61c8864680b583ebL + gx
        h = h * -0x3d4d51c2d82b14b1L + gz
        h = h * -0x7ee3623a03d4dd23L + epoch
        h = (h xor (h ushr 30)) * -0x4b6d499041498d8dL
        h = h xor (h ushr 27)
        return ((h ushr 40).toInt() / 8388608.0f) - 1.0f
    }

    // ── T2 fold-in (OQ8 fold-on-shutdown) ─────────────────────────────────

    /**
     * Fold the T2 window's θ′ into this tier: for every coarse cell the
     * region overlaps, add the column-mean θ′ averaged over the cell's
     * chunks. Storms survive restarts at synoptic resolution (01 §12).
     */
    fun foldFromT2(region: FieldRegion, thetaP: FieldHandle) {
        val g = region.geom
        val front = region.front(thetaP)
        val plane = g.planeSize
        // accumulate sums per coarse cell
        val sums = HashMap<Int, FloatArray>() // coarse idx → [sum, count]
        for (z in 0 until g.nz) for (x in 0 until g.nx) {
            val chunkX = g.originCellX + x
            val chunkZ = g.originCellZ + z
            val cx = Math.floorDiv(chunkX - originChunkX, span)
            val cz = Math.floorDiv(chunkZ - originChunkZ, span)
            if (cx < 0 || cz < 0 || cx >= nx || cz >= nz) continue
            var colSum = 0f
            for (l in 0 until g.layers) colSum += front[l * plane + z * g.nx + x]
            val acc = sums.getOrPut(cz * nx + cx) { FloatArray(2) }
            acc[0] += colSum / g.layers
            acc[1] += 1f
        }
        for ((idx, acc) in sums) {
            thetaA[idx] += acc[0] / acc[1]
        }
    }

    // ── persistence (opaque byte payload, D14) ────────────────────────────

    fun serialize(): ByteArray {
        val buf = ByteBuffer.allocate(1 + 4 * 3 + 8 + 4 * nx * nz)
        buf.put(1) // version
        buf.putInt(nx)
        buf.putInt(nz)
        buf.putInt(span)
        buf.putLong(stepCount)
        for (v in thetaA) buf.putFloat(v)
        return buf.array()
    }

    /** Load a payload; silently ignores mismatched version/shape (fresh start). */
    fun deserialize(payload: ByteArray): Boolean {
        val buf = ByteBuffer.wrap(payload)
        if (buf.remaining() < 21) return false
        if (buf.get() != 1.toByte()) return false
        if (buf.int != nx || buf.int != nz || buf.int != span) return false
        stepCount = buf.long
        if (buf.remaining() < 4 * nx * nz) return false
        for (i in 0 until nx * nz) thetaA[i] = buf.float
        return true
    }
}
