package ink.astrius.driftwardclimate.core.model

import ink.astrius.driftwardclimate.core.api.BaselinePort
import ink.astrius.driftwardclimate.core.api.ClockPort
import ink.astrius.driftwardclimate.core.api.SeasonPort
import ink.astrius.driftwardclimate.core.config.ClimateConfig
import ink.astrius.driftwardclimate.core.solver.Thermodynamics
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/**
 * T0 — the stateless analytic climatology (spec 01 §8, 06 §2). What any
 * position has with ZERO simulation: biome θ/humidity targets (via
 * [BaselinePort]) + season + diurnal cycle + the Sable-calibrated pressure
 * base state. The solver's relaxation target, the T2 boundary condition, and
 * the read of last resort everywhere else.
 *
 * **Pressure calibration (spec 02 §B):** [pressure0] reproduces Sable's
 * default barometric curve — `exp(−0.004·(y−sea))`, clamped at 1.5 below sea
 * level, smoothstep-faded to exactly 0 at build height — so wherever
 * anomalies are zero our answer ≡ Sable's native answer and every coverage
 * seam / LoD downgrade is smooth by construction.
 *
 * **θ vs T:** the baseline carries potential temperature θ; actual T is
 * *diagnosed* via the Exner function, so the altitude lapse — the feature
 * that started this whole mod — emerges from the pressure curve instead of
 * being a bolted-on per-block term.
 */
class Baseline(
    private val port: BaselinePort,
    private val season: SeasonPort,
    private val clock: ClockPort,
    private val cfg: ClimateConfig = ClimateConfig(),
) {

    // ── pressure base state ────────────────────────────────────────────────

    /** Normalised pressure at altitude [y] (sea level = 1) — Sable's curve. */
    fun pressure0(y: Float): Float {
        val sea = port.seaLevel().toFloat()
        var p = exp(-cfg.pressureScalePerBlock * (y - sea))
        if (p > cfg.pressureClampBelowSea) p = cfg.pressureClampBelowSea
        if (y >= cfg.pressureFadeStartY) {
            if (y >= cfg.buildHeight) return 0f
            val t = (y - cfg.pressureFadeStartY) / (cfg.buildHeight - cfg.pressureFadeStartY)
            p *= 1f - t * t * (3f - 2f * t) // smoothstep fade: C1 at both ends
        }
        return p
    }

    // ── thermal baseline ───────────────────────────────────────────────────

    /** Season θ offset: +amp mid-summer (phase 0.25), −amp mid-winter (0.75). */
    fun seasonOffsetK(): Float =
        (sin(2.0 * PI * season.seasonPhase()) * cfg.seasonAmplitudeK).toFloat()

    /** Diurnal θ offset: +amp at [ClimateConfig.diurnalPeakTicks] (~14:00). */
    fun diurnalOffsetK(): Float {
        val tod = (clock.worldTimeTicks() % 24000L + 24000L) % 24000L
        val phase = (tod - cfg.diurnalPeakTicks) / 24000.0
        return (cos(2.0 * PI * phase) * cfg.diurnalAmplitudeK).toFloat()
    }

    /** Baseline potential temperature, Kelvin, at the cell — time-aware. */
    fun theta0(cellX: Int, cellZ: Int): Float =
        port.thetaTarget(cellX, cellZ) + seasonOffsetK() + diurnalOffsetK()

    /** Baseline specific humidity, kg/kg, at the cell. */
    fun humidity0(cellX: Int, cellZ: Int): Float = port.humidityTarget(cellX, cellZ)

    // ── diagnosed quantities ───────────────────────────────────────────────

    /**
     * Baseline actual temperature, Kelvin, at (cell, altitude):
     * T = θ·(P^ (κ·lapseScale)) — colder aloft because pressure falls.
     * lapseExponentScale 1 = dry-adiabatic, <1 = more stable.
     */
    fun temperature0(cellX: Int, cellZ: Int, y: Float): Float {
        val p = pressure0(y)
        if (p <= 0f) return 0f
        val ex = p.pow(Thermodynamics.KAPPA * cfg.lapseExponentScale)
        return theta0(cellX, cellZ) * ex
    }

    /** [temperature0] in °C — what the Thermoo provider emits (spec 02 §A). */
    fun temperature0C(cellX: Int, cellZ: Int, y: Float): Float =
        temperature0(cellX, cellZ, y) - Thermodynamics.CELSIUS_ZERO_K

    /** Normalised density at (cell, y) — what buoyancy-true lift wants. */
    fun densityNorm0(cellX: Int, cellZ: Int, y: Float): Float {
        val p = pressure0(y)
        if (p <= 0f) return 0f
        return Thermodynamics.densityNorm(p, temperature0(cellX, cellZ, y), cfg.referenceTemperatureK)
    }

    /** Baseline relative humidity 0..∞ at (cell, y). */
    fun relativeHumidity0(cellX: Int, cellZ: Int, y: Float): Float {
        val pKPa = pressure0(y) * Thermodynamics.SEA_LEVEL_KPA
        if (pKPa <= 0f) return 0f
        return Thermodynamics.relativeHumidity(
            humidity0(cellX, cellZ),
            temperature0(cellX, cellZ, y),
            pKPa,
        )
    }
}
