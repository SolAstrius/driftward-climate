package ink.astrius.driftwardclimate.core.field

/**
 * Differential operators — hand-written central-difference stencils on the
 * chunk lattice (spec 01 §4). All kernels are allocation-free tight loops over
 * flat SoA arrays; callers pass preallocated destinations/scratch.
 *
 * ## Coordinate & wind conventions (spec 01 §2.1 — read before touching signs)
 * Axes are Minecraft's: **+x = east, +z = SOUTH, +y = up.** Winds are
 * axis-named on purpose:
 *  - `u` = +x wind — positive eastward (this one happens to match "zonal")
 *  - `v` = +z wind — positive SOUTHWARD = **−(meteorological meridional)**;
 *    we deliberately never use the name "meridional"
 *  - `w` = vertical, positive up
 * Consequences:
 *  - our ζ = ∂v/∂x − ∂u/∂z is **−(meteorological vorticity)**: positive ζ =
 *    clockwise seen from above
 *  - NH-style f-plane Coriolis in THIS frame: `du/dt = −f·v`, `dv/dt = +f·u`
 * The world is an unbounded plane — no meridians, no latitude: f is a
 * constant (f-plane). A β-plane `f(z) = f₀ − β·z` faking latitude along z is
 * a recorded future option, not v1.
 *
 * Boundary handling: clamped-neighbour (Neumann mirror) — the out-of-domain
 * neighbour is replaced by the boundary cell itself, so boundary derivatives
 * are one-sided/zero, consistent with the relax-to-T1 frontier (spec 01 §2).
 *
 * Interior rows are split from boundary columns so the hot inner loop carries
 * no branches (autovectorisation / future Vector-API drop-in, spec 01 §10).
 */
object Operators {

    /** dstX,dstZ = ∇src (per layer, central differences, spacing 2h). */
    fun gradient(geom: GridGeometry, src: FloatArray, dstX: FloatArray, dstZ: FloatArray) {
        val nx = geom.nx
        val nz = geom.nz
        val inv2h = 1f / (2f * geom.h)
        for (l in 0 until geom.layers) {
            val base = l * geom.planeSize
            for (z in 0 until nz) {
                val row = base + z * nx
                val rowM = base + (if (z > 0) z - 1 else 0) * nx
                val rowP = base + (if (z < nz - 1) z + 1 else nz - 1) * nx
                // x = 0 (clamped left neighbour)
                dstX[row] = (src[row + 1] - src[row]) * inv2h
                dstZ[row] = (src[rowP] - src[rowM]) * inv2h
                // interior — branch-free
                for (x in 1 until nx - 1) {
                    dstX[row + x] = (src[row + x + 1] - src[row + x - 1]) * inv2h
                    dstZ[row + x] = (src[rowP + x] - src[rowM + x]) * inv2h
                }
                // x = nx-1 (clamped right neighbour)
                val e = nx - 1
                dstX[row + e] = (src[row + e] - src[row + e - 1]) * inv2h
                dstZ[row + e] = (src[rowP + e] - src[rowM + e]) * inv2h
            }
        }
    }

    /** dst = ∇·(u,v) per layer. */
    fun divergence(geom: GridGeometry, u: FloatArray, v: FloatArray, dst: FloatArray) {
        val nx = geom.nx
        val nz = geom.nz
        val inv2h = 1f / (2f * geom.h)
        for (l in 0 until geom.layers) {
            val base = l * geom.planeSize
            for (z in 0 until nz) {
                val row = base + z * nx
                val rowM = base + (if (z > 0) z - 1 else 0) * nx
                val rowP = base + (if (z < nz - 1) z + 1 else nz - 1) * nx
                dst[row] = ((u[row + 1] - u[row]) + (v[rowP] - v[rowM])) * inv2h
                for (x in 1 until nx - 1) {
                    dst[row + x] = ((u[row + x + 1] - u[row + x - 1]) + (v[rowP + x] - v[rowM + x])) * inv2h
                }
                val e = nx - 1
                dst[row + e] = ((u[row + e] - u[row + e - 1]) + (v[rowP + e] - v[rowM + e])) * inv2h
            }
        }
    }

