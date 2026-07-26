package constructit

import constructit.core.ArcValue
import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.ArcRef
import constructit.dsl.LoopRef
import constructit.dsl.arc
import constructit.dsl.loop
import constructit.dsl.resultOf
import constructit.editor.Document
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.GeomMath
import constructit.geom.Vec2
import constructit.units.mm
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **A fillet's legs can be round** (OP-14/OP-1): line–circle and circle–circle, not only line–line.
 *
 * The assertions are tangency itself, in mm: the fillet's centre stands exactly r from a straight leg and
 * exactly R±r from a round one, and its own radius is r. Which of the variants a fillet is — which side of
 * the line, R+r or R−r, which intersection branch — is decided from the two clicks and then **stored**, so a
 * reload rebuilds the same fillet and not a sibling of it.
 */
class FilletCurvesTest {
    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerDown(s)
        pointerUp(s)
    }

    private fun Editor.segment(
        a: Vec2,
        b: Vec2,
    ) {
        setTool(Tools.SEGMENT)
        click(a)
        click(b)
    }

    private fun Editor.circleAt(
        centre: Vec2,
        radius: Double,
        name: String,
    ) {
        setTool(Tools.CIRCLE_R)
        activeScalar = doc.newParameter(name, radius.mm)
        click(centre)
    }

    private fun Editor.fillet(
        radius: Double,
        name: String,
        onFirst: Vec2,
        onSecond: Vec2,
    ) {
        activeScalar = doc.newParameter(name, radius.mm)
        setTool(Tools.FILLET)
        click(onFirst)
        click(onSecond)
    }

    private fun lastArc(doc: Document): ArcValue? {
        val el = doc.elements.lastOrNull { it.kind == ElementKind.ARC } ?: return null
        @Suppress("UNCHECKED_CAST")
        return Evaluator().valueOfOrNull(el.ref as ArcRef)
    }

    private fun Evaluator.valueOfOrNull(ref: ArcRef): ArcValue? = (resultOf(ref) as? EvalResult.Ok)?.value as? ArcValue

    // ---- line and circle ----

    /**
     * A horizontal segment along y=0 and a circle of R=10 at (0,20): the 10 mm gap between them takes a
     * fillet of r=10, whose centre lands at y=10 and 20 mm from the circle's centre.
     */
    private fun lineCircleScene(): Editor {
        val ed = Editor()
        ed.segment(Vec2(-80.0, 0.0), Vec2(80.0, 0.0))
        ed.circleAt(Vec2(0.0, 20.0), 10.0, "R")
        return ed
    }

    @Test
    fun aFilletBetweenALineAndACircleTouchesBoth() {
        val ed = lineCircleScene()
        // clicked to the right on both legs: the right-hand tangency is meant
        ed.fillet(10.0, "r", Vec2(30.0, 0.0), Vec2(8.0, 13.0))

        val a = assertNotNull(lastArc(ed.doc), "a line and a circle must produce a fillet arc")
        assertClose(a.arc.radius, 10.0, msg = "the fillet has the radius asked for")
        assertClose(a.arc.center.y, 10.0, msg = "distance centre->line = r (the line is y=0)")
        assertClose((a.arc.center - Vec2(0.0, 20.0)).length(), 20.0, msg = "distance centre->circle centre = R + r")
        assertTrue(a.arc.center.x > 0.0, "the side clicked is the side built (${a.arc.center})")
        assertClose(a.arc.center.x, kotlin.math.sqrt(300.0), tol = 1e-9)

        // the arc runs between the two tangencies: one on the line, one on the circle
        val start = GeomMath.arcStart(a.arc)
        val end = GeomMath.arcEnd(a.arc)
        val onLine = listOf(start, end).minByOrNull { abs(it.y) }!!
        val onCircle = listOf(start, end).maxByOrNull { abs(it.y) }!!
        assertClose(onLine.y, 0.0, msg = "one end sits on the line")
        assertClose((onCircle - Vec2(0.0, 20.0)).length(), 10.0, msg = "the other sits on the circle")
        assertTrue(abs(GeomMath.sweep(a.arc)) < kotlin.math.PI, "a fillet is the minor arc")
        assertEquals(1, ed.doc.journal.count { it.kind == "tool" && it.creates.size == 1 && it.creates[0].kind == ElementKind.ARC })
    }

    /** The clicks choose the branch, and the choice is *stored*: a reload rebuilds the same fillet. */
    @Test
    fun theClickedSideIsStoredAndSurvivesAReload() {
        fun filletCentre(onLineX: Double): Vec2 {
            val ed = lineCircleScene()
            ed.fillet(10.0, "r", Vec2(onLineX, 0.0), Vec2(if (onLineX > 0) 8.0 else -8.0, 13.0))
            return assertNotNull(lastArc(ed.doc)).arc.center
        }
        assertTrue(filletCentre(30.0).x > 0.0, "clicked right, built right")
        assertTrue(filletCentre(-30.0).x < 0.0, "clicked left, built left")

        val ed = lineCircleScene()
        ed.fillet(10.0, "r", Vec2(-30.0, 0.0), Vec2(-8.0, 13.0))
        val before = assertNotNull(lastArc(ed.doc)).arc
        val text = DocumentFormat.save(ed.doc)
        val after = assertNotNull(lastArc(DocumentFormat.load(text))).arc
        assertClose(after.center.x, before.center.x, tol = 1e-12, msg = "the reloaded fillet is the same one")
        assertClose(after.center.y, before.center.y, tol = 1e-12)
        assertEquals(text, DocumentFormat.save(DocumentFormat.load(text)), "save -> load -> save must be identical")
    }

    /**
     * The R−r variant: a fillet **inside** a circle, tangent to a chord line. The clicks pick it over the
     * outside one, and the tangency assertion is the same statement with the other sign.
     */
    @Test
    fun aFilletInsideACircleTakesTheInnerOffset() {
        val ed = Editor()
        ed.segment(Vec2(-60.0, -20.0), Vec2(60.0, -20.0))
        ed.circleAt(Vec2(0.0, 0.0), 50.0, "R")
        // both clicks in the upper right of the lens between chord and circle
        ed.fillet(10.0, "r", Vec2(38.0, -20.0), Vec2(48.0, -13.0))

        val a = assertNotNull(lastArc(ed.doc)).arc
        assertClose(a.radius, 10.0)
        assertClose(a.center.y + 20.0, 10.0, msg = "10 mm above the chord")
        assertClose(a.center.length(), 40.0, msg = "R - r from the circle's centre: tangent from inside")
        assertTrue(a.center.x > 0.0, "and on the clicked side")
    }

    // ---- circle and circle ----

    @Test
    fun aFilletBetweenTwoCirclesTouchesBoth() {
        val ed = Editor()
        ed.circleAt(Vec2(-30.0, 0.0), 20.0, "R1")
        ed.circleAt(Vec2(30.0, 0.0), 20.0, "R2")
        // clicked on the upper inner flanks: the fillet bridging the 20 mm gap above the axis
        ed.fillet(20.0, "r", Vec2(-16.0, 14.0), Vec2(16.0, 14.0))

        val a = assertNotNull(lastArc(ed.doc), "two circles must produce a fillet arc").arc
        assertClose(a.radius, 20.0)
        assertClose((a.center - Vec2(-30.0, 0.0)).length(), 40.0, msg = "R1 + r")
        assertClose((a.center - Vec2(30.0, 0.0)).length(), 40.0, msg = "R2 + r")
        assertClose(a.center.x, 0.0, tol = 1e-9)
        assertTrue(a.center.y > 0.0, "the branch the clicks indicated")

        // clicking below builds the mirror image, which is the other stored branch
        val down = Editor()
        down.circleAt(Vec2(-30.0, 0.0), 20.0, "R1")
        down.circleAt(Vec2(30.0, 0.0), 20.0, "R2")
        down.fillet(20.0, "r", Vec2(-16.0, -14.0), Vec2(16.0, -14.0))
        assertTrue(assertNotNull(lastArc(down.doc)).arc.center.y < 0.0)
    }

    /** A radius the geometry cannot admit is invalid with a reason, and heals when it can (OP-3). */
    @Test
    fun aRadiusThatCannotReachIsInvalidAndHeals() {
        val ed = Editor()
        ed.circleAt(Vec2(-30.0, 0.0), 20.0, "R1")
        ed.circleAt(Vec2(30.0, 0.0), 20.0, "R2")
        // the gap between the circles is 20 mm, so nothing under r=10 can bridge it
        ed.fillet(3.0, "r", Vec2(-16.0, 14.0), Vec2(16.0, 14.0))

        val el = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.ARC }, "the element exists even so")
        val bad = Evaluator().resultOf(el.ref)
        assertTrue(bad is EvalResult.Invalid, "no tangent circle of that radius exists: $bad")
        assertTrue(
            (bad as EvalResult.Invalid).reason.contains("tangent"),
            "and the reason says what is missing: ${bad.reason}",
        )

        // widen the radius: the same nodes now have a solution — nothing is rebuilt (OP-3's auto-heal)
        ed.doc.setParameter(ed.doc.scalars.single { it.name == "r" }, 20.0.mm)
        val healed = Evaluator().resultOf(el.ref)
        assertTrue(healed is EvalResult.Ok, "it heals: $healed")
        assertClose(Evaluator().arc(el.ref as ArcRef).radius, 20.0)
    }

    // ---- and the boundary follow walks through one ----

    /**
     * A segment, an arc that shares one end with it, and a fillet rounding the *other* corner: two clicks
     * trace the whole closed boundary, the fillet's tangencies being joints the construction registered.
     */
    @Test
    fun anOutlineFollowsThroughALineArcFillet() {
        val ed = Editor()
        // the chord, and an arc bulging above it through (0,30)
        ed.setTool(Tools.POINT)
        ed.click(Vec2(-50.0, 0.0))
        ed.click(Vec2(50.0, 0.0))
        ed.click(Vec2(0.0, 30.0))
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(-50.0, 0.0))
        ed.click(Vec2(50.0, 0.0))
        ed.setTool(Tools.ARC_3)
        ed.click(Vec2(-50.0, 0.0))
        ed.click(Vec2(0.0, 30.0))
        ed.click(Vec2(50.0, 0.0))
        assertEquals(1, ed.doc.elements.count { it.kind == ElementKind.ARC })

        // round the left corner, where chord and arc meet at (-50,0)
        ed.fillet(8.0, "r", Vec2(-30.0, 0.0), Vec2(-40.0, 12.0))
        assertEquals(2, ed.doc.elements.count { it.kind == ElementKind.ARC }, "the fillet arc joins them")
        val fillet = assertNotNull(lastArc(ed.doc)).arc
        assertClose(fillet.center.y, 8.0, msg = "tangent to the chord: centre 8 mm above it")

        ed.setTool(Tools.OUTLINE)
        ed.click(Vec2(0.0, 0.0)) // the chord
        ed.click(GeomMath.sampleArc(fillet, 2)[1]) // the middle of the fillet arc

        val outline = assertNotNull(ed.doc.elements.singleOrNull { it.kind == ElementKind.OUTLINE }, "chord, fillet and arc close")

        @Suppress("UNCHECKED_CAST")
        val ref = outline.ref as LoopRef
        assertTrue(Evaluator().resultOf(ref) is EvalResult.Ok, "the boundary must close: ${Evaluator().resultOf(ref)}")
        assertEquals(3, Evaluator().loop(ref).elements.size, "chord, fillet arc, bulging arc")
        assertTrue(abs(GeomMath.signedArea(Evaluator().loop(ref))) > 1000.0, "and it encloses the bulge")
    }
}
