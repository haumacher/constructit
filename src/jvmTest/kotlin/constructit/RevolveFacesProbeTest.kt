package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.Path3Value
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.dsl.valueOf
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.Curve3Element
import constructit.geom.Geom3
import constructit.geom.Vec2
import kotlin.math.PI
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **The probe review of the revolution's faces** — exactness that can be built on, not only admired.
 *
 * A plane 10 mm off a ball's centre now cuts an exact small circle. The probe makes that circle a *working
 * part*: the intersection curve it yields must be carried as the *exact arcs it is* (not a polyline fitted to
 * chords), and a tube swept along it is a ring whose volume Pappus can predict to a fraction of a percent —
 * the sphere face, the section dispatch, the curve-in-space machinery and the sweep all holding hands.
 */
class RevolveFacesProbeTest {
    private fun whyInvalid(el: Element): String? = (Evaluator().eval(el.ref.node) as? EvalResult.Invalid)?.reason

    @Suppress("UNCHECKED_CAST")
    private fun meshOf(el: Element) = Evaluator().solid(el.ref as SolidRef).mesh

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
    fun aBallsSmallCircleIsExactAndCarriesATube() {
        val ed = Editor()
        // the ball: r = 25 about the plan origin
        ed.setTool(Tools.SPHERE_R)
        ed.type("25")
        ed.click(Vec2(0.0, 0.0))
        val ball = ed.doc.elements.last { it.kind == ElementKind.SOLID }
        assertNull(whyInvalid(ball), "the ball builds: ${ed.statusHint}")

        // a vertical plane 10 mm off the centre — its section is the small circle of radius sqrt(625 - 100)
        ed.setTool(Tools.LINE)
        ed.click(Vec2(-60.0, 10.0))
        ed.click(Vec2(60.0, 10.0))
        val hinge = ed.doc.elements.last { it.kind == ElementKind.LINE }
        assertNotNull(ed.doc.createDatumSpace(hinge, null, "cut"), "the datum stands on the line")

        val small = sqrt(25.0 * 25.0 - 10.0 * 10.0)
        // the intersection curve, clicked on the section the plane draws
        ed.setTool(Tools.INTERSECTION_CURVE)
        ed.click(Vec2(0.0, small))
        val curve = ed.doc.elements.last { it.kind == ElementKind.SPACE_CURVE }
        assertNull(whyInvalid(curve), "the small circle is a curve in space: ${ed.statusHint}")
        val path = (Evaluator().valueOf(curve.ref) as Path3Value).path
        assertTrue(
            path.elements.isNotEmpty() && path.elements.all { it is Curve3Element.Arc3 },
            "…and it is carried as the exact arcs it is, not as chords: ${path.elements.map { it::class.simpleName }}",
        )
        val length = path.elements.sumOf { (it as Curve3Element.Arc3).arcLength }
        assertClose(length, 2 * PI * small, 1e-6, msg = "an exact small circle has an exact circumference")

        // a tube along it: Pappus holds the ring to account
        ed.setTool(Tools.TUBE)
        ed.type("3")
        ed.click(Vec2(0.0, small))
        val ring = ed.doc.elements.last { it.kind == ElementKind.SOLID }
        assertTrue(ring !== ball, "the tube is its own body: ${ed.statusHint}")
        assertNull(whyInvalid(ring), "…which builds: ${ed.statusHint}")
        assertManifold(meshOf(ring), "a tube riding a ball's small circle")
        val pappus = 2 * PI * PI * small * 9.0
        val v = Geom3.volume(meshOf(ring))
        assertTrue(v <= pappus && v > pappus * 0.99, "Pappus, from below, within the mesh band: $v of $pappus")
    }
}
