package constructit.geom

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * A **sphere as a locus** (OP-28): every point at [radius] from [center], and nothing else.
 *
 * **Not a body.** The tool already builds a ball — a full revolve of a half-disc profile, an ordinary
 * `Feature3.Revolution` with a mesh, a volume, a footprint, an export and a place in a boolean (see the ball's
 * as-built note under *Going to 3D*). This is the concept *behind* that one: the carrier of the sentence
 * *"40 from that corner"*, the thing a construction **intersects**. It has no interior, so it encloses nothing,
 * exports nothing and cuts nothing; what it does is compose.
 *
 * It is what the plane has had since OP-1 and space has never had: in the plane, distance is carried by the
 * circle, `circle ∩ circle` is an ordered solution set and its branch is a stored `Select` sign. In space,
 * points came only from height points (OP-25), riders on curves, projections and section corners — none of
 * which can say *"this far from there"*. A sphere says it, and it composes exactly as its 2D twin does
 * ([Spheres3]).
 *
 * A value rather than a feature, and a *distinct* value rather than a [Solid3] flagged somehow, for the reason
 * the whole 2D/3D seam is built on: what a type may fill is the type system's to enforce, and a locus that
 * could fill a `SolidValue` slot would be a hole in *watertight or refused* (OP-9) rather than a new concept.
 *
 * [radius] is required to be positive, and that is a condition on a **value**, so it is refused where values
 * live — inside the node's `compute` ([constructit.dsl.Construction.sphere]), by name and healing (OP-3).
 */
data class Sphere3(val center: Vec3, val radius: Double) {
    /** The signed **distance field** this locus is the zero set of: negative inside, positive outside. */
    fun sideAt(p: Vec3): Double = (p - center).length() - radius
}

/**
 * The **ordered solution set** of an intersection in space (OP-28): OP-1's [PointSet] one dimension up, and
 * read the same way — an ordered set plus a separate `Select`, never a list addressed by what its members
 * happen to look like.
 *
 * The ordering is stated by whatever produced it and is always a property of the **operands alone**
 * ([Spheres3.trilaterate] takes the side of the three-centre plane, [Spheres3.crossings] takes arc length
 * along the run). How many members there are is a **value**, so an index past the end is node invalidity with
 * a reason that heals (OP-3), exactly as branch 3 of a two-solution quartic is.
 */
data class Point3Set(val points: List<Vec3>)

/**
 * What two sphere loci make of each other (OP-28) — a **circle**, a **touch**, or one of three ways of
 * missing, each named so the node's refusal can be read by whoever drew it (OP-3).
 *
 * A sealed result rather than a nullable circle, because "there is no circle" is four different sentences and
 * a reason nobody can act on is the kind of invalidity that reads as a bug (the rule [
 * constructit.dsl.Construction.select]'s `emptyReason` argument was introduced for).
 */
sealed interface SphereMeet {
    /** They meet in a **circle in space** — the ordinary answer, and exact in every one of its numbers. */
    data class Circle(val circle: Curve3Element.Arc3) : SphereMeet

    /** They **touch** at one point: the circle has collapsed, and a point is not a circle. */
    data class Touch(val at: Vec3) : SphereMeet

    /** Too far apart to meet at all. */
    data object Apart : SphereMeet

    /** One runs entirely inside the other. */
    data object Nested : SphereMeet

    /** Concentric — the same centre, so either the same locus or two that never meet. */
    data object Concentric : SphereMeet
}

/**
 * What three sphere loci make of each other (OP-28) — the **trilateration pair**, ordered.
 *
 * [Pair] is the ordinary answer and it is *ordered*, which is the whole of OP-1's doctrine one dimension up:
 * the two solutions are mirror images in the plane through the three centres, so the branch is *which side of
 * that plane* — see [Spheres3.trilaterate] for the sign convention and for why it is a property of the
 * operands alone.
 */
sealed interface Trilateration {
    /** The two points, [plus] on the positive side of the three-centre plane and [minus] on the other. */
    data class Pair(val plus: Vec3, val minus: Vec3) : Trilateration

