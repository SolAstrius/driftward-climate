# driftward-climate — Default Field Contract & Piggyback Consumers

> The field layer is a **generic typed field-engine**; climate is its first plugin, radio its second,
> and several other Minecraft systems are field-shaped and can ride the same grid + operators +
> snapshot. This file fixes the **default contract** so adding a consumer = register/read a field,
> never a rewrite. Companion: `04` (radio, the motivating second reader).

---

## 1. The generalization (D12)

Do **not** hardcode "climate's 12 fields." The substrate is:
```
WorldFieldGrid = named-field registry  +  generic operators  +  standard derived accessors
                 +  shared services (solar, terrain, material)  +  region/snapshot semantics
```
Climate `registerField`s its prognostic set and drives the solver; other consumers register their own
fields and/or just read snapshots. **Build this registry shape in v1** even though only climate uses it
first — it's a modest upfront cost vs. a refactor when radio/sound/pollution arrive.

**Relationship to `Atmosphere` (spec 01 §9):** `Atmosphere` is a thin *climate-flavored façade* over
this contract — it owns naming/units (°C, normalized pressure); the registry owns storage,
interpolation and operators. There is **one** public substrate; new consumers target §3.5, not the
façade. (This is what keeps the two surfaces from drifting.)

---

## 2. Piggyback consumers (anything that's a PDE on space)

| Consumer | Reuses | Adds | Cost | Notes |
|---|---|---|---|---|
| **Amateur radio** (`04`) | `T,P,q_v`, `∇`, heightmap, solar | ionization layer, geomagnetic | med | refractivity `N`, ducting, MUF |
| **Sound propagation** | `T`, `v`, terrain, `∇` | — (derived `c`) | ~free | `c=331.3·√(1+T/273)`; wind-advected, gradient-refracted |
| **Pollution / smoke / gas** | `v`, advect, `∇²` | tracer scalar(s) | cheap | canonical dye-in-fluid |
| **Wildfire spread** | `v`, `q_v`, `T`, terrain | fuel + burn scalar | cheap | wind-driven; drought via low `q_v` |
| **Hydrology** | `q_r`, `T`, heightmap (`−∇h`) | soil-moisture, snow-depth | med | runoff/flood, farmland, snowpack |
| **Wind power / sailing** | `v` | — | ~free | Create windmills, Sable sails, kites |
| **Lightning / storms** | `ζ`, `q_c`, `−∇·v` | strike-probability | ~free | storms→fire ignition→radio QRN |
| **"Feels-like" temp** | `T`, `v`, `q_v` | — (derived) | ~free | wind-chill/heat-index — **upgrades Thermoo coupling** |
| **Atmospheric optics** | `T`, `q_v`, `∇N` | — | ~free | fog/visibility, mirage — optical twin of radio |
| **Magnetism / aurora / compass** | solar, ionization | geomagnetic vector | low | Sable has `magneticNorth`; aurora ↔ ionosphere |
| **Agriculture / ecology** | `T`, solar, soil-moisture | growth-rate | low | field-driven growth, biome drift |
| **Scent / tracking** | `v`, advect | scent tracer | cheap | predator/prey downwind tracking |

**Three reusable kernels cover most of them:** refraction (`∇` of a speed/index field) → radio + sound +
optics; advected tracer → smoke + scent + pollution + fire; field-read → water + sailing + lightning.

### 2.1 Reaction–Advection–Diffusion: smoke + fire + gas + scent are ONE subsystem

Everything in the "advected tracer" kernel is the same PDE — only the reaction term differs:
```
∂φ/∂t = −(v·∇)φ  +  D∇²φ  +  R(φ,…)  +  S
        advection    diffusion  reaction    source
        (shared v,    (turbulent (decay /    (emitters /
         same path     spread)    deposition/  ignition)
         as q fields)             combustion)
```
| substance | advect | `R` | buoyant |
|---|---|---|---|
| smoke | yes | −decay | yes (hot→rises) |
| pollution | yes | −deposition | no |
| gas leak | yes | −decay/chemistry | per-gas |
| scent | yes | −decay | no |
| **fire** | — (fuel static) | **combustion: fuel+heat → −fuel, +θ, +smoke, +q_v** | via emitted heat |

