package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.editor.Camera
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.Mesh3
import constructit.geom.Vec2
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The reviewer's probe for the anchored sweep (session 54, GitHub #15): compositions the package's own
 * tests never tried.
 *
 * - **The anchor is a route into OP-3's healing**, not only a way to move a valid body: drag the anchor far
 *   enough and the very same reach-vs-bend condition the unanchored sweep is refused by takes hold — as node
 *   invalidity naming the new reach — and dragging the anchor back heals it.
 * - **A typed roll turns the section about the anchor**, not about the section's own origin: the two readings
 *   differ by thirty millimetres here, so the bounds decide it.
 * - **A point in space clicked for the anchor slot is never adopted silently**: the anchor is a point of the
 *   section's own *plane* (the cross-plane refusal's own words), and a rider's plan dot standing exactly on
 *   the coil must not be quietly flattened into one.
 */
class SweepAnchorProbeTest {
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

    private fun Editor.solids(): List<Element> = doc.elements.filter { it.kind == ElementKind.SOLID }

    @Suppress("UNCHECKED_CAST")
    private fun meshOf(el: Element): Mesh3 = Evaluator().solid(el.ref as SolidRef).mesh

    private fun whyInvalid(el: Element): String? = (Evaluator().eval(el.ref.node) as? EvalResult.Invalid)?.reason

    /** The coil (radius 30, pitch 10, two turns) and the 2×2 square at (30..32, 5..7) — the package's own fixture. */
    private fun coilAndSquare(): Editor {
        val ed = Editor()
        ed.camera = Camera(-800.0, 500.0, 40.0)
        ed.setTool(Tools.HELIX)
        ed.type("30")
        ed.type("10")
        ed.type("2")
        ed.click(Vec2(0.0, 0.0))
        assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.SPACE_CURVE }, "the coil: ${ed.statusHint}")
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(30.0, 5.0))
        ed.click(Vec2(32.0, 7.0))
        return ed
    }

    /**
     * **Dragging the anchor off the coil is the refusal, and dragging it back is the healing** (OP-3 through
     * the new input). An anchor of the section's own plane but *not* of the section: at (29, 4) the section
     * reads 1..3 across and 1..3 up, a reach of `√18 = 4.24 mm` — which needs 8.49 mm of clearance between
     * windings where the 10 mm pitch grants 9.99 mm, and stands well inside the 30.084 mm bend. Dragged to
     * (−30, 4) the same section reads 60..62 across — `√(62² + 3²) = 62.07 mm` of reach — and the *node* says
     * so; back at (29, 4) the body returns exactly.
     */
    @Test
    fun draggingTheAnchorPastTheBendInvalidatesByNameAndBackHeals() {
        val ed = coilAndSquare()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(29.0, 4.0))
        ed.setTool(Tools.SWEEP)
        ed.click(Vec2(30.0, 0.0))
        ed.click(Vec2(29.0, 4.0))
        ed.click(Vec2(31.0, 5.0))
        val solid = assertNotNull(ed.solids().lastOrNull(), "the anchored sweep: ${ed.statusHint}")
        assertNull(whyInvalid(solid), "valid at a 4.24 mm reach: ${whyInvalid(solid)}")
        assertManifold(meshOf(solid), "the anchored section on the coil")
        val anchor =
            assertNotNull(
                ed.doc.elements.firstOrNull {
                    it.isPoint && !it.inSpace &&
                        ((Evaluator().eval(it.ref.node) as? EvalResult.Ok)?.value as? constructit.core.PointValue)
                            ?.p?.let { p -> (p - Vec2(29.0, 4.0)).length() < 1e-6 } == true
                },
                "the anchor point",
            )

        assertNotNull(anchor.handle, "the anchor is draggable").drag(Vec2(-30.0, 4.0), Evaluator())
        val why = assertNotNull(whyInvalid(solid), "sixty-two millimetres of reach on a thirty-millimetre bend refuses")
        assertTrue(why.contains("62.0"), "the new reach is named: $why")
        assertTrue(why.contains("pass through itself"), "and the consequence: $why")

        assertNotNull(anchor.handle).drag(Vec2(29.0, 4.0), Evaluator())
        assertNull(whyInvalid(solid), "and it heals where it was")
        assertManifold(meshOf(solid), "the healed body meshes again")
    }

    /**
     * **A typed roll turns the section about the anchor.** Anchored at its (30, 5) corner and rolled 180°,
     * the square hangs *inward* at the start (the far corner reaches in to 28 mm there), stays inside the
     * `√8` shell the anchor states all the way round — the frame drifts about the tangent, but the reach is
     * roll-invariant — and is a valid body, where rolling about the section's own origin would have thrown
     * the section sixty millimetres from the path and refused.
     */
    @Test
    fun aTypedRollTurnsTheSectionAboutTheAnchor() {
        val ed = coilAndSquare()
        ed.setTool(Tools.SWEEP)
        ed.type("180")
        ed.click(Vec2(30.0, 0.0))
        ed.click(Vec2(30.0, 5.0))
        ed.click(Vec2(31.0, 5.0))
        val solid = assertNotNull(ed.solids().lastOrNull(), "the rolled, anchored sweep: ${ed.statusHint}")
        assertNull(whyInvalid(solid), "the roll does not change the reach: ${whyInvalid(solid)}")
        val mesh = meshOf(solid)
        assertManifold(mesh, "the rolled section on the coil")

        val radial = mesh.vertices.map { sqrt(it.x * it.x + it.y * it.y) }
        val reach = sqrt(8.0)
        assertTrue(radial.min() <= 28.0 + 0.1, "rolled 180° about its corner, the far corner starts 2 mm inward: ${radial.min()}")
        assertTrue(radial.min() >= 30.0 - reach - 1e-6, "still within the shell the anchor states: ${radial.min()}")
        assertTrue(radial.max() <= 30.0 + reach + 1e-6, "on both sides of it: ${radial.max()}")

        // and the file restates the roll it was built with
        val once = DocumentFormat.save(ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "save → load → save is byte-equal")
    }

    /**
     * **A rider's dot is never flattened into an anchor.** A point *in space* projects onto the plan exactly
     * where the coil is drawn — but the anchor is a point of the section's own plane (the cross-plane
     * refusal's words), and session 53 already ruled that a space point is not silently the plane point at
     * its projection. So the click must either be declined in words or fall through to the section — and the
     * sweep that results must be the **unanchored** reading, refusing by the section's own 32.757 mm reach.
     */
    @Test
    fun aSpacePointClickedForTheAnchorIsNotAdoptedSilently() {
        val ed = coilAndSquare()
        ed.setTool(Tools.POINT_ON_HELIX)
        ed.click(Vec2(30.0, 0.0))
        val rider = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.ON_CURVE }, ed.statusHint)

        ed.setTool(Tools.SWEEP)
        ed.click(Vec2(0.0, 30.0))
        ed.click(Vec2(30.0, 0.0))
        assertFalse(
            ed.statusHint.contains("ride the run: ${ed.doc.nameOf(rider)}"),
            "the rider was not taken as the section's point: ${ed.statusHint}",
        )
        ed.click(Vec2(31.0, 5.0))
        val solid = assertNotNull(ed.solids().lastOrNull(), "the sweep still completes: ${ed.statusHint}")
        val why = assertNotNull(whyInvalid(solid), "and it is the unanchored reading, which this coil refuses")
        assertTrue(why.contains("32.757"), "by the section's own reach: $why")

        val once = DocumentFormat.save(ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "and the drawing round-trips")
    }
}
