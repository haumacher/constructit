package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.PointValue
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.Tools
import constructit.geom.Vec2
import constructit.units.deg
import constructit.units.mm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **Relative points — OP-4 case (b), on demand.**
 *
 * OP-4 listed re-parameterizing a free source as `P2 = P1 + PolarVector(d, θ)` and deferred it until something
 * asked for it. This is the demand, reported on the drawing below: a circle whose centre rides a segment and
 * whose rim passes through a free point. Dragging the segment moved the centre and *changed the radius*,
 * because the free rim point stayed where it was. What the user meant was that the rim point belongs to the
 * centre — and saying so is a **conversion, not a constraint**: the point's literal position gives way to
 * `polarPoint(anchor, d, θ)` with both scalars read off the geometry it already has, so nothing moves at the
 * moment of the change, two degrees of freedom stay two, and the radius now follows the centre.
 *
 * The substrate is the one welding uses (`SourceNode.boundTo`, OP-5): the binding is a mutation *in place*, so
 * everything already referring to the point — the circle, here — follows it without a single input list being
 * rewired. And it is invertible: *Make absolute* hands the point its own coordinates back where it now stands.
 */
class RelativePointTest {
    /** The reported drawing, verbatim: centre `e4` rides segment `e3`, and circle `e6` passes through `e5`. */
    private val fixture =
        """
constructit 1
point 26.5,62 -> e1
point -82.75,30.25 -> e2
tool segment pts=e1,e2 clicks=37.25,65.75;-85.25,29 -> e3
pointoncurve e3 -32.15366972477062,44.92889908256881 -> e4
point -23.5,22.5 -> e5
tool circle pts=e4,e5 clicks=-32.25,45.25;-23.5,22.5 -> e6
point -78.25,-19 -> e7
point 58.75,-18 -> e8
tool segment pts=e7,e8 clicks=-89,-16;89.25,-14.25 -> e9
pointoncurve e9 -38.25979951535941,-15.501849364105913 -> e10
pointoncurve e9 19.511630685774307,-14.934668422439803 -> e11
tool segment pts=e10,e11 clicks=-38.25,-16.5;19.5,-13.75 -> e12
""".trimStart()

    /** The element the script calls `e[n]`. */
    private fun el(
        ed: Editor,
        n: Int,
    ): Element = ed.doc.elements[n - 1]

    private fun pos(el: Element): Vec2 = ((Evaluator().eval(el.ref.node) as EvalResult.Ok).value as PointValue).p

