# driftward-climate — Integration Spec

> How the sim plugs into the existing stack. All signatures below are **verified by decompiling**
> the shipped jars (thermoo-4.8.1-neoforge, sable, aeronautics) in this pack.

---

## A. Thermoo — we become the ambient-environment authority

### A.1 The bus (verified)
Every ambient consumer reads through one call:
```java
// com.github.thedeathlycow.thermoo.api.environment.EnvironmentLookup
DataComponentMap findEnvironmentComponents(Level world, BlockPos pos);
//   → EnvironmentComponentTypes.TEMPERATURE        : TemperatureRecord  (°C via valueInUnit(CELSIUS))
//   → EnvironmentComponentTypes.RELATIVE_HUMIDITY  : Double (0..1)
```
`EnvironmentLookupImpl` resolves the biome at `pos`, runs that biome's `EnvironmentProvider`s into a
`DataComponentMap.Builder`. Providers attached per-biome at server start from the `thermoo:environment`
registry, **priority-sorted (desc)**.

### A.2 Consumption is read-transparent (verified)
Both mods subscribe to `ServerPlayerEnvironmentTickEvents.GET_TEMPERATURE_CHANGE` and read **only** the
`TEMPERATURE` °C value:
- **Scorchful** `ServerPlayerEnvironmentTickListeners.environmentTemperatureToTemperatureChange`:
  below `minTemperatureForHeatC` (**30 °C**, our `config/scorchful.json`) → 0; above →
  `+floor(mult·(tempC−30+10)/10)` (heats).
- **Frostiful** `ServerPlayerEnvironmentTickListeners.envTemperatureToTemperaturePoint`:
  above `maxTemperatureForColdC` (~0 °C) → 0; below → negative (cools), **×soaked-mult if wet**.
- ⇒ **Neutral band ~0–30 °C.** If our provider writes a °C value, both mods respond correctly with
  **zero code changes**. The only contract: stay in the same °C scale / honour that band.

### A.3 What we register (extension point, verified)
```java
// open NeoForge registry — ThermooRegistryKeys.ENVIRONMENT_PROVIDER_TYPE
// EnvironmentProvider.ELEMENT_CODEC = registry.byNameCodec().dispatch("type", ::getType, ::codec)
interface EnvironmentProvider {
    void buildCurrentComponents(Level, BlockPos, Holder<Biome>, DataComponentMap.Builder);
    EnvironmentProviderType<? extends EnvironmentProvider> getType();
}
class EnvironmentProviderType<T>(MapCodec<T> codec)
```
We register **one** type `driftwardclimate:atmosphere`:
```
Registry.register(ThermooRegistries.ENVIRONMENT_PROVIDER_TYPE,
                  ResourceLocation("driftwardclimate","atmosphere"), TYPE)
   // NeoForge: do this in a RegisterEvent for ThermooRegistryKeys.ENVIRONMENT_PROVIDER_TYPE
```
`AtmosphereEnvironmentProvider.buildCurrentComponents(...)` does:
```
builder.set(TEMPERATURE,        TemperatureRecord(Atmosphere.temperatureAt(world,pos,time), CELSIUS))
builder.set(RELATIVE_HUMIDITY,  Atmosphere.humidityAt(world,pos))
```

### A.4 Taking over (the 17 definitions)
Override the existing definitions by **shadowing their datapack paths** (our pack/jar loads with higher
priority) so every overworld biome resolves to *our* provider:
```
data/scorchful/thermoo/environment/{temperature/scorching_climate, temperature/warm_climate,
    temperature/temperate_climate, humidity/arid_climate, humidity/rainy_climate,
    humidity/overworld_rain, humidity/humid_cave, humidity/hell, hell}.json            (9)
data/frostiful/thermoo/environment/{cold_climate, cool_climate, freezing_climate, hell,
    snowy_biomes, stony_peaks_climate, temperate_climate, void}.json                   (8)
+ data/driftwardclimate/thermoo/environment/atmosphere.json   (biomes=#is_overworld → our provider, high priority)
```
**MUST NOT silently drop:** Scorchful also owns **RELATIVE_HUMIDITY** (the `humidity/*` defs) and
Frostiful owns **snow/freezing climate**. Our provider must emit *both* temperature and humidity, or
sweat/thirst (Scorchful) and freezing/snow (Frostiful) go blank. Nether/End/void handling: keep their
`hell`/`void` defs unless we explicitly model those dimensions.

> Note: `EnvironmentLookupImpl.addProvidersToBiomes` attaches *all* matching defs' providers and runs
> them in priority order into one builder (last write wins per component). Cleanest "takeover" =
> **shadow the files** so ours is the only matching def, rather than relying on priority/override order.
> *(Confirm builder last-write-vs-first-write during impl.)*

---

## B. Sable — it asks us for pressure (control inverted)

### B.1 Current behaviour (verified)
```java
// dev.ryanhcode.sable.physics.DimensionPhysicsData
public static double getAirPressure(Level level, Vector3dc pos) {
    DimensionPhysics physics = of(level);
    double base = physics.basePressure()...;            // 1.0 overworld
    BezierResourceFunction curve = physics.pressureFunction()...;
    return base * curve.evaluateFunction(pos.y());      // exp(-0.004·(y−sea)), →0 at build height
}
// getGravity(level,pos) ignores pos → constant -11 (NOT the curve)
```
`DimensionPhysics.createDefault()` builds that Bézier; it is also datapack-driven via the
`dimension_physics` reload listener — **but D7 says do NOT supply a curve; hook the call instead.**

