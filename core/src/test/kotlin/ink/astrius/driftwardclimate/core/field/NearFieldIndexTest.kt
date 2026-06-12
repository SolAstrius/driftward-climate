package ink.astrius.driftwardclimate.core.field

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Spec 05 §2.3 — sub-grid point sources: kernel shape, cutoff, chunk gather. */
class NearFieldIndexTest {

    @Test
    fun `peak at source, zero at and beyond radius, C1 at the cutoff`() {
        val idx = NearFieldIndex()
        idx.add(NearFieldIndex.PointSource(100.0, 64.0, 100.0, strength = 10f, radius = 5f))

        assertEquals(10f, idx.sum(100.0, 64.0, 100.0), 1e-4f)
        assertEquals(0f, idx.sum(105.0, 64.0, 100.0), 1e-6f)
        assertEquals(0f, idx.sum(110.0, 64.0, 100.0), 1e-6f)

        // monotone decay
        var prev = Float.MAX_VALUE
        for (step in 0..10) {
            val v = idx.sum(100.0 + step * 0.5, 64.0, 100.0)
            assertTrue(v <= prev + 1e-6f, "non-monotone at $step")
            prev = v
        }

        // C1 cutoff: slope just inside the radius is ~0 (Wendland kernel)
        val just = idx.sum(104.9, 64.0, 100.0)
        assertTrue(just < 0.02f, "kernel should approach 0 smoothly, got $just")
    }

    @Test
    fun `sources in neighbouring chunks are gathered`() {
        val idx = NearFieldIndex()
        // source at the very edge of chunk (6,6): block 111; query from chunk (7,*)
        idx.add(NearFieldIndex.PointSource(111.0, 64.0, 100.0, strength = 8f, radius = 6f))
        val v = idx.sum(113.0, 64.0, 100.0) // 2 blocks away, different chunk
        assertTrue(v > 4f, "cross-chunk gather failed: $v")
    }

    @Test
    fun `negative sources cool`() {
        val idx = NearFieldIndex()
        idx.add(NearFieldIndex.PointSource(0.0, 64.0, 0.0, strength = -6f, radius = 4f))
        assertTrue(idx.sum(1.0, 64.0, 0.0) < 0f)
    }

    @Test
    fun `superposition of two sources`() {
        val idx = NearFieldIndex()
        idx.add(NearFieldIndex.PointSource(10.0, 64.0, 10.0, strength = 5f, radius = 8f))
        idx.add(NearFieldIndex.PointSource(14.0, 64.0, 10.0, strength = 5f, radius = 8f))
        val mid = idx.sum(12.0, 64.0, 10.0)
        val single = NearFieldIndex().apply {
            add(NearFieldIndex.PointSource(10.0, 64.0, 10.0, strength = 5f, radius = 8f))
        }.sum(12.0, 64.0, 10.0)
        assertEquals(2f * single, mid, 1e-4f)
    }

    @Test
    fun `vertical distance counts`() {
        val idx = NearFieldIndex()
        idx.add(NearFieldIndex.PointSource(0.0, 64.0, 0.0, strength = 10f, radius = 4f))
        assertEquals(0f, idx.sum(0.0, 70.0, 0.0), 1e-6f)
        assertTrue(idx.sum(0.0, 65.0, 0.0) > 0f)
    }

    @Test
    fun `removeAt removes exactly the targeted source`() {
        val idx = NearFieldIndex()
        idx.add(NearFieldIndex.PointSource(5.0, 64.0, 5.0, strength = 3f, radius = 4f))
        idx.add(NearFieldIndex.PointSource(6.0, 64.0, 5.0, strength = 3f, radius = 4f))
        assertEquals(2, idx.sourceCount)
        assertTrue(idx.removeAt(5.0, 64.0, 5.0))
        assertEquals(1, idx.sourceCount)
        assertTrue(!idx.removeAt(5.0, 64.0, 5.0))
        assertTrue(abs(idx.sum(6.0, 64.0, 5.0) - 3f) < 1e-4f)
    }

    @Test
    fun `empty index costs nothing and returns zero`() {
        val idx = NearFieldIndex()
        assertEquals(0f, idx.sum(0.0, 0.0, 0.0))
    }
}
