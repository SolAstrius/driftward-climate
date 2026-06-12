# driftward-climate — Coupling Contracts (the implicit made explicit)

> The code and tests don't just implement the spec — they **decided** how the pieces couple, and
> several of those decisions exist nowhere in 00–06. This file is the canonical index: every
> cross-component contract, why it holds, and **the test that pins it** (the tests are the
> executable spec; this is its table of contents). If you change a contract, change its test and
> this row in the same commit.

---

## A. Tier coupling (T0 ↔ T1 ↔ T2)

| # | Contract | Why | Pinned by |
|---|---|---|---|
| A1 | **θ′ is the deviation from the T1+T0 reconstruction**, not from T0 alone. The solver's relax target and the read-path baseline are BOTH `recon.theta` (θ0 + T1 anomaly + detail). | θ′ stays small (float32 precision, D10); synoptic weather doesn't have to "leak" into T2 through slow relaxation. | `ClimateRuntimeTest.T1 anomaly flows into T2 reads immediately…` (asserts +5 K reads back ≈+4.9 in ONE tick **and** θ′ stays ≈0) |
| A2 | **T1 anomalies enter T2 reads instantly** via `refreshBaseline()`, not gradually via relax. Relaxation only erodes *deviations from* the (moving) target. | The relax τ is a weather-persistence knob, not a coupling-latency knob — keep concerns separate. | same as A1 |
| A3 | **T2 initialisation: θ′ = 0 MEANS "exactly T1+T0 weather"**; fresh cells get baseline q_v and **T1's drift wind**; teleports copy nothing. | "No storm summoned on arrival" (06 §4); wind continuity for balloons crossing window moves. | `ClimateRuntimeTest.teleport gets fresh T1 weather…`, `window move carries world-anchored state…` |
| A4 | **Fold-on-shutdown** (OQ8 middle ground): T2's column-mean θ′ is ADDED into overlapped T1 cells; T1 then persists. Restart ⇒ storm survives at synoptic resolution. | The cheap half of two-way nesting, exactly once, at the only moment it matters. | `ClimateRuntimeTest.shutdown folds the T2 storm…`, `SynopticTierTest.foldFromT2…` |
| A5 | **T1 reads taper to 0 at its window frontier** (linear over `t1EdgeTaperCells`); outside = T0 exactly. | No anomaly cliff at the T1 boundary (the D3 continuity discipline, one tier up). | `SynopticTierTest.edge taper…`, `…zero outside coverage` |
| A6 | **Pressure NEVER reads T1 anomalies or seeded detail** — `Reconstruction.pressure` = pure T0 (OQ10). | A fake or coarse ∇P would steer balloons; θ-side anomalies reach lift only through the read-path density factor (B2), which is C1. | `ReconstructionTest.OQ10 - pressure NEVER carries…` |
| A7 | **T1 is deterministic in (seed, t)** — value-noise forcing keyed on absolute epoch; `fastForward` = closed-form decay + blend toward the wake-time forcing. Same seed ⇒ same weather history; no wall clock anywhere in core. | Reproducible worlds, testability, OQ9 sleep/wake without simulation. | `SynopticTierTest.determinism…`, `fast-forward decays…` |

## B. Read-path coupling (Atmosphere ↔ Thermoo/Sable)

