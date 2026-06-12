package ink.astrius.driftwardclimate.core.field

/**
 * Grid geometry of one region (the T2 moving window, spec 01 §2).
 *
 * Horizontal cell = 1 chunk (h = 16 blocks = 16 m); vertical = [layers] stacked
 * Y-layers (OQ5). Indexing is x-fastest: `idx = x + nx*(z + nz*layer)` so inner
 * loops stride 1 (SIMD/autovectorisation-friendly).
 *
 * [originCellX]/[originCellZ] anchor local cell (0,0) in *world* cell coords
 * (worldBlockX / 16). World-block → fractional local cell coords for sampling:
 * `fx = (wx - originCellX*h)/h - 0.5` (cell centres at fx = 0..nx-1).
 */
class GridGeometry(
    val nx: Int,
    val nz: Int,
    val layers: Int,
    val h: Float = 16f,
    val originCellX: Int = 0,
    val originCellZ: Int = 0,
    /**
     * Layer-centre heights in blocks/metres, strictly increasing; spacing may
     * be non-uniform (e.g. tight near the surface, loose aloft). Default:
     * uniform 32 m starting at y=16 (surface / low / mid / high feel).
     */
    val layerY: FloatArray = FloatArray(layers) { 16f + 32f * it },
) {
    init {
        require(nx >= 2 && nz >= 2 && layers >= 1) { "degenerate grid ${nx}x${nz}x$layers" }
        require(layerY.size == layers) { "layerY size ${layerY.size} != layers $layers" }
        for (l in 1 until layers) require(layerY[l] > layerY[l - 1]) { "layerY must increase" }
    }

    val planeSize: Int = nx * nz
    val cells: Int = planeSize * layers

    /** Central vertical spacing at layer [l] (one-sided at the stack ends). */
    fun dy(l: Int): Float = when {
        layers == 1 -> Float.POSITIVE_INFINITY // no vertical structure
        l <= 0 -> layerY[1] - layerY[0]
        l >= layers - 1 -> layerY[layers - 1] - layerY[layers - 2]
        else -> (layerY[l + 1] - layerY[l - 1]) * 0.5f
    }

    fun idx(x: Int, z: Int, layer: Int): Int = x + nx * (z + nz * layer)

    /** World block coord → fractional local cell coord (x axis). */
    fun toCellX(worldX: Double): Float = ((worldX / h) - originCellX - 0.5).toFloat()

    /** World block coord → fractional local cell coord (z axis). */
    fun toCellZ(worldZ: Double): Float = ((worldZ / h) - originCellZ - 0.5).toFloat()

    fun contains(fx: Float, fz: Float): Boolean =
        fx >= -0.5f && fz >= -0.5f && fx <= nx - 0.5f && fz <= nz - 0.5f
}

/**
 * SoA storage for all registered fields over one region, **double-buffered**
 * (spec 01 §5/§10): the solver writes `back` arrays while readers sample the
 * published [Snapshot] lock-free. [publish] swaps the buffers and installs a
 * new snapshot in one volatile store.
 *
 * Not thread-safe for *writers* — one stepping thread owns `back`/[publish].
 */
class FieldRegion(val geom: GridGeometry, val registry: FieldRegistry) {
    init {
        registry.freeze()
    }

    private val bufA = Array(registry.size) { FloatArray(geom.cells) }
    private val bufB = Array(registry.size) { FloatArray(geom.cells) }

    /** True while bufB is the write side (bufA published). */
    private var backIsB = true

    @Volatile
    var snapshot: Snapshot = Snapshot(geom, bufA)
        private set

    /** The write-side array for [handle] — solver scratch + next-state. */
    fun back(handle: FieldHandle): FloatArray =
        (if (backIsB) bufB else bufA)[handle.ordinal]

    /** The read-side array for [handle] (same storage the snapshot exposes). */
    fun front(handle: FieldHandle): FloatArray =
        (if (backIsB) bufA else bufB)[handle.ordinal]

    /** Copy front → back for fields the step doesn't rewrite wholesale. */
    fun carry(handle: FieldHandle) {
        front(handle).copyInto(back(handle))
    }

