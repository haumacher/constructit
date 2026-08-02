package constructit.editor

import constructit.core.Evaluator
import constructit.core.SolidValue
import constructit.dsl.valueOf
import constructit.geom.Geom3
import constructit.geom.Mesh3
import constructit.geom.Vec3
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * One solid as the 3D view sees it: the element it belongs to, the mesh to draw, the colour it is drawn
 * in, and the [Edge3] creases that make its shape read.
 *
 * The mesh is taken straight out of the [SolidValue] — the 3D view is a **consumer of the sink**
 * (OP-9), not a second geometry pipeline: nothing here re-derives, re-tessellates or repairs anything,
 * so what is on screen is exactly what an STL export would contain. The edges are no exception: they are
 * *read off* that same mesh, not constructed beside it.
 *
 * [color] is the **assigned material's base colour where there is one, and the identification palette
 * where there is not** (OP-18's Tier 1, GitHub issue #8) — see [Scene3.colorOf].
 */
class SolidItem(
    val elementId: String,
    val mesh: Mesh3,
    val color: String,
) {
    /**
     * The creases, computed **on first read** rather than on construction.
     *
     * [Scene3.creaseEdges] walks every triangle and every directed edge, so it is the one genuinely
     * expensive thing in a scene — half a million triangles' worth for an imported assembly. Extracting a
     * scene has to stay cheap enough to do on *every* editor change, because that is what lets the view ask
     * "did the solids actually change?" before rebuilding anything ([Scene3Sync]); an eager crease pass
     * would have made the question cost more than the answer saves. Lazily is also *when* the number is
     * really needed: the two consumers that read edges (the GL upload and [Painter3]) are exactly the two
     * that were going to draw them anyway.
     *
     * Still no cache that can go stale: a [SolidItem] is built per extraction from an immutable [Mesh3], so
     * the value memoized here can only ever be this mesh's own.
     */
    val edges: List<Edge3> by lazy { Scene3.creaseEdges(mesh) }

    /**
     * The colour of this solid's feature edges: its own colour, darkened. Same hue, so a line still reads
     * as belonging to *this* part, and one authority both back ends ask, so an edge cannot come out a
     * different colour on the GPU than in a golden.
     */
    val edgeColor: String get() = Painter3.shade(color, Scene3.EDGE_SHADE)
}

/** A world-space line of the view's furniture: the ground grid and the three axes. */
class Line3(val a: Vec3, val b: Vec3, val color: String)

/**
 * A **feature edge**: an undirected mesh edge from [a] to [b] whose two triangles meet at a real crease
 * (their normals differ by more than [Scene3.CREASE_ANGLE_RAD]).
 *
 * Why the view needs these at all (GitHub issue #3): with one headlight and flat shading, two coplanar
 * faces get the same normal and therefore the *same* colour — so the floor of a 5 mm pocket shades exactly
 * like the surface it was cut into and the pocket's contour disappears. No lighting model fixes that; the
 * information is topological, not photometric. Drawing the crease *is* the fix, and it is honest about the
 * mesh being the sink (OP-9): a crease is a property of the triangles, found by walking them.
 *
 * [faceA] and [faceB] are the centroids of the two triangles that share the edge. They are here for the
 * painter's projector, which has no depth buffer and sorts by centroid depth: an edge must sort at least
 * as near as the nearer of *its own* two faces, or the face it lies on paints over it. The GPU path
 * ignores them — it has a real depth test.
 */
class Edge3(val a: Vec3, val b: Vec3, val faceA: Vec3, val faceB: Vec3)

/**
 * **What the 3D view's renderer is holding, and the exact question "is it still what the document says?"**
 *
 * The 3D view's geometry is one big vertex buffer, rebuilt from a whole [Scene3]. Rebuilding it is the most
 * expensive thing the view does — the creases of every solid, then a few million floats into the GPU — and
 * it was being done on **every [Editor.onChange]**, which includes every *hover*: a point-placing or
 * previewing tool refreshes its preview on each pointer move, so plain mouse motion across the 3D canvas
 * re-creased half a million triangles and re-uploaded them. Nothing about the solids had changed.
 *
 * The criterion here is the one the realistic preview already runs on (see [SceneSync] and OP-5): **mesh
 * identity**. The evaluator's argument-identity memo hands an unchanged node back the very object it
 * computed last time, so `mesh === mesh` is not a heuristic about the geometry — it is the statement *this
 * node did not recompute*. It cannot miss a real change: a node that recomputed produces a new object, and
 * an object that is the same one was produced by a computation that did not run.
 *
 * What is compared is therefore everything the buffer is built out of, and nothing else: the solids in
 * order, each one's element id, its colour (which is *in* the vertex data, so a material assignment must
 * re-upload) and its mesh's identity. The furniture is not compared because it is a pure function of those
 * meshes' bounds ([Scene3.gridStepFor]) — same solids, same grid.
 *
 * Conservative in the safe direction, deliberately: a node that recomputes to an *equal* mesh hands out a
 * new object and buys one upload it did not need. Comparing the geometry itself to avoid that would cost a
 * pass over every vertex on every hover, which is the thing being removed.
 */
class Scene3Sync {
    private var uploaded: List<SolidItem>? = null

    /** How many times [update] has actually uploaded. Zero is the answer a hover — or an orbit — must give. */
    var uploads: Int = 0
        private set

    /** True when [scene]'s solids are the ones already uploaded, so the renderer holds them already. */
    fun holds(scene: Scene3): Boolean {
        val had = uploaded ?: return false
        if (had.size != scene.solids.size) return false
        for (i in had.indices) {
            val a = had[i]
            val b = scene.solids[i]
            if (a.elementId != b.elementId || a.color != b.color || a.mesh !== b.mesh) return false
        }
        return true
    }

    /** Hand [scene] to [upload] exactly when it is not what was uploaded last. Returns whether it did. */
    fun update(
        scene: Scene3,
        upload: (Scene3) -> Unit,
    ): Boolean {
        if (holds(scene)) return false
        upload(scene)
        uploaded = scene.solids
        uploads++
        return true
    }

    /** Forget what was uploaded — what a renderer whose buffers were dropped says. */
    fun invalidate() {
        uploaded = null
    }
}

/**
 * Everything the 3D view draws, extracted from a [Document] in one pass: the visible solids, plus the
 * grid and axes that give them a place to stand.
 *
 * A *value*, computed per repaint from evaluated nodes only — the same discipline the 2D
 * [SceneRenderer] follows. Nothing is cached, so there is no state that can disagree with the model
 * after an edit, and both back ends (the painter's projector of [Painter3] for tests, WebGL in the
 * browser) consume the identical scene.
 */
class Scene3(val solids: List<SolidItem>, lines: List<Line3>? = null) {
    /**
     * The view's furniture — the ground grid and the axes — sized to the model, and like [SolidItem.edges]
     * computed **on first read**: its spacing comes from [boundsOf], which is a pass over every vertex of
     * every solid, and an extraction that is only being asked whether anything changed must not pay for it.
     * Passing a list explicitly says what the furniture is (what a hand-built test scene does).
     */
    val lines: List<Line3> by lazy { lines ?: furniture(gridStepFor(boundsOf(solids))) }

    val isEmpty: Boolean get() = solids.isEmpty()

    /** Axis-aligned bounds of the solids (the furniture does not count), or null when there are none. */
    fun bounds(): Pair<Vec3, Vec3>? = boundsOf(solids)

    companion object {
        /**
         * The solid palette — **what an *undressed* solid is drawn in**. A stable colour per element,
         * taken by the element's own id rather than by its position in the list, so deleting one solid
         * never recolours the others — a solid that changes colour when a sibling goes reads as a
         * different part.
         *
         * It is a *default*, not the law: a solid the user has given a material wears that material's
         * colour here ([colorOf]). See [Appearance]'s own note for why the palette is still not the
         * material default — identification and appearance are two different questions, and only the
         * second one is exported.
         */
        val PALETTE =
            listOf("#4e79a7", "#f28e2b", "#59a14f", "#e15759", "#b07aa1", "#76b7b2", "#edc948", "#9c755f")

        val GRID_COLOR = "#dcdcdc"
        val AXIS_X_COLOR = "#c0504d"
        val AXIS_Y_COLOR = "#4f8a3d"
        val AXIS_Z_COLOR = "#3f6fa8"

        /** Half-extent of the ground grid, in cells either side of the origin. */
        const val GRID_HALF = 10

        /** How much of a solid's colour its feature edges keep — dark enough to read on a fully lit face. */
        const val EDGE_SHADE = 0.55

        /**
         * How far two neighbouring triangles' normals must diverge before the edge between them counts as a
         * **crease** and is drawn: 30°, i.e. every dihedral sharper than 150°.
         *
         * The number is set by what must *not* be drawn, not by what must. A tessellated cylinder, fillet or
         * arc is a fan of facets whose neighbours differ by exactly the chord step
         * `2·acos(1 − tol/r)` (`GeomMath.TESS_TOL_MM` = 0.02 mm), so a threshold below that step turns every
         * curved surface into a barrel of lines — the tessellation made visible, which is precisely the thing
         * flat shading is supposed to keep quiet about. Inverting the chord formula, a threshold of `t` stays
         * clean for every radius above `tol / (1 − cos(t/2))` — a bound, because the chord *count* is rounded
         * up, so the step a mesh actually has is a little smaller than the formula's:
         *
         * | threshold | clean above | 1 mm fillet (22.5°) | 5 mm fillet (10.0°) | 10 mm bore (7.2°) |
         * |-----------|-------------|---------------------|---------------------|-------------------|
         * | 20°       | r > 1.32 mm | **drawn** (clutter) | 2.0x margin         | 2.8x margin       |
         * | 30°       | r > 0.59 mm | 1.3x margin         | 3.0x margin         | 4.2x margin       |
         *
         * 20° would speckle a 1–2 mm fillet, which is an everyday feature size; 30° only reaches radii under
         * about 0.6 mm (a sub-millimetre bore), where the facets are a few pixels apart anyway. What 30°
         * gives up is a crease shallower than 150° — and there the two faces already *do* shade differently,
         * which is the case shading handles by itself. Threshold and shading therefore cover between them
         * exactly the range each is good at.
         */
        const val CREASE_ANGLE_RAD = 30.0 * PI / 180.0

        /**
         * The **feature edges** of [mesh]: every undirected edge whose two triangles' outward normals differ
         * by more than [thresholdRad].
         *
         * Deterministic by construction: normals and centroids come from the triangles in emission order, and
         * the output follows the order in which the edges are *first met* while walking those triangles — a
         * hash map is used for lookup only, never iterated (the same rule [Mesh3] itself obeys).
         *
         * Edges with a number of adjacent triangles other than two contribute nothing. A solid's mesh is a
         * closed 2-manifold (OP-2, asserted by `assertManifold` on every solid in the suite), so that case is
         * not a shape being missed — it is a mesh that is already broken, and inventing lines along its holes
         * would only dress up the defect.
         *
         * Cost is one pass over the triangles per repaint, like everything else in the scene — no cache, so
         * nothing here can disagree with the model after an edit. Measured on this suite's meshes it is
         * microseconds against the millisecond the boolean itself takes; if a revolve-sized mesh ever makes it
         * matter, the answer is the queued *incremental recompute* (a value cache keyed by source-node
         * versions, which would carry the whole scene) rather than a private cache in the renderer.
         */
        fun creaseEdges(
            mesh: Mesh3,
            thresholdRad: Double = CREASE_ANGLE_RAD,
        ): List<Edge3> {
            val tris = mesh.triangles
            if (tris.isEmpty()) return emptyList()
            val v = mesh.vertices
            val normals = arrayOfNulls<Vec3>(tris.size)
            val centroids = arrayOfNulls<Vec3>(tris.size)
            for ((i, t) in tris.withIndex()) {
                val a = v[t.a]
                val b = v[t.b]
                val c = v[t.c]
                val n = (b - a).cross(c - a)
                if (n.length() <= Vec3.EPS) continue // degenerate sliver: it has no normal to compare
                normals[i] = n.normalized()
                centroids[i] = (a + b + c) * (1.0 / 3.0)
            }
            val order = ArrayList<Long>(tris.size * 3 / 2)
            val first = HashMap<Long, Int>(tris.size * 3)
            val second = HashMap<Long, Int>(tris.size * 3)

            fun meet(
                tri: Int,
                p: Int,
                q: Int,
            ) {
                val key = edgeKey(p, q)
                if (!first.containsKey(key)) {
                    first[key] = tri
                    order.add(key)
                } else if (!second.containsKey(key)) {
                    second[key] = tri
                } else {
                    // a third face on one edge: not a manifold, so there is no dihedral angle to speak of
                    second[key] = -1
                }
            }
            for ((i, t) in tris.withIndex()) {
                if (normals[i] == null) continue
                meet(i, t.a, t.b)
                meet(i, t.b, t.c)
                meet(i, t.c, t.a)
            }
            val cosLimit = cos(thresholdRad)
            val out = ArrayList<Edge3>()
            for (key in order) {
                val i = first[key] ?: continue
                val j = second[key] ?: continue
                if (j < 0) continue
                val na = normals[i] ?: continue
                val nb = normals[j] ?: continue
                if (na.dot(nb) >= cosLimit) continue
                out.add(Edge3(v[(key ushr 32).toInt()], v[(key and 0xffffffffL).toInt()], centroids[i]!!, centroids[j]!!))
            }
            return out
        }

        /** One key per *undirected* edge: the two vertex indices, smaller first, packed into a long. */
        private fun edgeKey(
            i: Int,
            j: Int,
        ): Long {
            val lo = min(i, j)
            val hi = max(i, j)
            return (lo.toLong() shl 32) or hi.toLong()
        }

        fun colorFor(elementId: String): String {
            val n = elementId.dropWhile { !it.isDigit() }.toIntOrNull() ?: 0
            return PALETTE[n % PALETTE.size]
        }

        /**
         * What [el] is shaded with in the **3D construction view**: the base colour of the material the user
         * assigned it, or the identification [PALETTE] entry while nobody has dressed it (GitHub issue #8).
         *
         * Reported as *"the colour of a solid should be reflected in the 3D construction view"*: the view had
         * only ever asked [colorFor], so a colour picked in the panel could reach the GLB and the realistic
         * preview but never this view — the one the modelling actually happens in. Asking [Document.materialOf]
         * would have been the wrong question, because it answers [Appearance.DEFAULT] for an undressed solid
         * and a scene of identical light-grey bodies is a scene in which nothing can be told apart.
         * [Document.assignedMaterial] is the right one: it distinguishes *a choice* from *no choice*, so the
         * palette keeps doing its own job — identification — for everything the user has not spoken about.
         *
         * **Roughness and metalness deliberately do not arrive here, and cannot.** This is a flat-shaded
         * line-and-fill technical view: one headlight, a diffuse term and an ambient floor ([Painter3]), with
         * feature edges carrying the topology that shading cannot. There is no specular term for a roughness to
         * spread and no environment for a metal to reflect, so a "metallic" solid could differ from a plastic
         * one only by some invented darkening — a number that looked like PBR and meant nothing. The
         * realistic preview and the GLB export are where those two numbers are honest; here the colour is the
         * whole of what an appearance can say, and that is stated rather than half-implemented.
         */
        fun colorOf(
            doc: Document,
            el: Element,
        ): String = doc.assignedMaterial(el)?.color ?: colorFor(el.id)

        /**
         * The scene of [doc]: every visible element whose value is a solid, plus a ground grid sized to
         * the model.
         *
         * Invalid solids simply contribute nothing, which is OP-3's rule unchanged — a depth dragged to
         * zero makes the part vanish from the 3D view and come back when it is dragged open again.
         *
         * Each solid's colour is read from the document here, per extraction ([colorOf]), so an assigned
         * material shows up on the very next repaint with no cache in between — the same *value, not state*
         * discipline the rest of this scene follows.
         */
        fun extract(
            doc: Document,
            ev: Evaluator = Evaluator(),
        ): Scene3 {
            val candidates = doc.elements.filter { it.visible && ev.valueOf(it.ref) is SolidValue }
            // A solid another visible solid is made OF is that solid's construction material —
            // OP-14's scaffolding rule one level up: a boolean's raw operand, the counterbore's
            // cylinder. Drawing both paints two coincident shells fighting per pixel, and the operand
            // is not an output; delete or hide the consumer and the operand shows again on its own.
            //
            // **Material, not merely ancestry** — the distinction the seam forces (OP-17). A solid reaches
            // another solid as material only along *solid-valued* inputs, which is exactly what a boolean
            // takes; a frame accessor (`facePlane`, `sideFacePlane`) or a section passes through a plane or
            // a region, so the base is an ancestor without being the material. Without that distinction a
            // plate vanished the moment anything was sketched on one of its faces, and a wall vanished
            // under the storey stacked on it: the consumer is a *boss on* the base, or a *drill through*
            // it — the base is still an output in its own right until a boolean consumes it.
            val consumedIds = HashSet<String>()
            val visited = HashSet<String>()

            fun walk(node: constructit.core.Node) {
                if (!visited.add(node.id)) return
                for (input in node.inputs) {
                    if (!Document.isMaterial(ev, input)) continue
                    consumedIds.add(input.id)
                    walk(input)
                }
            }
            candidates.forEach { walk(it.ref.node) }
            val solids = ArrayList<SolidItem>()
            for (el in candidates) {
                if (el.ref.node.id in consumedIds) continue
                val v = ev.valueOf(el.ref) as? SolidValue ?: continue
                if (v.solid.mesh.triangles.isEmpty()) continue
                solids.add(SolidItem(el.id, v.solid.mesh, colorOf(doc, el)))
            }
            return Scene3(solids)
        }

        /** Combined bounds of [solids], or null when none of them has a mesh. */
        fun boundsOf(solids: List<SolidItem>): Pair<Vec3, Vec3>? {
            var lo: Vec3? = null
            var hi: Vec3? = null
            for (s in solids) {
                val b = Geom3.bounds(s.mesh) ?: continue
                val l = lo
                val h = hi
                lo = if (l == null) b.first else Vec3(min(l.x, b.first.x), min(l.y, b.first.y), min(l.z, b.first.z))
                hi = if (h == null) b.second else Vec3(max(h.x, b.second.x), max(h.y, b.second.y), max(h.z, b.second.z))
            }
            val l = lo ?: return null
            return l to (hi ?: l)
        }

        /**
         * A "nice" grid spacing (1/2/5 x 10^k mm) that puts the model inside about ten cells — the same
         * rule the 2D grid uses, driven by the model's size instead of by the zoom, because a 3D grid
         * that re-spaced itself while orbiting would read as the ground moving.
         */
        fun gridStepFor(bounds: Pair<Vec3, Vec3>?): Double {
            val b = bounds ?: return 10.0
            val d = b.second - b.first
            val extent = max(max(d.x, d.y), 1.0)
            val target = extent / GRID_HALF
            val mag = 10.0.pow(floor(log10(target)))
            val norm = target / mag
            val factor =
                if (norm < 2) {
                    1.0
                } else if (norm < 5) {
                    2.0
                } else {
                    5.0
                }
            return factor * mag
        }

        /**
         * The ground grid on the world XY plane, and the three axes over one grid's worth of length.
         *
         * Emitted **one cell at a time** rather than as full-length lines. That is not tidiness: the
         * painter's projector sorts by a segment's own depth, and a line spanning the whole grid has a
         * single depth for its whole length — so it would be painted over a part standing halfway along
         * it. Per-cell segments give the sort something local to work with, and the WebGL path does not
         * care either way (it has a real depth buffer).
         */
        fun furniture(stepMm: Double): List<Line3> {
            val out = ArrayList<Line3>()
            for (i in -GRID_HALF..GRID_HALF) {
                val t = i * stepMm
                for (j in -GRID_HALF until GRID_HALF) {
                    val a = j * stepMm
                    val b = (j + 1) * stepMm
                    out.add(Line3(Vec3(t, a, 0.0), Vec3(t, b, 0.0), GRID_COLOR))
                    out.add(Line3(Vec3(a, t, 0.0), Vec3(b, t, 0.0), GRID_COLOR))
                }
            }
            for (j in 0 until GRID_HALF) {
                val a = j * stepMm
                val b = (j + 1) * stepMm
                out.add(Line3(Vec3(a, 0.0, 0.0), Vec3(b, 0.0, 0.0), AXIS_X_COLOR))
                out.add(Line3(Vec3(0.0, a, 0.0), Vec3(0.0, b, 0.0), AXIS_Y_COLOR))
                out.add(Line3(Vec3(0.0, 0.0, a), Vec3(0.0, 0.0, b), AXIS_Z_COLOR))
            }
            return out
        }
    }
}
