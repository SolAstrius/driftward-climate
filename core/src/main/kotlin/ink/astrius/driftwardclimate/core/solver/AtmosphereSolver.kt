package ink.astrius.driftwardclimate.core.solver

import ink.astrius.driftwardclimate.core.config.ClimateConfig
import ink.astrius.driftwardclimate.core.field.FieldDef
import ink.astrius.driftwardclimate.core.field.FieldHandle
import ink.astrius.driftwardclimate.core.field.FieldRegion
import ink.astrius.driftwardclimate.core.field.FftPoisson
import ink.astrius.driftwardclimate.core.field.Operators
import ink.astrius.driftwardclimate.core.field.PoissonSolver2D
import ink.astrius.driftwardclimate.core.field.WorldFieldGrid
import ink.astrius.driftwardclimate.core.model.Reconstruction
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** The climate plugin's prognostic field set (registered into the D12 grid). */
class ClimateFields(grid: WorldFieldGrid) {
    /** θ′ — potential-temperature perturbation from the T0/T1 reconstruction, K. */
    val thetaP: FieldHandle = grid.registerField(FieldDef("theta_prime", advected = true, persistent = true))
    val u: FieldHandle = grid.registerField(FieldDef("wind_u", advected = true, persistent = true))
    val v: FieldHandle = grid.registerField(FieldDef("wind_v", advected = true, persistent = true))
    val qv: FieldHandle = grid.registerField(FieldDef("qv", advected = true, persistent = true))
    val qc: FieldHandle = grid.registerField(FieldDef("qc", advected = true, persistent = true))
    val qr: FieldHandle = grid.registerField(FieldDef("qr", advected = true))
    /** W — vertical velocity DIAGNOSED from residual divergence (01 §5), m/s. */
    val w: FieldHandle = grid.registerField(FieldDef("w_diag"))
    /** Precip reaching ground this step (rained-out q_r), kg/kg — surface coupling later. */
    val precip: FieldHandle = grid.registerField(FieldDef("precip_out"))
}

/**
 * The stable-fluids dynamical core (spec 01 §5):
 * forces → advect → diffuse → microphysics → project → relax, off-thread,
 * publish at the end. All scratch is preallocated — zero allocation per step.
 *
 * Physics choices (v1, 2.5D with stacked layers):
 *  - **PGF is hydrostatic**: per-column integral of the θ_v anomaly gives a
 *    layer pressure perturbation; its horizontal gradient accelerates the
 *    wind. Warm column → surface low → low-level inflow → thermal
 *    circulations (sea breeze) emerge.
 *  - **Coriolis is an exact rotation** (unconditionally stable) with the
 *    §2.1 signs: du/dt = −f·v, dv/dt = +f·u.
 *  - **Projection is barotropic-only**: the column-mean wind is projected
 *    divergence-free; baroclinic divergence (low-level in / high-level out)
 *    is legitimate atmosphere and survives. The per-layer residual
 *    divergence integrates upward into the diagnosed W — "the removed δ is
 *    the uplift signal" — which then vertically advects θ′/q next step.
 *  - **Full-θ advection**: θ′ + θ0 are transported together (perturbation
 *    advection alone would drop the baseline-gradient term −(v·∇)θ0).
 */
