package ink.astrius.driftwardclimate.core.field

import org.jtransforms.dct.FloatDCT_2D
import kotlin.math.PI
import kotlin.math.cos

/**
 * Poisson projection (spec 01 §5 step 5): solve ∇²p = ∇·v* per layer, then
 * subtract ∇p so the wind is (approximately) divergence-free; the removed
 * divergence is the uplift/convergence signal.
 *
 * Two backends (OQ2 resolved):
 *  - [FftPoisson]  — JTransforms DCT-II, O(N log N), the default. Exact for
 *    the 5-point Neumann Laplacian on the rectangular T2 window.
 *  - [SorPoisson]  — red-black SOR. The reference implementation tests
 *    cross-check FFT against, and the fallback for non-rectangular domains.
 *
 * Note (collocated grid): div/grad use central differences while the solve is
 * the compact 5-point Laplacian, so projection damps but does not annihilate
 * divergence in a single pass (standard Stam-style behaviour). Tests assert a
 * strong *reduction*, not machine zero.
 */
interface PoissonSolver2D {
    /**
     * Solve ∇²p = rhs on an nx×nz plane with Neumann boundaries.
     * [rhs] is overwritten with scratch; the solution lands in [p].
     * Implementations de-mean the RHS (Neumann compatibility).
     */
    fun solve(rhs: FloatArray, p: FloatArray, nx: Int, nz: Int, h: Float)
}

/** Red-black successive over-relaxation. */
class SorPoisson(
    private val iterations: Int = 200,
    private val omega: Float = 1.8f,
) : PoissonSolver2D {

    override fun solve(rhs: FloatArray, p: FloatArray, nx: Int, nz: Int, h: Float) {
        deMean(rhs, nx * nz)
        java.util.Arrays.fill(p, 0, nx * nz, 0f)
        val h2 = h * h
        repeat(iterations) {
            var colour = 0
            while (colour < 2) {
                for (z in 0 until nz) {
                    val row = z * nx
                    val rowM = (if (z > 0) z - 1 else 0) * nx
                    val rowP = (if (z < nz - 1) z + 1 else nz - 1) * nx
                    var x = (z + colour) and 1
                    while (x < nx) {
                        val xm = if (x > 0) x - 1 else 0
                        val xp = if (x < nx - 1) x + 1 else nx - 1
                        val gs = (p[row + xm] + p[row + xp] + p[rowM + x] + p[rowP + x] - h2 * rhs[row + x]) * 0.25f
                        p[row + x] += omega * (gs - p[row + x])
                        x += 2
                    }
                }
                colour++
            }
        }
    }
}

/**
 * DCT-II (Neumann) FFT-Poisson via JTransforms. Plans and eigenvalue tables
 * are cached per (nx, nz); instances are NOT thread-safe (one per stepping
 * thread, like the rest of the engine).
 */
class FftPoisson : PoissonSolver2D {
    private var nx = -1
    private var nz = -1
    private var dct: FloatDCT_2D? = null
    private var eigX = FloatArray(0)
    private var eigZ = FloatArray(0)

    private fun plan(nx: Int, nz: Int, h: Float) {
        if (nx == this.nx && nz == this.nz) return
        this.nx = nx
        this.nz = nz
        dct = FloatDCT_2D(nz.toLong(), nx.toLong()) // rows = z, columns = x (x-fastest layout)
        val h2 = h * h
        eigX = FloatArray(nx) { k -> ((2.0 * cos(PI * k / nx) - 2.0) / h2).toFloat() }
        eigZ = FloatArray(nz) { k -> ((2.0 * cos(PI * k / nz) - 2.0) / h2).toFloat() }
    }

    override fun solve(rhs: FloatArray, p: FloatArray, nx: Int, nz: Int, h: Float) {
        plan(nx, nz, h)
        val n = nx * nz
        deMean(rhs, n)
        rhs.copyInto(p, 0, 0, n)

        val d = dct!!
        // JTransforms identity pair is forward(scale=true) + inverse(scale=true)
        // (verified empirically — (false,true) is NOT an inverse pair).
        d.forward(p, true)
        // Divide by Laplacian eigenvalues in DCT space; zero the (0,0) mode
        // (the Neumann null space — solution defined up to a constant).
        for (z in 0 until nz) {
            val row = z * nx
            val ez = eigZ[z]
            for (x in 0 until nx) {
                val lambda = eigX[x] + ez
                p[row + x] = if (lambda == 0f) 0f else p[row + x] / lambda
            }
        }
        d.inverse(p, true)
    }
}

object Projection {

    /**
     * Project (u, v) to (approximately) divergence-free, all layers.
     * [div] and [p] are caller-provided plane-sized (nx*nz) scratch arrays.
     * Returns nothing; u/v are corrected in place.
     */
    fun project(
        geom: GridGeometry,
        u: FloatArray,
        v: FloatArray,
        solver: PoissonSolver2D,
        div: FloatArray,
        p: FloatArray,
    ) {
        val nx = geom.nx
        val nz = geom.nz
        val inv2h = 1f / (2f * geom.h)
        for (l in 0 until geom.layers) {
            val base = l * geom.planeSize
            // div(u,v) for this layer into the plane scratch
            for (z in 0 until nz) {
                val row = base + z * nx
                val rowM = base + (if (z > 0) z - 1 else 0) * nx
                val rowP = base + (if (z < nz - 1) z + 1 else nz - 1) * nx
                val out = z * nx
                div[out] = ((u[row + 1] - u[row]) + (v[rowP] - v[rowM])) * inv2h
                for (x in 1 until nx - 1) {
                    div[out + x] = ((u[row + x + 1] - u[row + x - 1]) + (v[rowP + x] - v[rowM + x])) * inv2h
                }
                val e = nx - 1
                div[out + e] = ((u[row + e] - u[row + e - 1]) + (v[rowP + e] - v[rowM + e])) * inv2h
            }

            solver.solve(div, p, nx, nz, geom.h)

            // u -= ∂p/∂x ; v -= ∂p/∂z (central, clamped)
            for (z in 0 until nz) {
                val row = base + z * nx
                val pRow = z * nx
                val pRowM = (if (z > 0) z - 1 else 0) * nx
                val pRowP = (if (z < nz - 1) z + 1 else nz - 1) * nx
                u[row] -= (p[pRow + 1] - p[pRow]) * inv2h
                v[row] -= (p[pRowP] - p[pRowM]) * inv2h
                for (x in 1 until nx - 1) {
                    u[row + x] -= (p[pRow + x + 1] - p[pRow + x - 1]) * inv2h
                    v[row + x] -= (p[pRowP + x] - p[pRowM + x]) * inv2h
                }
                val e = nx - 1
                u[row + e] -= (p[pRow + e] - p[pRow + e - 1]) * inv2h
                v[row + e] -= (p[pRowP + e] - p[pRowM + e]) * inv2h
            }
        }
    }
}

private fun deMean(a: FloatArray, n: Int) {
    var sum = 0.0
    for (i in 0 until n) sum += a[i]
    val mean = (sum / n).toFloat()
    for (i in 0 until n) a[i] -= mean
}
