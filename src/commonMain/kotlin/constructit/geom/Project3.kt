package constructit.geom

import kotlin.math.abs

/**
 * A curve **projected onto a face** (OP-26, step 8), together with the two things the drawing has to say
 * about it.
 *
 * [fitted] is OP-15's class, read exactly as [IntersectionCurve] reads it: false when every piece of the
 * drawing was a straight run or a cubic, since both survive the projection *as themselves*; true when a piece
 * was a conic, for which [Curve3Element] has no case and which is therefore a chain of cubics within
 * [Intersect3.FIT_TOL_MM] of the exact curve.
 *
 * Whether the run also landed *within* the face's boundary is deliberately **not** here: it is a report, asked
 * once by the gesture ([Project3.whollyOnFace]) and never by the node, so a recompute on every drag pays
 * nothing for a sentence only the status line reads.
 */
data class ProjectedCurve(
    val path: Path3,
    val fitted: Boolean,
) {
    /** How this curve's exactness is spoken of in a status line — the honesty line, in one phrase. */
    val exactnessWord: String
        get() = if (fitted) "fitted to ${Frames3.mm(Intersect3.FIT_TOL_MM * 1000.0)} µm" else "exact"
}

/**
 * **Projection onto a face** (OP-26, step 8): a curve drawn in one space, thrown onto a face of a solid along
 * the direction that space is drawn from, becoming a curve in space that lies in that face.
 *
 * The engraved line, the trimmed edge, the route that must follow a surface — all of them are *this drawing,
 * over there*, and the reason it belongs in a kernel with no solver is that it is an **affine map** and
 * nothing else.
 *
 * ### What a face is here, and what that lets this step claim
 *
 * A face of a solid in this kernel is a [FacePatch] ([Section3.faces]): a **structural name** (OP-8 — built
 * from the feature's own parameters, never re-identified from mesh topology), a **plane** whose normal points
 * out of the material, and an **outline** in that plane's own (u, v). Every face this kernel names is
 * therefore **planar** — a boundary piece that is curved sweeps a cylinder, and that patch comes back with no
 * plane and a reason instead ([FacePatch.reason]), which is this step's refusal for a curved surface written
 * by the machinery that already knew.
 *
 * So the honest scope of step 8 is stated rather than implied: **projection onto a plane, which is exact**,
 * and *no* projection onto a curved surface, because there is no such operand anywhere in the system. A
 * general surface layer is named in OP-26's own to-be-discussed list as the one thing deliberately outside it.
 *
 * ### The direction is the drawing's own, and that is a decision
 *
 * The curve is projected along the **normal of the space it is drawn in**. Nothing else is stated, and no
 * direction operand exists — three reasons, in order of weight:
 *
 * - it is what *"drop it onto the face"* means. The projection along a space's normal is precisely what that
 *   space's own 2D view shows, so the result's projection back into the drawing **is** the drawing, exactly
 *   (the map is invertible — see [mapOnto]), and the step's defining property needs no tolerance to state;
 * - a direction is already stateable, **by construction**: a space *is* a direction, datum planes take any
 *   hinge and any angle (OP-17), so a route to be thrown obliquely is drawn in the space that throws it. A
 *   second way to say what a space already says is exactly what OP-26 refuses elsewhere (a helix's negative
 *   pitch, a negative turn count);
 * - it needs no new operand, no new slot kind and no 3D manipulator — OP-26's parenting rule paying out
 *   again: everything this takes is already draggable where it lives.
 *
 * A direction lying **in** the face — the plan curve thrown at an upright side face — is a property of
 * *values*, so it is node invalidity with a reason that heals (OP-3), never a gesture refusal: tilt the datum
 * and it comes back.
 *
 * ### Exact where the vocabulary has a name, fitted where it does not
 *
 * An orthographic projection along a fixed direction onto a plane is **affine**, and affine maps carry the two
 * cases [Curve3Element] names, one for one:
 *
 * - a **segment** projects to an exact [Curve3Element.Seg3];
 * - a **cubic** projects to an exact [Curve3Element.Bezier3] — control point for control point, since a
 *   Bézier is affine-invariant. This is the identical argument steps 5 and 6 make, and it costs **no
 *   tolerance at all**: the numbers of the result are the numbers of the drawing, mapped.
 * - an **arc, a circle, an ellipse or an elliptic arc** projects to a **conic**, exactly — an affine image of
 *   an ellipse is an ellipse, recovered in closed form from its conjugate semi-diameters ([Conics.transform],
 *   OP-24), which is why a circle thrown at a face standing at an angle comes back as the ellipse it really is
 *   and not as a circle with a fudged radius. But `Curve3Element` has **no** conic case, so that exact 2D
 *   answer is then **fitted** into cubics at the stated [Intersect3.FIT_TOL_MM] — the same 1e-4 mm steps 5 and
 *   6 state, by the same construction, so OP-26 has one fitting tolerance rather than three.
 *
 * The lift into space is [Intersect3.lifted] verbatim — the very function step 6 lifts a section's drawing
 * with. One lift, one exactness contract, one fitting tolerance: this step adds a 2D map in front of it and
 * nothing else.
 *
 * ### Running off the face: the plane, and the drawing says so
 *
 * A drawn curve may project past the face's boundary. Of the three honest answers this one takes the third:
 * **the run lands in the face's plane, and where it also lands within the face's outline is reported rather
 * than enforced** ([ProjectedCurve.whollyOn]). The two rejected answers are rejected with reasons, not by
 * taste:
 *
 * - **clipping to the outline** is *trimming*, which is OP-26's own to-be-discussed item 4 and is deliberately
 *   undesigned — step 7 refused to trim its two runs back to the join for the identical reason. Worse, it
 *   would produce a solution set **whose cardinality is a value**: a curve crossing a boundary yields one, two
 *   or three pieces depending on where it lies, so a persisted index would name nothing at the places where
 *   there is only one — precisely the drift step 5 refused a doubling-back view over, and precisely what OP-1
 *   exists to prevent;
 * - **refusing when it runs off** would make *validity* depend on a containment test that this kernel can
 *   only answer from a **tessellation** ([RegionBool] works on rings), i.e. on a drawing tolerance. A curve
 *   blinking out of existence because a chord of its outline fell the other side of 0.02 mm is a worse object
 *   than one that hangs over an edge and says so.
 *
 * What it *is* has a precedent already recorded in this kernel and is not a new idea: a `LINE` slot takes a
 * segment and works on its **carrier**, a `CIRCLE` slot takes an arc and works on its whole circle, and the
 * result may land beyond the picked piece — *"which is honest and is said in the help of the tools that can do
 * it"*. A face's carrier is its plane.
 *
 * ### A mesh body has no face to name, and is refused by name
 *
 * An imported body, a general boolean's result, a revolve and a sweep have **emergent** faces, not constructed
 * ones ([Section3.faces] returns nothing, with the sentence it already writes for each). Draping a curve over
 * a triangle soup is refused rather than shipped, for the two reasons OP-9's sink rule gives: the result would
 * be chords whose piece count is a property of the tessellation rather than of the drawing, and *which*
 * triangle a projection landed on is not a name that survives an edit — re-identifying it is the topological
 * naming problem this whole design exists to avoid. What does work is said instead: put a working plane where
 * the body is and take the **intersection curve** (step 6, which does have a mesh route), or build the
 * geometry beside it.
 */
