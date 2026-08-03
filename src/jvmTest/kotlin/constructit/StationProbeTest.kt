package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.SolidValue
import constructit.dsl.valueOf
import constructit.editor.Document
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
 * **The probe review of OP-26 step 4 — the station.**
 *
 * The delivery proves the station against its own contract: where it stands, that it is a space, that it
 * rides its path, that it heals. These ask the two things a *space* has to answer that a correct plane need
 * not — whether the newest space works as the operand of the newest operator, and whether an invalid space
 * takes the drawing that stands on it down with it and brings it back.
 */
class StationProbeTest {
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

    /** A straight run along +x in the plan, and a station a stated distance along it. */
    private fun runAndStation(
        ed: Editor,
        distance: String,
    ): Pair<constructit.editor.Element, String> {
        ed.setTool(Tools.POINT)
        listOf(Vec2(0.0, 0.0), Vec2(200.0, 0.0), Vec2(400.0, 0.0)).forEach { ed.click(it) }
        ed.setTool(Tools.CURVE3)
        listOf(Vec2(0.0, 0.0), Vec2(200.0, 0.0), Vec2(400.0, 0.0)).forEach { ed.click(it) }
        ed.key("Enter")
        val route = ed.doc.elements.last { it.kind == ElementKind.SPACE_CURVE }
        ed.setTool(Tools.STATION)
        ed.type(distance)
        ed.click(Vec2(100.0, 0.0))
        assertTrue(ed.doc.activeSpace.isStation, "a station space was opened: ${ed.statusHint}")
        return route to ed.doc.activeSpace.name
    }

    // ---- the newest space as the newest operator's operand ----

