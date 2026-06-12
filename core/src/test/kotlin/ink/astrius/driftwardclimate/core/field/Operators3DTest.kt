package ink.astrius.driftwardclimate.core.field

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The vertical axis as a real derivative axis (OQ5): 3D operator invariants. */
class Operators3DTest {

    // uniform vertical spacing 32 m — layerY = 16, 48, 80, 112
    private val geom = GridGeometry(nx = 20, nz = 18, layers = 4, h = 16f)

    private fun field(f: (x: Int, z: Int, l: Int) -> Float): FloatArray {
        val a = FloatArray(geom.cells)
        for (l in 0 until geom.layers)
            for (z in 0 until geom.nz)
                for (x in 0 until geom.nx)
                    a[geom.idx(x, z, l)] = f(x, z, l)
        return a
    }

    private fun interior3(check: (x: Int, z: Int, l: Int, i: Int) -> Unit) {
        for (l in 1 until geom.layers - 1)
            for (z in 1 until geom.nz - 1)
                for (x in 1 until geom.nx - 1)
                    check(x, z, l, geom.idx(x, z, l))
    }

    @Test
    fun `gradient3 of a linear-in-y field is exact`() {
        val c = 0.5f // per metre of altitude
        val s = field { _, _, l -> c * geom.layerY[l] }
        val gx = FloatArray(geom.cells)
        val gz = FloatArray(geom.cells)
        val gy = FloatArray(geom.cells)
        Operators.gradient3(geom, s, gx, gz, gy)
        interior3 { _, _, _, i ->
            assertEquals(0f, gx[i], 1e-4f)
            assertEquals(0f, gz[i], 1e-4f)
            assertEquals(c, gy[i], 1e-3f)
        }
    }

    @Test
    fun `gradient3 respects non-uniform layer spacing`() {
        val g = GridGeometry(nx = 8, nz = 8, layers = 4, h = 16f, layerY = floatArrayOf(8f, 24f, 56f, 120f))
        val s = FloatArray(g.cells)
        for (l in 0 until 4) for (z in 0 until 8) for (x in 0 until 8)
            s[g.idx(x, z, l)] = 2f * g.layerY[l] // linear in actual height
        val gx = FloatArray(g.cells)
        val gz = FloatArray(g.cells)
        val gy = FloatArray(g.cells)
        Operators.gradient3(g, s, gx, gz, gy)
        for (l in 0 until 4) {
            assertEquals(2f, gy[g.idx(4, 4, l)], 1e-3f, "∂/∂y wrong at layer $l (non-uniform spacing)")
        }
    }

    @Test
    fun `divergence3 includes the vertical stretch term`() {
        // u = αx, v = 0, w = γy → ∇·v = α + γ
        val alpha = 0.2f
        val gamma = 0.05f
        val u = field { x, _, _ -> alpha * x * geom.h }
        val v = field { _, _, _ -> 0f }
        val w = field { _, _, l -> gamma * geom.layerY[l] }
        val d = FloatArray(geom.cells)
        Operators.divergence3(geom, u, v, w, d, FloatArray(geom.cells))
        interior3 { _, _, _, i -> assertEquals(alpha + gamma, d[i], 1e-3f) }
    }

    @Test
    fun `curl3 sees overturning circulation invisible to the 2D curl`() {
        // a sea-breeze-like cell in the x–y plane: u = β·y, w = −β·x… take the
        // simple shear u(y): ξ = 0, η = ∂u/∂y, ζ = 0
        val beta = 0.1f
        val u = field { _, _, l -> beta * geom.layerY[l] }
        val v = field { _, _, _ -> 0f }
        val w = field { _, _, _ -> 0f }
        val xi = FloatArray(geom.cells)
        val eta = FloatArray(geom.cells)
        val zeta = FloatArray(geom.cells)
        Operators.curl3(geom, u, v, w, xi, eta, zeta, FloatArray(geom.cells))
        interior3 { _, _, _, i ->
            assertEquals(0f, xi[i], 1e-4f)
            assertEquals(beta, eta[i], 1e-3f, "vertical shear must appear in η")
            assertEquals(0f, zeta[i], 1e-4f)
        }
    }

