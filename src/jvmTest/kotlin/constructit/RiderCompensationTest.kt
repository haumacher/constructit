package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.PointValue
import constructit.core.ScalarValue
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.Tools
import constructit.geom.Vec2
import constructit.units.mm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **A gesture compensates the riders of the host it turns (OP-20).**
 *
 * OP-20 chose a distance *along the carrier* for a rider on a host with no world coordinate to offer, and
 * recorded one honest limit: turning the host still moves the rider, because the stored distance's meaning
 * turns with the carrier. Reported on the drawing below — dragging the lower segment's right endpoint 90°
 * down made the inner segment, which rides that host at both ends, slide dramatically along it.
 *
 * The limit is a property of the *parameter*, and the answer is therefore not a different parameter but the
 * **edit**: while a drag turns a host, every rider whose parameter would change meaning is re-solved so it
 * sits at the projection of the world position it had **at grab time** onto the host's current geometry.
 *
 * Grab-time rather than incremental, which is what buys all three of:
 * - a stretch that does not turn the host writes nothing at all (projection is the identity), so the
 *   absolute-anchoring behaviour OP-20 built is untouched;
 * - a rotation moves each rider continuously, by the least the host allows;
 * - dragging back to where the gesture started restores every rider exactly.
 */
class RiderCompensationTest {
    /** The reported drawing, verbatim. Two riders (`e10`, `e11`) on the diagonal host `e9`, joined by `e12`. */
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

    /** The element the script calls `e[n]`: steps declare their creations in the order they are made. */
    private fun el(
        ed: Editor,
        n: Int,
    ): Element = ed.doc.elements[n - 1]

    private fun pos(el: Element): Vec2 = ((Evaluator().eval(el.ref.node) as EvalResult.Ok).value as PointValue).p

    /** The literal a rider's own parameter holds — what "no churn" is about. */
    private fun dof(el: Element): Double =
        ((Evaluator().eval(el.handle!!.dragNodes.single()) as EvalResult.Ok).value as ScalarValue).q.base

    private fun Editor.drag(
        from: Vec2,
        to: Vec2,
        steps: Int = 1,
        each: () -> Unit = {},
    ) {
        setTool(Tools.SELECT)
        pointerDown(camera.worldToScreen(from))
        for (i in 1..steps) {
            pointerMove(camera.worldToScreen(from + (to - from) * (i.toDouble() / steps)))
            each()
        }
        pointerUp(camera.worldToScreen(to))
    }

    private fun projectOnto(
        p: Vec2,
        a: Vec2,
        b: Vec2,
    ): Vec2 {
        val dir = (b - a).normalized()
        return a + dir * (p - a).dot(dir)
    }

    /** Whether [p] lies between [a] and [b] along their line — "still on the host it rides". */
    private fun withinExtent(
        p: Vec2,
        a: Vec2,
        b: Vec2,
    ): Boolean {
        val d = b - a
        val t = (p - a).dot(d) / d.dot(d)
        return t >= -1e-9 && t <= 1.0 + 1e-9
    }