    /** dst = ∇×(u,v) = ∂v/∂x − ∂u/∂z per layer (the vorticity ζ diagnostic, OQ3). */
    fun curl(geom: GridGeometry, u: FloatArray, v: FloatArray, dst: FloatArray) {
        val nx = geom.nx
        val nz = geom.nz
        val inv2h = 1f / (2f * geom.h)
        for (l in 0 until geom.layers) {
            val base = l * geom.planeSize
            for (z in 0 until nz) {
                val row = base + z * nx
                val rowM = base + (if (z > 0) z - 1 else 0) * nx
                val rowP = base + (if (z < nz - 1) z + 1 else nz - 1) * nx
                dst[row] = ((v[row + 1] - v[row]) - (u[rowP] - u[rowM])) * inv2h
                for (x in 1 until nx - 1) {
                    dst[row + x] = ((v[row + x + 1] - v[row + x - 1]) - (u[rowP + x] - u[rowM + x])) * inv2h
                }
                val e = nx - 1
                dst[row + e] = ((v[row + e] - v[row + e - 1]) - (u[rowP + e] - u[rowM + e])) * inv2h
            }
        }
    }

    /** dst = ∇²src, 5-point stencil per layer. */
    fun laplacian(geom: GridGeometry, src: FloatArray, dst: FloatArray) {
        val nx = geom.nx
        val nz = geom.nz
        val invH2 = 1f / (geom.h * geom.h)
        for (l in 0 until geom.layers) {
            val base = l * geom.planeSize
            for (z in 0 until nz) {
                val row = base + z * nx
                val rowM = base + (if (z > 0) z - 1 else 0) * nx
                val rowP = base + (if (z < nz - 1) z + 1 else nz - 1) * nx
                run {
                    val c = src[row]
                    dst[row] = (src[row] + src[row + 1] + src[rowM] + src[rowP] - 4f * c) * invH2
                }
                for (x in 1 until nx - 1) {
                    val c = src[row + x]
                    dst[row + x] = (src[row + x - 1] + src[row + x + 1] + src[rowM + x] + src[rowP + x] - 4f * c) * invH2
                }
                val e = nx - 1
                val c = src[row + e]
                dst[row + e] = (src[row + e - 1] + src[row + e] + src[rowM + e] + src[rowP + e] - 4f * c) * invH2
            }
        }
    }

    /**
     * Semi-Lagrangian advection (spec 01 §5 step 2): for every cell, backtrace
     * along the local wind by [dt] and bilinearly sample [src] there. Wind is
     * per-layer horizontal (u,v in m/s); h converts to cell units. Backtrace
     * clamps to the domain (frontier inflow = boundary value, Neumann-style).
     * Unconditionally stable — no CFL blow-up.
     *
     * **RK2 (midpoint) backtrace:** a single straight chord lands on the
     * wrong rotation ring under SHEARED flow — measured ~1%/step systematic
     * flank erosion on a self-rotating anomaly. Sampling the wind at the
     * half-step point first makes the trajectory 2nd-order; rigid AND
     * differential rotation then conserve (coupling contract, docs 07).
     */
    fun advect(
        geom: GridGeometry,
        src: FloatArray,
        u: FloatArray,
        v: FloatArray,
        dt: Float,
        dst: FloatArray,
    ) {
        val nx = geom.nx
        val nz = geom.nz
        val scale = dt / geom.h
        val maxX = nx - 1f
        val maxZ = nz - 1f
        for (l in 0 until geom.layers) {
            val base = l * geom.planeSize
            for (z in 0 until nz) {
                val row = base + z * nx
                for (x in 0 until nx) {
                    val i = row + x
                    // midpoint
                    var mx = x - 0.5f * u[i] * scale
                    var mz = z - 0.5f * v[i] * scale
                    if (mx < 0f) mx = 0f else if (mx > maxX) mx = maxX
                    if (mz < 0f) mz = 0f else if (mz > maxZ) mz = maxZ
                    val uM = samplePlane(u, base, nx, nz, mx, mz)
                    val vM = samplePlane(v, base, nx, nz, mx, mz)
                    // full step with midpoint velocity
                    var px = x - uM * scale
                    var pz = z - vM * scale
                    if (px < 0f) px = 0f else if (px > maxX) px = maxX
                    if (pz < 0f) pz = 0f else if (pz > maxZ) pz = maxZ
                    dst[i] = samplePlane(src, base, nx, nz, px, pz)
                }
            }
        }
    }

