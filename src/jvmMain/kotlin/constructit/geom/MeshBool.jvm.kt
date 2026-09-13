package constructit.geom

import constructit.l10n.Msg
import constructit.l10n.Msgs
import manifold3d.Manifold
import manifold3d.UIntVector
import manifold3d.manifold.MeshGL
import java.io.File

/**
 * **Manifold on the JVM** (OP-9) — the `manifold3d` JavaCPP binding, one jar with the C++ library inside.
 *
 * Nothing here decides *whether* a boolean is general: that is [Geom3.sameAxis]'s job, and the exact
 * prismatic path (OP-22) has already declined by the time this runs. This file is only the conversion
 * `Mesh3 ↔ MeshGL` plus the three calls.
 *
 * **Availability is discovered, not assumed.** The binding ships one jar per platform; on a platform with
 * no jar the classes are still there but the native library is not, so the probe below runs one trivial
 * boolean at class-init and turns any `UnsatisfiedLinkError`/`NoClassDefFoundError` into
 * `available = false`. The general-boolean path then refuses with a reason and heals if the model changes
 * (OP-3) — the same behaviour the browser has while the WASM module is still loading.
 *
 * **Two engines, one seam.** By default this is the clojars binding exactly as it always was. When the
 * system property `constructit.manifold.native` (or the environment variable
 * `CONSTRUCTIT_MANIFOLD_NATIVE`) names a directory holding `libconstructit_manifold`, that shim is loaded
 * instead — ConstructIt's own JNI binding over a Manifold 3 built from source, serial and speaking
 * `MeshGL64`. Nothing else in the engine can tell which one answered; [status] names it, and that is the
 * whole difference a caller sees. The default build stays a bare-checkout build with no native toolchain
 * in it (OP-31's (5q), prototype).
 *
 * **Precision, stated.** `MeshGL` carries vertex positions as **float32**, so a general boolean on the
 * *default* engine is accurate to about 1e-5 mm on drawing-sized coordinates — five orders of magnitude
 * coarser than the exact path's 1e-7 mm welding lattice, but two orders *finer* than the 0.02 mm chord
 * tolerance the tessellated operands already carry. That is the honest cost of the general path, and it is
 * one more reason the exact path stays exact. It is also measurable: a second boolean on a **curved** body
 * re-snaps its tessellation, so two disjoint roundings of one partial revolve take 1.69e-4 mm³ less in one
 * gesture than one at a time, while the same experiment on a **box**, whose coordinates survive float32
 * exactly, agrees to 2e-15. Under the native shim both routes agree to the last bit — `BooleanEngineTest`
 * measures it.
 *
 * *And double precision was **not** a one-line change here, which an earlier note in this file claimed.*
 * `MeshGL64` arrived with Manifold **3**; the clojars binding `org.clojars.cartesiantheatrics:manifold3d`
 * ships a jar with `MeshGL` and no `MeshGL64` in it, though the **native** inside that jar *is* a Manifold 3
 * — `libmanifold.so.3`, carrying `GetMeshGL64`/`ImportMeshGL64`, linked against `libtbb.so.12`. It is the
 * **Java surface** that is 2.x-shaped. So the JVM needed a binding whose Java side matches its native, and
 * `native/` is that binding: one C++ file, one `boolean` call, `MeshGL64` in and out, statically linked
 * against an upstream Manifold 3.5.1 — the **same version the browser runs** as npm `manifold-3d`, so the
 * two platforms compute with the same engine and the same precision. Its determinism is a build fact and
 * not a flag: Manifold 3 *removed* `ExecutionParams::deterministic`, and `MANIFOLD_PAR=OFF` — a serial
 * engine with nothing left to race — is what replaces it.
 */