    /**
     * The report: rotate the host by dragging its right endpoint 90° down. Both riders travel continuously,
     * stay on the piece of host they were riding, and end at the projection of where they stood — which is
     * the *nearest* place on the turned host, so no parameter could have kept them closer to home.
     */
    @Test
    fun turningAHostCarriesItsRidersToWhereTheyWereInsteadOfSlidingThemAlongIt() {
        val ed = Editor()
        ed.replaceDocument(DocumentFormat.load(fixture))
        val a = el(ed, 7)
        val b = el(ed, 8)
        val r1 = el(ed, 10)
        val r2 = el(ed, 11)
        val inner = el(ed, 12)

        val was1 = pos(r1)
        val was2 = pos(r2)
        val t1 = dof(r1)
        val t2 = dof(r2)
        var last1 = was1
        var last2 = was2
        var worst = 0.0
        ed.drag(pos(b), Vec2(58.75, -150.0), steps = 30) {
            worst = maxOf(worst, (pos(r1) - last1).length(), (pos(r2) - last2).length())
            last1 = pos(r1)
            last2 = pos(r2)
            assertTrue(withinExtent(pos(r1), pos(a), pos(b)), "${pos(r1)} left the host ${pos(a)}..${pos(b)}")
            assertTrue(withinExtent(pos(r2), pos(a), pos(b)), "${pos(r2)} left the host ${pos(a)}..${pos(b)}")
        }
        // 132 mm of endpoint travel in 30 steps: a rider that moves calmly moves a few mm per step, while
        // the reported jump was tens of mm at once
        assertTrue(worst < 5.0, "a rider moved $worst mm in one step of the gesture")

        val ends = pos(a) to pos(b)
        for ((rider, before) in listOf(r1 to was1, r2 to was2)) {
            val want = projectOnto(before, ends.first, ends.second)
            assertClose(pos(rider).x, want.x, 1e-9, "the rider ends where it stood, projected onto the turned host")
            assertClose(pos(rider).y, want.y, 1e-9)
        }
        // the projection is the *nearest* point of the turned host, so the un-compensated answer — the same
        // distance along the carrier, re-read through the new direction — can only be further away
        val dir = (ends.second - ends.first).normalized()
        val anchor = ends.first - dir * ends.first.dot(dir)
        for ((rider, before) in listOf(r1 to (was1 to t1), r2 to (was2 to t2))) {
            val slid = anchor + dir * before.second
            assertTrue(
                (pos(rider) - before.first).length() <= (slid - before.first).length() + 1e-9,
                "compensating must not move a rider further than leaving its parameter alone would",
            )
        }
        // and the segment between them is still a segment between two points *on* the host
        assertTrue(withinExtent(pos(r1), pos(a), pos(b)))
        assertEquals(2, inner.ref.node.inputs.size)
    }

    /** Dragging back to where the gesture started restores every rider exactly — the reference is the grab. */
    @Test
    fun draggingBackToTheStartRestoresEveryRiderExactly() {
        val ed = Editor()
        ed.replaceDocument(DocumentFormat.load(fixture))
        val r1 = el(ed, 10)
        val r2 = el(ed, 11)
        val was1 = pos(r1)
        val was2 = pos(r2)
        val t1 = dof(r1)
        val t2 = dof(r2)

        val from = pos(el(ed, 8))
        ed.setTool(Tools.SELECT)
        ed.pointerDown(ed.camera.worldToScreen(from))
        for (i in 1..10) ed.pointerMove(ed.camera.worldToScreen(from + Vec2(0.0, -13.2 * i)))
        assertTrue((pos(r1) - was1).length() > 1.0, "the riders did move on the way out")
        for (i in 9 downTo 0) ed.pointerMove(ed.camera.worldToScreen(from + Vec2(0.0, -13.2 * i)))
        ed.pointerUp(ed.camera.worldToScreen(from))

        assertClose(pos(r1).x, was1.x, 1e-6)
        assertClose(pos(r1).y, was1.y, 1e-6)
        assertClose(pos(r2).x, was2.x, 1e-6)
        assertClose(pos(r2).y, was2.y, 1e-6)
        // and *exactly*: an unturned host restores the grab-time literal itself rather than recomputing it
        assertEquals(t1, dof(r1), "the rider's parameter came back bit for bit")
        assertEquals(t2, dof(r2))
    }

    /**
     * The other half of the rule: an edit that does **not** turn the host writes nothing at all. Projection
     * is the identity there, so compensation must not churn a literal — the file stays byte-identical, and
     * the absolute-anchoring semantics OP-20 built are reached by exactly the code they always were.
     */
    @Test
    fun stretchingAHostWithoutTurningItLeavesEveryRiderLiteralAlone() {
        val ed = Editor()
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(0.0, 20.0))
        ed.click(Vec2(120.0, 20.0))
        ed.setTool(Tools.POINT_ON_LINE)
        ed.click(Vec2(40.0, 20.0))
        val rider = ed.doc.elements.last()
        val t = dof(rider)
        val where = pos(rider)
        val before = DocumentFormat.save(ed.doc)

