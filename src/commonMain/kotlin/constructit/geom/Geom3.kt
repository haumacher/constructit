package constructit.geom

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 3D vector / point in millimetres — the world the 2D→3D seam embeds into (OP-17).
 *
 * Deliberately lean: 3D geometry in this engine is *derived* (a plane frame, a mesh vertex), never
 * drawn or dragged, so it needs arithmetic and nothing else. The analytic layer stays 2D plus a frame,
 * which is what keeps the mesh a sink (OP-9).
 */
data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)

    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)

    operator fun times(s: Double) = Vec3(x * s, y * s, z * s)

    operator fun unaryMinus() = Vec3(-x, -y, -z)

    fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z

    fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)

    fun length() = sqrt(x * x + y * y + z * z)

    fun normalized(): Vec3 {
        val l = length()
        return if (l < EPS) this else Vec3(x / l, y / l, z / l)
    }

    /** The [axis] component — so a measurement can be taken per axis without three near-copies. */
    fun component(axis: Axis3): Double =
        when (axis) {
            Axis3.X -> x
            Axis3.Y -> y
            Axis3.Z -> z
        }

    companion object {
        const val EPS = 1e-9
        val ZERO = Vec3(0.0, 0.0, 0.0)
        val X = Vec3(1.0, 0.0, 0.0)
        val Y = Vec3(0.0, 1.0, 0.0)
        val Z = Vec3(0.0, 0.0, 1.0)
    }
}

/** A world axis — the regular way to ask a solid for one bounding-box number (OP-4). */
enum class Axis3 { X, Y, Z }

/**
 * Which provenance-named face of a feature is meant (OP-8). A *constructed* accessor, not a discovered
 * one: `TOP` is "the sketch plane moved along its normal by the depth", which is a function of the
 * feature's parameters and therefore survives every edit — the topological-naming problem does not
 * arise because nothing is ever re-identified.
 */
enum class SolidFace { TOP, BOTTOM }

/**
 * The **embedding frame** of a sketch (OP-17): a plane through [origin] spanned by the orthonormal
 * pair [u], [v], with normal `u × v`.
 *
 * This is the same concept as a placed group's frame (OP-16) one dimension up, and the reason 2D
 * geometry is *not* made plane-resident: one 2D construction can be embedded on several planes, which
 * is macro-instance semantics applied to the seam.
 */
data class Plane3(val origin: Vec3, val u: Vec3, val v: Vec3) {
    val normal: Vec3 get() = u.cross(v)

    /** Where sketch point [p] lands in the world. */
    fun toWorld(p: Vec2): Vec3 = origin + u * p.x + v * p.y

    /**
     * Where world point [p] lands in this frame's own (u, v) — the inverse of [toWorld] for a point *in*
     * the plane, and the orthogonal projection of one that is not.
     *
     * The frame is orthonormal ([Construction.plane] orthonormalises every one that is built), so this is
     * two dot products and no solve.
     */
    fun toLocal(p: Vec3): Vec2 = Vec2((p - origin).dot(u), (p - origin).dot(v))

    /** Signed distance of [p] from this plane, along its own normal. */
    fun distanceTo(p: Vec3): Double = (p - origin).dot(normal.normalized())

    /** The same frame, moved [d] mm along its own normal. */
    fun translated(d: Double): Plane3 = Plane3(origin + normal * d, u, v)

    /**
     * The same plane with its normal reversed. The flip has to mirror the in-plane frame as well (`v`
     * is negated), because a right-handed frame whose normal points the other way is a mirrored frame —
     * there is no way to flip the normal and keep the 2D coordinates unchanged.
     */
    fun flipped(): Plane3 = Plane3(origin, u, -v)
}

/**
 * A world ray: everything from [origin] along [dir] with `t >= 0`.
 *
 * The one 3D thing a *gesture* needs (edit-in-3D slice 1): a pointer position in the 3D view is a ray,
 * and where it meets the working plane is the 2D coordinate every existing tool already speaks
 * ([Geom3.rayPlane]). [dir] is not required to be unit — [Geom3.rayPlane] returns the parameter in
 * *these* units, so a caller that built the ray from a normalized direction gets millimetres.
 */
data class Ray3(val origin: Vec3, val dir: Vec3) {
    fun at(t: Double): Vec3 = origin + dir * t
}

/**
 * **The seam** (OP-17): 2D result-layer [regions] (OP-14) embedded on a [plane].
 *
 * A separate value rather than a property of the regions, so the 2D engine stays abstract-planar and a
 * region can feed several sketches.
 */
data class Sketch3(val plane: Plane3, val regions: List<Region>)

/**
 * One **section of a loft** (OP-17's third feature): an area on its own plane, or a single point.
 *
 * The point is what makes a pyramid and a cone the same operation as a frustum — the degenerate end of the
 * run, not a special feature — and it is an ordinary constructed position, so dragging the point that places
 * it moves the apex and the solid follows like everything else in the DAG.
 */
sealed interface LoftSection {
    /** An area section: [sketch] is one region on one plane, exactly what `sketchOn` produces. */
    data class Area(val sketch: Sketch3) : LoftSection

    /** A terminal point section — the apex of a pyramid or a cone. */
    data class Apex(val at: Vec3) : LoftSection
}

/**
 * A **guide curve** of a loft: [pieces] read in the 2D coordinates of [plane].
 *
 * A guide is an ordinary drawn curve on an ordinary sketch plane — which is what makes it reachable by
 * clicking (draw it on a datum plane that cuts the sections) and what keeps it parametric: the plane is the
 * space's own plane node, so tilting the datum bends the guide and the loft with it. Where the guide attaches
 * is *not* stored: it is the boundary point it passes through, resolved inside `compute` (a value), which is
 * why a guide that stops honouring its sections makes the loft invalid with a reason instead of silently
 * shaping something else (OP-3).
 */
data class LoftGuide(val plane: Plane3, val pieces: List<ProfileElement>)

/** One mesh triangle, as indices into [Mesh3.vertices], wound counter-clockwise seen from outside. */
data class Tri(val a: Int, val b: Int, val c: Int)

/**
 * An indexed triangle mesh — the **sink** (OP-9): render/print/export only, never lifted back to
 * analytic geometry. Vertices are in insertion order and triangles in emission order, so a mesh is a
 * deterministic function of its feature's parameters (nothing here iterates a hash).
 */
data class Mesh3(val vertices: List<Vec3>, val triangles: List<Tri>) {
    val vertexCount: Int get() = vertices.size
    val triangleCount: Int get() = triangles.size
}

/**
 * One layer of a **prismatic** solid (OP-22): the area [regions] occupies between heights [z0] and [z1],
 * measured along the prism's own axis from its plane's origin.
 *
 * The regions are **polygonal** — a slab only ever comes out of a boolean, and a boolean tessellates its
 * operands first (OP-22, the 2D analog of OP-15's approximated-curve rule).
 */
data class Slab(val regions: List<Region>, val z0: Double, val z1: Double) {
    val height: Double get() = z1 - z0
}

/**
 * **How far a revolution goes round** (OP-17 slice 2, session 63) — and it is a *kind*, not a number.
 *
 * A complete revolution and a partial one are different structure, for the reason OP-14 gives one dimension
 * down: *"a circle is not faked as a full-turn arc whose 0-vs-2π sweep is ambiguous — it carries its own
 * ccw"*. A body swept the whole way round has **no start and no end**, hence no caps and a welded seam; a
 * partial body has two caps and a stated interval. Deciding between them from the *value* of a live
 * parameter — which is what `abs(angle − 2π) ≤ ε` used to do, alone — makes watertightness something a drag
 * can switch on and off, so the graph itself says which one this is: a [Full] revolution has no angle node
 * at all, and no edit of any parameter can open it.
 *
 * The reading of the interval is [Geom3.revolve]'s: the profile is the generator at angle 0, and the body
 * occupies the angles between [Arc.start] and [Arc.end] about the axis.
 */
sealed interface Turn3 {
    /** The whole way round: no start, no end, no caps — and therefore no offset either. */
    data object Full : Turn3

    /**
     * The body between [start] and [end] rad about the axis, **ordered** so that `start <= end`.
     *
     * Ordered because a negative sweep and a positive one over the same two angles are the *same point
     * set walked the other way* — normalizing here is what lets one cap-winding rule serve every
     * combination of signs (see [Geom3.revolve]). Build one with [of], which does the ordering.
     */
    data class Arc(val start: Double, val end: Double) : Turn3 {
        val sweep: Double get() = end - start

        companion object {
            /** The interval a stated [offset] and [angle] mean: `[offset, offset + angle]`, either sign. */
            fun of(
                offset: Double,
                angle: Double,
            ): Arc = Arc(min(offset, offset + angle), max(offset, offset + angle))
        }
    }
}

/**
 * The analytic description of a solid: which feature made it, from which sketch, with which
 * parameters (OP-9 — the analytic layer is the source of truth).
 *
 * Kept in the value next to the mesh so provenance accessors (OP-8) and later a B-rep/STEP export read
 * the *feature*, not the triangles.
 */
sealed interface Feature3 {
    /**
     * The 2D areas this feature's *plan* shows: what the canvas draws as a footprint hint and picks a
     * solid by (OP-17). An accessor rather than a field, because what "the plan" is differs per feature —
     * the sketch for a swept one, the stack of slabs for a prismatic one — and every caller wants the
     * same answer to the same question.
     */
    val footprint: List<Region>

    /** A prism: [sketch] swept [depth] mm along its plane's normal. */
    data class Extrusion(val sketch: Sketch3, val depth: Double) : Feature3 {
        override val footprint: List<Region> get() = sketch.regions
    }

    /** [sketch] swept [turn] about the in-plane axis through [axisOrigin] along [axisDir]. */
    data class Revolution(
        val sketch: Sketch3,
        val axisOrigin: Vec2,
        val axisDir: Vec2,
        val turn: Turn3,
    ) : Feature3 {
        override val footprint: List<Region> get() = sketch.regions
    }

    /**
     * A **prismatic solid** (OP-22): a stack of [slabs] along [plane]'s normal, each a polygonal area
     * over its own height range, the ranges disjoint and ascending.
     *
     * This is the form same-axis booleans are **closed** under, which is the whole reason it exists: an
     * extrusion is the one-slab case, and the result of subtracting/uniting/intersecting two prisms is
     * another prism, so results compose without ever leaving the exact algebra. A plain extrude keeps its
     * analytic [Extrusion] form instead (its arcs are still exact circles); the conversion happens only
     * when a boolean needs it — see [Geom3.prismatic].
     *
     * Which solids were combined is *not* recorded here: that is the op node's input list, and in this
     * model identity is the node (OP-8). The feature carries geometry, not history.
     */
    data class Prism(val plane: Plane3, val slabs: List<Slab>) : Feature3 {
        override val footprint: List<Region> get() = slabs.flatMap { it.regions }

        val minZ: Double get() = slabs.minOf { it.z0 }
        val maxZ: Double get() = slabs.maxOf { it.z1 }
    }

    /**
     * The result of a **general** boolean — one computed by the mesh engine (Manifold, OP-9) because the
     * operands had no common axis and the exact prismatic algebra (OP-22) therefore had no answer.
     *
     * It carries [kind] and *nothing else*, and that is the OP-9 mesh-is-a-sink rule showing up in the type
     * system rather than in a comment: a mesh boolean has no analytic form, so there is no plane to sketch
     * on ([Geom3.facePlane] refuses), no slab to cut ([Geom3.sectionAt] refuses), and no plan to draw
     * ([footprint] is empty). What it does have is a mesh — renderable, measurable, printable — and it is a
     * legal operand of the next boolean, which then also takes the general path. Which solids were combined
     * is the op node's input list, not a field here (identity is the node, OP-8).
     */
    data class MeshBoolean(
        val kind: BoolOp,
        /**
         * The outline this body projects to in the space it is shown in ([Silhouette]) — **the silhouette
         * plan**, and empty for the general boolean, which has no space of its own to be drawn in.
         *
         * *The parked note, answered where it applies* (session 55, retired in part by session 71). What was
         * parked is that a mesh boolean's result has no plan, so it draws no footprint hint and cannot be
         * picked in the plan — a body the drawing can build but not click on. Two honest answers were named
         * there: the silhouette an imported body already gets, or 3D picking. The **edge blend** (slice 2)
         * had to have one of them, because its own result is a `MeshBoolean` and a second blend has to be
         * able to pick the first one's body — so it takes the silhouette, computed once by the node that
         * knows which plane that is (`Construction.blend`), exactly as [Imported.plan] and [Sweep.plan] are.
         *
         * *Narrowed, not retired, by slice 3.* An ordinary blend is now a [Blend] and draws its base's own
         * plan, which is analytic and forces no mesh; what still arrives here is a blend applied to a body
         * a general boolean made (a fused part), which has no plan of its own and takes the silhouette
         * exactly as above. So the mechanism stays proven and stays used, on the narrower set.
         *
         * It is deliberately **not** filled by the ordinary boolean tools: giving them a plan is OP-22's
         * decision to make and would change what every cross-axis boolean draws today (see
         * `BooleanCrossSpaceTest`, which pins the miss). The field is here, the mechanism is proven, and the
         * general case is one line away when that package wants it.
         */
        val plan: List<Region> = emptyList(),
    ) : Feature3 {
        override val footprint: List<Region> get() = plan
    }

    /**
     * A body this kernel did not construct: a mesh **read from a file** (the JT import, OP-9), named by
     * the [source] it came from.
     *
     * The other half of the partition [MeshBoolean]'s note describes, and the one that note already
     * anticipated ("mesh-only operations (offset/shell/hull, imported meshes)"). It carries a mesh and a
     * provenance string and nothing else, deliberately: an imported body has no sketch, no depth and no
     * axis, so there is no plane to sketch on, no slab to cut and no plan to draw — every provenance
     * accessor refuses it by name rather than inventing a reading of triangles, which is what
     * *watertight-or-refused* buys and what *never rediscover* forbids spending.
     *
     * What it *is* is a solid like any other where that word means something measurable: it has a volume,
     * it renders, it exports, it can be **placed** — a rigid move leaves it exactly this feature with its
     * mesh somewhere else — and, **while it is closed**, it prints and it is a legal boolean operand. Which
     * of those it can do is [openShell]'s to say.
     */
    data class Imported(
        val source: String,
        /**
         * The outline this body projects to in the space it is shown in ([Silhouette]) — empty for the raw
         * literal, which has no space of its own to be drawn in.
         *
         * Stored rather than derived, and that is the load-bearing half: [footprint] is asked on every
         * repaint and of every element on every click, so it must be a field read. The projection is done
         * **once**, where the plane is known — by the placement that puts the body in a space (see
         * `Construction.placeSolid`) — which is also the only place that *can* do it, since a projection
         * without a plane is not defined.
         */
        val plan: List<Region> = emptyList(),
        /**
         * Why this body is an **open shell** — a surface that does not close, or does not close consistently
         * — or null when it is a closed solid ([constructit.geom.Watertight]).
         *
         * **Derived, never recorded.** It is a pure function of the mesh the literal carries, computed once
         * where that value is built (`Construction.importedSolid`), so a reload derives the same answer from
         * the same triangles and no stored flag can drift from the geometry it describes. A rigid placement
         * carries it through unchanged, because a motion cannot open or close a surface.
         *
         * Deliberately **not** called *invalid*: OP-3's invalid means a node has **no value**, and this body
         * has one — it displays, it places, it measures, it exports to GLB and JT. What it cannot do is what
         * needs an inside: it is refused by the two print writers and by every boolean, each in its own
         * words. That split is the user's design (session 34), and the reason is theirs too: refusing the
         * import outright "is necessary if the goal is printing, but useless when the goal is
         * re-engineering an imported geometry — and too restrictive, if the goal is only arranging and
         * displaying".
         *
         * **This changes nothing for constructed solids.** Everything the kernel builds is watertight by
         * construction (OP-9), and that doctrine is untouched: this field can only ever be non-null on a body
         * that came from outside.
         */
        val openShell: String? = null,
    ) : Feature3 {
        override val footprint: List<Region> get() = plan
    }

    /**
     * A **sweep** (OP-26, step 2): [profile] carried along [path] in the rotation-minimizing moving frame,
     * with the start reference derived from [up] and the frame turned by [roll] and [twist].
     *
     * The one solid whose *axis* is a curve rather than a straight line or a circle, which is what a cable, a
     * conduit, a handrail, a moulding, a gutter and a duct all are — and what a prism and a revolve between
     * them cannot express. Analytic, like every other feature here: the path is a chain of exact pieces, the
     * profile is a region with its own arcs and holes, and the four frame numbers are what a later B-rep
     * writer would need, so nothing about the shape has to be read back out of the triangles (OP-9).
     *
     * [up] is a **resolved direction**, not a plane: the sweep node reads the normal of the space its path is
     * parented to and hands the value over here, exactly as a revolve hands over its axis in the sketch's own
     * coordinates. What that buys is that the feature is self-contained — `(path, profile, up, roll, twist)`
     * is enough to rebuild the identical frame and hence the identical mesh — and that a rigid placement
     * needs only to turn one more vector.
     *
     * [plan] is the outline this body projects to in the space it is shown in, computed once by the node that
     * knows which plane that is ([Silhouette], the same field and the same reason [Imported] has one): a
     * sweep has no prismatic reading, so there is no sketch to draw as a footprint, and a projection with no
     * plane is not defined.
     *
     * [carry] is how the section travels — riding the frame or staying parallel to its own space (see
     * [CarryMode]). A *sweep* always states the rotating one, which is why it is defaulted; the **swept cut**
     * (OP-22's extension, step 2) states either, and the field is what keeps `(path, profile, up, roll,
     * twist, carry)` enough to rebuild the identical body rather than the feature describing one carry while
     * the mesh shows the other.
     */
    data class Sweep(
        val path: Path3,
        val profile: SweepProfile,
        val up: Vec3,
        val roll: Double,
        val twist: Double,
        val plan: List<Region> = emptyList(),
        val carry: CarryMode = CarryMode.ROTATING,
    ) : Feature3 {
        override val footprint: List<Region> get() = plan
    }

    /**
     * A **loft**: the ordered [sections] blended pairwise, optionally shaped by [guides] (OP-17).
     *
     * The one solid class a prism, a revolve and their booleans cannot make — the one whose cross-section
     * *changes* along the run. A pyramid is `[area, apex]`, a cone `[circle, apex]`, a frustum
     * `[area, area]`, and three or more sections blend piecewise between consecutive pairs; nothing here is a
     * case, which is the point (the queue entry's *"pyramids are the example, not the feature"*).
     *
     * [seams] is the **one discrete choice** this feature carries: per section, which of its boundary pieces
     * the correspondence starts at (an index into the loop's pieces, in provenance order — the same durable
     * name `sideFace` uses). Scored once from the gesture and then persisted in the tool step's `signs=`
     * (OP-1/OP-18), because re-scoring it on replay would let a rotated section come back as a different
     * solid. What is *not* a choice is the **winding**: it is fixed by construction so the shell comes out
     * closed and outward — see [Geom3.loft].
     *
     * The plan it shows is its **first area section**, which is the section a footprint hint is drawn from and
     * the coordinates a pick of the solid measures against.
     */
    data class Loft(
        val sections: List<LoftSection>,
        val seams: List<Int>,
        val guides: List<LoftGuide>,
    ) : Feature3 {
        override val footprint: List<Region>
            get() = (sections.firstOrNull { it is LoftSection.Area } as? LoftSection.Area)?.sketch?.regions ?: emptyList()

        /**
         * OP-15's honesty class, read off the feature: **exact** while every section boundary and every guide
         * is made of straight pieces (the facets are then the solid, and its volume is analytic), and
         * **approximated** as soon as one of them is curved — a circle section is a polygon of chords, and a
         * curved guide's rails are sampled, exactly the bargain a Bézier offset already makes.
         */
        val approximated: Boolean
            get() =
                sections.any { s -> s is LoftSection.Area && s.sketch.regions.any { curvedBoundary(it) } } ||
                    guides.any { g -> g.pieces.any { it !is ProfileElement.Seg } }
    }

