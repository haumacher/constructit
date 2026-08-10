package constructit

import constructit.dsl.valueOf
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.Vec2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * *Key points* had two silences, found by the probe review of the session-71 batch and fixed with it.
 *
 * **A traced outline handed back nothing at all.** `Element.isArea` is true of an `OUTLINE` and of an `AREA`,
 * so the slot took the pick either way — but the build served only `AREA`, and an outline fell through to the
 * per-kind table, found no branch, created nothing and **said nothing**. That is the class DESIGN.md's
 * session-33 note already names (*"the generic tool path still turns an unspoken null into an empty status
 * line"*), and the fix is the coercion that already existed for the seam's own slot (`Document.regionOf`), so
 * both kinds are one predicate and no element kind has a case of its own.
 *
 * **And a pick with genuinely nothing to take was silent too.** An *infinite* line has no ends, so there is
 * nothing to materialize; a click that does nothing and says nothing is indistinguishable from a click that
 * missed. It now refuses by name and lists what does hand its points back.
 */
class KeyPointsSpeakTest {
    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerMove(s)
        pointerDown(s)
        pointerUp(s)
    }

    /** A rectangle's four legs, traced into one closed outline. */
    private fun tracedRectangleOutline(): Editor {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(60.0, 40.0))
        ed.setTool(Tools.OUTLINE)
        ed.click(Vec2(30.0, 0.0))
        ed.click(Vec2(60.0, 20.0))
        ed.click(Vec2(30.0, 40.0))
        ed.click(Vec2(0.0, 20.0))
        ed.key("Enter")
        return ed
    }

    /**
     * **A traced outline hands back its corners** — the same answer a thick path's footprint gets, through the
     * same coercion. Before this the click created nothing and the status line stayed empty.
     */
    @Test
    fun aTracedOutlineHandsBackItsCorners() {
        val ed = tracedRectangleOutline()
        assertTrue(ed.doc.elements.any { it.kind == ElementKind.OUTLINE }, "the outline: ${ed.statusHint}")
        val before = ed.doc.elements.count { it.kind == ElementKind.DERIVED_POINT }

        ed.setTool(Tools.KEY_POINTS)
        ed.click(Vec2(30.0, 0.0))
        val corners =
            ed.doc.elements.filter { it.kind == ElementKind.DERIVED_POINT }.drop(before).map { posOfPoint(ed, it) }
        assertEquals(4, corners.size, "four corners, and it said so: ${ed.statusHint}")
        for (want in listOf(Vec2(0.0, 0.0), Vec2(60.0, 0.0), Vec2(60.0, 40.0), Vec2(0.0, 40.0))) {
            assertTrue(corners.any { (it - want).length() < 1e-9 }, "the corner at $want is among $corners")
        }

        // …and they are ordinary recorded points: the step owns them and the file round-trips
        assertTrue(ed.doc.steplessElements().isEmpty(), "every corner has its step")
        val once = DocumentFormat.save(ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "and the drawing survives its file")
    }

    /**
     * **A pick with nothing to take says so, and places nothing** — an infinite line has no ends, and the
     * refusal names the element, what it is, and what does publish points.
     */
    @Test
    fun aKeyPointPickWithNothingToTakeSaysSo() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(-50.0, 0.0))
        ed.click(Vec2(50.0, 0.0))
        ed.setTool(Tools.LINE)
        ed.click(Vec2(-50.0, 0.0))
        ed.click(Vec2(50.0, 0.0))
        val before = ed.doc.elements.size

        ed.setTool(Tools.KEY_POINTS)
        ed.click(Vec2(0.0, 0.0))
        assertEquals(before, ed.doc.elements.size, "nothing was created: ${ed.statusHint}")
        assertTrue(ed.statusHint.isNotEmpty(), "and the click did not pass in silence")
        assertTrue(ed.statusHint.contains("a line has no defining points to take"), ed.statusHint)
        assertTrue(ed.statusHint.contains("an arc"), "…and it names what does: ${ed.statusHint}")
    }

    private fun posOfPoint(
        ed: Editor,
        el: constructit.editor.Element,
    ): Vec2 =
        (constructit.core.Evaluator().valueOf(el.ref) as constructit.core.PointValue).p.also {
            assertTrue(ed.doc.elements.contains(el))
        }
}
