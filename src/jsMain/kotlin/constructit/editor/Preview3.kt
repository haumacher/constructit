package constructit.editor

import constructit.exchange.ExportNode
import constructit.exchange.ExportScene
import constructit.exchange.RenderMesh
import constructit.geom.Geom3
import constructit.geom.Mesh3
import constructit.geom.Vec3
import constructit.three.THREE
import org.khronos.webgl.Float32Array
import org.khronos.webgl.Uint32Array
import org.khronos.webgl.set
import org.w3c.dom.HTMLCanvasElement
import kotlin.math.max
import kotlin.math.min

/**
 * **The realistic preview** — a third view, display-only, on three.js.
 *
 * Three boundaries keep it honest to the architecture, and all three are visible in this file:
 *
 * 1. **`jsMain` only.** The engine stays platform-free: what crosses the seam is the neutral
 *    [ExportScene] — the very scene the GLB writer consumes — plus [RenderMesh], the one place normals are
 *    computed. So *what the preview shows is what the exported GLB shows, by construction*, rather than by two
 *    pipelines being kept in step by hand.
 * 2. **A second viewer, never the editing surface.** No picking, no gizmos, no tools, no hit-testing. The
 *    working 3D view ([Viewport3]) keeps the ray seam and the working-plane gestures; here a drag is always the
 *    camera's, which is why there is no decision in this file worth a headless test.
 * 3. **Incremental for free.** OP-5's argument-identity memo means an unchanged solid hands back the *same*
 *    [Mesh3] object, so mesh identity says which bodies changed and only their buffers are re-uploaded. An
 *    edit to one part of an assembly costs one geometry rebuild, not all of them.
 *
 * "Realistic" is configuration, not code: a PBR material from the Tier-1 numbers, an environment map through
 * [THREE.PMREMGenerator] (so a surface has something to reflect), and ACES tone mapping (so a highlight rolls
 * off instead of clipping). That trio is what makes flat-coloured solids read as physical objects.
 */
class Preview3(private val canvas: HTMLCanvasElement) {
    /** One body on screen, and what it was built from — the identity that says whether to rebuild it. */
    private class Entry(
        val mesh: Mesh3,
        var material: Appearance,
        val geometry: THREE.BufferGeometry,
        val standard: THREE.MeshStandardMaterial,
        val node: THREE.Mesh,
    )

    private var renderer: THREE.WebGLRenderer? = null
    private var scene: THREE.Scene? = null
    private var cam: THREE.PerspectiveCamera? = null
    private val shown = HashMap<String, Entry>()

    /** The orbit camera — the same one the working 3D view uses, so both views turn the same way. */
    var camera: Camera3 = Camera3()
        private set

    /** How many geometries the last [update] had to re-upload. Zero is the answer an orbit should give. */
    var lastUploads: Int = 0
        private set

    /** Whether the module is up and a context was obtained. */
    val ready: Boolean get() = renderer != null

    /** What went wrong, or null. A preview that cannot draw says so rather than showing an empty box. */
    var problem: String? = null
        private set

    // ---- the module, loaded on first open ----

    /**
     * Load three.js and call [then] once it is up (immediately, if it already is).
     *
     * A **dynamic import**, so webpack splits it into a chunk of its own and the main bundle carries none of
     * it: the preview is a panel, and most sessions never open it. The module namespace is then published as
     * the global `THREE` the external declarations are written against — one assignment, and the whole binding
     * is statically typed from there on.
     */
    fun load(then: (Boolean) -> Unit) {
        if (loaded) {
            then(true)
            return
        }
        if (loading) {
            waiting.add(then)
            return
        }
        loading = true
        waiting.add(then)
        try {
            val imported: dynamic = js("import('three')")
            imported.then(
                { module: dynamic ->
                    js("globalThis").THREE = module
                    loaded = true
                    loading = false
                    val version = (module.REVISION as? String) ?: "?"
                    console.log("[Preview] three.js r$version loaded")
                    waiting.toList().also { waiting.clear() }.forEach { it(true) }
                },
                { e: dynamic ->
                    loading = false
                    problem = "the 3D library could not be loaded: ${e?.message ?: e}"
                    console.log("[Preview] unavailable — $problem")
                    waiting.toList().also { waiting.clear() }.forEach { it(false) }
                },
            )
        } catch (e: Throwable) {
            loading = false
            problem = "the 3D library could not be loaded: ${e.message}"
            console.log("[Preview] unavailable — $problem")
            waiting.toList().also { waiting.clear() }.forEach { it(false) }
        }
    }

    // ---- the scene ----

