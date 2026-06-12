package ink.astrius.driftwardclimate.core.field

/**
 * Interpolation over a bare nx×nz plane (x-fastest), clamped at the edges —
 * the same C0/C1 schemes as [Snapshot], for planes that live OUTSIDE a
 * region's field storage (baseline caches, surface planes).
 */
object PlaneSampler {

    fun bilinear(a: FloatArray, nx: Int, nz: Int, fx: Float, fz: Float): Float {
        val cfx = clamp(fx, 0f, nx - 1f)
        val cfz = clamp(fz, 0f, nz - 1f)
        var x0 = cfx.toInt()
        var z0 = cfz.toInt()
        if (x0 > nx - 2) x0 = nx - 2
        if (z0 > nz - 2) z0 = nz - 2
        val tx = cfx - x0
        val tz = cfz - z0
        val i00 = z0 * nx + x0
        val v00 = a[i00]
        val v10 = a[i00 + 1]
        val v01 = a[i00 + nx]
        val v11 = a[i00 + nx + 1]
        val top = v00 + (v10 - v00) * tx
        val bot = v01 + (v11 - v01) * tx
        return top + (bot - top) * tz
    }

    /** Catmull–Rom bicubic — C1, exact on linear ramps (Snapshot's scheme). */
    fun catmullRom(a: FloatArray, nx: Int, nz: Int, fx: Float, fz: Float): Float {
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
        val r0 = cr(a[zm * nx + xm], a[zm * nx + x1], a[zm * nx + x2], a[zm * nx + xp], tx)
        val r1 = cr(a[z1 * nx + xm], a[z1 * nx + x1], a[z1 * nx + x2], a[z1 * nx + xp], tx)
        val r2 = cr(a[z2 * nx + xm], a[z2 * nx + x1], a[z2 * nx + x2], a[z2 * nx + xp], tx)
        val r3 = cr(a[zp * nx + xm], a[zp * nx + x1], a[zp * nx + x2], a[zp * nx + xp], tx)
        return cr(r0, r1, r2, r3, tz)
    }

    private fun cr(p0: Float, p1: Float, p2: Float, p3: Float, t: Float): Float {
        val a = 2f * p1
        val b = p2 - p0
        val c = 2f * p0 - 5f * p1 + 4f * p2 - p3
        val d = -p0 + 3f * p1 - 3f * p2 + p3
        return 0.5f * (a + b * t + c * t * t + d * t * t * t)
    }

    private fun clamp(v: Float, lo: Float, hi: Float): Float =
        if (v < lo) lo else if (v > hi) hi else v
}
