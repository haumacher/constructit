package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.LoopRef
import constructit.dsl.loop
import constructit.dsl.resultOf
import constructit.editor.Arg
import constructit.editor.Camera
import constructit.editor.Document
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.Styles
import constructit.editor.SvgDrawTarget
import constructit.editor.Tools
import constructit.geom.GeomMath
import constructit.geom.Vec2
import constructit.units.mm
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sin
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **Tracing a boundary, told and followed** (OP-14).
 *
 * Two halves of one complaint — *"I click curves and nothing tells me where I am"* and *"I have to click
 * every piece of a corner I already constructed"*:
 *
 * - the tool *reports*: picks are drawn in the pick colour, a click that hits nothing says so, and the count
 *   is on the status line throughout;
 * - the tool *follows*: after two picks fix the direction it appends every piece whose continuation is
 *   unique, and closes when it comes back to the first — the joint registry a fillet or chamfer wrote being
 *   what it reads. The **recorded step still lists every piece in order**, so replay re-runs the same
 *   construction and OP-14's rejection of discovered loops is untouched.
 */
class OutlineFollowTest {
    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerDown(s)
        pointerUp(s)
    }

    /** The elements a step names in `els=`, in order — what replay will re-run. */
    private fun stepElements(
        doc: Document,
        toolId: String,
    ): List<String> {
        val step =
            doc.journal.last { it.kind == "tool" && (it.args.firstOrNull() as? Arg.Text)?.s == toolId }
        val els = step.args.filterIsInstance<Arg.Keyed>().first { it.key == "els" }.value as Arg.Els
        return els.els.map { it.id }
    }

    // ---- 1. feedback while picking ----

    @Test
    fun aClickThatHitsNothingSaysSoAndKeepsTheCount() {
        val ed = Editor()
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(60.0, 0.0))
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(60.0, 0.0))
        ed.click(Vec2(60.0, 60.0))

        ed.setTool(Tools.OUTLINE)
        ed.click(Vec2(30.0, 0.0))
        assertTrue(ed.statusHint.contains("1 picked"), "the count is on the status line: ${ed.statusHint}")

        ed.click(Vec2(-200.0, -200.0)) // empty space
        // in the tool's own word for what it collects, since a repeating tool need not collect curves at all
        assertTrue(ed.statusHint.contains("hit no boundary curve"), "a miss must say it missed: ${ed.statusHint}")
        assertTrue(ed.statusHint.contains("1 picked so far"), "and say where the operation stands: ${ed.statusHint}")
        assertEquals(1, ed.pendingCount, "a miss picks nothing")
        assertEquals(1, ed.toolPicks.size)
    }

    @Test
    fun pickedPiecesAreDrawnInThePickColour() {
        val ed = Editor()
        ed.canvasW = 320.0
        ed.canvasH = 240.0
        ed.camera = Camera.centered(ed.canvasW, ed.canvasH)
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(-40.0, -20.0))
        ed.click(Vec2(40.0, -20.0))

        val before = SvgDrawTarget()
        ed.render(before)
        assertTrue(!before.svg().contains(Styles.PICKED.stroke), "nothing is picked yet")

        ed.setTool(Tools.OUTLINE)
        ed.click(Vec2(0.0, -20.0))
        val after = SvgDrawTarget()
        ed.render(after)
        assertTrue(after.svg().contains(Styles.PICKED.stroke), "a picked piece is drawn in the pick colour")
        val pickLine = after.svg().lines().first { it.contains(Styles.PICKED.stroke) }
        assertTrue(
            pickLine.contains("stroke-width=\"4"),
            "and in the pick weight, so it does not read as the selection: $pickLine",
        )
        // and it is *not* the selection colour: a pick is half an operation, not a subject
        assertTrue(Styles.PICKED.stroke != "#1f77b4")
    }

    // ---- 2. the follow, on the user's own drawing ----

    /**
     * The user's showcase file: a triangle with two chamfered corners and one filleted corner. Six boundary
     * pieces, and **two clicks** — one on a bevel, one on the leg next to it — are the whole trace.
     */
    private val triangle =
        """
        constructit 1
        point -18.75,85 -> e1
        point -44.25,-17.25 -> e2
        tool segment pts=e1,e2 clicks=-18.75,85;-44.25,-17.25 -> e3
        point 76,39 -> e4
        tool segment pts=e4,e1 clicks=69.5,54.5;-18,85.25 -> e5
        tool segment pts=e4,e2 clicks=70.25,53.25;-44.5,-17.75 -> e6
        param "r" = 20mm
        tool chamfer els=e3,e6 clicks=-36.5,14;-14.75,0.75 scalar="r" -> e7,e8,e9
        tool chamfer els=e5,e6 clicks=55.75,59.75;57.5,46.75 scalar="r" -> e10,e11,e12
        tool fillet els=e3,e5 clicks=-27.75,48.5;12.5,74.5 scalar="r" -> e13
        """.trimIndent() + "\n"

    private val vA = Vec2(-18.75, 85.0) // the filleted corner
    private val vB = Vec2(-44.25, -17.25) // chamfered
    private val vC = Vec2(76.0, 39.0) // chamfered

    /** Area of the triangle less the two bevels and the rounding — the closed form the trace must produce. */
    private fun expectedArea(r: Double): Double {
        fun angleAt(
            v: Vec2,
            p: Vec2,
            q: Vec2,
        ): Double {
            val a = (p - v).normalized()
            val b = (q - v).normalized()
            return acos((a.dot(b)).coerceIn(-1.0, 1.0))
        }
        val whole = abs((vB - vA).cross(vC - vA)) / 2.0
        val bevelB = 0.5 * r * r * sin(angleAt(vB, vA, vC))
        val bevelC = 0.5 * r * r * sin(angleAt(vC, vA, vB))
        val theta = angleAt(vA, vB, vC)
        val round = r * r / tan(theta / 2.0) - 0.5 * r * r * (PI - theta)
        return whole - bevelB - bevelC - round
    }

    private fun triangleEditor(): Editor {
        val ed = Editor()
        ed.replaceDocument(DocumentFormat.load(triangle))
        return ed
    }

    /** A point on the bevel that cut corner [v] off, between legs toward [p] and [q]. */
    private fun bevelMid(
        v: Vec2,
        p: Vec2,
        q: Vec2,
        r: Double = 20.0,
    ): Vec2 = (v + (p - v).normalized() * r + (v + (q - v).normalized() * r)) * 0.5

    @Test
    fun theUsersTriangleClosesInTwoClicks() {
        val ed = triangleEditor()
        assertEquals(6, ed.doc.elements.count { it.isCurve }, "three legs, two bevels and a fillet arc")

        ed.setTool(Tools.OUTLINE)
        ed.click(bevelMid(vB, vA, vC)) // the bevel at the chamfered corner
        ed.click(vB + (vA - vB).normalized() * 63.0) // the leg it hands over to

        val outline = assertNotNull(ed.doc.elements.singleOrNull { it.kind == ElementKind.OUTLINE }, "two clicks must close it")
        assertTrue(ed.statusHint.contains("Followed"), "the status line narrates the follow: ${ed.statusHint}")

        @Suppress("UNCHECKED_CAST")
        val ref = outline.ref as LoopRef
        val ev = Evaluator()
        assertTrue(ev.resultOf(ref) is EvalResult.Ok, "the followed boundary must close: ${ev.resultOf(ref)}")
        val l = ev.loop(ref)
        assertEquals(6, l.elements.size, "bevel, leg, fillet arc, leg, bevel, leg")
        assertClose(abs(GeomMath.signedArea(l)), expectedArea(20.0), tol = 1e-6, msg = "triangle less two bevels and one rounding")

        // the *step* carries the whole ordered boundary: replay re-runs it and never re-discovers it (OP-14)
        assertEquals(6, stepElements(ed.doc, Tools.OUTLINE).size, "all six pieces are recorded")

        // and it round-trips byte for byte, followed picks included
        val once = DocumentFormat.save(ed.doc)
        val twice = DocumentFormat.save(DocumentFormat.load(once))
        assertEquals(once, twice, "save -> load -> save must be identical")
        val reloaded = DocumentFormat.load(once)

        @Suppress("UNCHECKED_CAST")
        val back = reloaded.elements.single { it.kind == ElementKind.OUTLINE }.ref as LoopRef
        assertClose(abs(GeomMath.signedArea(Evaluator().loop(back))), expectedArea(20.0), tol = 1e-6, msg = "reloaded")
    }

    /** The traced boundary is still a function of the construction: retype the radius, everything follows. */
    @Test
    fun theFollowedBoundaryStillFollowsItsParameter() {
        val ed = triangleEditor()
        ed.setTool(Tools.OUTLINE)
        ed.click(bevelMid(vB, vA, vC))
        ed.click(vB + (vA - vB).normalized() * 63.0)

        @Suppress("UNCHECKED_CAST")
        val ref = ed.doc.elements.single { it.kind == ElementKind.OUTLINE }.ref as LoopRef
        ed.doc.setParameter(ed.doc.scalars.single { it.editable && it.name == "r" }, 12.0.mm)
        assertClose(
            abs(GeomMath.signedArea(Evaluator().loop(ref))),
            expectedArea(12.0),
            tol = 1e-6,
            msg = "the bevels, the rounding and every trim follow the radius",
        )
    }

    // ---- 3. where the follow stops ----

    /** Three segments meeting at one point: the continuation is a genuine choice, so the follow stops. */
    @Test
    fun aForkStopsTheFollowAndSaysWhy() {
        val ed = Editor()

        // a vertical piece, a horizontal piece, then two more from the same corner: a T
        fun seg(
            a: Vec2,
            b: Vec2,
        ) {
            ed.setTool(Tools.SEGMENT)
            ed.click(a)
            ed.click(b)
        }
        seg(Vec2(-50.0, 50.0), Vec2(-50.0, 0.0))
        seg(Vec2(-50.0, 0.0), Vec2(0.0, 0.0))
        seg(Vec2(0.0, 0.0), Vec2(50.0, 0.0))
        seg(Vec2(0.0, 0.0), Vec2(0.0, 50.0))

        ed.setTool(Tools.OUTLINE)
        ed.click(Vec2(-50.0, 25.0)) // the vertical piece
        ed.click(Vec2(-25.0, 0.0)) // the horizontal one: entered at (-50,0)
        assertEquals(2, ed.pendingCount, "the fork past (0,0) is a choice, so nothing is followed")
        assertTrue(ed.statusHint.contains("forks"), "and the tool says why: ${ed.statusHint}")
        assertTrue(ed.statusHint.contains("2 picked"), "with the count: ${ed.statusHint}")

        // manual picking still works right through the fork — the follow refuses nothing a click allowed
        ed.click(Vec2(25.0, 0.0))
        ed.click(Vec2(0.0, 25.0))
        assertEquals(4, ed.pendingCount)
        assertTrue(ed.key("Escape"), "no boundary is closed here; the point is that picking was not blocked")
    }

    /** A dead end is not a fork: the follow stops, says so, and the manual trace carries on. */
    @Test
    fun aDeadEndStopsTheFollow() {
        val ed = Editor()
        ed.setTool(Tools.LINE)
        ed.click(Vec2(-60.0, 0.0))
        ed.click(Vec2(60.0, 0.0))
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(0.0, 40.0))

        ed.setTool(Tools.OUTLINE)
        ed.click(Vec2(20.0, 0.0)) // the infinite line: no endpoints at all
        ed.click(Vec2(0.0, 20.0)) // the segment
        assertEquals(2, ed.pendingCount, "an infinite line offers nothing to follow")
        assertTrue(ed.statusHint.contains("2 picked"), "the count stays visible: ${ed.statusHint}")
    }

    /** Manual multi-pick tracing of four infinite lines is untouched by the follow. */
    @Test
    fun manualTracingOfLinesIsUnchanged() {
        val ed = Editor()
        ed.setTool(Tools.LINE)
        ed.click(Vec2(-50.0, 0.0))
        ed.click(Vec2(50.0, 0.0))
        ed.click(Vec2(40.0, -50.0))
        ed.click(Vec2(40.0, 50.0))
        ed.click(Vec2(-50.0, 30.0))
        ed.click(Vec2(50.0, 30.0))
        ed.click(Vec2(-20.0, -50.0))
        ed.click(Vec2(-20.0, 50.0))

        ed.setTool(Tools.OUTLINE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(40.0, 15.0))
        ed.click(Vec2(0.0, 30.0))
        ed.click(Vec2(-20.0, 15.0))
        assertTrue(ed.key("Enter"))
        @Suppress("UNCHECKED_CAST")
        val ref = ed.doc.elements.single { it.kind == ElementKind.OUTLINE }.ref as LoopRef
        assertClose(GeomMath.signedArea(Evaluator().loop(ref)), 60.0 * 30.0, tol = 1e-9)
    }
}