**Fire is not a separate engine** — it's a reaction coupling a static `fuel` field (from
`SurfaceMaterial`) to `θ` (heat) and the `smoke` tracer. *Spread is emergent*: combustion heat → `θ` →
buoyant updraft → advected by `v` → preheats downwind fuel past ignition. Same transport as a smoke plume.

**Free cascade** (shared substrate, no special-casing): lightning (`ζ`,`q_c`,updraft) → ignites fire →
emits smoke + heat (`θ`→pyroconvection) + `q_v`; rain (`q_r`) suppresses; wind (`v`) advects both.

⇒ Contract impact: one **`ReactiveTracer`** subsystem owns advect/diffuse/buoyancy (shared with
climate moisture); §3.5 `registerSource` generalizes to **`registerReaction(inputs…, outputs…, rate)`**.
smoke / pollution / gas / scent / **fire** = "register a substance (+ reaction)", zero bespoke machinery.

### 2.2 Gas *type* = multi-species reactive transport

Tracking gas **type** = keeping species as separate labeled tracers on the same `v`, coupled by the
reaction network. Each species carries properties → emergent behavior (no hardcoding):

| property | drives | reuses |
|---|---|---|
| molar mass `M` | buoyancy: `M>29` (CO₂, Cl₂) sinks/**pools in low terrain**; `M<29` (H₂, CH₄) rises | buoyancy + **heightmap** |
| flammability | ignition coupling (gas pocket + fire → combustion) | fire reaction §2.1 |
| toxicity | entity effect over concentration threshold | snapshot read |
| solubility | rain washout (`q_r` scavenges) | `q_r` |
| reactivity | reaction network (CO+O₂→CO₂, …) | `registerReaction` |

**Representation fork** (N species = N fields → choose storage per substance):
- **Eulerian field** — pervasive species (smoke, climate moisture). Always-on cost.
- **Sparse / opt-in activation** — a species grid is *live only in chunks containing it*; gas leaks are
  localized → near-zero cost when absent. **Default for gases.**
- **Lagrangian puffs** `(pos, type, amount)` advected by `v` — sparse transient releases (a cracked gas
  line); how real plume-dispersion models work; no world-grid for a 5-block cloud.

⇒ Contract: `registerField` gains per-species metadata `{molarMass, flammable, toxic, solubility}` + a
storage mode `{eulerian | sparse | lagrangian}`. Same `v`, same reaction registry; different backing store.

---

### 2.3 Sub-grid point sources (heat/cold/emitters below grid resolution)

A campfire warms ~3–5 blocks; a grid cell is 16. **Scale separation** — every sub-grid point source
splits into two terms at two resolutions:

1. **Near-field kernel — block-accurate, query-time, NOT on the grid.**
   ```
   nearField(pos) = Σ_sources  strength(type) · falloff(|pos − source|)   for sources within N
   ```
   - Backed by a **per-chunk source index** (`blockPos, strength, type`: campfire/torch/lava/furnace,
     negative for ice/powder-snow), refreshed on **block-change events** (same path as heightmap/exposure).
   - Query gathers from the 1–2 chunks around `pos`; `N` = falloff radius, **independent of the 16-block
     grid** (can be 3/5/8 — sub-chunk). Optional **LoS occlusion** (wall blocks radiant heat) reuses sky-occlusion.

2. **Grid coupling — the aggregate effect.** The source *also* injects into its cell as a RAD source
   (§2.1): fireplace = `θ` source (+ `smoke` source) → warms local air, drives plume/convection, lofts smoke.

```
feltTemp(pos) = ambientField(pos)   // chunk-scale air temp (incl. aggregate source injection)
              + nearField(pos)       // block-scale radiant heat (the "within N blocks" term)
```
Physically: air temperature (advected field) vs radiant heat (near-field falloff) — why it's a *radius*,
not a cell. **Generalizes** to any sub-grid emitter (heat/cold/gas/sound): coarse grid gets the aggregate
source term, near-field gets the query-time kernel — one mechanism, not a fireplace special case.

> Ownership: "near a heat source" is the **local/bodily** half of the ambient-vs-local split (spec 02 §D).
> Thermoo's `EnvironmentController` (Scorchful `onFireWarmRate`/`inLavaWarmRate`/`coolingFromIce`) already
> does *contact* heat; **v1 = augment** (our near-field adds the true radiant radius, which contact-only
> misses; their contact handlers stay). Full takeover for one consistent model is **OQ11** (spec 00 §4),
> not v1. Either way it's the climate mod's near-field layer, off-grid.

## 3. Default contract surface

### 3.1 Default-carried fields (climate populates)
`T`/`θ′`, `P`/`P′`, `ρ`, `q_v`, `q_c`, `q_r`, `v=(u,w[,W])`, `ζ`, `δ`.

### 3.2 Shared services (multi-consumer; factor out, don't bury in climate)
- `SolarForcing` — sun vector, zenith, daylight fraction, season → radiation, ionosphere, plants, aurora.
- `Terrain` — live heightmaps (`MOTION_BLOCKING_NO_LEAVES`/`OCEAN_FLOOR`), event-refreshed.
- `SurfaceMaterial` — per-column/material: albedo, roughness, **ground conductivity** (radio), **fuel** (fire),
  infiltration (water). Material-derived lookup so each consumer gets its coupling for free.

### 3.3 Standard derived accessors (computed once, consistent, shared)
`N` refractivity · `c` sound speed · `windChill` · `heatIndex` · `dewPoint` · `fogDensity`/visibility · `ρ`.
(Single source of truth — consumers must not each re-derive these differently.)

### 3.4 Registerable opt-in fields (pay only if enabled)
pollution/scent tracers · fire-fuel/burn · soil-moisture · snow-depth · ionization layer · geomagnetic vector.

### 3.5 Consumer hooks (the public API)
```
registerField(name, Scalar|Vector, advect?, diffuse?, buoyant?, persist?,
              storage = eulerian|sparse|lagrangian,                  // §2.2 representation fork
              species = {molarMass, flammable, toxic, solubility}?)  // gas-type metadata, optional
registerSource(field, sourceTerm)                                 // pluggable forcing (point/area emitter)
registerReaction(inputs…, outputs…, rate)                         // RAD reaction: combustion, decay, chemistry (§2.1)
registerPointSource(blockPredicate, field, strength, radiusN, falloff, occlude?)  // sub-grid source: near-field kernel + grid injection (§2.3)
nearField(field, pos)                                             // query-time sub-grid sum (radiant heat, etc.)
readSnapshot(level, pos, interp=bilinear)                         // lock-free, double-buffered
gradient(field, pos) / div(field) / curl(field) / laplacian(field)// generic operators
derived(name, pos)   // N, c, windChill, ...
handles(level, pos) -> Boolean                                    // region coverage (else fall back)
```

---

## 4. Discipline

- **Opt-in compute.** API shape accommodates all consumers; **only enabled fields cost FLOPs**. Default
  build = climate only.
- **Read-mostly consumers.** Radio/sound/optics query on demand against the snapshot; they must not
  perturb the solver or the main tick.
- **Stable, small, versioned public surface.** Consumers are sibling mods depending on this contract,
  not forks. Treat §3.5 as the API to keep stable.
- **Vertical first-class** (from `04`/OQ5): refraction (radio, sound, optics) and ionosphere are vertical
  phenomena → index `(cx, cz, layer)` in the registry from day one.
