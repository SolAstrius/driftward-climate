package ink.astrius.driftwardclimate.core.field

/**
 * Field registry — the D12 generic substrate (spec 05 §1, §3.5).
 *
 * A field is one scalar plane per layer, stored SoA as a flat [FloatArray] in
 * [FieldRegion]. Vector fields are registered as separate component scalars
 * (e.g. "wind_u" + "wind_v") and passed as array pairs to [Operators].
 */
class FieldDef(
    val name: String,
    /** Advected by the wind each solver step (semi-Lagrangian). */
    val advected: Boolean = false,
    /** Diffusivity κ, m²/s; 0 = not diffused. */
    val diffusivity: Float = 0f,
    /** Included in T1 persistence payloads. */
    val persistent: Boolean = false,
)

/** Stable, allocation-free handle into a [FieldRegistry]. */
@JvmInline
value class FieldHandle(val ordinal: Int)

/**
 * Registration phase happens before any [FieldRegion] is allocated; the first
 * region allocation freezes the registry (storage shape is fixed per region).
 */
class FieldRegistry {
    private val defs = ArrayList<FieldDef>()
    private val byName = HashMap<String, FieldHandle>()

    @Volatile
    var frozen: Boolean = false
        private set

    val size: Int get() = defs.size

    fun register(def: FieldDef): FieldHandle {
        check(!frozen) { "registry frozen — register fields before creating regions" }
        require(def.name !in byName) { "duplicate field '${def.name}'" }
        val handle = FieldHandle(defs.size)
        defs.add(def)
        byName[def.name] = handle
        return handle
    }

    fun handle(name: String): FieldHandle =
        byName[name] ?: throw NoSuchElementException("no field '$name'")

    fun handleOrNull(name: String): FieldHandle? = byName[name]

    fun def(handle: FieldHandle): FieldDef = defs[handle.ordinal]

    fun freeze() {
        frozen = true
    }

    /** All defs in ordinal order (for solver iteration). */
    fun defs(): List<FieldDef> = defs
}