| # | Contract | Why | Pinned by |
|---|---|---|---|
| B1 | **Sable parity:** `pressureAt ≡ p0(y)` (the decompiled curve: exp clamp fade) wherever θ′=0 — including exactly AT the `handles()` boundary. | 02 §B calibration: coverage seams and LoD downgrades must be invisible to lift. | `AtmosphereTest.pressure equals Sable's curve…`, `crossing the coverage boundary is seamless…`, `BaselineTest.pressure0 reproduces Sable's curve…` |
| B2 | **The density factor is a θ-ratio:** `pressureAt = p0(y)·(θ0/θ)`, Exner cancels exactly. Cold ⇒ more lift; clamped [0.5, 2]. | One smooth scalar folds buoyancy into Sable's existing pressure consumption (02 §B.3) with parity at zero anomaly by construction. | `AtmosphereTest.cold anomaly lifts more…` |
| B3 | **Anything a consumer finite-differences is C1 in EVERY term** — θ′ via Catmull-Rom snapshot AND θ0 via Catmull-Rom over the baseline plane. A C1 anomaly on a stepwise biome baseline re-imports the border bug. | Sable differences pressure at ±1 block (the original descent bug, one derivative up). | `AtmosphereTest.pressure is C1 across cell boundaries…`, `SamplingTest.C1 first difference is continuous…` |
| B4 | **Near-field is additive and OUTSIDE the grid:** `felt = ambient + Σ kernel`; ambient never contains point sources; kernel is C1 at its cutoff (Wendland). | 05 §2.3 scale separation; OQ11 = augment. | `AtmosphereTest.felt temperature adds the campfire…`, `NearFieldIndexTest.…C1 at the cutoff` |
| B5 | **Every read answers everywhere** (D13): region → else T1+T0. `handles()` only gates the *Sable mixin cancel*, never the ability to answer. | Graceful degradation is the architecture, not an error path. | `AtmosphereTest.facade without refreshBaseline never claims coverage` (still serves the Sable curve) |
| B6 | Thermoo emits **°C** and RH **clamped 0..1** (their band: ~0–30 °C neutral, 02 §A.2). | Read-transparent takeover of Scorchful/Frostiful. | `AtmosphereTest.relative humidity is clamped…`, `BaselineTest.celsius view…` |

## C. Solver-internal coupling

| # | Contract | Why | Pinned by |
|---|---|---|---|
| C1 | **Full-θ advection:** θ′+θ0 are transported TOGETHER (promote → advect → demote). Advecting θ′ alone drops the −(v·∇)θ0 baseline-gradient transport. | Air blowing from tundra into desert must carry tundra air. | `AtmosphereSolverTest.equilibrium state is a fixed point` (uniform baseline ⇒ exact no-op) |
| C2 | **Projection is barotropic-only**; per-layer residual divergence integrates upward into **W** (ground-up, rigid lid); W feeds NEXT step's 3D advection (one-step lag, intentional). | Full per-layer projection would delete sea breezes; the removed δ IS the uplift signal (01 §5). | `AtmosphereSolverTest.baroclinic shear survives projection…`, `warm strip drives low-level inflow and rising air…` |
| C3 | **Hydrostatic PGF** = column integral (top-down) of −(g/θref)·θ_v′ where θ_v′ is referenced against the **baseline θ_v (with q0)** — baseline humidity creates no spurious pressure. | Warm/moist column ⇒ surface low ⇒ inflow: thermal circulations emerge; equilibrium stays at rest. | `AtmosphereSolverTest.warm strip…`, `equilibrium…` |
| C4 | **Coriolis is an exact rotation** by f·dt with the §2.1 signs (du=−f·v, dv=+f·u); never an explicit Euler term. | Unconditional stability + speed preservation; signs live in §2.1 ONLY. | `AtmosphereSolverTest.Coriolis rotates the wind without changing its speed` |
| C5 | **Latent heat enters θ as ΔT/Π** (divided by the layer Exner), and microphysics order is condense/evaporate → autoconvert → rain-out, with rain-out exiting to the `precip` surface plane. **Total water (q_v+q_c+q_r+precip) closes.** | The storm engine must not double-count energy or leak water. | `AtmosphereSolverTest.supersaturation condenses, warms, and conserves total water` |
| C6 | **Evaporation precedes rain in dry air** — cloud seeded into sub-saturated air dies, it doesn't rain. | Physical realism guard discovered by a "failing" test that was actually correct physics. | `AtmosphereSolverTest.dense cloud autoconverts…` (requires a near-saturated environment) |

## D. Numerics contracts (discovered empirically — do not relearn these)

