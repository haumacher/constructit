package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.Construction
import constructit.dsl.LoftPart
import constructit.dsl.RegionRef
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.geom.Blend3
import constructit.geom.BlendKind
import constructit.geom.BlendSection
import constructit.geom.BoolFace3
import constructit.geom.EdgeName
import constructit.geom.FaceName
import constructit.geom.Feature3
import constructit.geom.Geom3
import constructit.geom.GeomMath
import constructit.geom.Plane3
import constructit.geom.Region
import constructit.geom.Section3
import constructit.geom.Solid3
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.units.Dimension
import constructit.units.Quantity
import constructit.units.mm
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **The ball pivoting about a slanted or a ring upright — the canal corner** (OP-31, slice 5h; GitHub #36).
 *
 * *The rule, and it is one sentence about two different pairs of walls.* At an inside corner the rolling ball
 * keeps its centre `r` from the face the two roundings share and `r` from the **upright** where their two
 * other faces cross, and the surface it leaves between the two band ends is the **pipe** of the ball along
 * that locus. Where the upright is one straight run square to the shared face those two conditions are a
 * plane and a cylinder about that plane's own normal and meet in a **circle** — session 80's exact pivot,
 * untouched. Where it **leans** they meet in an *ellipse*; where it is a **ring** — a revolve's circular edge
 * at an inside corner of its cap — in the *spiric* a plane cuts a torus in. Neither is tabulated: the
 * tangency on the shared face runs on that face's own polar ray about the corner and the one unknown is how
 * far out it stands, which is bracketed and bisected at every slant and every ring radius.
 *
 * *What slice 5f had as one pair of walls is two here, and that is the whole of what makes this a corner
 * rather than a run.* A canal band's spine and its section are both read off the crease's own two faces. A
 * pivot's **spine** is set by the shared face and the upright; its **section** is closed by the shared face
 * and the **near** one of the pair's two other faces — the one whose own crease still runs on past the band's
 * end. Both of those faces contain the upright, so both cut the station plane in a line through the ball's
 * contact; the near one's real half leaves that contact toward the crease and meets the shared face's trace
 * at the **crease point**, which is the apex a canal section has always closed on. The far one's ray bounds
 * nothing: the region taken with it is the region taken with the near one plus the void between them.
 *
 * *The two ends are the bands' own end sections, exactly.* The upright lies **in** the band's other face and
 * a sphere tangent to a plane touches it at one point only — so at the station where the rolling ball first
 * reaches the upright, its contact with the upright **is** its tangency with that face. Same contact, same
 * gradient, same tangent, same normal plane, same great circle: the pivot's spine joins each band's spine C¹
 * and its end rings are those bands' own end sections rather than a fit.
 *
 * *The figure.* Pappus' own volume element `∫ A(s)·(1 − κ(s)·x̄(s)) ds` along the spine, **less what the two
 * bands give up to it** — each one's rigid section times the crease it hands over, which is exact — so the
 * number a body can be measured against is the pivot's worth against the same two roundings run whole.
 * Bracketed on both sides by the drawing's own terms: the walls' own tessellation skin below, the tool's own
 * step-off above.
 */
class BlendCornerCanalTest {
    private var ids = 0

    private fun ang(deg: Double) = Quantity(deg * PI / 180.0, Dimension.ANGLE)

    private fun polygon(
        cx: Construction,
        pts: List<Vec2>,
    ): RegionRef {
        val ps = pts.map { cx.freePoint("L${ids++}", it.x.mm, it.y.mm) }
        return cx.region(cx.loop(*ps.indices.map { cx.segment(ps[it], ps[(it + 1) % ps.size]) }.toTypedArray()))
    }

    /** A loft between two L-shaped sections whose side faces lean by [slant] — the slanted upright. */
    private fun lofted(
        cx: Construction,
        slant: Double = 35.0,
    ): SolidRef {
        val lo = listOf(Vec2(0.0, 0.0), Vec2(60.0, 0.0), Vec2(60.0, 30.0), Vec2(30.0, 30.0), Vec2(30.0, 60.0), Vec2(0.0, 60.0))
        val shift = HEIGHT * tan(slant * PI / 180.0) / kotlin.math.sqrt(2.0)
        val scale = 1.0 - shift / 30.0
        return cx.loft(
            listOf(
                LoftPart.Area(cx.sketchOn(cx.planeXY(), polygon(cx, lo))),
                LoftPart.Area(cx.sketchOn(cx.planeOffset(cx.planeXY(), cx.const(HEIGHT.mm)), polygon(cx, lo.map { it * scale }))),
            ),
        )
    }

    /** An L-shaped meridian turned about the `x` axis — its cap's reflex corner stands on a **ring**. */
    private fun turned(
        cx: Construction,
        ring: Double = 20.0,
        sweep: Double = 90.0,
    ): SolidRef {
        val inner = ring / 2.0
        val prof =
            listOf(Vec2(0.0, inner), Vec2(0.0, ring + 10.0), Vec2(10.0, ring + 10.0), Vec2(10.0, ring), Vec2(20.0, ring), Vec2(20.0, inner))
        val o = cx.freePoint("Ro${ids++}", 0.mm, 0.mm)
        val axis = cx.direction(o, cx.freePoint("Rx${ids++}", 1.mm, 0.mm))
        return cx.revolve(cx.sketchOn(cx.planeXY(), polygon(cx, prof)), o, axis, cx.const(ang(sweep)))
    }

    // ---- running roundings ----

    private fun round(
        cx: Construction,
        on: SolidRef,
        address: List<Int>,
        size: Double,
        kind: BlendKind = BlendKind.FILLET,
    ): Pair<SolidRef?, String?> {
        val body = Evaluator().solid(on)
        val (choices, why) = Blend3.choicesFor(body, address, BlendSection(kind, size))
        if (choices == null) return null to (why?.render() ?: "no choice")
        val ref = cx.blendAll(on, cx.planeXY(), listOf(Construction.BlendRun(kind, cx.const(size.mm), null, address, choices)))
        val r = Evaluator().eval(ref.node)
        if (r is EvalResult.Invalid) return null to r.why.render()
        return ref to null
    }

    /** The same dressing made **one gesture at a time** — the other route to the same body. */
    private fun rounded(
        cx: Construction,
        on: SolidRef,
        address: List<Int>,
        size: Double,
    ): Pair<SolidRef?, String?> {
        var body = on
        for (a in address) {
            val (next, why) = round(cx, body, listOf(a), size)
            body = next ?: return null to why
        }
        return body to null
    }

    private fun facesOf(s: Solid3) = assertNotNull(Section3.faces(s.feature).first, "it names its faces")

    private fun edgesOf(s: Solid3) = assertNotNull(Section3.edges(s.feature).first, "it names its edges")

    /**
     * The two edges of the face named by [face] that meet at [at] — the pair the pivot stands between,
     * **found on the body** rather than written down as indices, so the same reading serves every slant and
     * every ring radius of the sweep.
     */
    private fun pairAt(
        solid: Solid3,
        face: (FaceName) -> Boolean,
        at: Vec3,
    ): List<Int> {
        val es = edgesOf(solid)
        return es.indices.filter { i ->
            val e = es[i]
            if (!face(e.between.a) && !face(e.between.b)) return@filter false
            val path = Blend3.edgePath(e).first ?: return@filter false
            val s = path.start ?: return@filter false
            val t = path.end ?: return@filter false
            (s - at).length() < 1e-6 || (t - at).length() < 1e-6
        }
    }

    /** The reflex vertex of the loft's own cap at [slant], and the two cap edges that meet there. */
    private fun loftPair(
        solid: Solid3,
        slant: Double,
    ): Pair<List<Int>, Vec3> {
        val shift = HEIGHT * tan(slant * PI / 180.0) / kotlin.math.sqrt(2.0)
        val scale = 1.0 - shift / 30.0
        val v = Vec3(30.0 * scale, 30.0 * scale, HEIGHT)
        return pairAt(solid, { n -> n.label.render().contains("section 2's own face") }, v) to v
    }

    /** The reflex vertex of the revolve's own start cap at [ring], and the two cap edges that meet there. */
    private fun turnPair(
        solid: Solid3,
        ring: Double,
    ): Pair<List<Int>, Vec3> {
        val v = Vec3(10.0, ring, 0.0)
        return pairAt(solid, { n -> n.label.render().contains("cap at the start") }, v) to v
    }

    /**
     * The same reflex vertex on **either** cap of a partial revolve of [sweep]° — the end cap's is the
     * start's turned by the sweep, and its two edges run the other way round the profile.
     *
     * It is asked of both because a pivot may not be a function of *which cap it stands on*: the two caps
     * are each other's mirror, and a construction that reads a sign off the gesture's own frame rather
     * than off the material builds one of them and folds the other (GitHub #36, the slice's own probe).
     */
    private fun capPair(
        solid: Solid3,
        ring: Double,
        sweep: Double,
        atStart: Boolean,
    ): Pair<List<Int>, Vec3> {
        val a = sweep * PI / 180.0
        val v = if (atStart) Vec3(10.0, ring, 0.0) else Vec3(10.0, ring * kotlin.math.cos(a), ring * kotlin.math.sin(a))
        val which = if (atStart) "cap at the start" else "cap at the end"
        return pairAt(solid, { n -> n.label.render().contains(which) }, v) to v
    }

    // ---- (a) the two uprights build, and the corner is the canal it says it is ----

    /**
     * **A loft's inside corner builds, and the surface between the two bands is the ball's own canal.**
     *
     * The upright between two of a loft's side faces leans, so the ball's centre runs on an **ellipse**
     * rather than a circle and the section it carries changes from one band end to the other. The corner is
     * a `BlendCorner` face of the body with a reason that says exactly that — neither a plane nor a surface
     * of revolution — and the two routes that make it agree to one part in a thousand million.
     */
    @Test
    fun aLoftsSlantedUprightCarriesTheBallsOwnPivot() {
        val cx = Construction()
        val base = lofted(cx)
        val v0 = Geom3.volume(Evaluator().solid(base).mesh)
        val (pair, _) = loftPair(Evaluator().solid(base), 35.0)
        assertEquals(2, pair.size, "the loft's cap turns a reflex corner between two of its own edges")
        val (ref, why) = round(cx, base, pair, 3.0)
        val body = Evaluator().solid(assertNotNull(ref, "the slanted upright carries the pivot: $why"))
        assertManifold(body.mesh, "a loft's inside corner at 3 mm")
        val corner = facesOf(body).filter { it.name is FaceName.BlendCorner }
        assertEquals(1, corner.size, "one corner face: ${facesOf(body).map { it.name.label.render() }}")
        val reason = assertNotNull(corner[0].reason, "…and it says what it is").render()
        assertTrue(reason.contains("canal"), "…the canal the pivoting ball leaves: $reason")
        assertTrue(reason.contains("neither a plane nor a surface of revolution"), reason)
        // …and its two rails are on the list beside the bands' own
        val rails = edgesOf(body).filter { it.name is EdgeName.BlendCornerRail }
        assertEquals(2, rails.size, "the tangency on the shared face, and the range of the upright it rolls along")
        assertInsideItsOwnBracket(body, v0, "the loft's slanted upright")
        assertBothRoutesAgree(::lofted, pair, 3.0, "the loft's slanted upright")
        // **nothing recorded moved.** A pivot is an ordinary corner of an ordinary dressing: its face is the
        // `BlendCorner` slot the catalogue already had and its two curves the `BlendCornerRail` slots a walk
        // already puts down, so the dressed lists grow by exactly what slice 5g's own block rule says and no
        // stored address re-packs. No slot name is added, none renumbers, and no format version rises.
        assertEquals(2, edgesOf(body).count { it.name is EdgeName.BlendCornerRail }, "the pivot's own two curves, and only those")
        assertEquals(1, facesOf(body).count { it.name is FaceName.BlendCorner }, "the pivot's own face, and only that")
    }

    /**
     * **A revolve's cap corner builds, and the ring is an upright like any other.**
     *
     * The upright is the circle the profile's own reflex corner traces, so the ball's centre stands `r` from
     * a *circle* — a plane against a torus, the spiric quartic — and the very same solve follows it, because
     * nothing in the construction knows whether the upright's carrier is a line or a ring.
     */
    @Test
    fun aRevolvesRingUprightCarriesTheSamePivot() {
        val cx = Construction()
        val base = turned(cx)
        val v0 = Geom3.volume(Evaluator().solid(base).mesh)
        val (pair, _) = turnPair(Evaluator().solid(base), 20.0)
        assertEquals(2, pair.size, "the cap turns a reflex corner between two of its own edges")
        val (ref, why) = round(cx, base, pair, 2.0)
        val body = Evaluator().solid(assertNotNull(ref, "the ring upright carries the pivot: $why"))
        assertManifold(body.mesh, "a revolve's cap corner at 2 mm")
        val corner = facesOf(body).filter { it.name is FaceName.BlendCorner }
        assertEquals(1, corner.size, "one corner face")
        assertTrue(assertNotNull(corner[0].reason, "it says what it is").render().contains("canal"), "the ball's own canal")
        assertBothRoutesAgree({ c -> turned(c) }, pair, 2.0, "the revolve's ring upright")
    }

    /**
     * **A pivot's tool states the runs its section is assembled from, and names the one it cannot**
     * (OP-31, slice 5u).
     *
     * The slice in one assertion, and it is three claims about the same list. (1) *The map is the section's
     * own*: the tool's face list is the corner section's **runs** — the leg in the shared face, the two jogs
     * either side of the fourth side, the fourth side, the near leg, the jog onto the ball's contact, the
     * three chords of the arc's fade, the arc, the jog home and the two caps — so a turn carries a dozen
     * faces where the polygon has `2·legSteps + arcSteps + 1` chords, which on this pivot is fifty-nine. The
     * sixty-carrier reading is exactly what slice 5w built, measured at ten minutes for the suite, and
     * refused. (2) *A whole leg is one surface, exactly*: the leg lying in the shared face is that face's own
     * plane **translated**, same normal, a step off it, and every one of its points stands on it — asked
     * here of the plane rather than of the points. (3) *And what is not stated says so*: the list is not the
     * tool's whole boundary ([BoolProvenance.whole]), which is what lets the tool state the rest of itself
     * instead of throwing all of it away at the first unnamed chord — and every triangle whose slot does
     * name a surface really stands on it.
     */
    @Test
    fun aPivotsToolStatesItsRunsAndNamesTheOneItCannot() {
        val cx = Construction()
        val base = turned(cx)
        val (pair, _) = turnPair(Evaluator().solid(base), 20.0)
        val shared = facesOf(Evaluator().solid(base)).mapNotNull { it.plane }
        var tool: Feature3.MeshBoolean? = null
        var tris = 0
        var mesh: constructit.geom.Mesh3? = null
        Geom3.combined = { _, _, b ->
            val f = b.feature
            if (f is Feature3.MeshBoolean && f.provenance?.faces?.any { it.name is FaceName.BlendCorner } == true) {
                tool = f
                tris = b.mesh.triangles.size
                mesh = b.mesh
            }
        }
        val (ref, why) =
            try {
                round(cx, base, pair, 2.0)
            } finally {
                Geom3.combined = null
            }
        assertNotNull(ref, "the ring pivot builds: $why")
        val t = assertNotNull(tool, "the pivot hands its tool to the kernel and the tool names faces")
        val prov = assertNotNull(t.provenance, "…and states a provenance of its own")
        val faces = prov.faces
        println("== pivot tool: $tris triangles, ${faces.size} faces, whole=${prov.whole}")
        for (p in faces) println(
            "   ${p.name} :: ${if (p.plane != null) {
                "plane"
            } else if (p.surface != null) {
                "surface"
            } else if (p.pipe != null) {
                "pipe"
            } else if (p.strip != null) {
                "strip"
            } else {
                "no carrier"
            }}",
        )
        assertTrue(faces.size in 8..16, "the run map's faces and not one per chord: ${faces.size}")
        assertTrue(!prov.whole, "the list is not the tool's whole boundary, and says so")
        assertTrue(faces.count { it.strip != null } >= 4, "the jogs are the ruled strips they are: ${faces.count { it.strip != null }}")
        assertTrue(faces.any { it.plane == null && it.surface == null && it.pipe == null && it.strip == null }, "the run it cannot name says so")
        // (2) the leg in the shared face **is** that face's plane, translated
        val legs =
            faces.filter { p -> p.plane != null && p.name is FaceName.BlendCorner }
                .mapNotNull { p ->
                    val pl = p.plane!!
                    shared.firstOrNull { f ->
                        abs(abs(f.normal.normalized().dot(pl.normal.normalized())) - 1.0) <= 1e-12 &&
                            abs((pl.origin - f.origin).dot(f.normal.normalized())) in 1e-9..2.0
                    }?.let { f -> (pl.origin - f.origin).dot(f.normal.normalized()) }
                }
        assertTrue(legs.isNotEmpty(), "the shared face's leg is that face translated: ${faces.mapNotNull { it.plane?.normal }}")
        println("== the shared face's leg stands ${legs.joinToString()} mm off the body's own face")
        // (3) every triangle whose slot names a surface really stands on it
        val m = assertNotNull(mesh, "the tool's mesh")
        var scale = 1.0
        for (v in m.vertices) scale = max(scale, max(abs(v.x), max(abs(v.y), abs(v.z))))
        val tol = max(1e-6, 64.0 * 1.1920929e-7 * scale)
        var stray = 0
        var carried = 0
        for (tri in m.triangles) {
            val vs = listOf(m.vertices[tri.a], m.vertices[tri.b], m.vertices[tri.c])
            val on =
                faces.filter { it.plane != null || it.surface != null || it.pipe != null || it.strip != null }
                    .any { p -> vs.all { abs(BoolFace3.offSurface(p, it)) <= tol + p.slack } }
            if (on) carried++ else stray++
        }
        println("== $carried of ${m.triangles.size} triangles stand on a surface the tool names, $stray on the runs it cannot name")
        // measured: 8,420 of 10,186, which is the arc's fade, the two legs' jogs, the fourth side, the
        // two caps and the whole of the leg in the shared face; what is left is the near leg and the arc
        assertTrue(carried > 2 * m.triangles.size / 3, "most of the tool stands on a surface it names: $carried of ${m.triangles.size}")
    }

    // ---- (b) the section, and what the corner still owes the loft ----

    /**
     * **Level sections through a ring pivot close, and the face the two roundings share still opens.**
     *
     * The pivot's own cut is **sampled** on its `(station, arc)` chart for the same reason slice 5f's band
     * is — the characteristic circles lie in planes that turn along the pivot, so a plane crosses the patch
     * *across* the turn — and its two ends are solved on the end stations' own great circles rather than
     * interpolated, which is what lets it hand over to each band's exact cut and the loop close.
     */
    @Test
    fun sectionsThroughARingPivotCloseAndTheSharedFaceStillOpens() {
        val cx = Construction()
        val base = turned(cx)
        val (pair, _) = turnPair(Evaluator().solid(base), 20.0)
        val body = Evaluator().solid(assertNotNull(round(cx, base, pair, 2.0).first, "the ring pivot builds"))
        for (z in listOf(19.0, 19.5, 20.5)) {
            val (regions, why) = Section3.regionsOf(body.feature, Plane3(Vec3(0.0, 0.0, z), Vec3.X, Vec3.Y))
            assertNotNull(regions, "the section at z = $z closes: ${why?.render()}")
        }
        val cap = facesOf(body).first { it.name.label.render().contains("cap at the start") }
        assertEquals(null, cap.reason?.render(), "the face the two roundings share is still one a sketch can be put on")
        assertTrue(cap.outline.isNotEmpty(), "…and it still states its own outline")
    }

    /**
     * **A lofted band's own free end closes its section** (OP-31, slice 5p — what slice 5h pinned open).
     *
     * *The gap this retires.* A band along the loft's own cap edge ends on a flat cap standing in the plane
     * square to its crease, and the neighbouring **side face leans**, so that cap is a face of the body like
     * any other — session 81 gave a straight crease no flat-end slot at all, on the sentence *"the cap is
     * square to the edge and so is the face"*, which holds at a right angle and nowhere else. Two things were
     * missing at once and the loop needed both: the **cap** itself, and the **triangle of the side face**
     * that survives past that cap, which the band's own strip had trimmed away over the face's whole piece.
     *
     * *The figure, and it is the body's own.* At height `h` below the cap the level plane crosses the band
     * in one straight ruling, so what the rounding takes off the plain loft's section there is a **rectangle**
     * — the crease's own length by the depth the band bites into the wall. That depth is read off the crease's
     * dihedral, which is `90° + β` where `tan β` is the wall's own lean (the plan shrinks by `shift` over the
     * height): the fillet's setback along the wall is `t = r / tan(α/2)`, the band's trace stands
     * `√(2rh − h²)` in from the tangency on the cap, and the wall's own trace has moved out by `h·tan β`. So
     * the strip is `h·tan β + t − √(2rh − h²)`, and it is **nothing at all** below `t·cos β`, where the band's
     * tangency on the wall is and the wall is untouched again. Nothing here is measured off a mesh and
     * nothing is fitted, which is why the tolerance below is a double's own and not a chord's.
     */
    @Test
    fun aLoftedBandsOwnFreeEndClosesItsSection() {
        val cx = Construction()
        val base = lofted(cx)
        val (pair, _) = loftPair(Evaluator().solid(base), 35.0)
        val one = Evaluator().solid(assertNotNull(round(cx, base, listOf(pair[0]), 3.0).first, "one band alone builds"))
        assertManifold(one.mesh, "one lofted band")
        val cx2 = Construction()
        val both = Evaluator().solid(assertNotNull(round(cx2, lofted(cx2), pair, 3.0).first, "both build"))
        assertManifold(both.mesh, "the lofted pivot")
        // **the two heights, and what each of them says.** 19.5 and 19.0 cross the band, so the region is the
        // plain loft's less the strip; 18.0 is below the band's own tangency on the wall, so it is the plain
        // loft's exactly — the band has run out and takes nothing there.
        for (z in listOf(19.5, 19.0, 18.0)) {
            val plane = Plane3(Vec3(0.0, 0.0, z), Vec3.X, Vec3.Y)
            val (regions, why) = Section3.regionsOf(one.feature, plane)
            val r = assertNotNull(regions, "one band alone: the section at z = $z closes: ${why?.render()}")
            assertEquals(1, r.size, "…in one region")
            assertClose(areaOf(r), loftFigure(z, 3.0), 1e-9, "…of the loft's own area less the band's own strip, at z = $z")
            assertEquals(if (z > 18.331) 10 else 6, r[0].outer.elements.size, "…and the loop is the L's six pieces, four more where the band cuts")
            assertEquals(emptyList(), r[0].holes, "…with no holes")
            val (rBoth, whyBoth) = Section3.regionsOf(both.feature, plane)
            val b = assertNotNull(rBoth, "the pivot beside it: the section at z = $z closes: ${whyBoth?.render()}")
            assertTrue(areaOf(b) <= areaOf(r) + 1e-9, "…and the second band takes at least as much again at z = $z")
        }
        // **and a plane along the run closes too**, which is the reading the level one cannot make: the plane
        // `y = 19.9` contains the crease's own direction and cuts the band along the whole of it.
        for (y in listOf(19.0, 19.9)) {
            val (regions, why) = Section3.regionsOf(one.feature, Plane3(Vec3(0.0, y, 0.0), Vec3.X, Vec3.Z))
            assertNotNull(regions, "a plane along the crease at y = $y closes: ${why?.render()}")
        }
    }

    /**
     * **Both gesture routes give the same closed section** (OP-30's invariant, read for slice 5p): one
     * dressing made in one gesture and the same two roundings made one at a time are one body, so the
     * section through the band region has to be the same region — and it is, to the last bit, because
     * every piece of it is exact.
     */
    @Test
    fun bothGestureRoutesCloseTheSameLoftedSection() {
        val cx = Construction()
        val base = lofted(cx)
        val (pair, _) = loftPair(Evaluator().solid(base), 35.0)
        val together = Evaluator().solid(assertNotNull(round(cx, base, pair, 3.0).first, "one gesture builds"))
        val cx2 = Construction()
        val apart = Evaluator().solid(assertNotNull(rounded(cx2, lofted(cx2), pair, 3.0).first, "one at a time builds"))
        assertManifold(together.mesh, "the loft rounded in one gesture")
        assertManifold(apart.mesh, "the loft rounded one at a time")
        for (z in listOf(19.5, 19.0)) {
            val plane = Plane3(Vec3(0.0, 0.0, z), Vec3.X, Vec3.Y)
            val (a, whyA) = Section3.regionsOf(together.feature, plane)
            val (b, whyB) = Section3.regionsOf(apart.feature, plane)
            assertNotNull(a, "the one-gesture body's section at z = $z closes: ${whyA?.render()}")
            assertNotNull(b, "the one-at-a-time body's section at z = $z closes: ${whyB?.render()}")
            assertClose(areaOf(b!!), areaOf(a!!), 1e-9, "…and the two routes enclose one area at z = $z")
        }
    }

    /** What a list of regions encloses — outer boundaries less their holes, in the section plane's own (u, v). */
    private fun areaOf(regions: List<Region>): Double =
        regions.sumOf { r -> abs(GeomMath.signedArea(r.outer)) - r.holes.sumOf { abs(GeomMath.signedArea(it)) } }

    /**
     * **What the loft's own level section at [z] encloses once a band of radius [r] has run along its cap
     * edge** — the figure the test above is measured against, and every term of it is the body's own.
     *
     * The loft scales its L-section linearly with height, so the plain section is `2700·s(z)²`. The band
     * cuts a rectangle out of it: the crease's own length by the depth the rounding bites into the wall at
     * that height, which is the 2D fillet of radius [r] in the crease's dihedral `α = 90° + β` — `β` being
     * the wall's own lean. Below `t·cos β` the band's tangency on the wall is passed and it takes nothing.
     */
    private fun loftFigure(
        z: Double,
        r: Double,
        slant: Double = 35.0,
    ): Double {
        val shift = HEIGHT * tan(slant * PI / 180.0) / kotlin.math.sqrt(2.0)
        val scale = 1.0 - shift / 30.0
        val s = 1.0 - z * shift / (HEIGHT * 30.0)
        val tanB = shift / HEIGHT
        val cosB = HEIGHT / kotlin.math.sqrt(shift * shift + HEIGHT * HEIGHT)
        val t = r / tan((PI / 2 + kotlin.math.atan(tanB)) / 2)
        val h = HEIGHT - z
        val strip = if (h <= 0.0 || h >= t * cosB) 0.0 else h * tanB + t - kotlin.math.sqrt(2 * r * h - h * h)
        return 2700.0 * s * s - 30.0 * scale * strip
    }

    // ---- (c) the sweep: whether a ball pivots is a property of the sizes and the angles ----

    /**
     * **The sweep** — `r ∈ {0.5, 1, 1.5, 2, 2.5}` against a slant of `{10°, 20°, 35°}`, and the ring at
     * `{6, 8, 10, 12, 20}` on a 270° partial revolve, in both gesture routes.
     *
     * The ring family runs from **6** to 20 since slice 5o, and it is the family the drawing's own answer is
     * read on: a tight ring is where the pivot's tool and the body meet along a surface rather than across
     * one, and what the sweep asserts of every cell of it is the rule and not a tally — built inside its own
     * bracket, or refused in a sentence of this drawing's own.
     *
     * Every cell builds inside the figure the construction states for it or is refused **by name**, and the
     * residue is nothing at all. Whether a ball pivots at a corner is a property of the sizes and the angles
     * and of nothing else: the two routes agree at every cell, and a refusal is the same refusal in both.
     */
    @Test
    fun everySizeAndEverySlantBuildsInsideItsBracketOrRefusesByName() {
        var cells = 0
        var built = 0
        var refused = 0
        val reasons = LinkedHashSet<String>()
        for (slant in listOf(10.0, 20.0, 35.0)) {
            for (r in listOf(0.5, 1.0, 1.5, 2.0, 2.5)) {
                cells +=
                    runCell("loft slant=$slant r=$r", { c -> lofted(c, slant) }, { s -> loftPair(s, slant).first }, r, reasons)
                        .also { if (it.second) built += 2 else refused += 2 }.first
            }
        }
        for (ring in listOf(6.0, 8.0, 10.0, 12.0, 20.0)) {
            for (r in listOf(0.5, 1.0, 1.5, 2.0, 2.5)) {
                cells +=
                    runCell("ring R=$ring r=$r", { c -> turned(c, ring, 270.0) }, { s -> turnPair(s, ring).first }, r, reasons)
                        .also { if (it.second) built += 2 else refused += 2 }.first
            }
        }
        // **both caps, at four sweeps** — the cell a pivot may never be a function of (GitHub #36). One of
        // the four is **200°**, and it is there because the other three are not enough: a right angle, a
        // half turn and a three-quarter turn all put the end cap's own plane on exact coordinates, so a
        // reading that is exact in an axis-aligned pose and noise in a generic one passes all three. The
        // end cap of a 200° turn folded while the same body's two caps in one gesture built (slice 5h's
        // second probe), and that is the cell this angle is here for.
        for (sweep in listOf(90.0, 180.0, 200.0, 270.0)) {
            for (atStart in listOf(true, false)) {
                for (r in listOf(0.5, 1.0, 1.5, 2.0, 2.5)) {
                    val what = "ring sweep=$sweep ${if (atStart) "start" else "end"} cap r=$r"
                    cells +=
                        runCell(what, { c -> turned(c, 20.0, sweep) }, { s -> capPair(s, 20.0, sweep, atStart).first }, r, reasons)
                            .also { if (it.second) built += 2 else refused += 2 }.first
                }
            }
        }
        println("== canal corners: $cells cells — $built built inside their own bracket, $refused refused by name")
        for (why in reasons) println("   refused: $why")
        assertEquals(cells, built + refused, "every cell is built inside its bracket or refused by name")
        assertTrue(built > 0, "the sweep builds something")
    }

    /** One cell of the sweep in both routes: how many readings it is, and whether they built. */
    private fun runCell(
        what: String,
        make: (Construction) -> SolidRef,
        pairOf: (Solid3) -> List<Int>,
        r: Double,
        reasons: MutableSet<String>,
    ): Pair<Int, Boolean> {
        val cx = Construction()
        val base = make(cx)
        val v0 = Geom3.volume(Evaluator().solid(base).mesh)
        val pair = pairOf(Evaluator().solid(base))
        assertEquals(2, pair.size, "$what: the cap turns a reflex corner")
        val (ref, why) = round(cx, base, pair, r)
        val cx2 = Construction()
        val (ref2, why2) = rounded(cx2, make(cx2), pair, r)
        if (ref == null || ref2 == null) {
            // **each route is its own cell.** One refusing where the other builds is two cells and not a
            // failure of the rule: it is the one asymmetry this slice records (see the as-built note), and
            // it is counted and named here rather than hidden. What may never happen is a refusal that is
            // not the drawing's own sentence.
            for (reason in listOfNotNull(why, why2)) {
                assertTrue(namesSomething(reason), "$what is refused by name: $reason")
                reasons.add("$what — $reason")
            }
            if (ref == null && ref2 == null) return 2 to false
            val standing = Evaluator().solid(ref ?: ref2!!)
            assertManifold(standing.mesh, "$what, the route that builds")
            assertInsideItsOwnBracket(standing, v0, what)
            return 2 to false
        }
        val body = Evaluator().solid(ref)
        val other = Evaluator().solid(ref2)
        assertManifold(body.mesh, "$what, one gesture")
        assertManifold(other.mesh, "$what, one gesture at a time")
        val v = Geom3.volume(body.mesh)
        // …to the engine's own ULP, which is the class the general boolean is deterministic in (session 84)
        assertClose(Geom3.volume(other.mesh), v, 1e-7 * v, "$what: the two routes are the same body")
        assertInsideItsOwnBracket(body, v0, what)
        return 2 to true
    }

    private fun namesSomething(reason: String): Boolean =
        reason.contains("#") || reason.contains("face") || reason.contains("edge") || reason.contains("cap")

    // ---- the figure ----

    /**
     * What the whole dressing takes, inside the figure the construction states for it: each band's own rigid
     * section times the run it keeps, plus the pivot's own quadrature less what the two bands hand over.
     *
     * **Asked of every cell of this class since slice 5o**, the ring family included. Slice 5h held the ring
     * cells to their build alone, on the reading that *"a band whose other face is a cylinder has a wedge with
     * a circular leg, and this algebra states no prism figure for one"*. That is true of the **matrix's** own
     * `predict`, which reads the L-block's algebra; it is **not** true of `Blend3.cornerRemoval`, which reads
     * each band's own section polygon and its own length and does not care what the legs are. A band along a
     * revolve's cap edge carries a rigid section — the dihedral is the same at every station of that straight
     * crease — so the band is a prism and its area is the polygon's. Every one of the 160 readings below is
     * therefore held to the bracket the construction states for it, which is what the residue's zero means.
     */
    private fun assertInsideItsOwnBracket(
        body: Solid3,
        v0: Double,
        what: String,
    ) {
        val bracket = assertNotNull(Blend3.cornerRemoval(body.feature as Feature3.Blend), "$what states its own figure")
        val took = v0 - Geom3.volume(body.mesh)
        assertTrue(took in Bracket(bracket.first, bracket.second), "$what: it takes $took, inside [${bracket.first}, ${bracket.second}]")
    }

    private fun assertBothRoutesAgree(
        make: (Construction) -> SolidRef,
        pair: List<Int>,
        r: Double,
        what: String,
    ) {
        val c1 = Construction()
        val one = Evaluator().solid(assertNotNull(round(c1, make(c1), pair, r).first, "$what: one gesture"))
        val c2 = Construction()
        val seq = rounded(c2, make(c2), pair, r)
        val two = Evaluator().solid(assertNotNull(seq.first, "$what: two gestures — ${seq.second}"))
        assertManifold(two.mesh, "$what, sequentially")
        val v = Geom3.volume(one.mesh)
        assertClose(Geom3.volume(two.mesh), v, 1e-7 * v, "$what: both routes give the same body")
        val c3 = Construction()
        val back = Evaluator().solid(assertNotNull(rounded(c3, make(c3), pair.reversed(), r).first, "$what: the other order"))
        assertClose(Geom3.volume(back.mesh), v, 1e-7 * v, "$what: and so does the other gesture order")
    }

    // ---- (d) the matrix class: every edge and every adjacent pair of the two uprights' own caps ----

    /**
     * **Every edge of the two caps, and every pair of them that shares a vertex** — in both kinds, at two
     * sizes, in both gesture routes. Each cell builds and is manifold, or is refused **by name**; the
     * residue is nothing at all.
     *
     * It lives beside the pivot rather than inside `BlendMatrixTest`, and that is a stated choice: the matrix
     * reads the L-block's own algebra — `predict`'s `w·L` and its containment brackets — which states no
     * figure for a band whose wedge has a **circular leg**, so half of this class would be out of it by the
     * very rule slice 5e wrote down for the sector's rim uprights. What it keeps of the matrix's rule is the
     * rule itself: built, manifold, both routes agreeing, or refused by name.
     */
    @Test
    fun everyEdgeAndEveryAdjacentPairOfTheTwoCapsBuildsOrRefusesByName() {
        var cells = 0
        var built = 0
        var refused = 0
        val reasons = LinkedHashSet<String>()
        for (
        (what, make, face) in
        listOf(
            Triple<String, (Construction) -> SolidRef, (FaceName) -> Boolean>("loft cap", { c -> lofted(c) }, { n ->
                n.label.render().contains("section 2's own face")
            }),
            Triple<String, (Construction) -> SolidRef, (FaceName) -> Boolean>("revolve cap", { c -> turned(c) }, { n ->
                n.label.render().contains("cap at the start")
            }),
        )
        ) {
            val probe = Evaluator().solid(make(Construction()))
            val es = edgesOf(probe)
            val mine = es.indices.filter { face(es[it].between.a) || face(es[it].between.b) }
            val addresses = ArrayList<List<Int>>()
            for (i in mine) addresses.add(listOf(i))
            for (i in mine) {
                for (j in mine) {
                    if (i >= j) continue
                    if (sharesAVertex(probe, i, j)) addresses.add(listOf(i, j))
                }
            }
            for (address in addresses) {
                for (kind in listOf(BlendKind.FILLET, BlendKind.CHAMFER)) {
                    for (size in listOf(1.0, 2.0)) {
                        cells++
                        val cx = Construction()
                        val (ref, why) = round(cx, make(cx), address, size, kind)
                        if (ref == null) {
                            val reason = assertNotNull(why, "$what $address $kind $size is refused by name")
                            assertTrue(namesSomething(reason), "$what $address $kind $size is refused by name: $reason")
                            reasons.add(reason)
                            refused++
                            continue
                        }
                        val solid = Evaluator().solid(ref)
                        assertManifold(solid.mesh, "$what $address $kind $size")
                        built++
                    }
                }
            }
        }
        println("== the two caps: $cells cells — $built built and manifold, $refused refused by name")
        assertEquals(cells, built + refused, "every cell builds or is refused by name")
        assertTrue(built > 0 && cells >= 40, "the class is the caps' own edges and pairs: $cells")
    }

    private fun sharesAVertex(
        solid: Solid3,
        i: Int,
        j: Int,
    ): Boolean {
        val a = Blend3.edgePath(edgesOf(solid)[i]).first ?: return false
        val b = Blend3.edgePath(edgesOf(solid)[j]).first ?: return false
        val ends = listOfNotNull(a.start, a.end)
        val others = listOfNotNull(b.start, b.end)
        return ends.any { p -> others.any { q -> (p - q).length() < 1e-6 } }
    }

    private companion object {
        const val HEIGHT = 20.0
    }
}
