# Driftward Climate

Standalone Kotlin **NeoForge 1.21.1** mod: a **server-authoritative atmospheric simulation**
(temperature, pressure, humidity, wind, clouds) discretised on the **chunk lattice** with a prognostic
stable-fluids solver. It feeds **Thermoo**'s ambient environment (so Scorchful/Frostiful work unchanged)
and answers **Sable**'s air-pressure queries so Aeronautics buoyancy is climate-aware.

**Status: scaffold only — no implementation code yet.**

## Spec

The full design lives in [`docs/`](docs/):
- `00-motivation-and-decisions.md` — why this exists, the journey, locked decisions D1–D13, open questions
- `01-climate-mod-spec.md` — the simulation (lattice, fields, operators, solver, clouds, perf, validation)
- `02-integration-spec.md` — Thermoo + Sable + Aeronautics wiring (verified signatures)
- `03-affected-components-and-loc.md` — every file touched, with paths + LoC
- `04-shared-field-substrate-and-radio.md` — shared field substrate; amateur radio as second reader
- `05-field-contract-and-consumers.md` — generic field-engine contract, RAD/gas/near-field, all consumers
- `06-lod-and-world-scaling.md` — T0/T1/T2 LoD nesting, rapid movement, persistence

## Building

JDK 21. Integration jars are compile-only and gitignored — present in `libs/`
(`thermoo`, `sable`, `sable-companion`, `aeronautics`). Plugin/dependency versions in `build.gradle`
are first-pass and **must be pinned before the first real build** (see spec `03`, "Build/versions TODO").

```sh
./gradlew build
```