    /**
     * BFECC advection (back-and-forth error compensated): three [advect]
     * passes recover 2nd-order accuracy and cancel most semi-Lagrangian
     * dissipation. Bilinear velocity is divergence-free only AT NODES — under
     * any curved wind profile plain SL systematically compresses (measured
     * ~1%/step mass loss on a self-rotating anomaly; rigid rotation is exact
     * because linear wind interpolates exactly). BFECC restores the lost
     * mass. Use for fields that must SURVIVE long advection (T1 anomalies);
     * plain [advect] stays the cheap default inside the forced T2 loop.
     */
    fun advectBFECC(
        geom: GridGeometry,
        src: FloatArray,
        u: FloatArray,
        v: FloatArray,
        dt: Float,
        dst: FloatArray,
        scratchA: FloatArray,
        scratchB: FloatArray,
    ) {
        advect(geom, src, u, v, dt, scratchA)          // forward
        advect(geom, scratchA, u, v, -dt, scratchB)    // back again
        // compensate: φ* = φ + (φ − back(forward(φ)))/2, then advect
        for (i in 0 until geom.cells) {
            scratchB[i] = src[i] + 0.5f * (src[i] - scratchB[i])
        }
        advect(geom, scratchB, u, v, dt, dst)
        // limiter: BFECC can overshoot — clamp to the local source extrema
        val nx = geom.nx
        val nz = geom.nz
        for (l in 0 until geom.layers) {
            val base = l * geom.planeSize
            for (z in 0 until nz) {
                val row = base + z * nx
                val rowM = base + (if (z > 0) z - 1 else 0) * nx
                val rowP = base + (if (z < nz - 1) z + 1 else nz - 1) * nx
                for (x in 0 until nx) {
                    val xm = if (x > 0) x - 1 else 0
                    val xp = if (x < nx - 1) x + 1 else nx - 1
                    var lo = src[row + x]
                    var hi = lo
                    val a = src[row + xm]; if (a < lo) lo = a else if (a > hi) hi = a
                    val b = src[row + xp]; if (b < lo) lo = b else if (b > hi) hi = b
                    val c = src[rowM + x]; if (c < lo) lo = c else if (c > hi) hi = c
                    val d = src[rowP + x]; if (d < lo) lo = d else if (d > hi) hi = d
                    val i = row + x
                    if (dst[i] < lo) dst[i] = lo else if (dst[i] > hi) dst[i] = hi
                }
            }
        }
    }

    /** Bilinear sample of one layer plane at fractional (px, pz), pre-clamped. */
    private fun samplePlane(a: FloatArray, base: Int, nx: Int, nz: Int, px: Float, pz: Float): Float {
        var x0 = px.toInt()
        var z0 = pz.toInt()
        if (x0 > nx - 2) x0 = nx - 2
        if (z0 > nz - 2) z0 = nz - 2
        val tx = px - x0
        val tz = pz - z0
        val i00 = base + z0 * nx + x0
        val v00 = a[i00]
        val v10 = a[i00 + 1]
        val v01 = a[i00 + nx]
        val v11 = a[i00 + nx + 1]
        val top = v00 + (v10 - v00) * tx
        val bot = v01 + (v11 - v01) * tx
        return top + (bot - top) * tz
    }

    // ────────────────────────────────────────────────────────────────────
    // 3D operators — the vertical axis is a real derivative axis (OQ5).
    // Vertical spacing comes from geom.layerY (may be non-uniform); central
    // differences interior, one-sided at the stack ends, zero for layers==1.
    // Velocity components: u = x-wind, v = z-wind, w = vertical (y-up), m/s.
    // ────────────────────────────────────────────────────────────────────

    /** (dstX, dstZ, dstY) = ∇₃src — full 3D gradient. */
    fun gradient3(
        geom: GridGeometry,
        src: FloatArray,
        dstX: FloatArray,
        dstZ: FloatArray,
        dstY: FloatArray,
    ) {
        gradient(geom, src, dstX, dstZ)
        ddy(geom, src, dstY)
    }

    /** dst = ∇·(u,v,w) — 3D divergence (horizontal central + ∂w/∂y). */
    fun divergence3(
        geom: GridGeometry,
        u: FloatArray,
        v: FloatArray,
        w: FloatArray,
        dst: FloatArray,
        scratch: FloatArray,
    ) {
        divergence(geom, u, v, dst)
        ddy(geom, w, scratch)
        for (i in 0 until geom.cells) dst[i] += scratch[i]
    }

