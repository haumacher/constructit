package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.PointValue
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.PointerButton
import constructit.editor.Tools
import constructit.geom.Vec2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **Ratio points, and the generic mechanism behind them: a scalar slot with a default.**
 *
 * The user asked for "the point a third of the way along", which is the midpoint with its 0.5 relaxed. The
 * interesting part is what it must *not* cost: Midpoint stays two clicks, its step stays what it was, and its
 * result stays a plain derived point — a tool whose scalar slots all carry a default never waits for one
 * ([ScalarSlot.default]). Type a number first and the very same two clicks build `pointAtRatio(A, B, t)`
 * instead, with `t` an ordinary dimensionless parameter: draggable along the span, typeable, wireable and
 * **shareable**, which is what makes one `t` over several pairs equal proportions *by construction* (OP-5).
 */
class RatioPointTest {
    private fun Editor.click(
        world: Vec2,
        additive: Boolean = false,
    ) {
        val s = camera.worldToScreen(world)
        pointerMove(s)
        pointerDown(s, PointerButton.PRIMARY, additive)
        pointerUp(s)
    }

    private fun Editor.drag(
        from: Vec2,
        to: Vec2,
        steps: Int = 3,
    ) {
        setTool(Tools.SELECT)
        pointerDown(camera.worldToScreen(from))
        for (i in 1..steps) pointerMove(camera.worldToScreen(from + (to - from) * (i.toDouble() / steps)))
        pointerUp(camera.worldToScreen(to))
    }

    private fun Editor.type(text: String) {
        for (c in text) key(c.toString())
        key("Enter")
    }

    private fun pos(el: Element): Vec2 = ((Evaluator().eval(el.ref.node) as EvalResult.Ok).value as PointValue).p