    /**
     * A **dress-up feature**: [base] with a [kind] blend of [size] run along the edges [targets] names
     * (session 71, slice 3 — see [Blend3] for the construction and DESIGN.md for the ledger).
     *
     * **What makes it a feature of its own rather than the mesh boolean slice 2 left behind**: its face
     * list *extends* the base's. Every face of [base] keeps its **index** and gets its outline corrected
     * where the blend consumed one of its edges; one band is appended per blended edge, the way an
     * extrusion's caps append after its sides. So a dressed part still answers *sketch on this face*,
     * still offers a working plane's section its structural inputs, and still refuses by name where the
     * base did — none of which a `MeshBoolean` can do (OP-9's sink rule). See [Section3.faces] and
     * [Section3.edges], both of which delegate here.
     *
     * **It wraps a feature, not a solid, and that is what keeps it pure.** `(base, targets, kind, size,
     * choices)` is enough to rebuild the identical body — the base's own mesh, the wedge swept along each
     * edge, the boolean — so [Solid3]'s "the mesh is a pure function of the feature" holds verbatim and a
     * reload derives the same triangles from the same numbers. [choices] are the discrete signs the
     * gesture scored once and the step restates (OP-1/OP-18); nothing here is re-scored.
     *
     * **The mesh is still slice 2's sweep-and-boolean** and deliberately so (the decision is recorded in
     * DESIGN.md with its alternative): the mesh is a *sink*, so answering faces analytically while the
     * triangles come off the same route costs no honesty and no second emitter to keep in step.
     *
     * [footprint] is the **base's**, not a silhouette of the triangles: a blend is a dressing, so the plan
     * it draws and is picked by is the plan of the body it dresses — which is analytic, free, and does not
     * force the mesh (slice 2's `MeshBoolean.plan` had to build one, since a mesh boolean has no other
     * reading of itself). What it costs is stated: a blend that rounds an **upright** rounds the plan
     * outline too, and the hint still shows the base's sharp corner.
     */
    data class Blend(
        val base: Feature3,
        /** Which of the base's edges are blended — indices into [Section3.edges] of [base], in order. */
        val targets: List<Int>,
        val kind: BlendKind,
        val size: Double,
        val choices: List<BlendChoice>,
    ) : Feature3 {
        override val footprint: List<Region> get() = base.footprint
    }
}

/** Whether any boundary piece of [region] is curved — OP-15's question, asked structurally. */
private fun curvedBoundary(region: Region): Boolean =
    (listOf(region.outer) + region.holes).any { l -> l.elements.any { it !is ProfileElement.Seg } }

/**
 * A solid: its analytic [feature] **plus** the [mesh] derived from it — derived **when it is first asked
 * for**, and remembered from then on.
 *
 * The mesh rides inside the value because it is derived data, not a separate object with a life of its
 * own — and `SolidValue` stays a distinct type from a future mesh-only value, which is the OP-9
 * partition the type system enforces: analytic-preserving features on one side, mesh-only operations
 * (offset/shell/hull, imported meshes) on the other.
 *
 * **Why the mesh is derived on demand.** A drag in the plan recomputes every node in the edited cone
 * (OP-5), and a body whose triangles nobody is looking at was paying for them anyway: a tube along a
 * three-turn helix is tens of thousands of triangles per mouse move, spent on a picture the 2D canvas
 * never draws. So the derivation moved *inside* the value: [feature] is computed eagerly by the node, the
 * triangles are computed by the first consumer that needs triangles (the 3D scene, a volume, a section,
 * an export, `assertManifold`) and then handed to every consumer after it.
 *
 * **Purity is untouched, and the argument is exactly this.** The mesh is a pure function of the feature
 * (that is what makes every `Feature3` self-contained — see [Feature3.Sweep]), so the same solid always
 * yields the same triangles no matter when they are built or how often they are asked for. *Laziness
 * inside an immutable value is not state*: nothing outside can observe the difference except by timing,
 * the value never changes what it says, and recompute, undo, reload and a byte-equal save are therefore
 * unaffected. What changed is *when* triangles are built, never *which*.
 *
 * **Its identity is reference identity, and that is what the memo always wanted.** This was a data class,
 * so `equals`, `hashCode` and `toString` all read the mesh — comparing two solids compared two triangle
 * lists, and printing one printed them. OP-5's memo is `===` on the value object and never needed more
 * (see [constructit.core.Node.computeMemoized]), and no consumer asks whether two solids are *equal*: two
 * solids are the same solid when they are the same value, which is a pointer compare that cannot force a
 * derivation. [toString] names the feature for the same reason — a debug print must not build a mesh.
 */
class Solid3 private constructor(
    val feature: Feature3,
    private val cell: MeshCell,
) {
    /**
     * The triangles — built now if this is the first ask, taken from the memo otherwise.
     *
     * Not thread-safe on purpose: the browser runs the engine on one thread (OP-12), so a guarded lazy
     * would be a monitor paid on every read for a race that cannot happen. The JVM suite is
     * single-threaded per document for the same reason [constructit.core.Node]'s memo is.
     */
    val mesh: Mesh3 get() = meshAt(MeshQuality.FINE)

    /**
     * The triangles **at [quality]** — the one door a picture's fineness can enter by (slice B).
     *
     * Two memoized levels, not a continuum, because a gesture has two states: running, and settled. Asking
     * twice at one level builds once; asking at the other level does not disturb the first. `Solid3.mesh` is
     * `meshAt(FINE)`, so every consumer that says nothing gets the fine mesh — **the law is the default**,
     * and a coarse triangle can reach a volume, a section, a boolean, an export or `assertManifold` only
     * through a call that no such consumer makes (see [MeshQuality]).
     *
     * **What varies with quality, and what may not.** Coarse multiplies the chord tolerance
     * ([GeomMath.effectiveTol]) of the curves a body's *surface* is made of — a section's or a profile's
     * boundary, and a revolution's angular step count. What it never varies is the **run stations of a
     * sweep**. Those are computed eagerly, from the feature, before any triangle exists, and three separate
     * things already read them: the plan hint and therefore the 2D pick target ([Silhouette.ofSwept]), a
     * station plane's transported frame ([Frames3.baseSteps], `internal` for exactly that reason), and the
     * self-intersection refusals. Coarsening them would move a pick target with a rendering choice, roll a
     * datum plane the user built on, and let the picture show a fold the refusal said was not there — and it
     * would cost a second transport walk, which is the expensive half of a sweep in the first place. So the
     * coarse sweep is the *same run* with a cheaper ring, which is also why it is recognisably the same body.
     *
     * **Where COARSE simply is FINE**, deliberately and at no cost (the same `Mesh3` object, so the identity
     * swap in `SceneSync` sees nothing change): a body whose triangles came out of a file or out of the
     * general boolean engine ([of] — there is no coarser statement of triangles one already holds); a
     * **prism**, whose cost is its region algebra and the global corner set every ring is conformed to
     * rather than its chords, so a coarse level would have to redo all of the expensive part to save the
     * cheap one; and a **loft**, whose row count comes from its guides' own sampled polylines inside the
     * correspondence plan, so the same applies. Each is stated rather than silently fine.
     */
    fun meshAt(quality: MeshQuality): Mesh3 = cell.mesh(quality)

    /** Whether the triangles are already in hand — what the mesh-build instrument reads (see [meterTo]). */
    val meshBuilt: Boolean get() = meshBuiltAt(MeshQuality.FINE)

    /** Whether the triangles **at [quality]** are already in hand. */
    fun meshBuiltAt(quality: MeshQuality): Boolean = cell.builtAt(quality) != null

    /**
     * Whether this body's triangles **coarsen at all** — false for the bodies where COARSE *is* FINE, listed
     * at [meshAt]. Asked by a placement, so that moving a one-level body into a space does not invent a
     * second level for it and pay for the same triangles twice.
     */
    val coarsens: Boolean get() = cell.coarsens

    /**
     * This solid's mesh under a **different feature**, sharing the very same derivation.
     *
     * The one legitimate reason to restate a feature without restating the body: a plan hint stated in
     * some plane's coordinates (an [Feature3.Imported] outline, a [Feature3.Sweep]'s) is re-projected when
     * the body is placed, and the triangles are unaffected by which plane one looks at them from. Sharing
     * the cell is what keeps that free — the mesh is built at most once whichever of the two is asked, and
     * both hand out the same `Mesh3` object, which is what `SceneSync`'s identity swap is keyed on.
     */
    fun restated(feature: Feature3): Solid3 = Solid3(feature, cell)

    /**
     * Tell [count] when this solid's mesh is derived — **the instrument**, set once, by the node that
     * produced this value ([constructit.core.Node.meshCount]).
     *
     * An observer of a derivation, never a second definition of it: the triangles are the same triangles
     * whether anybody is listening. First caller wins, so a value handed on unchanged (an identity
     * placement) keeps counting against the node that actually built it.
     */
    fun meterTo(count: (MeshQuality) -> Unit) {
        if (cell.meter == null) cell.meter = count
    }

    override fun toString(): String = "Solid3($feature)"

    /**
     * The one mutable thing here: the memo cell, shared by every restatement of one body — now with **one
     * slot per quality** ([MeshQuality]), since a picture and a number want different triangles of the same
     * value and neither may evict the other.
     */
    private class MeshCell(
        var fine: Mesh3?,
        private val derive: ((MeshQuality) -> Mesh3)?,
        /** Whether this body has two levels at all — see [Solid3.coarsens]. */
        val coarsens: Boolean,
    ) {
        var coarse: Mesh3? = null
        var meter: ((MeshQuality) -> Unit)? = null

        /** The level a given ask really lands on: a one-level body answers every ask with its fine mesh. */
        private fun level(quality: MeshQuality): MeshQuality = if (coarsens) quality else MeshQuality.FINE

        fun builtAt(quality: MeshQuality): Mesh3? = if (level(quality) == MeshQuality.FINE) fine else coarse

        fun mesh(quality: MeshQuality): Mesh3 {
            val q = level(quality)
            builtAt(q)?.let { return it }
            val d = derive ?: return fine!!
            val m = d(q)
            if (q == MeshQuality.FINE) fine = m else coarse = m
            meter?.invoke(q)
            return m
        }
    }

    companion object {
        /**
         * A solid whose mesh is **already in hand** — an imported body, whose triangles came out of a file
         * rather than out of a feature, and a mesh-only boolean, whose refusal is the engine's verdict on
         * the triangles themselves (see the note in `Construction.booleanValue`).
         */
        fun of(
            feature: Feature3,
            mesh: Mesh3,
        ): Solid3 = Solid3(feature, MeshCell(mesh, null, coarsens = false))

        /**
         * A solid whose mesh is **derived from its feature on demand** — every constructed one.
         *
         * [mesh] must be refusal-free: everything that can make a body impossible is decided *before* this
         * is called, so the node is invalid with a reason at evaluation time and nothing is ever half-built
         * (OP-3, OP-9). That is a real constraint on every op above, and the audit of it is this slice's
         * substance — see the refusal table in the design record.
         *
         * It takes the [MeshQuality] it is being asked at (slice B) and **must be refusal-free at both** —
         * which is why an op that coarsens by re-tessellating its own section falls back to the fine work it
         * already holds if the coarser tessellation cannot be triangulated. A picture may not refuse: every
         * refusal is the feature's and was decided above, at the fine rule, so the worst a coarse ask can do
         * is get the fine answer.
         */
        fun derived(
            feature: Feature3,
            mesh: (MeshQuality) -> Mesh3,
        ): Solid3 = Solid3(feature, MeshCell(null, mesh, coarsens = true))

        /**
         * A solid derived on demand that has **one** level: a coarse ask gets the fine mesh, the same object,
         * and nothing is built twice ([Solid3.meshAt] lists which bodies these are and why).
         *
         * Stating it here rather than letting a two-level provider quietly return the same triangles twice is
         * the whole difference between "this body does not coarsen" and "this body coarsens to itself": the
         * second builds a second identical mesh on every coarse ask, charges the instrument for it, and hands
         * `SceneSync` a new object to re-upload.
         */
        fun derivedFine(
            feature: Feature3,
            mesh: () -> Mesh3,
        ): Solid3 = Solid3(feature, MeshCell(null, { mesh() }, coarsens = false))
    }
}

/**
 * The 3D kernel: region tessellation, triangulation with holes, and the two features of this slice.
 *
 * Every function here is a pure function of values and returns `result to reason` rather than throwing,
 * so a feature that cannot be built becomes an **invalid node** with a reason and heals when its
 * parameters move back (OP-3).
 */
object Geom3 {
    /**
     * Distance below which two mesh vertices are the same vertex (mm). Caps and side walls are built
     * from the *same* tessellated points, so coincident vertices are normally bit-identical; this is a
     * safety net that also snaps a revolve profile onto its axis, and it is orders of magnitude below
     * the tessellation tolerance so it can never merge two genuinely distinct points.
     */
    const val WELD_TOL = 1e-7

    /** Areas below this (mm²) count as zero — a degenerate ear, a collinear vertex, a sliver. */
    private const val AREA_EPS = 1e-12

    /**
     * How far along [ray] it meets [plane], or **null** when it never does *ahead of the origin*: the ray
     * runs parallel to the plane (no meeting at all), or the plane lies behind it.
     *
     * The whole of the ray seam's arithmetic (edit-in-3D slice 1), and the reason it returns null rather
     * than an infinity: a gesture in the 3D view whose ray misses the working plane has no plane
     * coordinates, and a NaN handed to the editor would place geometry at a position no one asked for.
     * The caller says so instead (see `PlanePerspective.toPlane`).
     *
     * [PARALLEL_EPS] is compared against the *normalized* cosine, so it is an angle threshold (about
     * 6e-8 rad) rather than a length one — grazing a plane at less than that is not a pick, it is noise.
     */
    fun rayPlane(
        ray: Ray3,
        plane: Plane3,
    ): Double? {
        val n = plane.normal.normalized()
        val d = ray.dir
        val len = d.length()
        if (len <= Vec3.EPS) return null
        val denom = n.dot(d) / len
        if (abs(denom) < PARALLEL_EPS) return null
        val t = n.dot(plane.origin - ray.origin) / n.dot(d)
        return if (t > 0.0) t else null
    }

    /** Below this |cos| between a ray and a plane, the two count as parallel (see [rayPlane]). */
    const val PARALLEL_EPS = 1e-9

    /**
     * How far along [ray] it first meets [mesh], or **null** when it misses it altogether — the ray seam one
     * operand up, and what makes a body **clickable in the 3D view** (OP-13's 2D/3D split: the ray answers
     * what the plan cannot).
     *
     * Möller–Trumbore per triangle, nearest positive hit wins. Brute force over the triangles, deliberately:
     * this runs **once per click**, not per frame, so the acceleration structure a renderer would want here
     * would be state to keep in step with a model that changes under every edit — the same argument
     * `PlanePerspective` makes for building its matrix per projection rather than caching it beside a mutable
     * camera. A part of a hundred thousand triangles costs under a millisecond of a gesture nobody can make
     * twice in that time.
     *
     * The barycentric tests are slack by [BARY_EPS] so that a ray through an **edge** shared by two
     * triangles hits both rather than slipping between them. A watertight mesh has no gaps; a strict test
     * would invent some, and a click that fell through the middle of a body would be the one failure the
     * user cannot explain. Barycentric coordinates are dimensionless, so that slack is scale-free — it is
     * worth about a billionth of an edge, whatever the drawing's size.
     *
     * **The parallel cull is relative, and it has to be.** `det` is `|e1||e2||d|·sinθ`, so an absolute
     * threshold would mean two different things at two scales: it would cull every small triangle outright
     * (a sliver is a legal `Mesh3`, and culling it is how a ray invents the gap this promises not to), and
     * at building scale it would sit *below* the cancellation noise in `det` and accept a grazing triangle
     * whose `u`, `v` and `t` are then arithmetic dust — which is worse than a miss, because a spurious small
     * `t` wins the nearest-hit comparison and steals the pick from the body really under the cursor.
     * Dividing by the three lengths makes the test what it always meant: the sine of an angle.
     */
    fun rayMesh(
        ray: Ray3,
        mesh: Mesh3,
    ): Double? {
        val d = ray.dir
        val dLen = d.length()
        if (dLen <= Vec3.EPS) return null
        var best: Double? = null
        for (tri in mesh.triangles) {
            val a = mesh.vertices[tri.a]
            val e1 = mesh.vertices[tri.b] - a
            val e2 = mesh.vertices[tri.c] - a
            val h = d.cross(e2)
            val det = e1.dot(h)
            val scale = e1.length() * e2.length() * dLen
            if (scale <= 0.0 || abs(det) <= PARALLEL_EPS * scale) continue
            val inv = 1.0 / det
            val s = ray.origin - a
            val u = inv * s.dot(h)
            if (u < -BARY_EPS || u > 1.0 + BARY_EPS) continue
            val q = s.cross(e1)
            val v = inv * d.dot(q)
            if (v < -BARY_EPS || u + v > 1.0 + BARY_EPS) continue
            val t = inv * e2.dot(q)
            if (t > 0.0 && (best == null || t < best)) best = t
        }
        return best
    }

    /** How far outside a triangle a ray may land and still count as hitting it — see [rayMesh]. */
    const val BARY_EPS = 1e-9

    // ---- vertex welding: an indexed mesh, in deterministic insertion order ----

    /**
     * Accumulates an indexed mesh. Vertices are welded on a lattice of [WELD_TOL] boxes and looked up
     * in the 27-box neighbourhood, so a coordinate landing just across a box boundary still finds its
     * twin; the scan order is fixed, so the result is deterministic. Indices are handed out in
     * insertion order — never in hash order — which is what makes a mesh byte-comparable.
     */
    private class MeshBuilder {
        private val vertices = ArrayList<Vec3>()
        private val buckets = HashMap<Long, MutableList<Int>>()
        private val tris = ArrayList<Tri>()

        private fun cell(v: Double): Long = round(v / WELD_TOL).toLong()

        private fun hash(
            i: Long,
            j: Long,
            k: Long,
        ): Long = i * 73856093L xor j * 19349663L xor k * 83492791L

        fun vertex(p: Vec3): Int {
            val ci = cell(p.x)
            val cj = cell(p.y)
            val ck = cell(p.z)
            for (di in -1L..1L) {
                for (dj in -1L..1L) {
                    for (dk in -1L..1L) {
                        val list = buckets[hash(ci + di, cj + dj, ck + dk)] ?: continue
                        for (idx in list) {
                            if ((vertices[idx] - p).length() <= WELD_TOL) return idx
                        }
                    }
                }
            }
            val idx = vertices.size
            vertices.add(p)
            buckets.getOrPut(hash(ci, cj, ck)) { ArrayList() }.add(idx)
            return idx
        }

        /**
         * Emit a triangle, **dropping** it when two of its corners weld together. That is not sloppiness
         * but the axis case: a revolve profile touching its axis makes every quad of that strip collapse
         * to a point, and the closed shell is exactly the one with those triangles left out.
         */
        fun triangle(
            a: Int,
            b: Int,
            c: Int,
        ) {
            if (a == b || b == c || a == c) return
            tris.add(Tri(a, b, c))
        }

