package constructit

import constructit.core.Evaluator
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.SvgDrawTarget
import constructit.editor.Tools
import constructit.geom.Geom3
import constructit.geom.MeshBool
import constructit.geom.Vec2
import constructit.units.mm
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Probes over OP-23 patterns: a dimension across members stays single but live; the orbit rides a
 * reference drag; and a pattern of circles in a FACE SPACE with one Cut — six chained bores from
 * two clicks, the tip rule sequencing the fan.
 */
class PatternProbeTest {
    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerMove(s)
        pointerDown(s)
        pointerUp(s)
    }

    private fun Editor.drag(
        from: Vec2,
        to: Vec2,
    ) {
        setTool(Tools.SELECT)
        pointerDown(camera.worldToScreen(from))
        pointerMove(camera.worldToScreen(to))
        pointerUp(camera.worldToScreen(to))
    }

    private fun Editor.type(v: String) {
        v.forEach { key(it.toString()) }
        key("Enter")
    }

    private fun svg(ed: Editor): String {
        val t = SvgDrawTarget()
        ed.render(t)
        return t.svg()
    }

    /** A dimension between two ring members is measurement, not geometry: single, and live. */
    @Test
    fun aDimensionAcrossMembersStaysSingleAndLive() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(60.0, 0.0))
        ed.count = 6
        ed.setTool(Tools.PATTERN_CIRCULAR)
        ed.click(Vec2(0.0, 0.0)) // centre
        ed.click(Vec2(60.0, 0.0)) // reference
        val ringPoints = ed.doc.elements.count { it.kind == ElementKind.POINT || it.kind == ElementKind.DERIVED_POINT }
        assertEquals(7, ringPoints, "centre + reference + 5 derived ring copies")

        ed.setTool(Tools.DIM_LINEAR)
        ed.click(Vec2(60.0, 0.0)) // ref0
        ed.click(Vec2(30.0, 51.96)) // ref1 (60° around)
        ed.click(Vec2(55.0, 35.0)) // where the line sits
        assertEquals(1, ed.doc.elements.count { it.isAnnotation }, "a dimension does not orbit")
        assertTrue(svg(ed).contains(">60 mm<"), "hexagon side = radius")

        // drag the reference outward: every ring point follows; the dimension re-reads
        ed.drag(Vec2(60.0, 0.0), Vec2(80.0, 0.0))
        assertTrue(svg(ed).contains(">80 mm<"), "the orbit and the measurement follow the reference")
        val once = DocumentFormat.save(ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)))
    }

    /** The mechanical jackpot: a face-space ring of circles, ONE Cut → chained bores. */
    @Test
    fun aFaceSpacePatternCutsABoltCircleOfPockets() {
        assumeTrue(MeshBool.available, "mesh boolean engine unavailable")
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(120.0, 80.0))
        ed.activeScalar = ed.doc.newParameter("t", 30.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(60.0, 0.0))
        ed.setTool(Tools.SKETCH_ON_FACE)
        ed.click(Vec2(60.0, 0.0)) // the front face: u along 120, v down 30

        // a ring of 4 small circles around the face centre
        ed.setTool(Tools.POINT)
        ed.click(Vec2(60.0, 15.0)) // centre of the face
        ed.click(Vec2(70.0, 15.0)) // reference
        ed.count = 4
        ed.setTool(Tools.PATTERN_CIRCULAR)
        ed.click(Vec2(60.0, 15.0))
        ed.click(Vec2(70.0, 15.0))
        ed.setTool(Tools.CIRCLE_R)
        ed.type("3")
        ed.click(Vec2(70.0, 15.0)) // circle on ref0 — should orbit to 4 circles
        val circles = ed.doc.elements.count { it.kind == ElementKind.CIRCLE }
        assertEquals(4, circles, "the circle orbits the ring: ${ed.statusHint}")

        // ONE Cut on circle@0: the fan must chain four pockets (each cut targeting the tip)
        ed.setTool(Tools.CUT)
        ed.type("8")
        ed.click(Vec2(73.0, 15.0))
        val solids = ed.doc.elements.filter { it.kind == ElementKind.SOLID }
        val tip = solids.last()

        @Suppress("UNCHECKED_CAST")
        val mesh = Evaluator().solid(tip.ref as SolidRef).mesh
        assertManifold(mesh, "bolt circle of pockets")
        val expected = 120.0 * 80.0 * 30.0 - 4.0 * Math.PI * 9.0 * 8.0
        assertTrue(
            Math.abs(Geom3.volume(mesh) - expected) / expected < 2e-3,
            "four pockets cut: volume ${Geom3.volume(mesh)} vs $expected (status: ${ed.statusHint})",
        )
        val once = DocumentFormat.save(ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)))
    }
}