        ed.drag(Vec2(120.0, 20.0), Vec2(200.0, 20.0)) // stretch along the host's own direction
        assertEquals(t, dof(rider), "a stretch that does not turn the host must not touch the parameter")
        assertEquals(where, pos(rider))
        assertEquals(
            before.lines().first { it.startsWith("tool ptonline") },
            DocumentFormat.save(ed.doc).lines().first { it.startsWith("tool ptonline") },
            "…so the rider's line of the file is byte-identical",
        )
    }

    /**
     * Where OP-20 already stores an **absolute** quantity there is nothing to compensate, and the cheap check
     * for that is structural: such a rider is never registered, so no gesture even looks at it. A rider on an
     * ortho leg — a host axis-aligned *by construction* — holds a world coordinate, and moving the leg
     * perpendicular carries it without touching its literal, exactly as it always did.
     */
    @Test
    fun aRiderAnchoredToTheWorldIsNeverCompensatedAtAll() {
        val ed = Editor()
        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 2.0)) // one horizontal leg
        ed.finishPath()
        ed.setTool(Tools.POINT_ON_LINE)
        ed.click(Vec2(30.0, 0.0))
        val rider = ed.doc.elements.last()
        assertEquals("x", rider.handle!!.fields().single().label, "its DOF is a world coordinate (OP-20)")
        assertTrue(ed.doc.riderAnchors().isEmpty(), "so it is not carrier-anchored and nothing compensates it")

        val t = dof(rider)
        ed.drag(Vec2(80.0, 0.0), Vec2(80.0, -25.0)) // the leg itself, perpendicular
        assertEquals(t, dof(rider), "the leg's own move is ridden, not compensated")
        assertClose(pos(rider).x, 30.0, 1e-9)
        assertClose(pos(rider).y, -25.0, 1e-9)
    }

    /**
     * A **chain**: a rider on a host that is itself built from a rider. Compensating inner-first would project
     * the inner rider onto geometry that is still about to move, so the order is outer to inner — asserted by
     * the inner rider landing on the projection computed from the *final* geometry of its host.
     */
    @Test
    fun aChainOfRidersIsCompensatedOuterToInner() {
        val ed = Editor()
        // the outer host, diagonal so its rider is carrier-anchored
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(120.0, 12.0))
        ed.setTool(Tools.POINT_ON_LINE)
        ed.click(Vec2(30.0, 3.0))
        val outer = ed.doc.elements.last()
        // a second host through that rider…
        ed.setTool(Tools.POINT)
        ed.click(Vec2(10.0, 90.0))
        val far = ed.doc.elements.last()
        ed.setTool(Tools.SEGMENT)
        ed.click(pos(outer))
        ed.click(pos(far))
        val host2 = ed.doc.elements.last()
        // …and a rider on *that*
        ed.setTool(Tools.POINT_ON_LINE)
        ed.click(Vec2(20.0, 45.0))
        val inner = ed.doc.elements.last()
        assertTrue(ed.doc.elements.count { it.kind == constructit.editor.ElementKind.ON_CURVE } == 2)

        val wasOuter = pos(outer)
        val wasInner = pos(inner)
        ed.drag(Vec2(120.0, 12.0), Vec2(120.0, -60.0), steps = 20)

        // the outer rider: projected onto the turned first host
        val turned = projectOnto(wasOuter, Vec2(0.0, 0.0), Vec2(120.0, -60.0))
        assertClose(pos(outer).x, turned.x, 1e-9, "the outer rider rides its own host's turn")
        assertClose(pos(outer).y, turned.y, 1e-9)
        // the inner one: projected onto the second host **as it now stands** — which needs the outer rider to
        // have been compensated first, since that rider is one of the two points the second host is built from
        val wantInner = projectOnto(wasInner, pos(outer), pos(far))
        assertClose(pos(inner).x, wantInner.x, 1e-9, "the inner rider follows its host's new geometry")
        assertClose(pos(inner).y, wantInner.y, 1e-9)
        assertTrue((pos(inner) - wasInner).length() > 0.5, "and it did have to move")
        assertTrue(host2.isCurve)
    }

    /**
     * Typing reaches exactly as far as dragging (OP-13), so the same compensation applies to a typed field:
     * setting the host endpoint's y turns the host, and the riders must not be catapulted by it either.
     */
    @Test
    fun aTypedFieldThatTurnsAHostCompensatesTheSameWay() {
        val byDrag = Editor()
        byDrag.replaceDocument(DocumentFormat.load(fixture))
        byDrag.drag(pos(el(byDrag, 8)), Vec2(58.75, -150.0), steps = 20)

        val byField = Editor()
        byField.replaceDocument(DocumentFormat.load(fixture))
        byField.setTool(Tools.SELECT)
        byField.click(Vec2(58.75, -18.0)) // select the host's endpoint
        assertEquals("y", byField.selectionFields()[1].label)
        assertTrue(byField.writeSelectionField(1, -150.0))

        for (n in listOf(10, 11)) {
            assertClose(pos(el(byField, n)).x, pos(el(byDrag, n)).x, 1e-9, "typed and dragged must agree")
            assertClose(pos(el(byField, n)).y, pos(el(byDrag, n)).y, 1e-9)
        }
    }

    /** The whole drag — the host's move and every rider it compensated — is one undo step, as today. */
    @Test
    fun aCompensatedDragIsOneUndoStep() {
        val ed = Editor()
        ed.replaceDocument(DocumentFormat.load(fixture))
        val r1 = el(ed, 10)
        val r2 = el(ed, 11)
        val was1 = pos(r1)
        val was2 = pos(r2)
        val before = DocumentFormat.save(ed.doc)

        ed.drag(pos(el(ed, 8)), Vec2(58.75, -150.0), steps = 12)
        assertTrue((pos(r1) - was1).length() > 1.0, "the riders were compensated")
        val after = DocumentFormat.save(ed.doc)
        assertTrue(after != before, "and the compensated values are in the file, so they ride the snapshot")

        assertTrue(ed.undo(), "one step back")
        assertClose(pos(el(ed, 10)).x, was1.x, 1e-9, "the riders came back with the host")
        assertClose(pos(el(ed, 10)).y, was1.y, 1e-9)
        assertClose(pos(el(ed, 11)).x, was2.x, 1e-9)
        assertClose(pos(el(ed, 11)).y, was2.y, 1e-9)
        assertEquals(before, DocumentFormat.save(ed.doc), "the whole drag was one step")
        assertTrue(ed.redo())
        assertEquals(after, DocumentFormat.save(ed.doc), "…and one step forward again")
    }

    /** A rider being dragged is never compensated: its own drag wins, and it still slides along its host. */
    @Test
    fun aRiderBeingDraggedIsNotCompensated() {
        val ed = Editor()
        ed.replaceDocument(DocumentFormat.load(fixture))
        val r1 = el(ed, 10)
        val r2 = el(ed, 11)
        val was2 = pos(r2)
        ed.drag(pos(r1), Vec2(0.0, -18.4), steps = 6)
        assertTrue((pos(r1) - Vec2(0.0, -18.4)).length() < 1.0, "the dragged rider went where it was dragged")
        assertClose(pos(r2).x, was2.x, 1e-9, "and its neighbour on the same host did not move")
        assertClose(pos(r2).y, was2.y, 1e-9)
    }

    /** The drawing survives the whole business: write, read, write again — byte for byte (OP-18). */
    @Test
    fun aCompensatedDrawingRoundTrips() {
        val ed = Editor()
        ed.replaceDocument(DocumentFormat.load(fixture))
        ed.drag(pos(el(ed, 8)), Vec2(58.75, -150.0), steps = 8)
        val once = DocumentFormat.save(ed.doc)
        val twice = DocumentFormat.save(DocumentFormat.load(once))
        assertEquals(once, twice, "save -> load -> save must be identical")

        val reloaded = Editor()
        reloaded.replaceDocument(DocumentFormat.load(once))
        for (n in listOf(10, 11)) {
            assertEquals(pos(el(ed, n)), pos(el(reloaded, n)), "a rider's compensated position is restated")
        }
    }

    /** A typed rider parameter is state too, and comes back from the file — the same seam (OP-13/OP-18). */
    @Test
    fun aTypedRiderPositionIsRestated() {
        val ed = Editor()
        ed.replaceDocument(DocumentFormat.load(fixture))
        val rider = el(ed, 10)
        ed.setTool(Tools.SELECT)
        ed.click(pos(rider))
        val field = ed.selectionFields().single()
        assertEquals("along line", field.label)
        field.write(7.5.mm)
        val here = pos(rider)
        val text = DocumentFormat.save(ed.doc)
        assertTrue(text.contains("dofs=7.5mm"), text)
        assertEquals(here, pos(el(Editor().also { it.replaceDocument(DocumentFormat.load(text)) }, 10)))
    }

    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerMove(s)
        pointerDown(s)
        pointerUp(s)
    }
}
