package constructit

import constructit.core.Evaluator
import constructit.core.SegmentValue
import constructit.dsl.valueOf
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.Vec2
import constructit.units.mm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Dependency-aware delete: the unit of removal is the journal *step* (OP-18). Deleting an element
 * drops the step that created it plus every later step that depends on anything the dropped steps
 * made, then replays the remaining script — so what survives is exactly what still constructs, and
 * the result must still round-trip byte-identically.
 */
class DeleteTest {
    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerDown(s)
        pointerUp(s)
    }

    private fun Editor.selectAt(world: Vec2) {
        setTool(Tools.SELECT)
        click(world)
    }

    /** The invariant delete must preserve: the filtered script is a valid, stable document. */
    private fun assertRoundTrips(ed: Editor) {
        val once = DocumentFormat.save(ed.doc)
        val twice = DocumentFormat.save(DocumentFormat.load(once))
        assertEquals(once, twice, "save -> load -> save must stay identical after a delete")
    }

    @Test
    fun deletingASegmentTakesItsDependentsButNotItsEndpoints() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(-40.0, 0.0))
        ed.click(Vec2(40.0, 0.0))
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(-40.0, 0.0))
        ed.click(Vec2(40.0, 0.0))
        ed.setTool(Tools.POINT_ON_LINE)
        ed.click(Vec2(10.0, 0.0)) // rides the segment — downstream of it
        ed.setTool(Tools.MIDPOINT)
        ed.click(Vec2(-40.0, 0.0))
        ed.click(Vec2(40.0, 0.0)) // built on the two points only — NOT downstream of the segment

        ed.selectAt(Vec2(25.0, 0.0))
        assertEquals(ElementKind.SEGMENT, ed.selection?.kind)
        assertTrue(ed.deleteSelection())

        assertEquals(0, ed.doc.elements.count { it.kind == ElementKind.SEGMENT })
        assertEquals(0, ed.doc.elements.count { it.kind == ElementKind.ON_CURVE }, "the on-curve point goes with its curve")
        assertEquals(2, ed.doc.freePoints.size, "the endpoints are upstream and stay")
        assertEquals(1, ed.doc.elements.count { it.kind == ElementKind.DERIVED_POINT }, "the midpoint never depended on the segment")
        assertTrue(ed.statusHint.contains("1 dependent"), "got: ${ed.statusHint}")
        assertRoundTrips(ed)
    }

    @Test
    fun deletingAPointRenumbersLaterReferencesConsistently() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(-50.0, 0.0)) // e1 — deleted, so every later script name shifts
        ed.click(Vec2(0.0, 30.0))
        ed.click(Vec2(50.0, 0.0))
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(0.0, 30.0))
        ed.click(Vec2(50.0, 0.0)) // references the 2nd and 3rd point positionally

        ed.selectAt(Vec2(-50.0, 0.0))
        assertTrue(ed.deleteSelection())

        assertEquals(2, ed.doc.freePoints.size)
        val seg = ed.doc.elements.single { it.kind == ElementKind.SEGMENT }
        val v = (Evaluator().valueOf(seg.ref) as SegmentValue).seg
        assertClose(v.a.x, 0.0)
        assertClose(v.a.y, 30.0)
        assertClose(v.b.x, 50.0)
        assertClose(v.b.y, 0.0)
        assertRoundTrips(ed)
    }

    @Test
    fun deletingAnOrthoLegDeletesAtItsStepGranularity() {
        val ed = Editor()
        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(60.0, 2.0))
        ed.click(Vec2(58.0, 40.0))
        ed.finishPath()

        ed.selectAt(Vec2(30.0, 0.0)) // the first leg
        assertTrue(ed.deleteSelection())

        // the leg's creating step and the path steps built after it are gone; the start survives
        assertEquals(0, ed.doc.elements.count { it.kind == ElementKind.SEGMENT })
        assertEquals(1, ed.doc.orthoPaths.single().vertices.size)
        assertRoundTrips(ed)
    }

    @Test
    fun deletingAFootprintRemovesTheThickPathButKeepsItsCarrierAndParameter() {
        val ed = Editor()
        ed.activeScalar = ed.doc.newParameter("t", 10.0.mm)
        ed.setTool(Tools.WALL)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 2.0))
        ed.finishPath()

        ed.selectAt(Vec2(50.0, 5.0)) // the footprint, picked on one of its faces
        assertTrue(ed.deleteSelection())

        assertTrue(ed.doc.thickNetworks.isEmpty())
        assertEquals(1, ed.doc.orthoPaths.single().legCount, "the carrier is upstream of the thick path")
        assertTrue(ed.doc.scalars.any { it.name == "t" }, "a parameter is a panel entity, not a dependent")
        assertRoundTrips(ed)
    }

    /**
     * Interval features are independent of each other (OP-21). While an opening *regenerated* the wall's
     * faces, their count depended on every opening already present, so dropping one forced dropping every
     * later one; an interval now creates no geometry, so only the thick path it names cascades.
     */
    @Test
    fun deletingAThickPathTakesItsOpeningsAndNothingElse() {
        val ed = Editor()
        ed.activeScalar = ed.doc.newParameter("t", 10.0.mm)
        ed.setTool(Tools.WALL)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 2.0))
        ed.finishPath()
        ed.activeScalar = ed.doc.newParameter("w", 20.0.mm)
        ed.setTool(Tools.OPENING)
        ed.click(Vec2(30.0, 0.0))
        ed.click(Vec2(75.0, 0.0))
        assertEquals(2, ed.doc.thickNetworks.single().intervals.size)

        ed.selectAt(Vec2(50.0, 5.0)) // the footprint
        assertTrue(ed.deleteSelection())

        assertTrue(ed.doc.thickNetworks.isEmpty())
        assertTrue(ed.doc.journal.none { it.kind == "opening" }, "the intervals go with the path they described")
        assertEquals(1, ed.doc.orthoPaths.single().legCount, "the carrier survives")
        assertRoundTrips(ed)
    }

    /** The cascade the other way round: the footprint is downstream of the carrier, so it goes too. */
    @Test
    fun deletingACarrierLegTakesTheThickPathBuiltOverIt() {
        val ed = Editor()
        ed.activeScalar = ed.doc.newParameter("t", 10.0.mm)
        ed.setTool(Tools.WALL)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 2.0))
        ed.click(Vec2(98.0, 60.0))
        ed.finishPath()
        ed.activeScalar = ed.doc.newParameter("w", 20.0.mm)
        ed.setTool(Tools.OPENING)
        ed.click(Vec2(50.0, 0.0))

        ed.selectAt(Vec2(50.0, 0.0)) // the carrier leg itself, under the footprint's centre
        assertEquals(ElementKind.SEGMENT, ed.selection?.kind)
        assertTrue(ed.deleteSelection())

        assertTrue(ed.doc.thickNetworks.isEmpty(), "the footprint is downstream of the carrier vertices")
        assertTrue(ed.doc.journal.none { it.kind == "opening" })
        assertRoundTrips(ed)
    }

    @Test
    fun aDeleteIsItselfOneUndoStep() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(-30.0, 0.0))
        ed.click(Vec2(30.0, 0.0))
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(-30.0, 0.0))
        ed.click(Vec2(30.0, 0.0))
        val before = DocumentFormat.save(ed.doc)

        ed.selectAt(Vec2(0.0, 0.0))
        assertTrue(ed.deleteSelection())
        assertEquals(0, ed.doc.elements.count { it.kind == ElementKind.SEGMENT })

        assertTrue(ed.undo())
        assertEquals(before, DocumentFormat.save(ed.doc), "undo restores the deleted construction")
        assertTrue(ed.redo())
        assertEquals(0, ed.doc.elements.count { it.kind == ElementKind.SEGMENT })
    }
}
