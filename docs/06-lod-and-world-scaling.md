# driftward-climate — LoD, Out-of-Range Simulation & World Scaling

> You can't PDE-solve the planet. This file fixes how we simulate a vast world without modelling it
> completely: **multi-resolution nesting + a stateless climatology floor + a moving fine window**,
> with graceful degradation under rapid player movement. Concepts: NWP one-way nesting (global→regional
> →local), AMR moving patch, statistical downscaling, climatological fallback.

---

## 1. Core principle: the field is **world-anchored**, not player-anchored

The atmosphere is a property of the *world*, at world coordinates, on its own clock. **LoD controls the
resolution at which a region is *simulated*, never whether the field *exists*.** Every read resolves to
the **finest tier covering `pos`**; because each tier is initialized from the one above, tiers **agree at
boundaries** → no popping. A fast player doesn't move the field — they move through regions at different LoD.

---

## 2. The tier stack

| tier | resolution | extent | clock | state | cost |
|---|---|---|---|---|---|
| **T0 — Climatology** | closed-form | whole world | — | none (analytic) | ~0 |
| **T1 — Synoptic** | coarse, 1 cell / 8–16 chunks | large radius around players; **persisted** | slow (~seconds) | small grid on disk | low |
| **T2 — Mesoscale** | per-chunk (full SoA solver) | moving window per player + prefetch | fast (few ticks) | transient | the real cost |

- **T0** = the static baseline (spec 01 §8): biome + altitude + season + diurnal + terrain/exposure.
  Stateless, everywhere, always — what a chunk has with *zero* simulation.
- **T1** = large-scale anomalies (pressure systems, fronts, air-mass advection) on a cheap coarse grid.
  This **is** "weather outside sim distance." Few cells, slow step, persisted, keeps evolving on unload.
- **T2** = the expensive prognostic solver, **only in a moving window** around each player; boundary/
  forcing = downscaled T1.

---

## 3. Reconstruction / downscaling (why any chunk has a real, continuous field)

```
field(pos) = T1.interpolate(pos)        // large-scale anomaly (fallback to T0 if no T1 coverage)
           + T0.baseline(pos)           // biome / altitude / heightmap / season — deterministic
           + seededDetail(pos, time)    // sub-grid texture from a deterministic hash — no stored state
```
- Loaded or not, simulated or not → consistent value.
- T2 **initializes to exactly this**, then evolves local dynamics on top.
- Retiring a T2 cell = drop it (**one-way nesting**); reconstruction regenerates a consistent value.
  Optional **two-way**: fold the cell's anomaly back into T1 before dropping.

---

## 4. Rapid player movement

T2 window = radius around player **extended along the velocity vector** (predictive prefetch: spin up
cells *ahead* of a fast flyer, off-thread). Governed by a **per-tick spin-up budget**:

| situation | behaviour |
|---|---|
| normal speed | window keeps up; full local dynamics |
| faster than spin-up (elytra/balloon/fast horse) | **read drops to T1** (downscaled coarse + baseline) until they slow & T2 catches up — continuous at synoptic scale, no stutter |
| teleport | discard window; instantiate fresh from T1+T0 at destination (T1 persistent ⇒ destination already has consistent weather, no "storm summoned on arrival") |

- Hysteresis on window edges (no thrash); spin-up is **async + budgeted**.
- LoD downgrade is the safety valve: when over budget, serve coarser tier rather than stall the tick.

---

## 5. Persistence & "weather while you were away"

- T1 is small (coarse) → **persist per-world**, advance on the world clock.
- No players near a region / nobody online → T1 region **sleeps to T0**, **lazily fast-forwarded** on next
  visit (step the cheap coarse model forward, or stochastic/analytic update keyed on elapsed time).
- Returning players see plausible continuity without us having simulated the gap.

---

## 6. Cost-control heuristics (summary)

- T2 only where players are (cap forced/loader chunks); T1 extent/res scales with player count + config budget.
- Amortized cadences: T2 every few ticks, T1 every ~N seconds, T0 free.
- Sleep empty regions down a tier; wake lazily.
- One-way nesting by default (cheap; fine detail is reconstructable). Two-way only if conservation matters.
- All spin-up / stepping off the main tick (double-buffered snapshots per tier — spec 01 §5/§9).

---

## 7. The LoD stack **is** the long-range query layer (shared-substrate payoff)

Long-range consumers (spec 04/05) read the **coarsest tier that spans their query**:
- **Radio** HF skywave hops hundreds of blocks across regions nobody occupies → read **T1 + ionosphere**
  along the path; that coarse resolution is *exactly right* for synoptic-scale propagation.
- **Sound / optics** read the finest tier near the endpoints.
- ⇒ No special long-range machinery — the nesting tiers already provide multi-scale reads.

---

## 8. Open questions

- **OQ7 — T1 footprint:** fixed world-window vs union of per-player disks; coarse cell size (8 vs 16 chunks).
- **OQ8 — Nesting direction:** one-way (default) vs two-way feedback T2→T1 (conservation vs cost).
  *Note: this also decides **server-restart semantics** — T2 is transient, so an in-progress storm
  survives a restart only if its anomaly is folded into T1 on shutdown. **Fold-on-shutdown** is the
  cheap middle ground (one fold at stop, not continuous two-way) and is what spec 01 §12 tests.*
- **OQ9 — Sleep/fast-forward model:** step coarse solver on load vs closed-form stochastic catch-up.
- **OQ10 — Seeded detail:** which quantities get sub-grid noise vs pure interpolation (avoid fake gradients
  that would mislead radio/sound ray-tracing).
