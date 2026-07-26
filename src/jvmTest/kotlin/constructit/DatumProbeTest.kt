package constructit

import constructit.core.Evaluator
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.Geom3
import constructit.geom.Vec2
import constructit.units.mm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Probes over datum planes: the gable roof built purely by clicking (a plan ridge line + a datum +
 * one rectangle), and the tilt wired to a MEASURED angle so geometry drives geometry (OP-4).
 */
class DatumProbeTest {
    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerMove(s)
        pointerDown(s)
        pointerUp(s)
    }

    private fun Editor.type(v: String) {
        v.forEach { key(it.toString()) }
        key("Enter")
    }

    /** The roof that used to need the DSL: ridge line in plan, datum at 40°, one rectangle, extrude. */
    @Test
    fun aGableRoofPanelByClicking() {
        val ed = Editor()
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(-60.0, 0.0))
        ed.click(Vec2(60.0, 0.0)) // the eaves line in plan
        ed.setTool(Tools.SKETCH_PLANE)
        ed.type("40") // tilt 40 degrees about it
        ed.click(Vec2(0.0, 0.0))
        // in the datum's (u,v): u along the eaves, v up the slope — draw the roof panel
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(-60.0, 0.0))
        ed.click(Vec2(60.0, 50.0)) // 120 long, 50 up the slope
        ed.activeScalar = ed.doc.newParameter("t", 4.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(0.0, 0.0)) // a leg of the closed path

        val solid = ed.doc.elements.last { it.kind == ElementKind.SOLID }

        @Suppress("UNCHECKED_CAST")
        val mesh = Evaluator().solid(solid.ref as SolidRef).mesh
        assertManifold(mesh, "roof panel on a 40-degree datum")
        val b = Geom3.bounds(mesh)!!
        // the slope climbs 50·sin40 ≈ 32.14 in z and runs 50·cos40 ≈ 38.30 in plan-y
        assertClose(b.second.z - b.first.z, 50.0 * kotlin.math.sin(Math.toRadians(40.0)) + 4.0 * kotlin.math.cos(Math.toRadians(40.0)), tol = 0.1, msg = "the panel climbs the slope")
        assertClose(b.second.x - b.first.x, 120.0, tol = 1e-6, msg = "full length along the ridge")
        val once = DocumentFormat.save(ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)))
    }

    /** The tilt as CONSTRUCTION: wire the datum's angle to a measured angle between two lines. */
    @Test
    fun aDatumTiltWiredToAMeasuredAngleFollowsTheGeometry() {
        val ed = Editor()
        // two segments meeting at the origin: the measured angle between them
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(60.0, 0.0))
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(50.0, 50.0)) // 45 degrees
        ed.setTool(Tools.ANGLE_LINES)
        ed.click(Vec2(40.0, 0.0))
        ed.click(Vec2(35.0, 35.0))
        val measured = ed.doc.scalars.last { !it.editable }

        // the hinge for the datum, away from the measured pair
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(-20.0, 60.0))
        ed.click(Vec2(80.0, 60.0))
        ed.activeScalar = measured // the datum's angle = the measured angle
        ed.setTool(Tools.SKETCH_PLANE)
        ed.click(Vec2(30.0, 60.0))
        val datum = ed.doc.spaces.last()
        assertClose(ed.doc.spaceAngleDeg(datum)!!, 45.0, tol = 1e-6, msg = "the tilt IS the measurement")

        // rotate the measured pair: back in the PLAN view (the datum tool switched us away)
        assertTrue(ed.setActiveSpace("plan"))
        ed.setTool(Tools.SELECT)
        ed.pointerDown(ed.camera.worldToScreen(Vec2(50.0, 50.0)))
        ed.pointerMove(ed.camera.worldToScreen(Vec2(30.0, 60.0)))
        ed.pointerUp(ed.camera.worldToScreen(Vec2(30.0, 60.0)))
        assertTrue(ed.doc.spaceAngleDeg(datum)!! > 60.0, "the datum follows the measured geometry: ${ed.doc.spaceAngleDeg(datum)}")
        val once = DocumentFormat.save(ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)))
    }
}
