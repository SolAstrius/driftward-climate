package ink.astrius.driftwardclimate.core.field

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Spec 01 §12 "interpolation order" — the border-descent regression test class.
 * C0 (bilinear) must be value-continuous; C1 (Catmull–Rom) must ALSO have a
 * continuous first difference across cell boundaries and reproduce linear
 * ramps exactly (a uniform pressure gradient must read back uniform).
 */
class SamplingTest {

    private fun region(f: (x: Int, z: Int) -> Float): FieldRegion {
        val reg = FieldRegistry()
        val h = reg.register(FieldDef("t"))
        val geom = GridGeometry(nx = 16, nz = 16, layers = 1, h = 16f)
        val region = FieldRegion(geom, reg)
        val a = region.back(h)
        for (z in 0 until geom.nz) for (x in 0 until geom.nx) a[geom.idx(x, z, 0)] = f(x, z)
        region.publish()
        return region
    }

    private val handle = FieldHandle(0)

    @Test
    fun `C1 sampling reproduces a linear ramp exactly`() {
        val r = region { x, z -> 3f * x + 2f * z + 5f }
        val snap = r.snapshot
        var fx = 1.0f
        while (fx < 14f) {
            val v = snap.sampleC1(handle, fx, 7.3f, 0)
            assertEquals(3f * fx + 2f * 7.3f + 5f, v, 1e-3f, "at fx=$fx")
            fx += 0.137f
        }
    }

    @Test
    fun `C0 value is continuous across a cell boundary`() {
        val r = region { x, z -> ((x * x * 13 + z * 7) % 23).toFloat() }
        val snap = r.snapshot
        val eps = 1e-4f
        for (boundary in 2..12) {
            val below = snap.sampleC0(handle, boundary - eps, 5f, 0)
            val above = snap.sampleC0(handle, boundary + eps, 5f, 0)
            assertTrue(abs(below - above) < 0.01f, "C0 value jump at x=$boundary")
        }
    }

    @Test
    fun `C1 first difference is continuous across cell boundaries, C0's is not`() {
        // a curved field: bilinear has gradient kinks at every node, Catmull-Rom must not
        val r = region { x, z -> (x * x).toFloat() + 0.5f * (z * z).toFloat() }
        val snap = r.snapshot
        // probe spacing large enough that float32 cancellation noise (~1e-3 on
        // values ~200) stays well below the slope-jump signal we're detecting
        val d = 0.05f

        // kink detector = right-slope minus left-slope AT the node:
        // (f(b+d) − 2f(b) + f(b−d)) / d ≈ f''·d for a C1 interpolant (~0.1 here)
        // but ≈ the slope DISCONTINUITY (2.0 on x²) for bilinear.
        fun kinkC1(fx: Float) =
            (snap.sampleC1(handle, fx + d, 6f, 0) - 2 * snap.sampleC1(handle, fx, 6f, 0) +
                snap.sampleC1(handle, fx - d, 6f, 0)) / d

        fun kinkC0(fx: Float) =
            (snap.sampleC0(handle, fx + d, 6f, 0) - 2 * snap.sampleC0(handle, fx, 6f, 0) +
                snap.sampleC0(handle, fx - d, 6f, 0)) / d

        var worstC1 = 0f
        var worstC0 = 0f
        for (boundary in 3..12) {
            val b = boundary.toFloat()
            worstC1 = maxOf(worstC1, abs(kinkC1(b)))
            worstC0 = maxOf(worstC0, abs(kinkC0(b)))
        }
        // bilinear's slope jumps by exactly Δ(2x)=2.0 per node on x²; C1's true
        // jump is 0 (+ float probe noise ≪ 0.3)
        assertTrue(worstC1 < 0.3f, "C1 slope jump $worstC1 — pressure gradient would kink (the balloon bug)")
        assertTrue(worstC0 > 1.5f, "test should detect bilinear's kink (got $worstC0) — else it proves nothing")
    }

    @Test
    fun `analytic spline gradient is exact on a linear ramp`() {
        // f = 3x + 2z in cell units → world gradient (3/h, 2/h) everywhere,
        // including across cell boundaries (what Sable's ∇P needs).
        val r = region { x, z -> 3f * x + 2f * z }
        val snap = r.snapshot
        val out = FloatArray(2)
        var fx = 1.2f
        while (fx < 13.5f) {
            snap.sampleGradC1(handle, fx, 6.7f, 0, out)
            assertEquals(3f / 16f, out[0], 1e-4f, "∂/∂x at fx=$fx")
            assertEquals(2f / 16f, out[1], 1e-4f, "∂/∂z at fx=$fx")
            fx += 0.31f
        }
    }

