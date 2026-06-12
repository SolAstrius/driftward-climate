package ink.astrius.driftwardclimate.core.field

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The D12 façade: registry rules, region lifecycle, resolve = grid + near-field. */
class WorldFieldGridTest {

    @Test
    fun `register then create region then resolve`() {
        val grid = WorldFieldGrid()
        val t = grid.registerField(FieldDef("theta", advected = true))
        val region = grid.createRegion(GridGeometry(nx = 8, nz = 8, layers = 2, h = 16f))

        region.back(t).fill(280f)
        region.publish()

        val v = grid.resolve(t, wx = 64.0, wy = 70.0, wz = 64.0)
        assertEquals(280f, v!!, 1e-3f)
    }

    @Test
    fun `registry freezes on region creation`() {
        val grid = WorldFieldGrid()
        grid.registerField(FieldDef("a"))
        grid.createRegion(GridGeometry(4, 4, 1))
        assertFailsWith<IllegalStateException> { grid.registerField(FieldDef("b")) }
    }

    @Test
    fun `duplicate field names are rejected`() {
        val grid = WorldFieldGrid()
        grid.registerField(FieldDef("x"))
        assertFailsWith<IllegalArgumentException> { grid.registerField(FieldDef("x")) }
    }

    @Test
    fun `resolve returns null outside region coverage`() {
        val grid = WorldFieldGrid()
        val t = grid.registerField(FieldDef("t"))
        grid.createRegion(GridGeometry(nx = 4, nz = 4, layers = 1, h = 16f))
        assertNull(grid.resolve(t, wx = 10_000.0, wy = 64.0, wz = 0.0))
    }

    @Test
    fun `resolve returns null with no region`() {
        val grid = WorldFieldGrid()
        val t = grid.registerField(FieldDef("t"))
        assertNull(grid.resolve(t, 0.0, 0.0, 0.0))
    }

    @Test
    fun `resolve adds the near-field kernel on top of the grid (feltTemp)`() {
        val grid = WorldFieldGrid()
        val t = grid.registerField(FieldDef("t"))
        val region = grid.createRegion(GridGeometry(nx = 8, nz = 8, layers = 1, h = 16f))
        region.back(t).fill(0f)
        region.publish()

        // campfire at block (40, 64, 40), radius 5, +9 at the flame
        grid.nearField(t).add(NearFieldIndex.PointSource(40.0, 64.0, 40.0, strength = 9f, radius = 5f))

        val atFire = grid.resolve(t, 40.0, 64.0, 40.0)!!
        val nearby = grid.resolve(t, 42.0, 64.0, 40.0)!!
        val faraway = grid.resolve(t, 80.0, 64.0, 80.0)!!
        assertEquals(9f, atFire, 1e-3f)
        assertTrue(nearby in 0.5f..8.9f, "expected partial warmth, got $nearby")
        assertEquals(0f, faraway, 1e-4f)
    }

    @Test
    fun `handles by name`() {
        val grid = WorldFieldGrid()
        val t = grid.registerField(FieldDef("theta"))
        assertEquals(t, grid.registry.handle("theta"))
        assertNull(grid.registry.handleOrNull("nope"))
    }
}
