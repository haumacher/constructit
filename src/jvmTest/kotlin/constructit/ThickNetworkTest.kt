package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.RegionRef
import constructit.dsl.SolidRef
import constructit.dsl.region
import constructit.dsl.scalar
import constructit.dsl.valueOf
import constructit.editor.Document
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.ThickCarrier
import constructit.editor.ThickNetwork
import constructit.editor.Tools
import constructit.geom.Geom3
import constructit.geom.GeomMath
import constructit.geom.Justification
import constructit.geom.ProfileElement
import constructit.geom.Vec2
import constructit.units.mm
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The OP-21 **extension**: a wall is a thickness applied to an arbitrary connected graph of points and
 * point-connecting curves, with a side per curve.
 *
 * What these pin, in order of how much they pay: that a **branch vertex** resolves by cyclic order into one
 * ordinary corner per angularly adjacent pair — the T/L junction cleanup, with no 2D boolean and no sliver;
 * that arcs offset **exactly** to concentric arcs while a Bézier is honestly approximated (OP-15); that a
 * side belongs to a curve; and that everything already built on a wall — openings, jambs, the plan
 * convention, the 3D cut — carried over with no case per curve kind, because a leg is an arc length.
 */
class ThickNetworkTest {
    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerDown(s)
        pointerUp(s)
    }

    private fun Document.seg(
        a: Vec2,
        b: Vec2,
    ): Element = segment(freePoint(a.x.mm, a.y.mm), freePoint(b.x.mm, b.y.mm))!!

    /** An arc about [c] from [from] to [to], swept counter-clockwise. */
    private fun Document.arc(
        c: Vec2,
        from: Vec2,
        to: Vec2,
    ): Element = arcCenterStartEnd(freePoint(c.x.mm, c.y.mm), freePoint(from.x.mm, from.y.mm), freePoint(to.x.mm, to.y.mm))!!

    private fun regionOf(tn: ThickNetwork) = Evaluator().region(tn.footprint.ref as RegionRef)

    private fun areaOf(
        doc: Document,
        tn: ThickNetwork,
    ) = Evaluator().scalar(doc.cx.regionArea(tn.footprint.ref as RegionRef)).base

    /** Segment → quarter arc → segment, tangent at both joints: the everyday curved wall. */
    private fun curvedWall(side: Justification = Justification.CENTER): Pair<Editor, ThickNetwork> {
        val ed = Editor()
        val t = ed.doc.newParameter("t", 10.0.mm)
        val a = ed.doc.seg(Vec2(0.0, 0.0), Vec2(50.0, 0.0))
        val b = ed.doc.arc(Vec2(50.0, 50.0), Vec2(50.0, 0.0), Vec2(100.0, 50.0))
        val c = ed.doc.seg(Vec2(100.0, 50.0), Vec2(100.0, 100.0))
        val tn = assertNotNull(ed.doc.buildThickNetwork(listOf(a, b, c), List(3) { side }, t.ref), ed.doc.takeNote())
        return ed to tn
    }

    // ---- offsets per curve kind ----

    @Test
    fun anArcCarrierOffsetsToConcentricArcsAndMitresIntoItsNeighbours() {
        val (ed, tn) = curvedWall()
        val reg = regionOf(tn)
        assertTrue(reg.holes.isEmpty(), "an open carrier bounds a single loop")

        val arcs = reg.outer.elements.filterIsInstance<ProfileElement.ArcE>().map { it.arc }
        assertEquals(2, arcs.size, "the arc leg contributes two faces, and both are arcs — not chords")
        for (a in arcs) {
            assertClose(a.center.x, 50.0, msg = "concentric with the carrier arc")
            assertClose(a.center.y, 50.0)
        }
        assertClose(arcs.map { it.radius }.min(), 45.0, msg = "inner face is r - t/2")
        assertClose(arcs.map { it.radius }.max(), 55.0, msg = "outer face is r + t/2")

        // the joints are tangent, so the mitres land exactly where the offsets already met — which is what
        // makes the area the plain sum of the three bands with no overlap and no gap
        val exact = 50.0 * 10.0 + (PI / 4.0) * (55.0 * 55.0 - 45.0 * 45.0) + 50.0 * 10.0
        assertClose(areaOf(ed.doc, tn), exact, tol = 1e-6, msg = "two straight bands plus a quarter annulus")

        val body = assertNotNull(ed.doc.bodyOf(tn, Evaluator()))
        assertTrue(!body.approximated, "lines and arcs offset exactly — no kernel, no tessellation (OP-15)")
        assertClose(body.legs[1].length, (PI / 2.0) * 50.0, msg = "a leg's length is its ARC length")
    }

    /** A wall thicker than twice the arc it follows has no inner face at all: invalid with a reason (OP-3). */
    @Test
    fun anArcThinnerThanTheWallIsRefusedByName() {
        val ed = Editor()
        val t = ed.doc.newParameter("t", 10.0.mm)
        val a = ed.doc.arc(Vec2(0.0, 0.0), Vec2(20.0, 0.0), Vec2(0.0, 20.0))
        val tn = assertNotNull(ed.doc.buildThickNetwork(listOf(a), listOf(Justification.CENTER), t.ref))
        assertTrue(Evaluator().eval(tn.footprint.ref.node) is EvalResult.Ok, "10 thick on r=20 is fine")

        ed.doc.setParameter(ed.doc.scalars.first { it.name == "t" }, 50.0.mm)
        val bad = Evaluator().eval(tn.footprint.ref.node)
        assertTrue(bad is EvalResult.Invalid && bad.reason.contains("thicker than the arc"), "got: $bad")

        ed.doc.setParameter(ed.doc.scalars.first { it.name == "t" }, 10.0.mm)
        assertTrue(Evaluator().eval(tn.footprint.ref.node) is EvalResult.Ok, "and it heals")
    }

    /**
     * A Bézier's offset is **not** a Bézier (OP-15), so it is sampled — and says so. What is asserted is the
     * honest thing: the sampled face lies within the tessellation tolerance of the *exact* offset (the
     * carrier point displaced along its own normal) at points all along the curve.
     */
    @Test
    fun aBezierCarrierIsOffsetByTessellationAndSaysSo() {
        val ed = Editor()
        val t = ed.doc.newParameter("t", 10.0.mm)
        val p = (0..3).map { ed.doc.freePoint(listOf(0.0, 30.0, 70.0, 100.0)[it].mm, listOf(0.0, 60.0, -20.0, 40.0)[it].mm) }
        ed.doc.bezierCurve(p[0], p[1], p[2], p[3])
        val b = ed.doc.elements.last { it.kind == ElementKind.BEZIER }
        val tn = assertNotNull(ed.doc.buildThickNetwork(listOf(b), listOf(Justification.CENTER), t.ref))
        val note = assertNotNull(ed.doc.takeNote())

        val body = assertNotNull(ed.doc.bodyOf(tn, Evaluator()))
        assertTrue(body.approximated, "a sampled offset is OP-15's approximated class, and the type says so")
        assertTrue(note.contains("approximated"), "and so does the tool: $note")

        // The honest claim: the drawn offset is **exact at every sample parameter**, and approximate only in
        // the chords between them — the same bargain every tessellated curve makes here (OP-15).
        val ring = Geom3.tessellateLoop(regionOf(tn).outer)
        val bez = (Evaluator().valueOf(b.ref) as constructit.core.BezierValue).bezier
        for (k in 0..GeomMath.BEZIER_STEPS) {
            val u = k.toDouble() / GeomMath.BEZIER_STEPS
            val n = GeomMath.bezierTangentAt(bez, u).normalized().perp()
            for (off in listOf(-5.0, 5.0)) {
                val exact = GeomMath.bezierPointAt(bez, u) + n * off
                assertTrue(ring.any { (it - exact).length() < 1e-9 }, "the exact offset at t=$u is a drawn corner")
            }
        }
        // and a position *along* the leg still reads the exact offset there, so a jamb lands on the curve
        val leg = body.legs.single()
        val mid = leg.facePoint(leg.length / 2.0, 1)
        assertClose((mid - leg.pointAt(leg.length / 2.0)).length(), 5.0, tol = 1e-9, msg = "half the thickness out")
    }

    // ---- a side per curve ----

    @Test
    fun eachCurveCarriesItsOwnSide() {
        val ed = Editor()
        val t = ed.doc.newParameter("t", 10.0.mm)
        val a = ed.doc.seg(Vec2(0.0, 0.0), Vec2(100.0, 0.0)) // runs +X, so LEFT is +Y
        val b = ed.doc.seg(Vec2(100.0, 0.0), Vec2(100.0, 100.0)) // runs +Y, so RIGHT is +X
        val tn =
            assertNotNull(
                ed.doc.buildThickNetwork(listOf(a, b), listOf(Justification.LEFT, Justification.RIGHT), t.ref),
                ed.doc.takeNote(),
            )
        val pts = regionOf(tn).outer.elements.map { GeomMath.startOf(it) }

        fun has(
            x: Double,
            y: Double,
        ) = pts.any { abs(it.x - x) < 1e-6 && abs(it.y - y) < 1e-6 }
        assertTrue(has(0.0, 0.0) && has(0.0, 10.0), "the first leg's material is entirely on its left")
        assertTrue(has(110.0, 0.0) && has(110.0, 100.0), "the second leg's is entirely on its right")
        // the two bands share the line x = 100 and meet at a mitre, so the union is simply their sum
        assertClose(areaOf(ed.doc, tn), 2000.0, tol = 1e-6, msg = "100x10 plus 100x10, no overlap")
    }

    // ---- the branch vertex: the T/L junction cleanup ----

    /**
     * The headline of this extension. Three walls meeting at one carrier vertex used to be three footprints
     * that visibly overlapped; they are now **one carrier**, and the boundary resolves by cyclic order into
     * one ordinary corner per angularly adjacent pair. One region, no interior edge, no sliver — and the
     * area is exactly `sum of bands − overlaps`, which is computable here because everything is rectilinear.
     */
    @Test
    fun aTJunctionOfThreeWallsIsOneRegionWithNoSliver() {
        val ed = Editor()
        val t = ed.doc.newParameter("t", 10.0.mm)
        val hub = Vec2(0.0, 0.0)
        val a = ed.doc.seg(Vec2(-50.0, 0.0), hub)
        val b = ed.doc.seg(hub, Vec2(50.0, 0.0))
        val c = ed.doc.seg(hub, Vec2(0.0, 50.0))
        val tn = assertNotNull(ed.doc.buildThickNetwork(listOf(a, b, c), List(3) { Justification.CENTER }, t.ref), ed.doc.takeNote())

        val reg = regionOf(tn)
        assertTrue(reg.holes.isEmpty(), "a T encloses nothing")
        assertEquals(1, ed.doc.elements.count { it.kind == ElementKind.AREA }, "three walls, ONE footprint element")

        // 100x10 bar + 10x50 stem, less the 10x5 they share — exactly, because every corner is a mitre
        assertClose(areaOf(ed.doc, tn), 1000.0 + 500.0 - 50.0, tol = 1e-6)

        val pts = reg.outer.elements.map { GeomMath.startOf(it) }

        fun has(
            x: Double,
            y: Double,
        ) = pts.any { abs(it.x - x) < 1e-6 && abs(it.y - y) < 1e-6 }
        assertTrue(has(-5.0, 5.0) && has(5.0, 5.0), "the two inner corners of the branch are mitres")
        assertTrue(pts.none { abs(it.y) < 1e-9 && abs(it.x) < 1e-9 }, "and the hub itself is not on the boundary")
        assertTrue(!assertNotNull(ed.doc.bodyOf(tn, Evaluator())).approximated, "resolved by construction, not by the kernel")
    }

    /** A cross is the same rule with k = 4, and a *figure-8* the same rule producing two holes. */
    @Test
    fun aFigureEightNetworkIsOneRegionWithTwoHoles() {
        val ed = Editor()
        val t = ed.doc.newParameter("t", 4.0.mm)
        val hub = Vec2(0.0, 0.0)
        val ring1 =
            listOf(
                ed.doc.seg(hub, Vec2(40.0, 0.0)),
                ed.doc.seg(Vec2(40.0, 0.0), Vec2(40.0, 40.0)),
                ed.doc.seg(Vec2(40.0, 40.0), Vec2(0.0, 40.0)),
                ed.doc.seg(Vec2(0.0, 40.0), hub),
            )
        val ring2 =
            listOf(
                ed.doc.seg(hub, Vec2(-40.0, 0.0)),
                ed.doc.seg(Vec2(-40.0, 0.0), Vec2(-40.0, -40.0)),
                ed.doc.seg(Vec2(-40.0, -40.0), Vec2(0.0, -40.0)),
                ed.doc.seg(Vec2(0.0, -40.0), hub),
            )
        val tn =
            assertNotNull(
                ed.doc.buildThickNetwork(ring1 + ring2, List(8) { Justification.CENTER }, t.ref),
                ed.doc.takeNote(),
            )
        val reg = regionOf(tn)
        assertEquals(2, reg.holes.size, "two rooms, so two holes — a figure-8 is honest, not refused")
        assertTrue(GeomMath.signedArea(reg.outer) > 0.0 && reg.holes.all { GeomMath.signedArea(it) < 0.0 })
    }

    /** A ring whose legs include an arc: OP-14's hole machinery, and it extrudes watertight. */
    @Test
    fun aRingOfTwoArcsIsAnAnnulusAndExtrudesWatertight() {
        val ed = Editor()
        val t = ed.doc.newParameter("t", 10.0.mm)
        val upper = ed.doc.arc(Vec2(0.0, 0.0), Vec2(50.0, 0.0), Vec2(-50.0, 0.0))
        val lower = ed.doc.arc(Vec2(0.0, 0.0), Vec2(-50.0, 0.0), Vec2(50.0, 0.0))
        val tn = assertNotNull(ed.doc.buildThickNetwork(listOf(upper, lower), List(2) { Justification.CENTER }, t.ref), ed.doc.takeNote())

        val reg = regionOf(tn)
        assertEquals(1, reg.holes.size, "a circular wall ring is exactly OP-14's hole machinery")
        assertClose(areaOf(ed.doc, tn), PI * (55.0 * 55.0 - 45.0 * 45.0), tol = 1e-6, msg = "an exact annulus")
        assertTrue(reg.outer.elements.all { it is ProfileElement.ArcE }, "and its boundary is arcs, not chords")

        val h = ed.doc.newParameter("h", 30.0.mm)
        val solid = assertNotNull(ed.doc.extrudeSolid(tn.footprint, h.ref))
        val mesh = (Evaluator().valueOf(solid.ref as SolidRef) as constructit.core.SolidValue).solid.mesh
        assertManifold(mesh, "a curved wall ring extrudes watertight")
    }

    /**
     * Two collinear legs whose **sides differ** have parallel offsets that never meet, so there is no mitre
     * to take. The walk joins the two wall ends with a straight *step* — the same construction an end cap is
     * — instead of refusing, because a wall that changes side is a real plan, not a degenerate one.
     */
    @Test
    fun offsetsThatCannotMitreAreJoinedByAStep() {
        val ed = Editor()
        val t = ed.doc.newParameter("t", 20.0.mm)
        val a = ed.doc.seg(Vec2(0.0, 0.0), Vec2(100.0, 0.0))
        val b = ed.doc.seg(Vec2(100.0, 0.0), Vec2(200.0, 0.0))
        val tn =
            assertNotNull(
                ed.doc.buildThickNetwork(listOf(a, b), listOf(Justification.LEFT, Justification.RIGHT), t.ref),
                ed.doc.takeNote(),
            )
        val body = assertNotNull(ed.doc.bodyOf(tn, Evaluator()))
        assertTrue(!body.approximated, "a step is a construction, not a fallback")
        assertClose(areaOf(ed.doc, tn), 4000.0, tol = 1e-6, msg = "two 100x20 bands, one above the line and one below")
        assertTrue(
            body.joins.any { abs(it.a.x - 100.0) < 1e-9 && abs(it.b.x - 100.0) < 1e-9 && abs(abs(it.a.y - it.b.y) - 20.0) < 1e-9 },
            "the side change shows up as a 20 mm step across the carrier: ${body.joins}",
        )
    }

    /**
     * The one case pairwise construction **cannot** express, and the honest correction to OP-21's old "don't
     * reach for 2D booleans" note: a spur folded back along the wall it came from puts two faces on top of
     * each other, and no adjacent-pair corner can remove the overlap. The traced ring is then not simple,
     * which is detected as a *sign* (its total turning is two full circles, not one), and the footprint is
     * resolved by OP-22's kernel instead — at the price, stated in the type, of becoming approximated.
     */
    @Test
    fun overlappingFacesTakeTheKernelRouteAndSaySo() {
        val ed = Editor()
        val t = ed.doc.newParameter("t", 20.0.mm)
        val a = ed.doc.seg(Vec2(0.0, 0.0), Vec2(100.0, 0.0))
        val spur = ed.doc.seg(Vec2(100.0, 0.0), Vec2(60.0, 0.0)) // folds straight back along the first
        val tn = assertNotNull(ed.doc.buildThickNetwork(listOf(a, spur), List(2) { Justification.CENTER }, t.ref), ed.doc.takeNote())

        val body = assertNotNull(ed.doc.bodyOf(tn, Evaluator()))
        assertTrue(body.approximated, "the kernel is polygonal, so its result is OP-15's approximated class")
        val reg = regionOf(tn)
        assertTrue(reg.holes.isEmpty(), "one area, and the doubled stretch is not counted twice")
        assertClose(areaOf(ed.doc, tn), 2000.0, tol = 1e-6, msg = "the union of a band with a piece of itself is the band")
    }

    // ---- refusals, by name ----

    @Test
    fun aDisconnectedPickIsRefusedByName() {
        val ed = Editor()
        val t = ed.doc.newParameter("t", 10.0.mm)
        val a = ed.doc.seg(Vec2(0.0, 0.0), Vec2(50.0, 0.0))
        val b = ed.doc.seg(Vec2(80.0, 0.0), Vec2(120.0, 0.0))
        assertNull(ed.doc.buildThickNetwork(listOf(a, b), List(2) { Justification.CENTER }, t.ref))
        val why = assertNotNull(ed.doc.takeNote())
        assertTrue(why.contains("not connected") && why.contains("2 separate runs"), "got: $why")
        assertEquals(0, ed.doc.elements.count { it.kind == ElementKind.AREA }, "a refused pick leaves no node behind")
    }

    @Test
    fun aWholeCircleCannotBeACarrier() {
        val ed = Editor()
        val t = ed.doc.newParameter("t", 10.0.mm)
        val c = ed.doc.circleCR(ed.doc.freePoint(0.0.mm, 0.0.mm), t.ref)!!
        assertNull(ed.doc.buildThickNetwork(listOf(c), listOf(Justification.CENTER), t.ref))
        assertTrue(assertNotNull(ed.doc.takeNote()).contains("break it into arcs first"))
    }

    /**
     * Connectivity is a function of **values**, so it is checked inside the node: pulling a carrier end away
     * makes the footprint invalid with the same reason the pick would have been refused with, and putting it
     * back heals it (OP-3). This is the OP-21 purity rule applied to a topology instead of to a sort order.
     */
    @Test
    fun draggingTheNetworkApartInvalidatesItWithAReasonAndItHeals() {
        val ed = Editor()
        val t = ed.doc.newParameter("t", 10.0.mm)
        // two *separate* points at one place: what makes them one junction is where they are, which is a
        // value — so the graph this network has is re-read on every pass
        val a = ed.doc.segment(ed.doc.freePoint(0.0.mm, 0.0.mm), ed.doc.freePoint(50.0.mm, 0.0.mm))!!
        val joint = ed.doc.freePoint(50.0.mm, 0.0.mm)
        val jointEl = ed.doc.elements.last { it.kind == ElementKind.POINT }
        val b = ed.doc.segment(joint, ed.doc.freePoint(50.0.mm, 50.0.mm))!!
        val tn = assertNotNull(ed.doc.buildThickNetwork(listOf(a, b), List(2) { Justification.CENTER }, t.ref), ed.doc.takeNote())
        assertTrue(Evaluator().eval(tn.footprint.ref.node) is EvalResult.Ok)

        val nodesBefore = ed.doc.cx.nodesCreated
        ed.doc.moveFreePoint(jointEl, Vec2(90.0, 0.0))
        val bad = Evaluator().eval(tn.footprint.ref.node)
        assertTrue(bad is EvalResult.Invalid && bad.reason.contains("not connected"), "got: $bad")
        assertEquals(nodesBefore, ed.doc.cx.nodesCreated, "the topology is re-read, not rebuilt")

        ed.doc.moveFreePoint(jointEl, Vec2(50.0, 0.0))
        assertTrue(Evaluator().eval(tn.footprint.ref.node) is EvalResult.Ok, "and it heals (OP-3)")
    }

    // ---- openings on any curve kind ----

    /**
     * An interval's position is a distance along its leg's own **arc length**, which is what it always was —
     * the leg was just always straight. So an opening on an arc wall needs nothing new: its jambs come out
     * radial, and dragging one slides the opening *along the arc*.
     */
    @Test
    fun anOpeningOnAnArcLegIsPositionedByArcLengthAndItsJambsAreRadial() {
        val (ed, tn) = curvedWall()
        val w = ed.doc.newParameter("w", 12.0.mm)
        val arcLength = (PI / 2.0) * 50.0
        assertNotNull(ed.doc.addInterval(tn, 1, 20.0.mm, w.ref, 0.0.mm, 2100.0.mm))
        assertClose(assertNotNull(ed.doc.legLengthOf(tn, 1)), arcLength, msg = "the arc leg's extent is its arc length")

        val jambs = ed.doc.jambsOf(tn, Evaluator())
        assertEquals(2, jambs.size)
        for (j in jambs) {
            // a radial line: both ends share one angle about the arc's centre, 45 and 55 from it
            val c = Vec2(50.0, 50.0)
            assertClose((j.seg.a - c).length() + (j.seg.b - c).length(), 100.0, msg = "45 + 55 from the centre")
            assertClose((j.seg.a - c).angle(), (j.seg.b - c).angle(), msg = "and both on one radius")
        }

        // dragging the leading jamb slides the opening along the arc — no case of its own
        val lead = jambs.first { !it.atEnd }
        val at = tn.let { assertNotNull(ed.doc.bodyOf(it, Evaluator())) }.legs[1].pointAt(50.0)
        lead.handle(ed.doc).drag(at, Evaluator())
        assertClose(Evaluator().scalar(tn.intervals.single().position).mm, 50.0, tol = 1e-6, msg = "50 mm ALONG the arc")

        // and it is clamped by the leg's arc length, with the same rule a typed number gets
        lead.handle(ed.doc).drag(ed.doc.bodyOf(tn, Evaluator())!!.legs[1].pointAt(arcLength + 20.0), Evaluator())
        assertClose(Evaluator().scalar(tn.intervals.single().position).mm, arcLength - 12.0, tol = 1e-6)
    }

    /**
     * The 3D half, and the place the extension's one real defect lived: an opening on a **curved** leg cuts,
     * and the result is still one manifold solid (OP-22).
     *
     * A cutter that shared the wall's curved faces was two *independent tessellations of one arc* — near
     * coincident rather than coincident — so the kernel returned a crescent sliver per chord crossing, each
     * became a sub-slab, and the shell came back cracked. On a curved leg the cutter therefore overhangs
     * (see `ThickLeg.cutterOffsets`), which is asserted here two ways: the mesh is manifold, and the volume
     * removed is still exactly the opening's own — the overhang takes nothing, because it is outside.
     */
    @Test
    fun cutOpeningsWorksOnAnArcWall() {
        val (ed, tn) = curvedWall()
        val w = ed.doc.newParameter("w", 12.0.mm)
        ed.doc.addInterval(tn, 1, 20.0.mm, w.ref, 0.0.mm, 2000.0.mm)
        val h = ed.doc.newParameter("h", 2000.0.mm)
        val solid = assertNotNull(ed.doc.extrudeSolid(tn.footprint, h.ref))
        val cut = assertNotNull(ed.doc.cutOpenings(solid), "the wall's opening becomes a subtracted box")
        val mesh = (Evaluator().valueOf(cut.ref as SolidRef) as constructit.core.SolidValue).solid.mesh
        assertManifold(mesh, "a cut through a curved wall stays manifold")

        // the sector removed is (w/r)/2 x (R2 - r2) x height, which for a centred wall is exactly w x t x h
        val before = (Evaluator().valueOf(solid.ref as SolidRef) as constructit.core.SolidValue).solid.mesh
        assertClose(
            Geom3.volume(before) - Geom3.volume(mesh),
            12.0 * 10.0 * 2000.0,
            tol = 2000.0,
            msg = "the overhang is outside the wall, so it removes nothing extra",
        )
    }

    /**
     * The 2D handle and the 3D hole are **one description**, which is the whole point of an interval being a
     * parameter rather than geometry: dragging the jamb on the arc moves the cut, and cannot change how much
     * it removes (OP-13 — the leading jamb writes `pos`, and the width is measured *from* it).
     */
    @Test
    fun draggingAJambOnTheArcMovesTheHoleItCuts() {
        val (ed, tn) = curvedWall()
        val w = ed.doc.newParameter("w", 12.0.mm)
        val iv = assertNotNull(ed.doc.addInterval(tn, 1, 20.0.mm, w.ref, 0.0.mm, 2000.0.mm))
        val h = ed.doc.newParameter("h", 2000.0.mm)
        val solid = assertNotNull(ed.doc.extrudeSolid(tn.footprint, h.ref))
        val cut = assertNotNull(ed.doc.cutOpenings(solid))

        fun mesh() = (Evaluator().valueOf(cut.ref as SolidRef) as constructit.core.SolidValue).solid.mesh

        fun holeCentre(): Vec2 {
            val leg = assertNotNull(ed.doc.bodyOf(tn, Evaluator())).legs[1]
            return leg.pointAt(Evaluator().scalar(iv.position).mm + 6.0)
        }
        val vol = Geom3.volume(mesh())
        val was = holeCentre()

        // the same gesture a user makes: grab the leading jamb where the plan draws it, drop it further on
        val lead = ed.doc.jambsOf(tn, Evaluator()).first { !it.atEnd }
        val target = assertNotNull(ed.doc.bodyOf(tn, Evaluator())).legs[1].pointAt(50.0)
        lead.handle(ed.doc).drag(target, Evaluator())
        assertClose(Evaluator().scalar(iv.position).mm, 50.0, tol = 1e-6, msg = "the jamb slid along the arc")

        assertManifold(mesh(), "and the cut followed it, still manifold")
        // exactly equal analytically; equal to the chord error of the arc's tessellation in the mesh (OP-15)
        assertClose(Geom3.volume(mesh()), vol, tol = vol * 1e-4, msg = "sliding an opening cannot change its volume")
        assertTrue((holeCentre() - was).length() > 25.0, "and the hole is somewhere else on the arc now")
    }

    // ---- the tool, the file, and the key points ----

    /**
     * The tool is the repeating-slot one *Outline* already is, plus the wall-side option applying to the
     * **next** pick. Nothing about the per-curve side needed a new file argument: a side is a discrete
     * choice scored at creation, which is what `signs=` already carries (OP-1/OP-18).
     */
    @Test
    fun theThickenToolRecordsASidePerCurveAndTheFileRoundTrips() {
        val ed = Editor()
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 0.0))
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(100.0, 0.0))
        ed.click(Vec2(100.0, 100.0))
        ed.activeScalar = ed.doc.newParameter("t", 10.0.mm)
        ed.setTool(Tools.THICKEN)
        ed.justification = Justification.LEFT
        ed.click(Vec2(50.0, 0.0))
        ed.justification = Justification.RIGHT
        ed.click(Vec2(100.0, 50.0))
        ed.finishRepeatingTool()

        val tn = ed.doc.thickNetworks.single()
        assertEquals(listOf(Justification.LEFT, Justification.RIGHT), (tn.carrier as ThickCarrier.Network).sides)
        assertClose(areaOf(ed.doc, tn), 2000.0, tol = 1e-6)

        val text = DocumentFormat.save(ed.doc)
        assertTrue(text.contains("tool thicken"), "got:\n$text")
        assertTrue(text.contains("signs=1;2"), "the two sides ride the step's own signs=; got:\n$text")

        val reloaded = DocumentFormat.load(text)
        val back = reloaded.thickNetworks.single()
        assertEquals(listOf(Justification.LEFT, Justification.RIGHT), (back.carrier as ThickCarrier.Network).sides)
        assertClose(
            Evaluator().scalar(reloaded.cx.regionArea(back.footprint.ref as RegionRef)).base,
            2000.0,
            tol = 1e-6,
            msg = "the same shape came back",
        )
        assertEquals(text, DocumentFormat.save(reloaded), "save -> load -> save is byte-equal")
    }

    /**
     * *A wall should show and use its key points.* They are **extracted**, not owned: the count of a
     * footprint's corners is a function of its values, so a set of elements sized by it would have to be
     * regenerated on every edit — the very defect OP-21 exists to forbid. The existing *Key points* tool
     * takes a footprint instead, and what it hands back is ordinary, snappable, dimensionable points.
     */
    @Test
    fun keyPointsOfAFootprintAreExtractedAsOrdinaryPoints() {
        val ed = Editor()
        // thick enough that the middle of the wall is nowhere near its carrier, so the click means the area
        val t = ed.doc.newParameter("t", 60.0.mm)
        val a = ed.doc.seg(Vec2(0.0, 0.0), Vec2(100.0, 0.0))
        val b = ed.doc.seg(Vec2(100.0, 0.0), Vec2(100.0, 100.0))
        val tn = assertNotNull(ed.doc.buildThickNetwork(listOf(a, b), List(2) { Justification.CENTER }, t.ref))

        val before = ed.doc.elements.size
        ed.setTool(Tools.KEY_POINTS)
        ed.click(Vec2(50.0, -29.0)) // inside the wall body, far from every carrier curve
        val made = ed.doc.elements.drop(before)
        assertEquals(regionOf(tn).outer.elements.size, made.size, "one accessor per corner the footprint has now")
        assertTrue(made.all { it.isPoint }, "and they are ordinary points — pickable, snappable, dimensionable")

        val ev = Evaluator()
        val got = made.map { (ev.valueOf(it.ref) as constructit.core.PointValue).p }
        assertTrue(got.any { (it - Vec2(130.0, -30.0)).length() < 1e-6 }, "the outer mitre is a real point: $got")

        // a real point, so it follows the wall by recompute — nothing regenerated
        val nodesBefore = ed.doc.cx.nodesCreated
        ed.doc.setParameter(ed.doc.scalars.first { it.name == "t" }, 120.0.mm)
        val moved = made.map { (Evaluator().valueOf(it.ref) as constructit.core.PointValue).p }
        assertTrue(moved.any { (it - Vec2(160.0, -60.0)).length() < 1e-6 }, "and it followed the thickness: $moved")
        assertEquals(nodesBefore, ed.doc.cx.nodesCreated, "a value edit grows nothing")
    }

    /** A footprint corner accessor is structural, so a footprint with fewer corners says so (OP-3). */
    @Test
    fun aCornerThatIsGoneSaysSoRatherThanPointingSomewhereElse() {
        val ed = Editor()
        val t = ed.doc.newParameter("t", 10.0.mm)
        val a = ed.doc.seg(Vec2(0.0, 0.0), Vec2(100.0, 0.0))
        val tn = assertNotNull(ed.doc.buildThickNetwork(listOf(a), listOf(Justification.CENTER), t.ref))
        val far = ed.doc.cx.regionCorner(tn.footprint.ref as RegionRef, 9)
        val bad = Evaluator().eval(far.node)
        assertTrue(bad is EvalResult.Invalid && bad.reason.contains("corners"), "got: $bad")
    }

    /**
     * A pattern's members are ordinary geometry (OP-23), so the hexagon one segment gesture builds is a
     * carrier like any other: thicken all six sides and the wall closes into a room. *Thicken* itself does
     * not fan round the pattern — it is a repeating tool, which already collects the whole ring in one
     * gesture, so replicating it would build the same wall six times (the structural exception OP-23 names).
     */
    @Test
    fun aPatternsMemberCurvesCanCarryOneWall() {
        val ed = Editor()
        ed.count = 6
        ed.setTool(Tools.PATTERN_CIRCULAR)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 0.0))
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(100.0, 0.0))
        ed.click(Vec2(100.0 * cos(PI / 3), 100.0 * sin(PI / 3)))
        val sides = ed.doc.elements.filter { it.kind == ElementKind.SEGMENT }
        assertEquals(6, sides.size, "one gesture, six sides — that is OP-23")

        val t = ed.doc.newParameter("t", 8.0.mm)
        val tn = assertNotNull(ed.doc.buildThickNetwork(sides, List(6) { Justification.CENTER }, t.ref), ed.doc.takeNote())
        val reg = regionOf(tn)
        assertEquals(1, reg.holes.size, "six walls round a hexagonal room: one outer boundary, one hole")
        // the band of a regular hexagon of side 100: perimeter x thickness, exactly (every corner mitres)
        assertClose(areaOf(ed.doc, tn), 6.0 * 100.0 * 8.0, tol = 1e-6)
    }

    /** Derived geometry is carrier geometry: a mirrored segment thickens exactly as the original does. */
    @Test
    fun derivedCurvesCanCarryAWall() {
        val ed = Editor()
        val t = ed.doc.newParameter("t", 10.0.mm)
        val a = ed.doc.seg(Vec2(0.0, 0.0), Vec2(0.0, 50.0))
        val axis = ed.doc.line(ed.doc.freePoint((-20.0).mm, 0.0.mm), ed.doc.freePoint(20.0.mm, 0.0.mm))!!
        val mirrored = ed.doc.mirror(a, axis)
        val tn =
            assertNotNull(
                ed.doc.buildThickNetwork(listOf(a, mirrored), List(2) { Justification.CENTER }, t.ref),
                ed.doc.takeNote(),
            )
        // the two legs meet at the origin and run straight through it: one 100-long band
        assertClose(areaOf(ed.doc, tn), 1000.0, tol = 1e-6)
    }
}
