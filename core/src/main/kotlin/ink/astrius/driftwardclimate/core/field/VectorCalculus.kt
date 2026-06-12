package ink.astrius.driftwardclimate.core.field

/**
 * The "other shenanigans" (spec 00 §3): vector-calculus theorems and
 * diagnostics built on [Operators] and the Poisson solvers.
 *
 *  - Helmholtz decomposition — wind = rotational + divergent parts via
 *    streamfunction ψ (∇²ψ = ζ) and velocity potential χ (∇²χ = δ).
 *  - Line/area integrals — circulation ∮v·dl and outward flux ∮v·n̂ dl over
 *    rectangles, the discrete halves of Stokes' and the divergence theorem
 *    (the §12 tests assert both identities).
 *  - Okubo–Weiss — strain² − vorticity²: negative = vortex-dominated (storm
 *    cores), positive = strain-dominated (fronts, deformation zones).
 */
object VectorCalculus {

    /**
     * Helmholtz decomposition per layer:
     *   solve ∇²χ = ∇·v   → v_div = ∇χ          (irrotational part)
     *   solve ∇²ψ = ∇×v   → v_rot = (−∂ψ/∂z, ∂ψ/∂x)  (solenoidal part)
     *
     * On a bounded Neumann domain a harmonic remainder (boundary-driven mean
     * flow) survives outside both parts; interior identities
     * `curl(v_div) ≈ 0` and `div(v_rot) ≈ 0` hold regardless.
     *
     * [chi], [psi], [scratch] are plane-sized (nx*nz); outputs are full-sized.
     */
    fun helmholtz(
        geom: GridGeometry,
        u: FloatArray,
        v: FloatArray,
        solver: PoissonSolver2D,
        uDiv: FloatArray,
        vDiv: FloatArray,
        uRot: FloatArray,
        vRot: FloatArray,
        chi: FloatArray,
        psi: FloatArray,
        scratch: FloatArray,
    ) {
        val nx = geom.nx
        val nz = geom.nz
        val plane = geom.planeSize
        val inv2h = 1f / (2f * geom.h)

        for (l in 0 until geom.layers) {
            val base = l * plane

            // χ from divergence
            planeOp(geom, u, v, base, scratch, divergence = true)
            solver.solve(scratch, chi, nx, nz, geom.h)
            // ψ from vorticity
            planeOp(geom, u, v, base, scratch, divergence = false)
            solver.solve(scratch, psi, nx, nz, geom.h)

            for (z in 0 until nz) {
                val row = z * nx
                val rowM = (if (z > 0) z - 1 else 0) * nx
                val rowP = (if (z < nz - 1) z + 1 else nz - 1) * nx
                for (x in 0 until nx) {
                    val xm = if (x > 0) x - 1 else 0
                    val xp = if (x < nx - 1) x + 1 else nx - 1
                    val dChiDx = (chi[row + xp] - chi[row + xm]) * inv2h
                    val dChiDz = (chi[rowP + x] - chi[rowM + x]) * inv2h
                    val dPsiDx = (psi[row + xp] - psi[row + xm]) * inv2h
                    val dPsiDz = (psi[rowP + x] - psi[rowM + x]) * inv2h
                    val i = base + row + x
                    uDiv[i] = dChiDx
                    vDiv[i] = dChiDz
                    uRot[i] = -dPsiDz
                    vRot[i] = dPsiDx
                }
            }
        }
    }

    /** div (true) or curl (false) of one layer into a plane-sized dst. */
    private fun planeOp(
        geom: GridGeometry,
        u: FloatArray,
        v: FloatArray,
        base: Int,
        dst: FloatArray,
        divergence: Boolean,
    ) {
        val nx = geom.nx
        val nz = geom.nz
        val inv2h = 1f / (2f * geom.h)
        for (z in 0 until nz) {
            val row = base + z * nx
            val rowM = base + (if (z > 0) z - 1 else 0) * nx
            val rowP = base + (if (z < nz - 1) z + 1 else nz - 1) * nx
            val out = z * nx
            for (x in 0 until nx) {
                val xm = if (x > 0) x - 1 else 0
                val xp = if (x < nx - 1) x + 1 else nx - 1
                dst[out + x] = if (divergence) {
                    ((u[row + xp] - u[row + xm]) + (v[rowP + x] - v[rowM + x])) * inv2h
                } else {
                    ((v[row + xp] - v[row + xm]) - (u[rowP + x] - u[rowM + x])) * inv2h
                }
            }
        }
    }