### B.2 The hook
Mixin (Java) on the static method, redirect to our field:
```java
@Mixin(value = DimensionPhysicsData.class, remap = false)
public class DimensionPhysicsPressureMixin {
  @Inject(method = "getAirPressure(Lnet/minecraft/world/level/Level;Lorg/joml/Vector3dc;)D",
          at = @At("HEAD"), cancellable = true, remap = false)
  private static void driftward$pressure(Level level, Vector3dc pos, CallbackInfoReturnable<Double> cir) {
      if (ClimateRuntime.handles(level))
          cir.setReturnValue(Atmosphere.pressureAt(level, pos));   // 3D, dynamic, temperature-coupled
  }
}
```
- **Why this is exactly right:** `ServerBalloon.applyForces` (verified) reads `getAirPressure` at the
  balloon centre **and at ±1 block in x/y/z** to form a pressure **gradient** for buoyancy direction.
  Feeding our field there means the balloon gets real density-buoyancy **and drifts on `∇P` wind** — the
  gradient it already differences.
- **Scope caveat:** `getAirPressure` is **global** to Sable physics (propellers/fans too), not
  balloon-only — consistent, but know it's broad. Gate by dimension/region via `ClimateRuntime.handles`.
- **Fallback:** when our region doesn't cover `pos` (or sim disabled), don't cancel → Sable's native
  curve answers. No hard dependency.
- **Calibration constraint (seamless degradation):** T0's pressure baseline is *defined as* Sable's
  default barometric curve — `base · exp(-0.004·(y−sea))`, Bézier-clamped exactly as
  `DimensionPhysics.createDefault` — and our field stores **anomalies on top of it**. Then
  `pressureAt` ≡ Sable's native answer wherever anomalies are zero, so crossing a `handles()`
  boundary, *and* every LoD downgrade T2→T1→T0 (`06` §4), is seamless **by construction**. Without
  this, the coverage seam itself reintroduces a lift jump — the bug class we're killing.
- **Interpolation:** pressure is consumed by finite differencing (the ±1-block gradient above), so it
  must be served **C1-interpolated** (smoothstep/bicubic), never plain bilinear — see `01` §9.

### B.3 Pressure-vs-density note
Sable scales lift by *pressure*. Our `pressureAt` returns an **effective buoyant value** folding altitude
falloff × air density (cold/dense ⇒ higher ⇒ more lift), consistent with `densityAt`. This is the clean
replacement for the old "pressure-as-proxy" hand-wave now that we carry real ρ.

---

## C. Aeronautics — retire the totalLift hack (D6)

- **Remove** driftward-fixes `BalloonTemperatureMixin` (scaled `ServerBalloon.updateGasAmounts` totalLift
  by step-function Thermoo temp = the descent bug) **and** its helper `BalloonAccessor`.
- Lift now comes **solely** from Sable's native pressure-buoyancy reading our smooth field via §B.
  One mechanism, continuous by construction.
- See `03` for the exact files/lines to delete and the driftward-fixes metadata/build trimming.

---

## D. Orthogonal (leave alone)

- **kubejs entity-interaction tags** (`thermoo:consumable/*`, `scorchful:is_*`, `frostiful:warm_foods`,
  `thermoo:armor_material/*`, thirst settings) drive the **per-entity `EnvironmentController`** (foods
  warm you, armor resists cold), **not** ambient `EnvironmentLookup`. They stay; we keep feeding them.
- **Entity-side contact heat** (Scorchful `onFireWarmRate`/`inLavaWarmRate`, Frostiful ice cooling):
  **v1 = leave alone and augment** — our near-field radiant term (`05` §2.3) adds the *radius* effect
  contact-only handlers miss; theirs keep handling contact. Full takeover for one consistent model is
  **OQ11**, not v1.
- **Seasons calendar + sun geometry** (Ecliptic Seasons — verified by decompiling
  `EclipticSeasons-1.21.1-neoforge-0.13.5.2`): we read season *state* as an input (01 §8), we don't
  reimplement the calendar. Two integration facts:
  - **ES owns the sun.** It `@Overwrite`s `LevelTimeAccess.getTimeOfDay(float)` →
    `SolarAngelHelper.getSeasonCelestialAngle` (gated on `daylightChange` config + valid dimension), so
    seasonal day length changes the celestial angle server-side. `SolarForcing` reads sun position
    **through the vanilla accessors only** (01 §7) — they already return the seasonal sun.
  - **Public API to consume:** `EclipticSeasonsApi.getInstance()` — `getSolarTerm/getSeason/getSubSeason`,
    `isDay/isNight/getNightTime`, plus its own weather/humidity surface (`isRainAt/isSnowAt/isThunderAt`,
    `getBaseHumidity/getAdjustedHumidity`, greenhouse providers in `SolarDataManager`). **v1 boundary:**
    ES keeps owning *presented* weather (vanilla rain/snow events, snow cover, crops); our `q_r`/`q_c`
    are the prognostic layer underneath. Whether our precip eventually *drives* ES/vanilla weather
    events is a post-v1 question — don't fight it for v1.