    /** Two free points at (0,0) and (100,50) — the span every case below divides. */
    private fun span(): Editor {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 50.0))
        return ed
    }

    private fun Editor.midpointOf(
        a: Vec2,
        b: Vec2,
    ) {
        setTool(Tools.MIDPOINT)
        click(a)
        click(b)
    }

    // ---- the default: nothing about Midpoint changed ----

    /**
     * With nothing typed, Midpoint is exactly what it always was — the same two clicks, the same derived
     * point with no handle of its own, and a step with no `scalar=` in it. The defaulted slot is invisible
     * until it is used, which is the whole requirement.
     */
    @Test
    fun withNoFactorMidpointIsExactlyWhatItWas() {
        val ed = span()
        ed.midpointOf(Vec2(0.0, 0.0), Vec2(100.0, 50.0))
        val mid = ed.doc.elements.last()
        assertEquals(ElementKind.DERIVED_POINT, mid.kind, "a plain derived point, as before")
        assertNull(mid.handle, "and no freedom of its own")
        assertEquals(Vec2(50.0, 25.0), pos(mid))
        assertTrue(ed.doc.scalars.isEmpty(), "no parameter appears out of nowhere")
        assertEquals(
            """
constructit 2
point 0,0 -> e1
point 100,50 -> e2
tool midpoint pts=e1,e2 clicks=0,0;100,50 -> e3
""".trimStart(),
            DocumentFormat.save(ed.doc),
            "the step is unchanged: a slot nobody used records nothing",
        )
    }

    /** The status line names what the tool *will* use, default included — it does not go quiet about it. */
    @Test
    fun theStatusLineNamesTheDefaultItWillUse() {
        val ed = span()
        ed.setTool(Tools.MIDPOINT)
        assertTrue(ed.currentHelp().contains("factor = 0.5 (default)"), "got: ${ed.currentHelp()}")
        // …and a length picked in the panel is *not* silently taken for a ratio (OP-7): wrong dimension
        ed.activeScalar = ed.doc.newParameter("depth", constructit.units.Quantity.mm(7.0))
        assertTrue(ed.currentHelp().contains("factor = 0.5 (default)"), "got: ${ed.currentHelp()}")
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 50.0))
        assertEquals(ElementKind.DERIVED_POINT, ed.doc.elements.last().kind, "so it stayed the plain midpoint")
    }

    // ---- the factor, typed ----

    /** Type `.3`, click the two points: the exact 0.3 point, as a rider over an ordinary parameter. */
    @Test
    fun aTypedFactorPlacesTheExactRatioPoint() {
        val ed = span()
        ed.setTool(Tools.MIDPOINT)
        ed.type(".3")
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 50.0))
        val p = ed.doc.elements.last()
        assertEquals(ElementKind.ON_CURVE, p.kind, "1 DOF along the span, so it reads as a rider")
        assertEquals(Vec2(30.0, 15.0), pos(p), "exactly three tenths of the way, to the bit")
        val t = ed.doc.scalars.single()
        assertEquals("factor", t.name, "an ordinary parameter, named after the slot")
        assertEquals(0.3, (Evaluator().eval(t.ref.node) as EvalResult.Ok).let { (it.value as constructit.core.ScalarValue).q.value })
        assertEquals(listOf("factor"), p.handle!!.fields().map { it.label }, "and it is the point's own field (OP-13)")
    }

    /** Dragging the point writes the factor — dragging and typing are one operation (OP-13). */
    @Test
    fun draggingARatioPointWritesTheFactor() {
        val ed = span()
        ed.setTool(Tools.MIDPOINT)
        ed.type(".3")
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 50.0))
        val p = ed.doc.elements.last()
        assertTrue(p.draggable)

        ed.drag(pos(p), Vec2(75.0, 37.5))
        assertClose(pos(p).x, 75.0, 1e-9, "it slid along the span")
        assertClose(pos(p).y, 37.5, 1e-9)
        assertClose(factor(ed), 0.75, 1e-9, "…by writing the factor, which is the only thing it owns")

        // and the typed form of the same write
        ed.click(pos(p))
        assertEquals(listOf("factor"), ed.selectionFields().map { it.label })
        assertTrue(ed.writeSelectionField(0, 0.25))
        assertEquals(Vec2(25.0, 12.5), pos(p))
    }

    /**
     * The point of a *dimensionless* factor: one parameter feeding several spans is **equal proportions by
     * construction** (OP-5 — sharing a node is equality), so the two points stay at the same share of two
     * different spans however either span is edited, with nothing asserted and no solver.
     */
    @Test
    fun oneSharedFactorKeepsSeveralSpansInTheSameProportion() {
        val ed = span()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, -100.0))
        ed.click(Vec2(40.0, -100.0))
        ed.setTool(Tools.MIDPOINT)
        ed.type(".25")
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 50.0))
        val first = ed.doc.elements.last()
        // the same parameter, picked in the panel this time — a pick and a typed value are one thing (OP-13)
        ed.setTool(Tools.MIDPOINT)
        ed.activeScalar = ed.doc.scalars.single()
        ed.click(Vec2(0.0, -100.0))
        ed.click(Vec2(40.0, -100.0))
        val second = ed.doc.elements.last()
        assertEquals(1, ed.doc.scalars.size, "one factor, two ratio points")
        assertEquals(Vec2(25.0, 12.5), pos(first))
        assertEquals(Vec2(10.0, -100.0), pos(second))

        // drag one: the other keeps the same proportion, because it is the same node
        ed.drag(pos(first), Vec2(60.0, 30.0))
        assertClose(factor(ed), 0.6, 1e-9)
        assertClose(pos(second).x, 24.0, 1e-9, "three fifths of 40, by construction")

        // and stretching one span leaves the other's proportion alone
        ed.drag(Vec2(40.0, -100.0), Vec2(80.0, -100.0))
        assertClose(pos(second).x, 48.0, 1e-9)
        assertClose(factor(ed), 0.6, 1e-9)
    }

    /** A factor outside 0…1 is allowed — and said out loud, since the point then leaves its span. */
    @Test
    fun aFactorBeyondTheSpanExtrapolatesAndSaysSo() {
        val ed = span()
        ed.setTool(Tools.MIDPOINT)
        ed.type("1.5")
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 50.0))
        assertEquals(Vec2(150.0, 75.0), pos(ed.doc.elements.last()), "beyond the second point, as asked")
        assertTrue(ed.statusHint.contains("outside 0"), "got: ${ed.statusHint}")
        assertTrue(ed.statusHint.contains("beyond the second"), "got: ${ed.statusHint}")
    }

    // ---- the same slot on the perpendicular bisector ----

    /** No factor: the bisector, exactly as before — one element, no parameter, no point. */
    @Test
    fun withNoFactorThePerpBisectorIsExactlyWhatItWas() {
        val ed = span()
        ed.setTool(Tools.PERP_BISECTOR)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 50.0))
        assertEquals(3, ed.doc.elements.size, "two points and the line")
        assertEquals(ElementKind.LINE, ed.doc.elements.last().kind)
        assertTrue(ed.doc.scalars.isEmpty())
    }

    /**
     * With a factor it is the perpendicular through *that* point of the span — composed from the ops that
     * already existed, so the ratio point is an element of its own and the factor is draggable.
     */
    @Test
    fun aFactorMakesThePerpBisectorAPerpendicularAtThatRatio() {
        val ed = span()
        ed.setTool(Tools.PERP_BISECTOR)
        ed.type(".2")
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 50.0))
        val ratio = ed.doc.elements.first { it.kind == ElementKind.ON_CURVE }
        val line = ed.doc.elements.last()
        assertEquals(ElementKind.LINE, line.kind)
        assertEquals(Vec2(20.0, 10.0), pos(ratio))
        val l = (Evaluator().eval(line.ref.node) as EvalResult.Ok).value as constructit.core.LineValue
        // through the ratio point, and perpendicular to the span
        assertClose((l.line.origin - Vec2(20.0, 10.0)).dot(l.line.dir.perp()), 0.0, 1e-9)
        assertClose(l.line.dir.dot(Vec2(100.0, 50.0).normalized()), 0.0, 1e-9)

        // dragging the ratio point takes the line with it, which is what makes the factor reachable
        ed.drag(Vec2(20.0, 10.0), Vec2(80.0, 40.0))
        assertClose(factor(ed), 0.8, 1e-9)
    }

    // ---- persistence ----

    /** The factor is state, so it rides the file: `save -> load -> save` byte-equal and geometry intact. */
    @Test
    fun aRatioPointRoundTrips() {
        val ed = span()
        ed.setTool(Tools.MIDPOINT)
        ed.type(".3")
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 50.0))
        ed.drag(pos(ed.doc.elements.last()), Vec2(70.0, 35.0)) // the factor is dragged, i.e. state
        val once = DocumentFormat.save(ed.doc)
        assertTrue(once.contains("tool midpoint"), once)
        assertTrue(once.contains("scalar=\"factor\""), "the factor rides the ordinary scalar seam: $once")
        val reloaded = DocumentFormat.load(once)
        assertEquals(once, DocumentFormat.save(reloaded), "save -> load -> save must be identical")
        val fresh = Editor()
        fresh.replaceDocument(reloaded)
        assertEquals(pos(ed.doc.elements.last()), pos(fresh.doc.elements.last()), "and it came back exactly")
        assertEquals(ElementKind.ON_CURVE, fresh.doc.elements.last().kind, "…as a ratio point, still draggable")
        assertTrue(fresh.doc.elements.last().draggable)
    }

    /** The perpendicular-at-a-ratio round-trips too — two created elements and one parameter. */
    @Test
    fun thePerpendicularAtARatioRoundTrips() {
        val ed = span()
        ed.setTool(Tools.PERP_BISECTOR)
        ed.type(".2")
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 50.0))
        val once = DocumentFormat.save(ed.doc)
        val reloaded = DocumentFormat.load(once)
        assertEquals(once, DocumentFormat.save(reloaded))
        assertEquals(4, reloaded.elements.size, "two points, the ratio point, the line")
        assertNotNull(reloaded.elements.first { it.kind == ElementKind.ON_CURVE }.handle)
    }

    /** Typing a factor and clicking is **one** operation, so one undo takes the whole thing back. */
    @Test
    fun aTypedFactorAndItsClicksAreOneUndoStep() {
        val ed = span()
        val before = DocumentFormat.save(ed.doc)
        ed.setTool(Tools.MIDPOINT)
        ed.type(".3")
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 50.0))
        assertTrue(ed.undo())
        assertEquals(before, DocumentFormat.save(ed.doc), "the parameter went with the point that wanted it")
        assertTrue(ed.doc.scalars.isEmpty())
        assertTrue(ed.redo())
        assertFalse(ed.doc.scalars.isEmpty())
    }

    private fun factor(ed: Editor): Double =
        ((Evaluator().eval(ed.doc.scalars.first { it.name.startsWith("factor") }.ref.node) as EvalResult.Ok).value as constructit.core.ScalarValue).q.value
}