    /**
     * Full vorticity vector — three overturning/spin components:
     *   ξ = ∂w/∂z − ∂v/∂y   x-axis slot (overturning in the z–y plane: sea-breeze cells)
     *   η = ∂u/∂y − ∂w/∂x   z-axis slot (overturning in the x–y plane: vertical shear rolls)
     *   ζ = ∂v/∂x − ∂u/∂z   **y-axis (vertical) slot** — matches [curl]; cyclones
     *
     * Slot mapping matters for the identities: as a vector field with
     * components (x→ξ, z→η, y→ζ), `div₃(ξ, η, ζ) = 0` and the whole vector
     * vanishes on any gradient (both verified at machine epsilon in tests).
     * Orientation is the consistent left-handed one matching [curl]'s ζ.
     */
    fun curl3(
        geom: GridGeometry,
        u: FloatArray,
        v: FloatArray,
        w: FloatArray,
        xi: FloatArray,
        eta: FloatArray,
        zeta: FloatArray,
        scratch: FloatArray,
    ) {
        val nx = geom.nx
        val nz = geom.nz
        val inv2h = 1f / (2f * geom.h)

        // ζ — the existing vertical-spin component
        curl(geom, u, v, zeta)

        // ξ = ∂w/∂z − ∂v/∂y
        ddy(geom, v, scratch)
        for (l in 0 until geom.layers) {
            val base = l * geom.planeSize
            for (z in 0 until nz) {
                val row = base + z * nx
                val rowM = base + (if (z > 0) z - 1 else 0) * nx
                val rowP = base + (if (z < nz - 1) z + 1 else nz - 1) * nx
                for (x in 0 until nx) {
                    xi[row + x] = (w[rowP + x] - w[rowM + x]) * inv2h - scratch[row + x]
                }
            }
        }

        // η = ∂u/∂y − ∂w/∂x
        ddy(geom, u, scratch)
        for (l in 0 until geom.layers) {
            val base = l * geom.planeSize
            for (z in 0 until nz) {
                val row = base + z * nx
                for (x in 0 until nx) {
                    val xm = if (x > 0) x - 1 else 0
                    val xp = if (x < nx - 1) x + 1 else nx - 1
                    eta[row + x] = scratch[row + x] - (w[row + xp] - w[row + xm]) * inv2h
                }
            }
        }
    }

    /** dst = ∇²₃src — 3D Laplacian (5-point horizontal + non-uniform vertical). */
    fun laplacian3(geom: GridGeometry, src: FloatArray, dst: FloatArray) {
        laplacian(geom, src, dst)
        if (geom.layers < 2) return
        val plane = geom.planeSize
        for (l in 0 until geom.layers) {
            val lm = maxOf(l - 1, 0)
            val lp = minOf(l + 1, geom.layers - 1)
            val dDown = if (l > 0) geom.layerY[l] - geom.layerY[l - 1] else geom.layerY[1] - geom.layerY[0]
            val dUp = if (l < geom.layers - 1) geom.layerY[l + 1] - geom.layerY[l] else dDown
            val invDc = 2f / (dDown + dUp)
            val base = l * plane
            val baseM = lm * plane
            val baseP = lp * plane
            for (i in 0 until plane) {
                val c = src[base + i]
                val up = (src[baseP + i] - c) / dUp
                val down = (c - src[baseM + i]) / dDown
                dst[base + i] += (up - down) * invDc
            }
        }
    }

    /**
     * 3D semi-Lagrangian advection: backtrace along (u, v, w), trilinear
     * sample (bilinear in two layers + linear blend across the local layer
     * spacing). Vertical displacement uses the local dy — an O(Δy) approx
     * for non-uniform stacks, exact for uniform.
     */
    fun advect3(
        geom: GridGeometry,
        src: FloatArray,
        u: FloatArray,
        v: FloatArray,
        w: FloatArray,
        dt: Float,
        dst: FloatArray,
    ) {
        if (geom.layers == 1) {
            advect(geom, src, u, v, dt, dst)
            return
        }
        val nx = geom.nx
        val nz = geom.nz
        val layers = geom.layers
        val plane = geom.planeSize
        val scale = dt / geom.h
        val maxX = nx - 1f
        val maxZ = nz - 1f
        val maxL = layers - 1f
        for (l in 0 until layers) {
            val base = l * plane
            val invDy = 1f / geom.dy(l)
            for (z in 0 until nz) {
                val row = base + z * nx
                for (x in 0 until nx) {
                    val i = row + x
                    // RK2 midpoint (see advect): half-step, sample wind there
                    var mx = x - 0.5f * u[i] * scale
                    var mz = z - 0.5f * v[i] * scale
                    var ml = l - 0.5f * w[i] * dt * invDy
                    if (mx < 0f) mx = 0f else if (mx > maxX) mx = maxX
                    if (mz < 0f) mz = 0f else if (mz > maxZ) mz = maxZ
                    if (ml < 0f) ml = 0f else if (ml > maxL) ml = maxL
                    val uM = sampleVolume(u, nx, nz, layers, plane, mx, mz, ml)
                    val vM = sampleVolume(v, nx, nz, layers, plane, mx, mz, ml)
                    val wM = sampleVolume(w, nx, nz, layers, plane, mx, mz, ml)

                    var px = x - uM * scale
                    var pz = z - vM * scale
                    var pl = l - wM * dt * invDy
                    if (px < 0f) px = 0f else if (px > maxX) px = maxX
                    if (pz < 0f) pz = 0f else if (pz > maxZ) pz = maxZ
                    if (pl < 0f) pl = 0f else if (pl > maxL) pl = maxL
                    dst[i] = sampleVolume(src, nx, nz, layers, plane, px, pz, pl)
                }
            }
        }
    }

