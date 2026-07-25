package constructit

import constructit.core.CircleValue
import constructit.core.Evaluator
import constructit.core.SegmentValue
import constructit.dsl.valueOf
import constructit.editor.Document
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.Vec2
import constructit.units.mm
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Arrays — the interactive generalization of the boltCircle / holePattern macros (OP-6).
 *
 * A copy is an ordinary transform node over the original, so the interesting assertions are not the
 * positions but the consequences: the copies follow the original *live*, they work for any element kind
 * with no per-kind case, deleting the original takes them (they are its dependents, OP-18), and the
 * count is structural — recorded with the step and replayed verbatim.
 */
class ArrayTest {
    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerDown(s)
        pointerUp(s)
    }

    private fun Editor.drag(
        from: Vec2,
        to: Vec2,
    ) {
        setTool(Tools.SELECT)
        pointerDown(camera.worldToScreen(from))
        pointerMove(camera.worldToScreen(to))
        pointerUp(camera.worldToScreen(to))
    }

    private fun Editor.selectAt(world: Vec2) {
        setTool(Tools.SELECT)
        click(world)
    }

    private fun circles(doc: Document): List<constructit.geom.Circle> {
        val ev = Evaluator()
        return doc.elements.filter { it.kind == ElementKind.CIRCLE }.mapNotNull { (ev.valueOf(it.ref) as? CircleValue)?.circle }
    }

    private fun segments(doc: Document): List<constructit.geom.Segment> {
        val ev = Evaluator()
        return doc.elements.filter { it.kind == ElementKind.SEGMENT }.mapNotNull { (ev.valueOf(it.ref) as? SegmentValue)?.seg }
    }

    /** A circle at the origin of radius 10, plus the parameter that drives it. */
    private fun circleDoc(count: Int): Editor {
        val ed = Editor()
        ed.count = count
        ed.activeScalar = ed.doc.newParameter("r", 10.0.mm)
        ed.setTool(Tools.CIRCLE_R)
        ed.click(Vec2(0.0, 0.0))
        return ed
    }

    private fun assertRoundTrips(ed: Editor): Document {
        val once = DocumentFormat.save(ed.doc)
        val reloaded = DocumentFormat.load(once)
        assertEquals(once, DocumentFormat.save(reloaded), "save -> load -> save must be identical")
        assertEquals(ed.doc.elements.map { it.kind }, reloaded.elements.map { it.kind }, "same element kinds")
        return reloaded
    }

    // ---- linear ----

    @Test
    fun aLinearArrayPlacesEveryCopyAtAWholeMultipleOfTheStep() {
        val ed = circleDoc(count = 4)
        ed.setTool(Tools.ARRAY_LINEAR)
        ed.click(Vec2(10.0, 0.0)) // the circle itself
        ed.click(Vec2(-60.0, -60.0)) // step vector: from
        ed.click(Vec2(-60.0, -35.0)) // to — 25 mm up

        val centres = circles(ed.doc).map { it.center }.sortedBy { it.y }
        assertEquals(4, centres.size, "the original plus three copies")
        centres.forEachIndexed { i, c ->
            assertClose(c.x, 0.0)
            assertClose(c.y, 25.0 * i)
        }
        for (c in circles(ed.doc)) assertClose(c.radius, 10.0)
    }

    @Test
    fun aLinearArrayCopiesFollowTheOriginalAndTheStepVectorLive() {
        val ed = circleDoc(count = 3)
        ed.setTool(Tools.ARRAY_LINEAR)
        ed.click(Vec2(10.0, 0.0))
        ed.click(Vec2(-60.0, -60.0))
        ed.click(Vec2(-30.0, -60.0)) // 30 mm along +x

        // the original's own inputs: its centre point and its radius parameter
        ed.drag(Vec2(0.0, 0.0), Vec2(0.0, 40.0))
        ed.doc.setParameter(ed.doc.scalars.first { it.name == "r" }, 4.0.mm)
        val centres = circles(ed.doc).map { it.center }.sortedBy { it.x }
        centres.forEachIndexed { i, c ->
            assertClose(c.x, 30.0 * i)
            assertClose(c.y, 40.0, msg = "every copy follows the original's centre")
        }
        for (c in circles(ed.doc)) assertClose(c.radius, 4.0, msg = "and its radius")

        // moving one end of the step vector re-spaces the whole array
        ed.drag(Vec2(-30.0, -60.0), Vec2(-50.0, -60.0)) // now 10 mm
        val respaced = circles(ed.doc).map { it.center.x }.sorted()
        respaced.forEachIndexed { i, x -> assertClose(x, 10.0 * i) }
    }

    @Test
    fun anArrayOfASegmentWorksTheSameWay() {
        val ed = Editor()
        ed.count = 3
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(0.0, 20.0))
        ed.setTool(Tools.ARRAY_LINEAR)
        ed.click(Vec2(0.0, 10.0)) // the segment, between its endpoints
        ed.click(Vec2(-60.0, -60.0))
        ed.click(Vec2(-45.0, -60.0)) // 15 mm along +x

        val segs = segments(ed.doc).sortedBy { it.a.x }
        assertEquals(3, segs.size)
        segs.forEachIndexed { i, s ->
            assertClose(s.a.x, 15.0 * i)
            assertClose(s.b.x, 15.0 * i)
            assertClose((s.b - s.a).length(), 20.0)
        }
        assertRoundTrips(ed)
    }

    // ---- circular ----

    @Test
    fun aCircularArraySpacesItsCopiesEvenlyRoundTheCentre() {
        val ed = circleDoc(count = 6)
        // move the circle off the centre of rotation first, so the copies are distinguishable
        ed.drag(Vec2(0.0, 0.0), Vec2(40.0, 0.0))
        ed.setTool(Tools.ARRAY_CIRCULAR)
        ed.click(Vec2(50.0, 0.0)) // on the circle (centre 40, r 10)
        ed.click(Vec2(0.0, 0.0)) // the centre of rotation

        val centres = circles(ed.doc).map { it.center }
        assertEquals(6, centres.size)
        for (k in 0 until 6) {
            val a = 2 * PI * k / 6
            val want = Vec2(40.0 * cos(a), 40.0 * sin(a))
            assertTrue(centres.any { (it - want).length() < 1e-6 }, "no copy at $want; got $centres")
        }
        // a bolt circle: every hole the same size, every hole the same distance out
        for (c in circles(ed.doc)) {
            assertClose(c.radius, 10.0)
            assertClose(c.center.length(), 40.0)
        }
        assertRoundTrips(ed)
    }

    @Test
    fun aCircularArrayOfASegmentFollowsTheOriginal() {
        val ed = Editor()
        ed.count = 4
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(30.0, 0.0))
        ed.click(Vec2(50.0, 0.0))
        ed.setTool(Tools.ARRAY_CIRCULAR)
        ed.click(Vec2(40.0, 0.0)) // the segment
        ed.click(Vec2(0.0, 0.0)) // about the origin: quarter turns

        val segs = segments(ed.doc)
        assertEquals(4, segs.size)
        // the quarter-turn copy of (30,0)->(50,0) is (0,30)->(0,50)
        assertTrue(segs.any { (it.a - Vec2(0.0, 30.0)).length() < 1e-6 && (it.b - Vec2(0.0, 50.0)).length() < 1e-6 }, "got $segs")

        // dragging the original's far endpoint lengthens every copy
        ed.drag(Vec2(50.0, 0.0), Vec2(70.0, 0.0))
        for (s in segments(ed.doc)) assertClose((s.b - s.a).length(), 40.0)
    }

    // ---- structure: dependents, count, persistence ----

    @Test
    fun deletingTheOriginalTakesItsCopies() {
        val ed = circleDoc(count = 4)
        ed.setTool(Tools.ARRAY_LINEAR)
        ed.click(Vec2(10.0, 0.0))
        ed.click(Vec2(-60.0, -60.0))
        ed.click(Vec2(-30.0, -60.0))
        assertEquals(4, circles(ed.doc).size)

        ed.selectAt(Vec2(10.0, 0.0)) // the original circle
        assertEquals(ElementKind.CIRCLE, ed.selection?.kind)
        assertTrue(ed.deleteSelection())
        assertEquals(0, circles(ed.doc).size, "the copies are dependents of the original")
        assertTrue(ed.statusHint.contains("dependent"), "and the status line says so; got: ${ed.statusHint}")
        assertRoundTrips(ed)
    }

    @Test
    fun deletingOneCopyLeavesTheRest() {
        val ed = circleDoc(count = 4)
        ed.setTool(Tools.ARRAY_LINEAR)
        ed.click(Vec2(10.0, 0.0))
        ed.click(Vec2(-60.0, -60.0))
        ed.click(Vec2(-60.0, -30.0)) // 30 mm up

        // NOTE: an array is one step, so deleting *any* of its elements drops the whole array — the copies
        // are siblings of one another, not a chain, but they were created together. The original stays.
        ed.selectAt(Vec2(10.0, 30.0)) // the first copy
        assertTrue(ed.deleteSelection())
        assertEquals(1, circles(ed.doc).size, "only the original survives the array's step going")
        assertClose(circles(ed.doc).single().center.y, 0.0)
        assertRoundTrips(ed)
    }

    @Test
    fun theCountIsStructuralAndTravelsWithTheStep() {
        val ed = circleDoc(count = 5)
        ed.drag(Vec2(0.0, 0.0), Vec2(25.0, 0.0))
        ed.setTool(Tools.ARRAY_CIRCULAR)
        ed.click(Vec2(35.0, 0.0))
        ed.click(Vec2(0.0, 0.0))
        val text = DocumentFormat.save(ed.doc)
        assertTrue(text.contains("count=5"), "got:\n$text")

        val fresh = Editor()
        fresh.count = 3 // the editor's current count must not leak into a replay
        fresh.replaceDocument(DocumentFormat.load(text))
        assertEquals(5, circles(fresh.doc).size)
        assertEquals(text, DocumentFormat.save(fresh.doc))
    }
}
