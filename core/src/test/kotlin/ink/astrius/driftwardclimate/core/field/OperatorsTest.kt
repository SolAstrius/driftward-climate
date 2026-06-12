package ink.astrius.driftwardclimate.core.field

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Spec 01 §12 — analytic operator invariants on synthetic grids. */
class OperatorsTest {

    private val geom = GridGeometry(nx = 32, nz = 24, layers = 2, h = 16f)

    private fun field(f: (x: Int, z: Int) -> Float): FloatArray {
        val a = FloatArray(geom.cells)
        for (l in 0 until geom.layers)
            for (z in 0 until geom.nz)
                for (x in 0 until geom.nx)
                    a[geom.idx(x, z, l)] = f(x, z)
        return a
    }

    private fun interior(check: (x: Int, z: Int, l: Int, i: Int) -> Unit) {
        for (l in 0 until geom.layers)
            for (z in 1 until geom.nz - 1)
                for (x in 1 until geom.nx - 1)
                    check(x, z, l, geom.idx(x, z, l))
    }

    @Test
    fun `gradient of linear ramp is exact in the interior`() {
        val a = 3f
        val b = -2f
        val s = field { x, z -> a * x * geom.h + b * z * geom.h }
        val gx = FloatArray(geom.cells)
        val gz = FloatArray(geom.cells)
        Operators.gradient(geom, s, gx, gz)
        interior { _, _, _, i ->
            assertEquals(a, gx[i], 1e-3f)
            assertEquals(b, gz[i], 1e-3f)
        }
    }

    @Test
    fun `divergence of linear wind is exact in the interior`() {
        val u = field { x, _ -> 2f * x * geom.h }
        val v = field { _, z -> 5f * z * geom.h }
        val d = FloatArray(geom.cells)
        Operators.divergence(geom, u, v, d)
        interior { _, _, _, i -> assertEquals(7f, d[i], 1e-3f) }
    }

    @Test
    fun `curl of rigid rotation is twice the angular velocity`() {
        // v = ω × r → u = −ω·z, v = ω·x (in metres); ζ = 2ω.
        val omega = 0.25f
        val u = field { _, z -> -omega * z * geom.h }
        val v = field { x, _ -> omega * x * geom.h }
        val zeta = FloatArray(geom.cells)
        Operators.curl(geom, u, v, zeta)
        interior { _, _, _, i -> assertEquals(2f * omega, zeta[i], 1e-3f) }
    }

    @Test
    fun `curl of a gradient flow is zero`() {
        val u = field { x, _ -> 4f * x * geom.h }
        val v = field { _, z -> -1.5f * z * geom.h }
        val zeta = FloatArray(geom.cells)
        Operators.curl(geom, u, v, zeta)
        interior { _, _, _, i -> assertEquals(0f, zeta[i], 1e-3f) }
    }

    @Test
    fun `laplacian of quadratic bowl is constant`() {
        // s = x² + z² (metres) → ∇²s = 4.
        val s = field { x, z ->
            val mx = x * geom.h
            val mz = z * geom.h
            mx * mx + mz * mz
        }
        val lap = FloatArray(geom.cells)
        Operators.laplacian(geom, s, lap)
        interior { _, _, _, i -> assertEquals(4f, lap[i], 1e-2f) }
    }

    @Test
    fun `uniform field is a no-op for every operator`() {
        val s = field { _, _ -> 7.5f }
        val out1 = FloatArray(geom.cells)
        val out2 = FloatArray(geom.cells)
        Operators.gradient(geom, s, out1, out2)
        for (i in 0 until geom.cells) {
            assertEquals(0f, out1[i])
            assertEquals(0f, out2[i])
        }
        Operators.laplacian(geom, s, out1)
        for (i in 0 until geom.cells) assertEquals(0f, out1[i])

        // advect a uniform field by an arbitrary wind → unchanged (incl. boundaries)
        val u = field { x, z -> ((x * 31 + z * 17) % 13 - 6).toFloat() }
        val v = field { x, z -> ((x * 7 + z * 29) % 11 - 5).toFloat() }
        Operators.advect(geom, s, u, v, dt = 30f, dst = out1)
        for (i in 0 until geom.cells) assertEquals(7.5f, out1[i], 1e-5f)

        Operators.diffuse(geom, s, kappa = 50f, dt = 1f, dst = out1)
        for (i in 0 until geom.cells) assertEquals(7.5f, out1[i], 1e-4f)
    }

    @Test
    fun `advection translates a blob by u dt over h cells`() {
        // uniform wind +x at 8 m/s, dt = 4 s → shift = 2 cells exactly.
        val peakX = 10
        val peakZ = 12
        val s = field { x, z -> if (x == peakX && z == peakZ) 100f else 0f }
        val u = field { _, _ -> 8f }
        val v = field { _, _ -> 0f }
        val out = FloatArray(geom.cells)
        Operators.advect(geom, s, u, v, dt = 4f, dst = out)

        var maxI = -1
        var maxV = -1f
        for (z in 0 until geom.nz) for (x in 0 until geom.nx) {
            val i = geom.idx(x, z, 0)
            if (out[i] > maxV) {
                maxV = out[i]
                maxI = i
            }
        }
        val foundX = maxI % geom.nx
        val foundZ = (maxI / geom.nx) % geom.nz
        assertEquals(peakX + 2, foundX, "blob should move +2 cells in x")
        assertEquals(peakZ, foundZ)
        assertEquals(100f, maxV, 1e-3f) // integral backtrace lands on a node → exact
    }

    @Test
    fun `semi-Lagrangian advection does not amplify extrema`() {
        // monotone interpolation property: max(out) ≤ max(src), min(out) ≥ min(src)
        val s = field { x, z -> ((x * 13 + z * 7) % 19).toFloat() }
        val u = field { x, z -> ((x + z) % 9 - 4).toFloat() }
        val v = field { x, z -> ((x * 3 + z) % 7 - 3).toFloat() }
        val out = FloatArray(geom.cells)
        Operators.advect(geom, s, u, v, dt = 13f, dst = out)
        val srcMax = s.max()
        val srcMin = s.min()
        for (i in 0 until geom.cells) {
            assertTrue(out[i] <= srcMax + 1e-4f && out[i] >= srcMin - 1e-4f)
        }
    }

    @Test
    fun `pure advection approximately conserves the field sum away from boundaries`() {
        // periodic-free check: gentle rotation well inside the domain
        val cx = geom.nx / 2f
        val cz = geom.nz / 2f
        val s = field { x, z ->
            val dx = x - cx
            val dz = z - cz
            if (dx * dx + dz * dz < 16f) 10f else 0f
        }
        val u = field { _, z -> (z - cz) * 0.05f * geom.h }
        val v = field { x, _ -> -(x - cx) * 0.05f * geom.h }
        val out = FloatArray(geom.cells)
        Operators.advect(geom, s, u, v, dt = 1f, dst = out)
        val before = s.sumOf { it.toDouble() }
        val after = out.sumOf { it.toDouble() }
        assertTrue(abs(after - before) / before < 0.02, "mass drift ${abs(after - before) / before}")
    }
}