    private fun init(): Boolean {
        if (renderer != null) return true
        val params: dynamic = js("({ antialias: true, alpha: false })")
        params.canvas = canvas
        // `preserveDrawingBuffer`, for [WebGlRenderer3]'s reason exactly: without it the canvas is only
        // readable inside the frame that drew it, so `toDataURL` and a screenshot come back blank — and the
        // browser E2E would be asserting nothing about a view whose only output is pixels.
        params.preserveDrawingBuffer = true
        val r =
            try {
                THREE.WebGLRenderer(params)
            } catch (e: Throwable) {
                problem = "this browser gave no WebGL context, so the preview cannot draw"
                return false
            }
        r.setPixelRatio(minOf(2.0, kotlinx.browser.window.devicePixelRatio))
        // the two settings that turn "flat colours" into "a photograph of a part": a filmic curve instead of a
        // hard clip, and a display colour space, so the linear working values are shown correctly
        r.toneMapping = THREE.ACESFilmicToneMapping
        r.toneMappingExposure = 1.0
        r.outputColorSpace = THREE.SRGBColorSpace
        val s = THREE.Scene()
        val environment = pmrem(r)
        s.environment = environment
        s.background = environment
        // one directional light on top of the environment: an image-based light alone has no *direction*, so
        // edges lose their crispness — this is what puts a definite highlight on a chamfer
        val key = THREE.DirectionalLight(0xffffff, 1.6)
        key.position.set(0.6, -1.0, 1.4)
        s.add(key)
        renderer = r
        scene = s
        cam = THREE.PerspectiveCamera(45.0, 1.0, 0.1, 1e6)
        return true
    }

    /**
     * The environment, built here rather than imported: a **room** — an inside-out box with a few bright panels
     * — pre-filtered into a radiance map.
     *
     * That is exactly what three's own `RoomEnvironment` addon is, and this is a dozen lines of it. Worth
     * writing rather than pulling in, for the reason the whole preview is minimal: an addon is a second
     * dependency surface (and, in three's layout, one that imports the library by bare name, which a browser
     * cannot resolve without an import map) for something a box and two panels do.
     */
    private fun pmrem(r: THREE.WebGLRenderer): dynamic {
        val room = THREE.Scene()

        fun panel(
            w: Double,
            h: Double,
            d: Double,
            x: Double,
            y: Double,
            z: Double,
            color: Int,
            side: Int? = null,
        ) {
            val params: dynamic = js("({})")
            params.color = color
            if (side != null) params.side = side
            val box = THREE.Mesh(THREE.BoxGeometry(w, h, d), THREE.MeshBasicMaterial(params))
            box.position.set(x, y, z)
            room.add(box)
        }
        // the room itself, seen from the inside, in a neutral mid grey
        panel(20.0, 20.0, 20.0, 0.0, 0.0, 0.0, 0x8f8f8f, THREE.BackSide)
        // ...and the lights: one broad panel overhead and two dimmer ones to the sides, which is what gives a
        // curved surface a gradient to run along instead of one flat reflection
        panel(10.0, 0.4, 10.0, 0.0, 9.0, 0.0, 0xffffff)
        panel(0.4, 8.0, 8.0, -9.0, 0.0, 0.0, 0x606060)
        panel(0.4, 8.0, 8.0, 9.0, 0.0, 0.0, 0x909090)
        val generator = THREE.PMREMGenerator(r)
        val target = generator.fromScene(room, 0.04)
        generator.dispose()
        return target.texture
    }

    /**
     * Bring the preview in step with [exported] — **re-uploading only what changed**.
     *
     * Three cases per body, and the middle one is the point of the whole arrangement: the mesh object is the
     * same as last time (nothing upstream of it moved), so its buffers are left alone and at most its material
     * is written; the mesh is new, so its geometry is rebuilt; or the body is gone, so its geometry and
     * material are disposed. [lastUploads] counts the rebuilds, which is how the flow is observed from outside.
     */
    fun update(exported: ExportScene) {
        if (!init()) return
        val s = scene ?: return
        var uploads = 0
        val live = HashSet<String>()
        for (node in exported.nodes) {
            live.add(node.name)
            val had = shown[node.name]
            if (had != null && had.mesh === node.mesh) {
                // nothing upstream of this body moved (OP-5): keep its buffers, and write the material only if
                // *that* changed — a colour picked in the panel must not cost a geometry upload
                if (had.material != node.material) {
                    apply(had.standard, node.material)
                    had.material = node.material
                }
                continue
            }
            had?.let { drop(it) }
            shown[node.name] = build(node).also { s.add(it.node) }
            uploads++
        }
        for (name in shown.keys.toList()) {
            if (name in live) continue
            shown.remove(name)?.let {
                s.remove(it.node)
                drop(it)
            }
        }
        lastUploads = uploads
    }