    /** Swap buffers and publish a fresh snapshot (single volatile store). */
    fun publish() {
        val newFront = if (backIsB) bufB else bufA
        backIsB = !backIsB
        snapshot = Snapshot(geom, newFront)
    }
}

/**
 * Immutable view of one published buffer generation. Readers hold a reference
 * and sample without locks; the arrays behind it are never written again until
 * the *next* generation swaps back (by which time well-behaved readers have
 * re-fetched [FieldRegion.snapshot]).
 *
 * Sampling (spec 01 §9):
 *  - [sampleC0] — bilinear. Continuous value, kinked gradient. For value-only
 *    consumers (Thermoo temperature/humidity).
 *  - [sampleC1] — bicubic Catmull–Rom. Continuous value AND first derivative;
 *    reproduces linear ramps exactly (no gradient wiggle). REQUIRED for any
 *    field a consumer finite-differences — pressure for Sable's ±1-block
 *    buoyancy gradient.
 *
 * Coordinates are fractional local cell coords (see [GridGeometry.toCellX]);
 * out-of-range coordinates clamp to the boundary cell (Neumann-consistent).
 */
class Snapshot internal constructor(
    val geom: GridGeometry,
    private val planes: Array<FloatArray>,
) {
    fun raw(handle: FieldHandle): FloatArray = planes[handle.ordinal]

    fun at(handle: FieldHandle, x: Int, z: Int, layer: Int): Float {
        val cx = if (x < 0) 0 else if (x >= geom.nx) geom.nx - 1 else x
        val cz = if (z < 0) 0 else if (z >= geom.nz) geom.nz - 1 else z
        return planes[handle.ordinal][geom.idx(cx, cz, layer)]
    }

    fun sampleC0(handle: FieldHandle, fx: Float, fz: Float, layer: Int): Float {
        val a = planes[handle.ordinal]
        val nx = geom.nx
        val nz = geom.nz
        val base = layer * geom.planeSize

        val cfx = clamp(fx, 0f, nx - 1f)
        val cfz = clamp(fz, 0f, nz - 1f)
        var x0 = cfx.toInt()
        var z0 = cfz.toInt()
        if (x0 > nx - 2) x0 = nx - 2
        if (z0 > nz - 2) z0 = nz - 2
        val tx = cfx - x0
        val tz = cfz - z0

        val i00 = base + z0 * nx + x0
        val v00 = a[i00]
        val v10 = a[i00 + 1]
        val v01 = a[i00 + nx]
        val v11 = a[i00 + nx + 1]
        val top = v00 + (v10 - v00) * tx
        val bot = v01 + (v11 - v01) * tx
        return top + (bot - top) * tz
    }

    fun sampleC1(handle: FieldHandle, fx: Float, fz: Float, layer: Int): Float {
        val a = planes[handle.ordinal]
        val nx = geom.nx
        val nz = geom.nz
        val base = layer * geom.planeSize

        val cfx = clamp(fx, 0f, nx - 1f)
        val cfz = clamp(fz, 0f, nz - 1f)
        val x1 = minOf(cfx.toInt(), nx - 2)
        val z1 = minOf(cfz.toInt(), nz - 2)
        val tx = cfx - x1
        val tz = cfz - z1

        // 4 Catmull–Rom row interpolations in x, then one in z. Border rows/
        // columns clamp (replicated edge ⇒ one-sided slopes stay continuous).
        val xm = maxOf(x1 - 1, 0)
        val x2 = x1 + 1
        val xp = minOf(x1 + 2, nx - 1)
        val zm = maxOf(z1 - 1, 0)
        val z2 = z1 + 1
        val zp = minOf(z1 + 2, nz - 1)

        val r0 = catmullRom(a[base + zm * nx + xm], a[base + zm * nx + x1], a[base + zm * nx + x2], a[base + zm * nx + xp], tx)
        val r1 = catmullRom(a[base + z1 * nx + xm], a[base + z1 * nx + x1], a[base + z1 * nx + x2], a[base + z1 * nx + xp], tx)
        val r2 = catmullRom(a[base + z2 * nx + xm], a[base + z2 * nx + x1], a[base + z2 * nx + x2], a[base + z2 * nx + xp], tx)
        val r3 = catmullRom(a[base + zp * nx + xm], a[base + zp * nx + x1], a[base + zp * nx + x2], a[base + zp * nx + xp], tx)
        return catmullRom(r0, r1, r2, r3, tz)
    }

    /**
     * Analytic gradient of the C1 interpolant — differentiate the Catmull–Rom
     * spline itself instead of finite-differencing samples. This is what
     * gradient consumers should use: Sable's buoyancy ∇P, radio's ∇N ray
     * bending. Continuous across cell boundaries by construction (the spline
     * is C1) and exact for linear ramps.
     *
     * [out] receives (∂/∂x, ∂/∂z) in field-units **per block/metre**.
     */
    fun sampleGradC1(handle: FieldHandle, fx: Float, fz: Float, layer: Int, out: FloatArray) {
        val a = planes[handle.ordinal]
        val nx = geom.nx
        val nz = geom.nz
        val base = layer * geom.planeSize

        val cfx = clamp(fx, 0f, nx - 1f)
        val cfz = clamp(fz, 0f, nz - 1f)
        val x1 = minOf(cfx.toInt(), nx - 2)
        val z1 = minOf(cfz.toInt(), nz - 2)
        val tx = cfx - x1
        val tz = cfz - z1

        val xm = maxOf(x1 - 1, 0)
        val x2 = x1 + 1
        val xp = minOf(x1 + 2, nx - 1)
        val zm = maxOf(z1 - 1, 0)
        val z2 = z1 + 1
        val zp = minOf(z1 + 2, nz - 1)

        val rowM = base + zm * nx
        val row1 = base + z1 * nx
        val row2 = base + z2 * nx
        val rowP = base + zp * nx

        // values and x-derivatives of each z-row at tx
        val v0 = catmullRom(a[rowM + xm], a[rowM + x1], a[rowM + x2], a[rowM + xp], tx)
        val v1 = catmullRom(a[row1 + xm], a[row1 + x1], a[row1 + x2], a[row1 + xp], tx)
        val v2 = catmullRom(a[row2 + xm], a[row2 + x1], a[row2 + x2], a[row2 + xp], tx)
        val v3 = catmullRom(a[rowP + xm], a[rowP + x1], a[rowP + x2], a[rowP + xp], tx)
        val d0 = catmullRomD(a[rowM + xm], a[rowM + x1], a[rowM + x2], a[rowM + xp], tx)
        val d1 = catmullRomD(a[row1 + xm], a[row1 + x1], a[row1 + x2], a[row1 + xp], tx)
        val d2 = catmullRomD(a[row2 + xm], a[row2 + x1], a[row2 + x2], a[row2 + xp], tx)
        val d3 = catmullRomD(a[rowP + xm], a[rowP + x1], a[rowP + x2], a[rowP + xp], tx)

        val invH = 1f / geom.h
        out[0] = catmullRom(d0, d1, d2, d3, tz) * invH   // ∂/∂x: interpolate the x-derivatives in z
        out[1] = catmullRomD(v0, v1, v2, v3, tz) * invH  // ∂/∂z: differentiate the z-spline of values
    }

    private companion object {
        fun clamp(v: Float, lo: Float, hi: Float): Float =
            if (v < lo) lo else if (v > hi) hi else v

        fun catmullRom(p0: Float, p1: Float, p2: Float, p3: Float, t: Float): Float {
            val a = 2f * p1
            val b = p2 - p0
            val c = 2f * p0 - 5f * p1 + 4f * p2 - p3
            val d = -p0 + 3f * p1 - 3f * p2 + p3
            return 0.5f * (a + b * t + c * t * t + d * t * t * t)
        }

        /** d/dt of [catmullRom] (per-cell units). */
        fun catmullRomD(p0: Float, p1: Float, p2: Float, p3: Float, t: Float): Float {
            val b = p2 - p0
            val c = 2f * p0 - 5f * p1 + 4f * p2 - p3
            val d = -p0 + 3f * p1 - 3f * p2 + p3
            return 0.5f * (b + 2f * c * t + 3f * d * t * t)
        }
    }
}