actual object MeshBool {
    /**
     * **Which engine, decided once.** The system property `constructit.manifold.native`, or the environment
     * variable `CONSTRUCTIT_MANIFOLD_NATIVE`, names a directory holding [NativeManifold]'s library. Unset —
     * which is every ordinary build, CI included — means the clojars binding, exactly as before. Set means
     * the shim is **required**: a directory that does not hold a loadable library is a configuration
     * mistake a developer wants to hear about, so it becomes an ordinary unavailability with the operating
     * system's own reason in it (OP-3) rather than a silent fall-back to the engine they asked to replace.
     */
    private val nativeDir: String? =
        (System.getProperty("constructit.manifold.native") ?: System.getenv("CONSTRUCTIT_MANIFOLD_NATIVE"))?.takeIf { it.isNotBlank() }

    /** The shim's own version string once it has loaded and answered a real boolean, else null. */
    private var nativeVersion: String? = null

    /** The probe's failure, or null when the engine ran. Computed once, at class-init. */
    private val failure: String? =
        if (nativeDir != null) {
            loadNative(nativeDir)
        } else {
            try {
                preloadAssimp()
                // one real boolean, not just a class load: on a jar built for another platform the classes
                // resolve and only the first native call fails
                val cube = Manifold.Cube(manifold3d.linalg.DoubleVec3(1.0, 1.0, 1.0), false)
                val probe = cube.subtract(cube.translate(0.5, 0.5, 0.5))
                if (probe.isEmpty) Msgs.refusalMeshboolEngineReturnedNothingItsOwn().render() else null
            } catch (t: Throwable) {
                Msgs.refusalMeshboolNoUsableNativeManifoldLibrary(simpleName = t::class.simpleName ?: "", message = t.message ?: "").render()
            }
        }

    /**
     * Load the shim from [dir] and prove it works, or say why not.
     *
     * The proof is the same one the clojars path makes — one real boolean, a unit cube minus a copy of
     * itself shifted along the diagonal — because a library that loads and then cannot answer is worse than
     * one that never loaded. On success [nativeVersion] is filled in and this returns null.
     */
    private fun loadNative(dir: String): String? =
        try {
            val os = System.getProperty("os.name").lowercase()
            val ext =
                if (os.contains("mac")) {
                    ".dylib"
                } else if (os.contains("windows")) {
                    ".dll"
                } else {
                    ".so"
                }
            System.load(File(dir, "libconstructit_manifold$ext").absolutePath)
            val cube = unitCube(0.0)
            val out = NativeManifold.boolOp(2, cube.first, cube.second, unitCube(0.5).first, cube.second)
            val stage = (out[3] as IntArray)[0]
            if (stage != 0) {
                Msgs.refusalMeshboolNativeShimNamedButNot(dir = dir, message = Msgs.refusalMeshboolEngineReturnedNothingItsOwn().render()).render()
            } else {
                nativeVersion = NativeManifold.version()
                null
            }
        } catch (t: Throwable) {
            Msgs.refusalMeshboolNativeShimNamedButNot(dir = dir, message = "${t::class.simpleName}: ${t.message}").render()
        }

    /** The smoke test's operand: an axis-aligned unit cube at [at], as the shim's two flat arrays. */
    private fun unitCube(at: Double): Pair<DoubleArray, IntArray> {
        val v = DoubleArray(24)
        for (i in 0 until 8) {
            v[i * 3] = at + (if (i and 1 != 0) 1.0 else 0.0)
            v[i * 3 + 1] = at + (if (i and 2 != 0) 1.0 else 0.0)
            v[i * 3 + 2] = at + (if (i and 4 != 0) 1.0 else 0.0)
        }
        // the six faces as twelve triangles, each wound counter-clockwise seen from outside, in the order
        // z-, z+, y-, y+, x-, x+
        val t = intArrayOf(0, 2, 1, 1, 2, 3, 4, 5, 6, 5, 7, 6, 0, 1, 4, 1, 5, 4, 2, 6, 3, 3, 6, 7, 0, 4, 2, 2, 4, 6, 1, 3, 5, 3, 7, 5)
        return v to t
    }

    /**
     * Load `libassimp` (and the `libdraco` it needs) **before** Manifold's own library.
     *
     * Not optional and not a workaround for our code: `libmanifold.so` is built with Manifold's
     * assimp-based mesh IO — a path this engine never calls — and links against `libassimp.so.5` without
     * bundling it, so `dlopen` of the Manifold library fails outright on a machine that has no system
     * assimp. Loading a copy by absolute path first registers it under its own soname, which is what the
     * dynamic linker then resolves the dependency against; the copy comes from LWJGL's native bundle
     * (see `build.gradle.kts`) rather than from a system package, so the build stays self-contained.
     *
     * Best effort by design: a missing resource is skipped silently, and what a caller sees is the smoke
     * test's own failure with the real reason in it, not a guess made here.
     */
    private fun preloadAssimp() {
        val os = System.getProperty("os.name").lowercase()
        val (dir, names) =
            when {
                os.contains("linux") -> "linux/x64" to listOf("libdraco.so", "libassimp.so")
                os.contains("mac") -> "macos/x64" to listOf("libdraco.dylib", "libassimp.dylib")
                else -> return
            }
        for (name in names) {
            val stream = MeshBool::class.java.classLoader.getResourceAsStream("$dir/org/lwjgl/assimp/$name") ?: continue
            val file = File.createTempFile("constructit-", "-$name")
            file.deleteOnExit()
            stream.use { input -> file.outputStream().use { input.copyTo(it) } }
            try {
                System.load(file.absolutePath)
            } catch (t: UnsatisfiedLinkError) {
                // a library the platform will not take is no worse than one that was never there: the
                // smoke test below is what decides availability
            }
        }
    }

    actual val available: Boolean get() = failure == null

    /** Which engine answered, and how precisely — the one thing a caller can see the choice in. */
    actual val status: String
        get() =
            failure
                ?: nativeVersion?.let { Msgs.refusalMeshboolManifoldNativeShimDoubleMeshes(version = it).render() }
                ?: Msgs.refusalMeshboolManifoldJvmBindingFloatMeshes(VERSION = VERSION).render()

    /** The clojars binding's version, quoted in reasons so a report says which engine produced a mesh. */
    const val VERSION = "2.0.3"

    /** True when the from-source shim is what [boolean] runs — what a measuring test asks before it measures. */
    internal val isNative: Boolean get() = nativeVersion != null

    /**
     * **A test seam, and the measurement it exists for** (OP-9; OP-31's (5q)). Every general boolean passes
     * its two operands and its answer through here, so a test can ask the one question the suite could not
     * otherwise put: *is the engine's answer a function of its operands?* Measured in session 86 on a pivot
     * at a tight ring: two fresh constructions hand this call **byte-identical** operands (336 and 5639
     * vertices), and the engine answers with 2334 vertices on one call and 2336 on the next, in one JVM, with
     * the volume equal to nine decimals — also when the process is pinned to a single CPU. The bundled
     * `libmanifold.so.3` links `libtbb`, and neither Manifold's `deterministic` switch nor `MeshGL64` is on
     * the Java surface of this binding, so the cure was the binding itself, queued as (5q). On the default
     * engine a marginal contact's verdict — build or refuse — is therefore still the engine's own coin, which
     * is what `BlendCornerCanalTest`'s residue-zero sweep tolerates and `BooleanDeterminismTest` pins. Through
     * the from-source shim above the same twelve constructions give **one** mesh, which `BooleanEngineTest`
     * asserts rather than prints — the same seam, now measuring the cure.
     */
    internal var observer: ((BoolOp, Mesh3, Mesh3, Mesh3?) -> Unit)? = null

    actual fun boolean(
        kind: BoolOp,
        a: Mesh3,
        b: Mesh3,
    ): Pair<BoolMesh?, Msg?> {
        val out = boolean0(kind, a, b)
        observer?.invoke(kind, a, b, out.first?.mesh)
        return out
    }

    private fun boolean0(
        kind: BoolOp,
        a: Mesh3,
        b: Mesh3,
    ): Pair<BoolMesh?, Msg?> {
        val why = failure
        if (why != null) return null to meshBoolUnavailable(why)
        if (a.triangles.isEmpty() || b.triangles.isEmpty()) return null to Msgs.refusalMeshboolGeneralBooleanNeedsTwoClosed()
        if (nativeVersion != null) return native0(kind, a, b)
        return try {
            val ma = Manifold(meshGl(a))
            if (ma.status() != 0) return null to Msgs.refusalMeshboolFirstSolidMeshIsNot(status = ma.status())
            val mb = Manifold(meshGl(b))
            if (mb.status() != 0) return null to Msgs.refusalMeshboolSecondSolidMeshIsNot(status = mb.status())
            val r =
                when (kind) {
                    BoolOp.UNION -> ma.add(mb)
                    BoolOp.SUBTRACT -> ma.subtract(mb)
                    BoolOp.INTERSECT -> ma.intersect(mb)
                }
            if (r.status() != 0) return null to Msgs.refusalMeshboolGeneralBooleanFailedManifoldStatus(status = r.status())
            if (r.isEmpty) return null to Msgs.refusalMeshboolBooleanLeavesNothingSolid()
            val gl = r.getMeshGL()
            MeshCanon.finish(mesh3(gl), owners(gl, ma.originalID(), mb.originalID()))
        } catch (t: Throwable) {
            null to Msgs.refusalMeshboolGeneralBooleanEngineFailed(simpleName = t::class.simpleName ?: "", message = t.message ?: "")
        }
    }

    /**
     * The same boolean through the from-source shim (OP-31's (5q)): **double precision in and out**, and no
     * conversion to float anywhere on the way.
     *
     * The shim answers with four arrays and never throws — its `stage` is what becomes a refusal here, and
     * it is numbered to land on the refusals this file already had words for, so switching engines changed
     * no sentence. Ownership comes back per triangle, already matched against the two operands' original
     * ids on the C++ side, which is the same derivation [owners] makes for the clojars path.
     */
    private fun native0(
        kind: BoolOp,
        a: Mesh3,
        b: Mesh3,
    ): Pair<BoolMesh?, Msg?> {
        // by name, never by ordinal: `BoolOp` is declared UNION, INTERSECT, SUBTRACT, and a shim that read
        // an ordinal would silently swap two operations the day that list gains a member
        val op =
            when (kind) {
                BoolOp.UNION -> 0
                BoolOp.INTERSECT -> 1
                BoolOp.SUBTRACT -> 2
            }
        val out = NativeManifold.boolOp(op, flatVerts(a), flatTris(a), flatVerts(b), flatTris(b))
        val status = out[3] as IntArray
        when (status[0]) {
            1 -> return null to Msgs.refusalMeshboolFirstSolidMeshIsNot(status = status[1])
            2 -> return null to Msgs.refusalMeshboolSecondSolidMeshIsNot(status = status[1])
            3 -> return null to Msgs.refusalMeshboolGeneralBooleanFailedManifoldStatus(status = status[1])
            4 -> return null to Msgs.refusalMeshboolBooleanLeavesNothingSolid()
            5 -> return null to Msgs.refusalMeshboolNativeShimCaughtCppThrow()
        }
        val v = out[0] as DoubleArray
        val t = out[1] as IntArray
        val vertices = ArrayList<Vec3>(v.size / 3)
        var i = 0
        while (i + 2 < v.size) {
            vertices.add(Vec3(v[i], v[i + 1], v[i + 2]))
            i += 3
        }
        val tris = ArrayList<Tri>(t.size / 3)
        var j = 0
        while (j + 2 < t.size) {
            tris.add(Tri(t[j], t[j + 1], t[j + 2]))
            j += 3
        }
        return MeshCanon.finish(Mesh3(vertices, tris), out[2] as IntArray)
    }

    /** [mesh]'s positions as one flat array of doubles — the shim's own `MeshGL64.vertProperties`. */
    private fun flatVerts(mesh: Mesh3): DoubleArray {
        val out = DoubleArray(mesh.vertices.size * 3)
        for ((i, p) in mesh.vertices.withIndex()) {
            out[i * 3] = p.x
            out[i * 3 + 1] = p.y
            out[i * 3 + 2] = p.z
        }
        return out
    }

    /** [mesh]'s triangles as one flat index run. */
    private fun flatTris(mesh: Mesh3): IntArray {
        val out = IntArray(mesh.triangles.size * 3)
        for ((i, t) in mesh.triangles.withIndex()) {
            out[i * 3] = t.a
            out[i * 3 + 1] = t.b
            out[i * 3 + 2] = t.c
        }
        return out
    }

    /**
     * **Which operand each result triangle came from** ([BoolMesh]), read off Manifold's own triangle runs.
     *
     * A `MeshGL` built from a plain mesh is an *original*, so it has an `originalID`; the result's
     * `runOriginalID` says, per run of triangles, which original that run's surface belongs to, and
     * `runIndex` says where each run starts (in *indices*, so divisible by 3). Matching the two against the
     * two inputs' own ids is the whole derivation — nothing here measures anything, which is exactly why the
     * faces this ends up naming are provenance and not discovery (OP-8, OP-31 item 4).
     *
     * **Never a throw and never a wrong answer**: a binding that does not fill the runs in, or an id that
     * matches neither input, leaves `-1` — *"the engine did not say"* — and the assembly then looks that
     * triangle up against both operands' carriers instead of trusting a guess.
     */
    private fun owners(
        gl: MeshGL,
        idA: Int,
        idB: Int,
    ): IntArray {
        val tris = gl.triVerts().toIntArray().size / 3
        val out = IntArray(tris) { -1 }
        val ids =
            try {
                gl.runOriginalID().toIntArray()
            } catch (t: Throwable) {
                return out
            }
        val starts =
            try {
                gl.runIndex().toIntArray()
            } catch (t: Throwable) {
                return out
            }
        if (ids.isEmpty() || starts.size < ids.size) return out
        for (run in ids.indices) {
            val which =
                when (ids[run]) {
                    idA -> 0
                    idB -> 1
                    else -> continue
                }
            val from = starts[run] / 3
            val to = (if (run + 1 < starts.size) starts[run + 1] / 3 else tris)
            for (i in from until minOf(to, tris)) out[i] = which
        }
        return out
    }

    /** [mesh] as a `MeshGL`: three float properties per vertex, triangles as an unsigned index run. */
    private fun meshGl(mesh: Mesh3): MeshGL {
        val verts = FloatArray(mesh.vertices.size * 3)
        for ((i, v) in mesh.vertices.withIndex()) {
            verts[i * 3] = v.x.toFloat()
            verts[i * 3 + 1] = v.y.toFloat()
            verts[i * 3 + 2] = v.z.toFloat()
        }
        val idx = LongArray(mesh.triangles.size * 3)
        for ((i, t) in mesh.triangles.withIndex()) {
            idx[i * 3] = t.a.toLong()
            idx[i * 3 + 1] = t.b.toLong()
            idx[i * 3 + 2] = t.c.toLong()
        }
        val gl = MeshGL()
        gl.numProp(3)
        gl.vertProperties(manifold3d.FloatVector.FromArray(verts))
        gl.triVerts(UIntVector.FromArray(idx))
        return gl
    }

    /**
     * A `MeshGL` back as a [Mesh3]. `numProp` may exceed 3 (Manifold carries interpolated properties
     * through a boolean), so the stride is read rather than assumed; only the first three are positions.
     */
    private fun mesh3(gl: MeshGL): Mesh3 {
        val stride = gl.numProp()
        val props = gl.vertProperties().toFloatArray()
        val vertices = ArrayList<Vec3>(props.size / stride)
        var i = 0
        while (i + 2 < props.size) {
            vertices.add(Vec3(props[i].toDouble(), props[i + 1].toDouble(), props[i + 2].toDouble()))
            i += stride
        }
        val idx = gl.triVerts().toIntArray()
        val tris = ArrayList<Tri>(idx.size / 3)
        var j = 0
        while (j + 2 < idx.size) {
            tris.add(Tri(idx[j], idx[j + 1], idx[j + 2]))
            j += 3
        }
        return Mesh3(vertices, tris)
    }
}