    /** The three spheres meet at exactly one point: the pair has collapsed onto the centres' own plane. */
    data class Touch(val at: Vec3) : Trilateration

    /** No point is at all three distances at once. */
    data object None : Trilateration

    /**
     * The three centres lie on **one line**, so there is no plane to take a side of: such spheres meet either
     * in a whole circle or in nothing, and neither is a pair to choose a branch from.
     */
    data object Collinear : Trilateration
}

/**
 * How a sphere locus composes (OP-28) — the three lines of the composition table, and the shared circle
 * behind two of them.
 *
 * Every rule here is stated in terms of the **operands alone**: no viewport, no click, no tessellation. That
 * is what lets an index or a sign into any of these answers be the durable name of one branch (OP-1) — the
 * same drawing always yields the same answer in the same order, so a stored choice means today what it meant
 * when it was made, and a choice is never re-scored on replay.
 */
object Spheres3 {
    /**
     * Below this the circle of two spheres has collapsed and they **touch** — a length in millimetres, and the
     * same order of magnitude the 2D circle–circle intersection calls tangency at ([GeomMath.intersectCC]).
     *
     * Stated as a radius rather than as a distance between centres deliberately: it is the *answer* that is
     * degenerate, and a circle a nanometre across is not a circle a drawing can use.
     */
    const val TOUCH_TOL_MM = 1e-9

    /**
     * The **circle where two sphere loci meet** — OP-28's first composition, and exact.
     *
     * The derivation is one line of algebra and no search. Both spheres' equations subtracted leave a *plane*,
     * perpendicular to the centre line at
     *
     * ```
     * x = (d² + r₁² − r₂²) / 2d        (measured from [a]'s centre, along the unit centre line)
     * ```
     *
     * and the circle is that plane's own section of either sphere, of radius `sqrt(r₁² − x²)` about the point
     * `a.center + axis·x`. So the answer is a [Curve3Element.Arc3] of a full turn — the exact circle the
     * vocabulary already has (OP-26's lift put it there), never a fit and never a chord.
     *
     * The circle's frame is deterministic ([perpendicularTo]) and right-handed about the centre line, so the
     * same two spheres always produce the same numbers — which is what a byte-equal round trip rests on.
     */
    fun meet(
        a: Sphere3,
        b: Sphere3,
    ): SphereMeet {
        val between = b.center - a.center
        val d = between.length()
        if (d < Vec3.EPS) return SphereMeet.Concentric
        if (d > a.radius + b.radius + Vec3.EPS) return SphereMeet.Apart
        if (d < abs(a.radius - b.radius) - Vec3.EPS) return SphereMeet.Nested
        val axis = between * (1.0 / d)
        val x = (d * d + a.radius * a.radius - b.radius * b.radius) / (2.0 * d)
        val h2 = a.radius * a.radius - x * x
        val h = if (h2 > 0.0) sqrt(h2) else 0.0
        val at = a.center + axis * x
        if (h < TOUCH_TOL_MM) return SphereMeet.Touch(at)
        return SphereMeet.Circle(circleAbout(at, axis, h))
    }

    /**
     * The **circle where a sphere locus meets a plane** — the same final step [meet] takes once it has reduced
     * two spheres to one plane, offered as itself because it *is* that step.
     *
     * Kernel-only in this cut: the gesture it would want is a plane-valued tool slot, which the tool table has
     * lacked since session 16 and which is recorded as this package's one deliberate cut (see OP-28).
     */
    fun meetPlane(
        s: Sphere3,
        plane: Plane3,
    ): SphereMeet {
        val n = plane.normal.normalized()
        val x = (s.center - plane.origin).dot(n)
        val h2 = s.radius * s.radius - x * x
        if (h2 < 0.0 && sqrt(-h2) > TOUCH_TOL_MM) return SphereMeet.Apart
        val h = if (h2 > 0.0) sqrt(h2) else 0.0
        val at = s.center - n * x
        if (h < TOUCH_TOL_MM) return SphereMeet.Touch(at)
        return SphereMeet.Circle(circleAbout(at, n, h))
    }