        fun triangle(
            a: Vec3,
            b: Vec3,
            c: Vec3,
        ) = triangle(vertex(a), vertex(b), vertex(c))

        fun build(): Mesh3 = Mesh3(vertices.toList(), tris.toList())
    }

    // ---- tessellation: a region as polygons, in the sketch's own 2D coordinates ----

    /**
     * A [Region] as polygons: [outer] counter-clockwise, each hole clockwise (OP-14's convention,
     * preserved). Each list holds the distinct corners of a closed polygon — the closing point is *not*
     * repeated.
     */
    data class TessRegion(val outer: List<Vec2>, val holes: List<List<Vec2>>)

    /**
     * The area a tessellated region actually encloses — less than the region's exact area by each arc's
     * sagitta. The honest number to compare a mesh volume against, so the tests can state both: exact
     * agreement with the polygons the mesh is made of, and agreement with the exact area to within the
     * tessellation tolerance.
     */
    fun tessArea(t: TessRegion): Double = polygonArea(t.outer) + t.holes.sumOf { polygonArea(it) }

    /** A loop as its polyline of distinct corners, keeping the loop's own traversal direction. */
    fun tessellateLoop(
        loop: Loop,
        tolMm: Double = GeomMath.TESS_TOL_MM,
        quality: MeshQuality = MeshQuality.FINE,
    ): List<Vec2> {
        val pts = ArrayList<Vec2>()
        for (e in loop.elements) {
            val piece = GeomMath.tessellatePiece(e, tolMm, quality)
            for (p in piece) {
                if (pts.isEmpty() || (p - pts.last()).length() > WELD_TOL) pts.add(p)
            }
        }
        while (pts.size > 1 && (pts.first() - pts.last()).length() <= WELD_TOL) pts.removeAt(pts.size - 1)
        return pts
    }

    fun tessellateRegion(
        region: Region,
        tolMm: Double = GeomMath.TESS_TOL_MM,
        quality: MeshQuality = MeshQuality.FINE,
    ): Pair<TessRegion?, String?> {
        val outer = tessellateLoop(region.outer, tolMm, quality)
        if (outer.size < 3) return null to "the outer boundary tessellates to fewer than three corners"
        val holes = ArrayList<List<Vec2>>(region.holes.size)
        for (h in region.holes) {
            val poly = tessellateLoop(h, tolMm, quality)
            if (poly.size < 3) return null to "a hole tessellates to fewer than three corners"
            holes.add(poly)
        }
        // OP-14 normalises the loops, so this only re-states the convention in polygon terms.
        val o = if (polygonArea(outer) >= 0.0) outer else outer.reversed()
        val hs = holes.map { if (polygonArea(it) <= 0.0) it else it.reversed() }
        return TessRegion(o, hs) to null
    }

    /**
     * [regions] tessellated at [quality] — or **null** where any of them cannot be tessellated that coarsely.
     *
     * The coarse half of a provider ([Solid3.derived]), and null is not a refusal: every refusal about these
     * regions was decided at the fine rule, before the solid existed. A coarser polygon with fewer than three
     * corners is a statement about the *picture* being asked for, so the caller keeps the fine tessellation it
     * already holds and the body is drawn exactly as it settles. Nothing downstream can tell, and nothing
     * upstream is consulted twice.
     */
    internal fun tessAt(
        regions: List<Region>,
        tolMm: Double,
        quality: MeshQuality,
    ): List<TessRegion>? {
        val out = ArrayList<TessRegion>(regions.size)
        for (r in regions) out.add(tessellateRegion(r, tolMm, quality).first ?: return null)
        return out
    }

    /** [tessAt] with each region's cap triangles too — null where either step will not go through. */
    internal fun preparedAt(
        regions: List<Region>,
        tolMm: Double,
        quality: MeshQuality,
    ): List<Pair<TessRegion, List<Tri3>>>? {
        val tess = tessAt(regions, tolMm, quality) ?: return null
        val out = ArrayList<Pair<TessRegion, List<Tri3>>>(tess.size)
        for (t in tess) out.add(t to (triangulate(t).first ?: return null))
        return out
    }

    fun polygonArea(poly: List<Vec2>): Double {
        var s = 0.0
        for (i in poly.indices) s += poly[i].cross(poly[(i + 1) % poly.size])
        return s / 2.0
    }

    // ---- triangulation with holes: the hard kernel ----

    /**
     * Triangulate a tessellated region into counter-clockwise triangles.
     *
     * Two deterministic steps, because a cap that re-triangulated differently after a parameter edit
     * would make the mesh stop being a pure function of the parameters (OP-15's rule: determinism is
     * the load-bearing property):
     *
     * 1. **Hole bridging.** Each hole is spliced into the outer polygon along a bridge traversed twice,
     *    turning "outer plus holes" into one weakly-simple polygon. Holes are taken in order of their
     *    rightmost corner (ties broken upward, then by input order), and the bridge partner is the
     *    *nearest* outer-polygon corner (ties broken by index) that the bridge can see: the bridge must
     *    cross no boundary edge and its midpoint must lie inside the material.
     * 2. **Ear clipping.** Scanned from index 0 every time, so the same polygon always yields the same
     *    triangles. Collinear corners are dropped rather than emitted, and a pass that finds no valid
     *    ear clips the most convex corner instead — progress is guaranteed, so a near-degenerate sliver
     *    cannot hang the mesh.
     */
    fun triangulate(t: TessRegion): Pair<List<Tri3>?, String?> {
        val (merged, why) = bridgeHoles(t.outer, t.holes)
        if (merged == null) return null to (why ?: "cannot triangulate")
        return earClip(merged)
    }

    /** A triangle of the cap, in sketch coordinates. */
    data class Tri3(val a: Vec2, val b: Vec2, val c: Vec2)

    private fun anchorIndex(poly: List<Vec2>): Int {
        var best = 0
        for (i in poly.indices) {
            val p = poly[i]
            val q = poly[best]
            if (p.x > q.x || (p.x == q.x && p.y > q.y)) best = i
        }
        return best
    }

    private fun bridgeHoles(
        outer: List<Vec2>,
        holes: List<List<Vec2>>,
    ): Pair<List<Vec2>?, String?> {
        if (holes.isEmpty()) return outer to null
        val anchors = holes.map { anchorIndex(it) }
        val order =
            holes.indices.sortedWith(
                compareByDescending<Int> { holes[it][anchors[it]].x }
                    .thenByDescending { holes[it][anchors[it]].y }
                    .thenBy { it },
            )
        var merged = outer
        for ((done, hi) in order.withIndex()) {
            val hole = holes[hi]
            val m = anchors[hi]
            val M = hole[m]
            val pending = order.drop(done).map { holes[it] }
            val candidates = merged.indices.sortedWith(compareBy({ (merged[it] - M).length() }, { it }))
            var spliced = false
            for (j in candidates) {
                if (!bridgeIsClear(M, merged[j], merged, pending)) continue
                merged = splice(merged, j, hole, m)
                spliced = true
                break
            }
            if (!spliced) return null to "no bridge from hole ${hi + 1} to the outer boundary is visible"
        }
        return merged to null
    }

    /** Insert [hole] (starting at its corner [m]) into [outer] along the bridge to `outer[j]`. */
    private fun splice(
        outer: List<Vec2>,
        j: Int,
        hole: List<Vec2>,
        m: Int,
    ): List<Vec2> {
        val out = ArrayList<Vec2>(outer.size + hole.size + 2)
        for (i in 0..j) out.add(outer[i])
        for (k in hole.indices) out.add(hole[(m + k) % hole.size])
        out.add(hole[m])
        for (i in j until outer.size) out.add(outer[i])
        return out
    }

    /**
     * Can the bridge [a]–[b] be drawn: it crosses no edge of [poly] or of any [pending] hole, and its
     * midpoint is material (inside [poly], outside every hole).
     */
    private fun bridgeIsClear(
        a: Vec2,
        b: Vec2,
        poly: List<Vec2>,
        pending: List<List<Vec2>>,
    ): Boolean {
        if ((b - a).length() <= WELD_TOL) return false
        if (crossesAnyEdge(a, b, poly)) return false
        for (h in pending) if (crossesAnyEdge(a, b, h)) return false
        val mid = (a + b) * 0.5
        if (!insidePolygon(mid, poly)) return false
        for (h in pending) if (insidePolygon(mid, h)) return false
        return true
    }

    private fun crossesAnyEdge(
        a: Vec2,
        b: Vec2,
        poly: List<Vec2>,
    ): Boolean {
        for (i in poly.indices) {
            if (properlyIntersect(a, b, poly[i], poly[(i + 1) % poly.size])) return true
        }
        return false
    }

    private fun same(
        p: Vec2,
        q: Vec2,
    ): Boolean = (p - q).length() <= WELD_TOL

    /**
     * Do the segments a–b and c–d meet anywhere other than at a shared endpoint? Sharing an endpoint is
     * allowed on purpose: a bridge starts and ends *on* the boundary it is tested against. Anything
     * else — a proper crossing, a touch in the middle, a collinear overlap — blocks the bridge, which
     * only ever costs the next candidate a try.
     */
    private fun properlyIntersect(
        a: Vec2,
        b: Vec2,
        c: Vec2,
        d: Vec2,
    ): Boolean {
        if (same(a, c) || same(a, d) || same(b, c) || same(b, d)) return false
        val d1 = sign((b - a).cross(c - a))
        val d2 = sign((b - a).cross(d - a))
        val d3 = sign((d - c).cross(a - c))
        val d4 = sign((d - c).cross(b - c))
        if (d1 * d2 < 0 && d3 * d4 < 0) return true
        if (d1 == 0 && onSegment(a, b, c)) return true
        if (d2 == 0 && onSegment(a, b, d)) return true
        if (d3 == 0 && onSegment(c, d, a)) return true
        if (d4 == 0 && onSegment(c, d, b)) return true
        return false
    }

    private fun sign(v: Double): Int =
        if (v > AREA_EPS) {
            1
        } else if (v < -AREA_EPS) {
            -1
        } else {
            0
        }

    private fun onSegment(
        a: Vec2,
        b: Vec2,
        p: Vec2,
    ): Boolean {
        val len = (b - a).length()
        if (len <= WELD_TOL) return false
        val t = (p - a).dot(b - a) / (len * len)
        return t > 0.0 && t < 1.0
    }

    /** Even-odd ray crossing test. Points on the boundary are not the question here (midpoints are). */
    private fun insidePolygon(
        p: Vec2,
        poly: List<Vec2>,
    ): Boolean {
        var inside = false
        for (i in poly.indices) {
            val a = poly[i]
            val b = poly[(i + 1) % poly.size]
            if ((a.y > p.y) != (b.y > p.y)) {
                val x = a.x + (p.y - a.y) / (b.y - a.y) * (b.x - a.x)
                if (x > p.x) inside = !inside
            }
        }
        return inside
    }

    private fun earClip(poly: List<Vec2>): Pair<List<Tri3>?, String?> {
        val ring = ArrayList<Int>(poly.size)
        for (i in poly.indices) ring.add(i)
        val tris = ArrayList<Tri3>(max(1, poly.size - 2))
        while (ring.size > 3) {
            var clipped = false
            var bestK = -1
            var bestCross = AREA_EPS
            for (k in ring.indices) {
                val ip = ring[(k - 1 + ring.size) % ring.size]
                val ic = ring[k]
                val inx = ring[(k + 1) % ring.size]
                val a = poly[ip]
                val b = poly[ic]
                val c = poly[inx]
                val cr = (b - a).cross(c - b)
                if (abs(cr) <= AREA_EPS) {
                    // A straight (or doubled-back) corner carries no area: drop it, emit nothing.
                    ring.removeAt(k)
                    clipped = true
                    break
                }
                if (cr < 0.0) continue // reflex
                if (cr > bestCross) {
                    bestCross = cr
                    bestK = k
                }
                if (containsAnotherCorner(poly, ring, a, b, c)) continue
                tris.add(Tri3(a, b, c))
                ring.removeAt(k)
                clipped = true
                break
            }
            if (!clipped) {
                if (bestK < 0) return null to "the boundary cannot be triangulated (is it self-intersecting?)"
                // No corner is a clean ear — clip the most convex one anyway, so a near-degenerate
                // sliver costs a little accuracy rather than an endless loop.
                val ip = ring[(bestK - 1 + ring.size) % ring.size]
                val ic = ring[bestK]
                val inx = ring[(bestK + 1) % ring.size]
                tris.add(Tri3(poly[ip], poly[ic], poly[inx]))
                ring.removeAt(bestK)
            }
        }
        if (ring.size == 3) {
            val a = poly[ring[0]]
            val b = poly[ring[1]]
            val c = poly[ring[2]]
            if (abs((b - a).cross(c - b)) > AREA_EPS) tris.add(Tri3(a, b, c))
        }
        if (tris.isEmpty()) return null to "the boundary encloses no area"
        return tris to null
    }

    /**
     * Does any other corner of the ring fall inside the candidate ear — **its boundary included**?
     * Corners *coincident* with the ear's own corners are skipped, which is what makes the doubled bridge
     * vertices harmless.
     *
     * The boundary counts, and that is not fastidiousness. A corner sitting exactly *on* the ear's
     * diagonal makes the diagonal a T-junction: the neighbouring triangle stops at that corner while this
     * one runs past it, so the cap has a crack in it — and the polygon left after the clip touches itself
     * there, which the clipper then triangulates into overlapping garbage. It went unnoticed until
     * booleans started producing such polygons routinely (a plus-shaped union has one), and it is a defect
     * of the triangulator rather than of them: `extrude` had it too.
     */
    private fun containsAnotherCorner(
        poly: List<Vec2>,
        ring: List<Int>,
        a: Vec2,
        b: Vec2,
        c: Vec2,
    ): Boolean {
        for (i in ring) {
            val p = poly[i]
            if (same(p, a) || same(p, b) || same(p, c)) continue
            val s1 = (b - a).cross(p - a)
            val s2 = (c - b).cross(p - b)
            val s3 = (a - c).cross(p - c)
            if (s1 >= -AREA_EPS && s2 >= -AREA_EPS && s3 >= -AREA_EPS) return true
        }
        return false
    }

    // ---- features ----

    /**
     * A prism: [sketch] swept [depth] mm along its plane's normal (OP-17 slice 1).
     *
     * Caps are the triangulated region — bottom wound the other way so its normal points out of the
     * solid — and the side walls are a quad strip along every boundary piece, in the loop's own
     * direction. Because both read the *same* tessellated points, every wall edge meets exactly one cap
     * edge, which is where watertightness comes from rather than from a repair pass (OP-2).
     *
     * **Refusals first, triangles on demand** ([Solid3]). Every way this can fail is a property of the
     * *sketch* — no region at all, a boundary that cannot be tessellated, an area that cannot be
     * triangulated — so all of it is decided here, at evaluation time, and the emission below the fold is
     * a function that cannot fail. The 2D work (tessellation, ear clipping) stays eager because it is
     * *what the refusal is about*; the deferred part is the mapping of those flat corners into space.
     */
    fun extrude(
        sketch: Sketch3,
        depth: Double,
        tolMm: Double = GeomMath.TESS_TOL_MM,
    ): Pair<Solid3?, String?> {
        if (sketch.regions.isEmpty()) return null to "a sketch with no region cannot be extruded"
        if (depth <= WELD_TOL) return null to "extrude depth must be positive"
        val plane = sketch.plane
        val n = plane.normal
        val prepared = ArrayList<Pair<TessRegion, List<Tri3>>>(sketch.regions.size)
        for (region in sketch.regions) {
            val (tess, why) = tessellateRegion(region, tolMm)
            if (tess == null) return null to (why ?: "cannot tessellate the sketch")
            val (tris, reason) = triangulate(tess)
            if (tris == null) return null to (reason ?: "cannot triangulate the sketch")
            prepared.add(tess to tris)
        }
        return Solid3.derived(Feature3.Extrusion(sketch, depth)) { quality ->
            // The coarse picture is the same prism over a coarser outline of the same sketch — derived here,
            // inside the provider, so a body nobody is looking at pays for neither level (slice B). The fine
            // work already in hand is the fallback, because a picture may not refuse.
            val use =
                if (quality == MeshQuality.FINE) {
                    prepared
                } else {
                    preparedAt(sketch.regions, tolMm, quality) ?: prepared
                }
            val mb = MeshBuilder()
            for ((tess, tris) in use) {
                fun bottom(p: Vec2) = plane.toWorld(p)

                fun top(p: Vec2) = plane.toWorld(p) + n * depth
                for (t in tris) {
                    mb.triangle(top(t.a), top(t.b), top(t.c))
                    mb.triangle(bottom(t.a), bottom(t.c), bottom(t.b))
                }
                for (poly in listOf(tess.outer) + tess.holes) {
                    for (i in poly.indices) {
                        val p = poly[i]
                        val q = poly[(i + 1) % poly.size]
                        mb.triangle(bottom(p), bottom(q), top(q))
                        mb.triangle(bottom(p), top(q), top(p))
                    }
                }
            }
            mb.build()
        } to null
    }

    /**
     * A **complete** solid of revolution: [sketch] taken the whole way round the axis through
     * [axisOrigin] along [axisDir], **in the sketch plane** (OP-17 slice 2).
     *
     * A kind of its own rather than an angle of 360° ([Turn3]): the body has no start and no end, so it
     * has no caps, no seam and no offset, and no parameter edit anywhere can open it.
     */
    fun revolveFull(
        sketch: Sketch3,
        axisOrigin: Vec2,
        axisDir: Vec2,
        tolMm: Double = GeomMath.TESS_TOL_MM,
    ): Pair<Solid3?, String?> = revolve(sketch, axisOrigin, axisDir, Turn3.Full, tolMm)

    /**
     * A **partial** solid of revolution: [sketch] swept [angle] rad about the axis through [axisOrigin]
     * along [axisDir], starting [offset] rad from the sketch plane (OP-17 slice 2).
     *
     * The profile is the generator at angle 0 and the body occupies `[offset, offset + angle]`, so with a
     * non-zero offset the drawn profile is deliberately **not** a section of the body — the tool says where
     * the body starts and ends so nobody hunts for a solid 30° away from its drawing.
     *
     * **Either sign** of [angle] is legal. A positive sweep turns the profile toward its sketch plane's own
     * **normal** and a negative one away from it — the axis is canonicalized so the profile lies on `+P`,
     * which negates `A` and `P` together and therefore leaves `N = A x P` alone, so which way is positive is
     * a property of the *plane*, never of how the axis happened to be drawn. Whichever way a given plane
     * faces, the other way is now one minus sign away.
     */
    fun revolve(
        sketch: Sketch3,
        axisOrigin: Vec2,
        axisDir: Vec2,
        angle: Double,
        offset: Double = 0.0,
        tolMm: Double = GeomMath.TESS_TOL_MM,
    ): Pair<Solid3?, String?> = revolve(sketch, axisOrigin, axisDir, Turn3.Arc.of(offset, angle), tolMm)

