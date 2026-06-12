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

    // ── T2 mesoscale window (spec 06 §2/§4) ───────────────────────────────
    /** T2 region size in chunks (the moving fine window). */
    val t2Nx: Int = 48,
    val t2Nz: Int = 48,
    val t2Layers: Int = 4,
    /** Don't move the window until the desired origin drifts this far (hysteresis). */
    val windowMoveThresholdCells: Int = 6,
    /** Velocity prefetch: window centre leads the player by v·lead (06 §4). */
    val prefetchLeadS: Float = 10f,

    // ── T1 synoptic tier (spec 06 §2/§5, D13) ─────────────────────────────
    /** Chunks per T1 cell (16 ⇒ 256-block coarse cells, OQ7). */
    val t1CellChunks: Int = 16,
    /** T1 grid size in coarse cells (64 ⇒ ±8 km of weather around centre). */
    val t1Nx: Int = 64,
    val t1Nz: Int = 64,
    /** T1 window centre, chunk coords (fixed world-window, OQ7 v1). */
    val t1CenterChunkX: Int = 0,
    val t1CenterChunkZ: Int = 0,
    /** T1 step length, seconds (slow clock). */
    val t1StepDtS: Float = 60f,
    /** Synoptic forcing: amplitude (K), spatial scale (coarse cells), epoch length (s). */
    val t1ForcingK: Float = 4f,
    val t1ForcingScaleCells: Int = 8,
    val t1ForcingPeriodS: Float = 3600f,
    /** Relaxation time toward the evolving forcing target, s (~6 h). */
    val t1ForcingTauS: Float = 21600f,
    /**
     * Anomaly-gradient → drift-wind gain, m²/(s·K). Sized for GENTLE drift:
     * a 4 K system across 8 coarse cells → ~0.6 m/s (one cell per ~7 min).
     * Hotter gains (2000+) spin systems ~0.5 rad/step → filamentation →
     * semi-Lagrangian mass loss (measured: 85% of an anomaly destroyed in
     * 60 steps). Keep coarse rotation ≲0.1 rad/step.
     */
    val t1WindGain: Float = 300f,
    /** Edge taper width (coarse cells) — anomaly fades to 0 at the T1 frontier. */
    val t1EdgeTaperCells: Int = 2,
)
