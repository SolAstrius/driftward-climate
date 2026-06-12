package ink.astrius.driftwardclimate.core.field

import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.infra.Blackhole

/**
 * Field-engine kernel throughput (D10 optimization posture).
 *
 * Sizing context: a generous T2 window is ~64×64 chunks; 128×128 is the
 * stress case. ×4 layers. One solver step ≈ advect(7 fields) + diffuse(3)
 * + project(1) per cadence (~20–60 ticks), so kernel cost × ~10 ≈ step cost.
 */
@State(Scope.Benchmark)
open class FieldEngineBench {

    @Param("64", "128")
    var n = 0

    private lateinit var geom: GridGeometry
    private lateinit var s: FloatArray
    private lateinit var u: FloatArray
    private lateinit var v: FloatArray
    private lateinit var w: FloatArray
    private lateinit var dst: FloatArray
    private lateinit var dst2: FloatArray
    private lateinit var dst3: FloatArray
    private lateinit var planeA: FloatArray
    private lateinit var planeB: FloatArray
    private lateinit var sor: SorPoisson
    private lateinit var fft: FftPoisson
    private lateinit var snapshotRegion: FieldRegion
    private lateinit var grad2: FloatArray

    @Setup
    fun setup() {
        geom = GridGeometry(nx = n, nz = n, layers = 4, h = 16f)
        val c = geom.cells
        s = FloatArray(c)
        u = FloatArray(c)
        v = FloatArray(c)
        w = FloatArray(c)
        dst = FloatArray(c)
        dst2 = FloatArray(c)
        dst3 = FloatArray(c)
        planeA = FloatArray(geom.planeSize)
        planeB = FloatArray(geom.planeSize)
        for (l in 0 until geom.layers) for (z in 0 until n) for (x in 0 until n) {
            val i = geom.idx(x, z, l)
            s[i] = (Math.sin(x * 0.21) * 12 + Math.cos(z * 0.17) * 9 + l).toFloat()
            u[i] = (Math.sin((x + z) * 0.1) * 6).toFloat()
            v[i] = (Math.cos((x - z) * 0.13) * 5).toFloat()
            w[i] = (Math.sin(z * 0.05) * 0.4).toFloat()
        }
        sor = SorPoisson(iterations = 200)
        fft = FftPoisson()
        // warm the FFT plan so plan creation isn't measured
        fft.solve(planeA.copyOf(), planeB, n, n, 16f)

        val reg = FieldRegistry()
        reg.register(FieldDef("p"))
        snapshotRegion = FieldRegion(geom, reg)
        s.copyInto(snapshotRegion.back(FieldHandle(0)))
        snapshotRegion.publish()
        grad2 = FloatArray(2)
    }

    // ── stencil kernels ────────────────────────────────────────────────────

    @Benchmark
    fun gradient(bh: Blackhole) {
        Operators.gradient(geom, s, dst, dst2)
        bh.consume(dst)
    }

    @Benchmark
    fun laplacian(bh: Blackhole) {
        Operators.laplacian(geom, s, dst)
        bh.consume(dst)
    }

    @Benchmark
    fun curl(bh: Blackhole) {
        Operators.curl(geom, u, v, dst)
        bh.consume(dst)
    }

    @Benchmark
    fun gradient3(bh: Blackhole) {
        Operators.gradient3(geom, s, dst, dst2, dst3)
        bh.consume(dst3)
    }

    @Benchmark
    fun okuboWeiss(bh: Blackhole) {
        VectorCalculus.okuboWeiss(geom, u, v, dst)
        bh.consume(dst)
    }

    // ── advection ──────────────────────────────────────────────────────────

    @Benchmark
    fun advect2D(bh: Blackhole) {
        Operators.advect(geom, s, u, v, 30f, dst)
        bh.consume(dst)
    }

    @Benchmark
    fun advect3D(bh: Blackhole) {
        Operators.advect3(geom, s, u, v, w, 30f, dst)
        bh.consume(dst)
    }

    @Benchmark
    fun diffuse(bh: Blackhole) {
        Operators.diffuse(geom, s, 40f, 1.5f, dst)
        bh.consume(dst)
    }

    // ── projection backends (OQ2: FFT default, SOR reference) ─────────────

    @Benchmark
    fun projectFft(bh: Blackhole) {
        u.copyInto(dst)
        v.copyInto(dst2)
        Projection.project(geom, dst, dst2, fft, planeA, planeB)
        bh.consume(dst)
    }

    @Benchmark
    fun projectSor200(bh: Blackhole) {
        u.copyInto(dst)
        v.copyInto(dst2)
        Projection.project(geom, dst, dst2, sor, planeA, planeB)
        bh.consume(dst)
    }

    // ── query path (what Thermoo/Sable hit at read time) ──────────────────

    @Benchmark
    fun sampleC0(bh: Blackhole) {
        val snap = snapshotRegion.snapshot
        var acc = 0f
        var fx = 0.5f
        while (fx < n - 1.5f) {
            acc += snap.sampleC0(FieldHandle(0), fx, fx * 0.7f, 1)
            fx += 0.37f
        }
        bh.consume(acc)
    }

    @Benchmark
    fun sampleC1(bh: Blackhole) {
        val snap = snapshotRegion.snapshot
        var acc = 0f
        var fx = 0.5f
        while (fx < n - 1.5f) {
            acc += snap.sampleC1(FieldHandle(0), fx, fx * 0.7f, 1)
            fx += 0.37f
        }
        bh.consume(acc)
    }

    @Benchmark
    fun sampleGradC1(bh: Blackhole) {
        val snap = snapshotRegion.snapshot
        var acc = 0f
        var fx = 0.5f
        while (fx < n - 1.5f) {
            snap.sampleGradC1(FieldHandle(0), fx, fx * 0.7f, 1, grad2)
            acc += grad2[0] + grad2[1]
            fx += 0.37f
        }
        bh.consume(acc)
    }
}