    /**
     * The one revolve, over the interval [turn] describes (OP-17 slice 2).
     *
     * The profile may *touch* the axis — that is the normal case for a turned part, and the collapsed
     * quads are simply dropped — but a profile **crossing** the axis is refused with a reason and heals
     * when it is dragged back (OP-3): revolving through the axis would fold the shell through itself,
     * and a solid that is quietly self-intersecting is worse than one that is visibly absent.
     *
     * **The interval, normalized, is what makes one winding rule serve every sign.** [Turn3.Arc] is
     * ordered (`start <= end`), so the stations always run from the low angle to the high one whatever
     * signs the offset and the angle were stated with — a negative sweep is the same set of points walked
     * the other way, and nothing below it ever sees a negative step. The caps then keep the rule they
     * always had: the one at the **low** end faces backwards out of the sweep, the one at the **high** end
     * forwards, which is the extrude's reversed-bottom / upright-top rule read on the angle.
     *
     * A [Turn3.Full] turn closes the shell and gets no caps. So does a *stated* interval that happens to
     * measure a full turn, which is what every file written before the two became different kinds means by
     * `360°` — the value-level closure is kept for exactly that reason, and the structural one is what a
     * new drawing gets when no angle is stated at all.
     */
    fun revolve(
        sketch: Sketch3,
        axisOrigin: Vec2,
        axisDir: Vec2,
        turn: Turn3,
        tolMm: Double = GeomMath.TESS_TOL_MM,
    ): Pair<Solid3?, String?> {
        if (sketch.regions.isEmpty()) return null to "a sketch with no region cannot be revolved"
        if (axisDir.length() < Vec2.EPS) return null to "the axis of revolution has no direction"
        val twoPi = 2.0 * PI
        val arc = turn as? Turn3.Arc
        if (arc != null && arc.sweep <= WELD_TOL) {
            return null to "a revolve needs an angle to sweep through — this one sweeps none"
        }
        if (arc != null && arc.sweep > twoPi + 1e-9) return null to "revolve angle must not exceed a full turn"
        val start = arc?.start ?: 0.0
        val angle = arc?.sweep ?: twoPi
        val full = arc == null || abs(angle - twoPi) <= 1e-9

        val tessellated = ArrayList<TessRegion>(sketch.regions.size)
        for (region in sketch.regions) {
            val (tess, why) = tessellateRegion(region, tolMm)
            if (tess == null) return null to (why ?: "cannot tessellate the sketch")
            tessellated.add(tess)
        }

        // Axis frame in sketch coordinates: s along the axis, r away from it. Both signs of the axis
        // direction describe the same axis, so the one with the profile on its positive side is chosen —
        // a rotation by pi, hence orientation-preserving, which is what keeps the winding rules below
        // independent of how the axis happened to be drawn.
        var axis = axisDir.normalized()
        val allPoints = tessellated.flatMap { listOf(it.outer) + it.holes }.flatten()
        val radii = allPoints.map { (it - axisOrigin).dot(axis.perp()) }
        val hasPos = radii.any { it > WELD_TOL }
        val hasNeg = radii.any { it < -WELD_TOL }
        if (hasPos && hasNeg) return null to "the profile crosses the axis of revolution"
        if (hasNeg) axis = -axis
        val perp = axis.perp()
        if (!hasPos && !hasNeg) return null to "the profile lies on the axis of revolution"

        // The same frame in the world: A along the axis, P the in-plane radial direction at angle 0,
        // N = A x P the plane's own normal, so increasing the angle turns P towards N.
        val plane = sketch.plane
        val axisWorld = (plane.u * axis.x + plane.v * axis.y).normalized()
        val radialWorld = (plane.u * perp.x + plane.v * perp.y).normalized()
        val normalWorld = axisWorld.cross(radialWorld)
        val originWorld = plane.toWorld(axisOrigin)

        fun sr(p: Vec2): Pair<Double, Double> {
            val d = p - axisOrigin
            val r = d.dot(perp)
            return d.dot(axis) to if (abs(r) <= WELD_TOL) 0.0 else r
        }

        val maxR = allPoints.maxOf { abs(sr(it).second) }
        val steps = max(3, GeomMath.chordSteps(maxR, angle, tolMm))

        fun at(
            p: Vec2,
            step: Int,
            rings: Int,
        ): Vec3 {
            val (s, r) = sr(p)
            val th = start + angle * step / rings
            return originWorld + axisWorld * s + radialWorld * (r * cos(th)) + normalWorld * (r * sin(th))
        }

        // The caps are triangulated **here**, before anything is emitted, for [Solid3]'s reason: a profile
        // whose area cannot be triangulated is a refusal about the *sketch*, so it is the node's verdict at
        // evaluation time and not a surprise the first time somebody looks at the body.
        val caps = ArrayList<List<Tri3>>(if (full) 0 else tessellated.size)
        if (!full) {
            for (tess in tessellated) {
                val (tris, reason) = triangulate(tess)
                if (tris == null) return null to (reason ?: "cannot triangulate the revolve profile")
                caps.add(tris)
            }
        }
        return Solid3.derived(Feature3.Revolution(sketch, axisOrigin, axis, turn)) { quality ->
            // **A revolution coarsens on both of its axes**, and may: the rings round the axis are this
            // body's own surface and nothing outside the mesh reads their count (its plan hint is its sketch,
            // and its section is cut from a mesh, which is the fine one by law) — unlike a *sweep's* run
            // stations, which three things read (see [Solid3.meshAt]). The profile's own chords coarsen with
            // them, off the same chokepoint. The ring count keeps the **fine** greatest radius as its basis,
            // so the two levels are the same body seen at two fineness, not two measurements of it.
            var use = tessellated as List<TessRegion>
            var useCaps = caps as List<List<Tri3>>
            var rings = steps
            if (quality != MeshQuality.FINE) {
                rings = max(3, GeomMath.chordSteps(maxR, angle, tolMm, quality))
                if (full) {
                    tessAt(sketch.regions, tolMm, quality)?.let { use = it }
                } else {
                    preparedAt(sketch.regions, tolMm, quality)?.let { prep ->
                        use = prep.map { it.first }
                        useCaps = prep.map { it.second }
                    }
                }
            }
            val mb = MeshBuilder()
            for ((ti, tess) in use.withIndex()) {
                for (poly in listOf(tess.outer) + tess.holes) {
                    for (i in poly.indices) {
                        val p = poly[i]
                        val q = poly[(i + 1) % poly.size]
                        for (j in 0 until rings) {
                            mb.triangle(at(p, j, rings), at(q, j, rings), at(q, j + 1, rings))
                            mb.triangle(at(p, j, rings), at(q, j + 1, rings), at(p, j + 1, rings))
                        }
                    }
                }
                if (!full) {
                    for (t in useCaps[ti]) {
                        // The cap at the interval's **low** angle faces backwards out of the sweep, the one
                        // at its high angle forwards — the same reversed-bottom / upright-top rule the
                        // extrude uses, and it serves a negative sweep too because the interval was ordered
                        // before any station was computed ([Turn3.Arc]).
                        mb.triangle(at(t.a, 0, rings), at(t.c, 0, rings), at(t.b, 0, rings))
                        mb.triangle(at(t.a, rings, rings), at(t.b, rings, rings), at(t.c, rings, rings))
                    }
                }
            }
            mb.build()
        } to null
    }

    // ---- the sweep: a profile carried along a curve in space, on the moving frame (OP-26's step 2) ----
    // Everything value-dependent is here, inside one function of values (OP-21's rule) — including how many
    // stations the spine is cut into, which is a *compute-time* decision and never the shape of the graph.

    /**
     * A **sweep**: [profile] carried along [path] in the rotation-minimizing frame ([Frames3]), rolled by
     * [rollRad] at the start and twisted by [twistRad] over the whole run.
     *
     * **How the profile is carried.** Each station of the frame reads the profile's own 2D coordinates in its
     * own (ref, bi) axes, with the profile's origin on the path (see [SweepProfile]) — so an eccentric
     * section sweeps eccentrically, by construction rather than by an offset argument. The ring is then
     * pushed onto the station's mitre plane, which makes a corner in the path come out as the trim two
     * straight tubes make of each other; where the path is smooth that push is zero.
     *
     * **Watertight by construction** (OP-9), for the reason a prism is: consecutive bands share **one** ring,
     * computed once per station, and the caps are the same tessellated polygons the bands run through — so
     * every wall edge meets exactly one other edge and nothing is repaired afterwards. A closed path needs no
     * caps, because its last band hands back to its first ring.
     *
     * Refused with a reason that heals (OP-3), each naming what is wrong and where: a non-positive tube
     * radius; a profile whose outline does not close; a profile enclosing no area; a path with no pieces or
     * no length; a path that doubles back so sharply that no mitre exists ([Frames3]); the profile's
     * **reach** exceeding the path's local radius of curvature at some station, which is the sweep passing
     * through itself and is named by how far along the path it happens; and a **closed** path whose frame
     * does not come back to itself, which is named with the twist that would close it.
     */
    fun sweep(
        path: Path3,
        up: Vec3,
        profile: SweepProfile,
        rollRad: Double = 0.0,
        twistRad: Double = 0.0,
        plan: Plane3? = null,
        tolMm: Double = GeomMath.TESS_TOL_MM,
        seed: FrameSeed? = null,
    ): Pair<Solid3?, String?> {
        if (profile is SweepProfile.Round && profile.radius <= WELD_TOL) {
            return null to "a tube needs a positive radius — this one is ${Frames3.mm(profile.radius)} mm"
        }
        val region = profile.region
        for (loop in listOf(region.outer) + region.holes) openBoundary(loop)?.let { return null to it }
        val (tess, why) = tessellateRegion(region, tolMm)
        if (tess == null) return null to (why ?: "cannot tessellate the profile")
        if (tessArea(tess) <= AREA_EPS) return null to "the profile encloses no area, so there is nothing to sweep"
        // how far the profile reaches from the path — the number the self-intersection criterion is about,
        // and the radius the twist's own sampling refinement is measured at
        val reach = tess.outer.maxOf { it.length() }
        if (reach <= WELD_TOL) return null to "the profile has no size, so there is nothing to sweep"

        val (frame, noFrame) = Frames3.along(path, up, rollRad, twistRad, reach, tolMm, seed)
        if (frame == null) return null to (noFrame ?: "cannot build a moving frame along this curve")

        // **The self-intersection criterion: the spine's reach**, both terms of it ([Embedding]). Locally, a
        // profile reaching `reach` from the path folds through itself the moment the path's radius of
        // curvature there drops to `reach` — the inner side of the bend turns inside out. Globally, the run
        // may come back alongside itself with every station's curvature perfectly comfortable, which is the
        // spring whose wire is thicker than half its pitch. Checked before a triangle is emitted, and named
        // by *where*, because "this sweep self-intersects" is not something anyone can act on and "at 340 mm
        // along" is.
        // The section is handed over **as an outline** and not only as its reach, so that the criterion can
        // ask what it reaches *towards* the other leg rather than what it reaches at all — which is what an
        // off-centre section (the in-place sweep's everyday case) makes the difference between a ring and a
        // ring turned inside out. A round tube is analytically a disc about the run, so it is stated as its
        // radius (null) instead of as its chords: same answer, and to the last bit.
        // …and the same outline answers the **local** term's own directional question — what the section
        // reaches *towards the centre of the bend* at each station, which is the number a fold actually turns
        // on ([Embedding.intoTheBend]). It is named separately in the refusal because it is a different
        // measurement from the reach the global term quotes, and a message that printed one while testing the
        // other would be a correct refusal nobody could act on.
        val sectionOutline = if (profile is SweepProfile.Round) null else tess.outer
        val intoTheBend: ((Double) -> String)? =
            if (profile is SweepProfile.Round) null else { d -> "the profile's reach into the bend (${Frames3.mm(d)} mm)" }
        Embedding.check(frame, reach, profileReach(profile, reach), section = sectionOutline, inward = intoTheBend)
            .defect?.let { return null to it }
        // **…and the third way a swept body folds: a corner that mitres away more run than there is**
        // ([Embedding.cornerFold]). Neither term above can see it — it is not a proximity (a triangle's legs
        // all touch, so there is no non-neighbouring pair to be a bottleneck at) and it is not a curvature (a
        // polyline corner has none on either side), and what comes out is edge-manifold and, for a symmetric
        // section, positively volumed: silent wrong output, which outranks everything. It is asked **last**,
        // so that where more than one term fires the one that was there first keeps its words — the same rule
        // the local term already has against the global one.
        Embedding.cornerFold(frame, reach, sectionOutline)?.let { return null to it }
        // **A closed path whose frame does not close on itself** is reported rather than smeared over the
        // last band, and the report names the cure: the twist that makes the total come back to zero is an
        // ordinary parameter of this very feature, so the refusal heals by stating it (OP-3). A *planar*
        // closed path has no residual at all — the reference is the rotation axis at every step, so it is
        // carried through unchanged — which is why this fires only where the condition is real.
        if (frame.closed && abs(frame.seam) > Frames3.SEAM_EPS) {
            return null to
                "the frame does not come back to itself round this closed curve — it is ${Frames3.deg(frame.seam)}° " +
                "out at the seam, which would twist the last piece of the sweep against the first; state a twist " +
                "of ${Frames3.deg(twistRad - frame.seam)}° (or that plus any whole number of turns) to close it"
        }

        val (shells, noMesh) =
            sweptShells(listOf(tess), frame.stations, frame.closed, regions = listOf(region), tolMm = tolMm) { st, p ->
                st.place(p)
            }
        if (shells == null) return null to (noMesh ?: "cannot build this sweep")
        // **The plan comes off the run, not off the triangles** ([Silhouette.ofSwept]). It used to be
        // `Silhouette.of(mesh, plan)`, which made the 2D view the one consumer that could not avoid meshing —
        // and this body is exactly the one the deferral is for. The stations are already in hand from the
        // refusal checks above, so the outline costs a projection per station and nothing else.
        val outline =
            if (plan == null) {
                emptyList()
            } else {
                Silhouette.ofSwept(frame.stations, tess.outer, roundRadius(profile), frame.closed, plan)
            }
        // **The feature records the direction the frame actually started with**, not the one that was asked
        // for — [MovingFrame.startRef]. For a frame started at the run's beginning the two are the same
        // statement (`startReference` of an already-perpendicular direction is that direction), and for a
        // **seeded** one this is what keeps `(path, profile, up, roll, twist)` enough to rebuild the identical
        // body: where the section was stated is a fact about the *gesture*, and the frame it produced is a
        // direction like any other (OP-9's self-contained feature, OP-26's stated start frame).
        return Solid3.derived(Feature3.Sweep(path, profile, frame.startRef, rollRad, twistRad, outline), shells) to null
    }

    /**
     * The **plan of a swept body**, rebuilt in [plane] from the feature alone — what a placement asks for
     * when it has moved a sweep into a new space (`Construction.placeSolid`).
     *
     * The feature is by definition enough to rebuild the identical frame ([Feature3.Sweep]), so this is that
     * rebuild and then the outline: `O(stations)` arithmetic, no triangles. A refusal is impossible to report
     * from here — a plan is a hint, not a verdict — so a frame that cannot be built yields *no* hint, which is
     * exactly what a move without a re-projection already yields.
     *
     * A **translationally** carried section is not asked about: the only feature that states one is the
     * cutting tool of a swept cut, which is discarded the moment its boolean has run and shows no plan at all.
     */
    fun sweptPlan(
        feature: Feature3.Sweep,
        plane: Plane3,
        tolMm: Double = GeomMath.TESS_TOL_MM,
    ): List<Region> {
        if (feature.carry != CarryMode.ROTATING) return emptyList()
        val (tess, _) = tessellateRegion(feature.profile.region, tolMm)
        if (tess == null || tess.outer.isEmpty()) return emptyList()
        val reach = tess.outer.maxOf { it.length() }
        if (reach <= WELD_TOL) return emptyList()
        val (frame, _) = Frames3.along(feature.path, feature.up, feature.roll, feature.twist, reach, tolMm)
        if (frame == null) return emptyList()
        return Silhouette.ofSwept(frame.stations, tess.outer, roundRadius(feature.profile), frame.closed, plane)
    }

    /** The **analytic** radius of a round section, and null for any other — see [Silhouette.ofSwept]. */
    private fun roundRadius(profile: SweepProfile): Double? = (profile as? SweepProfile.Round)?.radius

    /**
     * The **shells a run of stations carries [sections] through**: one quad band per span and, on an open
     * run, a cap at each end.
     *
     * Extracted from [sweep] so that the sweep (OP-26, step 2) and the **swept cut** (OP-22's extension,
     * step 2) build their meshes from one piece of code rather than two that must be kept in step. What the
     * two differ in is exactly the two arguments: **[place]**, because a cut may carry its section
     * translationally instead of on the frame ([CarryMode]), and **[reversed]**, which is one question asked
     * once — *does this carry turn the section's own orientation round?* A cut whose route runs against its
     * chain space's normal reads the section mirrored, and a translational carry may travel against that
     * normal outright; either way **every** triangle turns, bands and caps together, since a shell wound one
     * way at its sides and the other at its ends is not a surface at all.
     *
     * The ring at a station is computed **once** and both adjacent bands use it, which is where
     * watertightness comes from — the prism's own argument (OP-2), not a repair pass. A closed run needs no
     * caps, its last band handing back to its first ring.
     *
     * **What it returns is the emission, not the mesh** ([Solid3]): the two things that can go wrong — a run
     * with no span in it, and a section whose area cannot be triangulated into a cap — are decided here, so
     * the caller has its refusal at evaluation time, and the function handed back cannot fail. Every station
     * ring, and therefore every triangle, is computed inside it, which is the whole of what a plan drag now
     * skips.
     *
     * **What a coarse ask changes here, and what it must not** (slice B). Given [regions] — the sections'
     * own boundaries, which only a caller that has them can supply — a coarse picture carries a coarser
     * *section* through the very same [stations]. The run is deliberately untouched: its station count is
     * read by the plan hint ([Silhouette.ofSwept]), by a station plane's transported frame and by the
     * self-intersection refusals, all of them outside any mesh, and it is the expensive half of a sweep
     * besides — so the coarse body is the same run with a cheaper ring, and the two levels stay the same
     * body. Without [regions] both levels are the fine mesh, which is what the swept **cut** takes: its
     * triangles feed a boolean and are never a picture at all.
     */
    internal fun sweptShells(
        sections: List<TessRegion>,
        stations: List<Frame3>,
        closed: Boolean,
        reversed: Boolean = false,
        regions: List<Region>? = null,
        tolMm: Double = GeomMath.TESS_TOL_MM,
        place: (Frame3, Vec2) -> Vec3,
    ): Pair<((MeshQuality) -> Mesh3)?, String?> {
        if (stations.size < 2) return null to "a sweep needs at least two stations along its run"
        val caps = ArrayList<List<Tri3>>(if (closed) 0 else sections.size)
        if (!closed) {
            for (tess in sections) {
                val (tris, noTris) = triangulate(tess)
                if (tris == null) return null to (noTris ?: "cannot cap this sweep")
                caps.add(tris)
            }
        }
        return { quality: MeshQuality ->
            var use = sections
            var useCaps = caps as List<List<Tri3>>
            if (quality != MeshQuality.FINE && regions != null) {
                if (closed) {
                    tessAt(regions, tolMm, quality)?.let { use = it }
                } else {
                    preparedAt(regions, tolMm, quality)?.let { prep ->
                        use = prep.map { it.first }
                        useCaps = prep.map { it.second }
                    }
                }
            }
            val mb = MeshBuilder()

            fun tri(
                a: Vec3,
                b: Vec3,
                c: Vec3,
            ) = if (reversed) mb.triangle(a, c, b) else mb.triangle(a, b, c)
            for ((si, tess) in use.withIndex()) {
                val polys = listOf(tess.outer) + tess.holes
                val rings = stations.map { st -> polys.map { poly -> poly.map { place(st, it) } } }
                val bands = if (closed) stations.size else stations.size - 1
                for (k in 0 until bands) {
                    val lo = rings[k]
                    val hi = rings[(k + 1) % stations.size]
                    for (pi in polys.indices) {
                        val poly = polys[pi]
                        for (i in poly.indices) {
                            val j = (i + 1) % poly.size
                            tri(lo[pi][i], lo[pi][j], hi[pi][j])
                            tri(lo[pi][i], hi[pi][j], hi[pi][i])
                        }
                    }
                }
                if (!closed) {
                    // (ref, bi, tangent) is right-handed, so a cap triangle wound counter-clockwise in the
                    // profile's own coordinates faces **along** the tangent: the end cap as it is, the start
                    // cap reversed — the extrude's own top/bottom rule, one dimension round.
                    val first = stations.first()
                    val last = stations.last()
                    for (t in useCaps[si]) {
                        tri(place(first, t.a), place(first, t.c), place(first, t.b))
                        tri(place(last, t.a), place(last, t.b), place(last, t.c))
                    }
                }
            }
            mb.build()
        } to null
    }