object Project3 {
    /**
     * Below this the projection direction lies **in** the face — the cosine of the angle between the drawing
     * space's normal and the face's.
     *
     * A threshold rather than an exact zero because that is what the condition physically is: the map's
     * stretch across the face is `1 / |cos|`, so at 1e-9 a 100 mm drawing is already thrown a hundred
     * thousand kilometres and every reading past that point is noise. Nothing a drawing can state lands
     * *near* it — an upright face seen from the plan is at exactly zero, to floating-point noise.
     */
    private const val EDGE_ON = 1e-9

    /**
     * The **2D affine map** from [from]'s own (u, v) to [onto]'s, along [from]'s normal — or null when that
     * direction lies in [onto].
     *
     * Affine because each of its three stages is: lifting a sketch point into the world is affine, projecting
     * along a fixed direction onto a plane is affine, and reading the result in an orthonormal frame is
     * affine. It is therefore **determined by the images of three points**, which is how it is built here —
     * the origin and the two unit directions — so there is no matrix algebra to get wrong and the map is
     * exactly the composition it claims to be.
     *
     * It is also always **invertible** when it exists, and that is worth one line because the step's defining
     * property rests on it: a direction in [from] maps to zero only if it is parallel to [from]'s normal, and
     * no direction in a plane is parallel to that plane's normal. So the projection back into the drawing is
     * the inverse map, exact, and *"the result's shadow in the space it was drawn in is the drawing"* is a
     * fact rather than an approximation.
     */
    fun mapOnto(
        from: Plane3,
        onto: Plane3,
    ): Affine? {
        val d = from.normal.normalized()
        val n = onto.normal.normalized()
        val denom = d.dot(n)
        if (abs(denom) <= EDGE_ON) return null

        fun image(p: Vec2): Vec2 {
            val x = from.toWorld(p)
            return onto.toLocal(x + d * ((onto.origin - x).dot(n) / denom))
        }
        val o = image(Vec2(0.0, 0.0))
        val ax = image(Vec2(1.0, 0.0))
        val ay = image(Vec2(0.0, 1.0))
        return Affine(ax.x - o.x, ax.y - o.y, ay.x - o.x, ay.y - o.y, o.x, o.y)
    }

