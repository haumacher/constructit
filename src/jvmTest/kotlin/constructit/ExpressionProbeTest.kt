package constructit

import constructit.core.CircleValue
import constructit.core.Evaluator
import constructit.dsl.scalar
import constructit.dsl.valueOf
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.ScalarEntry
import constructit.editor.Tools
import constructit.geom.Vec2
import constructit.units.mm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Probe review of the expressions scalar half — compositions the delivery never saw.
 *
 * The three questions: does the **cycle guard see across both binding kinds** (an expression edge followed
 * by the old plain-wire path, and the other way round); does a **chain of mixed bindings** — a plain wire
 * feeding an expression — follow its master, survive a rename of the middle link, and reload to the same
 * drawing; and does a parameter renamed to a **function's own name** stay readable, both live and through
 * the file.
 */
class ExpressionProbeTest {
    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerMove(s)
        pointerDown(s)
        pointerUp(s)
    }

    private fun scalarNamed(
        ed: Editor,
        name: String,
    ): ScalarEntry = assertNotNull(ed.doc.scalars.firstOrNull { it.name == name }, "the panel has a scalar named '$name'")

    private fun radiusOf(el: Element): Double =
        assertNotNull(Evaluator().valueOf(el.ref) as? CircleValue, "the circle is built").circle.radius

    private fun circleOf(ed: Editor): Element = ed.doc.elements.single { it.kind == ElementKind.CIRCLE }

    private fun roundTrips(
        ed: Editor,
        msg: String,
    ): String {
        val once = DocumentFormat.save(ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), msg)
        return once
    }

    /**
     * A value cannot be derived from itself, whichever spelling each half of the loop uses: an expression
     * followed by a plain wire back, and a plain wire followed by an expression back — both refused, and
     * the parameter left exactly as free as it was.
     */
    @Test
    fun theCycleGuardSeesAcrossBothBindingKinds() {
        // expression first, plain wire back
        run {
            val ed = Editor()
            val d = ed.doc.newParameter("d", 20.0.mm)
            val r = ed.doc.newParameter("r", 10.0.mm)
            assertTrue(ed.doc.bindParameter(r, "d/2 + 1mm"), "the forward binding is ordinary: ${ed.doc.note}")
            assertFalse(ed.doc.wireParameter(d, r), "the plain wire must see the expression edge it would close")
            assertClose(Evaluator().scalar(d.ref).mm, 20.0, tol = 1e-9, msg = "d stayed free")
        }
        // plain wire first, expression back
        run {
            val ed = Editor()
            val a = ed.doc.newParameter("a", 5.0.mm)
            val b = ed.doc.newParameter("b", 7.0.mm)
            assertTrue(ed.doc.wireParameter(b, a), "the forward wire is ordinary")
            assertFalse(ed.doc.bindParameter(a, "b*2"), "the expression must see the wire edge it would close")
            val note = assertNotNull(ed.doc.note, "the refusal speaks")
            assertTrue("cannot be derived from itself" in note, "and says why: $note")
            assertClose(Evaluator().scalar(a.ref).mm, 5.0, tol = 1e-9, msg = "a stayed free")
        }
    }

    /**
     * A plain wire feeding an expression — the degenerate binding and the general one in one chain:
     * `a → b (wire) → r = b*2 + 1mm (expression)` drives a circle. The master moves the whole chain, the
     * middle link renames without orphaning the text, and the file reloads to the same drawing.
     */
    @Test
    fun aChainAcrossBindingKindsFollowsItsMasterAndSurvivesTheFile() {
        val ed = Editor()
        val a = ed.doc.newParameter("a", 5.0.mm)
        val b = ed.doc.newParameter("b", 99.0.mm)
        ed.activeScalar = ed.doc.newParameter("r", 10.0.mm)
        ed.setTool(Tools.CIRCLE_R)
        ed.click(Vec2(0.0, 0.0))
        ed.checkpoint()
        val r = scalarNamed(ed, "r")

        assertTrue(ed.doc.wireParameter(b, a), "b follows a")
        assertTrue(ed.doc.bindParameter(r, "b*2 + 1mm"), "r is derived from b: ${ed.doc.note}")
        assertClose(radiusOf(circleOf(ed)), 11.0, tol = 1e-9, msg = "5*2 + 1 through the wire")

        ed.doc.setParameter(a, 10.0.mm)
        assertClose(radiusOf(circleOf(ed)), 21.0, tol = 1e-9, msg = "the master moved the whole chain")

        val renamed = assertNotNull(ed.doc.renameParameter(b, "width"), "the middle link renames")
        assertEquals("width", renamed)
        assertEquals("width*2 + 1mm", ed.doc.expressionOf(r), "the text is re-stamped, not orphaned")
        assertClose(radiusOf(circleOf(ed)), 21.0, tol = 1e-9, msg = "and nothing moved")

        val text = roundTrips(ed, "the mixed chain round-trips byte-equal")
        val loaded = DocumentFormat.load(text)
        val circle = loaded.elements.single { it.kind == ElementKind.CIRCLE }
        assertClose(radiusOf(circle), 21.0, tol = 1e-9, msg = "the reloaded drawing is the same drawing")
    }

    /**
     * A parameter renamed to a function's own name: `sin` is a legal one-word scalar name, so after the
     * rename the re-stamped text must still read it as a **reference** — live, and again through the file,
     * where the parser meets `sin/2` with no argument list in sight.
     */
    @Test
    fun aParameterRenamedToAFunctionsNameStaysAReference() {
        val ed = Editor()
        val d = ed.doc.newParameter("d", 20.0.mm)
        ed.activeScalar = ed.doc.newParameter("r", 10.0.mm)
        ed.setTool(Tools.CIRCLE_R)
        ed.click(Vec2(0.0, 0.0))
        ed.checkpoint()
        val r = scalarNamed(ed, "r")
        assertTrue(ed.doc.bindParameter(r, "d/2 + 1mm"), "the binding is ordinary: ${ed.doc.note}")

        val renamed = ed.doc.renameParameter(d, "sin")
        if (renamed == null) {
            // refusing the collision is a legitimate answer too — but it must speak
            val note = assertNotNull(ed.doc.note, "a refused rename says why")
            assertTrue("sin" in note, "and names the collision: $note")
            return
        }

        assertEquals("sin", renamed, "the rename is taken verbatim")
        assertEquals("sin/2 + 1mm", ed.doc.expressionOf(r), "the text is re-stamped")
        assertClose(radiusOf(circleOf(ed)), 11.0, tol = 1e-9, msg = "and still evaluates as a reference")

        val text = roundTrips(ed, "a function-named reference round-trips byte-equal")
        val loaded = DocumentFormat.load(text)
        val circle = loaded.elements.single { it.kind == ElementKind.CIRCLE }
        assertClose(radiusOf(circle), 11.0, tol = 1e-9, msg = "the parser reads 'sin/2' as a reference on load")

        // and the function is still a function where an argument list follows
        val extra = ed.doc.newParameter("t", 30.0.mm)
        assertTrue(ed.doc.bindParameter(extra, "sin + sin(90°)*1mm"), "both readings in one text: ${ed.doc.note}")
        assertClose(Evaluator().scalar(extra.ref).mm, 21.0, tol = 1e-9, msg = "20mm the reference + 1mm the function")
    }
}
