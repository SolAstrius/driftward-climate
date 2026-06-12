# driftward-climate — Shared Field Substrate & Amateur-Radio Consumer

> Forward requirement (user, 2026-06-12): the same world fields will be **reused for amateur-radio
> propagation simulation**. This is not a bolt-on — radio is a *second reader of the same physical
> fields*. Capturing it now so the field layer is designed shared, not climate-private.

---

## 1. The overlap — radio propagation is mostly derived fields of ours

| Radio mechanism | Band | Needs (our fields) | Notes |
|---|---|---|---|
| **Tropospheric refraction / ducting** | VHF/UHF | `T`, `P`, `q_v` → refractivity `N`; **∇N** | ducts where `dM/dh < 0`; uses the existing `∇` operator |
| **Rain/cloud attenuation (fade)** | UHF+ | `q_r`, `q_c` | already prognostic |
| **Terrain LoS + diffraction** | VHF+ | **heightmap**, ground conductivity (biome/material) | heightmap already sampled |
| **Ionospheric skywave (MUF/foF2)** | HF | **solar forcing** (zenith, day/night, season) | separate thin layer; reuses radiation's solar inputs, not the tropo grid |
| **Atmospheric/ground noise** | all | terrain, weather (storms → QRN) | `q_r`/`ζ` (lightning ∝ convection) optional |

### Refractivity — the bridge formula
```
N = 77.6·(P/T) + 3.73e5·(e/T²)        [N-units]   (P hPa, T K, e = vapor partial pressure)
e = q_v·P / (0.622 + 0.378·q_v)        (from specific humidity)
M = N + 0.157·h                         (modified refractivity, earth-curvature corrected; h in m)
duct / super-refraction:  dM/dh < 0   ⇔   dN/dh < −157 N-units/km
```
`N` is a **pure function of `T`, `P`, `q_v`** → computable on the existing grid; ducting = ray-tracing
through `∇N`, which the gradient stencil already produces. Tropo DX falls out of the climate fields.

---

## 2. Architectural consequences (design now, build later)

1. **Shared substrate seam.** Factor a general `WorldFieldGrid` / field-provider below `Atmosphere`.
   Climate **populates** it; radio (and future consumers) **read** it via a stable, snapshot-consistent,
   lock-free API. Don't let radio touch climate internals.
2. **Public field + operator reads.** Expose raw field snapshots and the gradient operator publicly
   (radio wants `∇N` ⇒ `∇T/∇P/∇q`). Today these live in `field/Operators.kt` (internal) — promote a
   read API. Affects: `field/ClimateField.kt`, `field/Operators.kt`, `Atmosphere.kt`.
3. **Solar/astronomy as its own service.** Sun zenith, day/night, season — consumed by **radiation**
   (clouds/ground) *and* **ionosphere**. Per D14 this is a **port**: core defines `SolarPort`
   (`api/Ports.kt`); the mod implements it as `adapter/VanillaSolarAdapter.kt` (~50 LoC); tests stub it.
   **Source of truth = the vanilla accessors, not our own math:** Ecliptic Seasons overwrites
   `LevelTimeAccess.getTimeOfDay` with its seasonal celestial angle (long summer days / long winter
   nights — see 01 §7, 02 §D). `SolarForcing` wraps `level.getTimeOfDay()`/`getSunAngle()` so radiation
   *and* the future ionosphere (day/night terminator, MUF cycle) automatically follow the seasonal sun.
4. **Packaging.** Radio = **sibling mod** (`driftward-radio`) depending on a shared field-engine
   module or on driftward-climate's stable public API — **not a fork**. Keep the field engine's public
   surface deliberately small and versioned.

---

## 3. Priority shift: vertical structure (OQ5)

Climate alone tolerates 2.5D for a while. **Radio does not** — tropospheric ducting *is* a vertical
`∂N/∂h` phenomenon (inversion layers), and ionosphere is layered by altitude. So:
- Keep the SoA grid's **vertical dimension first-class** from day one (index `(cx, cz, layer)`), even if
  v1 runs only a few layers cheaply.
- Re-weight **OQ5** toward "stacked Y-layers now," and treat the 2.5D→3D transition as the first
  post-v1 milestone rather than a distant maybe.

> **OQ5 RESOLVED (user, 2026-06-12): stacked Y-layers from day one** — `(cx, cz, layer)`, ~4 layers
> initial, config-tunable (01 §2). Radio's vertical requirement is satisfied in v1's grid shape.

---

## 4. Out of scope here (radio's own work, recorded for context)

Ionospheric Chapman-layer / solar-flux model, antenna patterns & gain, TX power / link budget,
modulation/decode, terrain knife-edge diffraction math, per-material ground conductivity tables —
these belong to the radio mod. This file only fixes the **shared-substrate contract** the climate mod
must honour so that work is possible without a rewrite.
