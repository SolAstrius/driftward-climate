package ink.astrius.driftwardclimate.core.api

/**
 * D14 SPI — everything the sim needs from a "world", spec 01 §11.
 *
 * Core never touches Minecraft types: positions are block-scale doubles/ints
 * (1 block = 1 m), units are SI. Implemented by the NeoForge adapter in
 * production and by flat/synthetic stubs in tests.
 */

/** Per-column terrain inputs, sampled at cell resolution (h = 16 blocks). */
interface TerrainPort {
    /** Surface height in blocks at the cell centre (MOTION_BLOCKING_NO_LEAVES side). */
    fun surfaceY(cellX: Int, cellZ: Int): Float

    /** Solar admittance 0..1 from skylight — passes glass (spec 01 §7). */
    fun solar(cellX: Int, cellZ: Int): Float

    /** Sky occlusion 0..1 from the heightmap — glass IS a roof (spec 01 §7). */
    fun shelter(cellX: Int, cellZ: Int): Float

    /** Surface albedo 0..1. */
    fun albedo(cellX: Int, cellZ: Int): Float

    /** Surface roughness length, m (drag on the lowest layer). */
    fun roughness(cellX: Int, cellZ: Int): Float
}

/** T0 baseline targets per cell (biome-derived; season/diurnal applied analytically). */
interface BaselinePort {
    /** Potential-temperature target, Kelvin. */
    fun thetaTarget(cellX: Int, cellZ: Int): Float

    /** Specific-humidity target, kg/kg. */
    fun humidityTarget(cellX: Int, cellZ: Int): Float

    /** Sea level in blocks — anchors the pressure base state (02 §B calibration). */
    fun seaLevel(): Int
}

/**
 * Sun geometry. MUST be backed by the host's own sun (the vanilla accessors,
 * which Ecliptic Seasons overwrites — spec 01 §7): core never computes a sun.
 */
interface SolarPort {
    /** cos(solar zenith), clamped to 0..1 (0 = sun below horizon). */
    fun cosZenith(): Float

    /** Fraction of the current day-cycle that is daylight, 0..1. */
    fun daylightFraction(): Float
}

/** Season state (read from the host calendar — we own the mapping, not the calendar). */
interface SeasonPort {
    /** Season phase 0..1, 0 = spring equinox. */
    fun seasonPhase(): Float
}

/** World clock. */
interface ClockPort {
    /** World time in ticks (20 ticks = 1 s). */
    fun worldTimeTicks(): Long
}