    /** Why [loop] is not a closed outline — naming the piece that leaves the gap — or null when it is one. */
    private fun openBoundary(loop: Loop): String? {
        val els = loop.elements
        if (els.isEmpty()) return "the profile has an empty boundary, so it is no closed section"
        for (i in els.indices) {
            val j = (i + 1) % els.size
            if ((GeomMath.endOf(els[i]) - GeomMath.startOf(els[j])).length() > WELD_TOL) {
                return "the profile's outline does not close — piece ${i + 1} ends where piece ${j + 1} does not " +
                    "begin, and a sweep needs a closed section"
            }
        }
        return null
    }

    /** How a refusal names the size of the profile that will not fit round a bend. */
    private fun profileReach(
        profile: SweepProfile,
        reach: Double,
    ): String =
        when (profile) {
            is SweepProfile.Round -> "the tube's radius (${Frames3.mm(profile.radius)} mm)"
            is SweepProfile.Section -> "the profile's reach from the path (${Frames3.mm(reach)} mm)"
        }

    // ---- the loft: an ordered run of sections, optionally shaped by guides (OP-17's third feature) ----
    // The one solid whose cross-section *changes* along the sweep, and therefore the one that needs a
    // correspondence between boundaries rather than a single profile. Everything value-dependent is here,
    // inside one function of values (OP-21's rule); the node above it only says which sections there are.

    /** One area section resolved to values: its polygon, in the loft's own winding and seam order. */
    internal class LoftPrep(
        val plane: Plane3,
        var poly: List<Vec2>,
        var starts: List<Int>,
        val tess: TessRegion,
    )

    /**
     * The **correspondence** of a loft, resolved to values and *before* a triangle is emitted: which
     * boundary parameter every rail sits at, where each rail meets each section, and which way the run
     * turns (OP-17's third feature).
     *
     * Extracted so it has **two consumers** rather than one: the mesh below, and the structural face/edge
     * naming a section and a working plane need ([Section3]). That is the same discipline the appearance
     * seam follows — one authority, two readers — and it is what makes "a loft's ruled face is its
     * base-edge/seam pair" a statable address instead of a mesh lookup (OP-8).
     */
    internal class LoftPlan(
        val sections: List<LoftSection>,
        val preps: List<LoftPrep?>,
        /** the global boundary parameters, ascending — one rail each */
        val us: List<Double>,
        /** per section: the sampled ring in the section's own plane coordinates, null for an apex */
        val ring2: List<List<Vec2>?>,
        /** per section: the sampled ring in the world (an apex's is its point, repeated) */
        val ringW: List<List<Vec3>>,
        val runs: List<Vec3>,
        val hand: IntArray,
        val rails: List<LoftRail>,
    ) {
        val railCount: Int get() = us.size

        /**
         * Which rail interval the boundary piece [piece] of section [k] falls into — the bridge from OP-8's
         * durable footprint-piece name to this correspondence's own rail index.
         *
         * Measured from the piece's **midpoint**, deliberately: the polygon may have been reversed (to turn
         * every boundary the same way about the run) and rotated (by the seam), and a midpoint is invariant
         * under both where a start index is not.
         */
        fun railOfPiece(
            k: Int,
            piece: Int,
        ): Int? {
            val p = preps.getOrNull(k) ?: return null
            val section = sections.getOrNull(k) as? LoftSection.Area ?: return null
            val pieces = section.sketch.regions.firstOrNull()?.outer?.elements ?: return null
            val e = pieces.getOrNull(piece) ?: return null
            val mid = GeomMath.tessellatePiece(e, GeomMath.TESS_TOL_MM).let { pts -> pts[pts.size / 2] }
            val cum = cumulativeOf(p.poly)
            val (_, u) = nearestOnPolygon(mid, p.poly, cum)
            for (i in us.indices) {
                val a = us[i]
                val b = if (i == us.size - 1) us[0] + 1.0 else us[i + 1]
                val uu = if (u < a) u + 1.0 else u
                if (uu >= a - 1e-12 && uu <= b + 1e-12) return i
            }
            return null
        }
    }

    /** A guide resolved to values: its world polyline and where it meets each section it spans. */
    internal class LoftRail(
        val poly: List<Vec3>,
        val cum: List<Double>,
        /** section index → (that section's boundary parameter, this guide's own arc parameter) */
        val touch: Map<Int, Pair<Double, Double>>,
        /** the boundary parameter this guide controls — the same in every section it spans, by definition */
        val param: Double,
    )

    /**
     * A **loft**: [sections] in order, blended pairwise, shaped by [guides], with [seams] saying where each
     * section's boundary correspondence starts (OP-17).
     *
     * Three decisions make this one function rather than a family of features:
     *
     * 1. **One global boundary parameter set.** Every area section's boundary is parameterized by normalized
     *    arc length *from its own seam vertex*, and the union of every section's own vertex parameters is
     *    sampled on **all** of them. So corresponding points are the same parameter, sections with different
     *    corner counts (a square and a tessellated circle) need no special case, and — the reason it is global
     *    rather than per band — the ring an interior section hands to the band below it is the same ring it
     *    hands to the band above, which is what keeps the shell free of T-junctions. Every added sample lies
     *    *on* the boundary, so nothing about the shape is approximated by adding them.
     * 2. **The winding is not a choice.** Each section is oriented against the run (the direction from one
     *    section's centre to the next) so that all boundaries turn the same way about it; the side walls then
     *    wind exactly as an extrude's do and the caps close them. The mirrored correspondence — the "flip" a
     *    feature-CAD loft offers — makes rails *cross* (two squares turned into each other meet in the middle),
     *    which is a self-intersecting solid, so it is not offered: what is left as the seam's freedom is the
     *    rotational offset, which is the choice a user actually makes.
     * 3. **A guide displaces the run, and cannot move the sections.** A guide contributes its deviation from
     *    its own chord, `D(t) = G(t) − ((1−t)·G(0) + t·G(1))`, which is zero at both ends *by construction* —
     *    so a guide bends the rails between two sections and can never drag a section off its plane. Several
     *    guides blend **linearly between adjacent ones** on the (circular) boundary parameter, a partition of
     *    unity: with one guide the whole run follows it, with several each rail follows its own and neither
     *    reaches past its neighbours (see [weightsAt]).
     *
     * Refused with a reason and healing (OP-3): fewer than two sections; a point section anywhere but at an
     * end; a section that is not one hole-free area; a section enclosing no area; two sections at the same
     * place; a section plane edge-on to the run; a **ruling that does not advance** along the run, which is
     * what "two section planes cross inside the solid" looks like locally; two **rails that meet** in mid-run,
     * which is what a seam turned too far produces (see [crossingRails]); and a guide that does not pass
     * through corresponding points of a consecutive run of sections. What is *not* detected is stated in
     * DESIGN.md: a shell that self-intersects for a reason no rail shows.
     */
    fun loft(
        sections: List<LoftSection>,
        seams: List<Int> = emptyList(),
        guides: List<LoftGuide> = emptyList(),
        tolMm: Double = GeomMath.TESS_TOL_MM,
    ): Pair<Solid3?, String?> {
        val (plan, why) = loftPlan(sections, seams, guides, tolMm)
        if (plan == null) return null to why
        return loftShell(plan, seams, guides, tolMm)
    }

    /**
     * The value half of a loft: everything that is decided before a triangle exists — see [LoftPlan].
     *
     * Every refusal of the feature is here, which is why it comes back as `null to reason` and heals (OP-3):
     * the naming of faces reads the *same* answers the mesh does, so a face a section can address is a face
     * the shell actually has.
     */
    internal fun loftPlan(
        sections: List<LoftSection>,
        seams: List<Int> = emptyList(),
        guides: List<LoftGuide> = emptyList(),
        tolMm: Double = GeomMath.TESS_TOL_MM,
    ): Pair<LoftPlan?, String?> {
        if (sections.size < 2) {
            return null to "a loft needs at least two sections — one area and a point make a pyramid or a cone"
        }
        val apexes = sections.indices.filter { sections[it] is LoftSection.Apex }
        if (apexes.size > 1) return null to "only one of a loft's sections may be a point — a run between two points has no volume"
        if (apexes.any { it != 0 && it != sections.size - 1 }) {
            return null to "a point may only be a loft's first or last section, not one inside the run"
        }

        // ---- every section as values: an area's polygon and piece starts, or an apex position ----
        val preps = arrayOfNulls<LoftPrep>(sections.size)
        val centres = arrayOfNulls<Vec3>(sections.size)
        for ((k, s) in sections.withIndex()) {
            when (s) {
                is LoftSection.Apex -> centres[k] = s.at
                is LoftSection.Area -> {
                    if (s.sketch.regions.size != 1) {
                        return null to "a loft's section is one area, and section ${k + 1} has ${s.sketch.regions.size}"
                    }
                    val region = s.sketch.regions[0]
                    if (region.holes.isNotEmpty()) {
                        return null to
                            "section ${k + 1} has a hole, and a loft pairs one boundary with one boundary — " +
                            "loft the outer boundaries and subtract a loft of the holes"
                    }
                    val (tess, why) = tessellateRegion(region, tolMm)
                    if (tess == null) return null to "section ${k + 1}: ${why ?: "cannot be tessellated"}"
                    if (abs(tessArea(tess)) <= AREA_EPS) return null to "section ${k + 1} encloses no area"
                    val (poly, starts) = loopWithStarts(region.outer, tolMm)
                    if (poly.size < 3) return null to "section ${k + 1} tessellates to fewer than three corners"
                    preps[k] = LoftPrep(s.sketch.plane, poly, starts, tess)
                    centres[k] = s.sketch.plane.toWorld(averageOf(poly))
                }
            }
        }

        // ---- the run, and the winding it fixes ----
        val runs = ArrayList<Vec3>(sections.size - 1)
        for (k in 0 until sections.size - 1) {
            val d = centres[k + 1]!! - centres[k]!!
            if (d.length() <= WELD_TOL) {
                return null to "sections ${k + 1} and ${k + 2} sit at the same place, so the loft has no direction to run in"
            }
            runs.add(d.normalized())
        }
        val hand = IntArray(sections.size)
        for (k in sections.indices) {
            val p = preps[k] ?: continue
            val r = if (k < runs.size) runs[k] else runs[k - 1]
            val n = p.plane.normal.normalized()
            if (abs(n.dot(r)) <= Vec3.EPS) {
                val neighbour = if (k < runs.size) sections[k + 1] else sections[k - 1]
                return null to
                    if (neighbour is LoftSection.Apex) {
                        "the apex lies in section ${k + 1}'s own plane, so the loft has no height"
                    } else {
                        "section ${k + 1}'s plane runs along the loft, so its shell would fold through itself"
                    }
            }
            if (n.dot(r) * polygonArea(p.poly) < 0.0) {
                val last = p.poly.size - 1
                p.starts = p.starts.map { last - it }
                p.poly = p.poly.reversed()
            }
            hand[k] = if (polygonArea(p.poly) >= 0.0) 1 else -1
            // the seam: which boundary piece the correspondence starts at, taken verbatim (OP-1/OP-18) and
            // read modulo the piece count, so a stored choice always resolves to *a* vertex of this boundary
            val off = seams.getOrElse(k) { 0 }.mod(p.starts.size)
            val s0 = p.starts[off]
            p.poly = List(p.poly.size) { p.poly[(s0 + it) % p.poly.size] }
        }

        // ---- one global parameter set, sampled on every section ----
        val cums = preps.map { it?.let { p -> cumulativeOf(p.poly) } }
        val perims = preps.indices.map { cums[it]?.last() ?: 0.0 }
        val minPerim = perims.filter { it > 0.0 }.minOrNull() ?: return null to "a loft needs at least one area section"
        // params closer together than a corner is wide are one parameter: the sampled points would weld in the
        // mesh but not in a cap's triangulation, and that difference is exactly a crack
        val paramTol = (10.0 * RegionBool.EPS / minPerim).coerceAtMost(1e-6)
        val sorted = ArrayList<Double>()
        for (k in sections.indices) {
            val p = preps[k] ?: continue
            val cum = cums[k]!!
            for (j in p.poly.indices) sorted.add(cum[j] / perims[k])
        }
        sorted.sort()
        val us = ArrayList<Double>(sorted.size)
        for (v in sorted) if (us.isEmpty() || v - us.last() > paramTol) us.add(v)
        if (us.size > 1 && (1.0 - us.last()) + us.first() <= paramTol) us.removeAt(us.size - 1)
        if (us.size < 3) return null to "the loft's boundaries give fewer than three rails"
        val ring2 = preps.indices.map { k -> preps[k]?.let { p -> us.map { u -> pointAtParam(p.poly, cums[k]!!, u) } } }
        val ringW =
            sections.indices.map { k ->
                val p = preps[k]
                if (p == null) us.map { centres[k]!! } else ring2[k]!!.map { p.plane.toWorld(it) }
            }

        // ---- the guides: where each one meets which sections, and what it may therefore shape ----
        val rails = ArrayList<LoftRail>(guides.size)
        for ((gi, g) in guides.withIndex()) {
            val (rail, why) = railOf(gi, g, sections, preps, cums, perims, tolMm)
            if (rail == null) return null to (why ?: "cannot honour guide ${gi + 1}")
            rails.add(rail)
        }
        return LoftPlan(sections, preps.toList(), us, ring2, ringW, runs, hand, rails) to null
    }

    /**
     * The mesh half of a loft: the bands, the caps, and the folds that are refused before a triangle.
     *
     * **Refused before a triangle** is now literal ([Solid3]): the two fold tests and the two cap steps are
     * questions about the *plan* — the section rings, the rulings between them, the correspondence the seam
     * states — so they are all asked here, in one pass over the bands that emits nothing, and the emission
     * that follows cannot fail. Only the rows, which is where a guided band's sampling lives and where every
     * triangle comes from, waits for somebody to ask for triangles.
     */
    private fun loftShell(
        plan: LoftPlan,
        seams: List<Int>,
        guides: List<LoftGuide>,
        tolMm: Double,
    ): Pair<Solid3?, String?> {
        val sections = plan.sections
        val preps = plan.preps
        val us = plan.us
        val ring2 = plan.ring2
        val ringW = plan.ringW
        val runs = plan.runs
        val hand = plan.hand
        val rails = plan.rails
        val m = us.size

        // ---- every refusal, and nothing emitted ----
        for (k in 0 until sections.size - 1) {
            // Two ways a band can be folded rather than swept: a ruling that runs *backwards* (which is what
            // two section planes crossing inside the solid looks like where it matters), and two rails that
            // meet (which is what a seam turned too far does). Neither is asked of an apex band: every rail
            // of one legitimately ends at the same point.
            if (preps[k] != null && preps[k + 1] != null) {
                for (j in 0 until m) {
                    if ((ringW[k + 1][j] - ringW[k][j]).dot(runs[k]) <= WELD_TOL) {
                        return null to
                            "sections ${k + 1} and ${k + 2} fold into each other — their planes cross inside the " +
                            "loft, so its shell would pass through itself"
                    }
                }
                val fold = crossingRails(ringW[k], ringW[k + 1])
                if (fold != null) {
                    return null to
                        "the seam pairs sections ${k + 1} and ${k + 2} so that their rails cross " +
                        "(the ones at ${percent(us[fold.first])} and ${percent(us[fold.second])} of the boundary meet " +
                        "in mid-run), which would fold the shell through itself — start the correspondence at " +
                        "another vertex"
                }
            }
        }
        // The caps are the terminal sections, triangulated and **conformed to the sampled ring** — the same
        // T-junction rule a prism's caps follow (see [prismMesh]): the band's first row runs through every
        // sampled parameter, so the cap has to as well or the shell has a crack in it.
        val caps = ArrayList<Pair<LoftPrep, List<Tri3>>>(2)
        val capAsIs = ArrayList<Boolean>(2)
        for (k in listOf(0, sections.size - 1)) {
            val p = preps[k] ?: continue
            val (tris, why) = triangulate(p.tess)
            if (tris == null) return null to "section ${k + 1}: ${why ?: "cannot be triangulated"}"
            val (split, why2) = splitToRequired(tris, ring2[k]!!)
            if (split == null) return null to (why2 ?: "cannot close section ${k + 1}")
            caps.add(p to split)
            // a cap triangle maps to the world with its normal along +plane.normal; the outward one is against
            // the run at the first section and along it at the last, and `hand` is which of the two that is
            capAsIs.add(if (k == 0) hand[k] < 0 else hand[k] > 0)
        }

        // ---- the shell ----
        // **One level** ([Solid3.meshAt]): a loft's row count comes from its guides' own sampled polylines,
        // which live inside the correspondence plan along with the global boundary parameter set every ring
        // is built on — so coarsening it would mean redoing the expensive half to save the cheap one.
        return Solid3.derivedFine(Feature3.Loft(sections, seams, guides)) {
            val mb = MeshBuilder()
            for (k in 0 until sections.size - 1) {
                val here = rails.filter { it.touch.containsKey(k) && it.touch.containsKey(k + 1) }
                val steps = if (here.isEmpty()) 1 else here.maxOf { stepsOf(it, k) }
                val rows =
                    (0..steps).map { i ->
                        val t = i.toDouble() / steps
                        val bows = here.map { bowOf(it, k, t) }
                        (0 until m).map { j ->
                            var p = ringW[k][j] * (1.0 - t) + ringW[k + 1][j] * t
                            if (here.isNotEmpty()) {
                                val w = weightsAt(here.map { it.param }, us[j])
                                for ((gi, bow) in bows.withIndex()) p += bow * w[gi]
                            }
                            p
                        }
                    }
                for (i in 0 until steps) {
                    for (j in 0 until m) {
                        val j2 = (j + 1) % m
                        mb.triangle(rows[i][j], rows[i][j2], rows[i + 1][j2])
                        mb.triangle(rows[i][j], rows[i + 1][j2], rows[i + 1][j])
                    }
                }
            }
            for ((ci, cap) in caps.withIndex()) {
                val (p, split) = cap
                for (t in split) {
                    val a = p.plane.toWorld(t.a)
                    val b = p.plane.toWorld(t.b)
                    val c = p.plane.toWorld(t.c)
                    if (capAsIs[ci]) mb.triangle(a, b, c) else mb.triangle(a, c, b)
                }
            }
            mb.build()
        } to null
    }

