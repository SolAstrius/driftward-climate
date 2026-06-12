package ink.astrius.driftwardclimate.core.config

/**
 * Core tunables (spec 01 §10). Plain data class — the adapter materialises it
 * from the mod config; tests construct it directly.
 */
data class ClimateConfig(
    // ── T0 pressure base state — CALIBRATED TO SABLE (spec 02 §B) ─────────
    // pressure0(y) must equal Sable's default barometric curve wherever
    // anomalies are zero, or every coverage seam becomes a lift jump.
    /** Sable `createDefault` exponent: exp(−scale·(y − seaLevel)). */
    val pressureScalePerBlock: Float = 0.004f,
    /** Sable clamps the curve at 1.5 below sea level. */
    val pressureClampBelowSea: Float = 1.5f,
    /** Build-height ceiling: pressure is exactly 0 here (Sable Bézier-fades). */
    val buildHeight: Float = 320f,
    /** Where our smoothstep fade to 0 begins (C1 at both ends). */
    val pressureFadeStartY: Float = 256f,

    // ── T0 thermal baseline (spec 01 §8) ──────────────────────────────────
    /** Season swing: θ offset = +amp at mid-summer, −amp at mid-winter. */
    val seasonAmplitudeK: Float = 8f,
    /** Diurnal swing about the daily mean. */
    val diurnalAmplitudeK: Float = 6f,
    /** Tick-of-day of the warm peak (~14:00 — T_ground phase lag, 01 §7). */
    val diurnalPeakTicks: Int = 8000,
    /**
     * Scales the Exner exponent when diagnosing baseline T from θ:
     * 1 = dry-adiabatic profile (steepest), <1 = more stable (gentler lapse).
     */
    val lapseExponentScale: Float = 1f,

    // ── T0/T1 reconstruction (spec 06 §3, OQ10) ───────────────────────────
    /** Seeded sub-grid θ texture amplitude. NEVER applied to pressure. */
    val seededDetailAmpK: Float = 0.4f,

    // ── reference state ───────────────────────────────────────────────────
    /** 15 °C — density normalisation anchor (ρ_norm = 1 at sea level, 15 °C). */
    val referenceTemperatureK: Float = 288.15f,
)
