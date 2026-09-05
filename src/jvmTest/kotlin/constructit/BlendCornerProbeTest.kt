package constructit

import constructit.SectionFamilyFixture.click
import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.SolidValue
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.Geom3
import constructit.geom.Mesh3
import constructit.geom.Vec2
import constructit.units.mm
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **Orchestrator's probe of the blend corner (GitHub #27/#28)** on a fixture the delivery never saw: a regular
 * hexagon prism — six 120° corners — chamfered round its whole top face against the closed-form volume, then
 * the same six edges chamfered one blend-of-blend at a time in a different order, then the file.
 */
class BlendCornerProbeTest {
    private val side = 40.0
    private val depth = 20.0
    private val setback = 4.0

    private fun solidOf(ed: Editor): Element = ed.doc.elements.last { it.kind == ElementKind.SOLID }

    private val pts = (0 until 6).map { Vec2(side * cos(it * PI / 3), side * sin(it * PI / 3)) }

    /** The middle of hexagon side [i] — where the top rim over that side is picked in the plan (nearest the eye). */
    private fun mid(i: Int): Vec2 = (pts[i] + pts[(i + 1) % 6]) * 0.5

    /** Blend by the real tool, so the step is recorded: [tool] armed with parameter `c`, one click at [at]. */
    private fun blendByTool(
        ed: Editor,
        tool: String,
        at: Vec2,
    ): Element {
        ed.activeScalar = ed.doc.scalars.first { it.name == "c" }
        ed.setTool(tool)
        // one gesture, one **rounding** — which since OP-30 is one entry under one dressed body rather than
        // one more solid, so what counts a gesture is the entry list
        val before = ed.doc.elements.count { it.kind == ElementKind.DRESSING }
        ed.click(at)
        assertEquals(before + 1, ed.doc.elements.count { it.kind == ElementKind.DRESSING }, "$tool at $at: ${ed.statusHint}")
        return solidOf(ed)
    }

    private fun meshOf(el: Element): Mesh3 = ((Evaluator().eval(el.ref.node) as EvalResult.Ok).value as SolidValue).solid.mesh

    private fun volumeOf(el: Element): Double = Geom3.volume(meshOf(el))