    /**
     * Circulation ∮ v·dl counterclockwise (in the x–z plane: +x then +z then
     * −x then −z) around the rectangle of cell centres [x0..x1]×[z0..z1],
     * trapezoid rule, dl = h. Exact for linear winds. Stokes' theorem pairs
     * this with [areaIntegral] of the curl.
     */
    fun circulation(
        geom: GridGeometry,
        u: FloatArray,
        v: FloatArray,
        layer: Int,
        x0: Int,
        z0: Int,
        x1: Int,
        z1: Int,
    ): Float {
        require(x0 < x1 && z0 < z1)
        val nx = geom.nx
        val base = layer * geom.planeSize
        var sum = 0.0

        // bottom edge (z=z0), +x direction: ∫ u dx
        for (x in x0..x1) {
            val wgt = if (x == x0 || x == x1) 0.5 else 1.0
            sum += wgt * u[base + z0 * nx + x]
        }
        // right edge (x=x1), +z: ∫ v dz
        for (z in z0..z1) {
            val wgt = if (z == z0 || z == z1) 0.5 else 1.0
            sum += wgt * v[base + z * nx + x1]
        }
        // top edge (z=z1), −x: −∫ u dx
        for (x in x0..x1) {
            val wgt = if (x == x0 || x == x1) 0.5 else 1.0
            sum -= wgt * u[base + z1 * nx + x]
        }
        // left edge (x=x0), −z: −∫ v dz
        for (z in z0..z1) {
            val wgt = if (z == z0 || z == z1) 0.5 else 1.0
            sum -= wgt * v[base + z * nx + x0]
        }
        return (sum * geom.h).toFloat()
    }

    /**
     * Outward flux ∮ v·n̂ dl across the same rectangle boundary — the discrete
     * left side of the divergence theorem.
     */
    fun outwardFlux(
        geom: GridGeometry,
        u: FloatArray,
        v: FloatArray,
        layer: Int,
        x0: Int,
        z0: Int,
        x1: Int,
        z1: Int,
    ): Float {
        require(x0 < x1 && z0 < z1)
        val nx = geom.nx
        val base = layer * geom.planeSize
        var sum = 0.0
        for (x in x0..x1) {
            val wgt = if (x == x0 || x == x1) 0.5 else 1.0
            sum -= wgt * v[base + z0 * nx + x] // bottom: n̂ = −ẑ
            sum += wgt * v[base + z1 * nx + x] // top:    n̂ = +ẑ
        }
        for (z in z0..z1) {
            val wgt = if (z == z0 || z == z1) 0.5 else 1.0
            sum -= wgt * u[base + z * nx + x0] // left:  n̂ = −x̂
            sum += wgt * u[base + z * nx + x1] // right: n̂ = +x̂
        }
        return (sum * geom.h).toFloat()
    }

    /**
     * ∬ s dA over [x0..x1]×[z0..z1] (cell centres), 2D trapezoid rule —
     * the area side of Stokes'/divergence theorems.
     */
    fun areaIntegral(
        geom: GridGeometry,
        s: FloatArray,
        layer: Int,
        x0: Int,
        z0: Int,
        x1: Int,
        z1: Int,
    ): Float {
        val nx = geom.nx
        val base = layer * geom.planeSize
        var sum = 0.0
        for (z in z0..z1) {
            val wz = if (z == z0 || z == z1) 0.5 else 1.0
            val row = base + z * nx
            for (x in x0..x1) {
                val wx = if (x == x0 || x == x1) 0.5 else 1.0
                sum += wz * wx * s[row + x]
            }
        }
        return (sum * geom.h * geom.h).toFloat()
    }

    /**
     * Okubo–Weiss parameter W = s_n² + s_s² − ζ² per cell:
     *   s_n = ∂u/∂x − ∂v/∂z (normal strain), s_s = ∂v/∂x + ∂u/∂z (shear strain).
     * W < 0 → rotation wins (vortex core / storm); W > 0 → strain wins
     * (front / deformation zone).
     */
    fun okuboWeiss(geom: GridGeometry, u: FloatArray, v: FloatArray, dst: FloatArray) {
        val nx = geom.nx
        val nz = geom.nz
        val inv2h = 1f / (2f * geom.h)
        for (l in 0 until geom.layers) {
            val base = l * geom.planeSize
            for (z in 0 until nz) {
                val row = base + z * nx
                val rowM = base + (if (z > 0) z - 1 else 0) * nx
                val rowP = base + (if (z < nz - 1) z + 1 else nz - 1) * nx
                for (x in 0 until nx) {
                    val xm = if (x > 0) x - 1 else 0
                    val xp = if (x < nx - 1) x + 1 else nx - 1
                    val dudx = (u[row + xp] - u[row + xm]) * inv2h
                    val dudz = (u[rowP + x] - u[rowM + x]) * inv2h
                    val dvdx = (v[row + xp] - v[row + xm]) * inv2h
                    val dvdz = (v[rowP + x] - v[rowM + x]) * inv2h
                    val sn = dudx - dvdz
                    val ss = dvdx + dudz
                    val zeta = dvdx - dudz
                    dst[row + x] = sn * sn + ss * ss - zeta * zeta
                }
            }
        }
    }
}