    /** Trilinear sample at fractional (px, pz, pl), pre-clamped. */
    private fun sampleVolume(
        a: FloatArray,
        nx: Int,
        nz: Int,
        layers: Int,
        plane: Int,
        px: Float,
        pz: Float,
        pl: Float,
    ): Float {
        var l0 = pl.toInt()
        if (l0 > layers - 2) l0 = layers - 2
        val tl = pl - l0
        val base = l0 * plane
        val lo = samplePlane(a, base, nx, nz, px, pz)
        val hi = samplePlane(a, base + plane, nx, nz, px, pz)
        return lo + (hi - lo) * tl
    }

    /** dst = (v·∇)src — the advective/material-derivative term, central diffs (diagnostic). */
    fun materialDerivative(
        geom: GridGeometry,
        src: FloatArray,
        u: FloatArray,
        v: FloatArray,
        dst: FloatArray,
        scratchX: FloatArray,
        scratchZ: FloatArray,
    ) {
        gradient(geom, src, scratchX, scratchZ)
        for (i in 0 until geom.cells) dst[i] = u[i] * scratchX[i] + v[i] * scratchZ[i]
    }

    /** dst = ∂src/∂y over the layer stack (0 when layers == 1). */
    fun ddy(geom: GridGeometry, src: FloatArray, dst: FloatArray) {
        val plane = geom.planeSize
        if (geom.layers < 2) {
            java.util.Arrays.fill(dst, 0, geom.cells, 0f)
            return
        }
        for (l in 0 until geom.layers) {
            val lm = maxOf(l - 1, 0)
            val lp = minOf(l + 1, geom.layers - 1)
            val invDy = 1f / (geom.layerY[lp] - geom.layerY[lm])
            val base = l * plane
            val baseM = lm * plane
            val baseP = lp * plane
            for (i in 0 until plane) {
                dst[base + i] = (src[baseP + i] - src[baseM + i]) * invDy
            }
        }
    }

    private fun bilerp(a: FloatArray, i00: Int, nx: Int, tx: Float, tz: Float): Float {
        val v00 = a[i00]
        val v10 = a[i00 + 1]
        val v01 = a[i00 + nx]
        val v11 = a[i00 + nx + 1]
        val top = v00 + (v10 - v00) * tx
        val bot = v01 + (v11 - v01) * tx
        return top + (bot - top) * tz
    }

    /**
     * Explicit diffusion: dst = src + κ·dt·∇²src. Stable iff κ·dt/h² ≤ ¼
     * (spec 01 §5) — caller is responsible for the cadence.
     */
    fun diffuse(
        geom: GridGeometry,
        src: FloatArray,
        kappa: Float,
        dt: Float,
        dst: FloatArray,
    ) {
        val nx = geom.nx
        val nz = geom.nz
        val alpha = kappa * dt / (geom.h * geom.h)
        for (l in 0 until geom.layers) {
            val base = l * geom.planeSize
            for (z in 0 until nz) {
                val row = base + z * nx
                val rowM = base + (if (z > 0) z - 1 else 0) * nx
                val rowP = base + (if (z < nz - 1) z + 1 else nz - 1) * nx
                run {
                    val c = src[row]
                    dst[row] = c + alpha * (src[row] + src[row + 1] + src[rowM] + src[rowP] - 4f * c)
                }
                for (x in 1 until nx - 1) {
                    val c = src[row + x]
                    dst[row + x] = c + alpha * (src[row + x - 1] + src[row + x + 1] + src[rowM + x] + src[rowP + x] - 4f * c)
                }
                val e = nx - 1
                val c = src[row + e]
                dst[row + e] = c + alpha * (src[row + e - 1] + src[row + e] + src[rowM + e] + src[rowP + e] - 4f * c)
            }
        }
    }
}
