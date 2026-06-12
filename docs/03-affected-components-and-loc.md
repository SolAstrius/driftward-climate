# driftward-climate — Affected Components, Paths & LoC

> Every file created, modified, or removed. LoC for **new** files are *estimates* (to size the work);
> LoC for **existing** files are *actual* (measured 2026-06-12). Spec refs point to `01`/`02`.

---

## 1. NEW — the standalone mod (`/home/sol/packwiz/driftward-climate/`)

### Source — D14 module split: `core/` = pure Kotlin/JVM (MC-less), rest = the NeoForge mod

**`core/src/main/kotlin/ink/astrius/driftwardclimate/core/`** (zero MC/NeoForge deps; tested by `:core:test`)
| Path | LoC~ | Spec | Purpose |
|---|---:|---|---|
| `api/Ports.kt` | **74 ✅** | 01§11 | **D14 SPI:** TerrainPort, BaselinePort, SolarPort, SeasonPort, ClockPort |
| `api/FieldContract.kt` | 60 | 05§3.5 | registerField/registerReaction/registerPointSource/readSnapshot/operators/derived |
| `field/WorldFieldGrid.kt` | **51 ✅** | 05§1,§3.5 | **D12:** façade: registry + region + near-field; `resolve()` = grid C1 + kernel |
| `field/Fields.kt` | **60 ✅** | 05§3.5 | FieldDef / FieldHandle / freezing FieldRegistry *(split from the old ClimateField sketch)* |
| `field/FieldRegion.kt` | **253 ✅** | 01§3,§9 | GridGeometry (layerY vertical), SoA double-buffer, Snapshot: C0/C1 sampling + **analytic spline gradient** |
| `field/NearFieldIndex.kt` | **103 ✅** | 05§2.3 | per-chunk COW source index + Wendland C1 kernel |
| `field/Operators.kt` | **426 ✅** | 01§4,§2.1 | 2D + **3D** stencils (grad/div/curl/∇²/ddy), semi-Lagrangian advect 2D/**3D**, diffuse, material derivative; conventions doc |
| `field/VectorCalculus.kt` | **239 ✅** | 01§4 | **Helmholtz decomposition** (ψ/χ via Poisson), circulation/flux/area integrals (Stokes + divergence thms), **Okubo–Weiss** |
| `field/Projection.kt` | **171 ✅** | 01§5,§10 | **OQ2:** JTransforms DCT FFT-Poisson (default, scale-pair verified) + red-black SOR reference |
| `solver/AtmosphereSolver.kt` | 400 | 01§5 | step orchestration: forces→advect→diffuse→microphysics→project→relax |
| `solver/Thermodynamics.kt` | 120 | 01§6 | θ↔T, ρ, q_sat (Tetens), latent heat, θ_v |
| `model/Baseline.kt` | 180 | 01§8 | T0 static baseline math (cell targets + lapse + season/diurnal analytic) |
| `model/Reconstruction.kt` | 80 | 06§3 | T1+T0+seeded-detail downscaling read path |
| `tier/SynopticTier.kt` | 250 | 06§2,§5 | **D13:** T1 coarse grid — slow step, sleep/fast-forward, fold-on-shutdown, byte-payload persistence |
| `config/ClimateConfig.kt` | 120 | 01§10 | plain data-class tunables (cadence, κ, f, layers, thresholds) |
| *(tests)* `core/src/test/kotlin/…` | **~1 050 ✅ (57 tests)** | 01§12 | operators (2D/3D), identities (curl∘grad, div∘curl @ machine-ε), Stokes + divergence thms, Helmholtz, projection (incl. FFT↔SOR cross-check), C0/C1/spline-gradient sampling, near-field, façade |
| *(bench)* `core/src/jmh/kotlin/FieldEngineBench.kt` | **~180 ✅** | 01§10 | JMH: stencils, advect 2D/3D, FFT vs SOR projection, query path (`:core:jmh`) |

**NeoForge mod (`src/main/`)** — thin adapter
| Path | LoC~ | Spec | Purpose |
|---|---:|---|---|
| `kotlin/.../DriftwardClimate.kt` | 60 | 01§11 | KFF `@Mod` object; instantiates core, wires ports, registers provider type |
| `kotlin/.../Atmosphere.kt` | 150 | 01§9 | façade: Level/BlockPos/°C ↔ core domain; C0/C1 interp policy |
| `kotlin/.../adapter/LevelTerrainAdapter.kt` | 150 | 01§7 | TerrainPort impl: heightmap + skylight per column, event-refresh |
| `kotlin/.../adapter/BiomeBaselineAdapter.kt` | 120 | 01§8 | BaselinePort impl: biome → θ/humidity targets (data-driven config) |
| `kotlin/.../adapter/VanillaSolarAdapter.kt` | 50 | 01§7, 04§2.3 | SolarPort impl via `getTimeOfDay`/`getSunAngle` (ES-aware) + season via EclipticSeasonsApi |
| `kotlin/.../adapter/RegionLifecycle.kt` | 200 | 06§4 | player tracking → core T2 window; velocity prefetch; off-thread step executor |
| `kotlin/.../adapter/BlockChangeListener.kt` | 60 | 01§7 | invalidate exposure + near-field index on block events |
| `kotlin/.../adapter/T1SavedData.kt` | 40 | 06§5 | SavedData/NBT wrapper around core's opaque T1 payloads |
| `kotlin/.../integration/thermoo/AtmosphereEnvironmentProvider.kt` | 80 | 02§A.3 | `EnvironmentProvider` → TEMPERATURE + RELATIVE_HUMIDITY |
| `kotlin/.../integration/thermoo/ClimateProviderTypes.kt` | 50 | 02§A.3 | register `driftwardclimate:atmosphere` type + MapCodec |
| `java/.../mixin/sable/DimensionPhysicsPressureMixin.java` | 40 | 02§B.2 | `@Inject` HEAD `getAirPressure(Level,Vector3dc)` → `Atmosphere.pressureAt` |
| *(later)* `kotlin/.../client/CloudRenderer.kt` | 300 | 01§6,OQ6 | render `q_c`; deferred past v1 (OQ6: server-sim only) |
| **Source subtotal (v1, excl. client + tests)** | **≈3 440** | | *core ≈2 440 + mod ≈1 000* |

### Resources
| Path (under `src/main/resources/`) | LoC~ | Spec | Purpose |
|---|---:|---|---|
| `META-INF/neoforge.mods.toml` | 40 | 02 | metadata + deps (KFF, thermoo, sable) — **scaffolded** |
| `driftward-climate.mixins.json` | 12 | 02§B | mixin config (empty until hook lands) — **scaffolded** |
| `data/scorchful/thermoo/environment/*.json` | 9 files | 02§A.4 | shadow Scorchful's 9 defs → our provider |
| `data/frostiful/thermoo/environment/*.json` | 8 files | 02§A.4 | shadow Frostiful's 8 defs → our provider |
| `data/driftwardclimate/thermoo/environment/atmosphere.json` | 1 file | 02§A.4 | overworld → our provider, high priority |
| `data/driftwardclimate/thermoo/environment_provider/*.json` | ~few | 02§A | named provider instance(s) if used |

### Build / project (scaffolded, no code)
`build.gradle` (root, mod) · `core/build.gradle` (pure Kotlin/JVM + JTransforms + JUnit) ·
`settings.gradle` (includes `core`) · `gradle.properties` · `.gitignore` · gradle wrapper (copied) ·
`libs/{thermoo,sable,sable-companion,aeronautics}.jar` (copied, gitignored).

**Build/versions TODO (before first build):**
- Pin **Kotlin for Forge** version for NeoForge 21.1.x; confirm `modLoader` (`javafml` + KFF dep vs `kotlinforforge`).
- Pin Kotlin plugin to KFF's bundled Kotlin.
- ~~Decide KFF packaging~~ **OQ4 resolved:** separate mod, already in the distro — pin version only.
- ~~If FFT-Poisson~~ **OQ2 resolved:** `com.github.wendykierp:JTransforms:3.1` is an active `core` dependency.
- **D14:** bundle `:core` into the mod jar for distribution (jarJar or shadow) — the server/client see ONE jar.
- If Vector API SIMD: add `--add-modules jdk.incubator.vector` to launcher JVM args (tests too).

---

## 2. MODIFIED / REMOVED — `driftward-fixes` (retire the totalLift hack, D6 / 02§C)

| Path | now | Action |
|---|---:|---|
| `src/main/java/.../mixin/BalloonTemperatureMixin.java` | 69 | **DELETE** (the step-function descent bug) |
| `src/main/java/.../mixin/BalloonAccessor.java` | 23 | **DELETE** (only used by the above) |
| `src/main/resources/driftward-fixes.aeronautics.mixins.json` | 8 | **DELETE** (no mixins left in it) |
| `src/main/java/.../Config.java` | 52 | **TRIM**: remove the `balloon_temperature` block (BALLOON_* fields + static init); file likely → minimal/empty SPEC. *(My earlier dangling `BALLOON_ALTITUDE_*` edit already reverted 2026-06-12.)* |
| `src/main/resources/META-INF/neoforge.mods.toml` | 67 | **TRIM**: drop the `[[mixins]]` aeronautics entry + `aeronautics`/`thermoo` optional deps (and `Config` registration if Config emptied) |
| `src/main/java/.../DriftwardFixes.java` | 12 | **MAYBE**: if `Config` is emptied, drop `registerConfig(...)` |
| `build.gradle` | 29 | **TRIM**: drop `compileOnly` `aeronautics`/`sable`/`sable-companion`/`thermoo` (only the balloon mixin used them; create/thirst/C&C/supplementaries stay) |

> Net: driftward-fixes goes back to being purely the C&C / Supplementaries / Create-Thirst bugfix mod;
> all balloon/climate concerns migrate to driftward-climate. Update its `README.md` + the
> `driftward-fixes-mod` memory afterward.

---

## 3. PACK — `/home/sol/packwiz/driftward/`

| Item | Action |
|---|---|
| `mods/` (packwiz) | **ADD** `driftward-climate` (`.pw.toml`); KFF already in the distro (OQ4) — verify/pin version only |
| `kubejs/data/{thermoo,scorchful,frostiful}/tags/**` | **KEEP** — entity-interaction tags (foods/armor), orthogonal to ambient (02§D) |
| `config/scorchful.json` | **KEEP** — its `minTemperatureForHeatC=30` etc. define the °C bands our provider must respect (02§A.2) |
| Datapack overrides | Shipped **inside the climate jar** (`data/scorchful|frostiful/...`), so no separate pack datapack needed |
| Client (potato-launcher `tree_smp` instance) | Copy climate jar (+ KFF) to the hand-built client; add `--add-modules jdk.incubator.vector` to launch args if SIMD enabled |
| Deploy | Restart the `minecraft` deploy to load server-side (server-authoritative sim) |

---

## 4. Reference targets (decompiled, for implementers)

| Symbol | Jar | Used by |
|---|---|---|
| `EnvironmentLookup.findEnvironmentComponents(Level,BlockPos)` | thermoo | 02§A.1 |
| `EnvironmentProvider` / `EnvironmentProviderType<T>(MapCodec)` | thermoo | 02§A.3 |
| `EnvironmentComponentTypes.{TEMPERATURE:TemperatureRecord, RELATIVE_HUMIDITY:Double}` | thermoo | 02§A.3 |
| `ThermooRegistries.ENVIRONMENT_PROVIDER_TYPE` / `ThermooRegistryKeys.ENVIRONMENT` | thermoo | 02§A.3/A.4 |
| `TemperatureRecord(double,TemperatureUnit)` / `valueInUnit(CELSIUS)` | thermoo | 02§A |
| `ServerPlayerEnvironmentTickEvents.GET_TEMPERATURE_CHANGE` (consumption) | thermoo | 02§A.2 |
| `DimensionPhysicsData.getAirPressure(Level,Vector3dc)` | sable | 02§B (hook) |
| `DimensionPhysics.createDefault` (exp(-0.004·(y−sea)) curve) | sable | 01§8, 02§B |
| `ServerBalloon.applyForces(double)` (pressure + ±1 gradient buoyancy) | aeronautics | 02§B/§C |
| `MixinLevelTimeAccess` `@Overwrite getTimeOfDay` → `SolarAngelHelper.getSeasonCelestialAngle(LevelTimeAccess,long)` (gated on `daylightChange`) | eclipticseasons | 01§7, 02§D — ES owns the sun; read via vanilla accessors |
| `EclipticSeasonsApi.getInstance()` — `getSolarTerm/getSeason/getSubSeason`, `isDay/isNight`, `isRainAt/isSnowAt/isThunderAt`, `getBaseHumidity/getAdjustedHumidity` | eclipticseasons | 01§8 (season input), 02§D (weather boundary) |
| `SolarDataManager` (SavedData: solar-term day/ticks, greenhouse + humidity providers) | eclipticseasons | 02§D context |

Decompile workspace used: `/tmp/jdump` (cfr/jdk21 via nix-shell); jars in `driftward-fixes/libs/` +
`EclipticSeasons-1.21.1-neoforge-0.13.5.2.jar` from the live mods dir.
