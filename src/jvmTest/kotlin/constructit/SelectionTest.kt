package constructit

import constructit.core.Evaluator
import constructit.dsl.PointRef
import constructit.dsl.point
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.PointerButton
import constructit.editor.Tools
import constructit.geom.Vec2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Multi-select (OP-16 build order step 0): the selection is a **set with a primary element**, built by
 * clicking, Shift+clicking and rubber-banding. Panning moved to the middle button (and Space+drag in
 * the shell) to free the empty-space drag for the marquee — the standard CAD split.
 */
class SelectionTest {
    private fun Editor.click(
        world: Vec2,
        additive: Boolean = false,
    ) {
        val s = camera.worldToScreen(world)
        pointerDown(s, PointerButton.PRIMARY, additive)
        pointerUp(s)
    }

    private fun Editor.marquee(
        from: Vec2,
        to: Vec2,
        additive: Boolean = false,
    ) {
        pointerDown(camera.worldToScreen(from), PointerButton.PRIMARY, additive)
        pointerMove(camera.worldToScreen(to))
        pointerUp(camera.worldToScreen(to))
    }

    /** Three free points, wide apart, plus a segment over the outer two. */
    private fun scene(): Editor {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(-60.0, 0.0))
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(60.0, 0.0))
        ed.setTool(Tools.SELECT)
        return ed
    }

    private fun ids(ed: Editor) = ed.selectedElements.map { it.id }.sorted()

    @Test
    fun shiftClickAccumulatesAndTogglesOff() {
        val ed = scene()
        ed.click(Vec2(-60.0, 0.0))
        assertEquals(1, ed.selectionCount)
        val first = ed.selection!!

        ed.click(Vec2(0.0, 0.0), additive = true)
        ed.click(Vec2(60.0, 0.0), additive = true)
        assertEquals(3, ed.selectionCount, "Shift+click adds to the selection")
        assertEquals("e3", ed.selection?.id, "the last Shift+click becomes the primary")
        assertTrue(ed.statusHint.contains("3 elements"), "got: ${ed.statusHint}")

        // the same Shift+click again removes it
        ed.click(Vec2(60.0, 0.0), additive = true)
        assertEquals(2, ed.selectionCount)
        assertFalse(ed.selectedElements.any { it.id == "e3" })
        assertTrue(ed.isSelected(first))

        // a plain click replaces the whole set
        ed.click(Vec2(0.0, 0.0))
        assertEquals(1, ed.selectionCount)
        assertEquals("e2", ed.selection?.id)
    }

    /** With several elements selected the inspector has nothing single to address (OP-13). */
    @Test
    fun fieldsAreEmptyForAMultiSelectionAndBackWithOne() {
        val ed = scene()
        ed.click(Vec2(-60.0, 0.0))
        assertEquals(listOf("x", "y"), ed.selectionFields().map { it.label })

        ed.click(Vec2(0.0, 0.0), additive = true)
        assertTrue(ed.selectionFields().isEmpty(), "no single handle to show")
        assertEquals("2 elements", ed.selectionLabel())
        assertFalse(ed.writeSelectionField(0, 10.0), "and nothing to write either")

        ed.click(Vec2(0.0, 0.0))
        assertEquals(listOf("x", "y"), ed.selectionFields().map { it.label })
    }

    @Test
    fun marqueeSelectsWhatItCoversAndNotWhatItDoesNot() {
        val ed = scene()
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(-60.0, 0.0))
        ed.click(Vec2(60.0, 0.0)) // a segment spanning the whole scene
        ed.setTool(Tools.SELECT)

        // a box around the left point only — the segment *crosses* it, so it comes too
        ed.marquee(Vec2(-80.0, -20.0), Vec2(-40.0, 20.0))
        assertEquals(listOf("e1", "e4"), ids(ed), "the point inside plus the segment crossing the box")

        // a box that touches nothing
        ed.marquee(Vec2(-80.0, 40.0), Vec2(-40.0, 80.0))
        assertEquals(0, ed.selectionCount)
        assertEquals("Nothing in the box", ed.statusHint)

        // a box over everything
        ed.marquee(Vec2(-80.0, -20.0), Vec2(80.0, 20.0))
        assertEquals(listOf("e1", "e2", "e3", "e4"), ids(ed))

        // Shift makes the marquee add rather than replace
        ed.marquee(Vec2(-80.0, 40.0), Vec2(-40.0, 80.0), additive = true)
        assertEquals(4, ed.selectionCount, "an empty Shift+marquee keeps what was selected")
    }

    /** A circle's *outline* is what the marquee meets — a box deep inside it touches nothing. */
    @Test
    fun marqueeMeetsACircleOutlineButNotItsInterior() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(50.0, 0.0))
        ed.setTool(Tools.CIRCLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(50.0, 0.0)) // r = 50
        ed.setTool(Tools.SELECT)

        ed.marquee(Vec2(10.0, 10.0), Vec2(20.0, 20.0)) // well inside the circle
        assertEquals(0, ed.doc.elements.count { it.kind == ElementKind.CIRCLE && ed.isSelected(it) })

        ed.marquee(Vec2(40.0, -10.0), Vec2(60.0, 10.0)) // straddling the outline
        assertTrue(ed.doc.elements.any { it.kind == ElementKind.CIRCLE && ed.isSelected(it) })
    }

    @Test
    fun clickingEmptySpaceAndEscapeBothClearTheSelection() {
        val ed = scene()
        ed.marquee(Vec2(-80.0, -20.0), Vec2(80.0, 20.0))
        assertEquals(3, ed.selectionCount)
        assertTrue(ed.key("Escape"))
        assertEquals(0, ed.selectionCount)
        assertEquals(null, ed.selection)

        ed.marquee(Vec2(-80.0, -20.0), Vec2(80.0, 20.0))
        ed.click(Vec2(0.0, 90.0)) // press and release on nothing
        assertEquals(0, ed.selectionCount)
    }

    /**
     * Shift is axis lock while dragging *and* the selection toggle on a click. The two coexist because
     * the toggle is decided on release: a gesture that moved is a drag and leaves the set alone.
     */
    @Test
    fun shiftDragAxisLocksWithoutTouchingTheSelection() {
        val ed = scene()
        ed.click(Vec2(-60.0, 0.0))
        ed.click(Vec2(0.0, 0.0), additive = true)
        val before = ids(ed)

        ed.axisLock = true // what the shell sets while Shift is held
        ed.pointerDown(ed.camera.worldToScreen(Vec2(60.0, 0.0)), PointerButton.PRIMARY, additive = true)
        ed.pointerMove(ed.camera.worldToScreen(Vec2(90.0, 18.0)))
        ed.pointerUp(ed.camera.worldToScreen(Vec2(90.0, 18.0)))
        ed.axisLock = false

        val moved = ed.doc.freePoints.first { it.id == "e3" }
        val p = Evaluator().point(moved.ref as PointRef)
        assertClose(p.x, 90.0)
        assertClose(p.y, 0.0, msg = "the drag was axis-locked")
        assertEquals(before, ids(ed), "a Shift+*drag* must not toggle membership")

        // and the click form still toggles
        ed.click(Vec2(90.0, 0.0), additive = true)
        assertEquals(3, ed.selectionCount)
    }

    @Test
    fun deletingAMultiSelectionDropsEveryStepAndItsDependentsInOneUndo() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(-40.0, 0.0))
        ed.click(Vec2(40.0, 0.0))
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(-40.0, 0.0))
        ed.click(Vec2(40.0, 0.0))
        ed.setTool(Tools.POINT_ON_LINE)
        ed.click(Vec2(10.0, 0.0)) // rides the segment — a dependent
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 60.0)) // unrelated, must survive
        val before = DocumentFormat.save(ed.doc)

        ed.setTool(Tools.SELECT)
        ed.click(Vec2(-40.0, 0.0))
        ed.click(Vec2(0.0, 0.0), additive = true) // the segment
        assertEquals(2, ed.selectionCount)
        assertTrue(ed.deleteSelection())

        assertEquals(0, ed.doc.elements.count { it.kind == ElementKind.SEGMENT })
        assertEquals(0, ed.doc.elements.count { it.kind == ElementKind.ON_CURVE }, "the on-curve point went with its curve")
        assertEquals(2, ed.doc.freePoints.size, "the right endpoint and the unrelated point survive")
        assertTrue(ed.statusHint.contains("2 elements"), "got: ${ed.statusHint}")
        assertTrue(ed.statusHint.contains("1 dependent"), "got: ${ed.statusHint}")
        assertEquals(0, ed.selectionCount, "what was deleted cannot stay selected")

        assertTrue(ed.undo())
        assertEquals(before, DocumentFormat.save(ed.doc), "one undo brings the whole bulk delete back")
    }

    @Test
    fun hidingTheSelectionIsAViewStateAndNotAnUndoStep() {
        val ed = scene()
        ed.marquee(Vec2(-80.0, -20.0), Vec2(80.0, 20.0))
        val saved = DocumentFormat.save(ed.doc)

        assertEquals(3, ed.setSelectionVisible(false))
        assertTrue(ed.doc.elements.none { it.visible })
        assertEquals(saved, DocumentFormat.save(ed.doc), "visibility is a view state — the file is a construction (OP-18)")

        assertEquals(3, ed.setSelectionVisible(true))
        assertTrue(ed.doc.elements.all { it.visible })

        // hiding consumed no undo step either: the next undo is still the third point's
        ed.setSelectionVisible(false)
        assertTrue(ed.undo())
        assertEquals(2, ed.doc.freePoints.size)
    }

    /** A hidden element is not pickable, so a marquee cannot select what it cannot show. */
    @Test
    fun aHiddenElementIsNotMarqueeSelectable() {
        val ed = scene()
        ed.click(Vec2(0.0, 0.0))
        ed.setSelectionVisible(false)
        ed.marquee(Vec2(-80.0, -20.0), Vec2(80.0, 20.0))
        assertEquals(listOf("e1", "e3"), ids(ed))
    }
}
