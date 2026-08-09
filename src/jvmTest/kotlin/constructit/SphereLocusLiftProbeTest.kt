package constructit

import constructit.core.Evaluator
import constructit.core.Point3Value
import constructit.dsl.Point3Ref
import constructit.dsl.valueOf
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.geom.Vec2
import constructit.geom.Vec3
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **The probe review of the sphere locus** — distance in space composed with the drawn world it measures.
 *
 * The user's rounded pillar has a border of segments and true arcs, lifted into space by clicking it. A sphere
 * locus stood on one of its corner points and intersected with that run says *"the place 40 mm along the fence
 * from here"* — and because everything is construction, retyping the fillet radius must slide that place along
 * the reshaped border while the 40 stays exactly 40, and retyping the reach must slide it further out. The
 * probe holds the locus, the lift, the arc-bearing crossing walk and the recorded choice to that sentence,
 * through a save and back.
 */
class SphereLocusLiftProbeTest {
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
    fun aDistanceAlongTheRoundedBorderStaysExactWhenTheFilletsMove() {
        val ed = Editor(DocumentFormat.load(LiftedRunTest.ROUND_PILLAR_CIT))
        val doc = ed.doc
        val corner = Vec3(-51.5, 33.0, 0.0)
        val scalarsBefore = doc.scalars.size

        // the locus: reach 40, centred on the footprint's corner point (shared, so it follows the drawing)
        ed.setTool(Tools_SPHERE_LOCUS)
        ed.type("40")
        ed.click(Vec2(corner.x, corner.y))
        val locus = assertNotNull(doc.elements.lastOrNull { it.kind == ElementKind.SPHERE_LOCUS }, ed.statusHint)

        // the meet: click the locus on its plan outline, then the rounded border, near the left-edge crossing
        ed.setTool(Tools_SPHERE_ON_RUN)
        ed.click(Vec2(corner.x, corner.y + 40.0))
        ed.click(Vec2(corner.x, corner.y - 40.0))
        val meet = assertNotNull(doc.elements.lastOrNull { it.kind == ElementKind.DERIVED_POINT }, ed.statusHint)
        assertNotNull(locus, "…the locus took part")

        fun at(): Vec3 = (Evaluator().valueOf(meet.ref as Point3Ref) as Point3Value).p
        assertClose((at() - corner).length(), 40.0, 1e-9, msg = "the point stands exactly 40 from the corner")

        // reshape the border: the fillets grow, the arcs move, the point slides — the 40 does not
        val fillet = doc.scalars.first { it.name == "radius" }
        ed.setParameter(fillet, 16.0)
        assertClose((at() - corner).length(), 40.0, 1e-9, msg = "…still exactly 40 with the fillets at 16")

        // and the reach itself is live: the typed 40 became a panel freedom of the locus's step
        val reach = doc.scalars.last()
        assertTrue(doc.scalars.size == scalarsBefore + 1 && reach !== fillet, "the typed reach is a panel row")
        ed.setParameter(reach, 55.0)
        assertClose((at() - corner).length(), 55.0, 1e-9, msg = "…and 55 when the locus says 55")

        // the file carries the whole construction: recorded choice, settled text, the same point back
        val before = at()
        var text = DocumentFormat.save(doc)
        repeat(4) {
            val again = DocumentFormat.save(DocumentFormat.load(text))
            if (again == text) return@repeat
            text = again
        }
        val back = DocumentFormat.load(text)
        val meetBack =
            assertNotNull(
                back.elements.lastOrNull { back.nameOf(it) == doc.nameOf(meet) },
                "the meet is in the file: ${text.lines().takeLast(3)}",
            )
        val p = (Evaluator().valueOf(meetBack.ref as Point3Ref) as Point3Value).p
        assertTrue((p - before).length() < 1e-9, "replay lands the same point: $p vs $before")
    }

    private companion object {
        const val Tools_SPHERE_LOCUS = "spherelocus"
        const val Tools_SPHERE_ON_RUN = "sphereonrun"
    }
}
