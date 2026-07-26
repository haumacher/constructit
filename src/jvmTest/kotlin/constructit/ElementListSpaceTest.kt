package constructit

import constructit.editor.Document
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.Vec2
import constructit.units.mm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * **The elements panel lists the active space, plus the solids** (OP-17, GitHub issue #2).
 *
 * Reported: *"the elements list contains the union of all elements of all sketches. The defining sketch is
 * shown, but this will get messy fast. I think it is sufficient to only show elements that are defined on the
 * current sketch — the 2D elements, and the 3D-defining outlines and resulting extrusions."*
 *
 * The rule ([Document.listedIn]) is one line: *an element belongs to one sketch space, except a solid, which
 * belongs to none.* The outlines and areas that define a feature are 2D and live in the space they were drawn
 * in, so they come along with the first half; a solid has no position in any space's coordinates and is shown
 * in the 3D viewport, the same view whichever space is active, so it is listed everywhere. Filtering solids by
 * the space they were extruded in would hide the part exactly where the next feature is being drawn.
 */
class ElementListSpaceTest {
    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerMove(s)
        pointerDown(s)
        pointerUp(s)
    }

    private fun names(ed: Editor): List<String> = ed.doc.listedElements().map { ed.doc.nameOf(it) }

    private fun kinds(ed: Editor): List<ElementKind> = ed.doc.listedElements().map { it.kind }

    /** A plate in the plan, extruded, with a face space sketched on its front edge. */
    private fun plateWithFaceSpace(): Editor {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(80.0, 50.0))
        ed.activeScalar = ed.doc.newParameter("thickness", 20.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(40.0, 0.0))
        ed.setTool(Tools.SKETCH_ON_FACE)
        ed.click(Vec2(40.0, 0.0))
        return ed
    }

    /** Each view lists its own 2D geometry, and neither lists the other's. */
    @Test
    fun eachSpaceListsItsOwn2dElementsAndNotTheOthers() {
        val ed = plateWithFaceSpace()
        assertFalse(ed.activeSpace.isPlan, "the face space is active after sketching on a face")

        // drawn on the face
        ed.setTool(Tools.CIRCLE)
        ed.click(Vec2(20.0, 10.0))
        ed.click(Vec2(28.0, 10.0))
        val onFace = ed.doc.elements.filter { ed.doc.spaceOf(it).name == ed.activeSpace.name }
        assertTrue(onFace.isNotEmpty(), "something was drawn here")

        val faceList = ed.doc.listedElements()
        for (el in onFace) assertTrue(el in faceList, "${ed.doc.nameOf(el)} was drawn here and must be listed")
        val plan = ed.doc.elements.filter { ed.doc.spaceOf(it).isPlan && it.kind != ElementKind.SOLID }
        assertTrue(plan.isNotEmpty(), "the plate's footprint and its rectangle are in the plan")
        for (el in plan) assertFalse(el in faceList, "${ed.doc.nameOf(el)} is the plan's, not this face's")

        // and back in the plan, the mirror image
        assertTrue(ed.setActiveSpace(Document.PLAN_SPACE))
        val planList = ed.doc.listedElements()
        for (el in plan) assertTrue(el in planList, "${ed.doc.nameOf(el)} belongs to the plan")
        for (el in onFace) assertFalse(el in planList, "${ed.doc.nameOf(el)} was drawn on the face")
    }

    /** A solid is in no sketch space, so both views list it — and the plan is not otherwise diminished. */
    @Test
    fun aSolidIsListedInEverySpaceBecauseItIsInNone() {
        val ed = plateWithFaceSpace()
        val solids = ed.doc.elements.filter { it.kind == ElementKind.SOLID }
        assertEquals(1, solids.size)
        assertTrue(kinds(ed).contains(ElementKind.SOLID), "the part is listed on the face being drawn on")
        assertTrue(ed.setActiveSpace(Document.PLAN_SPACE))
        assertTrue(kinds(ed).contains(ElementKind.SOLID), "and in the plan, where it was made")

        // a second feature's solid too, wherever it was made
        ed.setActiveSpace(ed.doc.spaces.last().name)
        ed.setTool(Tools.CIRCLE)
        ed.click(Vec2(20.0, 10.0))
        ed.click(Vec2(26.0, 10.0))
        ed.activeScalar = ed.doc.newParameter("bore", 15.0.mm)
        ed.setTool(Tools.CUT)
        ed.click(Vec2(26.0, 10.0))
        val cut = ed.doc.elements.last { it.kind == ElementKind.SOLID }
        assertTrue(cut in ed.doc.listedElements(), "the cut part is listed where it was cut")
        assertTrue(ed.setActiveSpace(Document.PLAN_SPACE))
        assertTrue(cut in ed.doc.listedElements(), "and in the plan, which has no coordinates for it at all")
    }

    /** The panel is a strict subset of the document, and the union over all spaces is the whole of it. */
    @Test
    fun theListsOfAllSpacesCoverTheDocumentWithoutRepeatingAnything2d() {
        val ed = plateWithFaceSpace()
        ed.setTool(Tools.CIRCLE)
        ed.click(Vec2(20.0, 10.0))
        ed.click(Vec2(28.0, 10.0))

        val seen = HashMap<String, Int>()
        for (space in ed.doc.spaces.map { it.name }) {
            assertTrue(ed.setActiveSpace(space))
            assertTrue(
                ed.doc.listedElements().size < ed.doc.elements.size,
                "the panel used to be the union of every space; in $space it must be less than that",
            )
            for (el in ed.doc.listedElements()) seen[el.id] = (seen[el.id] ?: 0) + 1
        }
        assertEquals(ed.doc.elements.map { it.id }.toSet(), seen.keys, "every element is listed somewhere")
        for (el in ed.doc.elements) {
            val times = seen.getValue(el.id)
            if (el.kind == ElementKind.SOLID) {
                assertEquals(ed.doc.spaces.size, times, "${ed.doc.nameOf(el)} is a solid: listed in every space")
            } else {
                assertEquals(1, times, "${ed.doc.nameOf(el)} is 2D: listed in exactly one space")
            }
        }
    }

    /**
     * Selecting a listed row works per space: what the active space lists is selected without being told to
     * go and look somewhere else, and a solid — listed everywhere, drawn in the 3D view — says nothing of the
     * kind either.
     */
    @Test
    fun selectingAListedElementWorksInTheSpaceThatListsIt() {
        val ed = plateWithFaceSpace()
        ed.setTool(Tools.CIRCLE)
        ed.click(Vec2(20.0, 10.0))
        ed.click(Vec2(28.0, 10.0))
        val circle = ed.doc.elements.last { it.kind == ElementKind.CIRCLE }
        val solid = ed.doc.elements.last { it.kind == ElementKind.SOLID }

        for (el in listOf<Element>(circle, solid)) {
            ed.selectElement(el)
            assertTrue(ed.isSelected(el), "${ed.doc.nameOf(el)} is selected")
            assertFalse(ed.statusHint.contains("switch the space"), "${ed.doc.nameOf(el)} is listed here: ${ed.statusHint}")
        }

        // in the plan, the face's circle is *not* listed — and selecting it by other means still says where to go
        assertTrue(ed.setActiveSpace(Document.PLAN_SPACE))
        assertFalse(circle in ed.doc.listedElements(), "not listed in the plan")
        ed.selectElement(circle)
        assertTrue(ed.isSelected(circle), "still selectable — the panel is a filter, not a lock")
        assertTrue(ed.statusHint.contains("switch the space"), "and it says where to look: ${ed.statusHint}")
        ed.selectElement(solid)
        assertFalse(ed.statusHint.contains("switch the space"), "a solid needs no space to be seen in: ${ed.statusHint}")
    }

    /** With one space there is nothing to filter, so the panel is what it always was. */
    @Test
    fun aPlanOnlyDrawingListsEverythingAsBefore() {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(40.0, 20.0))
        assertEquals(ed.doc.elements.map { ed.doc.nameOf(it) }, names(ed))
    }
}
