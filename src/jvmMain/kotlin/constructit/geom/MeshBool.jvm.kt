package constructit.geom

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
 * **Precision, stated.** `MeshGL` carries vertex positions as **float32** in this version of Manifold
 * (`MeshGL64` is the newer double-precision form, and moving to it is a one-line change here). So a
 * general boolean is accurate to about 1e-5 mm on drawing-sized coordinates, five orders of magnitude
 * coarser than the exact path's 1e-7 mm welding lattice but two orders *finer* than the 0.02 mm chord
 * tolerance that the tessellated operands already carry. That is the honest cost of the general path, and
 * it is one more reason the exact path stays exact.
 */
actual object MeshBool {
    /** The probe's failure, or null when the engine ran. Computed once, at class-init. */
    private val failure: String? =
        try {
            preloadAssimp()
            // one real boolean, not just a class load: on a jar built for another platform the classes
            // resolve and only the first native call fails
            val cube = Manifold.Cube(manifold3d.linalg.DoubleVec3(1.0, 1.0, 1.0), false)
            val probe = cube.subtract(cube.translate(0.5, 0.5, 0.5))
            if (probe.isEmpty) "the engine returned nothing for its own smoke test" else null
        } catch (t: Throwable) {
            "no usable native Manifold library for this platform (${t::class.simpleName}: ${t.message})"
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

    actual val status: String get() = failure ?: "Manifold $VERSION (JVM binding, float32 meshes)"

    /** The binding's version, quoted in reasons so a report says which engine produced a mesh. */
    const val VERSION = "2.0.3"

    actual fun boolean(
        kind: BoolOp,
        a: Mesh3,
        b: Mesh3,
    ): Pair<Mesh3?, String?> {
        val why = failure
        if (why != null) return null to meshBoolUnavailable(why)
        if (a.triangles.isEmpty() || b.triangles.isEmpty()) return null to "a general boolean needs two closed meshes"
        return try {
            val ma = Manifold(meshGl(a))
            if (ma.status() != 0) return null to "the first solid's mesh is not one Manifold accepts (status ${ma.status()})"
            val mb = Manifold(meshGl(b))
            if (mb.status() != 0) return null to "the second solid's mesh is not one Manifold accepts (status ${mb.status()})"
            val r =
                when (kind) {
                    BoolOp.UNION -> ma.add(mb)
                    BoolOp.SUBTRACT -> ma.subtract(mb)
                    BoolOp.INTERSECT -> ma.intersect(mb)
                }
            if (r.status() != 0) return null to "the general boolean failed (Manifold status ${r.status()})"
            if (r.isEmpty) return null to "the boolean leaves nothing of the solid"
            MeshCanon.finish(mesh3(r.getMeshGL()))
        } catch (t: Throwable) {
            null to "the general boolean engine failed (${t::class.simpleName}: ${t.message})"
        }
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
