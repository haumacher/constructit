package constructit.geom

import kotlin.math.abs

/**
 * A **3D affine map**, `p' = R·p + t` — the column-vector convention [Affine] already uses one dimension
 * down, so the two read the same way.
 *
 * It exists for the placement of solids (the JT-import package, OP-9): a body has to be *moved* without its
 * construction being rebuilt, and a move in 3D is exactly this. Stored as twelve numbers, row-major
 * (`r00 r01 r02 | r10 r11 r12 | r20 r21 r22 | tx ty tz`), because that is the layout every interchange
 * format hands over and the one a step can write as twelve plain decimals.
 *
 * **Rigid is the interesting case and the only one a solid is moved by.** A rigid map (an orthonormal
 * rotation with `det = +1`, plus a translation) preserves every length, every angle and every winding, which
 * is why moving a solid by one degrades **nothing in the honesty ledger** (OP-15): an exact extrusion stays
 * an exact extrusion, its sketch plane is the same frame moved, and its mesh is its own vertices moved. A
 * map that is *not* rigid — a scale, a shear, a mirror — has no such reading, so [Solid3.movedBy] refuses
 * one by name (OP-3) and the only honest thing left to do with it is to apply it to the vertices, which is
 * what an importer does at the boundary (and says so).
 */
class Xform3(
    /** The twelve numbers, row-major: nine of rotation, then the translation. */
    val m: DoubleArray,
) {
    init {
        require(m.size == 12) { "an Xform3 is twelve numbers (3x3 plus a translation), got ${m.size}" }
    }

    /** Where [p] lands. */
    fun apply(p: Vec3): Vec3 =
        Vec3(
            m[0] * p.x + m[1] * p.y + m[2] * p.z + m[9],
            m[3] * p.x + m[4] * p.y + m[5] * p.z + m[10],
            m[6] * p.x + m[7] * p.y + m[8] * p.z + m[11],
        )

    /** Where the **direction** [d] lands: the linear part only, so a plane's frame vectors map correctly. */
    fun linear(d: Vec3): Vec3 =
        Vec3(
            m[0] * d.x + m[1] * d.y + m[2] * d.z,
            m[3] * d.x + m[4] * d.y + m[5] * d.z,
            m[6] * d.x + m[7] * d.y + m[8] * d.z,
        )

    /** The determinant of the linear part: `+1` for a rotation, negative for a map that mirrors. */
    val det: Double
        get() =
            m[0] * (m[4] * m[8] - m[5] * m[7]) -
                m[1] * (m[3] * m[8] - m[5] * m[6]) +
                m[2] * (m[3] * m[7] - m[4] * m[6])

    /**
     * Whether this map is **rigid**: its rows orthonormal to [tol] and its determinant `+1`.
     *
     * [tol] is generous by default because the numbers usually come from a file written in single
     * precision — a JT instance matrix round-trips through `float`, so an exactly rigid pose arrives a few
     * units in the last place off orthonormal, and calling that a shear would refuse every real assembly.
     */
    fun isRigid(tol: Double = 1e-5): Boolean {
        for (i in 0..2) {
            for (j in i..2) {
                var s = 0.0
                for (k in 0..2) s += m[i * 3 + k] * m[j * 3 + k]
                if (abs(s - if (i == j) 1.0 else 0.0) > tol) return false
            }
        }
        return abs(det - 1.0) <= tol
    }

    /** Whether this map moves nothing at all — what lets a placement at the origin cost nothing (OP-5). */
    val isIdentity: Boolean get() = m.indices.all { m[it] == IDENTITY.m[it] }

    /** The twelve numbers as a list — what a step writes and reads back. */
    fun values(): List<Double> = m.toList()

    /** The same linear part, with the translation replaced — what splitting a pose into an anchor needs. */
    fun withTranslation(t: Vec3): Xform3 =
        Xform3(doubleArrayOf(m[0], m[1], m[2], m[3], m[4], m[5], m[6], m[7], m[8], t.x, t.y, t.z))

    companion object {
        val IDENTITY = Xform3(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0))

        /** The map sending the world axes onto [x], [y], [z] and the world origin onto [origin]. */
        fun frame(
            origin: Vec3,
            x: Vec3,
            y: Vec3,
            z: Vec3,
        ): Xform3 =
            Xform3(
                doubleArrayOf(
                    x.x, y.x, z.x,
                    x.y, y.y, z.y,
                    x.z, y.z, z.z,
                    origin.x, origin.y, origin.z,
                ),
            )

        /** Twelve numbers back as a map — the inverse of [values], for a step that recorded one. */
        fun of(values: List<Double>): Xform3 = Xform3(values.toDoubleArray())
    }
}

