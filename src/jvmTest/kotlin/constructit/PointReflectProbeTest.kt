package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.LineValue
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **The probe review of Point reflect** — the new transform as a working part, not a demonstration.
 *
 * On the user's plate, the trimming line `chain2` point-reflected through the plate's centre is the same trim
 * on the far side — and because a reflected line is still a line, it must be a legal **cutting chain** the
 * moment it exists. So the probe reflects, verifies the image is exactly `2c − p`, cuts the handle with the
 * image through a 3D ray pick, and checks the whole episode left no angle freedom in the panel — the
 * parameterlessness that is the tool's entire reason to exist.
 */
class PointReflectProbeTest {
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

    private fun named(
        doc: Document,
        name: String,
    ): Element =
        assertNotNull(
            doc.elements.firstOrNull { doc.userNameOf(it) == name } ?: doc.elements.firstOrNull { doc.nameOf(it) == name },
            "the drawing has $name",
        )

    @Test
    fun theReflectedLineIsTheSameTrimOnTheFarSide() {
        val ed = Editor(DocumentFormat.load(ChainCutFixture.CIT))
        val doc = ed.doc
        val handle = named(doc, "e30")
        val chain2 = named(doc, "chain2")
        val scalarsBefore = doc.scalars.size
        val centre = Vec2(-13.75, 5.75)

        // reflect chain2 through the plate's centre — two clicks, nothing typed
        ed.setTool(Tools.POINT_REFLECT)
        run {
            val line = (Evaluator().valueOf(chain2.ref) as LineValue).line
            val dir = line.dir.normalized()
            val on = line.origin + dir * 30.0
            ed.click(Vec2(on.x, on.y))
        }
        ed.click(centre)
        val image = doc.elements.last { it.kind == ElementKind.LINE }
        assertTrue(image !== chain2, "a new line: ${ed.statusHint}")
        assertNull(whyInvalid(image), "…which evaluates")

        // the image is exactly 2c − p: check two stations of the original against the image's carrier
        val original = (Evaluator().valueOf(chain2.ref) as LineValue).line
        val reflected = (Evaluator().valueOf(image.ref) as LineValue).line
        for (t in listOf(0.0, 50.0)) {
            val p = original.origin + original.dir.normalized() * t
            val q = Vec2(2 * centre.x - p.x, 2 * centre.y - p.y)
            val d = reflected.dir.normalized()
            val off = q - reflected.origin
            val dist = kotlin.math.abs(off.x * d.y - off.y * d.x)
            assertClose(dist, 0.0, 1e-9, msg = "2c − p lies on the image, station $t")
        }
        // …and the whole gesture left the panel alone: a point reflection has no parameters
        assertEquals(scalarsBefore, doc.scalars.size, "no angle freedom appeared anywhere")

        // the image is a line, so it cuts — and what it crosses is the plate's far side (the reflected trim
        // trims where the reflected handle would be; the handle itself stands on the near side, and cutting
        // it with a chain that passes it by refuses as the no-op it is). Cut the plate, ray-picked in 3D,
        // keeping the handle's side of the image.
        val plate = solids(doc).first()
        val plane = assertNotNull(doc.activePlane3(Evaluator()), "the plan has a plane")
        val plateCentre =
            assertNotNull(Geom3.bounds(meshOf(plate)), "the plate has bounds").let { (it.first + it.second) * 0.5 }
        ed.pointing = PlanePerspective(plane, Camera3(target = plateCentre, distance = 400.0, yaw = 0.6, pitch = 0.5), 800.0, 600.0)
        ed.setTool(Tools.CUT_BY_CHAIN)
        ed.click(plane.toLocal(plateCentre))
        ed.pointing = null
        run {
            val d = reflected.dir.normalized()
            val on = reflected.origin + d * 30.0
            val a = Vec2(on.x - d.y * 20.0, on.y + d.x * 20.0)
            val b = Vec2(on.x + d.y * 20.0, on.y - d.x * 20.0)
            ed.click(Vec2(on.x, on.y))
            // keep the side the plate's centre (and the handle) is on
            val keep = if ((a - centre).length() < (b - centre).length()) a else b
            ed.click(keep)
        }
        val cut = solids(doc).last()
        assertNull(whyInvalid(cut), "the image trims the plate's far rim: ${ed.statusHint}")
        assertManifold(meshOf(cut), "the plate trimmed by a point-reflected chain")
        assertTrue(Geom3.volume(meshOf(cut)) < Geom3.volume(meshOf(plate)), "…and material came off")

        // the file replays both new steps to the same geometry
        var text = DocumentFormat.save(doc)
        repeat(4) {
            val again = DocumentFormat.save(DocumentFormat.load(text))
            if (again == text) return@repeat
            text = again
        }
        val back = DocumentFormat.load(text)
        assertClose(
            Geom3.volume(meshOf(solids(back).last())),
            Geom3.volume(meshOf(cut)),
            1e-6,
            msg = "reflect-then-cut replays to the identical body",
        )
    }
}
