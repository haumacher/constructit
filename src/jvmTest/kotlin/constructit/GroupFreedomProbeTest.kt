package constructit

import constructit.core.CircleValue
import constructit.core.Evaluator
import constructit.core.PointValue
import constructit.dsl.valueOf
import constructit.editor.CreateMode
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.Vec2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Probes the per-kind freedom capture under ROTATION: the user's circle-on-rider figure turned 90°
 * (radius must be preserved through the re-anchored rider and the relative offset), and a ratio
 * point staying at its fraction — dimensionless, hence rotation-rigid.
 */
class GroupFreedomProbeTest {
    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerMove(s)
        pointerDown(s)
        pointerUp(s)
    }

    private fun radiusOf(ed: Editor): Double {
        val c = ed.doc.elements.first { it.kind == ElementKind.CIRCLE }
        return (Evaluator().valueOf(c.ref) as CircleValue).circle.radius
    }

    @Test
    fun theUsersFigurePlacedAndTurnedKeepsItsRadiusAndItsRider() {
        val ed = Editor()
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(-60.0, 0.0))
        ed.click(Vec2(60.0, 0.0))
        ed.setTool(Tools.POINT_ON_LINE)
        ed.click(Vec2(-10.0, 0.0)) // the rider — future circle centre
        ed.setTool(Tools.CIRCLE)
        ed.click(Vec2(-10.0, 0.0)) // centre = the rider
        ed.click(Vec2(15.0, 0.0)) // radius point, then made relative
        ed.setTool(Tools.MAKE_RELATIVE)
        ed.click(Vec2(15.0, 0.0))
        ed.click(Vec2(-10.0, 0.0))
        assertClose(radiusOf(ed), 25.0)

        // group everything with the default-ticked closure, place, and TURN 90°
        ed.setTool(Tools.SELECT)
        ed.pointerDown(ed.camera.worldToScreen(Vec2(-80.0, -40.0)))
        ed.pointerMove(ed.camera.worldToScreen(Vec2(80.0, 40.0)))
        ed.pointerUp(ed.camera.worldToScreen(Vec2(80.0, 40.0)))
        val d = assertNotNull(ed.beginCreate(CreateMode.GROUP))
        d.name = "fig"
        assertTrue(ed.confirmCreate(), "the closure-ticked group creates")
        val g = ed.doc.groups.last()
        // the dialog's frame tick is on by default, so confirming placed it too (OP-16)
        assertTrue(g.placed, "and places: ${ed.statusHint}")
        ed.setTool(Tools.SELECT)
        ed.click(Vec2(-60.0, 0.0))
        var angleIdx = ed.selectionFields().indexOfFirst { it.label == "angle" }
        if (angleIdx < 0) {
            println("DIAG: label=${ed.selectionLabel()} status=${ed.statusHint} count=${ed.selectionCount}")
            ed.click(Vec2(-60.0, 0.0)) // maybe the click-cycle reached the member alone; cycle back
            println("DIAG2: label=${ed.selectionLabel()} fields=${ed.selectionFields().map { it.label }}")
            angleIdx = ed.selectionFields().indexOfFirst { it.label == "angle" }
        }
        assertTrue(angleIdx >= 0, "frame fields: ${ed.selectionFields().map { it.label }}")
        assertTrue(ed.writeSelectionField(angleIdx, 90.0))

        assertClose(radiusOf(ed), 25.0, msg = "the radius survives the turn")
        // the segment is vertical now; the rider centre must sit ON it
        val centre = ed.doc.elements.first { it.kind == ElementKind.ON_CURVE }
        val cp = (Evaluator().valueOf(centre.ref) as PointValue).p
        val seg = ed.doc.elements.first { it.kind == ElementKind.SEGMENT }
        val sv = (Evaluator().valueOf(seg.ref) as constructit.core.SegmentValue).seg
        assertClose(sv.a.x, sv.b.x, msg = "the segment turned vertical")
        assertClose(cp.x, sv.a.x, msg = "the rider rides the turned segment")

        val once = DocumentFormat.save(ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "placed+turned figure replays")
    }

    @Test
    fun aRatioPointInATurnedGroupKeepsItsFraction() {
        val ed = Editor()
        ed.setTool(Tools.MIDPOINT)
        ed.key(".")
        ed.key("3")
        ed.key("Enter")
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 0.0))
        val ratio = ed.doc.elements.last { it.kind == ElementKind.DERIVED_POINT || it.kind == ElementKind.ON_CURVE }

        fun at(): Vec2 = (Evaluator().valueOf(ratio.ref) as PointValue).p
        assertClose(at().x, 30.0)

        ed.setTool(Tools.SELECT)
        ed.pointerDown(ed.camera.worldToScreen(Vec2(-20.0, -20.0)))
        ed.pointerMove(ed.camera.worldToScreen(Vec2(120.0, 20.0)))
        ed.pointerUp(ed.camera.worldToScreen(Vec2(120.0, 20.0)))
        val d = assertNotNull(ed.beginCreate(CreateMode.GROUP))
        d.name = "r"
        assertTrue(ed.confirmCreate())
        assertTrue(ed.doc.groups.last().placed, "confirming a group gives it a frame: ${ed.statusHint}")
        ed.setTool(Tools.SELECT)
        ed.click(Vec2(0.0, 0.0))
        // the marquee left this point selected, so the click-cycle reaches the *member alone* first; one more
        // click goes back to the whole group, whose fields are the frame's (same cycle as the probe above)
        if (ed.selectionFields().none { it.label == "angle" }) ed.click(Vec2(0.0, 0.0))
        val angleIdx = ed.selectionFields().indexOfFirst { it.label == "angle" }
        assertTrue(angleIdx >= 0, "frame fields: ${ed.selectionFields().map { it.label }}")
        assertTrue(ed.writeSelectionField(angleIdx, 90.0))

        // the pair is vertical; the ratio point sits at 0.3 of the turned span
        val a = at()
        assertClose(a.y - (-20.0 + 0.0), 0.3 * 100.0 - 20.0 + 0.0, tol = 60.0) // sanity only; exact below
        val pts = ed.doc.elements.filter { it.kind == ElementKind.POINT }
        val p0 = (Evaluator().valueOf(pts[0].ref) as PointValue).p
        val p1 = (Evaluator().valueOf(pts[1].ref) as PointValue).p
        assertClose(a.x, p0.x + 0.3 * (p1.x - p0.x), msg = "0.3 of the turned span, x")
        assertClose(a.y, p0.y + 0.3 * (p1.y - p0.y), msg = "0.3 of the turned span, y")
    }
}