    /**
     * [loop] as a polygon plus, per boundary piece, the polygon index that piece **starts at** — which is the
     * seam's own durable name (OP-8's provenance order, the same one `sideFace` indexes).
     *
     * Consecutive pieces share an endpoint, so a piece's start is the previous piece's last point; the closing
     * duplicate is dropped exactly as [tessellateLoop] drops it.
     */
    private fun loopWithStarts(
        loop: Loop,
        tolMm: Double,
    ): Pair<List<Vec2>, List<Int>> {
        val pts = ArrayList<Vec2>()
        val starts = ArrayList<Int>()
        for (e in loop.elements) {
            starts.add(if (pts.isEmpty()) 0 else pts.size - 1)
            for (p in GeomMath.tessellatePiece(e, tolMm)) {
                if (pts.isEmpty() || (p - pts.last()).length() > WELD_TOL) pts.add(p)
            }
        }
        while (pts.size > 1 && (pts.first() - pts.last()).length() <= WELD_TOL) pts.removeAt(pts.size - 1)
        val n = max(1, pts.size)
        return pts to starts.map { it % n }
    }

    private fun averageOf(poly: List<Vec2>): Vec2 {
        var s = Vec2(0.0, 0.0)
        for (p in poly) s += p
        return s * (1.0 / poly.size)
    }

    /** Cumulative arc length of a closed polygon: `size + 1` entries, the last one its perimeter. */
    private fun cumulativeOf(poly: List<Vec2>): List<Double> {
        val cum = ArrayList<Double>(poly.size + 1)
        cum.add(0.0)
        for (i in poly.indices) cum.add(cum.last() + (poly[(i + 1) % poly.size] - poly[i]).length())
        return cum
    }

    /** The boundary point at normalized arc length [u] — *on* the polygon, so it approximates nothing. */
    private fun pointAtParam(
        poly: List<Vec2>,
        cum: List<Double>,
        u: Double,
    ): Vec2 {
        val target = u.mod(1.0) * cum.last()
        var i = 0
        while (i < poly.size - 1 && cum[i + 1] < target) i++
        val len = cum[i + 1] - cum[i]
        val a = poly[i]
        val b = poly[(i + 1) % poly.size]
        if (len <= WELD_TOL) return a
        return a + (b - a) * ((target - cum[i]) / len)
    }

    /** Cumulative arc length of an open 3D polyline. */
    private fun cumulative3(poly: List<Vec3>): List<Double> {
        val cum = ArrayList<Double>(poly.size)
        cum.add(0.0)
        for (i in 0 until poly.size - 1) cum.add(cum.last() + (poly[i + 1] - poly[i]).length())
        return cum
    }

    /** The point of an open polyline at arc length [s], clamped to its ends. */
    private fun pointAtArc(
        poly: List<Vec3>,
        cum: List<Double>,
        s: Double,
    ): Vec3 {
        if (poly.size == 1) return poly[0]
        val t = s.coerceIn(0.0, cum.last())
        var i = 0
        while (i < poly.size - 2 && cum[i + 1] < t) i++
        val len = cum[i + 1] - cum[i]
        if (len <= WELD_TOL) return poly[i]
        return poly[i] + (poly[i + 1] - poly[i]) * ((t - cum[i]) / len)
    }

    /**
     * The guide [g] resolved against the sections: which of them it passes through, at which boundary
     * parameter, and where along itself — or null with the reason it cannot be honoured.
     *
     * A guide is *found* rather than declared, and that is deliberate: where it attaches is a value, so a guide
     * that a parameter edit pulls off a section makes the loft invalid **with that as the reason** and heals
     * when it is pulled back (OP-3), instead of quietly shaping something else.
     */
    private fun railOf(
        gi: Int,
        g: LoftGuide,
        sections: List<LoftSection>,
        preps: Array<LoftPrep?>,
        cums: List<List<Double>?>,
        perims: List<Double>,
        tolMm: Double,
    ): Pair<LoftRail?, String?> {
        val flat = ArrayList<Vec2>()
        for (e in g.pieces) {
            for (p in GeomMath.tessellatePiece(e, tolMm)) {
                if (flat.isEmpty() || (p - flat.last()).length() > WELD_TOL) flat.add(p)
            }
        }
        if (flat.size < 2) return null to "guide ${gi + 1} is not a curve — it has no length to run along"
        val poly = flat.map { g.plane.toWorld(it) }
        val cum = cumulative3(poly)
        if (cum.last() <= WELD_TOL) return null to "guide ${gi + 1} has no length"
        val touch = HashMap<Int, Pair<Double, Double>>()
        for (k in sections.indices) {
            val p = preps[k] ?: continue
            val hit = crossing(poly, cum, p, cums[k]!!, tolMm) ?: continue
            touch[k] = hit
        }
        if (touch.size < 2) {
            return null to
                "guide ${gi + 1} passes through ${touch.size} of the loft's sections — a guide must pass through " +
                "the corresponding point of at least two of them"
        }
        val spanned = touch.keys.sorted()
        if (spanned.last() - spanned.first() + 1 != spanned.size) {
            return null to
                "guide ${gi + 1} skips section ${(spanned.first()..spanned.last()).first { it !in touch }.plus(1)} — " +
                "a guide shapes a consecutive run of sections"
        }
        val ref = touch[spanned.first()]!!.first
        for (k in spanned) {
            val u = touch[k]!!.first
            val off = circularDistance(u, ref) * perims[k]
            if (off > max(tolMm, 10.0 * WELD_TOL)) {
                return null to
                    "guide ${gi + 1} meets section ${spanned.first() + 1} at ${percent(ref)} of its boundary but " +
                    "section ${k + 1} at ${percent(u)} — that is ${round(off * 100.0) / 100.0} mm along section " +
                    "${k + 1}'s boundary from the corresponding point, and a guide must pass through " +
                    "corresponding points"
            }
        }
        return LoftRail(poly, cum, touch, ref) to null
    }

    /**
     * Where the polyline [poly] meets section [p]'s boundary: the boundary parameter and the guide's own arc
     * parameter, or null when it does not come within [tolMm] of it.
     *
     * Found through the section's **plane** first (a guide that shapes a run has to leave that plane, so it
     * crosses it), then measured against the boundary polygon there — which is what makes "passes through the
     * boundary" a question with one answer rather than a search over two curves.
     */
    private fun crossing(
        poly: List<Vec3>,
        cum: List<Double>,
        p: LoftPrep,
        cumP: List<Double>,
        tolMm: Double,
    ): Pair<Double, Double>? {
        val n = p.plane.normal.normalized()
        val o = p.plane.origin
        val candidates = ArrayList<Pair<Vec3, Double>>()
        for (i in poly.indices) {
            val d = (poly[i] - o).dot(n)
            if (abs(d) <= max(tolMm, 10.0 * WELD_TOL)) candidates.add(poly[i] to cum[i])
            if (i == poly.size - 1) continue
            val d2 = (poly[i + 1] - o).dot(n)
            if (d * d2 < 0.0) {
                val f = d / (d - d2)
                candidates.add(poly[i] + (poly[i + 1] - poly[i]) * f to cum[i] + (cum[i + 1] - cum[i]) * f)
            }
        }
        var best: Pair<Double, Double>? = null
        var bestDist = Double.MAX_VALUE
        for ((x, s) in candidates) {
            val local = Vec2((x - o).dot(p.plane.u), (x - o).dot(p.plane.v))
            val (dist, u) = nearestOnPolygon(local, p.poly, cumP)
            if (dist < bestDist) {
                bestDist = dist
                best = u to s / cum.last()
            }
        }
        return if (bestDist <= max(tolMm, 10.0 * WELD_TOL)) best else null
    }

    /** The distance from [q] to the closed polygon [poly], and the normalized parameter of the nearest point. */
    private fun nearestOnPolygon(
        q: Vec2,
        poly: List<Vec2>,
        cum: List<Double>,
    ): Pair<Double, Double> {
        var bestDist = Double.MAX_VALUE
        var bestU = 0.0
        for (i in poly.indices) {
            val a = poly[i]
            val b = poly[(i + 1) % poly.size]
            val d = b - a
            val len = d.length()
            val t = if (len <= WELD_TOL) 0.0 else ((q - a).dot(d) / (len * len)).coerceIn(0.0, 1.0)
            val at = a + d * t
            val dist = (q - at).length()
            if (dist < bestDist) {
                bestDist = dist
                bestU = (cum[i] + len * t) / cum.last()
            }
        }
        return bestDist to bestU
    }

    /**
     * The first pair of **rails that meet** between two rings, or null when none do.
     *
     * A rail lies on the loft's surface, so two of them meeting anywhere but at a shared end is the surface
     * passing through itself — which is exactly what a seam turned too far produces (pair two squares corner to
     * *opposite* corner and the two diagonal rails cross in mid-run). Refusing it is the watertight-or-refused
     * doctrine applied where a triangle count cannot see the problem: such a shell is closed and consistently
     * wound, and still not a solid.
     *
     * What this does **not** claim is that the surface is free of self-intersection everywhere — two rails may
     * pass close without meeting while the patch between them folds. That limit is recorded in DESIGN.md; the
     * degenerate cases a user actually reaches are the ones where rails meet.
     */
    private fun crossingRails(
        a: List<Vec3>,
        b: List<Vec3>,
    ): Pair<Int, Int>? {
        val m = a.size
        for (i in 0 until m) {
            val lo1 = Vec3(min(a[i].x, b[i].x), min(a[i].y, b[i].y), min(a[i].z, b[i].z))
            val hi1 = Vec3(max(a[i].x, b[i].x), max(a[i].y, b[i].y), max(a[i].z, b[i].z))
            for (j in i + 1 until m) {
                // a shared end is not a meeting: adjacent rails of a collapsing run legitimately share one
                if ((a[i] - a[j]).length() <= WELD_TOL || (b[i] - b[j]).length() <= WELD_TOL) continue
                val lo2 = Vec3(min(a[j].x, b[j].x), min(a[j].y, b[j].y), min(a[j].z, b[j].z))
                val hi2 = Vec3(max(a[j].x, b[j].x), max(a[j].y, b[j].y), max(a[j].z, b[j].z))
                if (hi1.x < lo2.x - WELD_TOL || hi2.x < lo1.x - WELD_TOL) continue
                if (hi1.y < lo2.y - WELD_TOL || hi2.y < lo1.y - WELD_TOL) continue
                if (hi1.z < lo2.z - WELD_TOL || hi2.z < lo1.z - WELD_TOL) continue
                if (segmentDistance(a[i], b[i], a[j], b[j]) <= 10.0 * WELD_TOL) return i to j
            }
        }
        return null
    }

    /** The closest distance between two 3D segments — the standard clamped-parameter form. */
    private fun segmentDistance(
        p1: Vec3,
        q1: Vec3,
        p2: Vec3,
        q2: Vec3,
    ): Double {
        val d1 = q1 - p1
        val d2 = q2 - p2
        val r = p1 - p2
        val a = d1.dot(d1)
        val e = d2.dot(d2)
        val f = d2.dot(r)
        var s: Double
        var t: Double
        if (a <= Vec3.EPS && e <= Vec3.EPS) return r.length()
        if (a <= Vec3.EPS) {
            s = 0.0
            t = (f / e).coerceIn(0.0, 1.0)
        } else {
            val c = d1.dot(r)
            if (e <= Vec3.EPS) {
                t = 0.0
                s = (-c / a).coerceIn(0.0, 1.0)
            } else {
                val b = d1.dot(d2)
                val denom = a * e - b * b
                s = if (denom > Vec3.EPS) ((b * f - c * e) / denom).coerceIn(0.0, 1.0) else 0.0
                t = (b * s + f) / e
                if (t < 0.0) {
                    t = 0.0
                    s = (-c / a).coerceIn(0.0, 1.0)
                } else if (t > 1.0) {
                    t = 1.0
                    s = ((b - c) / a).coerceIn(0.0, 1.0)
                }
            }
        }
        return ((p1 + d1 * s) - (p2 + d2 * t)).length()
    }

    /** Distance between two boundary parameters, the short way round — the parameter is circular. */
    private fun circularDistance(
        a: Double,
        b: Double,
    ): Double {
        val d = abs(a.mod(1.0) - b.mod(1.0))
        return min(d, 1.0 - d)
    }

    /**
     * How finely a band shaped by [rail] is sampled along the run: the guide's **own** tessellation between the
     * two sections, which is where its curvature already is (OP-15's determinism rule — no adaptive pass).
     */
    private fun stepsOf(
        rail: LoftRail,
        k: Int,
    ): Int {
        val a = rail.touch[k]!!.second * rail.cum.last()
        val b = rail.touch[k + 1]!!.second * rail.cum.last()
        val lo = min(a, b)
        val hi = max(a, b)
        val inner = rail.cum.count { it > lo + WELD_TOL && it < hi - WELD_TOL }
        return max(4, inner + 1)
    }

    /** A guide's deviation from its own chord at run parameter [t] — zero at both sections, by construction. */
    private fun bowOf(
        rail: LoftRail,
        k: Int,
        t: Double,
    ): Vec3 {
        val total = rail.cum.last()
        val a = rail.touch[k]!!.second * total
        val b = rail.touch[k + 1]!!.second * total
        val g = pointAtArc(rail.poly, rail.cum, a + (b - a) * t)
        val g0 = pointAtArc(rail.poly, rail.cum, a)
        val g1 = pointAtArc(rail.poly, rail.cum, b)
        return g - (g0 * (1.0 - t) + g1 * t)
    }

    /**
     * How much each guide shapes the rail at boundary parameter [u]: **linear between adjacent guides**, on the
     * circular parameter.
     *
     * A partition of unity by construction, and the three properties that matter fall out of it: a guide's own
     * rail follows it *exactly* (its weight is 1 there), a guide has no influence past its neighbours (so
     * shaping one side of a duct leaves the other side straight), and a **single** guide weighs 1 everywhere —
     * which is not a special case but the same rule with one guide either side, and it means one guide displaces
     * the whole run rather than denting it at one parameter. An inverse-distance blend was the alternative and
     * is worse in exactly one visible way: every guide would bulge the far side of the section too.
     */
    private fun weightsAt(
        params: List<Double>,
        u: Double,
    ): List<Double> {
        val n = params.size
        if (n == 1) return listOf(1.0)
        val order = params.indices.sortedBy { params[it] }
        val w = DoubleArray(n)
        for (idx in order.indices) {
            val i = order[idx]
            val j = order[(idx + 1) % n]
            val span = (params[j] - params[i]).mod(1.0)
            if (span <= 1e-12) continue
            val f = (u - params[i]).mod(1.0)
            if (f <= span + 1e-12) {
                val t = (f / span).coerceIn(0.0, 1.0)
                w[i] += 1.0 - t
                w[j] += t
                return w.toList()
            }
        }
        val best = params.indices.minByOrNull { circularDistance(u, params[it]) } ?: 0
        w[best] = 1.0
        return w.toList()
    }

    private fun percent(u: Double): String {
        val v = round(u.mod(1.0) * 1000.0) / 10.0
        return "$v%"
    }

    // ---- exact prismatic booleans (OP-22) ----
    // A boolean between two solids extruded along the SAME axis decomposes into z-breakpoints times 2D
    // region booleans, and is therefore *exact* — no BSP split, no coplanar-face heuristic, no repair
    // pass. Anything else is refused and waits for Manifold (OP-9).

    /** Heights closer together than this (mm) are one level of the stack. */
    const val Z_EPS = 1e-7

    private const val NOT_PRISMATIC =
        "this solid is not a prism, so it has no same-axis boolean; general booleans arrive with Manifold (OP-9)"

    /**
     * The **prismatic reading** of a feature (OP-22), or null with a reason.
     *
     * An extrusion becomes one slab — its curved boundary pieces tessellated, which is where the
     * exactness of the *analytic* circle is traded for the exactness of the *algebra* (OP-15's
     * approximated-curve rule, one dimension down). A prism is already one. A revolve is refused rather
     * than approximated: its faces are not vertical, so nothing here can describe it, and guessing would
     * be exactly the leaky general CSG this whole design avoids.
     */
    fun prismatic(
        feature: Feature3,
        tolMm: Double = GeomMath.TESS_TOL_MM,
    ): Pair<Feature3.Prism?, String?> =
        when (feature) {
            is Feature3.Prism -> feature to null
            is Feature3.Revolution -> null to NOT_PRISMATIC
            is Feature3.MeshBoolean -> null to NOT_PRISMATIC
            // An imported body is a mesh with no analytic form at all, so it has no prism reading either.
            is Feature3.Imported -> null to NOT_PRISMATIC
            // A loft's whole point is that its cross-section changes along the run, so it is a prism only in
            // the degenerate case that is an extrude anyway — refused rather than approximated, like a
            // revolve, and the general engine (OP-9) takes it from here.
            is Feature3.Loft -> null to NOT_PRISMATIC
            // A sweep's axis is a curve, so there is no one direction to stack slabs along at all (OP-26).
            is Feature3.Sweep -> null to NOT_PRISMATIC
            // A blend's walls are rounded, so it is not a stack of slabs however prismatic its base was —
            // refused rather than approximated by the base, which would silently give the exact algebra a
            // body that is not the body (OP-22's dispatch is a *predicate*, and it must not lie).
            is Feature3.Blend -> null to NOT_PRISMATIC
            is Feature3.Extrusion -> oneSlab(feature, tolMm)
        }

    /**
     * Whether the **exact** path (OP-22) applies to these two features at all: both prismatic, along a
     * common axis. The dispatch predicate the boolean ops ask *before* running anything, so that the exact
     * algebra's own refusals (an empty result, an inconsistent arrangement) stay refusals and are not
     * quietly answered by the mesh engine instead — the one thing that must never happen, because a
     * silently-degraded exact path is indistinguishable from a correct one until it leaks.
     *
     * Cheap on purpose: only the *axis* is examined, not the areas, so the check costs no tessellation.
     */
    fun sameAxis(
        a: Feature3,
        b: Feature3,
    ): Boolean {
        val pa = axisPlaneOf(a) ?: return false
        val pb = axisPlaneOf(b) ?: return false
        return abs(abs(pa.normal.normalized().dot(pb.normal.normalized())) - 1.0) <= 1e-9
    }

    /** The plane a prismatic feature is swept along, or null when the feature has no prismatic reading. */
    private fun axisPlaneOf(feature: Feature3): Plane3? =
        when (feature) {
            is Feature3.Extrusion -> feature.sketch.plane
            is Feature3.Prism -> feature.plane
            is Feature3.Revolution -> null
            is Feature3.MeshBoolean -> null
            is Feature3.Imported -> null
            is Feature3.Loft -> null
            is Feature3.Sweep -> null
            // Null and **not** the base's plane: this predicate decides whether the exact algebra runs, and
            // a blend has no prismatic reading to run it on ([prismatic] refuses it). Naming an axis here
            // would send a blended body down a path that then declines — and the exact path's refusals are
            // never retried on the mesh engine, by design ([combine]).
            is Feature3.Blend -> null
        }

    private fun oneSlab(
        feature: Feature3.Extrusion,
        tolMm: Double,
    ): Pair<Feature3.Prism?, String?> {
        if (feature.depth <= WELD_TOL) return null to "extrude depth must be positive"
        val (rings, why) = RegionBool.ringsOf(feature.sketch.regions, tolMm)
        if (rings == null) return null to why
        val (regions, why2) = RegionBool.regionsOf(RegionBool.canonical(rings))
        if (regions == null) return null to why2
        return Feature3.Prism(feature.sketch.plane, listOf(Slab(regions, 0.0, feature.depth))) to null
    }