    @Test
    fun `divergence3 of curl3 vanishes (uniform spacing)`() {
        // arbitrary smooth 3D vector field
        val u = field { x, z, l -> (Math.sin(x * 0.4) * 3 + z * 0.1 + l * 0.7).toFloat() }
        val v = field { x, z, l -> (Math.cos(z * 0.5) * 2 - x * 0.05 + l * l * 0.3).toFloat() }
        val w = field { x, z, l -> (Math.sin((x + z) * 0.3) + l * 0.2).toFloat() }
        val xi = FloatArray(geom.cells)
        val eta = FloatArray(geom.cells)
        val zeta = FloatArray(geom.cells)
        Operators.curl3(geom, u, v, w, xi, eta, zeta, FloatArray(geom.cells))

        // slot mapping (see curl3 docs): x→ξ, z→η, y→ζ; divergence3 takes
        // (u = x-comp, v = z-comp, w = vertical) ⇒ (xi, eta, zeta).
        val d = FloatArray(geom.cells)
        Operators.divergence3(geom, xi, eta, zeta, d, FloatArray(geom.cells))
        var worst = 0f
        interior3 { _, _, _, i -> worst = maxOf(worst, abs(d[i])) }
        assertTrue(worst < 1e-3f, "div(curl) should vanish, worst=$worst")
    }

    @Test
    fun `curl3 of gradient3 vanishes (uniform spacing)`() {
        val s = field { x, z, l -> (Math.sin(x * 0.5) * 10 + Math.cos(z * 0.4) * 8 + l * l * 2.0).toFloat() }
        val gx = FloatArray(geom.cells)
        val gz = FloatArray(geom.cells)
        val gy = FloatArray(geom.cells)
        Operators.gradient3(geom, s, gx, gz, gy)
        val xi = FloatArray(geom.cells)
        val eta = FloatArray(geom.cells)
        val zeta = FloatArray(geom.cells)
        Operators.curl3(geom, gx, gz, gy, xi, eta, zeta, FloatArray(geom.cells))
        var worst = 0f
        interior3 { _, _, _, i ->
            worst = maxOf(worst, abs(xi[i]), abs(eta[i]), abs(zeta[i]))
        }
        assertTrue(worst < 1e-3f, "curl(grad) should vanish, worst=$worst")
    }

    @Test
    fun `laplacian3 of a quadratic-in-y field is constant`() {
        // s = y² → ∇²s = 2 (uniform spacing)
        val s = field { _, _, l -> geom.layerY[l] * geom.layerY[l] }
        val lap = FloatArray(geom.cells)
        Operators.laplacian3(geom, s, lap)
        interior3 { _, _, _, i -> assertEquals(2f, lap[i], 1e-2f) }
    }

    @Test
    fun `advect3 with uniform updraft shifts a blob one layer down-trace`() {
        // w = +1 m/s, dt = 32 s, spacing 32 m → backtrace exactly one layer down:
        // the blob in layer 1 appears in layer 2.
        val peak = geom.idx(10, 9, 1)
        val s = FloatArray(geom.cells).also { it[peak] = 50f }
        val zero = FloatArray(geom.cells)
        val w = FloatArray(geom.cells) { 1f }
        val out = FloatArray(geom.cells)
        Operators.advect3(geom, s, zero, zero, w, dt = 32f, dst = out)
        assertEquals(50f, out[geom.idx(10, 9, 2)], 1e-3f, "blob should rise one layer")
        assertEquals(0f, out[peak], 1e-3f)
    }

    @Test
    fun `advect3 uniform field is a no-op under arbitrary 3D wind`() {
        val s = FloatArray(geom.cells) { 4.25f }
        val u = field { x, z, l -> ((x + z + l) % 7 - 3).toFloat() }
        val v = field { x, z, _ -> ((x * z) % 5 - 2).toFloat() }
        val w = field { _, z, l -> ((z + l) % 3 - 1).toFloat() }
        val out = FloatArray(geom.cells)
        Operators.advect3(geom, s, u, v, w, dt = 20f, dst = out)
        for (i in 0 until geom.cells) assertEquals(4.25f, out[i], 1e-5f)
    }

    @Test
    fun `materialDerivative of a linear field under uniform wind is the expected dot product`() {
        // s = a·x_m + b·z_m, wind (u0, v0) → (v·∇)s = a·u0 + b·v0
        val a = 0.3f
        val b = -0.7f
        val s = field { x, z, _ -> a * x * geom.h + b * z * geom.h }
        val u = FloatArray(geom.cells) { 5f }
        val v = FloatArray(geom.cells) { 2f }
        val dst = FloatArray(geom.cells)
        Operators.materialDerivative(geom, s, u, v, dst, FloatArray(geom.cells), FloatArray(geom.cells))
        interior3 { _, _, _, i -> assertEquals(a * 5f + b * 2f, dst[i], 1e-3f) }
    }

    @Test
    fun `single-layer grid degrades gracefully - vertical derivatives are zero`() {
        val g = GridGeometry(nx = 8, nz = 8, layers = 1, h = 16f)
        val s = FloatArray(g.cells) { it.toFloat() }
        val gy = FloatArray(g.cells)
        Operators.ddy(g, s, gy)
        for (i in 0 until g.cells) assertEquals(0f, gy[i])
    }
}