/**
 * [mesh] with every vertex moved by [x] — and, when [x] mirrors (`det < 0`), every triangle re-wound, so
 * the surface still faces outward.
 *
 * The winding half is not a nicety: OP-9's whole watertight-or-refused guarantee is stated in terms of each
 * directed edge being used once with its reverse used once, and a mirrored mesh that kept its winding
 * encloses a *negative* volume — a solid turned inside out. A mirror is not a placement (see [Xform3]), but
 * an importer that has to bake one into the vertices still has to bake it honestly.
 */
fun Mesh3.movedBy(x: Xform3): Mesh3 {
    if (x.isIdentity) return this
    val vs = vertices.map { x.apply(it) }
    val ts = if (x.det < 0.0) triangles.map { Tri(it.a, it.c, it.b) } else triangles
    return Mesh3(vs, ts)
}

/** [Plane3] moved by the rigid map [x]: the same frame somewhere else. */
fun Plane3.movedBy(x: Xform3): Plane3 = Plane3(x.apply(origin), x.linear(u), x.linear(v))

/** [Sketch3] moved: its plane moves, its 2D regions are unchanged — they are read *in* that frame. */
fun Sketch3.movedBy(x: Xform3): Sketch3 = Sketch3(plane.movedBy(x), regions)

/**
 * A **curve in space** moved by [x] — piece for piece, through the control points (OP-26).
 *
 * Exact, and for the reason the plan projection of a path is exact: an affine map carries a cubic Bézier to
 * the cubic through the mapped control points, and a segment to the segment between its mapped ends. So a
 * placed sweep's spine is the same curve somewhere else, with no resampling anywhere in the statement.
 *
 * **An arc stays an arc and a helix stays a helix, with all their numbers untouched** — and that is a
 * statement about [x], not
 * about the helix: the only maps a solid is moved by are **rigid** ones ([Xform3], [Solid3.movedBy] refuses
 * the rest by name), and a rigid map preserves lengths, so the radius and the pitch are what they were, and
 * `det = +1`, so it preserves handedness too. A mirror would turn a right-hand spring into a left-hand one,
 * which is precisely why a mirror is not a placement here. Only the frame moves.
 */
fun Path3.movedBy(x: Xform3): Path3 =
    Path3(
        elements.map { el ->
            when (el) {
                is Curve3Element.Seg3 -> Curve3Element.Seg3(x.apply(el.start), x.apply(el.end))
                is Curve3Element.Bezier3 ->
                    Curve3Element.Bezier3(x.apply(el.p0), x.apply(el.p1), x.apply(el.p2), x.apply(el.p3))
                is Curve3Element.Arc3 ->
                    Curve3Element.Arc3(
                        x.apply(el.center),
                        x.linear(el.u),
                        x.linear(el.v),
                        el.radius,
                        el.startAngle,
                        el.sweepAngle,
                    )
                is Curve3Element.Helix3 ->
                    Curve3Element.Helix3.about(
                        x.apply(el.origin),
                        x.linear(el.axis),
                        x.linear(el.u),
                        el.radius,
                        el.pitch,
                        el.turns,
                        el.hand,
                    )
            }
        },
        closed,
    )

