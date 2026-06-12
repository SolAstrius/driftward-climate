package ink.astrius.driftwardclimate.core.field

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The multivariable-calculus contract: structural identities (curl∘grad = 0,
 * div∘curl = 0), the integral theorems (Stokes, divergence), Helmholtz
 * decomposition, and the Okubo–Weiss discriminant.
 */
class VectorCalculusTest {

    private val geom = GridGeometry(nx = 40, nz = 36, layers = 1, h = 16f)

    private fun field(f: (x: Int, z: Int) -> Float): FloatArray {
        val a = FloatArray(geom.cells)
        for (z in 0 until geom.nz) for (x in 0 until geom.nx) a[geom.idx(x, z, 0)] = f(x, z)
        return a
    }

    // ── structural identities ──────────────────────────────────────────────

    @Test
    fun `curl of a gradient is zero to machine epsilon`() {
        // arbitrary smooth scalar; central differences commute exactly
        val s = field { x, z ->
            (sin(2.0 * PI * x / geom.nx) * 40 + cos(2.0 * PI * 2 * z / geom.nz) * 25 + x * 0.7 + z * z * 0.03).toFloat()
        }
        val gx = FloatArray(geom.cells)
        val gz = FloatArray(geom.cells)
        val zeta = FloatArray(geom.cells)
        Operators.gradient(geom, s, gx, gz)
        Operators.curl(geom, gx, gz, zeta)
        for (z in 1 until geom.nz - 1) for (x in 1 until geom.nx - 1) {
            assertTrue(abs(zeta[geom.idx(x, z, 0)]) < 1e-4f, "curl(grad) = ${zeta[geom.idx(x, z, 0)]} at ($x,$z)")
        }
    }

    @Test
    fun `divergence of a rotated gradient is zero to machine epsilon`() {
        // 2D analogue of div(curl)=0: v = (−∂s/∂z, ∂s/∂x) is divergence-free
        val s = field { x, z ->
            (cos(2.0 * PI * x / geom.nx) * 30 + sin(2.0 * PI * 3 * z / geom.nz) * 20).toFloat()
        }
        val gx = FloatArray(geom.cells)
        val gz = FloatArray(geom.cells)
        Operators.gradient(geom, s, gx, gz)
        val u = FloatArray(geom.cells) { -gz[it] }
        val v = FloatArray(geom.cells) { gx[it] }
        val div = FloatArray(geom.cells)
        Operators.divergence(geom, u, v, div)
        for (z in 1 until geom.nz - 1) for (x in 1 until geom.nx - 1) {
            assertTrue(abs(div[geom.idx(x, z, 0)]) < 1e-4f, "div(curl) = ${div[geom.idx(x, z, 0)]}")
        }
    }

    // ── integral theorems ──────────────────────────────────────────────────

    @Test
    fun `Stokes - circulation equals area integral of curl for rigid rotation`() {
        val omega = 0.02f
        val cx = geom.nx / 2f
        val cz = geom.nz / 2f
        val u = field { _, z -> -omega * (z - cz) * geom.h }
        val v = field { x, _ -> omega * (x - cx) * geom.h }

        val zeta = FloatArray(geom.cells)
        Operators.curl(geom, u, v, zeta)

        val circ = VectorCalculus.circulation(geom, u, v, 0, 8, 7, 30, 27)
        val area = VectorCalculus.areaIntegral(geom, zeta, 0, 8, 7, 30, 27)
        // both are exact for a linear wind → tight relative tolerance
        assertEquals(circ, area, abs(circ) * 0.002f + 1f)
        // and the analytic value: Γ = 2ω · A
        val rectArea = (30 - 8) * (27 - 7) * geom.h * geom.h
        assertEquals(2f * omega * rectArea, circ, abs(circ) * 0.01f)
    }

    @Test
    fun `Stokes holds on a smooth nonlinear wind`() {
        val u = field { x, z -> (sin(2.0 * PI * x / geom.nx) * 5 + cos(2.0 * PI * z / geom.nz) * 3).toFloat() }
        val v = field { x, z -> (cos(2.0 * PI * 2 * x / geom.nx) * 4 - sin(2.0 * PI * z / geom.nz) * 6).toFloat() }
        val zeta = FloatArray(geom.cells)
        Operators.curl(geom, u, v, zeta)
        val circ = VectorCalculus.circulation(geom, u, v, 0, 5, 5, 33, 29)
        val area = VectorCalculus.areaIntegral(geom, zeta, 0, 5, 5, 33, 29)
        val scale = maxOf(abs(circ), abs(area), 1f)
        assertTrue(abs(circ - area) / scale < 0.05f, "Stokes: ∮=$circ vs ∬ζ=$area")
    }

    @Test
    fun `divergence theorem - outward flux equals area integral of divergence`() {
        val alpha = 0.15f
        val beta = -0.06f
        val u = field { x, _ -> alpha * x * geom.h }
        val v = field { _, z -> beta * z * geom.h }
        val div = FloatArray(geom.cells)
        Operators.divergence(geom, u, v, div)

        val flux = VectorCalculus.outwardFlux(geom, u, v, 0, 6, 6, 32, 28)
        val area = VectorCalculus.areaIntegral(geom, div, 0, 6, 6, 32, 28)
        assertEquals(flux, area, abs(flux) * 0.002f + 1f)
        // analytic: ∯ = (α+β)·A
        val rectArea = (32 - 6) * (28 - 6) * geom.h * geom.h
        assertEquals((alpha + beta) * rectArea, flux, abs(flux) * 0.01f)
    }

