package constructit

import constructit.core.ArcValue
import constructit.core.BezierValue
import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.SegmentValue
import constructit.core.SolidValue
import constructit.dsl.LoopRef
import constructit.dsl.loop
import constructit.dsl.valueOf
import constructit.editor.Document
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.Bezier
import constructit.geom.GeomMath
import constructit.geom.Vec2
import constructit.units.mm
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **The join of two drawn curves is a drawing curve** (GitHub #34: *"connect curves result cannot be used to
 * define an outline"*).
 *
 * The report's own drawing: two segments meeting at a corner, that corner filleted, and then *Connect two
 * curves* from the free end of one segment to the free end of the other — a bend closing the figure along
 * the bottom. Every drawing tool takes a `CURVE`, so a join that is a curve in *space* can bound nothing,
 * although the whole of it lies in the sketch plane.
 *
 * What is asserted here is the structural rule that fixes it — both picks drawn, of one space, therefore
 * `BEZIER` (OP-21: read off the picks, never off where anything stands) — the outline it now closes, that
 * outline's area measured against an independent one, the G2 mode's three pieces, that a curve in space or
 * a second space still gives the run in space it always did, and what an older file does on load.
 */
class ConnectInPlaneTest {
    /** The report's file, verbatim (GitHub #34) — written by the build that could not bound anything with it. */
    private val fixture =
        """
        constructit 4
        point -60.625,-15.875 -> e1
        point -27.5,45.5 -> e2
        tool segment pts=e1,e2 clicks=-60.625,-15.875;-39.125,50.375 -> e3
        point 0.125,-11.125 -> e4
        tool segment pts=e2,e4 clicks=-40.125,49.125;33.125,-24.875 -> e5
        param "r2" = 4mm
        tool fillet els=e3,e5 clicks=-50.125,16.875;-20.625,30.125 scalar="r2" signs=-1;1 -> e6
        tool connect els=e3,e5 clicks=-59.875,-12.875;-4.375,-26.875 signs=0;0 dofs=1;1 -> e7
        """.trimIndent() + "\n"

    /** The same drawing up to the fillet — what the *Connect* gesture is then performed on, by clicking. */
    private val drawing = fixture.lines().dropLast(2).joinToString("\n") + "\n"

    /**
     * Where the report's two clicks landed, in this test's own zoom: **near the free end of each segment**,
     * which is the half of the gesture that says *which* end (OP-1). Measured off the segment rather than
     * written out, because the fillet has moved its other end and a click has to be nearer the one it means.
     */
    private fun nearStart(el: Element): Vec2 {
        val (a, b) = segmentEnds(el)
        return a + (b - a).normalized() * 3.0
    }

    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerMove(s)
        pointerDown(s)
        pointerUp(s)
    }

    private fun named(
        doc: Document,
        name: String,
    ): Element = doc.elements.first { doc.nameOf(it) == name }

    private fun bezierOf(el: Element): Bezier = (Evaluator().valueOf(el.ref) as BezierValue).bezier

    private fun reasonOf(el: Element): String? = (Evaluator().eval(el.ref.node) as? EvalResult.Invalid)?.reason

    private fun segmentEnds(el: Element): Pair<Vec2, Vec2> =
        (Evaluator().valueOf(el.ref) as SegmentValue).seg.let { it.a to it.b }

    private fun assertVec(
        actual: Vec2,
        expected: Vec2,
        tol: Double = 1e-9,
        msg: String = "",
    ) {
        assertClose(actual.x, expected.x, tol, "$msg (x)")
        assertClose(actual.y, expected.y, tol, "$msg (y)")
    }

    /** That two directions are parallel, read as the cross product of their unit vectors. */
    private fun assertAlong(
        actual: Vec2,
        expected: Vec2,
        msg: String,
    ) {
        val u = actual.normalized()
        val v = expected.normalized()
        assertClose(abs(u.cross(v)), 0.0, 1e-9, "$msg — $actual is not along $expected")
    }

    // ---- an area measured without asking the outline ----

    /** How finely a curved piece is sampled for the independent area below. */
    private val steps = 20000

    /**
     * One element's own polyline, read from **its** value rather than from the boundary that used it — which
     * is what makes the area computed from these an independent number.
     */
    private fun samplesOf(el: Element): List<Vec2> {
        val ev = Evaluator()
        return when (val v = ev.valueOf(el.ref)) {
            is SegmentValue -> listOf(v.seg.a, v.seg.b)
            is BezierValue -> (0..steps).map { GeomMath.bezierPointAt(v.bezier, it.toDouble() / steps) }
            is ArcValue -> {
                val a = v.arc
                val sweep = if (a.ccw) turn(a.endAngle - a.startAngle) else -turn(a.startAngle - a.endAngle)
                (0..steps).map {
                    val t = a.startAngle + sweep * it.toDouble() / steps
                    a.center + Vec2(cos(t), sin(t)) * a.radius
                }
            }
            else -> error("no polyline for ${el.kind}")
        }
    }

    private fun turn(x: Double): Double = ((x % (2 * PI)) + 2 * PI) % (2 * PI)

    /** The area of the ring the given pieces make, chained end to end here rather than by the boundary code. */
    private fun ringArea(pieces: List<Element>): Double {
        val left = pieces.map { samplesOf(it) }.toMutableList()
        val ring = ArrayList(left.removeAt(0))
        while (left.isNotEmpty()) {
            val here = ring.last()
            val i = left.indexOfFirst { (it.first() - here).length() < 1e-6 || (it.last() - here).length() < 1e-6 }
            assertTrue(i >= 0, "the pieces do not chain: no piece starts or ends at $here")
            val next = left.removeAt(i)
            ring.addAll((if ((next.first() - here).length() < 1e-6) next else next.reversed()).drop(1))
        }
        assertTrue((ring.first() - ring.last()).length() < 1e-6, "and the ring closes")
        var twice = 0.0
        for (i in 0 until ring.size - 1) twice += ring[i].cross(ring[i + 1])
        return abs(twice) / 2.0
    }

    private fun areaOfOutline(el: Element): Double {
        @Suppress("UNCHECKED_CAST")
        val ref = el.ref as LoopRef
        return abs(GeomMath.signedArea(Evaluator().loop(ref)))
    }

    /**
     * The boundary traced round [walk] with the *Outline* tool, clicking each piece in turn — and stopping
     * the moment the tool's own follow (OP-14) has closed it, which on this figure it does early.
     */
    private fun traceOutline(
        ed: Editor,
        walk: List<Element>,
    ): Element {
        ed.setTool(Tools.OUTLINE)
        for (piece in walk) {
            ed.click(midOf(piece))
            ed.doc.elements.lastOrNull { it.kind == ElementKind.OUTLINE }?.let { return it }
        }
        ed.click(midOf(walk.first()))
        return assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.OUTLINE }, ed.statusHint)
    }

    /** A point on [el] a click reaches it by: the middle of its own parameter range. */
    private fun midOf(el: Element): Vec2 {
        val pts = samplesOf(el)
        return if (pts.size == 2) (pts[0] + pts[1]) * 0.5 else pts[pts.size / 2]
    }

    // ---- 1. the report's own file ----

    /**
     * **The fixture, verbatim: the join is a drawing curve** — a `BEZIER`, valid, meeting each segment at the
     * end the file's `signs=` named, and leaving each along that segment (G1, true by construction).
     */
    @Test
    fun theFixtureJoinIsADrawingCurveThatMeetsBothSegments() {
        val doc = DocumentFormat.load(fixture)
        val join = named(doc, "e7")
        assertEquals(ElementKind.BEZIER, join.kind, "the join of two drawn curves is a curve of the drawing")
        assertEquals(null, reasonOf(join), "and it is valid")
        assertEquals(Document.PLAN_SPACE, join.space, "drawn in the space both its curves are drawn in")

        val b = bezierOf(join)
        val first = segmentEnds(named(doc, "e3"))
        val second = segmentEnds(named(doc, "e5"))
        assertVec(b.p0, first.first, 1e-9, "it starts at the start of e3")
        assertVec(b.p3, second.first, 1e-9, "and ends at the start of e5")
        // G1: it leaves each end *away* from the curve it leaves, so along that segment's own direction
        assertAlong(b.p1 - b.p0, first.second - first.first, "the join leaves e3 along e3")
        assertAlong(b.p3 - b.p2, second.second - second.first, "and arrives at e5 along e5")

        // …and it is a drawn curve in every sense the drawing tools ask about
        assertTrue(join.isCurve, "a curve slot takes it")
        assertTrue(doc.isLiftable(join), "and a run slot lifts it, as it does every drawing")
    }

    // ---- 2. the thing the report asked for: it bounds an outline ----

    /**
     * **The outline the report could not trace**: the two segments, the fillet arc and the join, closed by
     * clicking round them — and the region it bounds has the area the four pieces really enclose, measured
     * without asking the boundary, and extrudes into a watertight solid.
     */
    @Test
    fun theJoinClosesAnOutlineWhoseAreaIsTheFiguresOwn() {
        val ed = Editor(DocumentFormat.load(fixture))
        val doc = ed.doc
        val e3 = named(doc, "e3")
        val e5 = named(doc, "e5")
        val e6 = named(doc, "e6")
        val e7 = named(doc, "e7")

        val outline = traceOutline(ed, listOf(e3, e6, e5, e7))
        assertEquals(null, reasonOf(outline), "the boundary closes: ${ed.statusHint}")
        @Suppress("UNCHECKED_CAST")
        assertEquals(4, Evaluator().loop(outline.ref as LoopRef).elements.size, "four pieces: the two legs, the round and the join")
        val area = areaOfOutline(outline)
        assertTrue(area > 1000.0, "it is the figure, not a sliver: $area mm²")
        assertClose(area, ringArea(listOf(e3, e6, e5, e7)), 1e-6 * area, "and it is the area the four pieces enclose")

        val solid = assertNotNull(doc.extrudeSolid(outline, doc.newParameter("h", 10.0.mm).ref), doc.note)
        assertManifold((Evaluator().valueOf(solid.ref) as SolidValue).solid.mesh, "the extruded figure")
    }

    // ---- 3. both modes, and the curvature join is three drawing curves ----

    /** The report's drawing with the *Connect* gesture performed live, in [tool]'s mode. */
    private fun connected(tool: String): Editor {
        val ed = Editor(DocumentFormat.load(drawing))
        ed.setTool(tool)
        ed.click(nearStart(named(ed.doc, "e3")))
        ed.click(nearStart(named(ed.doc, "e5")))
        return ed
    }

    private fun joinPieces(ed: Editor): List<Element> = ed.doc.elements.filter { it.kind == ElementKind.BEZIER }

    @Test
    fun theG1GestureBuildsOneDrawingCurveAndSaysSo() {
        val ed = connected(Tools.CONNECT)
        val pieces = joinPieces(ed)
        assertEquals(1, pieces.size, "one cubic: ${ed.statusHint}")
        assertEquals(null, reasonOf(pieces[0]))
        assertTrue(ed.statusHint.contains("drawing curve"), "and the status says what it built: ${ed.statusHint}")
        assertTrue(ed.statusHint.contains("outline"), "…in terms of what can now be done with it: ${ed.statusHint}")
    }

    /**
     * **The curvature join is three cubics, so in the drawing it is three drawing curves** — a chain, and an
     * outline takes all three. Cutting it to one would be cutting the mode, and reading it as a run in space
     * would be the defect this whole item is about.
     */
    @Test
    fun theG2GestureBuildsThreeDrawingCurvesAnOutlineCanTake() {
        val ed = connected(Tools.CONNECT_G2)
        val doc = ed.doc
        val pieces = joinPieces(ed)
        assertEquals(3, pieces.size, "three cubics, three drawn curves: ${ed.statusHint}")
        assertTrue(pieces.all { reasonOf(it) == null }, "all valid: ${pieces.map { reasonOf(it) }}")
        assertTrue(ed.statusHint.contains("3 drawing curves"), "and the status counts them: ${ed.statusHint}")

        val e3 = named(doc, "e3")
        val e5 = named(doc, "e5")
        val e6 = named(doc, "e6")
        assertVec(bezierOf(pieces.first()).p0, segmentEnds(e3).first, 1e-9, "the chain starts on e3")
        assertVec(bezierOf(pieces.last()).p3, segmentEnds(e5).first, 1e-9, "and ends on e5")
        // C0 between the spans, exactly: they are the same control point read twice
        assertVec(bezierOf(pieces[0]).p3, bezierOf(pieces[1]).p0, 0.0, "span 1 to 2")
        assertVec(bezierOf(pieces[1]).p3, bezierOf(pieces[2]).p0, 0.0, "span 2 to 3")

        // the chain runs from e3's end to e5's, so walking the figure meets its spans in reverse
        val walk = listOf(e3, e6, e5) + pieces.reversed()
        val outline = traceOutline(ed, walk)
        assertEquals(null, reasonOf(outline), "the curvature join bounds too: ${ed.statusHint}")
        @Suppress("UNCHECKED_CAST")
        assertEquals(6, Evaluator().loop(outline.ref as LoopRef).elements.size, "six pieces: the two legs, the round and the join's three")
        val area = areaOfOutline(outline)
        assertClose(area, ringArea(walk), 1e-6 * area, "and the area is the figure's own")
    }

    // ---- 4. what is not two drawings of one space is the run in space it always was ----

    /**
     * **A curve in space keeps the reading it always had**, and the status says which pick decided it. The
     * drawn segment is lifted into the join exactly as before, so nothing about the geometry moved.
     */
    @Test
    fun aPickThatIsACurveInSpaceStillGivesARunInSpace() {
        val ed = Editor(DocumentFormat.load(drawing))
        // a curve in space through two points of the plan — the ordinary source (OP-26, step 1)
        ed.setTool(Tools.POINT)
        ed.click(Vec2(60.0, -60.0))
        ed.click(Vec2(120.0, -20.0))
        ed.setTool(Tools.CURVE3)
        ed.click(Vec2(60.0, -60.0))
        ed.click(Vec2(120.0, -20.0))
        ed.key("Enter")
        val run = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.SPACE_CURVE }, ed.statusHint)

        ed.setTool(Tools.CONNECT)
        ed.click(nearStart(named(ed.doc, "e5")))
        ed.click(Vec2(62.0, -58.0))
        val join = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.SPACE_CURVE }, ed.statusHint)
        assertTrue(join !== run, "a join was built: ${ed.statusHint}")
        assertEquals(0, ed.doc.elements.count { it.kind == ElementKind.BEZIER }, "and nothing was drawn")
        assertEquals(null, reasonOf(join), "it is a run: ${ed.statusHint}")
        assertTrue(ed.statusHint.contains("curve in space"), "and the status says why: ${ed.statusHint}")
    }

    /** Two drawings of **different spaces** have no one plane between them, so their join is in space. */
    @Test
    fun twoDrawingsOfDifferentSpacesStillGiveARunInSpace() {
        val ed = Editor(DocumentFormat.load(drawing))
        val here = named(ed.doc, "e3")
        ed.setTool(Tools.SKETCH_PLANE)
        for (c in "40") ed.key(c.toString())
        ed.key("Enter")
        ed.click(midOf(here))
        val datum = ed.activeSpace.name
        assertTrue(datum != Document.PLAN_SPACE, "a second space: ${ed.statusHint}")
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(40.0, 40.0))
        ed.click(Vec2(90.0, 40.0))
        val there = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.SEGMENT }, ed.statusHint)
        assertEquals(datum, there.space)

        ed.setTool(Tools.CONNECT)
        ed.click(nearStart(there))
        assertTrue(ed.setActiveSpace(Document.PLAN_SPACE))
        ed.click(nearStart(named(ed.doc, "e5")))
        val join = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.SPACE_CURVE }, ed.statusHint)
        assertTrue(join !== there)
        assertTrue(ed.statusHint.contains("no one plane between them"), "the status says why: ${ed.statusHint}")
    }

    // ---- 5. what a file does ----

    /** **`save → load → save` is a fixed point on the report's own drawing**, and the join survives it. */
    @Test
    fun theFixtureIsAFixedPointThroughSaveAndLoad() {
        val once = DocumentFormat.save(DocumentFormat.load(fixture))
        assertEquals("constructit ${DocumentFormat.VERSION}", once.lines().first(), "re-saved at the version this build writes")
        assertTrue(once.lines().any { it.startsWith("tool connect ") && it.contains("signs=0;0") }, "the step is unchanged: $once")
        val doc = DocumentFormat.load(once)
        assertEquals(once, DocumentFormat.save(doc), "save → load → save is byte-equal")
        assertEquals(ElementKind.BEZIER, named(doc, "e7").kind, "and the join is still a drawing curve")
        assertTrue(doc.loadNotes.isEmpty(), "a file that already means this says nothing on load: ${doc.loadNotes}")
    }

    /**
     * **A file written by the previous build loads with the join in the same place and a note that says what
     * it is now** (OP-18's versioning doctrine, the fillet's own precedent): nothing moved, and the sentence
     * is a fact about the drawing rather than a warning.
     */
    @Test
    fun anOlderFileLoadsWithTheJoinNowADrawingCurveAndNothingMoved() {
        val old = DocumentFormat.load(fixture)
        assertEquals(1, old.loadNotes.size, "one note: ${old.loadNotes}")
        val note = old.loadNotes.single()
        assertTrue(note.contains("drawing curve"), "it says what the join is now: $note")
        assertTrue(note.contains("same"), "…and that nothing moved: $note")

        // "nothing moved" measured rather than asserted: the join still ends exactly on the two segments
        val join = bezierOf(named(old, "e7"))
        assertVec(join.p0, segmentEnds(named(old, "e3")).first, 0.0, "the join still starts exactly where it did")
        assertVec(join.p3, segmentEnds(named(old, "e5")).first, 0.0, "and ends exactly where it did")
    }

    /**
     * **A curvature join in an older file keeps the one run in space its file names**, because three drawing
     * curves cannot wear one name — and that reading is written down in the step's own `signs=` on the way
     * out, so every later load reads it from the file rather than from a version it no longer declares.
     */
    @Test
    fun anOlderCurvatureJoinStaysTheRunInSpaceItsFileNames() {
        val oldText = fixture.replace("tool connect ", "tool connectg2 ")
        val doc = DocumentFormat.load(oldText)
        val join = named(doc, "e7")
        assertEquals(ElementKind.SPACE_CURVE, join.kind, "one name, one element: ${doc.loadNotes}")
        assertEquals(0, doc.elements.count { it.kind == ElementKind.BEZIER }, "nothing was drawn")
        assertTrue(doc.loadNotes.single().contains("curve in space"), "the load says so: ${doc.loadNotes}")

        val once = DocumentFormat.save(doc)
        assertTrue(
            once.lines().any { it.startsWith("tool connectg2 ") && it.contains("signs=0;0;1") },
            "and the reading is written down beside the two ends: $once",
        )
        val back = DocumentFormat.load(once)
        assertEquals(once, DocumentFormat.save(back), "save → load → save is byte-equal")
        assertEquals(ElementKind.SPACE_CURVE, named(back, "e7").kind, "and it is still the run its file names")
        assertTrue(back.loadNotes.isEmpty(), "with nothing more to say about it: ${back.loadNotes}")
    }

    /**
     * **What was built on a join goes on working.** A route along the bend is what step 7 exists for, and a
     * `PATH3` slot lifts a drawing ([Document.isLiftable]) exactly as it takes a run — so a tube along the
     * join is the same solid it was, and a file that has one is a fixed point through save and load.
     */
    @Test
    fun aTubeAlongTheJoinIsTheSolidItAlwaysWas() {
        val doc = DocumentFormat.load(fixture)
        val tube = assertNotNull(doc.tubeAlongCurve(named(doc, "e7"), doc.newParameter("r", 2.0.mm).ref), doc.note)
        assertEquals(null, reasonOf(tube), "a tube runs along a drawn join: ${doc.note}")
        assertManifold((Evaluator().valueOf(tube.ref) as SolidValue).solid.mesh, "a tube along the join")

        val once = DocumentFormat.save(doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "and the file with it is a fixed point")
    }

    // ---- 6. one gesture, one undo ----

    /** **One undo takes the join back and nothing else** — including the curvature join's three pieces. */
    @Test
    fun oneUndoTakesTheWholeJoinBack() {
        for (tool in listOf(Tools.CONNECT, Tools.CONNECT_G2)) {
            val before = Editor(DocumentFormat.load(drawing)).doc.elements.size
            val ed = connected(tool)
            assertTrue(ed.doc.elements.size > before, "$tool built something: ${ed.statusHint}")
            assertTrue(ed.undo(), "and it can be undone")
            assertEquals(before, ed.doc.elements.size, "$tool: exactly the join went")
            assertEquals(0, ed.doc.elements.count { it.kind == ElementKind.BEZIER }, "$tool: no piece left behind")
        }
    }
}