/**
 * [Feature3] moved by the **rigid** map [x] — the analytic half of a placement, and the reason a placement
 * costs nothing in exactness.
 *
 * Every feature in the vocabulary is "2D data plus a frame", so moving one moves the frames and leaves the
 * 2D data alone: an extrusion's regions, a revolve's in-plane axis, a prism's slabs and a loft's
 * correspondence all keep the coordinates they were built in. The two that carry no frame — a general
 * boolean's result and an imported body — carry no analytic form either, so they are returned unchanged and
 * only their mesh moves (OP-9's sink rule).
 */
fun Feature3.movedBy(x: Xform3): Feature3 =
    when (this) {
        is Feature3.Extrusion -> Feature3.Extrusion(sketch.movedBy(x), depth)
        is Feature3.Revolution -> Feature3.Revolution(sketch.movedBy(x), axisOrigin, axisDir, turn)
        is Feature3.Prism -> Feature3.Prism(plane.movedBy(x), slabs)
        is Feature3.Loft ->
            Feature3.Loft(
                sections.map { s ->
                    when (s) {
                        is LoftSection.Area -> LoftSection.Area(s.sketch.movedBy(x))
                        is LoftSection.Apex -> LoftSection.Apex(x.apply(s.at))
                    }
                },
                seams,
                guides.map { LoftGuide(it.plane.movedBy(x), it.pieces) },
            )
        // A sweep is "2D data plus a frame" like the rest of them: the profile keeps its own coordinates and
        // only the path and the direction the start reference is derived from move. Its **plan is dropped**
        // for [Feature3.Imported]'s reason — that outline is stated in the coordinates of a plane this move
        // invalidates — and whoever moved the body re-projects (`Construction.placeSolid`).
        is Feature3.Sweep -> Feature3.Sweep(path.movedBy(x), profile, x.linear(up), roll, twist, carry = carry)
        is Feature3.MeshBoolean -> this
        // ...and an imported body keeps its provenance but **loses its plan**, deliberately: that outline is
        // stated in the coordinates of a plane this move invalidates, so carrying it over would draw the body
        // where it no longer is. Whoever moved it re-projects (see `Construction.placeSolid`), and a caller
        // that does not gets no hint rather than a wrong one.
        // (the **open-shell flag** rides along, because a rigid motion cannot open or close a surface)
        is Feature3.Imported -> Feature3.Imported(source, openShell = openShell)
        // A blend is a dressing, and a rigid move of a dressed part is the same dressing of the moved part:
        // the addresses are indices into a list that moves with the base, and the radius and the stored signs
        // are frame-free numbers. So the analytic form survives a placement exactly as every other feature's
        // does — which is what keeps a placed blended body sketchable and sectionable (session 71, slice 3).
        is Feature3.Blend -> Feature3.Blend(base.movedBy(x), targets, kind, size, choices)
    }

/**
 * [Solid3] moved by [x] — feature *and* mesh, or a refusal when [x] is not rigid.
 *
 * `result to reason`, the convention every kernel function here follows, so a placement whose plane frame
 * has gone degenerate becomes an invalid node with a reason and heals when it stops being (OP-3).
 *
 * **Lazy through the move** ([Solid3]): whether the frame is rigid is a question about the frame, so the
 * refusal is here, while the turned triangles are derived from the source's when somebody asks for them —
 * a placed body that nobody looks at moves for the price of one feature.
 */
fun Solid3.movedBy(x: Xform3): Pair<Solid3?, String?> {
    if (x.isIdentity) return this to null
    if (!x.isRigid()) {
        return null to
            "a solid can only be placed by a rigid motion (a turn and a shift); this frame scales, shears or mirrors it"
    }
    // The placement mirrors its source's levels: a body that coarsens is turned at whichever quality is
    // asked for (and its own derivation stays deferred through this), while a one-level body stays one
    // level here too, so the same triangles are never turned twice ([Solid3.coarsens]).
    return if (coarsens) {
        Solid3.derived(feature.movedBy(x)) { quality -> meshAt(quality).movedBy(x) } to null
    } else {
        Solid3.derivedFine(feature.movedBy(x)) { mesh.movedBy(x) } to null
    }
}
