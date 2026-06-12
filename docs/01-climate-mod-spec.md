# driftward-climate — Climate Mod Spec

> The simulation itself. Integration wiring is in `02-integration-spec.md`.
> Module paths + LoC estimates in `03-affected-components-and-loc.md`.

---

## 1. Overview

A **server-authoritative finite-difference atmospheric solver** discretised on the **chunk lattice**.
It evolves temperature, pressure, humidity, wind and clouds over loaded chunks, and exposes a
continuous (interpolated) query API. Two consumers read it:
- **Thermoo** ← ambient `TEMPERATURE` + `RELATIVE_HUMIDITY` (so Scorchful/Frostiful work unchanged)
- **Sable** ← air pressure / density at a point (so Aeronautics buoyancy is climate-aware)

Design principles: continuous field (no biome steps), physically-coupled T↔P (potential temperature),
emergent weather (no scripted events), native-free + heavily optimised.

---

## 2. The lattice

- **Horizontal cell = 1 chunk** (h = 16 blocks). Indexed by `(cx, cz)`.
- **Vertical:** v1 is **2.5D** — horizontal fields evolve on the 2D grid; vertical structure
  (altitude lapse, heightmap/exposure) is a **static per-column profile**. Designed so a few stacked
  Y-layers can be added later (→ true 3D, convection) without reshaping the API. *(OQ5)*
- **Domain (= the T2 tier, see `06`):** a **moving window** around each player + velocity prefetch,
  per dimension. No global fine sim. Frontier boundary/forcing = the **downscaled T1 synoptic tier**
  (falls back to the T0 static baseline where T1 has no coverage) — see `06` §3.
- **Spacing note:** interpolating across chunk centres caps the spatial gradient at
  `Δ(adjacent cells) / 16 blocks` — this is what removes the original border-descent.

---

## 3. Fields (~12 volumetric + 6 surface)