/**
 * **ConstructIt's own Manifold binding** (OP-9; OP-31's (5q)) — two native methods and nothing else.
 *
 * The library behind it is `native/libconstructit_manifold.so`, built by `native/build.sh` from an upstream
 * Manifold 3.5.1 (`MANIFOLD_PAR=OFF`, so serial and therefore reproducible) with one C++ file of ours on
 * top. It is **not** part of the ordinary build and not committed: a bare checkout has no native toolchain
 * and must still configure and pass, so this is reached only when [MeshBool] has been pointed at a
 * directory that holds it. See `native/src/constructit_manifold.cpp` for the surface's rationale.
 *
 * Deliberately not `internal`: an internal member's JVM name is mangled with the module name, and a `native`
 * method's JVM name is the one the linker resolves. Package-private is the narrowest visibility that keeps
 * the name `constructit.geom.NativeManifold.boolOp` the shim exports.
 */
private object NativeManifold {
    /** What engine this is, as the shim was built to report it — version, commit, backend, precision. */
    external fun version(): String

    /**
     * The answer is `[DoubleArray positions, IntArray triangles,
     * IntArray owners, IntArray {stage, code}]`, and `stage` is what [MeshBool] turns into a refusal.
     * Never throws across the boundary: the shim catches its own C++ and reports stage 5 instead.
     */
    external fun boolOp(
        /** 0 union, 1 intersect, 2 subtract — see [MeshBool] for where the three names are spelled out. */
        kind: Int,
        vertsA: DoubleArray,
        trisA: IntArray,
        vertsB: DoubleArray,
        trisB: IntArray,
    ): Array<Any>
}