    /**
     * The **trilateration pair** of three sphere loci — OP-28's second composition, and the answer to *"40 from
     * that corner, 55 from that one and 30 from the third"*.
     *
     * **The sign convention, which is the decision this composition turns on.** The two solutions are mirror
     * images in the plane through the three centres, so there is exactly one geometric thing to call *the
     * branch*: **which side of that plane** the point stands on, positive being the side the right-hand normal
     *
     * ```
     * n = (C₂ − C₁) × (C₃ − C₁)
     * ```
     *
     * points to — the side from which the three centres, taken in the order the construction states them, are
     * seen to turn counter-clockwise. [Trilateration.Pair.plus] is that one and [Trilateration.Pair.minus] the
     * other, so a stored `+1` means "above the centres' plane" for as long as the drawing exists.
     *
     * Three properties recommend it, and they are the three OP-1's own 2D rules were chosen for:
     * - it is a property of the **operands alone** — the order of the three spheres is *structural*, decided
     *   when the tool collected its slots and never re-derived, so nothing about the viewport, the click or
     *   the tessellation can move it;
     * - it **turns with the construction**: under any rigid motion the centres and the normal turn together,
     *   so the branch a drawing rides is the branch its rotated copy rides (a *mirrored* copy takes the other
     *   one, which is correct — mirroring is what exchanges the two solutions);
     * - it is **continuous** everywhere except at one genuine degeneracy: the pair collapses onto the plane
     *   itself exactly when the two solutions coincide (a tangency — [Trilateration.Touch], where both signs
     *   answer the same point, exactly as OP-1 rules for two coincident circle crossings), and the plane
     *   itself is ill-defined exactly when the three centres are collinear, which is [Trilateration.Collinear]
     *   and refuses by name.
     *
     * The construction is the textbook one, and the convention above **falls out of it** rather than being
     * imposed on it: with `eₓ` along `C₂ − C₁` and `e_y` along the perpendicular component of `C₃ − C₁`
     * (whose coefficient `j` is positive by construction), `e_z = eₓ × e_y` and
     * `n = (C₂ − C₁) × (C₃ − C₁) = d·j·e_z` with `d > 0` and `j > 0`. So the `+z` solution *is* the `+n` one,
     * and there is no second place where a sign could quietly be decided.
     */
    fun trilaterate(
        a: Sphere3,
        b: Sphere3,
        c: Sphere3,
    ): Trilateration {
        val ab = b.center - a.center
        val d = ab.length()
        if (d < Vec3.EPS) return Trilateration.Collinear
        val ex = ab * (1.0 / d)
        val ac = c.center - a.center
        val i = ex.dot(ac)
        val perp = ac - ex * i
        val j = perp.length()
        if (j < Vec3.EPS) return Trilateration.Collinear
        val ey = perp * (1.0 / j)
        val ez = ex.cross(ey)
        val x = (d * d + a.radius * a.radius - b.radius * b.radius) / (2.0 * d)
        val y = (i * i + j * j + a.radius * a.radius - c.radius * c.radius - 2.0 * i * x) / (2.0 * j)
        val z2 = a.radius * a.radius - x * x - y * y
        val base = a.center + ex * x + ey * y
        if (z2 < 0.0 && sqrt(-z2) > TOUCH_TOL_MM) return Trilateration.None
        val z = if (z2 > 0.0) sqrt(z2) else 0.0
        if (z < TOUCH_TOL_MM) return Trilateration.Touch(base)
        return Trilateration.Pair(base + ez * z, base - ez * z)
    }