    @Test
    fun `analytic spline gradient matches finite differences of the spline`() {
        val r = region { x, z -> (x * x).toFloat() - (z * x) * 0.5f + z }
        val snap = r.snapshot
        val out = FloatArray(2)
        val d = 0.02f
        for (p in listOf(3.3f to 4.1f, 7.9f to 9.5f, 11.01f to 2.99f)) {
            val (fx, fz) = p
            snap.sampleGradC1(handle, fx, fz, 0, out)
            val fdX = (snap.sampleC1(handle, fx + d, fz, 0) - snap.sampleC1(handle, fx - d, fz, 0)) / (2 * d) / 16f
            val fdZ = (snap.sampleC1(handle, fx, fz + d, 0) - snap.sampleC1(handle, fx, fz - d, 0)) / (2 * d) / 16f
            assertEquals(fdX, out[0], 0.02f, "∂/∂x at $p")
            assertEquals(fdZ, out[1], 0.02f, "∂/∂z at $p")
        }
    }

    @Test
    fun `analytic spline gradient is continuous across cell boundaries`() {
        val r = region { x, z -> (x * x).toFloat() + 0.5f * (z * z).toFloat() }
        val snap = r.snapshot
        val out = FloatArray(2)
        val eps = 1e-3f
        for (boundary in 3..12) {
            val b = boundary.toFloat()
            snap.sampleGradC1(handle, b - eps, 6f, 0, out)
            val left = out[0]
            snap.sampleGradC1(handle, b + eps, 6f, 0, out)
            val right = out[0]
            assertTrue(abs(right - left) < 0.01f, "∇ jump ${abs(right - left)} at x=$boundary")
        }
    }

    @Test
    fun `sampling at cell centres returns stored values`() {
        val r = region { x, z -> (x * 100 + z).toFloat() }
        val snap = r.snapshot
        for (z in 1 until 15) for (x in 1 until 15) {
            assertEquals((x * 100 + z).toFloat(), snap.sampleC0(handle, x.toFloat(), z.toFloat(), 0), 1e-3f)
            assertEquals((x * 100 + z).toFloat(), snap.sampleC1(handle, x.toFloat(), z.toFloat(), 0), 1e-3f)
        }
    }

    @Test
    fun `out-of-range sampling clamps instead of exploding`() {
        val r = region { x, z -> (x + z).toFloat() }
        val snap = r.snapshot
        assertEquals(snap.at(handle, 0, 0, 0), snap.sampleC0(handle, -5f, -5f, 0), 1e-4f)
        assertEquals(snap.at(handle, 15, 15, 0), snap.sampleC1(handle, 99f, 99f, 0), 1e-4f)
    }

    @Test
    fun `double buffer - snapshot is stable while back is mutated, publish flips`() {
        val reg = FieldRegistry()
        val h = reg.register(FieldDef("p"))
        val geom = GridGeometry(4, 4, 1)
        val region = FieldRegion(geom, reg)

        region.back(h).fill(1f)
        region.publish()
        val gen1 = region.snapshot
        assertEquals(1f, gen1.at(h, 2, 2, 0))

        // mutate the new back — gen1 must not move
        region.back(h).fill(2f)
        assertEquals(1f, gen1.at(h, 2, 2, 0))
        assertEquals(1f, region.snapshot.at(h, 2, 2, 0))

        region.publish()
        assertEquals(2f, region.snapshot.at(h, 2, 2, 0))
        // gen1 now points at the write side — by contract readers re-fetch; the
        // reference itself stays valid (no UAF semantics on the JVM).
    }

    @Test
    fun `world to cell mapping respects origin and cell centres`() {
        val geom = GridGeometry(nx = 8, nz = 8, h = 16f, layers = 1, originCellX = 100, originCellZ = -20)
        // centre of world cell (100, -20) is block (100*16+8, -20*16+8)
        assertEquals(0f, geom.toCellX(100.0 * 16 + 8), 1e-4f)
        assertEquals(0f, geom.toCellZ(-20.0 * 16 + 8), 1e-4f)
        // one full cell over
        assertEquals(1f, geom.toCellX(101.0 * 16 + 8), 1e-4f)
    }
}
