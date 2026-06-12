package ink.astrius.driftwardclimate.core.field

import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Sub-grid point sources (spec 05 §2.3): block-accurate emitters below grid
 * resolution — campfires, ice, gas leaks. NOT on the grid: a per-chunk source
 * index queried at read time with a radial falloff kernel. The radius is
 * independent of the 16-block cell size.
 *
 * `nearField(pos) = Σ strength · w(d/R)` over sources with d < R, where
 * `w(q) = (1−q²)²` — Wendland-style: C1 at the cutoff, matching the engine's
 * C1 sampling discipline (no kink as you cross a source's radius).
 *
 * Maintained by block-change events adapter-side; thread-safe for concurrent
 * readers with infrequent writers (copy-on-write per bucket).
 */
class NearFieldIndex {

    class PointSource(
        val x: Double,
        val y: Double,
        val z: Double,
        /** Peak contribution at d = 0; negative for sinks (ice). */
        val strength: Float,
        /** Cutoff radius R in blocks. */
        val radius: Float,
    ) {
        init {
            require(radius > 0f) { "radius must be > 0" }
        }
    }

    /** chunk key → immutable source array (copy-on-write). */
    private val buckets = java.util.concurrent.ConcurrentHashMap<Long, Array<PointSource>>()

    @Volatile
    var maxRadius: Float = 0f
        private set

    private fun key(chunkX: Int, chunkZ: Int): Long =
        (chunkX.toLong() shl 32) or (chunkZ.toLong() and 0xffffffffL)

    private fun chunkOf(coord: Double): Int = Math.floorDiv(coord.toInt(), 16)

    fun add(source: PointSource) {
        val k = key(chunkOf(source.x), chunkOf(source.z))
        buckets.compute(k) { _, old ->
            if (old == null) arrayOf(source) else old + source
        }
        if (source.radius > maxRadius) maxRadius = source.radius
    }

    /** Remove every source at exactly this block position. True if any removed. */
    fun removeAt(x: Double, y: Double, z: Double): Boolean {
        val k = key(chunkOf(x), chunkOf(z))
        var removed = false
        buckets.computeIfPresent(k) { _, old ->
            val kept = old.filterNot { it.x == x && it.y == y && it.z == z }
            removed = kept.size != old.size
            if (kept.isEmpty()) null else kept.toTypedArray()
        }
        return removed
    }

    fun clearChunk(chunkX: Int, chunkZ: Int) {
        buckets.remove(key(chunkX, chunkZ))
    }

    val sourceCount: Int
        get() = buckets.values.sumOf { it.size }

    /**
     * The query-time kernel: Σ strength·(1−(d/R)²)² over in-range sources,
     * gathering only the chunks a [maxRadius] ball can reach.
     */
    fun sum(px: Double, py: Double, pz: Double): Float {
        val r = maxRadius
        if (r <= 0f || buckets.isEmpty()) return 0f
        val reach = ceil(r / 16.0).toInt()
        val cx = chunkOf(px)
        val cz = chunkOf(pz)
        var total = 0f
        for (dz in -reach..reach) {
            for (dx in -reach..reach) {
                val sources = buckets[key(cx + dx, cz + dz)] ?: continue
                for (s in sources) {
                    val ddx = px - s.x
                    val ddy = py - s.y
                    val ddz = pz - s.z
                    val d2 = ddx * ddx + ddy * ddy + ddz * ddz
                    val r2 = s.radius.toDouble() * s.radius
                    if (d2 >= r2) continue
                    val q = sqrt(d2 / r2)
                    val w = (1.0 - q * q)
                    total += (s.strength * (w * w)).toFloat()
                }
            }
        }
        return total
    }
}