    private fun build(node: ExportNode): Entry {
        val render = RenderMesh.of(node.mesh)
        // straight from the seam's arrays into typed arrays — no per-vertex object in between, which is what
        // keeps an upload proportional to the mesh and nothing else
        val positions = Float32Array(render.positions.size)
        for (i in render.positions.indices) positions[i] = render.positions[i].toFloat()
        val normals = Float32Array(render.normals.size)
        for (i in render.normals.indices) normals[i] = render.normals[i].toFloat()
        val indices = Uint32Array(render.indices.size)
        for (i in render.indices.indices) indices[i] = render.indices[i]
        val geometry = THREE.BufferGeometry()
        geometry.setAttribute("position", THREE.BufferAttribute(positions, 3))
        geometry.setAttribute("normal", THREE.BufferAttribute(normals, 3))
        geometry.setIndex(THREE.BufferAttribute(indices, 1))
        geometry.computeBoundingSphere()
        val material = THREE.MeshStandardMaterial()
        apply(material, node.material)
        val mesh = THREE.Mesh(geometry, material)
        mesh.name = node.name
        return Entry(node.mesh, node.material, geometry, material, mesh)
    }

    /**
     * The Tier-1 numbers onto a `MeshStandardMaterial`. The colour is set from [Appearance.linearRgb] — the
     * same conversion the GLB's `baseColorFactor` uses, and the space three's renderer works in — so the two
     * consumers cannot drift apart by one being told sRGB and the other linear.
     */
    private fun apply(
        material: THREE.MeshStandardMaterial,
        appearance: Appearance,
    ) {
        val rgb = appearance.linearRgb()
        material.color.setRGB(rgb[0], rgb[1], rgb[2])
        material.roughness = appearance.roughnessClamped
        material.metalness = appearance.metallicClamped
    }

    private fun drop(entry: Entry) {
        entry.geometry.dispose()
        entry.standard.dispose()
    }

    /** Point the camera at [exported] — [Camera3.framing], so this view opens exactly as the 3D view does. */
    fun frame(exported: ExportScene) {
        var lo: Vec3? = null
        var hi: Vec3? = null
        for (node in exported.nodes) {
            val b = Geom3.bounds(node.mesh) ?: continue
            val l = lo
            val h = hi
            lo = if (l == null) b.first else Vec3(min(l.x, b.first.x), min(l.y, b.first.y), min(l.z, b.first.z))
            hi = if (h == null) b.second else Vec3(max(h.x, b.second.x), max(h.y, b.second.y), max(h.z, b.second.z))
        }
        val l = lo ?: return
        camera = Camera3.framing(l, hi ?: l)
    }

    /**
     * Orbit by a screen displacement. The pixels-to-radians constant and the sign convention are
     * [Viewport3]'s own ([Viewport3.ORBIT_RAD_PER_PX]) rather than new numbers: a drag has to *feel* the same
     * in both 3D views, and two constants for one gesture is how they drift apart.
     */
    fun orbit(
        dxPx: Double,
        dyPx: Double,
    ) {
        camera = camera.orbit(-dxPx * Viewport3.ORBIT_RAD_PER_PX, dyPx * Viewport3.ORBIT_RAD_PER_PX)
    }

    fun zoom(deltaY: Double) {
        camera = camera.zoom(if (deltaY < 0) 1.0 / Viewport3.ZOOM_STEP else Viewport3.ZOOM_STEP)
    }

    fun pan(
        dxPx: Double,
        dyPx: Double,
    ) {
        camera = camera.panBy(dxPx, dyPx, canvas.height.toDouble())
    }

    /** Draw one frame at the canvas's current size. */
    fun draw() {
        val r = renderer ?: return
        val s = scene ?: return
        val c = cam ?: return
        val w = canvas.clientWidth
        val h = canvas.clientHeight
        if (w <= 0 || h <= 0) return
        r.setSize(w, h, false)
        c.aspect = w.toDouble() / h.toDouble()
        c.fov = camera.fovY * 180.0 / kotlin.math.PI
        c.near = camera.near
        c.far = camera.far
        c.updateProjectionMatrix()
        val eye = camera.eye
        c.position.set(eye.x, eye.y, eye.z)
        // the world is Z-up (OP-17) and three's default camera up is +Y, so the up vector is stated rather
        // than the geometry turned: no conversion is applied to a single vertex
        c.up.set(0.0, 0.0, 1.0)
        c.lookAt(camera.target.x, camera.target.y, camera.target.z)
        r.render(s, c)
    }

    private companion object {
        private var loaded = false
        private var loading = false
        private val waiting = ArrayList<(Boolean) -> Unit>()
    }
}
