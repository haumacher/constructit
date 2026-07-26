package constructit

import constructit.core.Evaluator
import constructit.core.PointValue
import constructit.dsl.valueOf
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.Vec2
import constructit.units.mm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **A parameter pick can be switched off** (OP-13: the panel is as much an input as the canvas, so it needs
 * the same "never mind" the canvas has).
 *
 * Clicking the active row again clears it, and so does Escape with no gesture pending. Without that there was
 * no way to un-pick a scalar — and an idle pick is not harmless: a **defaulted** slot adopts any pick of its
 * own dimension, so one stray dimensionless pick shadows every dimensionless default for the rest of the
 * session. That is the user's trap, reproduced below: Midpoint keeps building the ratio point of a factor
 * picked long ago instead of the midpoint it is asked for.
 */
class ParameterDeselectTest {
    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerDown(s)
        pointerUp(s)
    }

    /**
     * Where the last point the Midpoint tool built landed. A **ratio** point reads as a rider (1 DOF along
     * the span, hence [ElementKind.ON_CURVE]) and a plain midpoint as a [ElementKind.DERIVED_POINT], so the
     * kind is exactly what is under test — the position is asked of whichever it made.
     */
    private fun mid(ed: Editor): Vec2 =
        (
            Evaluator().valueOf(
                ed.doc.elements.last { it.kind == ElementKind.DERIVED_POINT || it.kind == ElementKind.ON_CURVE }.ref,
            ) as PointValue
        ).p

    /** Two points 100 apart, so a midpoint lands at x = 50 and a 0.25 ratio point at x = 25. */
    private fun span(): Editor {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 0.0))
        return ed
    }

    @Test
    fun clickingTheActiveRowAgainSwitchesItOff() {
        val ed = span()
        val p = ed.doc.newParameter("r", 12.0.mm)
        ed.clickScalar(p)
        assertEquals(p, ed.activeScalar)

        ed.clickScalar(p)
        assertNull(ed.activeScalar, "the active row clicked again turns the pick off")
        assertEquals("no parameter active — tools use their defaults", ed.statusHint)

        // …and a *different* row is an ordinary pick, not a toggle
        val q = ed.doc.newParameter("s", 3.0.mm)
        ed.clickScalar(p)
        ed.clickScalar(q)
        assertEquals(q, ed.activeScalar)
    }

    @Test
    fun escapeWithNoGesturePendingClearsTheActiveParameterToo() {
        val ed = span()
        val p = ed.doc.newParameter("r", 12.0.mm)
        ed.clickScalar(p)
        ed.setTool(Tools.SELECT)
        ed.click(Vec2(0.0, 0.0))
        assertEquals(1, ed.selectionCount)

        assertTrue(ed.key("Escape"))
        assertEquals(0, ed.selectionCount, "Escape still clears the selection")
        assertNull(ed.activeScalar, "…and the parameter pick with it")
        assertTrue(ed.statusHint.contains("no parameter active"), "got: ${ed.statusHint}")

        // with a tool gesture pending, Escape still belongs to the gesture (the picks it abandons)
        val q = ed.doc.newParameter("s", 4.0.mm)
        ed.clickScalar(q)
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(0.0, 0.0)) // one of two slots filled
        assertTrue(ed.key("Escape"))
        assertEquals(q, ed.activeScalar, "the first Escape abandoned the half-built tool, not the pick")
        assertTrue(ed.key("Escape"))
        assertNull(ed.activeScalar, "the next one clears the pick")
    }

    /**
     * **The user's trap.** A ratio point is built by typing a factor, which leaves a *dimensionless*
     * parameter picked; Midpoint's factor slot is dimensionless and **defaulted** (0.5), so it adopted that
     * pick and every later "midpoint" was that ratio point instead — with no way to say "never mind".
     */
    @Test
    fun aDefaultedMidpointWorksAgainAfterTheStrayDimensionlessPickIsDropped() {
        val ed = span()
        ed.setTool(Tools.MIDPOINT)
        for (c in ".25") ed.key(c.toString())
        ed.key("Enter")
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 0.0))
        assertClose(mid(ed).x, 25.0, msg = "the typed factor built a ratio point")
        val factor = assertNotNull(ed.activeScalar, "…and left its parameter picked")

        // the trap: the next midpoint silently reuses it
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 0.0))
        assertClose(mid(ed).x, 25.0, msg = "the stray pick shadows the default")

        // switch the pick off — the panel's own gesture, and the whole fix
        ed.clickScalar(factor)
        assertNull(ed.activeScalar)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 0.0))
        assertClose(mid(ed).x, 50.0, msg = "the defaulted factor is back: 0.5 is the midpoint")

        // and the parameter itself is untouched — dropping a *pick* is not deleting a value (OP-7)
        assertTrue(ed.doc.scalars.any { it === factor }, "the factor is still in the panel")
    }

    /** The same, dropped with Escape rather than with the row — one decision, two routes. */
    @Test
    fun escapeAlsoFreesTheDefaultedSlot() {
        val ed = span()
        ed.setTool(Tools.MIDPOINT)
        for (c in ".25") ed.key(c.toString())
        ed.key("Enter")
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 0.0))
        assertClose(mid(ed).x, 25.0)

        assertTrue(ed.key("Escape"))
        assertNull(ed.activeScalar)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 0.0))
        assertClose(mid(ed).x, 50.0, msg = "the midpoint is a midpoint again")
    }
}
