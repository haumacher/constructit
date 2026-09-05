package constructit

import constructit.core.ArcValue
import constructit.core.BezierValue
import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.SolidValue
import constructit.dsl.valueOf
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.Geom3
import constructit.geom.GeomMath
import constructit.geom.Vec2
import constructit.units.mm
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **Orchestrator's probe of the planar join (GitHub #34)** on what the delivery never saw: the join's two ends on
 * an **arc** and a **Bézier** rather than two segments, two joins closing one figure that *Outline* then takes and
 * *Extrude* makes a body of, the join **following** a dragged source point, a **break** on the join, and the file.
 */
class ConnectInPlaneProbeTest {
    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerMove(s)
        pointerDown(s)
        pointerUp(s)
    }

    private fun Editor.drag(
        from: Vec2,
        to: Vec2,
    ) {
        pointerDown(camera.worldToScreen(from))
        pointerMove(camera.worldToScreen(to))
        pointerUp(camera.worldToScreen(to))
    }

    private fun Editor.byKind(kind: ElementKind): List<Element> = doc.elements.filter { it.kind == kind }

    private fun reasonOf(el: Element): String? = (Evaluator().eval(el.ref.node) as? EvalResult.Invalid)?.reason

    private fun bezier(el: Element) = (Evaluator().valueOf(el.ref) as BezierValue).bezier

    private fun arcEnd(
        el: Element,
        atStart: Boolean,
    ): Pair<Vec2, Vec2> {
        val a = (Evaluator().valueOf(el.ref) as ArcValue).arc
        val t = if (atStart) a.startAngle else a.endAngle
        val p = a.center + Vec2(cos(t), sin(t)) * a.radius
        // the tangent along the arc's own direction of travel
        val tangent = Vec2(-sin(t), cos(t)) * (if (a.ccw) 1.0 else -1.0)
        return p to tangent
    }

    private fun assertAt(
        actual: Vec2,
        expected: Vec2,
        msg: String,
    ) {
        assertTrue((actual - expected).length() < 1e-9, "$msg: $actual vs $expected")
    }

    private fun assertParallel(
        a: Vec2,
        b: Vec2,
        msg: String,
    ) {
        assertTrue(abs(a.normalized().cross(b.normalized())) < 1e-9, "$msg: $a not along $b")
    }

    // the figure: an arc on the left, a Bézier on the right, joined top and bottom
    private val a0 = Vec2(-40.0, -20.0)
    private val aMid = Vec2(-55.0, 0.0)
    private val a1 = Vec2(-40.0, 20.0)
    private val b0 = Vec2(40.0, 25.0)
    private val bc1 = Vec2(60.0, 10.0)
    private val bc2 = Vec2(60.0, -10.0)
    private val b1 = Vec2(40.0, -25.0)

    /** The arc and the Bézier drawn, the two joins made by clicking near the ends they join. */
    private fun figure(): Editor {
        val ed = Editor()
        ed.setTool(Tools.ARC_3)
        ed.click(a0)
        ed.click(aMid)
        ed.click(a1)
        ed.setTool(Tools.BEZIER)
        ed.click(b0)
        ed.click(bc1)
        ed.click(bc2)
        ed.click(b1)
        assertEquals(1, ed.byKind(ElementKind.ARC).size, ed.statusHint)
        assertEquals(1, ed.byKind(ElementKind.BEZIER).size, ed.statusHint)
        val arc = ed.byKind(ElementKind.ARC).single()
        val bez = ed.byKind(ElementKind.BEZIER).single()
        // top join: from the arc's end (a1) to the Bézier's start (b0)
        ed.setTool(Tools.CONNECT)
        ed.click(arcEnd(arc, atStart = false).first + Vec2(-1.0, -1.5))
        ed.click(GeomMath.bezierPointAt(bezier(bez), 0.05))
        assertEquals(2, ed.byKind(ElementKind.BEZIER).size, "the top join: ${ed.statusHint}")
        // bottom join: from the Bézier's end (b1) to the arc's start (a0)
        ed.setTool(Tools.CONNECT)
        ed.click(GeomMath.bezierPointAt(bezier(bez), 0.95))
        ed.click(arcEnd(arc, atStart = true).first + Vec2(-1.0, 1.5))
        assertEquals(3, ed.byKind(ElementKind.BEZIER).size, "the bottom join: ${ed.statusHint}")
        return ed
    }

    @Test
    fun aJoinBetweenAnArcAndABezierIsADrawingCurveMeetingBothTangentially() {
        val ed = figure()
        val arc = ed.byKind(ElementKind.ARC).single()
        val (drawn, joins) = ed.byKind(ElementKind.BEZIER).let { it.first() to it.drop(1) }
        for (j in joins) assertEquals(null, reasonOf(j), "a valid join")
        val top = bezier(joins[0])
        val bottom = bezier(joins[1])
        val (aEnd, aEndT) = arcEnd(arc, atStart = false)
        val (aStart, aStartT) = arcEnd(arc, atStart = true)
        val d = bezier(drawn)

        // ends land exactly on the ends they were clicked near, whichever way round the join runs
        fun endsOf(b: constructit.geom.Bezier) = setOf(b.p0, b.p3)
        assertTrue(endsOf(top).any { (it - aEnd).length() < 1e-9 } && endsOf(top).any { (it - d.p0).length() < 1e-9 }, "top join ends: $top")
        assertTrue(endsOf(bottom).any { (it - aStart).length() < 1e-9 } && endsOf(bottom).any { (it - d.p3).length() < 1e-9 }, "bottom join ends: $bottom")
        // G1 at the arc: the join's control leg at the arc end is along the arc's tangent there
        val topArcLeg = if ((top.p0 - aEnd).length() < 1e-9) top.p1 - top.p0 else top.p2 - top.p3
        assertParallel(topArcLeg, aEndT, "top join leaves the arc along it")
        val botArcLeg = if ((bottom.p0 - aStart).length() < 1e-9) bottom.p1 - bottom.p0 else bottom.p2 - bottom.p3
        assertParallel(botArcLeg, aStartT, "bottom join meets the arc along it")
        // G1 at the Bézier: along its own end tangents
        val topBezLeg = if ((top.p0 - d.p0).length() < 1e-9) top.p1 - top.p0 else top.p2 - top.p3
        assertParallel(topBezLeg, d.p1 - d.p0, "top join meets the Bézier along its start tangent")
        val botBezLeg = if ((bottom.p0 - d.p3).length() < 1e-9) bottom.p1 - bottom.p0 else bottom.p2 - bottom.p3
        assertParallel(botBezLeg, d.p3 - d.p2, "bottom join leaves the Bézier along its end tangent")
    }

    @Test
    fun theTwoJoinsCloseAnOutlineThatExtrudesToAWatertightBody() {
        val ed = figure()
        // round the boundary in order: the arc, the top join, the drawn Bézier, the bottom join
        val bez = ed.byKind(ElementKind.BEZIER)
        val pieces = listOf(ed.byKind(ElementKind.ARC).single(), bez[1], bez[0], bez[2])
        ed.setTool(Tools.OUTLINE)
        for (p in pieces) {
            val mid =
                if (p.kind == ElementKind.ARC) {
                    val a = (Evaluator().valueOf(p.ref) as ArcValue).arc
                    a.center + Vec2(cos(Math.PI), sin(Math.PI)) * a.radius
                } else {
                    GeomMath.bezierPointAt(bezier(p), 0.5)
                }
            ed.click(mid)
            if (ed.byKind(ElementKind.OUTLINE).isNotEmpty()) break
        }
        if (ed.byKind(ElementKind.OUTLINE).isEmpty()) ed.click(aMid)
        val outline = assertNotNull(ed.byKind(ElementKind.OUTLINE).lastOrNull(), "the outline closed: ${ed.statusHint}")
        assertEquals(null, reasonOf(outline), "a valid outline")
        ed.activeScalar = ed.doc.newParameter("h", 10.0.mm)
        ed.setTool(Tools.EXTRUDE)
        // an extrude is picked by clicking the outline itself: its arc, at the arc's own apex
        ed.click(aMid)
        val solid = assertNotNull(ed.byKind(ElementKind.SOLID).lastOrNull(), "the body: ${ed.statusHint}")
        val mesh = (Evaluator().valueOf(solid.ref) as SolidValue).solid.mesh
        assertManifold(mesh, "the extruded figure")
        // the figure spans about 110 x 50, so its body is in the tens of thousands of mm³ and not a sliver
        assertTrue(Geom3.volume(mesh) > 30000.0, "a real body: ${Geom3.volume(mesh)}")
        // the file holds it
        val once = DocumentFormat.save(ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "save → load → save is a fixed point")
        assertTrue(DocumentFormat.load(once).loadNotes.isEmpty(), "no load notes on a current file")
    }

    @Test
    fun theJoinFollowsADraggedSourcePointAndUndoBringsItBack() {
        val ed = figure()
        val drawn = ed.byKind(ElementKind.BEZIER).first()
        val before = bezier(ed.byKind(ElementKind.BEZIER)[1])
        // drag the Bézier's start point, which the top join lands on
        ed.setTool(Tools.SELECT)
        ed.drag(b0, b0 + Vec2(5.0, 7.0))
        val moved = bezier(drawn)
        assertAt(moved.p0, b0 + Vec2(5.0, 7.0), "the source point moved")
        val after = bezier(ed.byKind(ElementKind.BEZIER)[1])
        assertTrue(setOf(after.p0, after.p3).any { (it - moved.p0).length() < 1e-9 }, "the join followed the point: $after")
        assertTrue((after.p0 - before.p0).length() > 1e-6 || (after.p3 - before.p3).length() > 1e-6, "the join is not where it was")
        ed.undo()
        val back = bezier(ed.doc.elements.filter { it.kind == ElementKind.BEZIER }[1])
        assertAt(back.p0, before.p0, "undo restores the join's start")
        assertAt(back.p3, before.p3, "undo restores the join's end")
    }

    @Test
    fun aJoinCanBeBrokenLikeAnyDrawnBezier() {
        val ed = figure()
        val join = ed.byKind(ElementKind.BEZIER)[1]
        val at = GeomMath.bezierPointAt(bezier(join), 0.5)
        val count = ed.byKind(ElementKind.BEZIER).size
        ed.setTool(Tools.BREAK_LEG)
        ed.click(at)
        // a break keeps the broken curve, hidden, and adds its two halves (OP-19)
        assertEquals(count + 2, ed.byKind(ElementKind.BEZIER).size, "the join broke into two halves: ${ed.statusHint}")
        assertTrue(!join.visible, "the join itself stays, hidden: ${ed.statusHint}")
        for (b in ed.byKind(ElementKind.BEZIER)) assertEquals(null, reasonOf(b), "every piece valid")
        val halves = ed.byKind(ElementKind.BEZIER).takeLast(2).map { bezier(it) }
        // the split is at the curve point nearest the click, found along the curve's own chords
        assertTrue(halves.any { (it.p0 - at).length() < 0.05 || (it.p3 - at).length() < 0.05 }, "a half ends at the break point $at: $halves")
        assertAt(halves[0].p3, halves[1].p0, "the two halves meet")
    }
}