### Prognostic — time-stepped, one `FloatArray` each (SoA)
| field | symbol | notes |
|---|---|---|
| potential-temperature perturbation | `θ′` | store θ′; **diagnose T = θ·(P/P₀)^0.286**. Conserves under adiabatic lift → bakes in T↔P coupling |
| wind x | `u` | |
| wind z | `v` | (vertical velocity is `W`, see below — the table's old `w` was a naming collision) |
| pressure perturbation | `P′` | stored, or projection scratch |
| vapor | `q_v` | |
| **cloud condensate** | `q_c` | **the cloud field** |
| precip | `q_r` | rain if T>0 else snow; `q_s`/ice phase = later |

### Diagnostic — computed each step or at query time (some need scratch arrays)
`T`, `ρ` (ideal gas), `q_sat` (Clausius–Clapeyron), `RH`, `W` (vertical, from −∇·v), `δ` (divergence),
`ζ` (vorticity), cloud optical depth. *(~4 of these need materialised scratch arrays during a step:
`δ`, `W`, `ζ`, projection RHS.)*

### Per-column surface — 2D, cheap (one grid, not Y-stacked)
`surfaceY`, `solar`, `shelter`, `T_ground`, `albedo`, `roughness`. Event-refreshed on block change.

> **Count:** ~7 prognostic + ~4 scratch volumetric + 6 surface ≈ **a dozen 3D `FloatArray`s + 6 surface
> arrays per region**. Memory is trivial (< ~1 MB/region); **FLOPs/step is the cost** (each prognostic
> gets semi-Lagrangian advected → moisture roughly doubles the dry work).

---

## 4. Differential operators (hand-written stencils, central differences, h = 16)

```
∇S    gradient    = ( (S[x+1]−S[x−1])/2h , (S[z+1]−S[z−1])/2h )
∇·v   divergence  = (u[x+1]−u[x−1])/2h + (v[z+1]−v[z−1])/2h
∇×v   curl/vort.  = (v[x+1]−v[x−1])/2h − (u[z+1]−u[z−1])/2h
∇²S   Laplacian   = (S[x+1]+S[x−1]+S[z+1]+S[z−1] − 4S)/h²
(v·∇)S advection  = semi-Lagrangian backtrace + bilinear sample
```
These are **not** a linalg problem — they're local stencils = tight allocation-free loops over flat
arrays. No library expresses a 7-point Laplacian better. Vector-API-ready.

---

## 5. Dynamical core (stable-fluids, prognostic)

Per solver step (fixed Δt, off main thread):
```
1. add forces      dv += Δt·( −(1/ρ)∇P′  − f·(ẑ×v)  +  buoyancy(θ_v)  )   // PGF + Coriolis + buoyancy
2. advect          θ′,q_v,q_c,q_r,u,v  ←  semi-Lagrangian (unconditionally stable)
3. diffuse         κ∇²·  (viscosity/thermal/moisture)
4. microphysics    condensation/evaporation + latent heat + precip (see §6)
5. project         solve ∇²P′ = ∇·v* ; v −= ∇P′    → divergence-free; the removed δ = uplift signal
6. forcing/relax   radiation (§7) + pull θ′,q toward the T1+T0 reconstruction (§8, `06` §3) at edges
```
- **Coriolis** `f·(ẑ×v)` makes `∇×v ≠ 0` → cyclones/anticyclones (the "curl").
- **Advection** moves air masses → **fronts**.
- **Projection** enforces continuity; the **divergence it subtracts is the convergence/precip field**.
- **Stability:** semi-Lagrangian advection has no CFL blow-up; diffusion explicit-stable if `κΔt/h² ≤ ¼`.
  Step cadence ~20–60 ticks (weather is slow), **double-buffered** so queries read a lock-free snapshot.

---

## 6. Thermodynamics & clouds

```
potential temp     θ = T·(P₀/P)^(R/cp),  R/cp ≈ 0.286            // store θ′, diagnose T
density            ρ = P/(R·T)                                    // ideal gas; what buoyancy keys on
saturation         e_s(T) = 0.611·exp(17.27·T_C/(T_C+237.3)) kPa  // Tetens; q_sat ≈ 0.622·e_s/P
condensation       RH=q_v/q_sat; if RH≥1: Δ=q_v−q_sat;
                     q_v−=Δ; q_c+=Δ; θ += (L_v/cp)·Δ              // LATENT HEAT → storm engine
precip             q_c>thresh → q_c→q_r  (snow if T<0)
buoyancy var       θ_v = θ(1 + 0.61·q_v − q_c)                    // moist=lighter, cloudy=heavier
```
**Clouds (`q_c`) are emergent and physically located:** condensation where air rises & cools —
orographic (wind forced up **heightmap** gradients), convergent (fronts/lows via −∇·v uplift),
convective (warm moist ground). Advected downwind; shades the surface (cuts `solar`), traps longwave
at night (cloud radiation feedback); rains out as `q_r`.

---

## 7. Radiation & surface coupling

- **Shortwave in:** `solar` (skylight 0..15, **passes glass**) × daylight × (1 − cloud albedo) → heats `T_ground`.
- **Longwave out:** radiative cooling where **open to sky** (`shelter`≈0); clouds/roofs trap it.
- **Ground reservoir** `T_ground` has thermal inertia → correct **diurnal phase lag** (peak ~2pm, min pre-dawn);
  exchanges with the air's lowest layer.
- **Exposure model (the glass fix):** two independent scalars per column —
  - `solar` = propagated **skylight** (`getBrightness(SKY,pos)`) → sees through glass (greenhouse warms by day)
  - `shelter` = sky occlusion via **heightmap** (`MOTION_BLOCKING`) / `canSeeSky` → glass *is* a roof
    (sheltered from rain, traps heat at night)
  → **emergent greenhouse**: glass = `solar` high **and** `shelter` high.
- **Heightmap** is live (updates with player builds + worldgen structures); use
  `MOTION_BLOCKING_NO_LEAVES`/`OCEAN_FLOOR` to avoid leaf/ocean false surfaces; refresh column profile
  on block-change events (event-driven, no polling).
- **Sun-position authority = Ecliptic Seasons (verified, decompiled):** ES `@Overwrite`s
  `LevelTimeAccess.getTimeOfDay(float)` → `SolarAngelHelper.getSeasonCelestialAngle(level, dayTime)`
  when its `daylightChange` config is on — **seasonal day length (long summer days / long winter
  nights) changes the celestial angle itself**, in common (server) code. So `SolarForcing` must derive
  zenith / daylight fraction **through the vanilla accessors** (`level.getTimeOfDay()` /
  `getSunAngle()`), never by hand-rolling `dayTime % 24000 / 24000` — a hand-rolled sun would be the
  *vanilla* sun and silently drift from the sky everyone sees (wrong sunrise/sunset, wrong day length,
  wrong diurnal-forcing phase). Same principle as Thermoo: **read the bus, don't recompute.**

---

## 8. Static baseline (the per-chunk model = the solver's equilibrium)

Computed per chunk (static part once on load; season/time applied analytically on read):
```
baseTheta(chunk)  = avg over the chunk's 16 biome cells, each biome → θ target  (BiomeClimateMap)
baseHumidity      = avg biome humidity
+ altitude lapse (shared with the pressure curve)
+ seasonOffset(time)   (via EclipticSeasonsApi.getInstance(): getSeason/getSubSeason/getSolarTerm — we own the mapping, not the calendar)
+ diurnalOffset(timeOfDay)   (via T_ground)
```
The dynamics (§5) are **deviations** riding on this baseline; the baseline is the relaxation target and
the boundary condition. Nothing from the earlier per-chunk design is wasted — it became the forcing field.

---

## 9. Query API (what Thermoo/Sable call)

`Atmosphere` (object/service), all reads = **interpolation of the current double-buffer snapshot** to
block resolution. **Interpolation order is consumer-driven:**
- Value-only consumers (Thermoo temperature/humidity) get **bilinear (C0)** — cheap, continuous value.
- Any field a consumer **finite-differences** MUST be interpolated **C1** — smoothstep-weighted
  (Perlin-style) or bicubic. Concretely: Sable's `ServerBalloon.applyForces` samples pressure at
  **±1 block to build a buoyancy gradient** (`02` §B.2); bilinear would leave ∇P jumping at every cell
  boundary — the original border bug, one derivative up. So **pressure interpolates C1, always.**

`Atmosphere` is a thin, climate-flavored **façade over the generic field contract** (`05` §3.5): it owns
naming, units and the °C/hPa conventions; `WorldFieldGrid` owns storage, interpolation and operators.
New consumers (radio, sound, …) target the contract, not this façade.
```
temperatureAt(level, pos, time): °C        // → Thermoo TEMPERATURE
humidityAt(level, pos): 0..1               // → Thermoo RELATIVE_HUMIDITY
pressureAt(level, pos): Double              // → Sable getAirPressure hook
densityAt(level, pos): Double              // ρ; buoyancy-true
cloudAt(level, pos): Double                // q_c / optical depth (render + radiation)
windAt(level, pos): Vec2                    // (u,v) — drift, particles, sailing later
```
Interpolated reads are the **continuous field** (C0 everywhere, C1 where differenced) that fixes the
border-descent — now *plus* live wind.

---

## 10. Math & performance strategy (native-free)

- **Operators + advection + SOR/multigrid:** ours, pure Kotlin over flat `FloatArray` SoA, allocation-free.
- **The only real solver = Poisson projection:**
  - default **geometric multigrid / red-black SOR** (stencil smoothing + restrict/prolong; ~150 LoC), or
  - **FFT-Poisson via JTransforms** (pure-Java, O(N log N)) — `02`/`03` track it as optional. *(OQ2)*
- **SIMD:** **JDK Vector API** (`jdk.incubator.vector`, Panama) for hot kernels — portable AVX/NEON,
  no JNI. Needs `--add-modules jdk.incubator.vector` at launch (we own the launcher); scalar loops
  (C2 autovectorised) are the fallback, not a prerequisite.
- **Parallelism:** Fork/Join / tiled over cores, off the main server tick.
- **Precision:** **base-state + perturbation** variables (θ′, P′) so float32 resolves sub-Kelvin anomalies.
- **Escalation path (not v1):** GPU compute via **LWJGL** (already in MC, no new native dep) — parked
  because servers are usually headless and an authoritative GPU sim adds sync complexity.
- **Never:** JNI BLAS/LAPACK (ND4J, JavaCPP-MKL/FFTW, Multik-native) — dense-tensor shape ≠ structured
  stencil, and per-OS/arch natives are a cross-platform/server deployment liability in a mod.

---

## 11. Module layout (Kotlin unless noted) — see `03` for LoC

```
ink.astrius.driftwardclimate
├─ DriftwardClimate.kt              entrypoint + wiring (KFF @Mod object)
├─ Atmosphere.kt                    public query API (§9) — façade over the field contract (05 §3.5)
├─ field/
│   ├─ WorldFieldGrid.kt            generic named-field registry + tier-aware reads (D12, 05 §1/§3.5)
│   ├─ ClimateField.kt              SoA region: FloatArrays + indexing + double-buffer
│   ├─ Operators.kt                 grad/div/curl/laplacian/advect (Vector-API-ready)
│   ├─ Projection.kt                SOR/multigrid (+ optional JTransforms FFT backend)
│   └─ NearFieldIndex.kt            per-chunk sub-grid point-source index + query kernel (05 §2.3)
├─ solver/
│   ├─ AtmosphereSolver.kt          stable-fluids step orchestration (§5)
│   └─ Thermodynamics.kt            θ↔T, ρ, q_sat, latent heat (§6)
├─ model/
│   ├─ BiomeClimateMap.kt           biome → θ/humidity targets (data-driven)
│   ├─ ChunkClimate.kt              per-chunk static baseline = T0 (§8)
│   ├─ SolarForcing.kt              shared sun/zenith/season service (04 §2.3) — radiation + ionosphere
│   └─ ExposureSampler.kt           heightmap + skylight per column, event-refresh (§7)
├─ runtime/
│   ├─ RegionManager.kt             T2 moving-window lifecycle, off-thread step, buffer swap (06 §4)
│   ├─ SynopticTier.kt              T1 coarse grid: slow step, SavedData persistence, sleep/fast-forward (06 §2/§5)
│   └─ BlockChangeListener.kt       invalidate exposure + near-field index on block events
├─ config/ClimateConfig.kt          tunables
├─ integration/thermoo/             provider + type registration  (see 02)
└─ mixin/sable/                     getAirPressure hook (Java)     (see 02)
```

---

## 12. Validation plan (write the checks *with* the solver, not after)

Cheap analytic invariants — each is a unit test against the bare solver (no Minecraft), run on a
synthetic grid:

| check | invariant | catches |
|---|---|---|
| **Uniform no-op** | uniform θ′,q, v=0, flat terrain → every step is an exact no-op (bit-stable) | sign errors, stray forcing, boundary leaks |
| **Projection** | after step 5, `‖∇·v‖∞` ≤ tolerance on arbitrary v* | broken Poisson solve / stencil mismatch |
| **Conservation budget** | Σ(q_v+q_c+q_r) constant under advect+microphysics (no sources, periodic BC); Σθ′ constant under pure advection | semi-Lagrangian mass loss, latent-heat double-count |
| **Geostrophic balance** | initialise v ⟂ ∇P′ with `f·v = −(1/ρ)∇P′` → state holds steady | Coriolis/PGF sign or scale errors |
| **Interpolation order** | sample `pressureAt` along a line crossing cell boundaries; assert value *and first difference* continuous (the §9 C1 requirement) | regression of the border-descent bug class |
| **Sea-breeze toy** | warm strip beside cool strip → onshore low-level flow develops with the right sign | end-to-end dynamics sanity (qualitative) |

**Restart semantics:** T2 is transient — an in-progress storm dies on server restart unless its anomaly
is **folded into T1 on shutdown** (the cheap middle ground of `06` OQ8, even if full two-way nesting
stays off). T1 is SavedData-persisted, so post-restart reconstruction (`06` §3) then reproduces a
plausible continuation rather than clear skies. Test: snapshot → fold → drop → reconstruct → fields
agree at T1 resolution.
