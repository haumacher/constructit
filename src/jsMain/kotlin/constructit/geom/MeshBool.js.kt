package constructit.geom

import org.khronos.webgl.Float32Array
import org.khronos.webgl.Uint32Array
import org.khronos.webgl.get
import org.khronos.webgl.set
import kotlin.js.Promise

/**
 * **Manifold in the browser** (OP-9) — the same engine as the JVM side, compiled to WASM
 * (`manifold-3d`, wired in `build.gradle.kts` through the Kotlin/JS `npm()` mechanism).
 *
 * ### The one real problem: instantiating WASM is asynchronous, evaluating a node is not
 * `Evaluator` is a synchronous pure function of the graph (OP-4) and must stay one — a boolean node
 * cannot await anything. So the module is **loaded once at startup** and, until it is there,
 * [available] is false and every general boolean is an ordinary **invalid node with a reason**
 * (OP-3): hidden, harmless, and *healing* — [initialize] recomputes and repaints when the module
 * arrives, and the cross-axis solids simply appear. No loading flag threads through the engine, no
 * async colour spreads into the DAG; the state that already existed for "this cannot be built right
 * now" carries the case exactly.
 *
 * ### Why the module is fetched rather than bundled
 * The npm package's entry point is emscripten glue: an ES module with a top-level `await` that locates
 * its `.wasm` through `import.meta.url`. Both are hostile to being re-bundled by the Kotlin/JS webpack
 * pipeline, and the failure mode would be a mangled loader rather than a clear error. So the build
 * **copies `manifold.js` and `manifold.wasm` out of the resolved npm package into the distribution**
 * (see the `manifoldWasm` task) and this loads them from the app's *own* origin with the browser's
 * native ESM loader, `locateFile` pointing at the `.wasm` next to it. Consequences, both intended:
 * offline works — nothing is fetched from a CDN, the two files ship with the app — and the emscripten
 * glue is never transformed, so what runs is exactly what upstream published.
 */
