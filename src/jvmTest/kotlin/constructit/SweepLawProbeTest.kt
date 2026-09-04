package constructit

import constructit.core.Evaluator
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.editor.Document
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.expr.ExprParser
import constructit.geom.BoolOp
import constructit.geom.Curves3
import constructit.geom.Geom3
import constructit.geom.Loop
import constructit.geom.Mesh3
import constructit.geom.Path3
import constructit.geom.ProfileElement
import constructit.geom.Region
import constructit.geom.Segment
import constructit.geom.SizeLaw
import constructit.geom.SweepProfile
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.units.Dimension
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Probe review of the variable-section sweep (OP-26, session 77) — compositions the package's own
 * tests never exercise:
 *
 * 1. **A tapered swept body is an honest operand of the general boolean.** A swept surface is the
 *    approximated tier by nature, but a *linear* law scales one polygon rigidly, so every lateral
 *    face is a planar trapezoid and the mesh volume is *exactly* the integral of the polygon's own
 *    area law — which makes the boolean's result predictable to arithmetic, not to a band: the
 *    polygon factor cancels between the piece the cut removes and the whole body it is measured
 *    from.
 *
 * 2. **A law that reads a point's coordinate follows the drag, the undo, the redo and the reload.**
 *    The delivery proved the text re-stamps on rename; this drives the *value* through the whole
 *    editing loop — drag `P`, watch the taper follow, undo it back, redo it forward, and reload
 *    into a byte-equal file whose body equals the live one to 1e-12.
 */
class SweepLawProbeTest {
    private val up = Vec3.Z

    private fun straight(
        a: Vec3,
        b: Vec3,
    ) = Path3(Curves3.straightThrough(listOf(a, b)))

    private fun rectangle(
        w: Double,
        h: Double,
    ): Region =
        Region(
            Loop(
                listOf(Vec2(-w / 2, -h / 2), Vec2(w / 2, -h / 2), Vec2(w / 2, h / 2), Vec2(-w / 2, h / 2)).let { pts ->
                    pts.indices.map { ProfileElement.Seg(Segment(pts[it], pts[(it + 1) % pts.size])) }
                },
            ),
            emptyList(),
        )

    private fun radiusLaw(text: String) = SizeLaw(ExprParser.parse(text), emptyMap(), Dimension.LENGTH, "t", text)

    /**
     * A 40 × 20 bar along x ∈ [0, 120] minus a tube along x ∈ [−30, 150] whose radius tapers
     * 6 → 3 mm over its own longer run, so the caps of the two bodies share no plane and the cut
     * is a clean through-bore.
     *
     * The tube's ring is one polygon scaled linearly, so its area is `c·r(x)²` for a constant `c`
     * the test never needs to know: the removed piece is `c·∫₀¹²⁰ r²dx` and the whole tube is
     * `c·∫₋₃₀¹⁵⁰ r²dx`, and the ratio of the two integrals is exact arithmetic on the law itself —
     * `r(x) = 6 − (x+30)/60`, so 2470/3780.
     */
    @Test
    fun aTaperedTubeBoresThroughABarAndTheNumbersAreExact() {
        val bar =
            assertNotNull(
                Geom3.sweep(straight(Vec3(0.0, 0.0, 0.0), Vec3(120.0, 0.0, 0.0)), up, SweepProfile.Section(rectangle(40.0, 20.0))).first,
                "the bar was built",
            )
        val tube =
            assertNotNull(
                Geom3.sweep(straight(Vec3(-30.0, 0.0, 0.0), Vec3(150.0, 0.0, 0.0)), up, SweepProfile.of(radiusLaw("6mm * (1 - t/2)"))).first,
                "the tapered tube was built",
            )
        assertManifold(tube.mesh, "the tapered tube before the boolean")

        val (bored, why) = Geom3.combine(BoolOp.SUBTRACT, bar, tube)
        val result = assertNotNull(bored, "the subtraction was refused: $why")
        assertManifold(result.mesh, "the bar with the tapering bore")

        val barVol = 40.0 * 20.0 * 120.0
        val tubeVol = Geom3.volume(tube.mesh)
        // ∫ r² dx with r linear is L/3 · (r0² + r0·r1 + r1²): 40·(5.5² + 5.5·3.5 + 3.5²) inside the
        // bar against 60·(6² + 6·3 + 3²) for the whole run — the polygon factor cancels in the ratio
        val removed = tubeVol * (40.0 * (30.25 + 19.25 + 12.25)) / (60.0 * (36.0 + 18.0 + 9.0))
        // the boolean's own floating point is the only slack: the agreement is ~3e-5 mm³ on 88 000 mm³,
        // asserted at the boolean tests' house tolerance
        assertClose(Geom3.volume(result.mesh), barVol - removed, tol = 1e-3, msg = "the bore removes exactly its own slice of the tube")

        // and the taper is truly in the hole: the bore's mouth at x = 0 is the law's 5.5, the exit at
        // x = 120 its 3.5 — read off the result's own boundary vertices, not the tube's
        fun bore(x: Double): Double {
            val on = result.mesh.vertices.filter { abs(it.x - x) < 1e-9 && hypot(it.y, it.z) < 10.0 - 1e-9 }
            assertTrue(on.size >= 3, "there is a bore rim at x = $x: ${on.size}")
            return on.maxOf { hypot(it.y, it.z) }
        }
        // t rides the *sampled* arc-length map (the ruling's own metric-tier honesty note), so a rim
        // vertex carries the map's interpolation residual (~3e-7 mm here) — asserted above that noise
        // and far below any real defect
        assertClose(bore(0.0), 5.5, tol = 1e-5, msg = "the mouth is the law at the bar's start")
        assertClose(bore(120.0), 3.5, tol = 1e-5, msg = "and the exit is the law at its end")
    }