class AtmosphereSolver(
    private val region: FieldRegion,
    private val fields: ClimateFields,
    private val recon: Reconstruction,
    private val cfg: ClimateConfig = ClimateConfig(),
    private val poisson: PoissonSolver2D = FftPoisson(),
) {
    private val geom = region.geom
    private val cells = geom.cells
    private val plane = geom.planeSize

    // work copies of the prognostic state (front → work → back)
    private val wTheta = FloatArray(cells) // FULL θ during the step
    private val wU = FloatArray(cells)
    private val wV = FloatArray(cells)
    private val wQv = FloatArray(cells)
    private val wQc = FloatArray(cells)
    private val wQr = FloatArray(cells)
    private val wW = FloatArray(cells)
    private val tmp = FloatArray(cells)

    // baseline caches (refreshed each step — season/diurnal drift)
    private val theta0 = FloatArray(plane)
    private val q0 = FloatArray(plane)

    // per-layer static atmosphere (geometry never changes)
    private val pLayer = FloatArray(geom.layers) { recon.t0.pressure0(geom.layerY[it]) }
    private val exnerLayer = FloatArray(geom.layers) { Thermodynamics.exner(pLayer[it]) }
    private val pKPaLayer = FloatArray(geom.layers) { pLayer[it] * Thermodynamics.SEA_LEVEL_KPA }

    // plane scratch
    private val hp = FloatArray(cells)          // hydrostatic pressure perturbation
    private val ubar = FloatArray(plane)
    private val vbar = FloatArray(plane)
    private val ubar0 = FloatArray(plane)
    private val vbar0 = FloatArray(plane)
    private val planeA = FloatArray(plane)
    private val planeB = FloatArray(plane)
    private val divPre = FloatArray(cells)
    private val geom2d = ink.astrius.driftwardclimate.core.field.GridGeometry(geom.nx, geom.nz, 1, geom.h)

    // edge-relaxation ramp (1 at the frontier → 0 inside the margin)
    private val edgeRamp = FloatArray(plane).also { ramp ->
        val m = cfg.edgeRelaxCells
        for (z in 0 until geom.nz) for (x in 0 until geom.nx) {
            val d = minOf(x, z, geom.nx - 1 - x, geom.nz - 1 - z)
            ramp[z * geom.nx + x] = if (d >= m) 0f else (m - d).toFloat() / m
        }
    }

    /** Advance the atmosphere by [dtS] seconds (default [ClimateConfig.stepDtS]). */
    fun step(dtS: Float = cfg.stepDtS) {
        loadState()
        refreshBaseline()
        addForces(dtS)
        advectAll(dtS)
        diffuseAll(dtS)
        microphysics(dtS)
        projectAndDiagnoseW()
        relax(dtS)
        storeState()
        region.publish()
    }

    // ── 0. state in/out ────────────────────────────────────────────────────

    private fun loadState() {
        region.front(fields.u).copyInto(wU)
        region.front(fields.v).copyInto(wV)
        region.front(fields.qv).copyInto(wQv)
        region.front(fields.qc).copyInto(wQc)
        region.front(fields.qr).copyInto(wQr)
        region.front(fields.w).copyInto(wW)
        // θ′ → full θ (per-layer columns share the surface baseline for now)
        val thetaP = region.front(fields.thetaP)
        for (l in 0 until geom.layers) {
            val base = l * plane
            for (i in 0 until plane) wTheta[base + i] = thetaP[base + i]
        }
    }

    private fun refreshBaseline() {
        for (z in 0 until geom.nz) for (x in 0 until geom.nx) {
            val i = z * geom.nx + x
            theta0[i] = recon.theta(geom.originCellX + x, geom.originCellZ + z)
            q0[i] = recon.t0.humidity0(geom.originCellX + x, geom.originCellZ + z)
        }
        // promote θ′ planes to full θ
        for (l in 0 until geom.layers) {
            val base = l * plane
            for (i in 0 until plane) wTheta[base + i] += theta0[i]
        }
    }

    private fun storeState() {
        // full θ → θ′
        for (l in 0 until geom.layers) {
            val base = l * plane
            for (i in 0 until plane) wTheta[base + i] -= theta0[i]
        }
        wTheta.copyInto(region.back(fields.thetaP))
        wU.copyInto(region.back(fields.u))
        wV.copyInto(region.back(fields.v))
        wQv.copyInto(region.back(fields.qv))
        wQc.copyInto(region.back(fields.qc))
        wQr.copyInto(region.back(fields.qr))
        wW.copyInto(region.back(fields.w))
        // precip plane was written during microphysics
    }

    // ── 1. forces: hydrostatic PGF + Coriolis + drag ──────────────────────

    private fun addForces(dt: Float) {
        // hydrostatic pressure perturbation: hp[l] = Σ_{k≥l} −(g/θref)·θv′[k]·dy_k
        // (warm/moist column → negative hp below → PGF accelerates inward)
        val gOverTheta = cfg.gravity / cfg.referenceTemperatureK
        for (i in 0 until plane) {
            var acc = 0f
            for (l in geom.layers - 1 downTo 0) {
                val idx = l * plane + i
                val thetaV = Thermodynamics.virtualTheta(wTheta[idx], wQv[idx], wQc[idx])
                val thetaV0 = Thermodynamics.virtualTheta(theta0[i], q0[i], 0f)
                val dy = if (geom.layers == 1) 32f else layerThickness(l)
                acc += -gOverTheta * (thetaV - thetaV0) * dy
                hp[idx] = acc
            }
        }

        // Coriolis: exact rotation by φ = f·dt (§2.1: du=−f·v, dv=+f·u)
        val phi = cfg.coriolisF * dt
        val c = cos(phi)
        val s = sin(phi)
        for (i in 0 until cells) {
            val u0 = wU[i]
            val v0 = wV[i]
            wU[i] = u0 * c - v0 * s
            wV[i] = u0 * s + v0 * c
        }

        // PGF: v −= dt·∇hp (per layer, central differences)
        Operators.gradient(geom, hp, tmp, divPre) // tmp=∂x, divPre=∂z (borrowed)
        for (i in 0 until cells) {
            wU[i] -= dt * tmp[i]
            wV[i] -= dt * divPre[i]
        }

        // surface drag (lowest layer) + weak Rayleigh everywhere
        val drag = 1f / (1f + cfg.surfaceDragPerS * dt)
        for (i in 0 until plane) {
            wU[i] *= drag
            wV[i] *= drag
        }
        val ray = 1f / (1f + cfg.rayleighPerS * dt)
        for (i in 0 until cells) {
            wU[i] *= ray
            wV[i] *= ray
        }
    }

    private fun layerThickness(l: Int): Float = when {
        geom.layers == 1 -> 32f
        l == 0 -> geom.layerY[1] - geom.layerY[0]
        l == geom.layers - 1 -> geom.layerY[l] - geom.layerY[l - 1]
        else -> (geom.layerY[l + 1] - geom.layerY[l - 1]) * 0.5f
    }

    // ── 2. advection (3D semi-Lagrangian with diagnosed W) ────────────────

    private fun advectAll(dt: Float) {
        advectOne(wTheta, dt)
        advectOne(wQv, dt)
        advectOne(wQc, dt)
        advectOne(wQr, dt)
        // winds advect themselves (with the post-force field)
        wU.copyInto(tmp)
        Operators.advect3(geom, tmp, wU, wV, wW, dt, wU)
        wV.copyInto(tmp)
        Operators.advect3(geom, tmp, wU, wV, wW, dt, wV)
    }

    private fun advectOne(field: FloatArray, dt: Float) {
        field.copyInto(tmp)
        Operators.advect3(geom, tmp, wU, wV, wW, dt, field)
    }

    // ── 3. diffusion (explicit, stability-clamped) ────────────────────────

    private fun diffuseAll(dt: Float) {
        diffuseOne(wTheta, cfg.kappaTheta, dt)
        diffuseOne(wQv, cfg.kappaMoisture, dt)
        diffuseOne(wQc, cfg.kappaMoisture, dt)
        diffuseOne(wU, cfg.kappaWind, dt)
        diffuseOne(wV, cfg.kappaWind, dt)
    }

    private fun diffuseOne(field: FloatArray, kappa: Float, dt: Float) {
        if (kappa <= 0f) return
        // clamp α = κ·dt/h² to the explicit stability bound (01 §5)
        val alpha = min(kappa * dt / (geom.h * geom.h), 0.2f)
        val kEff = alpha * geom.h * geom.h / dt
        field.copyInto(tmp)
        Operators.diffuse(geom, tmp, kEff, dt, field)
    }

    // ── 4. microphysics (spec 01 §6) ──────────────────────────────────────

    private fun microphysics(dt: Float) {
        val precip = region.back(fields.precip)
        java.util.Arrays.fill(precip, 0f)
        for (l in 0 until geom.layers) {
            val base = l * plane
            val exner = exnerLayer[l]
            val pKPa = pKPaLayer[l]
            if (pKPa <= 0f) continue
            for (i in 0 until plane) {
                val idx = base + i
                val tK = wTheta[idx] * exner
                val qsat = Thermodynamics.qSat(tK, pKPa)

                if (wQv[idx] > qsat) {
                    // condensation + latent heat (θ gets ΔT/Π) — the storm engine
                    val dq = wQv[idx] - qsat
                    wQv[idx] -= dq
                    wQc[idx] += dq
                    wTheta[idx] += Thermodynamics.LATENT_HEAT_K_PER_QV * dq / exner
                } else if (wQc[idx] > 0f) {
                    // cloud evaporates into the deficit (cools)
                    val dq = min(wQc[idx], (qsat - wQv[idx]) * cfg.cloudEvapFraction)
                    wQc[idx] -= dq
                    wQv[idx] += dq
                    wTheta[idx] -= Thermodynamics.LATENT_HEAT_K_PER_QV * dq / exner
                }

                // autoconversion: dense cloud → rain
                if (wQc[idx] > cfg.autoconvThresholdQc) {
                    val dq = (wQc[idx] - cfg.autoconvThresholdQc) *
                        min(cfg.autoconvRatePerS * dt, 1f)
                    wQc[idx] -= dq
                    wQr[idx] += dq
                }

                // rain-out: q_r leaves the atmosphere → surface precip signal
                if (wQr[idx] > 0f) {
                    val out = wQr[idx] * min(cfg.rainoutPerS * dt, 1f)
                    wQr[idx] -= out
                    precip[i] += out
                }
            }
        }
    }

    // ── 5. barotropic projection + W diagnosis ────────────────────────────

    private fun projectAndDiagnoseW() {
        // pre-projection divergence per layer (the uplift signal, 01 §5)
        Operators.divergence(geom, wU, wV, divPre)

        // column-mean (thickness-weighted) wind
        java.util.Arrays.fill(ubar, 0f)
        java.util.Arrays.fill(vbar, 0f)
        var totalDy = 0f
        for (l in 0 until geom.layers) totalDy += layerThickness(l)
        for (l in 0 until geom.layers) {
            val wgt = layerThickness(l) / totalDy
            val base = l * plane
            for (i in 0 until plane) {
                ubar[i] += wU[base + i] * wgt
                vbar[i] += wV[base + i] * wgt
            }
        }

        // project the barotropic mode only; apply the correction to all layers
        ubar.copyInto(ubar0)
        vbar.copyInto(vbar0)
        ink.astrius.driftwardclimate.core.field.Projection.project(
            geom2d, ubar, vbar, poisson, planeA, planeB,
        )
        for (l in 0 until geom.layers) {
            val base = l * plane
            for (i in 0 until plane) {
                wU[base + i] += ubar[i] - ubar0[i]
                wV[base + i] += vbar[i] - vbar0[i]
            }
        }

        // W: integrate residual convergence upward from the ground
        // W[l] = W[l−1] − div[l]·Δy_l  (convergence below ⇒ rising air)
        for (i in 0 until plane) {
            var wAcc = 0f
            for (l in 0 until geom.layers) {
                wAcc -= divPre[l * plane + i] * layerThickness(l)
                wW[l * plane + i] = wAcc
            }
        }
    }

    // ── 6. Newtonian relaxation toward the reconstruction ─────────────────

    private fun relax(dt: Float) {
        val interior = dt / cfg.relaxTauS
        val edge = dt / cfg.edgeRelaxTauS
        for (l in 0 until geom.layers) {
            val base = l * plane
            for (i in 0 until plane) {
                val rate = min(interior + edge * edgeRamp[i], 1f)
                val idx = base + i
                wTheta[idx] += (theta0[i] - wTheta[idx]) * rate
                wQv[idx] += (q0[i] - wQv[idx]) * rate
                // winds relax to calm at the frontier only
                val edgeOnly = min(edge * edgeRamp[i], 1f)
                wU[idx] -= wU[idx] * edgeOnly
                wV[idx] -= wV[idx] * edgeOnly
            }
        }
    }
}
