package ink.astrius.driftwardclimate.core.field

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/** Spec 01 §12 — projection invariants + the FFT ↔ SOR cross-check (OQ2). */
class ProjectionTest {

    private fun maxAbsDivergence(geom: GridGeometry, u: FloatArray, v: FloatArray): Float {
        val d = FloatArray(geom.cells)
        Operators.divergence(geom, u, v, d)
        var m = 0f
        // interior only — boundary divergence is clamped/one-sided by design
        for (l in 0 until geom.layers)
            for (z in 1 until geom.nz - 1)
                for (x in 1 until geom.nx - 1) {
                    val a = abs(d[geom.idx(x, z, l)])
                    if (a > m) m = a
                }
        return m
    }

    /**
     * Smooth (resolvable) random wind: low-order sinusoids with seed-varied
     * phases. NO white noise on purpose — cell-frequency (checkerboard)
     * divergence is invisible to central differences on a collocated grid, so
     * no projection can remove it; physical wind at chunk scale is smooth.
     */
    private fun randomWind(geom: GridGeometry, seed: Int): Pair<FloatArray, FloatArray> {
        val rnd = Random(seed)
        val p1 = rnd.nextDouble() * Math.PI
        val p2 = rnd.nextDouble() * Math.PI
        val u = FloatArray(geom.cells)
        val v = FloatArray(geom.cells)
        for (l in 0 until geom.layers)
            for (z in 0 until geom.nz)
                for (x in 0 until geom.nx) {
                    val i = geom.idx(x, z, l)
                    val sx = x.toDouble() / geom.nx
                    val sz = z.toDouble() / geom.nz
                    u[i] = (Math.sin(2.0 * Math.PI * sx + p1) * 4 + Math.cos(2.0 * Math.PI * 2 * sz) * 2).toFloat()
                    v[i] = (Math.cos(2.0 * Math.PI * sz + p2) * 3 - Math.sin(2.0 * Math.PI * 3 * sx) * 2).toFloat()
                }
        return u to v
    }

    @Test
    fun `SOR projection strongly reduces divergence`() {
        val geom = GridGeometry(nx = 48, nz = 48, layers = 1, h = 16f)
        val (u, v) = randomWind(geom, 1)
        val before = maxAbsDivergence(geom, u, v)
        Projection.project(geom, u, v, SorPoisson(iterations = 400), FloatArray(geom.planeSize), FloatArray(geom.planeSize))
        val after = maxAbsDivergence(geom, u, v)
        assertTrue(after < before * 0.15f, "div: $before -> $after (expected ≥85% reduction)")
    }

    @Test
    fun `FFT projection strongly reduces divergence`() {
        val geom = GridGeometry(nx = 48, nz = 48, layers = 1, h = 16f)
        val (u, v) = randomWind(geom, 2)
        val before = maxAbsDivergence(geom, u, v)
        Projection.project(geom, u, v, FftPoisson(), FloatArray(geom.planeSize), FloatArray(geom.planeSize))
        val after = maxAbsDivergence(geom, u, v)
        assertTrue(after < before * 0.15f, "div: $before -> $after (expected ≥85% reduction)")
    }

    @Test
    fun `FFT and SOR solve the same Poisson problem`() {
        val nx = 40
        val nz = 32
        val h = 16f
        val rnd = Random(7)
        val rhs = FloatArray(nx * nz) { rnd.nextFloat() - 0.5f }

        val pSor = FloatArray(nx * nz)
        val pFft = FloatArray(nx * nz)
        SorPoisson(iterations = 4000, omega = 1.9f).solve(rhs.copyOf(), pSor, nx, nz, h)
        FftPoisson().solve(rhs.copyOf(), pFft, nx, nz, h)

        // Neumann solution is defined up to a constant — compare de-meaned.
        fun deMean(a: FloatArray) {
            val m = (a.sumOf { it.toDouble() } / a.size).toFloat()
            for (i in a.indices) a[i] -= m
        }
        deMean(pSor)
        deMean(pFft)

        var scale = 0f
        for (x in pFft) scale = maxOf(scale, abs(x))
        var maxErr = 0f
        for (i in pSor.indices) maxErr = maxOf(maxErr, abs(pSor[i] - pFft[i]))
        assertTrue(maxErr < scale * 0.02f, "FFT vs SOR mismatch: maxErr=$maxErr scale=$scale")
    }

    @Test
    fun `FFT solution satisfies the 5-point Laplacian`() {
        // direct residual check: ∇²p ≈ de-meaned rhs (interior)
        val nx = 32
        val nz = 32
        val h = 16f
        val rnd = Random(11)
        val rhs = FloatArray(nx * nz) { rnd.nextFloat() - 0.5f }
        val mean = (rhs.sumOf { it.toDouble() } / rhs.size).toFloat()

        val p = FloatArray(nx * nz)
        FftPoisson().solve(rhs.copyOf(), p, nx, nz, h)

        val geom = GridGeometry(nx, nz, 1, h)
        val lap = FloatArray(nx * nz)
        Operators.laplacian(geom, p, lap)

        var maxErr = 0f
        var scale = 0f
        for (z in 1 until nz - 1) for (x in 1 until nx - 1) {
            val i = z * nx + x
            val want = rhs[i] - mean
            maxErr = maxOf(maxErr, abs(lap[i] - want))
            scale = maxOf(scale, abs(want))
        }
        assertTrue(maxErr < scale * 0.01f, "Laplacian residual $maxErr vs scale $scale")
    }

    @Test
    fun `projection of divergence-free rotation is a no-op`() {
        val geom = GridGeometry(nx = 40, nz = 40, layers = 1, h = 16f)
        val cx = geom.nx / 2f
        val cz = geom.nz / 2f
        val u = FloatArray(geom.cells)
        val v = FloatArray(geom.cells)
        for (z in 0 until geom.nz) for (x in 0 until geom.nx) {
            val i = geom.idx(x, z, 0)
            u[i] = (z - cz) * 0.1f
            v[i] = -(x - cx) * 0.1f
        }
        val u0 = u.copyOf()
        val v0 = v.copyOf()
        Projection.project(geom, u, v, FftPoisson(), FloatArray(geom.planeSize), FloatArray(geom.planeSize))
        var maxDelta = 0f
        for (z in 2 until geom.nz - 2) for (x in 2 until geom.nx - 2) {
            val i = geom.idx(x, z, 0)
            maxDelta = maxOf(maxDelta, abs(u[i] - u0[i]), abs(v[i] - v0[i]))
        }
        assertTrue(maxDelta < 0.02f, "rotation perturbed by $maxDelta")
    }
}