    /**
     * [kind] applied to two solids (OP-22). Both must be prismatic **along a common axis**; the result is
     * a prism, so booleans compose.
     *
     * The algebra: take every slab boundary of either operand as a z-breakpoint, and on each resulting
     * z-interval apply the 2D kernel ([RegionBool]) to the two operands' areas there. Adjacent output
     * slabs whose areas come out identical are merged back, so a union of two storeys with the same
     * footprint is one shaft with no seam in it rather than two boxes touching.
     *
     * An **empty** result is refused with a reason rather than returned as a solid with no material: it
     * is an ordinary invalid node, hidden and healing when a parameter moves back (OP-3).
     */
    fun boolean(
        kind: BoolOp,
        a: Solid3,
        b: Solid3,
        tolMm: Double = GeomMath.TESS_TOL_MM,
    ): Pair<Solid3?, String?> {
        val (pa, whyA) = prismatic(a.feature, tolMm)
        if (pa == null) return null to (whyA ?: NOT_PRISMATIC)
        val (pb, whyB) = prismatic(b.feature, tolMm)
        if (pb == null) return null to (whyB ?: NOT_PRISMATIC)
        val (slabsB, whyM) = onAxisOf(pb, pa.plane)
        if (slabsB == null) return null to (whyM ?: NOT_PRISMATIC)
        val slabsA = pa.slabs

        val levels = levelsOf(slabsA + slabsB)
        val out = ArrayList<Slab>()
        for (i in 0 until levels.size - 1) {
            val z0 = levels[i]
            val z1 = levels[i + 1]
            if (z1 - z0 <= Z_EPS) continue
            val (rings, why) = RegionBool.combine(ringsBetween(slabsA, z0, z1), ringsBetween(slabsB, z0, z1), kind)
            if (rings == null) return null to (why ?: "the boolean failed on the slice $z0..$z1 mm")
            if (rings.isEmpty()) continue
            val (regions, why2) = RegionBool.regionsOf(rings)
            if (regions == null) return null to (why2 ?: "the boolean produced an area that does not nest")
            out.add(Slab(regions, z0, z1))
        }
        val merged = mergeSlabs(out)
        if (merged.isEmpty()) return null to "the boolean leaves nothing of the solid"
        val prism = Feature3.Prism(pa.plane, merged)
        // The analytic boolean is analytic all the way: its result is a prism, so it refuses like one and its
        // triangles wait like one — nothing here reads either operand's mesh (see `Construction.booleanValue`
        // for the general engine, which does and therefore cannot wait).
        val (shell, whyMesh) = prismShell(prism, tolMm)
        if (shell == null) return null to (whyMesh ?: "cannot build the boolean's mesh")
        // **One level** ([Solid3.meshAt]): a prism's cost is its region algebra and the one global corner set
        // every ring is conformed to, not its chords — so a coarse level would redo all of the expensive part
        // to save the cheap one, and a body whose walls are flat has nothing to gain by it anyway.
        return Solid3.derivedFine(prism, shell) to null
    }

    /**
     * **The one place that decides which engine runs** (OP-22 first, then OP-9): the exact slab algebra for
     * two prisms sharing an axis, the general engine for everything else.
     *
     * It lives here rather than inside `Construction` because it has three callers now and *"which path did
     * this take"* is the one question about a boolean that must have a single answer — the boolean ops
     * (`Construction.booleanValue`), the cut by an unbounded chain, and the **edge blend** ([Blend3]), whose
     * swept wedge is applied to the body by exactly this dispatch and no other. A second copy of it would be
     * a second thing to keep in step.
     *
     * The exact path's refusals stay refusals and are never retried on the mesh engine: a general boolean
     * that quietly answered where the exact one declined would make the exact path impossible to trust.
     */
    fun combine(
        kind: BoolOp,
        a: Solid3,
        b: Solid3,
    ): Pair<Solid3?, String?> =
        if (sameAxis(a.feature, b.feature)) {
            boolean(kind, a, b)
        } else {
            val (mesh, why) = MeshBool.boolean(kind, a.mesh, b.mesh)
            if (mesh == null) null to why else Solid3.of(Feature3.MeshBoolean(kind), mesh) to null
        }

    /**
     * Whether the **watertight** [mesh] encloses [p] — the generalized winding number, summed over the
     * triangles as signed solid angles and rounded.
     *
     * A ray cast would answer the same question and is what [rayMesh] already does for a *pick*; this is not
     * that, because a pick may miss and a containment question may not. A ray through a vertex, along an
     * edge, or exactly in the plane of a triangle has to be adjudicated, and every adjudication is a
     * tolerance that is wrong somewhere. The solid angle has no such cases: it is continuous in [p], exactly
     * `4π` inside and exactly `0` outside for a closed oriented shell, and its only degeneracy is a point
     * *on* the surface — which is a question nobody asks it (the blend probes a stated distance off the
     * crease, [Blend3]).
     *
     * Its one caller reads it as a **measurement scored once and then stored** (OP-1): which side of a crease
     * the material is on decides subtract-or-add, the answer goes into the step's `signs=`, and nothing ever
     * asks again. So this reads triangles without breaking *never rediscover* — no name, no index and no
     * structure comes out of it, only a yes or a no about one point.
     */
    fun encloses(
        mesh: Mesh3,
        p: Vec3,
    ): Boolean {
        var omega = 0.0
        for (tri in mesh.triangles) {
            val a = mesh.vertices[tri.a] - p
            val b = mesh.vertices[tri.b] - p
            val c = mesh.vertices[tri.c] - p
            val la = a.length()
            val lb = b.length()
            val lc = c.length()
            if (la <= Vec3.EPS || lb <= Vec3.EPS || lc <= Vec3.EPS) continue
            val num = a.dot(b.cross(c))
            val den = la * lb * lc + a.dot(b) * lc + b.dot(c) * la + c.dot(a) * lb
            omega += 2.0 * atan2(num, den)
        }
        return abs(omega) > 2.0 * PI
    }

    /**
     * [prism]'s slabs re-expressed in the frame [ref] — the step that makes "same axis" concrete.
     *
     * The two prisms must have **parallel** normals (either direction); everything else about the frames
     * may differ, because the map from one in-plane frame to the other is then a rigid motion of the 2D
     * coordinates and therefore preserves the polygons exactly. An anti-parallel normal reverses both the
     * height direction and the in-plane orientation, so the heights are swapped and every ring is
     * reversed — which is why this is worth doing properly rather than demanding identical frames: a
     * solid extruded from a *flipped* face plane is a perfectly ordinary operand.
     */
    private fun onAxisOf(
        prism: Feature3.Prism,
        ref: Plane3,
    ): Pair<List<Slab>?, String?> {
        val n = ref.normal.normalized()
        val nb = prism.plane.normal.normalized()
        val dot = n.dot(nb)
        if (abs(abs(dot) - 1.0) > 1e-9) {
            return null to "the two solids are not extruded along a common axis; general booleans arrive with Manifold (OP-9)"
        }
        val flip = dot < 0.0
        val base = (prism.plane.origin - ref.origin).dot(n)
        val slabs = ArrayList<Slab>(prism.slabs.size)
        for (s in prism.slabs) {
            val (rings, why) = RegionBool.ringsOf(s.regions)
            if (rings == null) return null to why
            val mapped =
                rings.map { ring ->
                    val moved =
                        ring.map { p ->
                            val w = prism.plane.toWorld(p)
                            Vec2((w - ref.origin).dot(ref.u), (w - ref.origin).dot(ref.v))
                        }
                    if (flip) moved.reversed() else moved
                }
            val (regions, why2) = RegionBool.regionsOf(RegionBool.canonical(mapped))
            if (regions == null) return null to why2
            val za = base + if (flip) -s.z0 else s.z0
            val zb = base + if (flip) -s.z1 else s.z1
            slabs.add(Slab(regions, min(za, zb), max(za, zb)))
        }
        return slabs.sortedBy { it.z0 } to null
    }

    /** Every slab boundary of [slabs], ascending, with heights within [Z_EPS] welded into one level. */
    private fun levelsOf(slabs: List<Slab>): List<Double> {
        val all = (slabs.map { it.z0 } + slabs.map { it.z1 }).sorted()
        val out = ArrayList<Double>(all.size)
        for (z in all) if (out.isEmpty() || z - out.last() > Z_EPS) out.add(z)
        return out
    }

    /**
     * The rings of the one slab of [slabs] spanning the whole interval [z0]..[z1], or none.
     *
     * Because the interval comes from the *combined* breakpoints, a slab either covers it entirely or
     * misses it — which is what makes the boolean a plain 2D operation per slice with no clipping.
     */
    private fun ringsBetween(
        slabs: List<Slab>,
        z0: Double,
        z1: Double,
    ): List<List<Vec2>> {
        val slab = slabs.firstOrNull { it.z0 <= z0 + Z_EPS && it.z1 >= z1 - Z_EPS } ?: return emptyList()
        return RegionBool.ringsOf(slab.regions).first ?: emptyList()
    }

    /** Adjacent slabs with identical areas merged into one — the interface between them is not a face. */
    private fun mergeSlabs(slabs: List<Slab>): List<Slab> {
        val out = ArrayList<Slab>(slabs.size)
        for (s in slabs) {
            val last = out.lastOrNull()
            if (last != null && abs(last.z1 - s.z0) <= Z_EPS && sameArea(last.regions, s.regions)) {
                out[out.size - 1] = Slab(last.regions, last.z0, s.z1)
            } else {
                out.add(s)
            }
        }
        return out
    }

    /**
     * Whether two areas are the same shape. A structural comparison of the canonical rings
     * ([RegionBool.canonical]) rather than a shape match: the kernel is deterministic, so two slices that
     * *are* the same area come out of it corner for corner.
     */
    private fun sameArea(
        a: List<Region>,
        b: List<Region>,
    ): Boolean {
        val ra = RegionBool.canonical(RegionBool.ringsOf(a).first ?: return false)
        val rb = RegionBool.canonical(RegionBool.ringsOf(b).first ?: return false)
        if (ra.size != rb.size) return false
        for (i in ra.indices) {
            if (ra[i].size != rb[i].size) return false
            for (j in ra[i].indices) if ((ra[i][j] - rb[i][j]).length() > RegionBool.EPS) return false
        }
        return true
    }

    /**
     * The mesh of a prismatic solid (OP-22): side walls per slab, horizontal caps at every level.
     *
     * The two halves and why they close:
     * - **Caps.** At each level the up-facing faces are `areaBelow − areaAbove` and the down-facing ones
     *   `areaAbove − areaBelow`, both by the 2D kernel. The counterbore's annular shoulder is not a case
     *   here — it is what that subtraction *is*. Where the areas agree the difference is empty and there
     *   is no face at all, which is how two storeys with one footprint become a single shaft.
     * - **Walls.** A quad strip along every slab boundary, in the loop's own direction.
     *
     * Watertightness needs one thing beyond that, and it is the only subtlety in this file: a horizontal
     * boundary may **cross** a vertical one (a boss overhanging the plate it sits on), which puts a cap
     * corner in the middle of a wall edge — a T-junction, i.e. a hole in the shell that no triangle
     * count would reveal. So every polygon is made to *conform* to one global corner set: wall edges are
     * split at it ([conform]), and cap triangles are subdivided at it ([splitToRequired]) — the latter
     * after triangulation, because the triangulator legitimately drops collinear corners and would
     * otherwise undo the very split that is needed.
     */
    fun prismMesh(
        prism: Feature3.Prism,
        tolMm: Double = GeomMath.TESS_TOL_MM,
    ): Pair<Mesh3?, String?> {
        val (emit, why) = prismShell(prism, tolMm)
        return if (emit == null) null to why else emit() to null
    }

    /**
     * [prismMesh]'s two halves separated ([Solid3]): every refusal, then the emission that cannot refuse.
     *
     * All of it is a question about the *slabs* — a slab with no height, two that overlap, an area whose
     * rings do not nest, a level whose difference cannot be triangulated — so a prism that cannot be built is
     * an invalid node at evaluation time exactly as it was. What waits is the mapping into space: the wall
     * strips, the conforming of each ring to the global corner set, and the caps' triangles in the world.
     */
    internal fun prismShell(
        prism: Feature3.Prism,
        tolMm: Double = GeomMath.TESS_TOL_MM,
    ): Pair<(() -> Mesh3)?, String?> {
        val slabs = prism.slabs
        if (slabs.isEmpty()) return null to "a prism needs at least one slab"
        for (s in slabs) if (s.height <= WELD_TOL) return null to "a slab of the prism has no height"
        for (i in 0 until slabs.size - 1) {
            if (slabs[i].z1 > slabs[i + 1].z0 + Z_EPS) return null to "the prism's slabs overlap"
        }
        val slabRings = ArrayList<List<List<Vec2>>>(slabs.size)
        for (s in slabs) {
            val (rings, why) = RegionBool.ringsOf(s.regions, tolMm)
            if (rings == null) return null to why
            slabRings.add(rings)
        }

        val caps = ArrayList<Triple<Double, List<List<Vec2>>, Boolean>>()
        for (z in levelsOf(slabs)) {
            val below = slabs.indexOfFirst { abs(it.z1 - z) <= Z_EPS }.let { if (it < 0) emptyList() else slabRings[it] }
            val above = slabs.indexOfFirst { abs(it.z0 - z) <= Z_EPS }.let { if (it < 0) emptyList() else slabRings[it] }
            val (up, whyUp) = RegionBool.combine(below, above, BoolOp.SUBTRACT)
            if (up == null) return null to whyUp
            val (down, whyDown) = RegionBool.combine(above, below, BoolOp.SUBTRACT)
            if (down == null) return null to whyDown
            if (up.isNotEmpty()) caps.add(Triple(z, up, true))
            if (down.isNotEmpty()) caps.add(Triple(z, down, false))
        }

        // the one global corner set every polygon is made to agree with
        val required = (slabRings.flatten() + caps.flatMap { it.second }).flatten().distinct()

        // Every cap's triangles, in the order they will be emitted — this is where a cap can refuse, so it
        // is asked before there is a solid at all.
        val capTris = ArrayList<Triple<Double, Boolean, List<Tri3>>>(caps.size)
        for ((z, rings, up) in caps) {
            val (regions, whyR) = RegionBool.regionsOf(rings)
            if (regions == null) return null to whyR
            for (r in regions) {
                val (tess, why) = tessellateRegion(r, tolMm)
                if (tess == null) return null to why
                val (tris, why2) = triangulate(tess)
                if (tris == null) return null to why2
                val (split, why3) = splitToRequired(tris, required)
                if (split == null) return null to why3
                capTris.add(Triple(z, up, split))
            }
        }

        return {
            val mb = MeshBuilder()
            val n = prism.plane.normal.normalized()

            fun world(
                p: Vec2,
                z: Double,
            ): Vec3 = prism.plane.toWorld(p) + n * z
            for ((si, rings) in slabRings.withIndex()) {
                val z0 = slabs[si].z0
                val z1 = slabs[si].z1
                for (ring in rings) {
                    val poly = conform(ring, required)
                    for (i in poly.indices) {
                        val p = poly[i]
                        val q = poly[(i + 1) % poly.size]
                        mb.triangle(world(p, z0), world(q, z0), world(q, z1))
                        mb.triangle(world(p, z0), world(q, z1), world(p, z1))
                    }
                }
            }
            for ((z, up, split) in capTris) {
                for (t in split) {
                    if (up) {
                        mb.triangle(world(t.a, z), world(t.b, z), world(t.c, z))
                    } else {
                        mb.triangle(world(t.a, z), world(t.c, z), world(t.b, z))
                    }
                }
            }
            mb.build()
        } to null
    }

    /** [ring] with every corner of [required] that lies in the interior of one of its edges inserted. */
    private fun conform(
        ring: List<Vec2>,
        required: List<Vec2>,
    ): List<Vec2> {
        val out = ArrayList<Vec2>(ring.size)
        for (i in ring.indices) {
            val a = ring[i]
            val b = ring[(i + 1) % ring.size]
            out.add(a)
            val d = b - a
            val len = d.length()
            if (len <= RegionBool.EPS) continue
            val inner = ArrayList<Pair<Double, Vec2>>()
            for (p in required) {
                val t = (p - a).dot(d) / (len * len)
                if (t * len <= RegionBool.EPS || (1.0 - t) * len <= RegionBool.EPS) continue
                if (abs(d.cross(p - a)) / len > RegionBool.EPS) continue
                inner.add(t to p)
            }
            inner.sortWith(compareBy({ it.first }, { it.second.x }, { it.second.y }))
            var last = a
            for ((_, p) in inner) {
                if ((p - last).length() > RegionBool.EPS) {
                    out.add(p)
                    last = p
                }
            }
        }
        return out
    }

    /**
     * [tris] subdivided until no corner of [required] lies in the interior of a triangle edge.
     *
     * A point on an edge splits its triangle in two through the opposite corner; both halves keep the
     * winding, the new interior edge is shared by exactly those two, and each split consumes one point —
     * so this terminates and stays manifold. A [required] point on an *interior* diagonal is split on
     * both sides, because the test is a function of the edge alone. The budget is a guard, not a policy:
     * exceeding it refuses the mesh (OP-3) rather than emitting one with a T-junction in it.
     */
    private fun splitToRequired(
        tris: List<Tri3>,
        required: List<Vec2>,
    ): Pair<List<Tri3>?, String?> {
        val out = ArrayList<Tri3>(tris.size)
        val pending = ArrayDeque<Tri3>()
        pending.addAll(tris)
        var budget = 4 * (tris.size + 1) * (required.size + 1)
        while (pending.isNotEmpty()) {
            val t = pending.removeFirst()
            val hit = firstInteriorPoint(t, required)
            if (hit == null) {
                out.add(t)
                continue
            }
            if (budget-- <= 0) return null to "a cap of the prism cannot be split to meet its neighbours"
            val (edge, p) = hit
            when (edge) {
                0 -> {
                    pending.addLast(Tri3(t.a, p, t.c))
                    pending.addLast(Tri3(p, t.b, t.c))
                }
                1 -> {
                    pending.addLast(Tri3(t.b, p, t.a))
                    pending.addLast(Tri3(p, t.c, t.a))
                }
                else -> {
                    pending.addLast(Tri3(t.c, p, t.b))
                    pending.addLast(Tri3(p, t.a, t.b))
                }
            }
        }
        return out to null
    }

    /** The first (edge index, point) of [t] with a [required] corner strictly inside that edge. */
    private fun firstInteriorPoint(
        t: Tri3,
        required: List<Vec2>,
    ): Pair<Int, Vec2>? {
        val ends = listOf(t.a to t.b, t.b to t.c, t.c to t.a)
        for ((k, e) in ends.withIndex()) {
            val d = e.second - e.first
            val len = d.length()
            if (len <= RegionBool.EPS) continue
            for (p in required) {
                val u = (p - e.first).dot(d) / (len * len)
                if (u * len <= RegionBool.EPS || (1.0 - u) * len <= RegionBool.EPS) continue
                if (abs(d.cross(p - e.first)) / len > RegionBool.EPS) continue
                return k to p
            }
        }
        return null
    }

    // ---- provenance accessors (OP-8) ----

