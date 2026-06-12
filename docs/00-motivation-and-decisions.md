# driftward-climate — Motivation, Journey & Decisions

> Working spec, captured 2026-06-12. Source of truth for *why* this mod exists and *how* we
> arrived at the design. Companion files:
> - `01-climate-mod-spec.md` — the simulation itself
> - `02-integration-spec.md` — Thermoo + Sable + Aeronautics wiring
> - `03-affected-components-and-loc.md` — every file touched, with paths + LoC
> - `04-shared-field-substrate-and-radio.md` — the fields are reused by a future amateur-radio sim
> - `05-field-contract-and-consumers.md` — the generic field-engine contract + all piggyback consumers
> - `06-lod-and-world-scaling.md` — multi-resolution nesting: simulating the world without modelling it all

---

## 1. How we got here (the journey)

The whole thing started as a *small balloon feature* and unrolled into "we need our own climate
model." The path, in order, because each step's discovery forced the next:

1. **Wanted:** high altitude → colder air → more hot-air-balloon lift (Create: Aeronautics).
   Began adding a lapse-rate term to `BalloonTemperatureMixin` in driftward-fixes.
2. **Discovery:** Thermoo's ambient temperature is **biome-based with no altitude term**. Almost
   shipped an arbitrary per-block lapse hack.
3. **Correction (user):** "there IS a curve that affects how high an airship rises." Decompiled
   Sable → found a real **barometric pressure curve**: `pressure(y) = exp(-0.004·(y − seaLevel))`,
   scale height ≈ 250 blocks, clamped ≤1.5 below sea level, Bézier-smoothed to **exactly 0 at build
   height**. Balloon lift is `∝ pressure` (see `ServerBalloon.applyForces`), so the altitude ceiling
   is *emergent*: a ship rises until `lift · pressure(y) = weight`.
4. **Idea:** unify temperature + pressure into one "common layer." Investigated Thermoo: it's a
   **data-driven, registry-backed `EnvironmentProvider` system**; everyone reads ambient state
   through `EnvironmentLookup.findEnvironmentComponents(level, pos)`.
5. **Survey (Java scan of all 169 mod jars):** *only two mods* contribute ambient environment —
   **Scorchful** (9 defs) and **Frostiful** (8 defs). Both consume one shared `TEMPERATURE` (°C) per
   tick. Found Scorchful even ships an **unused** `sea_level_altitude_temperature` provider.
6. **Decision (user):** build our **own climate mod**, model pressure properly, and **invert
   control so Sable asks *us*** for pressure at runtime (no static datapack curve).
7. **The real bug surfaced:** biome temperature transitions are **too rapid** — flying a balloon from
   `jagged_peaks` across a biome border into a warmer biome causes a near-instant lift drop →
   sudden descent. Root cause: our v1.3 mixin scales lift by a **step-function** biome temperature.
8. **Decision:** make the temperature field **spatially continuous** (per-chunk model + interpolation);
   **retire the `totalLift` hack**; ship as a **standalone mod**.
9. **Exposure / heightmap discussion:** caves vs. open sky vs. *glass*. `canSeeSky` is wrong for glass
   (glass is in the `MOTION_BLOCKING` heightmap → reads as roofed, but skylight passes through). Split
   into two signals → **emergent greenhouse behaviour** falls out for free.
10. **Ambition escalated (user):** do real **div / grad / curl** — i.e. a **finite-difference
    atmospheric solver** on the chunk lattice. The chunk grid *is* the discretisation grid.
11. **Field set deepened:** the naïve `{T, P, q, v}` is the barotropic toy and **cannot make a cloud**.
    Need potential temperature θ, moisture split (q_v/q_c/q_r) with latent heat, density, vertical
    velocity, vorticity/divergence, base-state+perturbation, ground reservoir, radiation/cloud feedback.
12. **Math libraries:** don't roll calculus — but the operators are *stencils* (ours), the only real
    solver is the Poisson projection (SOR/multigrid or JTransforms FFT), SIMD via the JDK Vector API;
    **avoid JNI/BLAS** (wrong shape + cross-platform deployment liability in a mod).

---

## 2. Locked decisions

