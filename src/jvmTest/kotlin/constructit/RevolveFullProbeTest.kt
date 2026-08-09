package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.dsl.valueOf
import constructit.editor.Camera3
import constructit.editor.Document
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.PlanePerspective
import constructit.editor.Tools
import constructit.geom.Geom3
import constructit.geom.Vec2
import constructit.geom.Vec3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **The probe review of the completed revolve** — the structural full turn composed with the machinery that
 * landed around it.
 *
 * On the user's plate, the handle profile revolved with nothing typed becomes a complete ring round the
 * plate's own axis: a body with no start and no end sides. That is precisely the body a chain cut has to
 * *give* caps to — so the probe rings the plate, cuts the ring by the user's own trimming line through a 3D
 * ray pick, and demands watertightness on both sides of that transaction. Then it walks the whole thing back:
 * this fixture restates an attached point on every save, which is the exact drawing whose second undo used to
 * wedge — two gestures must peel in two undos, and the file must settle and replay to the same bodies.
 */
class RevolveFullProbeTest {
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

    private fun Editor.look(at: Vec3) {
        val plane = assertNotNull(doc.activePlane3(Evaluator()), "the active space has a plane")
        pointing = PlanePerspective(plane, Camera3(target = at, distance = 300.0, yaw = 0.6, pitch = 0.5), 800.0, 600.0)
    }

    private fun centreOf(el: Element): Vec3 {
        val b = assertNotNull(Geom3.bounds(meshOf(el)), "the solid has bounds")
        return (b.first + b.second) * 0.5
    }

    private fun named(
        doc: Document,
        name: String,
    ): Element =
        assertNotNull(
            doc.elements.firstOrNull { doc.userNameOf(it) == name } ?: doc.elements.firstOrNull { doc.nameOf(it) == name },
            "the drawing has $name",
        )

    @Test
    fun theFullRingIsCutByTheUsersLineAndWalksAllTheWayBack() {
        val ed = Editor(DocumentFormat.load(ChainCutFixture.CIT))
        val doc = ed.doc
        val handle = named(doc, "e30")
        val solidsBefore = solids(doc).size

        // ---- the ring: profile and axis clicked, nothing typed — the structural full turn ----
        ed.setActiveSpace("plane1")
        ed.setTool(Tools.REVOLVE)
        // the profile's fillet arc, mid-arc, away from every point (the outline e28's own edge)
        ed.click(Vec2(84.0, 5.0))
        // the axis e29 — the perpendicular bisector, clicked far from the drawing's points
        ed.click(Vec2(-13.8, 60.0))
        val ring = solids(doc).last()
        assertEquals(solidsBefore + 1, solids(doc).size, "the two clicks built the ring: ${ed.statusHint}")
        assertNull(whyInvalid(ring), "…and it evaluates: ${ed.statusHint}")
        val ringMesh = meshOf(ring)
        assertManifold(ringMesh, "the complete revolution")
        // a complete turn of the same generator holds twelve times the 30-degree handle's material
        assertClose(
            Geom3.volume(ringMesh),
            Geom3.volume(meshOf(handle)) * 12.0,
            Geom3.volume(meshOf(handle)) * 0.12,
            msg = "a full turn is twelve of the fixture's 30-degree sweeps",
        )
        val savedRing = DocumentFormat.save(doc)
        val revolveStep = savedRing.lineSequence().last { it.startsWith("tool revolve") }
        assertTrue(!revolveStep.contains("scalar="), "the full turn is spelled structurally — no angle at all: $revolveStep")

        // ---- the cut: ray-pick the ring where it passes through the drawn handle, chain2, keep a side ----
        ed.setActiveSpace(Document.PLAN_SPACE)
        ed.look(centreOf(handle))
        ed.setTool(Tools.CUT_BY_CHAIN)
        ed.click(ed.aimAtWorld(centreOf(handle)))
        ed.pointing = null
        val chain2 = named(doc, "chain2")
        run {
            val line = (Evaluator().valueOf(chain2.ref) as constructit.core.LineValue).line
            val dir = line.dir.normalized()
            val on = line.origin + dir * 30.0
            ed.click(Vec2(on.x, on.y))
            ed.click(Vec2(on.x - dir.y * 20.0, on.y + dir.x * 20.0))
        }
        val cut = solids(doc).last()
        assertEquals(solidsBefore + 2, solids(doc).size, "the cut is its own body: ${ed.statusHint}")
        assertNull(whyInvalid(cut), "…which builds: ${ed.statusHint}")
        assertManifold(meshOf(cut), "the ring, opened by the fence — the cut had to make the caps a full turn never had")
        assertTrue(Geom3.volume(meshOf(cut)) < Geom3.volume(ringMesh), "…and material came off")

        // ---- all the way back: two gestures, two undos, on the drawing whose undo used to wedge ----
        ed.undo()
        assertEquals(solidsBefore + 1, solids(ed.doc).size, "the first undo takes the cut")
        ed.undo()
        assertEquals(solidsBefore, solids(ed.doc).size, "…and the second takes the ring")

        // ---- and forward again from the settled file: the spellings replay to the same bodies ----
        var text = savedRing
        repeat(4) {
            val again = DocumentFormat.save(DocumentFormat.load(text))
            if (again == text) return@repeat
            text = again
        }
        val back = DocumentFormat.load(text)
        assertClose(
            Geom3.volume(meshOf(solids(back).last())),
            Geom3.volume(ringMesh),
            1e-6,
            msg = "the structural full turn replays to the identical ring",
        )
    }

    /** Where a body is clicked in the 3D view, given a world point on it. */
    private fun Editor.aimAtWorld(world: Vec3): Vec2 {
        val plane = assertNotNull(doc.activePlane3(Evaluator()), "the active space has a plane")
        return plane.toLocal(world)
    }
}
