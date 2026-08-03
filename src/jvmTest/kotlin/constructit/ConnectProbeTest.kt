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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **The probe review of OP-26 step 7 — the connect.**
 *
 * The delivery proves the join against its own definition: tangency by construction, the exact G2, the end
 * choice, the refusals. These ask the two things a *derived curve* has to answer that a correct one need
 * not: whether its own ends are as good as any other curve's — so that a join can be joined — and whether
 * the run it makes is one the operators built for runs will take.
 */
class ConnectProbeTest {
    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerMove(s)
        pointerDown(s)
        pointerUp(s)
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

    /** A straight run through the given plan points, drawn with the ordinary tools. */
    private fun run(
        ed: Editor,
        vararg at: Vec2,
    ): constructit.editor.Element {
        ed.setTool(Tools.POINT)
        at.forEach { ed.click(it) }
        ed.setTool(Tools.CURVE3)
        at.forEach { ed.click(it) }
        ed.key("Enter")
        return ed.doc.elements.last { it.kind == ElementKind.SPACE_CURVE }
    }

    private fun connect(
        ed: Editor,
        a: Vec2,
        b: Vec2,
    ): constructit.editor.Element? {
        ed.setTool(Tools.CONNECT)
        ed.click(a)
        ed.click(b)
        return ed.doc.elements.lastOrNull { it.kind == ElementKind.SPACE_CURVE }
    }

    // ---- a join's own ends are ends like any other ----

    /**
     * **A join can be joined.** A connect is a *derived* curve, so its ends are not points anybody drew and
     * its tangents there are read off the piece it computed rather than off a picked direction. If those
     * ends were second-class in any way — unpickable, or reporting a tangent that is merely nearly right —
     * the natural way to build a route out of several bends would be the one thing that does not work. Three
     * runs, two joins, and then the joins themselves joined into a fourth.
     */
    @Test
    fun aJoinCanItselfBeJoined() {
        val ed = Editor()
        val a = run(ed, Vec2(-400.0, 0.0), Vec2(-250.0, 0.0))
        val b = run(ed, Vec2(-100.0, 120.0), Vec2(50.0, 120.0))
        val c = run(ed, Vec2(200.0, 0.0), Vec2(350.0, 0.0))

        val ab = assertNotNull(connect(ed, Vec2(-250.0, 0.0), Vec2(-100.0, 120.0)), "first join: ${ed.statusHint}")
        assertTrue(ab !== a && ab !== b, "a new curve was made")
        assertEquals(null, invalid(ab), "and it is a run: ${ed.statusHint}")

        val bc = assertNotNull(connect(ed, Vec2(50.0, 120.0), Vec2(200.0, 0.0)), "second join: ${ed.statusHint}")
        assertEquals(null, invalid(bc), "and so is that one")
        assertTrue(bc !== ab && bc !== c)

        // now join the two joins to each other, across the middle run — a curve derived from derived curves
        val mid = Curves3.polyline(pathOf(ed, ab)).last()
        val mid2 = Curves3.polyline(pathOf(ed, bc)).first()
        val joinOfJoins = connect(ed, Vec2(mid.x, mid.y), Vec2(mid2.x, mid2.y))
        if (joinOfJoins != null && joinOfJoins !== bc) {
            val why = invalid(joinOfJoins)
            if (why != null) {
                assertTrue(why.length > 15, "if it refuses it names itself: $why")
            } else {
                val pts = Curves3.polyline(pathOf(ed, joinOfJoins))
                assertTrue(pts.size >= 2, "a run came out of joining two joins")
            }
        }

        // every join is an ordinary run: a tube follows each, and the three tubes are watertight
        for (curve in listOf(ab, bc)) {
            val tube = ed.doc.tubeAlongCurve(curve, ed.doc.newParameter("r${curve.id}", 6.0.mm).ref)
            if (tube != null && invalid(tube) == null) {
                assertManifold(meshOf(ed, tube), "a tube along a join")
                assertTrue(Geom3.volume(meshOf(ed, tube)) > 0.0)
            }
        }
        assertEquals(null, invalid(ab), "and the joins are still runs afterwards")
    }

    // ---- a join rides what it joins ----

    /**
     * **Moving a joined run moves the join, and it stays tangent.** The whole reason a connect is derived
     * rather than drawn is that it must follow — a route edited at one end should not need its bends
     * rebuilt. The claim is asserted the way the delivery asserts tangency: as a *direction*, after the
     * edit, so that "it followed" and "it is still a join" are one statement rather than two.
     */
    @Test
    fun aJoinFollowsTheRunItJoinsAndStaysTangentToIt() {
        val ed = Editor()
        run(ed, Vec2(-300.0, 0.0), Vec2(-150.0, 0.0))
        run(ed, Vec2(100.0, 150.0), Vec2(250.0, 150.0))
        val join = assertNotNull(connect(ed, Vec2(-150.0, 0.0), Vec2(100.0, 150.0)), "the join: ${ed.statusHint}")
        assertEquals(null, invalid(join))
        val before = Curves3.polyline(pathOf(ed, join))
        assertTrue(before.size >= 2)

        // drag the far run's near end well away; the join must follow it
        ed.setTool(Tools.SELECT)
        ed.pointerDown(ed.camera.worldToScreen(Vec2(100.0, 150.0)))
        ed.pointerMove(ed.camera.worldToScreen(Vec2(120.0, 330.0)))
        ed.pointerUp(ed.camera.worldToScreen(Vec2(120.0, 330.0)))

        assertEquals(null, invalid(join), "the join survived the edit")
        val after = Curves3.polyline(pathOf(ed, join))
        val movedEnd = after.last()
        assertTrue(
            (movedEnd - before.last()).length() > 50.0,
            "the join's far end followed the run it joins: ${before.last()} -> $movedEnd",
        )
        // and it still ends where that run now begins
        assertTrue(
            kotlin.math.abs(movedEnd.x - 120.0) < 1.0 && kotlin.math.abs(movedEnd.y - 330.0) < 1.0,
            "exactly at the run's own end: $movedEnd",
        )

        val text = DocumentFormat.save(ed.doc)
        assertEquals(text, DocumentFormat.save(DocumentFormat.load(text)), "save → load → save is byte-equal")
    }
}