    /**
     * How far the drawn point [p] travels to reach [onto], **signed along the drawing's own normal** — the
     * number *"which face am I looking at"* is decided by ([landingFace]).
     *
     * Positive is towards the viewer, because a sketch space is always seen from its own `+normal`: the plan
     * is looked down on from above, a face space's normal points out of the material at whoever is looking at
     * it (OP-17's intrinsic frame), and a datum's is its frame's. So the **greatest** value here is the face
     * nearest the eye, which is the one a click on that body meant.
     */
    private fun signedReach(
        p: Vec2,
        from: Plane3,
        onto: Plane3,
    ): Double {
        val d = from.normal.normalized()
        val n = onto.normal.normalized()
        val denom = d.dot(n)
        if (abs(denom) <= EDGE_ON) return -Double.MAX_VALUE
        val x = from.toWorld(p)
        return (onto.origin - x).dot(n) / denom
    }

    /**
     * The curve [view], drawn in [from], projected onto the face [face] — or null with the reason there is no
     * curve there.
     *
     * Everything it can refuse is a property of **values**, so each is node invalidity that heals (OP-3): a
     * face that is not a plane (a cylinder swept by a curved edge, a ruled band of a loft — the patch's own
     * sentence, with the plane that *does* work named in it), a direction lying in the face, and a drawing
     * with no length to throw.
     */
    fun projectedOnto(
        view: List<ProfileElement>,
        from: Plane3,
        face: FacePatch,
    ): Pair<ProjectedCurve?, String?> {
        val plane =
            face.plane
                ?: return null to
                    (face.reason ?: "${face.name.label} is not a plane, so there is no flat answer to project onto it")
        val map =
            mapOnto(from, plane)
                ?: return null to
                    "${face.name.label} is edge-on to the space this curve is drawn in — a drawing thrown along " +
                    "that space's own normal lands on that face in a line, not in a curve; tilt the space, draw " +
                    "the curve in one that faces it, or pick a face this one can see"
        val mapped = view.map { mapped(it, map) }
        var fitted = false
        val elements = ArrayList<Curve3Element>()
        for (piece in mapped) {
            val (made, wasFitted) = Intersect3.lifted(piece, plane)
            if (wasFitted) fitted = true
            elements.addAll(made)
        }
        if (elements.isEmpty()) {
            return null to "this drawing has no length, so its projection is a point rather than a curve"
        }
        // Closure is read off the **operand**, which is step 6's own rule for a *derived* curve: whether the
        // drawing comes back to itself is a fact about the drawing, and the map is injective, so the answer
        // cannot differ on the face. (For a *constructed* curve `closed` is a claim the user made — OP-21.)
        val closed = (GeomMath.startOf(mapped.first()) - GeomMath.endOf(mapped.last())).length() <= GeomMath.JOIN_TOL
        return ProjectedCurve(Path3(elements, closed), fitted) to null
    }