    /** The circle's radius as the user reads it: how far the rim point is from the centre. */
    private fun radius(ed: Editor): Double = (pos(el(ed, 5)) - pos(el(ed, 4))).length()

    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerMove(s)
        pointerDown(s)
        pointerUp(s)
    }

    private fun Editor.drag(
        from: Vec2,
        to: Vec2,
        steps: Int = 1,
    ) {
        setTool(Tools.SELECT)
        pointerDown(camera.worldToScreen(from))
        for (i in 1..steps) pointerMove(camera.worldToScreen(from + (to - from) * (i.toDouble() / steps)))
        pointerUp(camera.worldToScreen(to))
    }

    /** Make `e5` relative to `e4` with the tool, as a user would. */
    private fun makeRelative(ed: Editor) {
        ed.setTool(Tools.MAKE_RELATIVE)
        ed.click(pos(el(ed, 5)))
        ed.click(pos(el(ed, 4)))
    }

    /**
     * The report, both halves. Dragging the host of the centre changes the radius while the rim point is free;
     * once the rim point is made relative to the centre, the very same drag carries it and the radius holds.
     */
    @Test
    fun makingTheRimPointRelativeToTheCentreKeepsTheRadiusUnderAHostDrag() {
        val loose = Editor()
        loose.replaceDocument(DocumentFormat.load(fixture))
        val was = radius(loose)
        loose.drag(Vec2(26.5, 62.0), Vec2(50.0, 85.0), steps = 8)
        assertTrue(kotlin.math.abs(radius(loose) - was) > 1.0, "the reported behaviour: the radius changed")

        val ed = Editor()
        ed.replaceDocument(DocumentFormat.load(fixture))
        makeRelative(ed)
        assertNotNull(ed.doc.relativeOf(el(ed, 5)), "e5 now follows e4")
        assertEquals(was, radius(ed), "and making it relative moves nothing: same radius, to the bit")
        assertTrue(ed.statusHint.contains("follows"), ed.statusHint)

        val centreWas = pos(el(ed, 4))
        ed.drag(Vec2(26.5, 62.0), Vec2(50.0, 85.0), steps = 8)
        assertTrue((pos(el(ed, 4)) - centreWas).length() > 0.5, "the centre still rides its segment")
        assertClose(radius(ed), was, 1e-9, "…and the circle keeps its radius while it goes")
        // the rim point kept its bearing too, which is what "follows" means
        val offset = pos(el(ed, 5)) - pos(el(ed, 4))
        assertClose(offset.angle(), (Vec2(-23.5, 22.5) - centreWas).angle(), 1e-9)
    }

    /** Dragging the relative point itself still adjusts the offset — it did not become read-only. */
    @Test
    fun draggingARelativePointWritesItsDistanceAndAngle() {
        val ed = Editor()
        ed.replaceDocument(DocumentFormat.load(fixture))
        makeRelative(ed)
        val rim = el(ed, 5)
        assertTrue(rim.draggable, "two degrees of freedom before, two after")
        assertEquals(listOf("distance", "angle"), rim.handle!!.fields().map { it.label })

        ed.drag(pos(rim), Vec2(0.0, 30.0), steps = 4)
        assertClose(pos(rim).x, 0.0, 1e-9, "the point goes where it is dragged")
        assertClose(pos(rim).y, 30.0, 1e-9)
        assertClose(radius(ed), (Vec2(0.0, 30.0) - pos(el(ed, 4))).length(), 1e-9, "so the radius follows the drag")
    }

    /** …and typing reaches exactly as far (OP-13): the distance field *is* the radius, as a number. */
    @Test
    fun typingTheDistanceSetsTheRadiusExactly() {
        val ed = Editor()
        ed.replaceDocument(DocumentFormat.load(fixture))
        makeRelative(ed)
        ed.setTool(Tools.SELECT)
        ed.click(pos(el(ed, 5)))
        assertEquals(listOf("distance", "angle"), ed.selectionFields().map { it.label })
        assertTrue(ed.writeSelectionField(0, 40.0))
        assertClose(radius(ed), 40.0, 1e-9, "a typed radius, at last")

        assertTrue(ed.writeSelectionField(1, 90.0))
        val offset = pos(el(ed, 5)) - pos(el(ed, 4))
        assertClose(offset.x, 0.0, 1e-9, "a typed angle puts it straight above the centre")
        assertClose(offset.y, 40.0, 1e-9)
        assertEquals(40.0.mm, ed.selectionFields()[0].read(Evaluator()))
        assertEquals(90.0.deg.base, ed.selectionFields()[1].read(Evaluator())!!.base)
    }

    /** *Make absolute* is the inverse: the point stays where it is and owns its coordinates again. */
    @Test
    fun makeAbsoluteRestoresAFreePointWhereItStands() {
        val ed = Editor()
        ed.replaceDocument(DocumentFormat.load(fixture))
        makeRelative(ed)
        ed.drag(Vec2(26.5, 62.0), Vec2(50.0, 85.0), steps = 4)
        val rim = el(ed, 5)
        val where = pos(rim)
        val was = radius(ed)

        ed.setTool(Tools.MAKE_ABSOLUTE)
        ed.click(where)
        assertNull(ed.doc.relativeOf(rim), "no longer relative")
        assertEquals(where, pos(rim), "and it did not move an inch in the process")
        assertEquals(listOf("x", "y"), rim.handle!!.fields().map { it.label }, "its own coordinates again")
        assertFalse(ed.doc.isWelded(rim))

        // freedom restored means the old behaviour is back: the host drag changes the radius again
        ed.drag(Vec2(50.0, 85.0), Vec2(70.0, 100.0), steps = 4)
        assertTrue(kotlin.math.abs(radius(ed) - was) > 0.5, "a free rim point lets the radius change")
    }

    /** A relative point is not a welded one: it stays visible, draggable, and is not hidden as an alias. */
    @Test
    fun aRelativePointIsNotAWeld() {
        val ed = Editor()
        ed.replaceDocument(DocumentFormat.load(fixture))
        makeRelative(ed)
        val rim = el(ed, 5)
        assertTrue(rim.visible)
        assertFalse(ed.doc.isWelded(rim), "a weld pins both coordinates; this re-parameterizes them")
        assertTrue(rim.hasFreeDof)
    }

    /**
     * Cycles are refused, by the predicate every other connection uses (OP-4's acyclicity): anchoring a point
     * to something that already follows *it* would put the point inside its own input cone.
     */
    @Test
    fun anchoringAPointToItsOwnDependentIsRefused() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(30.0, 10.0))
        val a = ed.doc.elements[0]
        val b = ed.doc.elements[1]
        assertTrue(ed.doc.makeRelative(b, a), "b follows a")

        val steps = ed.doc.journal.size
        assertFalse(ed.doc.makeRelative(a, b), "…so a cannot follow b")
        assertTrue(ed.doc.note!!.contains("already follows"), ed.doc.note!!)
        assertEquals(steps, ed.doc.journal.size, "and a refusal records nothing")
        assertNull(ed.doc.relativeOf(a))
        // …nor can a point that already follows something be re-anchored without freeing it first
        assertFalse(ed.doc.makeRelative(b, a))
    }

    /** The construction survives the file: `save -> load -> save` byte-equal, geometry intact (OP-18). */
    @Test
    fun aRelativePointRoundTrips() {
        val ed = Editor()
        ed.replaceDocument(DocumentFormat.load(fixture))
        makeRelative(ed)
        ed.drag(pos(el(ed, 5)), Vec2(-10.0, 30.0), steps = 3) // the offset is state
        val once = DocumentFormat.save(ed.doc)
        assertTrue(once.contains("tool makerel"), once)
        assertTrue(once.contains("dofs="), once)
        val reloaded = DocumentFormat.load(once)
        assertEquals(once, DocumentFormat.save(reloaded), "save -> load -> save must be identical")

        val fresh = Editor()
        fresh.replaceDocument(reloaded)
        assertEquals(pos(el(ed, 5)), pos(el(fresh, 5)), "the offset came back exactly")
        assertEquals(radius(ed), radius(fresh))
        assertNotNull(fresh.doc.relativeOf(el(fresh, 5)), "…as a relative point, not a free one")
        // and the reloaded drawing behaves the same way
        fresh.drag(Vec2(26.5, 62.0), Vec2(50.0, 85.0), steps = 4)
        assertClose(radius(fresh), radius(ed), 1e-9)
    }

    /** The same when the re-parameterization is recorded on its own (`relative`) rather than through a tool. */
    @Test
    fun aRelativeStepRecordedDirectlyRoundTripsToo() {
        val ed = Editor()
        ed.replaceDocument(DocumentFormat.load(fixture))
        assertTrue(ed.doc.makeRelative(el(ed, 5), el(ed, 4)))
        ed.drag(pos(el(ed, 5)), Vec2(-40.0, 60.0), steps = 3)
        val once = DocumentFormat.save(ed.doc)
        assertTrue(once.lines().any { it.startsWith("relative ") && it.contains("dofs=") }, once)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)))
        val fresh = Editor()
        fresh.replaceDocument(DocumentFormat.load(once))
        assertEquals(pos(el(ed, 5)), pos(el(fresh, 5)))
    }

    /** Making a point relative is one undoable operation, and undo gives the free point back. */
    @Test
    fun makingAPointRelativeIsOneUndoStep() {
        val ed = Editor()
        ed.replaceDocument(DocumentFormat.load(fixture))
        val before = DocumentFormat.save(ed.doc)
        makeRelative(ed)
        assertTrue(ed.undo())
        assertEquals(before, DocumentFormat.save(ed.doc))
        assertNull(ed.doc.relativeOf(el(ed, 5)), "the point is free again")
        assertTrue(ed.redo())
        assertNotNull(ed.doc.relativeOf(el(ed, 5)))
    }
}
