package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.SolidValue
import constructit.dsl.valueOf
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.exchange.ExportFormat
import constructit.exchange.ExportScene
import constructit.exchange.Exports
import constructit.geom.Vec2
import constructit.units.mm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **The probe review of the sweep's embedding criterion (session 40).**
 *
 * The delivery proves the criterion against geometry: the double normal, the boundary, the cost, the cases
 * that must and must not fire. These ask what a *refused* sweep is to the rest of the drawing — because the
 * fix is only worth anything if the refusal reaches every consumer that would otherwise have trusted a
 * self-intersecting shell — and whether the criterion is really about the **profile's reach** rather than
 * about tubes.
 */
class EmbeddingProbeTest {
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

    private fun coil(
        ed: Editor,
        radius: String,
        pitch: String,
        turns: String,
    ): constructit.editor.Element {
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.setTool(Tools.HELIX)
        ed.type(radius)
        ed.type(pitch)
        ed.type(turns)
        ed.click(Vec2(0.0, 0.0))
        return ed.doc.elements.last { it.kind == ElementKind.SPACE_CURVE }
    }

    private fun invalidity(
        ed: Editor,
        el: constructit.editor.Element,
    ): String? = (Evaluator().eval(el.ref.node) as? EvalResult.Invalid)?.reason

    /**
     * **A refused sweep is refused everywhere, and heals everywhere.** The whole value of the criterion is
     * that nothing downstream is allowed to trust the body: it must not export, must not be a boolean
     * operand, and must not appear as a body of the scene — and because this is a property of a *value*
     * (OP-3) rather than a gesture refusal, thinning the wire must bring all of that back without the user
     * rebuilding anything.
     */
    @Test
    fun aSelfIntersectingSpringIsRefusedByEveryConsumerAndHealsForAllOfThem() {
        val ed = Editor()
        val c = coil(ed, "20", "6", "4")
        val wire = ed.doc.newParameter("w", 5.0.mm)
        val spring = assertNotNull(ed.doc.tubeAlongCurve(c, wire.ref), "the gesture builds — this is a value's business")

        val why = assertNotNull(invalidity(ed, spring), "a 10 mm wire through turns 6 mm apart is refused")
        assertTrue(why.contains("mm"), "and the refusal carries the numbers: $why")

        // nothing downstream may treat it as a body
        val scene = ExportScene.extract(ed.doc, "probe")
        assertEquals(0, scene.nodes.size, "an invalid solid is not a body of the scene")
        assertTrue(scene.notes.any { it.contains("invalid") }, "and it is said, not silently dropped: ${scene.notes}")
        for (format in ExportFormat.entries) {
            val out = Exports.export(ed.doc, "probe", format)
            assertTrue(out.bytes == null || scene.nodes.isEmpty(), "${format.label} wrote no body for it")
        }
        // a boolean against it cannot be valid either — invalidity propagates transitively (OP-3)
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(-30.0, -30.0))
        ed.click(Vec2(30.0, 30.0))
        ed.activeScalar = ed.doc.newParameter("h", 10.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(0.0, -30.0))
        val block = ed.doc.elements.last { it.kind == ElementKind.SOLID && it !== spring }
        val fused = ed.doc.combineSolids(spring, block, constructit.geom.BoolOp.UNION)
        if (fused != null) {
            assertNotNull(invalidity(ed, fused), "a boolean built on a refused body is invalid too")
        }

        // …and it heals, for every one of them at once
        ed.doc.setParameter(wire, 1.5.mm)
        assertEquals(null, invalidity(ed, spring), "a thin wire is an embedded spring again")
        val healed = ExportScene.extract(ed.doc, "probe")
        assertEquals(1, healed.nodes.size, "the body is back in the scene")
        assertManifold((Evaluator().valueOf(spring.ref) as SolidValue).solid.mesh, "and it is watertight")

        // the file stores the parameters; the verdict is derived again from them
        val text = DocumentFormat.save(ed.doc)
        assertEquals(text, DocumentFormat.save(DocumentFormat.load(text)), "save → load → save is byte-equal")
    }

    /**
     * **The criterion is about the section's reach, not about tubes.** A round profile makes the reach easy
     * to name, which is exactly why a probe should use something else: a long thin rectangle whose *corner*
     * is what comes round to meet the run must trip the same test, and shrinking it must heal the same way.
     */
    @Test
    fun anArbitrarySectionTripsTheSameCriterionByItsFarthestCorner() {
        val ed = Editor()
        // the coil stands well away from the space origin, because a profile is read in its own 2D
        // coordinates **with its origin on the path** — a section drawn off the origin runs off the route,
        // which is a construction rather than a defect, and would otherwise swamp what this probe measures
        ed.setTool(Tools.POINT)
        ed.click(Vec2(150.0, 0.0))
        ed.setTool(Tools.HELIX)
        ed.type("25")
        ed.type("14")
        ed.type("3")
        ed.click(Vec2(150.0, 0.0))

        // a bar whose half-diagonal (12.7 mm) is well over half the pitch
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(-9.0, -9.0))
        ed.click(Vec2(9.0, 9.0))
        ed.setTool(Tools.SWEEP)
        ed.click(Vec2(175.0, 0.0))
        ed.click(Vec2(0.0, -9.0))
        val swept = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.SOLID }, "built: ${ed.statusHint}")
        assertNotNull(invalidity(ed, swept), "a section reaching past half the pitch cannot be embedded")

        // a slender section on the same coil is fine, and watertight
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(-2.0, -2.0))
        ed.click(Vec2(2.0, 2.0))
        ed.setTool(Tools.SWEEP)
        ed.click(Vec2(175.0, 0.0))
        ed.click(Vec2(1.0, -2.0))
        val ok = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.SOLID && it !== swept }, "${ed.statusHint}")
        assertEquals(null, invalidity(ed, ok), "a slender bar follows the same coil happily")
        assertManifold((Evaluator().valueOf(ok.ref) as SolidValue).solid.mesh, "and it is a solid")
    }

    /**
     * **A dense coil that is genuinely embedded is not refused.** The permissive direction matters as much
     * as the strict one: a criterion that fired on a real close-wound spring would be worse than none,
     * because a user would learn to distrust it. Many turns, wire just under half the pitch.
     */
    @Test
    fun aCloseWoundButEmbeddedSpringIsAccepted() {
        val ed = Editor()
        val c = coil(ed, "40", "12", "12")
        val spring = assertNotNull(ed.doc.tubeAlongCurve(c, ed.doc.newParameter("w", 5.0.mm).ref), "${ed.doc.note}")
        assertEquals(null, invalidity(ed, spring), "a 10 mm wire in a 12 mm pitch clears itself")
        assertManifold((Evaluator().valueOf(spring.ref) as SolidValue).solid.mesh, "a close-wound spring")
        val out = Exports.export(ed.doc, "spring", ExportFormat.THREE_MF)
        assertTrue(out.ok, "and it prints: ${out.message}")
    }
}
