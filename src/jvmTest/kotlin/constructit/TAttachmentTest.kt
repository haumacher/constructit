package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.RegionRef
import constructit.dsl.region
import constructit.dsl.scalar
import constructit.editor.Document
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.ThickCarrier
import constructit.editor.ThickNetwork
import constructit.geom.GeomMath
import constructit.geom.Justification
import constructit.geom.Loop
import constructit.geom.ProfileElement
import constructit.geom.Vec2
import constructit.units.mm
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **A T-attachment is a vertex** (GitHub #7, the OP-21 network extension's second half).
 *
 * An endpoint of one carrier that lands in the *interior* of another is a junction of the fat graph and
 * splits the carrier it lands on, inside the footprint node's `compute` like every other value-dependent
 * decision. Once split, a T needs no rule of its own: it is the *k*=3 branch the cyclic-order walk already
 * resolves — three ordinary mitres, one region, no seam and no boolean.
 *
 * The fixtures are the user's own floor plan, verbatim: the hull as four ortho legs plus three interior
 * partitions whose ends `attachortho` puts mid-way along the hull legs.
 */
class TAttachmentTest {
    companion object {
        /** The user's construction: a hull ring and three partitions T-attached to it. */
        val PLAN =
            """
            constructit 2
            orthostart -104.25,-45.25 -> e1
            orthovertex -11.75,-45.25 -> e2,e3
            orthovertex -11.75,9 -> e4,e5
            orthovertex -104.25,9 -> e6,e7
            orthoclose -> e8
            orthostart -85.75,9 -> e9
            attachortho e9 e7
            orthovertex -85.75,-45.25 -> e10,e11
            attachortho e10 e3
            orthostart -26.75,9 -> e12
            attachortho e12 e7
            orthovertex -26.75,-21.5 -> e13,e14
            orthovertex -11.75,-21.5 -> e15,e16
            attachortho e15 e5
            param "d" = 5mm
            """.trimIndent()

        /** Fixture A: the walls as the user got them — three separate thickenings that abut but never join. */
        val THREE_WALLS =
            PLAN + "\n" +
                """
                tool thicken els=e8,e7,e5,e3 clicks=-104.75,-16.25;-67.25,8;-12.25,-1.5;-21,-45.75 scalar="d" signs=2;2;2;2 -> e17
                tool thicken els=e11 clicks=-86,-21.25 scalar="d" signs=0 -> e18
                tool thicken els=e14,e16 clicks=-26.75,-9.5;-20.75,-22 scalar="d" signs=0;0 -> e19
                """.trimIndent()

        /** Fixture B: the same construction as **one** wall over all seven curves — refused before this work. */
        val ONE_WALL =
            PLAN + "\n" +
                "tool thicken els=e8,e7,e5,e3,e11,e14,e16 " +
                "clicks=-104.75,-16.25;-67.25,8;-12.25,-1.5;-21,-45.75;-86,-21.25;-26.75,-9.5;-20.75,-22 " +
                "scalar=\"d\" signs=2;2;2;2;0;0;0 -> e17"

        /** The hull plus the first partition — what one extension gesture must reach. */
        val HULL_PLUS_E11 =
            PLAN + "\n" +
                "tool thicken els=e8,e7,e5,e3,e11 clicks=-104.75,-16.25;-67.25,8;-12.25,-1.5;-21,-45.75;-86,-21.25 " +
                "scalar=\"d\" signs=2;2;2;2;0 -> e17"

        /** The hull alone, which the extension gesture grows (see WallExtendTest). */
        val HULL =
            PLAN + "\n" +
                "tool thicken els=e8,e7,e5,e3 clicks=-104.75,-16.25;-67.25,8;-12.25,-1.5;-21,-45.75 scalar=\"d\" signs=2;2;2;2 -> e17"
    }

    private fun regionOf(tn: ThickNetwork) = Evaluator().region(tn.footprint.ref as RegionRef)

    /** A loop's corner points, deduplicated — what a plan reader would call its corners. */
    private fun cornersOf(l: Loop): List<Vec2> = l.elements.map { GeomMath.startOf(it) }

    private fun assertHasCorner(
        corners: List<Vec2>,
        p: Vec2,
        msg: String = "",
    ) = assertTrue(corners.any { (it - p).length() < 1e-6 }, "expected a corner at $p; got $corners. $msg")

    private fun extentOf(pts: List<Vec2>) =
        listOf(pts.minOf { it.x }, pts.minOf { it.y }, pts.maxOf { it.x }, pts.maxOf { it.y })

    // ---- fixture B: one wall over the whole plan ----

    @Test
    fun oneThickenOverTheWholePlanIsOneWallWithThreeRooms() {
        val doc = DocumentFormat.load(ONE_WALL)
        val tn = assertNotNull(doc.thickNetworks.singleOrNull(), "exactly one wall")
        assertEquals(7, (tn.carrier as ThickCarrier.Network).curves.size)
        assertTrue(Evaluator().eval(tn.footprint.ref.node) is EvalResult.Ok, "the footprint is valid")

        val reg = regionOf(tn)
        assertEquals(3, reg.holes.size, "three rooms, and each is a hole of one footprint")

        val outer = cornersOf(reg.outer)
        val (x0, y0, x1, y1) = extentOf(outer).let { listOf(it[0], it[1], it[2], it[3]) }
        assertClose(x0, -109.25, msg = "the hull wall lies outside its centreline (RIGHT of a ccw ring)")
        assertClose(y0, -50.25)
        assertClose(x1, -6.75)
        assertClose(y1, 14.0)
        assertEquals(4, outer.size, "the outer boundary is the hull's own rectangle — the partitions are inside")

        // the left room: the hull's inner faces on three sides, and the partition's face on the fourth. That
        // the corners are *exactly* these is the T-junction mitre: the partition's two faces (x=-88.25 and
        // x=-83.25) meet the hull's inner face y=9 with nothing between them.
        val left =
            assertNotNull(
                reg.holes.firstOrNull { h -> cornersOf(h).any { (it - Vec2(-104.25, -45.25)).length() < 1e-6 } },
                "a room in the hull's bottom-left corner",
            )
        val lc = cornersOf(left)
        assertEquals(4, lc.size, "a rectangular room has four corners and no seam")
        val room = listOf(Vec2(-104.25, -45.25), Vec2(-88.25, -45.25), Vec2(-88.25, 9.0), Vec2(-104.25, 9.0))
        for (p in room) assertHasCorner(lc, p, "the T at (-85.75, 9) mitres cleanly")
    }

    @Test
    fun oneWallRoundTrips() {
        val doc = DocumentFormat.load(ONE_WALL)
        val saved = DocumentFormat.save(doc)
        assertEquals(saved, DocumentFormat.save(DocumentFormat.load(saved)), "save -> load -> save is byte-equal")
    }

    /** Fixture A keeps loading as it always did: three walls, and nothing merges behind the user's back. */
    @Test
    fun threeSeparateWallsStayThreeWalls() {
        val doc = DocumentFormat.load(THREE_WALLS)
        assertEquals(3, doc.thickNetworks.size, "loading does not merge what the file says are three walls")
        for (tn in doc.thickNetworks) assertTrue(Evaluator().eval(tn.footprint.ref.node) is EvalResult.Ok)
        val saved = DocumentFormat.save(doc)
        assertEquals(saved, DocumentFormat.save(DocumentFormat.load(saved)), "and it round-trips byte-identically")
    }

    /**
     * **The material is the same; the boundary is not.** The user's three walls already covered the right
     * area — the partitions are centred and end on the hull's inner faces, so nothing was missing. What was
     * wrong is that it was three regions with abutting boundaries (hence a seam line at every junction)
     * instead of one region with three rooms in it.
     */
    @Test
    fun oneWallHasTheSameMaterialAsThreeButOneBoundary() {
        val one = DocumentFormat.load(ONE_WALL)
        val three = DocumentFormat.load(THREE_WALLS)

        fun area(
            d: Document,
            tn: ThickNetwork,
        ) = Evaluator().scalar(d.cx.regionArea(tn.footprint.ref as RegionRef)).base
        val joined = area(one, one.thickNetworks.single())
        assertClose(joined, 2066.25, tol = 1e-9, msg = "the hull band plus the three partitions")
        assertClose(three.thickNetworks.sumOf { area(three, it) }, joined, tol = 1e-9, msg = "the same material")

        // …and the difference: one region with three rooms in it, against three regions whose boundaries
        // merely abut — the hull's own ring is the only enclosure any of them knows about
        assertEquals(3, regionOf(one.thickNetworks.single()).holes.size)
        assertEquals(1, three.thickNetworks.sumOf { regionOf(it).holes.size }, "only the hull ring encloses anything")
    }

    // ---- what a T is, in the small ----

    /** A plain T: one segment, and a second ending half-way along it. Three mitres, one region, no hole. */
    @Test
    fun aTeeInTheMiddleOfASegmentIsOneRegion() {
        val ed = Editor()
        val t = ed.doc.newParameter("t", 10.0.mm)
        val across = ed.doc.seg(Vec2(0.0, 0.0), Vec2(100.0, 0.0))
        val stem = ed.doc.seg(Vec2(50.0, 0.0), Vec2(50.0, 60.0))
        val tn =
            assertNotNull(
                ed.doc.buildThickNetwork(listOf(across, stem), List(2) { Justification.CENTER }, t.ref),
                ed.doc.takeNote(),
            )
        val reg = regionOf(tn)
        assertTrue(reg.holes.isEmpty(), "a T encloses nothing")
        val body = assertNotNull(ed.doc.bodyOf(tn, Evaluator()))
        assertTrue(!body.approximated, "three straight mitres — no kernel and no tessellation")
        assertEquals(2, body.legCount, "one leg per picked curve, whatever the split did")
        assertEquals(2, body.legs[0].runs[1].size, "the host's faces are split at the T")
        assertEquals(1, body.legs[1].runs[0].size, "the stem's are not")
        // the area is the two bands minus the double-counted square where they cross
        val area = Evaluator().scalar(ed.doc.cx.regionArea(tn.footprint.ref as RegionRef)).base
        assertClose(area, 100.0 * 10.0 + 60.0 * 10.0 - 10.0 * 5.0, tol = 1e-9, msg = "no sliver, no overlap")
    }

    /**
     * An **oblique** T mitres like any other corner, and exactly: a 45° stem's two faces meet the host's face
     * at the intersections of the offset lines, which are closed-form here (55 ∓ 5√2 on the host's face).
     */
    @Test
    fun anObliqueTeeMitresExactly() {
        val ed = Editor()
        val t = ed.doc.newParameter("t", 10.0.mm)
        val across = ed.doc.seg(Vec2(0.0, 0.0), Vec2(100.0, 0.0))
        val stem = ed.doc.seg(Vec2(50.0, 0.0), Vec2(90.0, 40.0)) // 45° out of the host
        val tn =
            assertNotNull(
                ed.doc.buildThickNetwork(listOf(across, stem), List(2) { Justification.CENTER }, t.ref),
                ed.doc.takeNote(),
            )
        val body = assertNotNull(ed.doc.bodyOf(tn, Evaluator()))
        assertTrue(!body.approximated, "three straight mitres, however sharp — still exact")
        val reg = regionOf(tn)
        assertTrue(reg.holes.isEmpty())

        val corners = cornersOf(reg.outer)
        val root = 5.0 * kotlin.math.sqrt(2.0)
        assertHasCorner(corners, Vec2(55.0 - root, 5.0), "where the stem's left face meets the host's face")
        assertHasCorner(corners, Vec2(55.0 + root, 5.0), "…and its right face")
        // Both of the host's faces are cut into spans by the split — that is what a split is — but on the far
        // side the *boundary* is closed up again, so the region carries one whole face and not two collinear
        // halves with a zero-turning corner between them.
        assertEquals(2, body.legs[0].runs[0].size, "the leg's face runs are one per span")
        assertEquals(2, body.legs[0].runs[1].size)
        val far =
            reg.outer.elements.filterIsInstance<ProfileElement.Seg>().map { it.segment }
                .filter { abs(it.a.y + 5.0) < 1e-9 && abs(it.b.y + 5.0) < 1e-9 }
        assertEquals(1, far.size, "the far face is one piece: $far")
        assertClose(abs(far.single().b.x - far.single().a.x), 100.0, tol = 1e-9, msg = "the whole length of it")
    }

    /** Dragging the T apart is invalid **with a reason**, and pushing it back heals it (OP-3). */
    @Test
    fun aTeePulledApartGoesInvalidByNameAndHeals() {
        val ed = Editor()
        val t = ed.doc.newParameter("t", 10.0.mm)
        val across = ed.doc.seg(Vec2(0.0, 0.0), Vec2(100.0, 0.0))
        val end = ed.doc.freePoint(50.0.mm, 0.0.mm)
        val endEl = ed.doc.elements.last { it.kind == ElementKind.POINT }
        val tip = ed.doc.freePoint(50.0.mm, 60.0.mm)
        val stem = assertNotNull(ed.doc.segment(end, tip))
        val tn =
            assertNotNull(
                ed.doc.buildThickNetwork(listOf(across, stem), List(2) { Justification.CENTER }, t.ref),
                ed.doc.takeNote(),
            )
        assertTrue(Evaluator().eval(tn.footprint.ref.node) is EvalResult.Ok)

        ed.doc.moveFreePoint(endEl, Vec2(50.0, 20.0))
        val bad = Evaluator().eval(tn.footprint.ref.node)
        assertTrue(bad is EvalResult.Invalid && bad.reason.contains("not connected"), "got: $bad")

        ed.doc.moveFreePoint(endEl, Vec2(50.0, 0.0))
        assertTrue(Evaluator().eval(tn.footprint.ref.node) is EvalResult.Ok, "and it heals")
    }

    /** An endpoint *at* the host's own end is an ordinary weld, not a split — the degenerate case collapses. */
    @Test
    fun aTeeAtTheHostsEndIsAnOrdinaryCorner() {
        val ed = Editor()
        val t = ed.doc.newParameter("t", 10.0.mm)
        val a = ed.doc.seg(Vec2(0.0, 0.0), Vec2(100.0, 0.0))
        val b = ed.doc.seg(Vec2(100.0, 0.0), Vec2(100.0, 60.0))
        val tn =
            assertNotNull(
                ed.doc.buildThickNetwork(listOf(a, b), List(2) { Justification.CENTER }, t.ref),
                ed.doc.takeNote(),
            )
        val body = assertNotNull(ed.doc.bodyOf(tn, Evaluator()))
        for (leg in body.legs) for (side in 0..1) assertEquals(1, leg.runs[side].size, "no leg was split")
    }

    /** Two stems ending at the same interior point are one split, not two — and the wall still resolves. */
    @Test
    fun twoTeesAtOneSpotAreOneSplit() {
        val ed = Editor()
        val t = ed.doc.newParameter("t", 10.0.mm)
        val across = ed.doc.seg(Vec2(0.0, 0.0), Vec2(100.0, 0.0))
        val up = ed.doc.seg(Vec2(50.0, 0.0), Vec2(50.0, 60.0))
        val down = ed.doc.seg(Vec2(50.0, 0.0), Vec2(50.0, -60.0))
        val tn =
            assertNotNull(
                ed.doc.buildThickNetwork(listOf(across, up, down), List(3) { Justification.CENTER }, t.ref),
                ed.doc.takeNote(),
            )
        val body = assertNotNull(ed.doc.bodyOf(tn, Evaluator()))
        assertEquals(2, body.legs[0].runs[1].size, "one split, from two coincident stems (a cross, k=4)")
        assertTrue(regionOf(tn).holes.isEmpty())
    }

    /** A T on an **arc** host stays exact: the corners lie on the concentric offsets, to 1e-9. */
    @Test
    fun aTeeOnAnArcHostKeepsTheConcentricOffsetsExact() {
        val ed = Editor()
        val t = ed.doc.newParameter("t", 8.0.mm)
        // a half-circle of radius 50 about the origin, from (50,0) counter-clockwise to (-50,0)
        val host = ed.doc.arc(Vec2(0.0, 0.0), Vec2(50.0, 0.0), Vec2(-50.0, 0.0))
        // …and a spur running outward from its top, i.e. from the arc's mid-point
        val spur = ed.doc.seg(Vec2(0.0, 50.0), Vec2(0.0, 90.0))
        val tn =
            assertNotNull(
                ed.doc.buildThickNetwork(listOf(host, spur), List(2) { Justification.CENTER }, t.ref),
                ed.doc.takeNote(),
            )
        val body = assertNotNull(ed.doc.bodyOf(tn, Evaluator()))
        assertTrue(!body.approximated, "an arc host splits *exactly* — no kernel and no tessellation")
        assertEquals(2, body.legs[0].runs[0].size, "the arc host is split at the spur")

        val reg = regionOf(tn)
        val arcs = reg.outer.elements.filterIsInstance<ProfileElement.ArcE>().map { it.arc }
        // the face the spur joins (the outer one, r = 54) is split in two and mitred against it; the face on
        // the far side passes *through* the junction and is closed back up into the single arc it always was
        assertEquals(3, arcs.size, "two split outer faces and one whole inner one: ${'$'}{arcs.map { it.radius }}")
        assertEquals(2, arcs.count { abs(it.radius - 54.0) < 1e-9 }, "the spur's own side is split")
        assertEquals(1, arcs.count { abs(it.radius - 46.0) < 1e-9 }, "the far side keeps one face, with no seam")
        for (a in arcs) {
            assertClose(a.center.x, 0.0, tol = 1e-9, msg = "concentric with the carrier")
            assertClose(a.center.y, 0.0, tol = 1e-9)
            assertTrue(
                abs(a.radius - 46.0) < 1e-9 || abs(a.radius - 54.0) < 1e-9,
                "a face radius is r -+ t/2, exactly: ${a.radius}",
            )
        }
        // every corner of the boundary lies on one of the two concentric circles, or on the spur's faces
        for (p in cornersOf(reg.outer)) {
            val r = p.length()
            val onFace = abs(r - 46.0) < 1e-9 || abs(r - 54.0) < 1e-9
            val onSpur = abs(abs(p.x) - 4.0) < 1e-9
            assertTrue(onFace || onSpur, "corner $p is neither on a concentric face nor on the spur")
        }
    }

    /** Two curves that merely **cross** are not a junction: no endpoint, no vertex, refused by name. */
    @Test
    fun twoCurvesThatOnlyCrossAreStillRefused() {
        val ed = Editor()
        val t = ed.doc.newParameter("t", 5.0.mm)
        val a = ed.doc.seg(Vec2(-50.0, 0.0), Vec2(50.0, 0.0))
        val b = ed.doc.seg(Vec2(0.0, -50.0), Vec2(0.0, 50.0))
        assertEquals(null, ed.doc.buildThickNetwork(listOf(a, b), List(2) { Justification.CENTER }, t.ref))
        val why = assertNotNull(ed.doc.takeNote())
        assertTrue(why.contains("not connected"), "an X crossing has no shared point to join at: $why")
    }

    // ---- the plan drawing does not bridge a junction ----

    /** The seam the user saw: a T's face gap must not be drawn across. */
    @Test
    fun thePlanDrawsNoSeamAcrossATee() {
        val ed = Editor()
        val t = ed.doc.newParameter("t", 10.0.mm)
        val across = ed.doc.seg(Vec2(0.0, 0.0), Vec2(100.0, 0.0))
        val stem = ed.doc.seg(Vec2(50.0, 0.0), Vec2(50.0, 60.0))
        val tn =
            assertNotNull(
                ed.doc.buildThickNetwork(listOf(across, stem), List(2) { Justification.CENTER }, t.ref),
                ed.doc.takeNote(),
            )
        val plan = assertNotNull(ed.doc.planOf(tn, Evaluator())).segments()
        // the host's upper face is interrupted by the stem: y=+5 from x=0..45 and from x=55..100, and
        // nothing in between
        val upper = plan.filter { abs(it.a.y - 5.0) < 1e-9 && abs(it.b.y - 5.0) < 1e-9 }
        assertEquals(2, upper.size, "two stretches of the interrupted face: $upper")
        assertTrue(
            upper.none { s -> abs(s.a.x - 45.0) < 1e-9 && abs(s.b.x - 55.0) < 1e-9 },
            "and no piece bridging the junction: $upper",
        )
    }

    // ---- openings keep their meaning across a T ----

    /**
     * An opening on the host leg is measured along the **carrier**, so a T landing on that leg does not move
     * it, hide it or change its jambs. The same numbers before and after, asserted.
     */
    @Test
    fun anOpeningOnTheHostSurvivesATeeLandingOnItsLeg() {
        val ed = Editor()
        val t = ed.doc.newParameter("t", 10.0.mm)
        val across = ed.doc.seg(Vec2(0.0, 0.0), Vec2(100.0, 0.0))
        val width = ed.doc.newParameter("w", 12.0.mm)
        val before =
            assertNotNull(
                ed.doc.buildThickNetwork(listOf(across), listOf(Justification.CENTER), t.ref),
                ed.doc.takeNote(),
            )
        assertNotNull(ed.doc.addInterval(before, 0, 20.0.mm, width.ref, 0.0.mm, 2100.0.mm))
        val jambsBefore = ed.doc.jambsOf(before, Evaluator()).map { it.seg }

        val stem = ed.doc.seg(Vec2(70.0, 0.0), Vec2(70.0, 60.0))
        val after =
            assertNotNull(
                ed.doc.buildThickNetwork(listOf(across, stem), List(2) { Justification.CENTER }, t.ref),
                ed.doc.takeNote(),
            )
        assertNotNull(ed.doc.addInterval(after, 0, 20.0.mm, width.ref, 0.0.mm, 2100.0.mm))
        val jambsAfter = ed.doc.jambsOf(after, Evaluator()).map { it.seg }

        assertEquals(jambsBefore.size, jambsAfter.size, "the same two jambs")
        for ((b, a) in jambsBefore.zip(jambsAfter)) {
            assertClose(a.a.x, b.a.x, tol = 1e-9, msg = "a jamb does not move because a T landed on its leg")
            assertClose(a.a.y, b.a.y, tol = 1e-9)
            assertClose(a.b.x, b.b.x, tol = 1e-9)
            assertClose(a.b.y, b.b.y, tol = 1e-9)
        }
    }

    private fun Document.seg(
        a: Vec2,
        b: Vec2,
    ): Element = assertNotNull(segment(freePoint(a.x.mm, a.y.mm), freePoint(b.x.mm, b.y.mm)))

    private fun Document.arc(
        c: Vec2,
        from: Vec2,
        to: Vec2,
    ): Element =
        assertNotNull(
            arcCenterStartEnd(freePoint(c.x.mm, c.y.mm), freePoint(from.x.mm, from.y.mm), freePoint(to.x.mm, to.y.mm)),
        )
}