    /**
     * Every place the run [path] **enters or leaves** the sphere locus [s] — OP-28's third composition: the
     * points at a stated distance along a run, in order along the run.
     *
     * **Pierce3's own walk, over a different field**, which is the whole of the implementation and the point of
     * it. Where a plane crossing follows the signed distance `n·(P − p₀)` along the run and bisects its sign
     * changes on the analytic piece, this follows `|P − C| − r` and bisects the same way — so every law the
     * in-place sweep's crossings obey is obeyed here without being restated: the order is **arc length along
     * the run** (the only order that is a property of the drawing rather than of the arithmetic), a closed run's
     * **seam** is compared across (a ring that enters the sphere at its own start crosses there, once), and a
     * **touch is not a crossing** — a run that comes tangent to the sphere and turns back changes no side and
     * pierces nothing, exactly as one that grazes a plane does.
     *
     * Where the exactness lies is Pierce3's answer too (OP-15): the samples only *bracket* a root, the root is
     * bisected on the piece's own formula to the last bits of its parameter, and the point is then read off
     * that piece — so the crossing is as exact as the curve's own formula, and only the *finding* of it is
     * sampled.
     */
    fun crossings(
        s: Sphere3,
        path: Path3,
        tolMm: Double = GeomMath.TESS_TOL_MM,
    ): List<Pierce> = Pierce3.crossingsOf(path, tolMm) { el, t -> s.sideAt(Frames3.pointAt(el, t)) }

    /**
     * The **three great circles** a sphere locus is drawn as in a 3D view (OP-28) — its equator and the two
     * meridians in the world's own axes, as ordinary closed [Path3]s of one exact `Arc3` each.
     *
     * **View-independent, deliberately.** The obvious alternative is the *silhouette* — the circle the eye
     * sees the locus's edge as — and it is rejected for the reason DESIGN.md states about silhouette edges in
     * general: it is a property of the camera and would have to be recomputed on every orbit, so what is
     * drawn would not be a fact about the drawing. Three fixed great circles read instantly as a sphere, turn
     * with the model rather than with the viewer, and are the same lines however the scene is being looked at.
     *
     * **One definition, so what is drawn is what is picked**: [SceneRenderer] draws exactly these and
     * [HitTest] measures against exactly these, which is the standing rule for every kind in this engine.
     */
    fun greatCircles(s: Sphere3): List<Path3> =
        listOf(Vec3.X to Vec3.Y, Vec3.Y to Vec3.Z, Vec3.Z to Vec3.X).map { (u, v) ->
            Path3(listOf(Curve3Element.Arc3(s.center, u, v, s.radius, 0.0, 2.0 * PI)), closed = true)
        }

    /**
     * A whole circle of [radius] about [center], in the plane [axis] is normal to — the exact
     * [Curve3Element.Arc3] two of the three compositions above end in.
     *
     * A full turn is an ordinary value of that piece rather than a case of its own, so this is a circle in
     * space in the same vocabulary a lifted drawn circle is (OP-26): one closed path, one piece, exact
     * radius, exact centre.
     */
    private fun circleAbout(
        center: Vec3,
        axis: Vec3,
        radius: Double,
    ): Curve3Element.Arc3 {
        val n = axis.normalized()
        val u = perpendicularTo(n)
        return Curve3Element.Arc3(center, u, n.cross(u), radius, 0.0, 2.0 * PI)
    }

    /**
     * A **deterministic** unit vector perpendicular to [axis].
     *
     * Deterministic is the requirement, not merely a courtesy: the circle two spheres meet in is written into
     * no file, but its numbers are what a byte-equal save→load→save round trip re-derives, and a frame chosen
     * by anything the value does not contain would make the same drawing produce two different circles. So the
     * reference is the world axis **least aligned** with [axis] — a total order on three candidates, never
     * near-parallel, hence never ill-conditioned.
     */
    private fun perpendicularTo(axis: Vec3): Vec3 {
        val ax = abs(axis.x)
        val ay = abs(axis.y)
        val az = abs(axis.z)
        val ref =
            when {
                ax <= ay && ax <= az -> Vec3.X
                ay <= az -> Vec3.Y
                else -> Vec3.Z
            }
        return ref.cross(axis).normalized()
    }
}
