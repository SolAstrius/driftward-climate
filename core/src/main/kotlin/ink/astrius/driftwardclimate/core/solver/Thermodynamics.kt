package ink.astrius.driftwardclimate.core.solver

import kotlin.math.exp
import kotlin.math.pow

/**
 * Moist thermodynamics (spec 01 §6). Pure formulas, SI-ish units:
 * temperatures in Kelvin (°C where named), pressure either *normalised*
 * (sea-level standard = 1 — Sable's scale, what the fields carry) or kPa
 * (what the moisture formulas want); q's in kg/kg.
 */
object Thermodynamics {

    /** R/cp for dry air. */
    const val KAPPA: Float = 0.2854f

    /** Standard sea-level pressure, kPa (converts normalised → kPa). */
    const val SEA_LEVEL_KPA: Float = 101.325f

    /** L_v / c_p — Kelvin of warming per kg/kg condensed (the storm engine). */
    const val LATENT_HEAT_K_PER_QV: Float = 2491f

    const val CELSIUS_ZERO_K: Float = 273.15f

    /** Exner function Π = (P/P₀)^κ with P₀ = 1 (normalised pressure). */
    fun exner(pNorm: Float): Float = pNorm.pow(KAPPA)

    /** Diagnose actual temperature from potential temperature: T = θ·Π. */
    fun temperature(thetaK: Float, pNorm: Float): Float = thetaK * exner(pNorm)

    /** Potential temperature from actual: θ = T/Π. Conserved under adiabatic lift. */
    fun theta(tK: Float, pNorm: Float): Float = tK / exner(pNorm)

    /**
     * Normalised density ρ/ρ₀ via ideal gas: 1 at (P=1, T=ref). What buoyancy
     * keys on — cold/dense columns ⇒ more balloon lift (spec 02 §B.3).
     */
    fun densityNorm(pNorm: Float, tK: Float, refK: Float = 288.15f): Float =
        pNorm * refK / tK

    /** Tetens saturation vapour pressure, kPa, over water. */
    fun saturationVapourKPa(tC: Float): Float =
        0.611f * exp(17.27f * tC / (tC + 237.3f))

    /** Saturation specific humidity, kg/kg, at (T, P). */
    fun qSat(tK: Float, pKPa: Float): Float {
        val es = saturationVapourKPa(tK - CELSIUS_ZERO_K)
        return 0.622f * es / (pKPa - 0.378f * es)
    }

    /** Relative humidity 0..∞ (≥1 ⇒ condensation, spec 01 §6). */
    fun relativeHumidity(qv: Float, tK: Float, pKPa: Float): Float =
        qv / qSat(tK, pKPa)

    /**
     * Virtual potential temperature θ_v = θ(1 + 0.61·q_v − q_c):
     * moist air is lighter, cloud water loads it down.
     */
    fun virtualTheta(thetaK: Float, qv: Float, qc: Float): Float =
        thetaK * (1f + 0.61f * qv - qc)
}