| # | Contract | Why | Pinned by |
|---|---|---|---|
| D-1 | **CFL discipline:** semi-Lagrangian is stable at any CFL but features narrower than `u·dt/h` cells SELF-ERASE (the jet backtraces past its own width). Keep `u·dt/h ≲ O(1)`: T2 default dt = 5 s. | A 30 s dt silently deleted a developing sea breeze — stability ≠ accuracy. | `AtmosphereSolverTest.warm strip…` (fails at dt=30), `ClimateConfig.stepDtS` doc |
| D-2 | **RK2 (midpoint) backtrace everywhere** — straight chords land on the wrong rotation ring under shear. | 2nd-order trajectories; rigid AND sheared rotation behave. | `OperatorsTest` advection suite still exact; probe history in this file |
| D-3 | **Bilinear velocity is divergence-free only at nodes** → plain SL loses ~1%/step of a self-rotating anomaly (rigid rotation: exact, because linear wind interpolates exactly). **BFECC** (3 SL passes + extrema limiter) recovers ~10× (0.09%/step). T1 self-advection MUST use BFECC; forced T2 fields may use plain advect. | T1 anomalies must survive hours of unforced drift; T2 fields are continuously re-forced so dissipation is tolerable. | `SynopticTierTest.systems drift…` (≤12% over 60 steps) |
| D-4 | **T1 drift gain stays gentle** (`t1WindGain` sized for ≲0.1 rad/step rotation). Hot gains filament anomalies below grid scale and even BFECC can't save them. | Measured: gain 2000 ⇒ 85% anomaly destruction in 60 steps. | `SynopticTierTest.weather happens…` (bounded), `systems drift…` |
| D-5 | **JTransforms DCT identity pair is forward(true)+inverse(true)** — (false,true) is NOT an inverse pair. | Empirically probed; the FFT-Poisson eigen-division is only valid on an identity pair. | `ProjectionTest.FFT solution satisfies the 5-point Laplacian`, `FFT and SOR solve the same…` |
| D-6 | Collocated central div/grad + compact 5-point solve ⇒ projection REDUCES divergence (≥85% on smooth winds), never machine-zero; checkerboard divergence is invisible by construction. | Stam-standard collocated discretisation; tests assert reduction, not zero. | `ProjectionTest.SOR/FFT projection strongly reduces divergence` |

## E. Lifecycle & buffer coupling

| # | Contract | Why | Pinned by |
|---|---|---|---|
| E1 | **One publish per state change**: solver writes ALL fields to `back` then publishes once; region moves initialise ALL fields (defaults + world-anchored overlap carry) before their single publish. Readers only ever see complete generations. | Lock-free reads (01 §5/§10). | `SamplingTest.double buffer…`, `ClimateRuntimeTest.window move carries…` |
| E2 | **`refreshBaseline()` runs on the stepping thread every tick()**; `Atmosphere` re-binds its caches on region identity change; `handles()` is false until the first refresh. | The façade is passive — no hidden threading. | `AtmosphereTest.facade without refreshBaseline never claims coverage`, `diurnal cycle flows through refreshBaseline` |
| E3 | **Window hysteresis + velocity lead** (`windowMoveThresholdCells`, `prefetchLeadS`); a move carries the overlap at WORLD coordinates. | 06 §4: no thrash, prefetch for fast flyers. | `ClimateRuntimeTest.small movement does not thrash…`, `velocity prefetch leads…` |
| E4 | **T1 payload is opaque bytes, version-prefixed, shape-checked** — mismatch ⇒ clean fresh start, never corruption. | D14: core persists bytes; the adapter owns NBT. | `SynopticTierTest.serialize-deserialize roundtrip is exact` (incl. shape rejection) |

---

*Maintenance rule: a PR that changes coupling behaviour must touch (1) the code, (2) the pinning
test, (3) this file's row — or it doesn't merge. That keeps the implicit permanently explicit.*
