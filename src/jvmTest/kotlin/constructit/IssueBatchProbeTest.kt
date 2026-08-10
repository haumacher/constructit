package constructit

import constructit.core.Evaluator
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.dsl.valueOf
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.Geom3
import constructit.geom.Vec2
import constructit.units.mm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Probe review of the session-71 issue batch — compositions the delivery never saw.
 *
 * The two questions: is the incidence registry **general over derived copies** — the radius point of a
 * *mirrored* circle was put on that circle by the mirror, not by the circle tool, and the tangent must
 * accept it all the same; and does the new corner-bend term stay silent where **no corner exists** — a
 * tube along a rounded rectangle's border is curved legs and tangent joins throughout, which the
 * session-65 law says is corner-free however tight the rounding.
 */
class IssueBatchProbeTest {
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

    /**
     * A circle mirrored across a line: the copy publishes no radius point of its own (a mirror copies the
     * *circle* — its inputs are the original and the axis), so the reachable routes are the ones this probe
     * pins: **key points hand back the copy's centre** as a derived point, and a **rider on the copy** takes
     * the tangent in one click — and the drawing that results survives its file byte-for-byte.
     *
     * (The probe's first draft expected an extracted rim point and filtered for `ElementKind.POINT` only —
     * both wrong: key points are `DERIVED_POINT`, riders are `ON_CURVE`, and a circle publishes its centre
     * alone, with the rim key point recorded as a parked format slice. The delivery refuted the draft with
     * an element dump, which is this fixture's corrected contract.)
     */
    @Test
    fun theMirroredCirclesTangentIsReachable() {
        val ed = Editor()
        ed.setTool(Tools.CIRCLE)
        ed.click(Vec2(-40.0, 0.0))
        ed.click(Vec2(-20.0, 0.0))
        ed.setTool(Tools.LINE)
        ed.click(Vec2(0.0, -50.0))
        ed.click(Vec2(0.0, 50.0))
        ed.setTool(Tools.MIRROR)
        ed.click(Vec2(-40.0, 20.0))
        ed.click(Vec2(0.0, 10.0))
        val mirrored = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.CIRCLE }, "the copy: ${ed.statusHint}")
        assertTrue(ed.doc.elements.count { it.kind == ElementKind.CIRCLE } == 2, "two circles now")
        assertNotNull(mirrored)

        // key points on the copy hand back its centre — a derived point, not a free one
        ed.setTool(Tools.KEY_POINTS)
        ed.click(Vec2(20.0, 0.0))
        val centre =
            ed.doc.elements.mapNotNull { el ->
                (Evaluator().valueOf(el.ref) as? constructit.core.PointValue)?.p?.let { el to it }
            }.lastOrNull { (_, p) -> (p - Vec2(40.0, 0.0)).length() < 1e-9 }
        assertNotNull(centre, "the copy's centre came back as a key point: ${ed.statusHint}")

        // and the rider route reaches the tangent on the copy in one click each
        ed.setTool(Tools.POINT_ON_CIRCLE)
        ed.click(Vec2(20.0, 0.0))
        val before = ed.doc.elements.size
        ed.setTool(Tools.TANGENT_AT)
        ed.click(Vec2(20.0, 0.0))
        assertTrue(
            ed.doc.elements.size > before,
            "the rider on the copy takes the tangent in one click: ${ed.statusHint}",
        )
        val tangent = ed.doc.elements.last()
        assertEquals(ElementKind.LINE, tangent.kind, "a tangent line stands on the mirrored circle")

        val once = DocumentFormat.save(ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "and the drawing survives its file")
    }

    /**
     * A tube along a rounded rectangle's border: curved legs and tangent joins, so the corner-bend term has
     * **no corner to speak about** — the tube builds, watertight and positively volumed, at a section that
     * would certainly refuse on the same rectangle drawn sharp.
     */
    @Test
    fun aTubeAlongARoundedBorderHasNoCornerForTheBendTermToFind() {
        val ed = Editor()
        ed.activeScalar = ed.doc.newParameter("corner", 12.0.mm)
        ed.setTool(Tools.ROUNDED_RECT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(80.0, 50.0))
        ed.setTool(Tools.TUBE)
        ed.type("4")
        ed.click(Vec2(40.0, 0.0))
        val tube = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.SOLID }, "the tube: ${ed.statusHint}")

        @Suppress("UNCHECKED_CAST")
        val mesh = Evaluator().solid(tube.ref as SolidRef).mesh
        assertManifold(mesh, "a rounded route is corner-free however tight the rounding")
        assertTrue(Geom3.volume(mesh) > 0.0, "and positively volumed")

        val once = DocumentFormat.save(ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "and the drawing survives its file")
    }
}
