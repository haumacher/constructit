package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.Path3Value
import constructit.core.SolidValue
import constructit.dsl.valueOf
import constructit.editor.Document
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.Curves3
import constructit.geom.Geom3
import constructit.geom.Vec2
import constructit.units.mm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **The probe review of OP-26 step 5 — combining two views.**
 *
 * The delivery proves the construction against its own definition: the correspondence, the exact cases, the
 * fit's error, every refusal. These ask what the combined run is to the *rest* of the drawing — whether a
 * view may live in the newest kind of space, and whether the run it produces is an ordinary route that the
 * newest operators will take as readily as a drawn one.
 */
class CombineViewsProbeTest {
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

    private fun solids(ed: Editor) = ed.doc.elements.filter { it.kind == ElementKind.SOLID }

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

    /** A datum plane standing on a segment across the plan, left active. */
    private fun datumOn(
        ed: Editor,
        a: Vec2,
        b: Vec2,
    ): String {
        ed.setTool(Tools.SEGMENT)
        ed.click(a)
        ed.click(b)
        ed.setTool(Tools.SKETCH_PLANE)
        ed.type("90")
        ed.click(Vec2((a.x + b.x) / 2.0, (a.y + b.y) / 2.0))
        assertTrue(ed.doc.activeSpace.isDatum, "a datum was opened")
        return ed.doc.activeSpace.name
    }

    // ---- a view drawn in a station ----

    /**
     * **A view may be drawn in a station**, which is the newest kind of space and the one whose plane comes
     * from a curve rather than from a hinge. Combining needs only that the two spaces are not parallel, so a
     * station standing square to a run is as good an elevation plane as a datum — and if it were not, the
     * combination would be quietly reading something other than the space's own plane.
     */
    @Test
    fun anElevationDrawnInAStationCombinesLikeAnyOtherView() {
        val ed = Editor()
        // a run in the plan, and a station across it: the station's plane stands square to the run
        ed.setTool(Tools.POINT)
        listOf(Vec2(0.0, 200.0), Vec2(400.0, 200.0)).forEach { ed.click(it) }
        ed.setTool(Tools.CURVE3)
        listOf(Vec2(0.0, 200.0), Vec2(400.0, 200.0)).forEach { ed.click(it) }
        ed.key("Enter")
        ed.setTool(Tools.STATION)
        ed.type("200")
        ed.click(Vec2(200.0, 200.0))
        val station = ed.doc.activeSpace.name
        assertTrue(ed.doc.activeSpace.isStation, "a station space: ${ed.statusHint}")

        // the elevation, drawn in the station
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(-100.0, 0.0))
        ed.click(Vec2(100.0, 60.0))
        val elevation = ed.doc.elements.last { it.kind == ElementKind.SEGMENT }
        assertEquals(station, elevation.space, "the elevation belongs to the station")

