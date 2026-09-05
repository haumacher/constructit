package constructit

import constructit.core.Evaluator
import constructit.core.PointValue
import constructit.dsl.SolidRef
import constructit.dsl.plane
import constructit.dsl.solid
import constructit.dsl.valueOf
import constructit.editor.Document
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.Geom3
import constructit.geom.Vec2
import constructit.units.mm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **Where a sketch space's origin sits** (OP-17, session 32) — the two-layer control the intrinsic frame
 * rule is completed by: an optional **anchor** (a corner of the part's own section on this plane, "a click,
 * not a formula") and an in-plane **(dx, dy)** pair of ordinary parameters.
 *
 * Both layers are *nodes*, which is what the tests below are really about: the anchor tracks the geometry it
 * names, the offsets are panel parameters like any other (retype them, wire them), and moving the origin of a
 * space that already carries a drawing **translates that drawing** — the parametrically correct reading, and
 * the way a whole sketch is moved on its face.
 */
class SpaceOriginTest {
    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerDown(s)
        pointerUp(s)
    }

    private fun Editor.drag(
        from: Vec2,
        to: Vec2,
    ) {
        pointerDown(camera.worldToScreen(from))
        pointerMove(camera.worldToScreen(to))
        pointerUp(camera.worldToScreen(to))
    }

    private fun Editor.type(digits: String) {
        for (c in digits) key(c.toString())
        key("Enter")
    }

    private fun Editor.at(el: Element): Vec2 =
        assertNotNull((Evaluator().valueOf(el.ref) as? PointValue)?.p, "${doc.nameOf(el)} is a point")

    /** The 80 × 50 plate, 20 thick, with a sketch space on its front face (u ∈ −40..40, v ∈ 0..20). */
    private fun plateOnFace(): Editor {
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

    /** Arm *Space origin* and anchor on the section corner nearest [corner]. */
    private fun Editor.anchorAt(corner: Vec2) {
        setTool(Tools.SPACE_ORIGIN)
        click(corner)
    }

    private fun roundTrips(ed: Editor): Document {
        val once = DocumentFormat.save(ed.doc)
        val back = DocumentFormat.load(once)
        assertEquals(once, DocumentFormat.save(back), "save -> load -> save must be byte-equal:\n$once")
        return back
    }

    // ---- 1. the anchor: a corner of the face ----

    /**
     * Anchoring on the left end of the picked edge moves the origin there, so every coordinate on the plane
     * is measured from that corner — the face then covers u ∈ 0..80 instead of −40..40.
     */
    @Test
    fun anchoringOnACornerMeasuresFromThatCorner() {
        val ed = plateOnFace()
        ed.anchorAt(Vec2(-40.0, 0.0))
        assertEquals(0, ed.activeSpace.originCorner, "the picked corner is recorded by index: ${ed.statusHint}")

        val p = Evaluator().plane(assertNotNull(ed.activeSpace.plane))
        assertClose(p.origin.x, 0.0, msg = "the origin *is* that corner, in the world")
        assertClose(p.origin.y, 0.0)
        assertClose(p.origin.z, 0.0)
        assertClose(p.u.x, 1.0, msg = "the axes are untouched — an anchor moves the origin, nothing else")
        assertClose(p.v.z, 1.0)

        val r = assertNotNull(ed.doc.faceOutline(ed.activeSpace, Evaluator()))
        assertClose(r[0].x, 0.0, msg = "the face now runs 0..80 in u")
        assertClose(r[1].x, 80.0)
        assertClose(r[2].y, 20.0, msg = "...and still 0..20 in v")
        roundTrips(ed)

        // one gesture, one undo — and the document is rebuilt, so the space is looked up again
        assertTrue(ed.undo(), "undo the anchoring")
        val back = assertNotNull(ed.doc.spaceNamed("face1"))
        assertEquals(null, back.originCorner, "the origin went back to the frame's own")
        assertClose(Evaluator().plane(assertNotNull(back.plane)).origin.x, 40.0, msg = "...which is the edge's midpoint")
    }

    /**
     * The anchor is a **node**, so it tracks: drag the plate's corner in the plan and the space's origin goes
     * with it — and so does everything drawn on the plane, because the frame moved rigidly.
     */
    @Test
    fun theAnchorTracksTheCornerItNames() {
        val ed = plateOnFace()
        ed.setTool(Tools.CIRCLE_R)
        ed.type("4")
        ed.click(Vec2(-15.0, 12.0))
        val circle = ed.doc.elements.last { it.kind == ElementKind.CIRCLE }
        ed.anchorAt(Vec2(-40.0, 0.0))
        val space = ed.activeSpace

        // the drawing kept its numbers and therefore moved in the world: the frame is what changed
        val ev = Evaluator()
        val moved = Evaluator().plane(assertNotNull(space.plane)).toWorld(Vec2(-15.0, 12.0))
        assertClose(moved.x, -15.0, msg = "the circle now sits 15 mm to the left of the corner it is measured from")
        assertClose(moved.z, 12.0)
        assertNotNull(ev.valueOf(circle.ref))

        // now drag the plate's corner: the anchor follows the geometry, the frame follows the anchor
        assertTrue(ed.setActiveSpace(Document.PLAN_SPACE))
        ed.setTool(Tools.SELECT)
        ed.drag(Vec2(0.0, 0.0), Vec2(-20.0, -10.0))
        val p = Evaluator().plane(assertNotNull(space.plane))
        assertClose(p.origin.x, -20.0, msg = "the origin rode the corner it is anchored on")
        assertClose(p.origin.y, -10.0)
        assertClose(p.origin.z, 0.0)
    }

    /**
     * **Re-anchoring a space that already carries a sketch translates the sketch** — stated as a feature: the
     * 2D numbers keep their meaning and the frame they are read in moves, which is how a whole drawing is
     * shifted on its face. The drill built from it moves with it, and the replay is byte-equal.
     */
    @Test
    fun reAnchoringMovesTheWholeSketchAndReplaysByteEqual() {
        val ed = plateOnFace()
        ed.setTool(Tools.CIRCLE_R)
        ed.type("3")
        ed.click(Vec2(-10.0, 10.0))
        val before = Evaluator().plane(assertNotNull(ed.activeSpace.plane)).toWorld(Vec2(-10.0, 10.0))
        assertClose(before.x, 30.0, msg = "10 mm left of the edge's midpoint at x = 40")

        ed.anchorAt(Vec2(40.0, 0.0)) // the *other* end of the picked edge
        val after = Evaluator().plane(assertNotNull(ed.activeSpace.plane)).toWorld(Vec2(-10.0, 10.0))
        assertClose(after.x, 70.0, msg = "the same drawing, now measured from the right-hand corner at x = 80")
        assertClose(after.z, 10.0, msg = "and unmoved in v, which the anchor shares with the frame")

        val reloaded = roundTrips(ed)
        val space = assertNotNull(reloaded.spaceNamed("face1"))
        assertEquals(1, space.originCorner, "the anchor is a recorded choice, replayed verbatim")
        val p = Evaluator().plane(assertNotNull(space.plane))
        assertClose(p.origin.x, 80.0, msg = "and the reloaded frame stands on the same corner")
    }

    // ---- 2. the offsets: ordinary parameters ----

    /**
     * The second layer: an in-plane (dx, dy) typed with the gesture. They are **ordinary panel parameters** —
     * retyping one slides the origin, and everything drawn on the plane with it.
     */
    @Test
    fun theOffsetsAreOrdinaryParametersAndSlideTheFrame() {
        val ed = plateOnFace()
        ed.setTool(Tools.SPACE_ORIGIN)
        ed.type("10")
        ed.type("5")
        ed.click(Vec2(-40.0, 0.0))
        val space = ed.activeSpace
        val p = Evaluator().plane(assertNotNull(space.plane))
        assertClose(p.origin.x, 10.0, msg = "the corner at x = 0, plus dx = 10")
        assertClose(p.origin.z, 5.0, msg = "...and dy = 5 up the face")

        val dx = assertNotNull(space.originDxEntry, "dx is a panel parameter")
        assertTrue(ed.doc.scalars.any { it === dx }, "listed in the panel like any other")
        ed.doc.setParameter(dx, 30.0.mm)
        assertClose(Evaluator().plane(assertNotNull(space.plane)).origin.x, 30.0, msg = "retyping it slides the frame")

        // ...and it wires, which is what makes "the same offset as that one" a construction (OP-5)
        val other = ed.doc.newParameter("edgeGap", 12.0.mm)
        assertTrue(ed.doc.wireParameter(dx, other), "dx can be wired to another parameter")
        assertClose(Evaluator().plane(assertNotNull(space.plane)).origin.x, 12.0, msg = "and then follows it")
        ed.doc.setParameter(other, 18.0.mm)
        assertClose(Evaluator().plane(assertNotNull(space.plane)).origin.x, 18.0, msg = "...at every value")
    }

    /** Both layers at once, replayed: the anchor *and* the offsets come back exactly. */
    @Test
    fun anAnchorWithOffsetsReplaysByteEqual() {
        val ed = plateOnFace()
        ed.setTool(Tools.SPACE_ORIGIN)
        ed.type("10")
        ed.type("5")
        ed.click(Vec2(-40.0, 0.0))
        val reloaded = roundTrips(ed)
        val space = assertNotNull(reloaded.spaceNamed("face1"))
        assertEquals(0, space.originCorner)
        val p = Evaluator().plane(assertNotNull(space.plane))
        assertClose(p.origin.x, 10.0, msg = "the reloaded frame carries both layers")
        assertClose(p.origin.z, 5.0)
    }

    // ---- 3. a drill through the moved frame, and the refusal ----

    /** A cut drawn in anchored coordinates lands where those coordinates say, and replays byte-equal. */
    @Test
    fun aDrillThroughTheAnchoredFrameLandsWhereItsCoordinatesSay() {
        val ed = plateOnFace()
        ed.anchorAt(Vec2(-40.0, 0.0))
        ed.setTool(Tools.CIRCLE_R)
        ed.type("2.5")
        ed.click(Vec2(25.0, 12.0))
        ed.setTool(Tools.CUT)
        ed.type("10")
        ed.click(Vec2(27.5, 12.0))
        val solids = ed.doc.elements.filter { it.kind == ElementKind.SOLID }
        assertEquals(3, solids.size, "the drill and the cut part: ${ed.statusHint}")

        @Suppress("UNCHECKED_CAST")
        val mesh = Evaluator().solid(solids[1].ref as SolidRef).mesh
        val bb = assertNotNull(Geom3.bounds(mesh))
        assertClose((bb.first.x + bb.second.x) / 2, 25.0, tol = 0.02, msg = "25 mm from the corner it is measured from")
        assertClose((bb.first.z + bb.second.z) / 2, 12.0, tol = 0.02)
        assertClose(
            bb.first.y,
            -Geom3.TOOL_STEP_MM,
            tol = 1e-9,
            msg = "it starts one micron off the face, in the air (GitHub #33)",
        )
        assertClose(bb.second.y, 10.0, tol = 1e-9, msg = "and still drills *into* the material")
        roundTrips(ed)
    }

    /**
     * A point **drawn on the plane** cannot define the frame, and the refusal says why: such a point rides
     * the frame it would be defining. The corners of the part's section do not, which is why they can.
     */
    @Test
    fun aPointDrawnOnThePlaneIsRefusedAsAnAnchorWithTheReason() {
        val ed = plateOnFace()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(-5.0, 6.0))
        ed.setTool(Tools.SPACE_ORIGIN)
        ed.click(Vec2(-5.0, 6.0))
        assertEquals(null, ed.activeSpace.originCorner, "nothing was anchored")
        assertTrue(ed.statusHint.contains("moves with the frame"), "and it says why: ${ed.statusHint}")
        assertClose(Evaluator().plane(assertNotNull(ed.activeSpace.plane)).origin.x, 40.0, msg = "the frame is untouched")
    }

    /** The plan has no origin to move: it *is* what everything is measured from, and it says so. */
    @Test
    fun thePlanRefusesToMoveItsOwnOrigin() {
        val ed = Editor()
        assertFalse(ed.doc.setSpaceOrigin(ed.doc.planSpace, null), "the plan declines")
        assertTrue(assertNotNull(ed.doc.takeNote()).contains("world origin"), "with the reason")
    }

    // ---- 4. the same mechanism on a datum plane ----

    /**
     * The origin control is **generic over sketch spaces**, not a face feature: a datum plane's section has
     * corners too, and anchoring on one moves that plane's origin the same way, through the same nodes.
     */
    @Test
    fun aDatumPlaneTakesTheSameAnchor() {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(80.0, 50.0))
        ed.activeScalar = ed.doc.newParameter("h", 20.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(40.0, 0.0))
        // a datum plane standing on the front edge, cutting the plate
        ed.setTool(Tools.SKETCH_PLANE)
        ed.click(Vec2(40.0, 0.0))
        assertTrue(ed.activeSpace.isDatum, "a datum: ${ed.statusHint}")
        val section = assertNotNull(ed.doc.spaceSection(ed.activeSpace, Evaluator()))
        val corner = assertNotNull(section.cornerPoints.firstOrNull { it.x < 0.5 && it.y < 0.5 }, "a corner at the plate's own corner")

        ed.setTool(Tools.SPACE_ORIGIN)
        ed.click(corner)
        assertNotNull(ed.activeSpace.originCorner, "the datum's origin is anchored too: ${ed.statusHint}")
        val p = Evaluator().plane(assertNotNull(ed.activeSpace.plane))
        assertClose(p.origin.x, 0.0, msg = "and it stands on the corner that was clicked")
        assertClose(p.origin.z, 0.0)
        roundTrips(ed)
    }
}
