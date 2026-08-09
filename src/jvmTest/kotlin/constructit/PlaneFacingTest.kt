package constructit

import constructit.core.Evaluator
import constructit.editor.Camera3
import constructit.editor.Editor
import constructit.editor.SvgDrawTarget
import constructit.editor.Tools
import constructit.editor.Viewport3
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.units.deg
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **Which way a sketch plane fronts** (session 64, on the user's session-61 report: their revolve *"swept the
 * wrong way"* because `plane1`'s front faced away from where they expected).
 *
 * A positive *Extrude* or *Revolve* builds toward the plane's front, the front is `u × v`, and which way that
 * turns out is decided by which end of the hinge line happened to be drawn first — invisible state deciding a
 * visible outcome. Two surfacings, asserted here:
 *
 * - the **words**, in the space's own note and in its label, as a **bearing in the base space** — which is
 *   the piece of information that was actually missing, since "right of the hinge" would only restate the
 *   sign of the angle the label already shows (see `Document.spaceFacing`);
 * - the **tick**, a short arrow standing out of the working plane's origin along its normal, drawn in the 3D
 *   view and nowhere else.
 *
 * Both are pinned to the geometry rather than to each other: every wording assertion below stands next to an
 * assertion about the plane node's own normal, so the sentence cannot drift from what a feature will do.
 */
class PlaneFacingTest {
    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerDown(s)
        pointerUp(s)
    }

    private fun Editor.type(digits: String) {
        for (c in digits) key(c.toString())
        key("Enter")
    }

    /** A single segment from [a] to [b], then an upright datum hinged on it. */
    private fun hinged(
        a: Vec2,
        b: Vec2,
        deg: String? = null,
    ): Editor {
        val ed = Editor()
        ed.setTool(Tools.SEGMENT)
        ed.click(a)
        ed.click(b)
        ed.setTool(Tools.SKETCH_PLANE)
        if (deg != null) ed.type(deg)
        ed.click((a + b) * 0.5)
        return ed
    }

    private fun normalOf(ed: Editor) = assertNotNull(ed.doc.activePlane3(Evaluator()), "the datum evaluates").normal

    // ---- 1. the words ----

    /**
     * The same line, drawn the two ways round, hinged the same 90°: the plane's front is **opposite** in the
     * two drawings, and the note and the label say so with different numbers.
     *
     * This is the whole test of the wording choice. "Right of the hinge as it is drawn" would have been true
     * of *both* of these, because both are +90° — and it is precisely the drawn direction that the user
     * cannot see. A bearing in the plan differs, and is checkable against a canvas that draws +x right.
     */
    @Test
    fun theTwoDrawnDirectionsOfOneHingeFrontOppositeWaysAndTheWordsDiffer() {
        val east = hinged(Vec2(0.0, 0.0), Vec2(100.0, 0.0), "90")
        val west = hinged(Vec2(100.0, 0.0), Vec2(0.0, 0.0), "90")

        // the geometry first: an upright plane hinged on the x axis fronts along −y or +y, by the drawing
        assertEquals(-1.0, normalOf(east).y, 1e-9, "drawn +x, the front turns toward −y")
        assertEquals(1.0, normalOf(west).y, 1e-9, "drawn −x, the front turns toward +y — the same 90°")

        assertEquals(270.0, assertNotNull(east.doc.spaceFacing(east.activeSpace)).bearingDeg!!, 1e-9, "−y is 270°")
        assertEquals(90.0, assertNotNull(west.doc.spaceFacing(west.activeSpace)).bearingDeg!!, 1e-9, "+y is 90°")

        assertTrue(east.statusHint.contains("Its front faces 270° in plan"), east.statusHint)
        assertTrue(west.statusHint.contains("Its front faces 90° in plan"), west.statusHint)
        assertTrue(
            east.statusHint.contains("that is where a positive Extrude or Revolve builds"),
            "the note says what the bearing is *for*: ${east.statusHint}",
        )
        assertTrue(east.doc.spaceLabel(east.activeSpace).endsWith(", front toward 270°)"), east.doc.spaceLabel(east.activeSpace))
        assertTrue(west.doc.spaceLabel(west.activeSpace).endsWith(", front toward 90°)"), west.doc.spaceLabel(west.activeSpace))
    }

    /** A datum that lies **flat** on its base has no bearing at all, and says the honest thing instead. */
    @Test
    fun aFlatDatumFrontsWithItsBaseOrAgainstIt() {
        val flat = hinged(Vec2(0.0, 0.0), Vec2(100.0, 0.0), "0")
        val over = hinged(Vec2(0.0, 0.0), Vec2(100.0, 0.0), "180")

        assertEquals(1.0, normalOf(flat).z, 1e-9, "0° is the plan again, re-anchored on the line")
        assertEquals(-1.0, normalOf(over).z, 1e-9, "180° is the plan turned over")

        val a = assertNotNull(flat.doc.spaceFacing(flat.activeSpace))
        val b = assertNotNull(over.doc.spaceFacing(over.activeSpace))
        assertEquals(null, a.bearingDeg, "flat on its base: there is no side to lean toward")
        assertEquals(null, b.bearingDeg, "flat on its base: there is no side to lean toward")
        assertTrue(a.outward, "0° fronts the way the plan does")
        assertFalse(b.outward, "180° fronts the other way")
        assertTrue(flat.doc.spaceLabel(flat.activeSpace).endsWith(", front with plan)"), flat.doc.spaceLabel(flat.activeSpace))
        assertTrue(over.doc.spaceLabel(over.activeSpace).endsWith(", front against plan)"), over.doc.spaceLabel(over.activeSpace))
        assertTrue(flat.statusHint.contains("It lies flat on plan, fronting the same way"), flat.statusHint)
        assertTrue(over.statusHint.contains("It lies flat on plan, fronting the opposite way"), over.statusHint)
    }

    /**
     * **The plan says nothing new.** Its front is up, it is the space every drawing starts in, and a sentence
     * about facing there would be noise — the deliberate cut, asserted so it stays cut.
     */
    @Test
    fun thePlanGainsNoFacingWords() {
        val ed = hinged(Vec2(0.0, 0.0), Vec2(100.0, 0.0), "90")
        ed.setActiveSpace("plan")
        assertEquals("plan", ed.doc.spaceLabel(ed.doc.activeSpace), "the plan's label is the one word it was")
        assertFalse(ed.statusHint.contains("front"), "the plan's note says nothing about facing: ${ed.statusHint}")
        assertEquals(null, ed.doc.spaceFacing(ed.doc.activeSpace), "the question is not asked of the plan")
    }

    /** A **bound** angle moves the words with it: the facing is read off the plane node, not off a literal. */
    @Test
    fun retypingTheAngleTurnsTheWordsRound() {
        val ed = hinged(Vec2(0.0, 0.0), Vec2(100.0, 0.0), "90")
        val angle = assertNotNull(ed.activeSpace.angle, "a datum's angle is a parameter")
        assertEquals(270.0, assertNotNull(ed.doc.spaceFacing(ed.activeSpace)).bearingDeg!!, 1e-9)
        ed.doc.setParameter(angle, (-90.0).deg)
        assertEquals(1.0, normalOf(ed).y, 1e-9, "a negative angle turns the plane over")
        assertEquals(90.0, assertNotNull(ed.doc.spaceFacing(ed.activeSpace)).bearingDeg!!, 1e-9, "and the words follow it")
        assertTrue(ed.doc.spaceLabel(ed.activeSpace).endsWith(", front toward 90°)"), ed.doc.spaceLabel(ed.activeSpace))
    }

    // ---- 2. the tick ----

    private fun view(ed: Editor): Viewport3 {
        val vp =
            Viewport3(
                camera = Camera3(target = Vec3(50.0, 0.0, 25.0), distance = 300.0, yaw = -1.05, pitch = 0.55, fovY = PI / 4.0),
                widthPx = 640.0,
                heightPx = 420.0,
            )
        vp.editor = ed
        vp.shown = true
        return vp
    }

    /** Every `polyline` in an SVG, as its screen points. */
    private fun polylines(svg: String): List<List<Vec2>> =
        Regex("""points="([^"]*)"""").findAll(svg).map { m ->
            m.groupValues[1].trim().split(" ").filter { it.isNotEmpty() }.map {
                val (x, y) = it.split(",")
                Vec2(x.toDouble(), y.toDouble())
            }
        }.toList()

    /**
     * The tick, as what it structurally is: a two-point polyline standing at the plane's projected origin
     * whose far end carries an **arrowhead** (the three-point polyline whose apex is that end).
     *
     * Identified that way rather than by direction, because the direction is the thing under test — and the
     * hinge line starts at the very same point, so "the segment from the origin" would find that instead.
     */
    private fun tickOf(
        svg: String,
        foot: Vec2,
    ): Vec2? {
        val all = polylines(svg)
        val heads = all.filter { it.size == 3 }
        val tick =
            all.firstOrNull { p ->
                p.size == 2 && (p[0] - foot).length() < 0.01 && heads.any { (it[1] - p[1]).length() < 0.01 }
            }
        return tick?.let { it[1] - it[0] }
    }

    /**
     * The tick stands **along the plane's own normal**, so the two drawings of one hinge push it to opposite
     * sides of the screen — and it is drawn in the 3D view only, because a 2D canvas looks straight down the
     * normal and a tick there would be a dot that said nothing (`SceneRenderer.drawHeightPoint`'s rule).
     */
    @Test
    fun theFrontTickStandsOutOfThePlaneAndOnlyWhereItCanBeSeen() {
        for ((ed, expected) in listOf(hinged(Vec2(0.0, 0.0), Vec2(100.0, 0.0), "90") to -1.0, hinged(Vec2(100.0, 0.0), Vec2(0.0, 0.0), "90") to 1.0)) {
            val vp = view(ed)
            val proj = assertNotNull(vp.projection(), "the 3D view has a working plane")
            val svg = SvgDrawTarget()
            vp.renderSketch(svg)
            val foot = assertNotNull(proj.toScreen(Vec2(0.0, 0.0)), "the plane's origin is on screen")
            val drawn = assertNotNull(tickOf(svg.svg(), foot), "the 3D view draws a tick at the plane's origin")

            // where the normal itself lands: one plane length off the plane, projected the same way
            val out = assertNotNull(proj.toScreenLifted(Vec2(0.0, 0.0), 10.0), "a lifted point projects") - foot
            // 1e-3 of a radian because the comparison is made through the **SVG**, whose coordinates carry
            // three decimals: over a 30-pixel tick that rounding is worth about 1e-4 rad on its own
            assertEquals(0.0, drawn.normalized().cross(out.normalized()), 1e-3, "the tick runs along the plane's normal")
            assertTrue(drawn.dot(out) > 0.0, "and toward the front, not away from it")
            assertEquals(expected, normalOf(ed).y, 1e-9, "…which is the side the drawing put it on")

            // …and the same drawing on the 2D canvas has none: it looks straight down the normal, where a
            // tick could only ever be a dot
            val plan = SvgDrawTarget()
            ed.draw(plan, 640.0, 420.0)
            assertEquals(
                null,
                tickOf(plan.svg(), ed.camera.worldToScreen(Vec2(0.0, 0.0))),
                "the canvas draws no tick",
            )
        }
    }
}
