# driftward-climate — Affected Components, Paths & LoC

> Every file created, modified, or removed. LoC for **new** files are *estimates* (to size the work);
> LoC for **existing** files are *actual* (measured 2026-06-12). Spec refs point to `01`/`02`.

---

## 1. NEW — the standalone mod (`/home/sol/packwiz/driftward-climate/`)

### Source (Kotlin unless noted)
| Path (under `src/main/`) | LoC~ | Spec | Purpose |
|---|---:|---|---|
| `kotlin/.../DriftwardClimate.kt` | 60 | 01§11 | KFF `@Mod` object; register provider type + mixin; lifecycle hooks |
| `kotlin/.../Atmosphere.kt` | 150 | 01§9 | Public query API (interp snapshot): temperature/pressure/density/humidity/cloud/wind |
| `kotlin/.../field/WorldFieldGrid.kt` | 200 | 05§1,§3.5 | **D12:** generic named-field registry, tier-aware reads, consumer hooks |
| `kotlin/.../field/ClimateField.kt` | 250 | 01§3,§11 | SoA region: `FloatArray` per field, indexing, double-buffer, swap |
| `kotlin/.../field/NearFieldIndex.kt` | 150 | 05§2.3 | per-chunk point-source index + query-time radiant kernel (`nearField`) |
| `kotlin/.../field/Operators.kt` | 300 | 01§4 | grad/div/curl/laplacian + semi-Lagrangian advect; Vector-API-ready |
| `kotlin/.../field/Projection.kt` | 250 | 01§5,§10 | SOR/multigrid Poisson; optional JTransforms FFT backend |
| `kotlin/.../solver/AtmosphereSolver.kt` | 400 | 01§5 | step orchestration: forces→advect→diffuse→microphysics→project→relax |
| `kotlin/.../solver/Thermodynamics.kt` | 120 | 01§6 | θ↔T, ρ, q_sat (Tetens), latent heat, θ_v |
| `kotlin/.../model/BiomeClimateMap.kt` | 120 | 01§8 | biome → θ/humidity targets (data-driven config) |
| `kotlin/.../model/ChunkClimate.kt` | 180 | 01§8 | per-chunk static baseline (cache, season/time analytic) |
| `kotlin/.../model/SolarForcing.kt` | 80 | 04§2.3 | shared sun/zenith/season service (radiation now, ionosphere later) |
| `kotlin/.../model/ExposureSampler.kt` | 150 | 01§7 | heightmap + skylight per column; `solar`/`shelter`/`T_ground`; event-refresh |
| `kotlin/.../runtime/RegionManager.kt` | 250 | 01§2, 06§4 | T2 moving-window lifecycle, velocity prefetch, off-thread step, buffer swap |
| `kotlin/.../runtime/SynopticTier.kt` | 250 | 06§2,§5 | **D13:** T1 coarse grid — slow step, SavedData persistence, sleep/fast-forward, fold-on-shutdown |
| `kotlin/.../runtime/BlockChangeListener.kt` | 60 | 01§7 | invalidate exposure profile on block events |
| `kotlin/.../config/ClimateConfig.kt` | 120 | 01§10 | tunables (cadence, κ, Coriolis f, lapse, thresholds, enable flags) |
| `kotlin/.../integration/thermoo/AtmosphereEnvironmentProvider.kt` | 80 | 02§A.3 | `EnvironmentProvider` → TEMPERATURE + RELATIVE_HUMIDITY |
| `kotlin/.../integration/thermoo/ClimateProviderTypes.kt` | 50 | 02§A.3 | register `driftwardclimate:atmosphere` type + MapCodec |
| `java/.../mixin/sable/DimensionPhysicsPressureMixin.java` | 40 | 02§B.2 | `@Inject` HEAD `getAirPressure(Level,Vector3dc)` → `Atmosphere.pressureAt` |
| *(later)* `kotlin/.../client/CloudRenderer.kt` | 300 | 01§6,OQ6 | render `q_c`; deferred past v1 |
| **Source subtotal (v1, excl. client)** | **≈3 260** | | *(was ≈2 580 pre-D12/D13; +680 for WorldFieldGrid, NearFieldIndex, SolarForcing, SynopticTier)* |

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
`build.gradle` (~55) · `settings.gradle` (~8) · `gradle.properties` (~3) · `.gitignore` (~10) ·
gradle wrapper (copied) · `libs/{thermoo,sable,sable-companion,aeronautics}.jar` (copied, gitignored).

**Build/versions TODO (before first build):**
- Pin **Kotlin for Forge** version for NeoForge 21.1.x; confirm `modLoader` (`javafml` + KFF dep vs `kotlinforforge`).
- Pin Kotlin plugin to KFF's bundled Kotlin.
- Decide KFF packaging: separate pack mod vs jarJar (**OQ4**).
- If FFT-Poisson: add `com.github.wendykierp:JTransforms:3.1` (commented stub present).
- If Vector API SIMD: add `--add-modules jdk.incubator.vector` to launcher JVM args.

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
| `mods/` (packwiz) | **ADD** `driftward-climate` (`.pw.toml`) and **`kotlinforforge`** (KFF runtime), unless KFF jarJar'd |
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
