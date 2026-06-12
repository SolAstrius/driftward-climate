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

    // ── solver (spec 01 §5) ───────────────────────────────────────────────
    /**
     * Solver Δt in seconds per step (cadence is the adapter's business).
     * Semi-Lagrangian advection is STABLE at any CFL but loses sharp features
     * when winds cross many cells per step — keep `u·dt/h ≲ O(1)`:
     * at h = 16 m and gusts ~15 m/s, dt = 5 s ⇒ CFL ≈ 5. Don't crank this up.
     */
    val stepDtS: Float = 5f,
    /** f-plane Coriolis parameter, rad/s. §2.1 signs: du=−f·v, dv=+f·u. */
    val coriolisF: Float = 5e-4f,
    /** Buoyancy/PGF strength: g (m/s²) over the reference θ. */
    val gravity: Float = 9.81f,
    /** Surface friction on the lowest layer, 1/s. */
    val surfaceDragPerS: Float = 2e-3f,
    /** Weak Rayleigh damping on all layers, 1/s (keeps winds bounded). */
    val rayleighPerS: Float = 2e-5f,
    /** Diffusivities, m²/s (explicit — stability-clamped internally). */
    val kappaWind: Float = 0.5f,
    val kappaTheta: Float = 0.5f,
    val kappaMoisture: Float = 0.5f,
    /** Newtonian relaxation of θ′/q_v toward the T1+T0 reconstruction, s. */
    val relaxTauS: Float = 7200f,
    /** Extra edge forcing: ramp width (cells) and time-scale (s) at the frontier. */
    val edgeRelaxCells: Int = 3,
    val edgeRelaxTauS: Float = 300f,
    /** Cloud→rain autoconversion: threshold (kg/kg) and rate (1/s). */
    val autoconvThresholdQc: Float = 8e-4f,
    val autoconvRatePerS: Float = 1e-3f,
    /** Rain-out: q_r removal rate, 1/s (reaches the ground reservoir later). */
    val rainoutPerS: Float = 5e-4f,
    /** Fraction of the saturation deficit that cloud evaporates per step. */
    val cloudEvapFraction: Float = 0.5f,
)
