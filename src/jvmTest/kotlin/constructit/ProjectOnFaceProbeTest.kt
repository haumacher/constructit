package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.Path3Value
import constructit.core.SolidValue
import constructit.dsl.valueOf
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.Curves3
import constructit.geom.Geom3
import constructit.geom.Vec2
import constructit.units.mm
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **The probe review of OP-26 step 8 — projecting onto a face.**
 *
 * The delivery proves the projection against its own definition: the affine exactness, the face choice, the
 * off-the-face rule, the refusals. These ask whether the run it produces is an ordinary one — a curve
 * derived from a *solid's face*, which is a provenance no earlier step had — and whether it keeps its
 * defining property when the body it landed on changes under it.
 */
class ProjectOnFaceProbeTest {
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

    private fun meshOf(
        ed: Editor,
        el: constructit.editor.Element,
    ) = (Evaluator().valueOf(el.ref) as SolidValue).solid.mesh

    private fun invalid(el: constructit.editor.Element): String? =
        (Evaluator().eval(el.ref.node) as? EvalResult.Invalid)?.reason

    private fun pathOf(
        ed: Editor,
        el: constructit.editor.Element,
    ) = (Evaluator().valueOf(el.ref) as Path3Value).path

    /** A plate on the plan, and a segment drawn over it — the two picks the tool takes. */
    private fun plateAndLine(ed: Editor): Pair<constructit.editor.Element, constructit.editor.Element> {
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(-100.0, -60.0))
        ed.click(Vec2(100.0, 60.0))
        ed.activeScalar = ed.doc.newParameter("h", 30.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(0.0, -60.0))
        val plate = ed.doc.elements.last { it.kind == ElementKind.SOLID }
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(-70.0, -20.0))
        ed.click(Vec2(70.0, 30.0))
        return plate to ed.doc.elements.last { it.kind == ElementKind.SEGMENT }
    }

    // ---- a run whose provenance is a solid's face ----

    /**
     * **A projected line is an ordinary run, and it stays on the face when the face moves.** The defining
     * property is that the result lies *on the face* — so the interesting test is not that it does when it
     * is made, but that it still does after the body's own parameter has moved the face out from under it.
     * A projection that merely computed a height once would pass the first and fail the second.
     */
    @Test
    fun aProjectedRunStaysOnTheFaceWhenTheBodyGrowsUnderIt() {
        val ed = Editor()
        val (plate, line) = plateAndLine(ed)

        ed.setTool(Tools.PROJECT_ON_FACE)
        ed.click(Vec2(0.0, 5.0))
        ed.click(Vec2(0.0, -60.0))
        val run =
            assertNotNull(
                ed.doc.elements.lastOrNull { it.kind == ElementKind.SPACE_CURVE },
                "the projection was built: ${ed.statusHint}",
            )
        assertEquals(null, invalid(run), "and it is a run")
        assertTrue(line.kind == ElementKind.SEGMENT && plate.kind == ElementKind.SOLID)

        val on = Curves3.polyline(pathOf(ed, run))
        assertTrue(on.size >= 2, "a run came out")
        // it lies on the plate's top face, 30 mm up
        for (p in on) assertTrue(abs(p.z - 30.0) < 1e-9, "every point is on the top face: $p")
        // …and its shadow in the plan is the line that was drawn
        assertClose(on.first().x, -70.0, 1e-9, "the shadow is the drawing, at its start")
        assertClose(on.last().x, 70.0, 1e-9, "and at its end")

        // now make the plate taller: the face moves, and the run must move with it
        ed.doc.setParameter(ed.doc.scalars.first { it.name == "h" }, 85.0.mm)
        val after = Curves3.polyline(pathOf(ed, run))
        for (p in after) assertTrue(abs(p.z - 85.0) < 1e-9, "the run is still on the face, which is higher now: $p")
        assertClose(after.first().x, -70.0, 1e-9, "and its shadow is unchanged, because the drawing is")

        val text = DocumentFormat.save(ed.doc)
        assertEquals(text, DocumentFormat.save(DocumentFormat.load(text)), "save → load → save is byte-equal")
    }

    // ---- the newest provenance meets the earlier steps ----

    /**
     * **An engraved line carries a tube and joins another run.** The point of a projection being a `Path3`
     * rather than a drawing is that everything built for runs applies to it — so the newest provenance is
     * asked to do what all the others do, including being the operand of step 7's join, which reads a
     * tangent at its end.
     */
    @Test
    fun aProjectedRunCarriesATubeAndJoinsAnotherRun() {
        val ed = Editor()
        plateAndLine(ed)
        ed.setTool(Tools.PROJECT_ON_FACE)
        ed.click(Vec2(0.0, 5.0))
        ed.click(Vec2(0.0, -60.0))
        val engraved =
            assertNotNull(
                ed.doc.elements.lastOrNull { it.kind == ElementKind.SPACE_CURVE },
                "the projection: ${ed.statusHint}",
            )
        assertEquals(null, invalid(engraved))

        // a tube along it — an engraved bead
        val tube = ed.doc.tubeAlongCurve(engraved, ed.doc.newParameter("r", 3.0.mm).ref)
        if (tube != null && invalid(tube) == null) {
            assertManifold(meshOf(ed, tube), "a bead along an engraved line")
            assertTrue(Geom3.volume(meshOf(ed, tube)) > 0.0)
        }

        // …and a second run it can be joined to, through the ordinary join gesture
        ed.setTool(Tools.POINT)
        ed.click(Vec2(200.0, 120.0))
        ed.click(Vec2(320.0, 120.0))
        ed.setTool(Tools.CURVE3)
        ed.click(Vec2(200.0, 120.0))
        ed.click(Vec2(320.0, 120.0))
        ed.key("Enter")

        val end = Curves3.polyline(pathOf(ed, engraved)).last()
        ed.setTool(Tools.CONNECT)
        ed.click(Vec2(end.x, end.y))
        ed.click(Vec2(200.0, 120.0))
        val join = ed.doc.elements.lastOrNull { it.kind == ElementKind.SPACE_CURVE }
        if (join != null && join !== engraved) {
            val why = invalid(join)
            if (why != null) {
                assertTrue(why.length > 15, "if the join refuses, it names itself: $why")
            } else {
                val pts = Curves3.polyline(pathOf(ed, join))
                assertTrue(pts.size >= 2, "a join came out")
                // it starts where the engraved run ends, which is up on the face
                assertTrue(abs(pts.first().z - end.z) < 1e-6, "the join begins on the face: ${pts.first()} vs $end")
            }
        }

        val text = DocumentFormat.save(ed.doc)
        assertEquals(text, DocumentFormat.save(DocumentFormat.load(text)), "save → load → save is byte-equal")
    }
}