actual object MeshBool {
    /** The `ManifoldToplevel` namespace once the module is up, null before. */
    private var wasm: dynamic = null

    /** Why [available] is false. Replaced as loading progresses, so the reason is always current. */
    private var failure: String = "Manifold's WASM module (OP-9) is still starting up"

    private var started = false

    /** The npm package version, quoted in [status] so a report says which engine produced a mesh. */
    const val VERSION = "3.5.1"

    actual val available: Boolean get() = wasm != null

    actual val status: String get() = if (wasm != null) "Manifold $VERSION (WASM, float32 meshes)" else failure

    /**
     * Load the engine, then call [onReady] — with `true` once, when it is usable, or `false` if it
     * cannot be loaded at all. Idempotent, so a second caller cannot start a second WASM instance.
     *
     * The caller's job in [onReady] is one line: recompute and repaint. That is the whole auto-heal
     * story (OP-3) — nothing needs to remember which nodes were waiting, because a node's validity is
     * recomputed from its inputs and this object's state, never cached across passes.
     */
    fun initialize(onReady: (Boolean) -> Unit) {
        if (started) return
        started = true
        val jsUrl = urlOf("manifold.js")
        val wasmUrl = urlOf("manifold.wasm")

        fun fail(why: String) {
            failure = why
            onReady(false)
        }

        fun instantiate(module: dynamic) {
            val options: dynamic = emptyObject()
            options.locateFile = { _: String -> wasmUrl }
            val instance = module.default(options) as Promise<dynamic>
            instance.then<Unit>(
                { top: dynamic ->
                    top.setup()
                    wasm = top
                    onReady(true)
                },
                { e: Throwable -> fail("Manifold's WASM module could not be instantiated ($e)") },
            )
        }

        try {
            importModule(jsUrl).then<Unit>(
                { module: dynamic -> instantiate(module) },
                { e: Throwable -> fail("Manifold's WASM module could not be loaded from $jsUrl ($e)") },
            )
        } catch (t: Throwable) {
            fail("Manifold could not be started (${t.message})")
        }
    }

    actual fun boolean(
        kind: BoolOp,
        a: Mesh3,
        b: Mesh3,
    ): Pair<Mesh3?, String?> {
        val w = wasm ?: return null to meshBoolUnavailable(failure)
        if (a.triangles.isEmpty() || b.triangles.isEmpty()) return null to "a general boolean needs two closed meshes"
        var ma: dynamic = null
        var mb: dynamic = null
        var result: dynamic = null
        return try {
            ma = construct(w.Manifold, arrayOf(meshOf(w, a)))
            mb = construct(w.Manifold, arrayOf(meshOf(w, b)))
            result =
                when (kind) {
                    BoolOp.UNION -> w.Manifold.union(ma, mb)
                    BoolOp.SUBTRACT -> w.Manifold.difference(ma, mb)
                    BoolOp.INTERSECT -> w.Manifold.intersection(ma, mb)
                }
            val state = result.status() as String
            if (state != "NoError") {
                null to "the general boolean failed (Manifold status $state)"
            } else if (result.isEmpty() as Boolean) {
                null to "the boolean leaves nothing of the solid"
            } else {
                MeshCanon.finish(mesh3(result.getMesh()))
            }
        } catch (t: Throwable) {
            null to "the general boolean engine failed (${t.message})"
        } finally {
            // WASM heap objects are not garbage-collected for us — a boolean per recompute would leak
            for (p in listOf(ma, mb, result)) if (p != null) p.delete()
        }
    }

    /** [mesh] as a Manifold `Mesh`: three float properties per vertex, triangles as a `Uint32Array`. */
    private fun meshOf(
        w: dynamic,
        mesh: Mesh3,
    ): dynamic {
        val verts = Float32Array(mesh.vertices.size * 3)
        for ((i, v) in mesh.vertices.withIndex()) {
            verts[i * 3] = v.x.toFloat()
            verts[i * 3 + 1] = v.y.toFloat()
            verts[i * 3 + 2] = v.z.toFloat()
        }
        val tris = Uint32Array(mesh.triangles.size * 3)
        for ((i, t) in mesh.triangles.withIndex()) {
            tris[i * 3] = t.a
            tris[i * 3 + 1] = t.b
            tris[i * 3 + 2] = t.c
        }
        val options: dynamic = emptyObject()
        options.numProp = 3
        options.vertProperties = verts
        options.triVerts = tris
        return construct(w.Mesh, arrayOf(options))
    }

    /**
     * A Manifold `Mesh` back as a [Mesh3]. `numProp` may exceed 3 (properties ride through a boolean),
     * so the stride is read rather than assumed; only the first three are positions.
     */
    private fun mesh3(mesh: dynamic): Mesh3 {
        val stride = mesh.numProp as Int
        val props = mesh.vertProperties.unsafeCast<Float32Array>()
        val vertices = ArrayList<Vec3>(props.length / stride)
        var i = 0
        while (i + 2 < props.length) {
            vertices.add(Vec3(props[i].toDouble(), props[i + 1].toDouble(), props[i + 2].toDouble()))
            i += stride
        }
        val idx = mesh.triVerts.unsafeCast<Uint32Array>()
        val tris = ArrayList<Tri>(idx.length / 3)
        var j = 0
        while (j + 2 < idx.length) {
            tris.add(Tri(idx[j], idx[j + 1], idx[j + 2]))
            j += 3
        }
        return Mesh3(vertices, tris)
    }
}

/** A URL for a file next to the app's own document — the copy the build put in the distribution. */
private val urlOf: (String) -> String =
    js("(function(n) { return new URL(n, document.baseURI).href; })").unsafeCast<(String) -> String>()

/**
 * `import(url)` at runtime, out of webpack's reach.
 *
 * Deliberately a bare function expression that captures nothing: written as a plain `import()` in
 * Kotlin source it would be a *static* dependency for the bundler, which is exactly what must not
 * happen — the emscripten glue has to reach the browser untransformed (see [MeshBool]).
 */
private val importModule: (String) -> Promise<dynamic> =
    js("(function(u) { return import(/* webpackIgnore: true */ u); })").unsafeCast<(String) -> Promise<dynamic>>()

/** `{}` — a fresh options bag to fill in dynamically. */
private fun emptyObject(): dynamic = js("({})")

/** `new ctor(...args)`, without embedding a `new` on a dynamic value in generated code. */
private val construct: (dynamic, Array<dynamic>) -> dynamic =
    js("(function(c, a) { return Reflect.construct(c, a); })").unsafeCast<(dynamic, Array<dynamic>) -> dynamic>()
