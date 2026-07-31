package constructit

import constructit.core.Evaluator
import constructit.dsl.SolidRef
import constructit.dsl.scalar
import constructit.dsl.solid
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.Geom3
import constructit.geom.Justification
import constructit.geom.Vec2
import constructit.units.mm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Probes on the loft, composing it with what it has never met: a **wall footprint as a section** (both new
 * this week — a tent over a thickened wall), the spoken refusal for a ring footprint's hole, and the new
 * parallel-datum **offset re-edited as the ordinary parameter it claims to be**.
 */
class LoftProbeTest {
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

    private fun Editor.solids(): List<Element> = doc.elements.filter { it.kind == ElementKind.SOLID }

    @Suppress("UNCHECKED_CAST")
    private fun volumeOf(el: Element): Double = Geom3.volume(Evaluator().solid(el.ref as SolidRef).mesh)

    @Suppress("UNCHECKED_CAST")
    private fun meshOf(el: Element) = Evaluator().solid(el.ref as SolidRef).mesh

    private fun roundTrips(
        ed: Editor,
        msg: String,
    ) {
        val once = DocumentFormat.save(ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), msg)
    }

    /** A tent over a wall: the footprint of a thickened segment is a section like any other area. */
    @Test
    fun aWallFootprintLoftsToAnApex() {
        val ed = Editor()
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 0.0))
        ed.activeScalar = ed.doc.newParameter("d", 20.0.mm)
        ed.setTool(Tools.THICKEN)
        ed.justification = Justification.CENTER
        ed.click(Vec2(50.0, 0.0))
        ed.key("Enter")
        assertEquals(1, ed.doc.thickNetworks.size, "the wall exists: ${ed.statusHint}")

        ed.setTool(Tools.EXTRUDE_TO_POINT)
        ed.type("30")
        // the footprint picks on its boundary — the face line at y = +10
        ed.click(Vec2(50.0, 10.0))
        // the apex may stand anywhere in plan — Cavalieri keeps the volume — so keep it off every curve
        ed.click(Vec2(40.0, 25.0))
        val solid = ed.solids().singleOrNull()
        assertTrue(solid != null, "the tent was built: ${ed.statusHint}")
        assertManifold(meshOf(solid), "tent over a wall")
        // a pyramid over ANY planar base is base * h / 3: (100 x 20) * 30 / 3
        assertClose(volumeOf(solid), 20000.0, tol = 1e-6, msg = "base 2000 mm^2, apex at 30: ${ed.statusHint}")
        roundTrips(ed, "wall -> loft replays byte-equal")
    }

    /** A ring wall footprint has a hole: the loft goes invalid with the hole spoken, and could heal (OP-3). */
    @Test
    fun aRingWallFootprintGoesInvalidWithItsHoleSpoken() {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 100.0))
        ed.activeScalar = ed.doc.newParameter("d", 10.0.mm)
        ed.setTool(Tools.THICKEN)
        ed.justification = Justification.CENTER
        ed.click(Vec2(50.0, 0.0))
        ed.click(Vec2(100.0, 50.0))
        ed.click(Vec2(50.0, 100.0))
        ed.click(Vec2(0.0, 50.0))
        ed.key("Enter")
        assertEquals(1, ed.doc.thickNetworks.size, "the ring wall exists: ${ed.statusHint}")

        ed.setTool(Tools.EXTRUDE_TO_POINT)
        ed.type("30")
        ed.click(Vec2(50.0, 5.0))
        ed.click(Vec2(50.0, 50.0))
        // OP-3 latent invalidity, not an up-front refusal: the element exists, invalid with the hole SPOKEN,
        // and can heal if the wall stops being a ring
        val solid = ed.solids().single()
        assertTrue(
            Evaluator().eval(solid.ref.node) is constructit.core.EvalResult.Invalid,
            "the ring-footprint loft is invalid: ${ed.statusHint}",
        )
        assertTrue(ed.statusHint.contains("hole", ignoreCase = true), "the invalidity names the hole: ${ed.statusHint}")
        roundTrips(ed, "an invalid-with-reason loft still replays byte-equal")
    }

    /** The parallel datum's offset is an ordinary parameter: re-typing it moves the plane AND the solid. */
    @Test
    fun reEditingTheDatumOffsetResizesTheFrustumExactly() {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 100.0))
        ed.setTool(Tools.SKETCH_PLANE)
        ed.type("0")
        ed.type("60")
        ed.click(Vec2(30.0, 0.0))
        assertTrue(!ed.activeSpace.isPlan, "on the datum: ${ed.statusHint}")
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(20.0, 20.0))
        ed.click(Vec2(80.0, 80.0))
        ed.setTool(Tools.LOFT)
        ed.click(Vec2(50.0, 20.0))
        assertTrue(ed.setActiveSpace(constructit.editor.Document.PLAN_SPACE))
        ed.click(Vec2(30.0, 0.0))
        ed.key("Enter")
        val solid = ed.solids().single()
        assertClose(volumeOf(solid), 392000.0, tol = 1e-6, msg = "the acceptance frustum first: ${ed.statusHint}")

        // the offset is a scalar entry like any other — find it by its value (the angle throws on .mm, skip it)
        val offset = ed.doc.scalars.first { runCatching { Evaluator().scalar(it.ref).mm == 60.0 }.getOrDefault(false) }
        ed.doc.setParameter(offset, 30.0.mm)
        // prismatoid at h=30: 30/6 * (10000 + 4 * 6400 + 3600) = 196000
        assertClose(volumeOf(solid), 196000.0, tol = 1e-6, msg = "the solid followed the plane")
        assertManifold(meshOf(solid), "the resized frustum")
        roundTrips(ed, "the re-typed offset restates in the file and replays")
    }
}
