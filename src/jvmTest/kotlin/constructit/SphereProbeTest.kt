package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.editor.Camera3
import constructit.editor.Document
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.PlanePerspective
import constructit.editor.Tools
import constructit.geom.Geom3
import constructit.geom.Vec2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **The probe review of the ball** — a sphere as a working part of the drawing, not a party trick.
 *
 * The ball is a revolve of a sketch, so it must build wherever a sketch lives: this one stands on a
 * **vertical datum**, its equator in that plane and its centre 30 mm up. Then the drawing does to it what
 * drawings do — a plan line straight through its middle, the ball ray-picked in the 3D view, cut by the
 * cross-space fence — and a fence through the centre of a sphere must leave **half of it**, which is the kind
 * of number a probe can hold the whole toolchain to. Two undos walk it all back.
 */
class SphereProbeTest {
    private fun whyInvalid(el: Element): String? = (Evaluator().eval(el.ref.node) as? EvalResult.Invalid)?.reason

    @Suppress("UNCHECKED_CAST")
    private fun meshOf(el: Element) = Evaluator().solid(el.ref as SolidRef).mesh

    private fun solids(doc: Document): List<Element> = doc.elements.filter { it.kind == ElementKind.SOLID }

    private fun Editor.click(world: Vec2) {
        val s = assertNotNull(pointing?.toScreen(world) ?: camera.worldToScreen(world), "the point has an image")
        pointerMove(s)
        pointerDown(s)
        pointerUp(s)
    }

    private fun Editor.type(digits: String) {
        for (c in digits) key(c.toString())
        key("Enter")
    }

    @Test
    fun aBallOnADatumIsHalvedByAPlanLineThroughItsCentre() {
        val ed = Editor()
        ed.setTool(Tools.LINE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 0.0))
        val hinge = ed.doc.elements.last { it.kind == ElementKind.LINE }
        assertNotNull(ed.doc.createDatumSpace(hinge, null, "wall"), "the datum stands on the line")

        // the ball: radius typed, centre clicked 40 along and 30 up the wall
        ed.setTool(Tools.SPHERE_R)
        ed.type("25")
        ed.click(Vec2(40.0, 30.0))
        val ball = solids(ed.doc).last()
        assertNull(whyInvalid(ball), "the ball builds on the datum: ${ed.statusHint}")
        val ballMesh = meshOf(ball)
        assertManifold(ballMesh, "a ball whose equator stands in a vertical plane")
        val vBall = Geom3.volume(ballMesh)
        val analytic = 4.0 / 3.0 * kotlin.math.PI * 25.0 * 25.0 * 25.0
        assertTrue(vBall <= analytic && vBall > analytic * 0.99, "inscribed, within the queue's own band: $vBall of $analytic")

        // its world centre, read off the mesh — the plan line must pass under it exactly
        val b = assertNotNull(Geom3.bounds(ballMesh), "the ball has bounds")
        val c = (b.first + b.second) * 0.5
        assertClose(c.z, 30.0, 1e-6, msg = "the centre stands 30 mm up the wall")

        // the fence: a plan line straight through the centre's plan shadow, at a lazy angle
        ed.setActiveSpace(Document.PLAN_SPACE)
        ed.setTool(Tools.LINE)
        ed.click(Vec2(c.x - 60.0, c.y - 10.0))
        ed.click(Vec2(c.x + 60.0, c.y + 10.0))
        val fence = ed.doc.elements.last { it.kind == ElementKind.LINE }

        // cut: the ball ray-picked in the 3D view, the chain and the side in the plan
        val plane = assertNotNull(ed.doc.activePlane3(Evaluator()), "the plan has a plane")
        val perspective = PlanePerspective(plane, Camera3(target = c, distance = 300.0, yaw = 0.6, pitch = 0.5), 800.0, 600.0)
        ed.pointing = perspective
        ed.setTool(Tools.CUT_BY_CHAIN)
        // aim at the centre itself, 30 mm above the plan — its shadow at z = 0 lies below the ball entirely
        run {
            val s = assertNotNull(perspective.toScreenLifted(plane.toLocal(c), c.z), "the centre has an image")
            ed.pointerMove(s)
            ed.pointerDown(s)
            ed.pointerUp(s)
        }
        ed.pointing = null
        run {
            val d = Vec2(120.0, 20.0).let { it * (1.0 / it.length()) }
            val on = Vec2(c.x + d.x * 40.0, c.y + d.y * 40.0)
            ed.click(on)
            ed.click(Vec2(on.x - d.y * 30.0, on.y + d.x * 30.0))
        }
        val half = solids(ed.doc).last()
        assertTrue(half !== ball, "the cut is its own body: ${ed.statusHint}")
        assertNull(whyInvalid(half), "…which builds: ${ed.statusHint}")
        assertManifold(meshOf(half), "the hemisphere the fence leaves")
        val ratio = Geom3.volume(meshOf(half)) / vBall
        assertClose(ratio, 0.5, 0.02, msg = "a fence through the centre of a sphere leaves half of it")

        // and two undos walk it back: first the cut, then the whole ball with its construction
        val elementsBeforeBall = 4
        ed.undo()
        assertEquals(1, solids(ed.doc).size, "the first undo takes the hemisphere")
        ed.undo()
        ed.undo()
        assertEquals(0, solids(ed.doc).size, "…and the ball goes as one gesture")
        assertTrue(
            ed.doc.elements.size <= elementsBeforeBall,
            "…taking its poles, arc and diameter with it: ${ed.doc.elements.map { ed.doc.nameOf(it) }}",
        )
    }
}