    /**
     * Whether the projection of [view] lands **wholly within** [face]'s outline — the report the status line
     * carries, and never a condition on anything (see this object's note on why the run is neither clipped nor
     * refused).
     *
     * Asked by the gesture and by a test, never by the node's own `compute`: the answer costs a tessellation
     * of the face and a walk along the run, and nothing geometric depends on it, so a recompute on every drag
     * must not pay for it.
     */
    fun whollyOnFace(
        view: List<ProfileElement>,
        from: Plane3,
        face: FacePatch,
    ): Boolean {
        val plane = face.plane ?: return false
        val map = mapOnto(from, plane) ?: return false
        return whollyOn(view.map { mapped(it, map) }, face)
    }

    /**
     * One drawn piece under the map, **exact in every case the 2D vocabulary has**.
     *
     * A segment, a cubic, an ellipse and an elliptic arc are [GeomMath.transform]'s already. An **arc** and a
     * **circle** are not, and the reason is worth stating rather than working around: [GeomMath.transformArc]
     * scales the radius by `sqrt|det|`, which is the right answer for a *similarity* and the wrong one for
     * any other affine map — the image of a circle under a projection onto a plane standing at an angle is an
     * **ellipse**. So both are read as the conics they are ([Conics.ofCircle]) and mapped exactly. Nothing is
     * lost by the promotion: [Curve3Element] has no case for either kind, so all four are fitted downstream
     * by the same construction and to the same stated tolerance.
     */
    private fun mapped(
        e: ProfileElement,
        t: Affine,
    ): ProfileElement =
        when (e) {
            is ProfileElement.ArcE ->
                ProfileElement.EllipticArcE(
                    Conics.transform(
                        EllipticArc(
                            Conics.ofCircle(Circle(e.arc.center, e.arc.radius)),
                            e.arc.startAngle,
                            e.arc.endAngle,
                            e.arc.ccw,
                        ),
                        t,
                    ),
                )
            is ProfileElement.CircleE ->
                ProfileElement.EllipseE(Conics.transform(Conics.ofCircle(e.circle), t), if (t.det < 0) !e.ccw else e.ccw)
            else -> GeomMath.transform(e, t)
        }

    // ---- the face outline: what "on the face" is measured against, for the report and for the score ----

    /**
     * The loops of a face's outline, in order.
     *
     * The pieces of each loop are **consecutive** in a [FacePatch]'s outline by construction — a cap is
     * `regions.flatMap { outer + holes }`, a side face is one rectangle, a loft's band is one ring — so a
     * loop ends where it comes back to its own start and the next one begins there. A walk rather than a
     * search, and nothing is joined that the drawing did not already state as one ring.
     */
    fun loopsOf(outline: List<ProfileElement>): List<List<ProfileElement>> {
        val out = ArrayList<List<ProfileElement>>()
        var run = ArrayList<ProfileElement>()
        for (e in outline) {
            run.add(e)
            if ((GeomMath.startOf(run.first()) - GeomMath.endOf(e)).length() <= GeomMath.JOIN_TOL) {
                out.add(run)
                run = ArrayList()
            }
        }
        if (run.isNotEmpty()) out.add(run)
        return out
    }

    /** A face's outline as rings, at the drawing tolerance — what a containment question is answered with. */
    fun ringsOf(outline: List<ProfileElement>): List<List<Vec2>> =
        loopsOf(outline).map { Geom3.tessellateLoop(Loop(it)) }.filter { it.size >= 3 }

    /**
     * How finely the containment **report** reads a projected run, in millimetres, and how many points it
     * will ever spend on one piece.
     *
     * Stated because the report is only as good as its resolution: a run that leaves a notched face and comes
     * back within one step is reported as wholly on it. That is an acceptable limit for something that is a
     * *report* and never a condition (see [Project3]) — nothing geometric turns on it, the run is the same run
     * either way — and it is a millimetre rather than the drawing tolerance because the answer is read by a
     * person and not by a mesh.
     */
    private const val REPORT_STEP_MM = 1.0
    private const val REPORT_MAX_PER_PIECE = 512

    /** The points a containment question is asked at: the drawn polyline, refined to [REPORT_STEP_MM]. */
    private fun samplesOf(piece: ProfileElement): List<Vec2> {
        val tess = GeomMath.tessellatePiece(piece)
        val out = ArrayList<Vec2>(tess.size)
        for (i in tess.indices) {
            out.add(tess[i])
            if (i == tess.size - 1) break
            val step = tess[i + 1] - tess[i]
            val n = minOf(REPORT_MAX_PER_PIECE, (step.length() / REPORT_STEP_MM).toInt())
            for (k in 1 until n) out.add(tess[i] + step * (k.toDouble() / n))
        }
        return out
    }

