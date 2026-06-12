package ink.astrius.driftwardclimate.core.model

import ink.astrius.driftwardclimate.core.api.BaselinePort
import ink.astrius.driftwardclimate.core.api.ClockPort
import ink.astrius.driftwardclimate.core.api.SeasonPort
import ink.astrius.driftwardclimate.core.config.ClimateConfig
import kotlin.math.abs
import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Test stubs for the D14 ports — flat world, fixed season/time. */
class FixedBaselinePort(
    private val thetaK: Float = 288.15f,
    private val qv: Float = 0.008f,
    private val sea: Int = 63,
) : BaselinePort {
    override fun thetaTarget(cellX: Int, cellZ: Int): Float = thetaK
    override fun humidityTarget(cellX: Int, cellZ: Int): Float = qv
    override fun seaLevel(): Int = sea
}

class FixedSeason(private val phase: Float) : SeasonPort {
    override fun seasonPhase(): Float = phase
}

class FixedClock(private val ticks: Long) : ClockPort {
    override fun worldTimeTicks(): Long = ticks
}

class BaselineTest {

    private fun baseline(
        seasonPhase: Float = 0f,
        ticks: Long = 8000L,
        cfg: ClimateConfig = ClimateConfig(),
        port: BaselinePort = FixedBaselinePort(),
    ) = Baseline(port, FixedSeason(seasonPhase), FixedClock(ticks), cfg)

    // ── the Sable calibration constraint (spec 02 §B) ─────────────────────

    @Test
    fun `pressure0 reproduces Sable's curve exactly below the fade zone`() {
        val b = baseline()
        var y = 63f
        while (y < 255f) {
            val sable = exp(-0.004f * (y - 63f))
            assertEquals(sable, b.pressure0(y), 1e-5f, "at y=$y")
            y += 7f
        }
    }

    @Test
    fun `pressure0 is 1 at sea level and clamps at 1,5 deep below`() {
        val b = baseline()
        assertEquals(1f, b.pressure0(63f), 1e-5f)
        assertEquals(1.5f, b.pressure0(-64f), 1e-5f, "deep cave should hit Sable's 1.5 clamp")
    }

    @Test
    fun `pressure0 fades smoothly to exactly 0 at build height`() {
        val b = baseline()
        assertEquals(0f, b.pressure0(320f), 1e-6f)
        assertEquals(0f, b.pressure0(400f), 1e-6f)
        // strictly monotone decreasing through the fade zone
        var prev = b.pressure0(250f)
        var y = 252f
        while (y <= 320f) {
            val p = b.pressure0(y)
            assertTrue(p <= prev + 1e-6f, "pressure must not rise at y=$y")
            prev = p
            y += 2f
        }
        // C1 at the fade start: no slope jump (smoothstep has zero derivative there)
        val d = 0.25f
        val slopeBelow = (b.pressure0(256f) - b.pressure0(256f - d)) / d
        val slopeAbove = (b.pressure0(256f + d) - b.pressure0(256f)) / d
        assertTrue(abs(slopeAbove - slopeBelow) < 0.002f, "fade-start kink: $slopeBelow vs $slopeAbove")
    }

    @Test
    fun `scale height is about 250 blocks (checked below the fade zone)`() {
        val b = baseline()
        // the e-folding altitude itself (y=313) sits inside the build-height
        // fade, so verify the scale height via a ratio below the fade:
        // p(y+100)/p(y) = e^(−100/250) = e^−0.4
        val ratio = b.pressure0(180f) / b.pressure0(80f)
        assertEquals(exp(-0.4f), ratio, 1e-4f)
    }

    // ── thermal baseline ───────────────────────────────────────────────────

    @Test
    fun `season offset - summer warm, winter cold, equinoxes neutral`() {
        assertEquals(0f, baseline(seasonPhase = 0f).seasonOffsetK(), 1e-3f)
        assertEquals(8f, baseline(seasonPhase = 0.25f).seasonOffsetK(), 1e-3f)
        assertEquals(0f, baseline(seasonPhase = 0.5f).seasonOffsetK(), 0.01f)
        assertEquals(-8f, baseline(seasonPhase = 0.75f).seasonOffsetK(), 1e-3f)
    }

    @Test
    fun `diurnal offset - peak at 14h, trough 12000 ticks later`() {
        assertEquals(6f, baseline(ticks = 8000).diurnalOffsetK(), 1e-3f)
        assertEquals(-6f, baseline(ticks = 20000).diurnalOffsetK(), 1e-3f)
        // negative world times must not break the modulo
        assertEquals(6f, baseline(ticks = 8000 - 48000).diurnalOffsetK(), 1e-3f)
    }

    @Test
    fun `theta0 composes target plus season plus diurnal`() {
        val b = baseline(seasonPhase = 0.25f, ticks = 8000)
        assertEquals(288.15f + 8f + 6f, b.theta0(0, 0), 1e-2f)
    }

    // ── the emergent lapse: the feature that started the whole mod ────────

    @Test
    fun `temperature falls with altitude at constant theta`() {
        val b = baseline()
        val tSea = b.temperature0(0, 0, 63f)
        val tPeak = b.temperature0(0, 0, 200f)
        assertTrue(tPeak < tSea - 20f, "jagged_peaks must be markedly colder: sea=$tSea peak=$tPeak")
        // and continuous in between (no biome steps — sampled densely)
        var prev = tSea
        var y = 64f
        while (y <= 200f) {
            val t = b.temperature0(0, 0, y)
            assertTrue(t < prev && prev - t < 1f, "lapse must be gradual at y=$y (Δ=${prev - t})")
            prev = t
            y += 1f
        }
    }

    @Test
    fun `lapseExponentScale below 1 gives a gentler lapse`() {
        val adiabatic = baseline()
        val stable = baseline(cfg = ClimateConfig(lapseExponentScale = 0.5f))
        val dAd = adiabatic.temperature0(0, 0, 63f) - adiabatic.temperature0(0, 0, 200f)
        val dSt = stable.temperature0(0, 0, 63f) - stable.temperature0(0, 0, 200f)
        assertTrue(dSt < dAd * 0.6f, "half exponent should roughly halve the lapse: $dSt vs $dAd")
    }

    @Test
    fun `density - cold column is denser than warm column at the same altitude`() {
        val warmPort = FixedBaselinePort(thetaK = 303.15f)
        val coldPort = FixedBaselinePort(thetaK = 263.15f)
        val warm = baseline(port = warmPort).densityNorm0(0, 0, 100f)
        val cold = baseline(port = coldPort).densityNorm0(0, 0, 100f)
        assertTrue(cold > warm, "cold air denser → more balloon lift (D6's replacement mechanism)")
    }

    @Test
    fun `relative humidity rises with altitude at constant qv - orographic cloud premise`() {
        val b = baseline()
        val rhSea = b.relativeHumidity0(0, 0, 63f)
        val rhPeak = b.relativeHumidity0(0, 0, 220f)
        assertTrue(rhPeak > rhSea, "same vapour, colder+thinner air ⇒ closer to saturation")
    }

    @Test
    fun `celsius view matches the kelvin view`() {
        val b = baseline()
        assertEquals(b.temperature0(0, 0, 80f) - 273.15f, b.temperature0C(0, 0, 80f), 1e-4f)
    }

    @Test
    fun `determinism - identical inputs give identical outputs`() {
        val a = baseline(seasonPhase = 0.3f, ticks = 5000)
        val b = baseline(seasonPhase = 0.3f, ticks = 5000)
        assertEquals(a.theta0(7, -3), b.theta0(7, -3))
        assertEquals(a.pressure0(150f), b.pressure0(150f))
    }
}