    // ---- 2. the law's value through the whole editing loop ----

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
        val a = camera.worldToScreen(from)
        val b = camera.worldToScreen(to)
        pointerMove(a)
        pointerDown(a)
        pointerMove(b)
        pointerUp(b)
    }

    private fun Editor.type(digits: String) {
        for (c in digits) key(c.toString())
        key("Enter")
    }

    private fun solidOf(doc: Document): Element = doc.elements.last { it.kind == ElementKind.SOLID }

    private fun ringRadius(
        mesh: Mesh3,
        x: Double,
    ): Double {
        val on = mesh.vertices.filter { abs(it.x - x) < 1e-9 }
        assertTrue(on.size >= 3, "there is a ring at x = $x: ${on.size}")
        return on.maxOf { hypot(it.y, it.z) }
    }

    private fun radiusAtStart(doc: Document): Double {
        val mesh = Evaluator().solid(solidOf(doc).ref as SolidRef).mesh
        return ringRadius(mesh, 0.0)
    }

    @Test
    fun aLawReadingAPointFollowsTheDragTheUndoAndTheReload() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(40.0, 200.0))
        val p = assertNotNull(ed.doc.elements.lastOrNull { it.isPoint }, "the point was drawn")
        assertEquals("P", ed.doc.nameElement(p, "P"), "and named")

        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(120.0, 0.0))
        ed.setTool(Tools.CURVE3)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(120.0, 0.0))
        ed.key("Enter")

        assertTrue(ed.setSectionLaw("P.x / 4 * (1 - t/2)"), "the law is armed: ${ed.statusHint}")
        ed.setTool(Tools.TUBE)
        ed.type("5")
        ed.click(Vec2(60.0, 0.0))
        assertClose(radiusAtStart(ed.doc), 10.0, tol = 1e-12, msg = "the law reads P.x = 40 into a 10 mm start")

        // drag P to x = 60: the taper follows the coordinate its law reads, with no step re-stamped
        ed.setTool(Tools.SELECT)
        ed.drag(Vec2(40.0, 200.0), Vec2(60.0, 200.0))
        assertClose(radiusAtStart(ed.doc), 15.0, tol = 1e-12, msg = "the dragged coordinate is the new radius: ${ed.statusHint}")

        assertTrue(ed.undo(), "the drag undoes")
        assertClose(radiusAtStart(ed.doc), 10.0, tol = 1e-12, msg = "and the taper follows it back")
        assertTrue(ed.redo(), "the drag redoes")
        assertClose(radiusAtStart(ed.doc), 15.0, tol = 1e-12, msg = "and forward again")

        val text = DocumentFormat.save(ed.doc)
        assertEquals(text, DocumentFormat.save(DocumentFormat.load(text)), "save → load → save is byte-equal")
        assertClose(radiusAtStart(DocumentFormat.load(text)), radiusAtStart(ed.doc), tol = 1e-12, msg = "the reloaded body is the live one")
    }
}