    /**
     * Whether **every** drawn point of the projected chain [mapped] stands within [face]'s outline — the
     * report [ProjectedCurve.whollyOn] carries, and never a condition on anything.
     *
     * The nonzero winding rule, so a hole in the face is off the face exactly as the outside is; a face whose
     * outline has no rings at all (a degenerate patch) answers *false*, which is the honest reading of *"it
     * did not land on anything"*.
     */
    private fun whollyOn(
        mapped: List<ProfileElement>,
        face: FacePatch,
    ): Boolean {
        val rings = ringsOf(face.outline)
        if (rings.isEmpty()) return false
        return mapped.all { piece -> samplesOf(piece).all { RegionBool.contains(rings, it) } }
    }

    /** Whether **any** drawn point of [mapped] stands within [face]'s outline — what a score asks. */
    private fun landsOn(
        mapped: List<ProfileElement>,
        face: FacePatch,
    ): Boolean {
        val rings = ringsOf(face.outline)
        if (rings.isEmpty()) return false
        return mapped.any { piece -> samplesOf(piece).any { RegionBool.contains(rings, it) } }
    }

    // ---- which face the drawing lands on: scored once, then persisted (OP-1, OP-18) ----

    /**
     * Which face of [feature] the curve [view], drawn in [from], lands on — an index into
     * [Section3.faces]'s **provenance order**, or null with the reason there is none.
     *
     * **Scored once and then persisted**, exactly as step 6's chosen curve and step 7's chosen end are: the
     * caller writes the index into the step's `signs=` and every replay takes it verbatim (OP-18). A reload
     * that scored again would move an engraving to the other side of a plate as soon as an edit slid the
     * drawing past it — the fillets-came-back-inverted defect, two features along.
     *
     * **The rule is one sentence: the face the drawing lands on, and of those the one you are looking at.**
     * Formally, among the faces that are planes and that the direction is not parallel to, those the
     * projection actually falls within are preferred, and among them the one **nearest the eye** — a space is
     * always seen from its own `+normal` ([signedReach]), so this is *the face you can see from where you
     * drew*, which is the standing rule that what is visible is what is pickable, said about a body. The
     * face's own index breaks a tie, so the answer is the same bit on every machine. Two consequences are
     * worth seeing:
     *
     * - for the everyday body — anything extruded upright — the upright side faces are **edge-on** to the plan
     *   and drop out on their own, so a plan curve over a plate scores its **top** face without the rule
     *   having to know what a top is, and whether the plate stands on the plan or floats above it makes no
     *   difference to which face is engraved;
     * - for an inclined face — a pyramid's flank, a loft's flat band — the containment half is what does the
     *   work, since the *plane* of the far flank stands nearer the eye than the near flank's does over the
     *   near flank's own material.
     *
     * The fallback when the drawing lands within **no** face is deliberate rather than a defence: the nearest
     * candidate is still taken, because a run hanging over the edge of a face is a thing this step allows and
     * reports (see [Project3]'s note), so a gesture that refused it would be stricter than the value it makes.
     */
    fun landingFace(
        feature: Feature3,
        view: List<ProfileElement>,
        from: Plane3,
    ): Pair<Int?, String?> {
        val (faces, why) = Section3.faces(feature)
        if (faces == null) return null to (why ?: "this body has no named faces to project onto")
        var best: Int? = null
        var bestKey: Triple<Int, Double, Int>? = null
        for (i in faces.indices) {
            val plane = faces[i].plane ?: continue
            val map = mapOnto(from, plane) ?: continue
            val mapped = view.map { mapped(it, map) }
            val at = GeomMath.startOf(view.first())
            val key = Triple(if (landsOn(mapped, faces[i])) 0 else 1, -signedReach(at, from, plane), i)
            val cur = bestKey
            if (cur == null || compareValuesBy(key, cur, { it.first }, { it.second }, { it.third }) < 0) {
                best = i
                bestKey = key
            }
        }
        if (best == null) {
            return null to
                "every flat face of this body is edge-on to the space this curve is drawn in, so a drawing " +
                "thrown along that space's normal lands on none of them in a curve"
        }
        return best to null
    }
}