    /**
     * The plane of a feature's named [which] face — a *constructed* accessor, so it moves with the
     * parameters and is what makes "sketch on this face" possible without discovered topology.
     *
     * `TOP` keeps the sketch plane's own `u`/`v`, so a sketch placed on it uses the same coordinates as
     * the sketch below — the point of the accessor. `BOTTOM` is the sketch plane flipped, so its normal
     * points out of the solid.
     */
    fun facePlane(
        feature: Feature3,
        which: SolidFace,
    ): Pair<Plane3?, String?> =
        when (feature) {
            is Feature3.Extrusion ->
                when (which) {
                    SolidFace.TOP -> feature.sketch.plane.translated(feature.depth) to null
                    SolidFace.BOTTOM -> feature.sketch.plane.flipped() to null
                }
            // A prism's named faces are the same construction over its own extent (OP-22): the accessor
            // survives a boolean, so a boss can still be sketched on a counterbored plate's top face.
            is Feature3.Prism ->
                when (which) {
                    SolidFace.TOP -> feature.plane.translated(feature.maxZ) to null
                    SolidFace.BOTTOM -> feature.plane.translated(feature.minZ).flipped() to null
                }
            // **Reversed in session 69** (OP-17's item 4 of the sphere queue). The recorded cut read: *"a
            // revolve's end caps are planes too, but they are *rotated* frames, and naming them TOP/BOTTOM
            // would invent a convention this slice has no use for"*. Both halves have since stopped being
            // true. The convention is no longer invented — [Turn3.Arc] is **ordered**, and this very file's
            // emitter already states the rule ("the cap at the interval's low angle faces backwards out of
            // the sweep, the one at its high angle forwards — the same reversed-bottom / upright-top rule
            // the extrude uses"), so BOTTOM = the low-angle cap is a reading of the winding rather than a
            // new choice. And the use exists: a boss on the end of a partial turned part is exactly what
            // *Extrude on face* asks of this accessor. A **complete** revolution still has neither cap,
            // and says so in the words its own kind uses.
            is Feature3.Revolution -> {
                val f = Revolve3.frameOf(feature)
                when {
                    f == null -> null to "the axis of revolution has no direction"
                    f.full ->
                        null to
                            "this solid is a complete revolution, so it has no start and no end and therefore no " +
                            "top or bottom face — put a datum plane where you want to sketch"
                    else -> Revolve3.capPlane(f, which) to null
                }
            }
            // Deliberately refused, for the reason a revolve's caps are: a loft's end faces *are* planes (its
            // terminal sections'), but their frames are the sections' own — which may be tilted relative to
            // each other and absent altogether at an apex — so naming one TOP would invent a convention this
            // slice has no use for. A datum plane reaches any of them (DESIGN.md, the loft's note).
            is Feature3.Loft -> null to "a lofted solid has no named top or bottom face — put a datum plane where you want to sketch"
            // A general boolean's result is a mesh (OP-9's sink rule): its faces are emergent, not
            // constructed, so there is nothing here that a provenance accessor could name.
            is Feature3.MeshBoolean -> null to "a general boolean's result is mesh-only, so it has no named faces (OP-9)"
            // Same rule, same reason: an imported body's faces are emergent triangles, and naming one would
            // be discovery (OP-9, OP-23).
            is Feature3.Imported -> null to "an imported body is mesh-only, so it has no named faces (OP-9)"
            // Refused for the loft's own reason, one step further: a sweep's end caps *are* planes, but their
            // frames are the moving frame's at each end — which for a closed path do not exist at all — so
            // naming one TOP would invent a convention. A datum plane reaches either of them.
            is Feature3.Sweep -> null to "a swept solid has no top or bottom face — put a datum plane where you want to sketch"
            // **The dressed part keeps its named faces** (session 71, slice 3): a blend does not move a face's
            // plane, it trims the face's outline, so `TOP` of a filleted plate is the plate's own top face and
            // a boss sketched on it before the fillet is on it after. That is the whole point of the feature
            // case — a `MeshBoolean` refused here, and everything anchored on a face died when a blend was
            // added. The outline is corrected where a sketch reads one ([Section3.facePatchOfFootprintPiece]).
            is Feature3.Blend -> facePlane(feature.base, which)
        }

    // ---- side faces: the planar faces a boundary piece sweeps (OP-8 provenance, OP-17's frame) ----

    /**
     * One **planar side face** of a prismatic solid: the frame it spans, and how big it is.
     *
     * **The frame is intrinsic** (OP-17, session 32's rule, superseding the top-anchored one): the picked
     * boundary segment lies **on the x axis**, `v` runs **into the face's interior as seen from that
     * segment**, and the normal points **out of the material** — i.e. towards someone looking at the face.
     * Right-handedness (`u × v` = normal) then fixes which way `u` runs along the segment, so nothing about
     * this frame is a stored choice and nothing degenerates when a face turns parallel to a world axis: a
     * face is locally on exactly one side of its own boundary edge, which is the whole of the derivation.
     *
     * The **origin is the segment's midpoint** — the one choice-free point on it — and a space may move it
     * from there by an *anchor* and an in-plane offset (`Document.setSpaceOrigin`), which is a property of
     * the space rather than of this accessor.
     *
     * For an upright prism that reads: the face's own bottom edge on the x axis, `v` = world up, the face
     * itself covering `v` in `0..height` and `u` in `-length/2 .. +length/2`. A solid extruded *downwards*
     * has its footprint edge at the face's **top**, and then `v` runs down — intrinsic, not world-anchored.
     *
     * [length] is the piece's length, [height] the solid's own z-extent — together the rectangle the face
     * covers, which is what a sketch space draws as its reference outline.
     */
    data class SideFace(val plane: Plane3, val length: Double, val height: Double)

    /**
     * The footprint boundary pieces of [feature] in **provenance order** (OP-8): regions in order, each
     * region's outer loop then its holes, pieces in loop order. A side face is named by its index into
     * this list — a constructed accessor, so nothing is ever re-identified from mesh topology.
     *
     * The order is the *construction's* (OP-14 forbids discovering a boundary), with one honest caveat: a
     * `Loop` is normalised counter-clockwise, so a ring the user turns inside out — dragging a rectangle's
     * corner past its opposite — comes back reversed and renames its own edges. That is the same
     * order-of-traversal limit OP-20 records for a reversed host line, and it is unreachable for a
     * footprint that keeps its handedness.
     */
    fun boundaryPieces(feature: Feature3): List<ProfileElement> =
        feature.footprint.flatMap { r -> r.outer.elements + r.holes.flatMap { it.elements } }

    /** The plane a prismatic feature is swept along, with its axis span — null when it has no prism form. */
    private fun prismSpan(feature: Feature3): Triple<Plane3, Double, Double>? =
        when (feature) {
            is Feature3.Extrusion -> Triple(feature.sketch.plane, 0.0, feature.depth)
            is Feature3.Prism -> Triple(feature.plane, feature.minZ, feature.maxZ)
            is Feature3.Revolution -> null
            is Feature3.MeshBoolean -> null
            is Feature3.Imported -> null
            is Feature3.Loft -> null
            is Feature3.Sweep -> null
            // **The base's span**, and only for [sideFace]'s question, which is *which plane a face is*: a
            // blend does not move a face, it trims it, so the frame a sketch on a dressed part's side face
            // is measured in must be the very frame it was measured in before (OP-18 — a stored
            // `sketchspace el= piece=` may not change meaning). What the blend *did* take off that face is
            // in the dressed outline ([Section3.faces]), which is where a boundary belongs; it is
            // deliberately not here, because the one thing that must not follow from this is a *prismatic*
            // reading of a rounded body — [prismatic] refuses one and this is not asked for it.
            is Feature3.Blend -> prismSpan(feature.base)
        }

    /**
     * The side face over boundary piece [piece] of [feature] (OP-8), or null with a reason (OP-3).
     *
     * Refused rather than approximated: a solid with no prism form (a revolve, a general boolean — its
     * faces are emergent, OP-9); a solid whose axis is **not vertical**, where "v = world +Z" is not a
     * direction in the face at all; and a **curved** boundary piece, whose swept face is a cylinder and not
     * a plane. Each heals when the geometry changes, since all of it is a function of the feature.
     */
    fun sideFace(
        feature: Feature3,
        piece: Int,
    ): Pair<SideFace?, String?> {
        val span =
            prismSpan(feature)
                ?: return null to "this solid is not a prism, so it has no constructed side faces (OP-8)"
        val (plane, s0, s1) = span
        val n = plane.normal.normalized()
        if (abs(abs(n.z) - 1.0) > 1e-9) {
            return null to "this solid is not extruded vertically, so its side faces are not upright"
        }
        val pieces = boundaryPieces(feature)
        val p =
            pieces.getOrNull(piece)
                ?: return null to "this solid has no boundary piece #${piece + 1} (it has ${pieces.size})"
        val seg =
            (p as? ProfileElement.Seg)?.segment
                ?: return null to "that boundary edge is curved, so the face it sweeps is not planar — pick a straight edge"
        // n is ±Z, and the frame's u/v therefore lie in the plan, so a point's world height is the plane's
        // own origin height plus its axis coordinate along n.
        val zA = plane.origin.z + n.z * s0
        val zB = plane.origin.z + n.z * s1
        val zLo = min(zA, zB)
        val zHi = max(zA, zB)
        if (zHi - zLo <= WELD_TOL) return null to "this solid has no height, so its side faces have no area"
        // A plane whose normal is −Z maps 2D orientation-reversingly into the world, so the boundary runs
        // the other way round there: traversing the piece backwards is what keeps the material on the left
        // in the world, and hence what makes the piece's right point *out* of the material.
        val forward = n.z > 0.0
        val wa = plane.toWorld(if (forward) seg.a else seg.b)
        val wb = plane.toWorld(if (forward) seg.b else seg.a)
        val d = Vec2(wb.x - wa.x, wb.y - wa.y)
        val len = d.length()
        if (len <= WELD_TOL) return null to "that boundary edge has no length"
        val along = Vec3(d.x / len, d.y / len, 0.0)
        // out of the material: the traversal direction's right, in the world
        val outward = along.cross(Vec3.Z)
        // Where the picked segment lies **on the face**, and which way the material runs from there: the
        // footprint plane's own height, snapped to the near edge of the face's extent. For the ordinary
        // upward extrude that is the bottom edge and the interior is up; a downward one picks its top edge
        // and the interior is down. Intrinsic in both cases — the face is on one side of its own edge.
        val up = plane.origin.z <= (zLo + zHi) / 2
        val v = if (up) Vec3.Z else -Vec3.Z
        // right-handed with the outward normal: u × v = outward
        val u = v.cross(outward)
        val mid = Vec3((wa.x + wb.x) / 2, (wa.y + wb.y) / 2, if (up) zLo else zHi)
        return SideFace(Plane3(mid, u, v), len, zHi - zLo) to null
    }

    // ---- datum planes: an arbitrary sketch plane, by a line and an angle (OP-17, GitHub #6) ----

    /**
     * The **datum plane** through the carrier of the 2D [line] — read in [base]'s own (u, v) — rotated
     * [angle] radians about it, out of [base] (OP-17's datum extension, GitHub #6).
     *
     * The whole frame is [base]'s frame **rotated about the line by [angle], right-hand rule about the
     * line's direction**, re-anchored on the line. Concretely, with `n` = [base]'s normal and
     * `w = n × u` (the in-plane perpendicular, so `u × w = n`):
     *
     * - `u` = the line's direction, embedded in [base]. The hinge is therefore the datum's own u axis, and
     *   a point of the drawing at `v = 0` lies in *both* planes — which is what makes an angle edit rotate
     *   the plane about the line the user picked rather than about anything else.
     * - `v = w·cos θ + n·sin θ`, so `θ = 0` reproduces [base] exactly (same point set, same normal — a
     *   datum at zero degrees *is* the space it was defined in, re-anchored) and `θ = 90°` stands the plane
     *   upright with `v` pointing out of [base] along its normal.
     * - `normal = u × v = n·cos θ − w·sin θ`, i.e. [base]'s normal rotated the same way. **The sign of the
     *   angle therefore flips the normal**, which is the only control a datum has over which side is
     *   "out": unlike a face ([sideFace]) a datum plane has no material to point away from.
     *
     * The origin is the point of the **carrier** nearest [base]'s own 2D origin, mapped into the world —
     * OP-20's anchoring rule verbatim, and for the same reason: an anchor at the picked segment's *start*
     * would slide along the plane whenever that segment is stretched, whereas the carrier's foot moves only
     * when the carrier itself does. Nothing about the datum's coordinates depends on how far the host
     * reaches.
     *
     * Refused (OP-3, healing) only for a line with no direction; every angle is legal, including the ones
     * that give a coplanar datum (0° and 180°, the latter being [base] flipped).
     */
    fun datumPlane(
        base: Plane3,
        line: Line,
        angle: Double,
    ): Pair<Plane3?, String?> {
        val len = line.dir.length()
        if (len <= WELD_TOL) return null to "that line has no direction, so it fixes no sketch plane"
        val dir = line.dir * (1.0 / len)
        // OP-20's anchor: the carrier's nearest-origin point, so stretching the host cannot move the datum
        val anchor = line.origin - dir * line.origin.dot(dir)
        val u = (base.u * dir.x + base.v * dir.y).normalized()
        val n = base.normal.normalized()
        val w = n.cross(u)
        val v = (w * cos(angle) + n * sin(angle)).normalized()
        if (u.length() < Vec3.EPS || v.length() < Vec3.EPS) return null to "that sketch plane's own frame is degenerate"
        return Plane3(base.toWorld(anchor), u, v) to null
    }

    // ---- sections: the downward half of the seam (OP-17), exact for prisms (OP-22) ----

    /**
     * The **horizontal cross-section** of [feature] at world height [height] mm, as 2D areas in **world
     * plan coordinates** — the downward direction of the seam (OP-17: `section(solid, plane) → Region`).
     *
     * For a prismatic solid this is not an approximation of anything: a prism *is* a stack of areas over
     * z-intervals (OP-22), so the section at a height **is** the slab there, corner for corner. A plain
     * [Feature3.Extrusion] is answered from its own analytic sketch rather than from its prismatic
     * reading, so a cut through a bored plate keeps its exact circles — the tessellation that a boolean
     * would force is not needed here.
     *
     * **The boundary rule** (a height landing exactly on a slab interface, within [Z_EPS]): the section
     * shows the material **above** the cut. Stated in the *world*, so it holds for a solid extruded from a
     * flipped face plane too, whose own axis runs downwards — hence the two cases below. A height at the
     * solid's very **top** face is therefore outside every slab and refused, as is one below its bottom:
     * a face is not a section, and saying so is more useful than an empty area (OP-3 — the node is
     * invalid, hidden, and heals when the height moves back).
     *
     * Refused rather than guessed: a revolve (its cross-section is a real analytic problem, cut from this
     * slice), and a prism whose axis is not vertical (a horizontal cut through it is not one of its
     * slabs at all).
     *
     * The areas come back mapped through the sketch plane's own in-plane frame, so they are in the
     * coordinates the 2D canvas draws — the *plan* — and not in the sketch's. For the world XY plane and
     * every plane derived from it by [Plane3.translated] that map is the identity; for a flipped one it is
     * a reflection, which is why it is applied rather than assumed away.
     */
    fun sectionAt(
        feature: Feature3,
        height: Double,
    ): Pair<List<Region>?, String?> {
        val plane: Plane3
        val layers: List<Slab>
        when (feature) {
            is Feature3.Revolution ->
                return null to "a revolved solid has no prismatic cross-section; sectioning one needs an analytic revolve section (OP-17)"
            is Feature3.MeshBoolean ->
                return null to "a general boolean's result is mesh-only, so it has no analytic cross-section (OP-9); slicing its mesh is a separate operation"
            is Feature3.Imported ->
                return null to "an imported body is mesh-only, so it has no analytic cross-section (OP-9)"
            // A loft is not a stack of areas over height intervals — its area varies *continuously* along the
            // run — so it has no slab to answer with. Refused rather than interpolated: the honest answer is
            // an analytic loft section, which is its own piece of work (DESIGN.md, the loft's note).
            is Feature3.Loft ->
                return null to "a lofted solid has no prismatic cross-section, because its area changes along the run; sectioning one needs an analytic loft section (OP-17)"
            // The same answer for the same reason, and a stronger one: a sweep's section is normal to its
            // *path*, which a horizontal cut is not — the station of OP-26's step 4 is where that lives.
            is Feature3.Sweep ->
                return null to "a swept solid has no prismatic cross-section, because its axis is a curve; sectioning one needs an analytic sweep section (OP-26)"
            // A **horizontal** cut of a blended body is not one of the base's slabs: the blend rounds the
            // walls, so the area at a height inside the rounding is not the base's area there. Refused by
            // name rather than answered with the base's — a working plane's section (`Section3.sectionOf`)
            // is the exact reading that does work on a dressed part, and it says so.
            is Feature3.Blend ->
                return null to
                    "this solid is a blended body, so its horizontal cross-section is not one of the base's slabs — " +
                    "the rounding changes the area through the blend; cut it with a working plane instead, " +
                    "whose section of a dressed part is exact and offers inputs"
            is Feature3.Extrusion -> {
                plane = feature.sketch.plane
                // [Slab] is borrowed here as a plain (interval, areas) carrier and never escapes this
                // function, so the polygonal-regions convention of a *stored* slab is not at stake — which
                // is the whole point: these regions are the analytic ones, arcs and all.
                layers = listOf(Slab(feature.sketch.regions, 0.0, feature.depth))
            }
            is Feature3.Prism -> {
                plane = feature.plane
                layers = feature.slabs
            }
        }
        val n = plane.normal.normalized()
        if (abs(abs(n.z) - 1.0) > 1e-9) {
            return null to "this solid is not extruded vertically, so a horizontal cut is not one of its cross-sections"
        }
        // the solid's own axis coordinate of the cut: n is ±Z, so dividing by n.z is multiplying by it
        val s = (height - plane.origin.z) * n.z
        val up = n.z > 0.0
        val hit =
            layers.firstOrNull {
                if (up) s >= it.z0 - Z_EPS && s < it.z1 - Z_EPS else s > it.z0 + Z_EPS && s <= it.z1 + Z_EPS
            }
        if (hit == null) {
            val zs = layers.flatMap { listOf(plane.origin.z + n.z * it.z0, plane.origin.z + n.z * it.z1) }
            return null to "the solid has no material at z = $height mm (it spans ${zs.min()} to ${zs.max()} mm, and its top face is not a section)"
        }
        // sketch coordinates -> world plan coordinates: u and v as the columns of a 2D affine, which is
        // rigid (the frame is orthonormal and its normal is ±Z, so u and v lie in the plan), hence exact.
        val t = Affine(plane.u.x, plane.u.y, plane.v.x, plane.v.y, plane.origin.x, plane.origin.y)
        val out =
            hit.regions.map { r ->
                Region(
                    GeomMath.orient(GeomMath.transform(r.outer, t), ccw = true),
                    r.holes.map { GeomMath.orient(GeomMath.transform(it, t), ccw = false) },
                )
            }
        return out to null
    }

    // ---- measurements (OP-4): scalars, so they may drive new 2D constructions ----

    /**
     * Volume enclosed by [mesh], from the divergence theorem: `V = Σ a·(b×c) / 6` over the triangles.
     * Exact for the mesh (a finite sum of rationals in the vertex coordinates), hence deterministic —
     * approximate only with respect to the *curved* solid, by the tessellation tolerance.
     */
    fun volume(mesh: Mesh3): Double {
        var sum = 0.0
        for (t in mesh.triangles) {
            val a = mesh.vertices[t.a]
            val b = mesh.vertices[t.b]
            val c = mesh.vertices[t.c]
            sum += a.dot(b.cross(c))
        }
        return sum / 6.0
    }

    /** Axis-aligned bounds of [mesh], or null when it has no vertices. */
    fun bounds(mesh: Mesh3): Pair<Vec3, Vec3>? {
        if (mesh.vertices.isEmpty()) return null
        var lo = mesh.vertices[0]
        var hi = mesh.vertices[0]
        for (p in mesh.vertices) {
            lo = Vec3(min(lo.x, p.x), min(lo.y, p.y), min(lo.z, p.z))
            hi = Vec3(max(hi.x, p.x), max(hi.y, p.y), max(hi.z, p.z))
        }
        return lo to hi
    }
}