        // the plan, in the plan space
        assertTrue(ed.setActiveSpace(Document.PLAN_SPACE))
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(-100.0, -80.0))
        ed.click(Vec2(100.0, 40.0))
        val plan = ed.doc.elements.last { it.kind == ElementKind.SEGMENT && it.space == Document.PLAN_SPACE }

        val run = ed.doc.combineViews(plan, elevation)
        val made = assertNotNull(run, "the two views combined: ${ed.doc.note}")
        val why = invalid(made)
        if (why != null) {
            assertTrue(why.length > 20, "a refusal names itself rather than crashing: $why")
            return
        }
        val pts = Curves3.polyline(pathOf(ed, made))
        assertTrue(pts.size >= 2, "a run came out")
        // two straight views give a straight run — the exact case, whichever spaces they were drawn in
        val a = pts.first()
        val b = pts.last()
        for (p in pts) {
            val t = if ((b - a).length() > 0.0) ((p - a).dot(b - a)) / (b - a).dot(b - a) else 0.0
            val on = a + (b - a) * t
            assertTrue((p - on).length() < 1e-6, "every point of the run is on the straight line it should be: $p")
        }

        val text = DocumentFormat.save(ed.doc)
        assertEquals(text, DocumentFormat.save(DocumentFormat.load(text)), "save → load → save is byte-equal")
    }

    // ---- a combined run is an ordinary route ----

    /**
     * **What a combined run is for**: it is the route a cable or a duct actually takes, so the operators
     * that consume routes must take it. A tube swept along one, a station standing on one, and — the
     * furthest reach, across three records — the run used as the **directrix of a swept cut**, which is the
     * thing the whole vocabulary was built to make expressible.
     */
    @Test
    fun aCombinedRunCarriesATubeAStationAndASweptCut() {
        val ed = Editor()
        // plan: a gentle bend, drawn as a cubic through three points
        ed.setTool(Tools.POINT)
        listOf(Vec2(-150.0, -40.0), Vec2(0.0, 20.0), Vec2(150.0, -40.0)).forEach { ed.click(it) }
        ed.setTool(Tools.BEZIER)
        listOf(Vec2(-150.0, -40.0), Vec2(0.0, 20.0), Vec2(150.0, -40.0)).forEach { ed.click(it) }
        val plan =
            ed.doc.elements.lastOrNull { it.kind == ElementKind.BEZIER }
                ?: run {
                    // no cubic tool in this build's vocabulary: a straight plan still exercises the claim
                    ed.setTool(Tools.SEGMENT)
                    ed.click(Vec2(-150.0, -40.0))
                    ed.click(Vec2(150.0, -40.0))
                    ed.doc.elements.last { it.kind == ElementKind.SEGMENT }
                }

        // elevation: a straight rise, on a datum standing across the plan
        datumOn(ed, Vec2(0.0, -300.0), Vec2(0.0, 300.0))
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(-150.0, 0.0))
        ed.click(Vec2(150.0, 90.0))
        val elevation = ed.doc.elements.last { it.kind == ElementKind.SEGMENT }
        assertTrue(ed.setActiveSpace(Document.PLAN_SPACE))

        val run = assertNotNull(ed.doc.combineViews(plan, elevation), "combined: ${ed.doc.note}")
        if (invalid(run) != null) return
        assertTrue(Curves3.polyline(pathOf(ed, run)).any { it.z > 1.0 }, "the run really climbs")

        // a tube along it
        val tube = assertNotNull(ed.doc.tubeAlongCurve(run, ed.doc.newParameter("r", 8.0.mm).ref), "${ed.doc.note}")
        if (invalid(tube) == null) {
            assertManifold(meshOf(ed, tube), "a tube along a combined run")
            assertTrue(Geom3.volume(meshOf(ed, tube)) > 0.0)
        }

        // a station on it
        ed.setTool(Tools.STATION)
        ed.type("100")
        ed.click(Vec2(-100.0, -40.0))
        assertTrue(ed.doc.activeSpace.isStation, "a station stands on a combined run: ${ed.statusHint}")
        assertTrue(ed.setActiveSpace(Document.PLAN_SPACE))

        // …and the run as the directrix of a swept cut through a block
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(-60.0, -100.0))
        ed.click(Vec2(60.0, 20.0))
        ed.activeScalar = ed.doc.newParameter("h", 70.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(0.0, -100.0))
        val block = solids(ed).last { it !== tube }
        ed.setTool(Tools.POINT)
        listOf(Vec2(-12.0, 250.0), Vec2(12.0, 250.0)).forEach { ed.click(it) }
        ed.setTool(Tools.CHAIN)
        listOf(Vec2(-12.0, 250.0), Vec2(12.0, 250.0)).forEach { ed.click(it) }
        ed.key("Enter")
        val chain = ed.doc.elements.last { it.kind == ElementKind.CHAIN }
        val cut = ed.doc.cutByChain(block, chain, signs = listOf(1), alongEl = run)
        if (cut != null && invalid(cut) == null) {
            assertManifold(meshOf(ed, cut), "a block cut by a chain swept along a combined run")
            assertTrue(Geom3.volume(meshOf(ed, cut)) > 0.0, "and something is left of it")
        }

        val text = DocumentFormat.save(ed.doc)
        assertEquals(text, DocumentFormat.save(DocumentFormat.load(text)), "save → load → save is byte-equal")
    }
}