    // ── Helmholtz decomposition ────────────────────────────────────────────

    @Test
    fun `Helmholtz - rotational part is divergence-free, divergent part is curl-free`() {
        val u = field { x, z -> (sin(2.0 * PI * x / geom.nx) * 4 + cos(2.0 * PI * 2 * z / geom.nz) * 2).toFloat() }
        val v = field { x, z -> (cos(2.0 * PI * z / geom.nz) * 3 - sin(2.0 * PI * 3 * x / geom.nx) * 2).toFloat() }

        val n = geom.cells
        val uDiv = FloatArray(n); val vDiv = FloatArray(n)
        val uRot = FloatArray(n); val vRot = FloatArray(n)
        VectorCalculus.helmholtz(
            geom, u, v, FftPoisson(), uDiv, vDiv, uRot, vRot,
            FloatArray(geom.planeSize), FloatArray(geom.planeSize), FloatArray(geom.planeSize),
        )

        val checkDiv = FloatArray(n)
        Operators.divergence(geom, uRot, vRot, checkDiv)
        val checkCurl = FloatArray(n)
        Operators.curl(geom, uDiv, vDiv, checkCurl)

        // scale = typical original derivative magnitude
        val origDiv = FloatArray(n)
        Operators.divergence(geom, u, v, origDiv)
        var scale = 0f
        for (i in 0 until n) scale = maxOf(scale, abs(origDiv[i]))

        for (z in 3 until geom.nz - 3) for (x in 3 until geom.nx - 3) {
            val i = geom.idx(x, z, 0)
            assertTrue(abs(checkDiv[i]) < scale * 0.1f, "div(v_rot)=${checkDiv[i]} at ($x,$z), scale=$scale")
            assertTrue(abs(checkCurl[i]) < scale * 0.1f, "curl(v_div)=${checkCurl[i]} at ($x,$z), scale=$scale")
        }
    }

    @Test
    fun `Helmholtz parts carry the right derivatives of the original wind`() {
        val u = field { x, z -> (sin(2.0 * PI * 2 * x / geom.nx) * 3 + cos(2.0 * PI * z / geom.nz)).toFloat() }
        val v = field { x, z -> (sin(2.0 * PI * z / geom.nz) * 4 + cos(2.0 * PI * x / geom.nx) * 2).toFloat() }
        val n = geom.cells
        val uDiv = FloatArray(n); val vDiv = FloatArray(n)
        val uRot = FloatArray(n); val vRot = FloatArray(n)
        VectorCalculus.helmholtz(
            geom, u, v, FftPoisson(), uDiv, vDiv, uRot, vRot,
            FloatArray(geom.planeSize), FloatArray(geom.planeSize), FloatArray(geom.planeSize),
        )
        // div(v_div) ≈ div(v) and curl(v_rot) ≈ curl(v) in the interior
        val dOrig = FloatArray(n); val dPart = FloatArray(n)
        Operators.divergence(geom, u, v, dOrig)
        Operators.divergence(geom, uDiv, vDiv, dPart)
        val cOrig = FloatArray(n); val cPart = FloatArray(n)
        Operators.curl(geom, u, v, cOrig)
        Operators.curl(geom, uRot, vRot, cPart)

        var scaleD = 0f; var scaleC = 0f
        for (i in 0 until n) { scaleD = maxOf(scaleD, abs(dOrig[i])); scaleC = maxOf(scaleC, abs(cOrig[i])) }
        for (z in 4 until geom.nz - 4) for (x in 4 until geom.nx - 4) {
            val i = geom.idx(x, z, 0)
            assertTrue(abs(dPart[i] - dOrig[i]) < scaleD * 0.15f, "δ mismatch at ($x,$z)")
            assertTrue(abs(cPart[i] - cOrig[i]) < scaleC * 0.15f, "ζ mismatch at ($x,$z)")
        }
    }

    // ── Okubo–Weiss ────────────────────────────────────────────────────────

    @Test
    fun `Okubo-Weiss is negative in a vortex and positive in a strain flow`() {
        val cx = geom.nx / 2f
        val cz = geom.nz / 2f
        // rigid rotation → pure vorticity → W = −ζ² < 0
        val uR = field { _, z -> -(z - cz) * 0.1f * geom.h }
        val vR = field { x, _ -> (x - cx) * 0.1f * geom.h }
        val w = FloatArray(geom.cells)
        VectorCalculus.okuboWeiss(geom, uR, vR, w)
        assertTrue(w[geom.idx(20, 18, 0)] < 0f, "vortex core should be W<0, got ${w[geom.idx(20, 18, 0)]}")

        // pure deformation (u=αx, v=−αz) → zero vorticity → W > 0
        val uS = field { x, _ -> (x - cx) * 0.1f * geom.h }
        val vS = field { _, z -> -(z - cz) * 0.1f * geom.h }
        VectorCalculus.okuboWeiss(geom, uS, vS, w)
        assertTrue(w[geom.idx(20, 18, 0)] > 0f, "strain zone should be W>0, got ${w[geom.idx(20, 18, 0)]}")
    }
}
