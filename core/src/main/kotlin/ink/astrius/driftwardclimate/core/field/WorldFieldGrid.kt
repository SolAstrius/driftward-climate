package ink.astrius.driftwardclimate.core.field

/**
 * The field-engine façade (D12, spec 05 §1): named-field registry + region
 * storage + near-field indices behind one object. Climate registers its
 * prognostic set here and drives the solver; other consumers (radio, sound,
 * pollution…) register their own fields and/or read snapshots.
 *
 * v1 manages a single region (the T2 window); tier-aware fallthrough
 * (T1/T0 reconstruction, spec 06 §3) plugs in above this seam.
 */
class WorldFieldGrid {

    val registry = FieldRegistry()

    @Volatile
    var region: FieldRegion? = null
        private set

    private val nearFields = java.util.concurrent.ConcurrentHashMap<Int, NearFieldIndex>()

    fun registerField(def: FieldDef): FieldHandle = registry.register(def)

    /** Allocate (or replace, e.g. on window move) the active region. */
    fun createRegion(geom: GridGeometry): FieldRegion {
        val r = FieldRegion(geom, registry)
        region = r
        return r
    }

    /** Per-field sub-grid point-source index (spec 05 §2.3), created lazily. */
    fun nearField(handle: FieldHandle): NearFieldIndex =
        nearFields.computeIfAbsent(handle.ordinal) { NearFieldIndex() }

    /**
     * Resolve a field at world block coords (spec 05 §2.3):
     * grid sample (C1 by default — safe for differencing consumers) plus the
     * near-field kernel. Returns null when no region covers the position
     * (caller falls through to T1/T0 reconstruction).
     */
    fun resolve(handle: FieldHandle, wx: Double, wy: Double, wz: Double, layer: Int = 0): Float? {
        val r = region ?: return null
        val snap = r.snapshot
        val fx = snap.geom.toCellX(wx)
        val fz = snap.geom.toCellZ(wz)
        if (!snap.geom.contains(fx, fz)) return null
        val grid = snap.sampleC1(handle, fx, fz, layer)
        val near = nearFields[handle.ordinal]?.sum(wx, wy, wz) ?: 0f
        return grid + near
    }
}
