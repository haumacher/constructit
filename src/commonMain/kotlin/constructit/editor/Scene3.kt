package constructit.editor

import constructit.core.Evaluator
import constructit.core.SolidValue
import constructit.dsl.valueOf
import constructit.geom.Geom3
import constructit.geom.Mesh3
import constructit.geom.Vec3
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * One solid as the 3D view sees it: the element it belongs to, the mesh to draw, and the colour it is
 * *always* drawn in.
 *
 * The mesh is taken straight out of the [SolidValue] — the 3D view is a **consumer of the sink**
 * (OP-9), not a second geometry pipeline: nothing here re-derives, re-tessellates or repairs anything,
 * so what is on screen is exactly what an STL export would contain.
 */
class SolidItem(val elementId: String, val mesh: Mesh3, val color: String)

/** A world-space line of the view's furniture: the ground grid and the three axes. */
class Line3(val a: Vec3, val b: Vec3, val color: String)

/**
 * Everything the 3D view draws, extracted from a [Document] in one pass: the visible solids, plus the
 * grid and axes that give them a place to stand.
 *
 * A *value*, computed per repaint from evaluated nodes only — the same discipline the 2D
 * [SceneRenderer] follows. Nothing is cached, so there is no state that can disagree with the model
 * after an edit, and both back ends (the painter's projector of [Painter3] for tests, WebGL in the
 * browser) consume the identical scene.
 */
class Scene3(val solids: List<SolidItem>, val lines: List<Line3>) {
    val isEmpty: Boolean get() = solids.isEmpty()

    /** Axis-aligned bounds of the solids (the furniture does not count), or null when there are none. */
    fun bounds(): Pair<Vec3, Vec3>? = boundsOf(solids)

    companion object {
        /**
         * The solid palette. A **stable colour per element**, taken by the element's own id rather than
         * by its position in the list, so deleting one solid never recolours the others — a solid that
         * changes colour when a sibling goes reads as a different part.
         */
        val PALETTE =
            listOf("#4e79a7", "#f28e2b", "#59a14f", "#e15759", "#b07aa1", "#76b7b2", "#edc948", "#9c755f")

        val GRID_COLOR = "#dcdcdc"
        val AXIS_X_COLOR = "#c0504d"
        val AXIS_Y_COLOR = "#4f8a3d"
        val AXIS_Z_COLOR = "#3f6fa8"

        /** Half-extent of the ground grid, in cells either side of the origin. */
        const val GRID_HALF = 10

        fun colorFor(elementId: String): String {
            val n = elementId.dropWhile { !it.isDigit() }.toIntOrNull() ?: 0
            return PALETTE[n % PALETTE.size]
        }

        /**
         * The scene of [doc]: every visible element whose value is a solid, plus a ground grid sized to
         * the model.
         *
         * Invalid solids simply contribute nothing, which is OP-3's rule unchanged — a depth dragged to
         * zero makes the part vanish from the 3D view and come back when it is dragged open again.
         */
        fun extract(
            doc: Document,
            ev: Evaluator = Evaluator(),
        ): Scene3 {
            val solids = ArrayList<SolidItem>()
            for (el in doc.elements) {
                if (!el.visible) continue
                val v = ev.valueOf(el.ref) as? SolidValue ?: continue
                if (v.solid.mesh.triangles.isEmpty()) continue
                solids.add(SolidItem(el.id, v.solid.mesh, colorFor(el.id)))
            }
            return Scene3(solids, furniture(gridStepFor(boundsOf(solids))))
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