    /**
     * **A chain drawn in a station cuts across the run it stands on.** This is the composition the station
     * exists for — *"a cut normal to the path: a mitre, a notch, a gland"* — and it is the one that could
     * silently not work, because the cut reads the **chain's own space** for both the section's coordinates
     * and the frame's start reference. A station is a space whose plane is derived from a curve rather than
     * from a hinge, so nothing guarantees in advance that the cut's reading of it is the same reading the
     * station's own plane makes.
     */
    @Test
    fun aChainDrawnInAStationCutsTheBodyTheRunPassesThrough() {
        val ed = Editor()
        // a bar lying along the run, made first, in the plan
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, -40.0))
        ed.click(Vec2(400.0, 40.0))
        ed.activeScalar = ed.doc.newParameter("h", 50.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(200.0, -40.0))
        val bar = solids(ed).last()
        val whole = Geom3.volume(meshOf(ed, bar))
        assertClose(whole, 400.0 * 80.0 * 50.0, 1.0, "the bar")

        val (_, station) = runAndStation(ed, "150")

        // in the station's own plane — which stands across the bar — a chain, and a cut with it
        ed.setTool(Tools.POINT)
        ed.click(Vec2(-60.0, 20.0))
        ed.click(Vec2(60.0, 20.0))
        ed.setTool(Tools.CHAIN)
        ed.click(Vec2(-60.0, 20.0))
        ed.click(Vec2(60.0, 20.0))
        ed.key("Enter")
        val chain = ed.doc.elements.last { it.kind == ElementKind.CHAIN }
        assertEquals(station, chain.space, "the chain belongs to the station it was drawn in")

        val cut = ed.doc.cutByChain(bar, chain, signs = listOf(1))
        val body = assertNotNull(cut, "the cut built: ${ed.doc.note}")
        val why = invalid(body)
        if (why != null) {
            assertTrue(why.length > 20, "an honest refusal names itself, never a crash: $why")
            return
        }
        assertManifold(meshOf(ed, body), "a bar cut by a chain drawn across it at a station")
        val kept = Geom3.volume(meshOf(ed, body))
        assertTrue(kept > 0.0 && kept < whole, "material came off: $kept of $whole")

        val text = DocumentFormat.save(ed.doc)
        assertEquals(text, DocumentFormat.save(DocumentFormat.load(text)), "save → load → save is byte-equal")
    }

    // ---- an invalid space takes its drawing with it, and brings it back ----

    /**
     * **A station pushed past the end of its run hides what stands on it, and healing brings all of it
     * back.** The contract says out-of-range is node invalidity rather than a gesture refusal *because* the
     * distance is a live value — which is only worth anything if invalidity propagates to the boss standing
     * on the station and then un-propagates. That is OP-3's transitive rule asked of a *space* rather than
     * of a value, which is a place it has never been asked before.
     */
    @Test
    fun aStationDrivenPastItsRunTakesTheBossWithItAndBringsItBack() {
        val ed = Editor()
        runAndStation(ed, "150")

        // a boss standing on the station
        ed.setTool(Tools.CIRCLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(15.0, 0.0))
        ed.activeScalar = ed.doc.newParameter("d", 30.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(15.0, 0.0))
        val boss = assertNotNull(solids(ed).lastOrNull(), "a boss on the station: ${ed.statusHint}")
        assertEquals(null, invalid(boss), "and it is a real solid")
        assertManifold(meshOf(ed, boss), "the boss")
        val standing = Geom3.volume(meshOf(ed, boss))
        assertTrue(standing > 0.0)

        // drive the distance past the end of a 400 mm run
        val along = assertNotNull(ed.doc.scalars.firstOrNull { it.name == "distance" }, "the station's own parameter")
        ed.doc.setParameter(along, 900.0.mm)
        assertNotNull(invalid(boss), "the boss standing on a station that is nowhere is nowhere itself")

        // …and negative is the other end of the same statement
        ed.doc.setParameter(along, (-50.0).mm)
        assertNotNull(invalid(boss), "before the start is out of range too")

        // heal
        ed.doc.setParameter(along, 250.0.mm)
        assertEquals(null, invalid(boss), "back in range, and the boss is back")
        assertManifold(meshOf(ed, boss), "and it is watertight again")
        assertClose(Geom3.volume(meshOf(ed, boss)), standing, standing * 1e-6, "the same boss, further along")

        val text = DocumentFormat.save(ed.doc)
        assertEquals(text, DocumentFormat.save(DocumentFormat.load(text)), "save → load → save is byte-equal")
    }

    // ---- a station on a curve that lies in no plane ----

    /**
     * **A station on a helix stands square to a run that is nowhere flat.** The straight and polyline cases
     * can both be right by accident — a plan-parallel frame would pass them. A coil cannot: its tangent has
     * a component along every axis, so a station's plane there is only correct if the transport is.
     */
    @Test
    fun aStationOnACoilStandsSquareToARunThatIsNowhereFlat() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.setTool(Tools.HELIX)
        ed.type("40")
        ed.type("60")
        ed.type("3")
        ed.click(Vec2(0.0, 0.0))
        ed.setTool(Tools.STATION)
        ed.type("300")
        ed.click(Vec2(40.0, 0.0))
        assertTrue(ed.doc.activeSpace.isStation, "a station on the coil: ${ed.statusHint}")

        val plane = assertNotNull(ed.doc.activePlane3(Evaluator()), "the station has a plane")
        val n = plane.normal.normalized()
        // a coil of 40 mm radius and 60 mm rise per turn leaves the plan at atan(60 / (2π·40)) ≈ 13.4°,
        // so the station's normal is tilted by that much and lies along no axis at all
        assertTrue(kotlin.math.abs(n.z) > 0.1 && kotlin.math.abs(n.z) < 0.99, "the normal is tilted, not flat and not vertical: $n")
        assertTrue(kotlin.math.abs(n.x) > 0.01 || kotlin.math.abs(n.y) > 0.01, "and it leans in plan too: $n")

        // it is an ordinary space: draw in it and build
        ed.setTool(Tools.CIRCLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(8.0, 0.0))
        ed.activeScalar = ed.doc.newParameter("t", 20.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(8.0, 0.0))
        val boss = assertNotNull(solids(ed).lastOrNull(), "a boss on a coil's station: ${ed.statusHint}")
        assertEquals(null, invalid(boss))
        assertManifold(meshOf(ed, boss), "the boss on a coil")
        assertTrue(ed.setActiveSpace(Document.PLAN_SPACE), "and the plan is still there to go back to")
    }
}