    /** A regular hexagon of side 40 about the origin, traced and extruded 20 deep. */
    private fun hexPrism(): Editor {
        val ed = Editor()
        ed.setTool(Tools.SEGMENT)
        for (i in 0 until 6) {
            ed.click(pts[i])
            ed.click(pts[(i + 1) % 6])
        }
        ed.setTool(Tools.OUTLINE)
        ed.click((pts[0] + pts[1]) * 0.5)
        ed.click((pts[1] + pts[2]) * 0.5)
        assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.OUTLINE }, "the hexagon traced: ${ed.statusHint}")
        ed.activeScalar = ed.doc.newParameter("depth", depth.mm)
        ed.setTool(Tools.EXTRUDE)
        // a solid is picked by its footprint: click on the outline, not inside it
        ed.click(mid(0))
        assertEquals(1, ed.doc.elements.count { it.kind == ElementKind.SOLID }, "the prism: ${ed.statusHint}")
        ed.doc.newParameter("c", setback.mm)
        return ed
    }

    /** Closed form: hexagon area x depth, minus a triangular wedge per edge, plus cot(θ/2)·c³/3 per corner. */
    private fun exactChamfered(): Double {
        val area = 3 * sqrt(3.0) / 2 * side * side
        val edges = 6 * (setback * setback / 2) * side
        val corners = 6 * (1 / sqrt(3.0)) * setback * setback * setback / 3
        return area * depth - edges + corners
    }

    @Test
    fun aHexagonsWholeCapChamfersToTheClosedFormAndRoundTrips() {
        val ed = hexPrism()
        val made = blendByTool(ed, Tools.CHAMFER_FACE, mid(0))
        assertTrue("(6 edges)" in ed.statusHint, "all six pieces: ${ed.statusHint}")
        assertManifold(meshOf(made), "the chamfered hexagon cap")
        val v = volumeOf(made)
        assertClose(v / exactChamfered(), 1.0, tol = 1e-5, msg = "a chamfer is exact: $v vs ${exactChamfered()}")

        val saved = DocumentFormat.save(ed.doc)
        val again = DocumentFormat.load(saved)
        assertEquals(saved, DocumentFormat.save(again), "byte-equal round trip")
        val back = again.elements.last { it.kind == ElementKind.SOLID }
        assertClose(volumeOf(back), v, tol = 1e-9, msg = "the reloaded body is the same body")
        assertManifold(meshOf(back), "reloaded")
    }

    /** Six blends of blends, in a scrambled order, arrive at the one-gesture chain's body. */
    @Test
    fun sixSequentialChamfersInAnyOrderEqualTheOneGestureChain() {
        val whole = hexPrism()
        val chain = blendByTool(whole, Tools.CHAMFER_FACE, mid(0))
        val target = volumeOf(chain)

        val ed = hexPrism()
        // one top rim after another, in a scrambled order, each a blend of the blend before it
        for (side in listOf(2, 0, 5, 1, 4, 3)) {
            val el = blendByTool(ed, Tools.CHAMFER_EDGE, mid(side))
            assertTrue("top face" in ed.statusHint, "the top rim over side $side: ${ed.statusHint}")
            assertManifold(meshOf(el), "after chamfering the rim over side $side")
        }
        val v = volumeOf(solidOf(ed))
        assertClose(v / target, 1.0, tol = 1e-6, msg = "six blends of blends = one chain: $v vs $target")
        assertClose(v / exactChamfered(), 1.0, tol = 1e-5, msg = "and both are the closed form")
        val saved = DocumentFormat.save(ed.doc)
        assertEquals(6, saved.lines().count { it.trim().startsWith("tool chamferedge") }, "six recorded steps:\n$saved")
        val again = DocumentFormat.load(saved)
        assertEquals(saved, DocumentFormat.save(again), "six dressed steps round-trip")
        assertClose(volumeOf(again.elements.last { it.kind == ElementKind.SOLID }), v, tol = 1e-9, msg = "and rebuild the same body")
    }

    /** The reporter's fillet on the triangle: retype the radius and the corner follows; undo brings the body back. */
    @Test
    fun theTriangleChainFolllowsItsRadiusAndUndoes() {
        val ed = Editor()
        ed.replaceDocument(
            DocumentFormat.load(
                """
constructit 3
point -81.375,-16.125 -> e1
point 9.375,46.375 -> e2
tool segment pts=e1,e2 clicks=-81.375,-16.125;9.375,46.375 -> e3
point 30,-40 -> e4
tool segment pts=e2,e4 clicks=9.375,46.375;32.125,-40.625 -> e5
tool segment pts=e4,e1 clicks=29.875,-40.125;-81.875,-16.125 -> e6
param "h" = 20mm
tool outline els=e6,e3,e5 clicks=-46.875,-23.625;-59.875,-2.625;19.6875,3.1875 -> e7,e8,e9,e10
tool extrude els=e10 clicks=-28.125,-27.375 scalar="h" -> e11
param "r" = 5mm
""".trimStart(),
            ),
        )
        val r = ed.doc.scalars.first { it.name == "r" }
        ed.activeScalar = r
        ed.setTool(Tools.BLEND_FACE)
        // the body is picked on its footprint's rim; the face that rim is seen from is the top
        ed.click(Vec2((-81.375 + 30.0) / 2, (-16.125 - 40.0) / 2))
        val body = solidOf(ed)
        assertTrue("(3 edges)" in ed.statusHint, "the whole cap: ${ed.statusHint}")
        assertManifold(meshOf(body), "r = 5")
        val v5 = volumeOf(body)
        assertTrue(ed.setParameter(r, 3.0), ed.statusHint)
        val v3 = volumeOf(solidOf(ed))
        assertManifold(meshOf(solidOf(ed)), "r = 3")
        assertTrue(v3 > v5, "a smaller radius removes less: $v3 vs $v5")
        assertTrue(ed.undo(), "undo the retype")
        assertClose(volumeOf(solidOf(ed)), v5, tol = 1e-9, msg = "back to r = 5's body")
    }
}
