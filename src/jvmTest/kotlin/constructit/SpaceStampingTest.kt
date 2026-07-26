package constructit

import constructit.editor.Document
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.Vec2
import constructit.units.mm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **Every element is stamped with the space it was drawn in** (OP-17) — including the ones that are not
 * created by drawing a curve.
 *
 * Reported: an ortho path drawn in a **face** view put its *legs* in the face space and its corner *points* in
 * the plan. The cause was one creation route that built its `Element` by hand instead of going through
 * `Document.add`, which is where the stamp is applied — so every point that route makes (a path corner, a
 * rider, a ratio point, an arc break's split point) was left in the plan whatever space it was drawn in. In a
 * face view those points are then invisible and unpickable (one canvas shows one space), while the plan shows a
 * scatter of stray points whose coordinates mean nothing there and which drag the face's geometry about.
 *
 * The fix is at the seam, so this suite is the *audit*: one assertion per creation route, drawn on a face.
 */
class SpaceStampingTest {
    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerMove(s)
        pointerDown(s)
        pointerUp(s)
    }

    /** An 80 × 50 plate, 20 thick, with a sketch space on its front side face — OP-17's own fixture. */
    private fun onFace(): Editor {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(80.0, 50.0))
        ed.activeScalar = ed.doc.newParameter("thickness", 20.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(40.0, 0.0))
        ed.setTool(Tools.SKETCH_ON_FACE)
        ed.click(Vec2(40.0, 0.0))
        assertEquals("face1", ed.activeSpace.name, "the view is the face: ${ed.statusHint}")
        return ed
    }

    /** Everything created since [from], with the space each one carries. */
    private fun madeSince(
        doc: Document,
        from: Int,
    ): List<Element> = doc.elements.drop(from)

    private fun assertAllIn(
        doc: Document,
        made: List<Element>,
        space: String,
        what: String,
    ) {
        assertTrue(made.isNotEmpty(), "$what created nothing")
        val strays = made.filter { it.space != space }
        assertTrue(
            strays.isEmpty(),
            "$what left ${strays.map { "${doc.nameOf(it)} (${it.kind}) in ${it.space}" }} outside $space",
        )
    }

    // ---- the reported case ----

    /**
     * **The user's gesture**: an ortho path drawn on a face. Legs *and* corners belong to the face, and the
     * plan is left with exactly what it had.
     */
    @Test
    fun anOrthoPathDrawnOnAFaceIsWhollyInTheFaceSpace() {
        val ed = onFace()
        val before = ed.doc.elements.size
        val plan = ed.doc.elements.filter { it.space == Document.PLAN_SPACE }.size
        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(10.0, 5.0))
        ed.click(Vec2(50.0, 5.0))
        ed.click(Vec2(50.0, 15.0))
        ed.finishPath()

        val made = madeSince(ed.doc, before)
        assertTrue(made.any { it.kind == ElementKind.ON_CURVE }, "the corners are on-curve elements")
        assertTrue(made.any { it.kind == ElementKind.SEGMENT }, "and the legs are segments")
        assertAllIn(ed.doc, made, "face1", "an ortho path on a face")
        assertEquals(plan, ed.doc.elements.filter { it.space == Document.PLAN_SPACE }.size, "the plan is untouched")

        // and it survives the file: which space a step was built in is carried by ordering (OP-18)
        val text = DocumentFormat.save(ed.doc)
        val fresh = DocumentFormat.load(text)
        assertEquals(ed.doc.elements.map { it.space }, fresh.elements.map { it.space }, "the reload agrees")
        assertAllIn(fresh, madeSince(fresh, before), "face1", "the reloaded path")
        assertEquals(text, DocumentFormat.save(fresh), "save -> load -> save byte-equal")
    }

    // ---- the audit: every other route that creates a point without drawing a curve ----

    @Test
    fun aRiderDrawnOnAFaceBelongsToTheFace() {
        val ed = onFace()
        ed.setTool(Tools.CIRCLE_R)
        for (c in "10") ed.key(c.toString())
        ed.key("Enter")
        ed.click(Vec2(25.0, 8.0))
        val before = ed.doc.elements.size
        ed.setTool(Tools.POINT_ON_CIRCLE)
        ed.click(Vec2(35.0, 8.0))
        assertAllIn(ed.doc, madeSince(ed.doc, before), "face1", "a point on a circle")
    }

    @Test
    fun aRatioPointDrawnOnAFaceBelongsToTheFace() {
        val ed = onFace()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(10.0, 5.0))
        ed.click(Vec2(60.0, 5.0))
        val before = ed.doc.elements.size
        ed.setTool(Tools.MIDPOINT)
        for (c in ".25") ed.key(c.toString())
        ed.key("Enter")
        ed.click(Vec2(10.0, 5.0))
        ed.click(Vec2(60.0, 5.0))
        assertAllIn(ed.doc, madeSince(ed.doc, before), "face1", "a ratio point")
    }

    @Test
    fun aBreakOnAFaceLeavesEveryPieceAndItsSplitPointOnTheFace() {
        val ed = onFace()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(10.0, 5.0))
        ed.click(Vec2(60.0, 5.0))
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(10.0, 5.0))
        ed.click(Vec2(60.0, 5.0))
        val before = ed.doc.elements.size
        ed.setTool(Tools.BREAK_LEG)
        ed.click(Vec2(35.0, 5.0))
        assertAllIn(ed.doc, madeSince(ed.doc, before), "face1", "a break")
    }

    @Test
    fun keyPointsFilletChamferAndDimensionsOnAFaceBelongToTheFace() {
        val ed = onFace()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(10.0, 5.0))
        ed.click(Vec2(60.0, 5.0))
        ed.click(Vec2(60.0, 15.0))
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(10.0, 5.0))
        ed.click(Vec2(60.0, 5.0))
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(60.0, 5.0))
        ed.click(Vec2(60.0, 15.0))

        var before = ed.doc.elements.size
        ed.setTool(Tools.FILLET)
        for (c in "3") ed.key(c.toString())
        ed.key("Enter")
        ed.click(Vec2(40.0, 5.0))
        ed.click(Vec2(60.0, 10.0))
        assertAllIn(ed.doc, madeSince(ed.doc, before), "face1", "a fillet")

        before = ed.doc.elements.size
        ed.setTool(Tools.KEY_POINTS)
        ed.click(Vec2(40.0, 5.0))
        assertAllIn(ed.doc, madeSince(ed.doc, before), "face1", "key points")

        before = ed.doc.elements.size
        ed.setTool(Tools.DIM_LINEAR)
        ed.click(Vec2(10.0, 5.0))
        ed.click(Vec2(60.0, 5.0))
        ed.click(Vec2(35.0, 12.0))
        assertAllIn(ed.doc, madeSince(ed.doc, before), "face1", "a linear dimension")
    }

    @Test
    fun anArrayOnAFaceCopiesIntoTheFaceAndACopyKeepsItsOriginalsSpace() {
        val ed = onFace()
        ed.setTool(Tools.CIRCLE_R)
        for (c in "5") ed.key(c.toString())
        ed.key("Enter")
        ed.click(Vec2(15.0, 8.0))
        ed.setTool(Tools.POINT)
        ed.click(Vec2(15.0, 8.0))
        ed.click(Vec2(35.0, 8.0))
        val before = ed.doc.elements.size
        ed.setTool(Tools.ARRAY_LINEAR)
        for (c in "3") ed.key(c.toString())
        ed.key("Enter")
        ed.click(Vec2(20.0, 8.0)) // the circle
        ed.click(Vec2(15.0, 8.0)) // from
        ed.click(Vec2(35.0, 8.0)) // to
        assertAllIn(ed.doc, madeSince(ed.doc, before), "face1", "a linear array")
    }

    /** Drawing in the plan stamps nothing at all — the default, and the file mentions no space. */
    @Test
    fun aPlanDrawingIsUnchangedByAnyOfThis() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(60.0, 0.0))
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(60.0, 0.0))
        ed.setTool(Tools.POINT_ON_LINE)
        ed.click(Vec2(30.0, 0.0))
        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(0.0, 20.0))
        ed.click(Vec2(40.0, 20.0))
        ed.finishPath()
        assertTrue(ed.doc.elements.all { it.space == Document.PLAN_SPACE }, "everything is in the plan")
        assertTrue(!DocumentFormat.save(ed.doc).contains("space"), "and the file says nothing about spaces")
    }
}
