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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Probe on the issue-9 package, composing the ancestor context with two packages it never met: **one
 * parallel plane crossing a loft pyramid AND an elliptic prism** (OP-24), a segment bridging an input
 * taken from each — and the bridge following the pyramid's height parameter **analytically**, because a
 * section corner is a node, not a coordinate.
 */
class PlaneAncestorProbeTest {
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

    @Test
    fun aBridgeBetweenTwoAncestorsSectionsFollowsTheApexParameter() {
        val ed = Editor()

        // ancestor 1: the pyramid, its height a named parameter
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(60.0, 60.0))
        val h = ed.doc.newParameter("h", 90.0.mm)
        ed.activeScalar = h
        ed.setTool(Tools.EXTRUDE_TO_POINT)
        ed.click(Vec2(30.0, 0.0))
        ed.click(Vec2(30.0, 30.0))

        // ancestor 2: the elliptic prism (a = 40, b = 20 about (150, 30))
        ed.setTool(Tools.ELLIPSE)
        ed.click(Vec2(150.0, 30.0))
        ed.click(Vec2(190.0, 30.0))
        ed.click(Vec2(150.0, 50.0))
        ed.setTool(Tools.EXTRUDE)
        ed.type("80")
        ed.click(Vec2(190.0, 30.0))
        assertEquals(2, ed.doc.elements.count { it.kind == ElementKind.SOLID }, ed.statusHint)

        // one plane at z = 30, no solid picked: BOTH sections are its input geometry
        ed.setTool(Tools.PLANE_AT_HEIGHT)
        ed.type("30")
        ed.click(Vec2(300.0, 300.0))
        assertTrue(!ed.activeSpace.isPlan, "the new plane is the active space: ${ed.statusHint}")

        // the bridge: from the pyramid's section corner (at z=30, h=90: 60·(1−30/90) → the 40×40 square,
        // corner (10,10)) to the ellipse section's rightmost point (190, 30)
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(10.0, 10.0))
        ed.click(Vec2(190.0, 30.0))
        val bridge = ed.doc.elements.last { it.kind == ElementKind.SEGMENT }

        fun seg() = assertNotNull(Evaluator().valueOf(bridge.ref) as? SegmentValue).seg
        assertClose(seg().a.x, 10.0, tol = 1e-9, msg = "anchored on the pyramid's section corner")
        assertClose(seg().a.y, 10.0, tol = 1e-9, msg = "anchored on the pyramid's section corner")
        assertClose(seg().b.x, 190.0, tol = 1e-9, msg = "…and on the ellipse's section")

        // the apex drops to 60: at z = 30 the section square is 30×30 about (30,30) — corner (15,15).
        // The bridge END on the pyramid must follow ANALYTICALLY; the ellipse end must not move.
        ed.doc.setParameter(h, 60.0.mm)
        assertClose(seg().a.x, 15.0, tol = 1e-9, msg = "the section corner re-derived, not remembered")
        assertClose(seg().a.y, 15.0, tol = 1e-9, msg = "the section corner re-derived, not remembered")
        assertClose(seg().b.x, 190.0, tol = 1e-9, msg = "the other ancestor's input is untouched")

        // …and past the apex (z = 30 ≥ h = 25), the pyramid's section is gone: the bridge goes invalid
        // WITH a reason, and heals when the apex rises again (OP-3)
        ed.doc.setParameter(h, 25.0.mm)
        val why = Evaluator().eval(bridge.ref.node)
        assertTrue(why is constructit.core.EvalResult.Invalid, "no section above the apex: $why")
        ed.doc.setParameter(h, 90.0.mm)
        assertClose(seg().a.x, 10.0, tol = 1e-9, msg = "healed at the original height")

        // the whole cross-ancestor story replays byte-equal
        val once = DocumentFormat.save(ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "two ancestors, one plane, one bridge — byte-equal")
    }
}
