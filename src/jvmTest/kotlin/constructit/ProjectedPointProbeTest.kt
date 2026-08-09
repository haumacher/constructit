package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.PlaneValue
import constructit.core.PointValue
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.Plane3
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.units.Quantity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The reviewer's probe for the projected point (GitHub #14, session 57). The delivery proved the projection
 * follows the **source** (and even the source's own plane tilting). The axis it did not test is the one a
 * stale-plane bug would hide in: the projection must follow the **target plane** when *that* moves — the
 * plane is a live input to the op, so retyping the datum's angle must re-drop the foot onto the new plane,
 * not leave it on the old one. Asserted by two geometry invariants that need no closed form and that a stale
 * plane fails: the foot lies *in* the current plane, and the source-to-foot vector is perpendicular to it.
 */
class ProjectedPointProbeTest {
    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerMove(s)
        pointerDown(s)
        pointerUp(s)
    }

    private fun Editor.type(digits: String) {
        for (c in digits) key(c.toString())
        key("Enter")
    }

    /** The current plane of the named space, as a value. */
    private fun planeOf(
        ed: Editor,
        space: String,
    ): Plane3 {
        val ref = assertNotNull(ed.doc.spaceNamed(space)?.plane, "$space has a plane")
        return ((Evaluator().eval(ref.node) as EvalResult.Ok).value as PlaneValue).plane
    }

    /** The world position of a 2D point [el] of space [space]. */
    private fun worldOf(
        ed: Editor,
        el: Element,
        space: String,
    ): Vec3 {
        val local = ((Evaluator().eval(el.ref.node) as EvalResult.Ok).value as PointValue).p
        return planeOf(ed, space).toWorld(local)
    }

    /** Assert [foot] is the perpendicular foot of [source] on [plane]: in the plane, and normal-aligned. */
    private fun assertPerpendicularFoot(
        source: Vec3,
        foot: Vec3,
        plane: Plane3,
        msg: String,
    ) {
        assertClose(plane.distanceTo(foot), 0.0, 1e-9, "$msg: the foot lies in the plane")
        val d = source - foot
        val inPlane = d - plane.normal.normalized() * d.dot(plane.normal.normalized())
        assertClose(inPlane.length(), 0.0, 1e-9, "$msg: source→foot is perpendicular to the plane")
    }

    /**
     * **The projection follows the target plane when the plane moves.** A fixed plan point, projected onto a
     * datum whose tilt is a parameter: at 90° and again at 55° and again at 120°, the projected point is the
     * true perpendicular foot on the datum *as it now stands* — which is only possible if the plane is a live
     * input, not a value captured when the tool ran.
     */
    @Test
    fun theProjectionFollowsTheTargetPlaneWhenItMoves() {
        val ed = Editor()
        // the point to project, and a line to hinge the datum on
        ed.setTool(Tools.POINT)
        ed.click(Vec2(20.0, 35.0))
        val src = ed.doc.elements.last { it.kind == ElementKind.POINT }
        ed.setTool(Tools.LINE)
        ed.click(Vec2(-50.0, 0.0))
        ed.click(Vec2(50.0, 0.0))

        // a datum on that line at 90°, then project the plan point onto it
        ed.setTool(Tools.SKETCH_PLANE)
        ed.type("90")
        ed.click(Vec2(0.0, 0.0))
        val datum = ed.doc.activeSpace.name
        assertTrue(datum != "plan", "the datum is the active space: $datum")

        // the gesture as designed: pick the source in ITS pane (the plan), then switch to the target and land
        ed.setActiveSpace("plan")
        ed.setTool(Tools.PROJECT_TO_PLANE)
        ed.click(Vec2(20.0, 35.0)) // the source, in the plan where it lives
        ed.setActiveSpace(datum) // crossSpace keeps the pick
        ed.click(Vec2(0.0, 0.0)) // the SIDE slot: land it on the datum
        val proj = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.DERIVED_POINT && it.space == datum }, ed.statusHint)

        val srcWorld = Vec3(20.0, 35.0, 0.0)
        assertPerpendicularFoot(srcWorld, worldOf(ed, proj, datum), planeOf(ed, datum), "at 90°")

        // move the target plane, three ways, and the foot must track it each time
        val angle = assertNotNull(ed.doc.scalars.firstOrNull { it.name.startsWith("angle") }, "the datum's angle is a parameter")
        for (deg in listOf(55.0, 120.0, 90.0)) {
            ed.doc.setParameter(angle, Quantity.deg(deg))
            assertPerpendicularFoot(srcWorld, worldOf(ed, proj, datum), planeOf(ed, datum), "after the datum turned to $deg°")
        }

        // and it still follows the source too, on the moved plane
        ed.doc.setParameter(angle, Quantity.deg(70.0))
        assertNotNull(src.handle, "the source is draggable").drag(Vec2(-15.0, 40.0), Evaluator())
        assertPerpendicularFoot(Vec3(-15.0, 40.0, 0.0), worldOf(ed, proj, datum), planeOf(ed, datum), "source moved on a turned plane")

        val once = DocumentFormat.save(ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "save → load → save byte-equal")
    }

    /**
     * **Projecting a point in space straight down onto the plan drops only its height.** A coil's end stands
     * two pitches above its centre; projected onto the plan (the identity plane, world XY) it must land at
     * the end's own (x, y) — the vertical drop is exactly the rise and nothing else. The identity case stated
     * as a checkable number, and the "any pane" the issue asks for. Driven through the document API so the
     * source is unambiguous (the gesture is exercised in the other test).
     */
    @Test
    fun projectingASpacePointOntoThePlanDropsOnlyItsHeight() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(12.0, 8.0))
        ed.setTool(Tools.HELIX)
        ed.type("20")
        ed.type("12")
        ed.type("2")
        ed.click(Vec2(12.0, 8.0))
        val coil = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.SPACE_CURVE }, ed.statusHint)
        ed.setTool(Tools.KEY_POINTS)
        ed.click(Vec2(32.0, 8.0)) // centre, start, end of the coil
        val end = ed.doc.elements.last { it.kind == ElementKind.DERIVED_POINT && it.inSpace }
        val w = ((Evaluator().eval(end.ref.node) as EvalResult.Ok).value as constructit.core.Point3Value).p
        assertTrue(w.z > 20.0, "the coil end stands two pitches up: $w")

        ed.setActiveSpace("plan")
        val proj = assertNotNull(ed.doc.projectToPlane(end), "the space point projects onto the plan: ${ed.doc.note}")
        val local = ((Evaluator().eval(proj.ref.node) as EvalResult.Ok).value as PointValue).p
        assertClose(local.x, w.x, 1e-9, "the plan foot keeps the source's x")
        assertClose(local.y, w.y, 1e-9, "and its y — the drop onto the plan is purely vertical")
        assertTrue(!proj.inSpace && proj.space == "plan", "and it is an ordinary plan point")

        val once = DocumentFormat.save(ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "save → load → save byte-equal")
    }
}