| # | Decision | Rationale |
|---|----------|-----------|
| D1 | **Standalone mod** `driftward-climate`, own repo/modid | It's a real subsystem, not a bugfix |
| D2 | **Kotlin** (+ Kotlin for Forge runtime) | User choice; `FloatArray` SoA = zero-boxing |
| D3 | **Per-chunk climate model**, interpolated for C1 continuity | Kills the biome-border lift discontinuity at the source |
| D4 | **Prognostic stable-fluids** solver (advect → diffuse → project) | The real "div/grad/curl" dynamics: fronts, highs/lows, cyclones |
| D5 | **3D intended, 2.5D-optimized for v1** | Horizontal dynamics on the grid; vertical structure static for now |
| D6 | **Retire the `totalLift` balloon hack**; pressure-buoyancy only | One smooth mechanism, not two; the hack is the discontinuous one |
| D7 | **Sable asks us** — runtime hook on `getAirPressure`, **not** a datapack curve | 3D, dynamic, temperature-coupled; the inversion the user wants |
| D8 | **We own our clouds** — emergent `q_c` from moisture + uplift + saturation | Not a particle hack; orographic/convective/frontal clouds for free |
| D9 | **Native-free math:** ours = stencils + SOR/multigrid; libs = JTransforms (FFT) + Vector API (SIMD) | Cross-platform, server-friendly; BLAS/JNI is the wrong shape |
| D10 | **Heavy optimization posture** | SoA flat arrays, allocation-free hot loops, off-thread, double-buffer, perturbation variables for float precision |
| D11 | **Field layer is a shared substrate**, not climate-private | A future **amateur-radio** sim reads the same `T/P/q`/heightmap/solar fields (refractivity `N`, ducting, ionosphere). Factor a `WorldFieldGrid` seam + public field/operator reads + shared solar service; radio = sibling mod. Raises the weight on **OQ5** (vertical layering — ducting is a vertical phenomenon). See `04`. |
| D12 | **Generic field-engine built in v1** — named-field registry + operators + shared services; climate is plugin #1 | Radio/sound/pollution/fire/gas/scent become *registrations* (`registerField`/`registerReaction`/`registerPointSource`), never rewrites. Modest upfront cost vs. a refactor when consumer #2 arrives. See `05`. |
| D13 | **Multi-resolution LoD tier stack:** T0 climatology (analytic, everywhere) → T1 synoptic (coarse, persisted) → T2 mesoscale (moving fine window) → near-field (sub-grid kernels) | The field is **world-anchored and exists everywhere** at some resolution; reads resolve to the finest covering tier; tiers agree at boundaries → no popping, graceful degradation under fast movement, "weather while away" via T1 persistence. See `06`. |

---

## 3. User notes (verbatim intent)

- "we want sable to have to **ask us**, what's the pressure at some point." → runtime query, not datapack.
- "we also probably want **our own clouds**."
- "complete div, grad, curl and other shenanigans."
- "3d intended, optimized for 2.5D for now. and let's probably write in **kotlin**, and do a **LOT of
  optimizations**."
- "climate should be **written by us**, math should use already optimized, maybe, jni powered stuff."
  → recorded as intent; **counter-recommendation accepted**: stay native-free (Vector API + JTransforms),
  escalate to LWJGL **GPU compute** (already in MC) only if profiling demands, never JNI/BLAS.
- "**glass** — canSeeSky doesn't handle [it]." → split solar (skylight) vs shelter (heightmap).
- "is heightmap always updated? does it update with structures built?" → **yes**, the live heightmaps
  (`MOTION_BLOCKING`, `MOTION_BLOCKING_NO_LEAVES`, `WORLD_SURFACE`, `OCEAN_FLOOR`) update incrementally
  on every block change (player + worldgen structures). `_WG` variants are gen-time snapshots — unused.

---

## 4. Open questions / cut lines (decide before/with first code)

- **OQ1 — v1 moisture:** carry the full 7 prognostic (incl. `q_v/q_c/q_r`, latent heat) from day one,
  or dry-dynamics core first (4 advected fields) and switch moisture on later? *(Compute decision, not
  memory — moisture ≈ doubles per-step work.)*
- **OQ2 — Projection backend:** SOR/multigrid (hand, no dep, default) vs JTransforms FFT-Poisson.
- **OQ3 — Vorticity formulation:** prognostic `{u,v}` advected, or vorticity–divergence `{ζ,δ}`.
- **OQ4 — KFF packaging:** ship Kotlin-for-Forge as a separate pack mod (simplest) or jarJar into our jar.
- **OQ5 — Vertical layering for "2.5D":** single column profile vs a few stacked Y-layers now (cheap path to 3D later).
- **OQ6 — Cloud rendering:** server-sim only for v1 (gameplay: shade/rain), or also a client cloud renderer.
- **OQ11 — Near-field vs Thermoo contact heat:** v1 stance is **locked = augment** (our radiant near-field
  adds the radius effect Scorchful/Frostiful contact handlers miss; theirs stay). Open: later full takeover
  of `onFireWarmRate`/`inLavaWarmRate`/ice-cooling for one consistent model. *(See `02` §D, `05` §2.3.)*
