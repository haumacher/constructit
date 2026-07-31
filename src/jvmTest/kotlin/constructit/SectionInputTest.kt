package constructit

import constructit.core.Evaluator
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.dsl.valueOf
import constructit.editor.Document
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.Vec2
import constructit.units.deg
import constructit.units.mm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **The section is an input** (OP-17's section-inputs package), by clicking — the user's own acceptance
 * scenarios.
 *
 * The geometry is in `PlaneSectionTest`; this is the gesture record and the parametric one: a face space on a
 * pyramid's lateral face whose three edges feed the session-17 three-tangent circle, a datum plane whose
 * section square anchors a construction that follows when the height is retyped, and the refusals that speak.
 */
class SectionInputTest {
    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
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

    private fun Editor.type(digits: String) {
        for (c in digits) key(c.toString())
        key("Enter")
    }

    private fun Editor.solids(): List<Element> = doc.elements.filter { it.kind == ElementKind.SOLID }

    @Suppress("UNCHECKED_CAST")
    private fun Editor.volumeOf(el: Element): Double =
        constructit.geom.Geom3.volume(Evaluator().solid(el.ref as SolidRef).mesh)

    @Suppress("UNCHECKED_CAST")
    private fun Editor.meshOf(el: Element) = Evaluator().solid(el.ref as SolidRef).mesh

    private fun requireEngine() =
        org.junit.jupiter.api.Assumptions.assumeTrue(
            constructit.geom.MeshBool.available,
            "a cross-axis cut needs the general boolean engine (Manifold, OP-9): ${constructit.geom.MeshBool.status}",
        )

    private fun roundTrip(ed: Editor): Document {
        val once = DocumentFormat.save(ed.doc)
        val back = DocumentFormat.load(once)
        assertEquals(once, DocumentFormat.save(back), "save -> load -> save must be byte-equal")
        return back
    }

    /** The acceptance pyramid, by clicking: a 100 × 100 plan square and an apex 90 mm over its centre. */
    private fun pyramid(apexAt: Vec2 = Vec2(50.0, 50.0)): Editor {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 100.0))
        ed.setTool(Tools.EXTRUDE_TO_POINT)
        ed.type("90")
        ed.click(Vec2(30.0, 0.0))
        ed.click(apexAt)
        return ed
    }

    /** Where the point element [el] is, in the space it is drawn in. */
    private fun Editor.pointAt(el: Element): Vec2 =
        assertNotNull((Evaluator().valueOf(el.ref) as? constructit.core.PointValue)?.p, "a point")

    // ---- 1. the headline: a pyramid's face, its inscribed circle, and a drill ----

    /**
     * The user's own acceptance scenario, end to end and by clicking: **select one of the pyramid's planes as
     * the working plane**, take its three edges as inputs to the session-17 three-tangent circle — which is
     * the face's inscribed circle — and drill at its centre.
     *
     * Every number here is a function of the pyramid's parameters, which is the whole point: the LLL circle is
     * tangent to three *section* inputs, so it is the incircle of the triangle the face actually is.
     */
    @Test
    fun aPyramidsFaceCarriesItsInscribedCircleAndADrill() {
        requireEngine()
        val ed = pyramid()
        ed.setTool(Tools.SKETCH_ON_FACE)
        ed.click(Vec2(30.0, 0.0))
        assertTrue(ed.activeSpace.isFace, "a flat face of a loft is a face space: ${ed.statusHint}")
        assertTrue(ed.statusHint.contains("3 curves and 3 corners"), "the note says what is on offer: ${ed.statusHint}")

        // the face, in its own (u, v): the apex at the origin, the base edge at the slant height
        val section = assertNotNull(ed.doc.spaceSection(ed.activeSpace, Evaluator()))
        val slant = kotlin.math.sqrt(50.0 * 50.0 + 90.0 * 90.0)
        val corners = section.cornerPoints
        assertEquals(3, corners.size, "a triangle: $corners")
        assertTrue(corners.any { it.length() < 1e-9 }, "the apex is the frame's origin: $corners")
        assertTrue(corners.any { (it - Vec2(-50.0, slant)).length() < 1e-6 }, "and the base corners: $corners")
        assertTrue(corners.any { (it - Vec2(50.0, slant)).length() < 1e-6 }, "and the base corners: $corners")

        // the three edges as inputs to the three-tangent circle, then the click that picks the inscribed one
        ed.setTool(Tools.CIRCLE_LLL)
        ed.click(Vec2(0.0, slant)) // the base edge
        ed.click(Vec2(-25.0, slant / 2.0)) // the left edge
        ed.click(Vec2(25.0, slant / 2.0)) // the right edge
        val incentre = incentreOf(Vec2(0.0, 0.0), Vec2(-50.0, slant), Vec2(50.0, slant))
        ed.click(onIncircle(Vec2(0.0, 0.0), Vec2(-50.0, slant), Vec2(50.0, slant)))
        val circle = ed.doc.elements.last { it.kind == ElementKind.CIRCLE }
        val c = assertNotNull(Evaluator().valueOf(circle.ref) as? constructit.core.CircleValue).circle
        assertClose(c.center.x, incentre.x, 1e-6, "the incentre of the face triangle")
        assertClose(c.center.y, incentre.y, 1e-6)
        assertEquals(3, ed.doc.elements.count { it.kind == ElementKind.SEGMENT && it.space == ed.activeSpace.name }, "three section inputs were taken")

        // the drill: a small circle at the incircle's centre, cut into the part
        ed.setTool(Tools.KEY_POINTS)
        ed.click(onIncircle(Vec2(0.0, 0.0), Vec2(-50.0, slant), Vec2(50.0, slant)))
        val centre = ed.doc.elements.last { it.isPoint }
        assertClose(ed.pointAt(centre).x, incentre.x, 1e-6, "the circle's own centre, as a point")
        ed.setTool(Tools.CIRCLE_R)
        ed.type("6")
        ed.click(incentre)
        ed.setTool(Tools.CUT)
        ed.type("40")
        ed.click(Vec2(incentre.x + 6.0, incentre.y))
        val part = ed.solids().last()
        assertManifold(ed.meshOf(part), "drilled pyramid")
        assertTrue(ed.volumeOf(part) < 300000.0 - 100.0, "the bore took material out: ${ed.volumeOf(part)}")
        roundTrip(ed)
    }

    /**
     * …and **moving the apex re-derives everything**: the drill follows the new inscribed centre, because
     * every step of the chain is a node downstream of the apex (OP-21 — recompute, never rebuild).
     */
    @Test
    fun movingTheApexMovesTheInscribedCentreAndTheDrillWithIt() {
        val ed = pyramid()
        ed.setTool(Tools.SKETCH_ON_FACE)
        ed.click(Vec2(30.0, 0.0))
        val slant = kotlin.math.sqrt(50.0 * 50.0 + 90.0 * 90.0)
        ed.setTool(Tools.CIRCLE_LLL)
        ed.click(Vec2(0.0, slant))
        ed.click(Vec2(-25.0, slant / 2.0))
        ed.click(Vec2(25.0, slant / 2.0))
        ed.click(onIncircle(Vec2(0.0, 0.0), Vec2(-50.0, slant), Vec2(50.0, slant)))
        val circle = ed.doc.elements.last { it.kind == ElementKind.CIRCLE }
        val elementsBefore = ed.doc.elements.size

        // drag the apex in the plan — one literal edit on the point that places it
        assertTrue(ed.setActiveSpace(Document.PLAN_SPACE))
        val apex = ed.doc.elements.first { it.kind == ElementKind.POINT && ed.pointAt(it).let { p -> (p - Vec2(50.0, 50.0)).length() < 1e-9 } }
        ed.setTool(Tools.SELECT)
        ed.drag(Vec2(50.0, 50.0), Vec2(50.0, 30.0))
        assertClose(ed.pointAt(apex).y, 30.0, 1e-6, "the apex moved")
        assertEquals(elementsBefore, ed.doc.elements.size, "and nothing was rebuilt (OP-21) — the edit is one literal")

        // the face is now a different triangle, and the circle is its incircle — recomputed, not re-scored
        assertTrue(ed.setActiveSpace(ed.doc.spaces.last().name))
        val section = assertNotNull(ed.doc.spaceSection(ed.activeSpace, Evaluator()))
        val cs = section.cornerPoints
        val apexPt = assertNotNull(cs.minByOrNull { it.length() })
        val base = cs.filter { it !== apexPt }
        val expect = incentreOf(apexPt, base[0], base[1])
        val c = assertNotNull(Evaluator().valueOf(circle.ref) as? constructit.core.CircleValue).circle
        assertClose(c.center.x, expect.x, 1e-6, "the incentre of the new triangle")
        assertClose(c.center.y, expect.y, 1e-6)
    }

    /**
     * Where to click to pick the **inscribed** one of the four tangent circles: on its circumference (which is
     * what the tool scores against — "the circle that goes there"), beside the centre rather than at the
     * tangency point, which the apex-side excircle shares with it on a symmetric face.
     */
    private fun onIncircle(
        a: Vec2,
        b: Vec2,
        c: Vec2,
    ): Vec2 {
        val i = incentreOf(a, b, c)
        val d = b - c
        val r = kotlin.math.abs((i - c).cross(d.normalized()))
        return Vec2(i.x + r, i.y)
    }

    /** The incentre of a triangle — the weighted average of its corners by opposite side length. */
    private fun incentreOf(
        a: Vec2,
        b: Vec2,
        c: Vec2,
    ): Vec2 {
        val la = (c - b).length()
        val lb = (c - a).length()
        val lc = (b - a).length()
        val s = la + lb + lc
        return Vec2((la * a.x + lb * b.x + lc * c.x) / s, (la * a.y + lb * b.y + lc * c.y) / s)
    }

    // ---- 2. a datum at height h: the exact section square, and what it anchors ----

    /**
     * The user's second scenario: a datum plane parallel to the base at height 45 sections the pyramid into
     * the **exact** 50 × 50 square (a linear shrink at half height), its four corners are inputs, and a
     * construction anchored on two of them **follows when the height is retyped**.
     */
    @Test
    fun aDatumAtHeightSectionsThePyramidAndAnchorsAConstruction() {
        val ed = pyramid()
        ed.setTool(Tools.SKETCH_PLANE)
        ed.type("0") // parallel to the plan…
        ed.type("45") // …and 45 mm above it: the offset the loft package added
        ed.click(Vec2(30.0, 0.0))
        assertTrue(ed.activeSpace.isDatum, "the view switched to the datum: ${ed.statusHint}")
        assertTrue(ed.statusHint.contains("4 curves and 4 corners"), "the note says what is on offer: ${ed.statusHint}")

        val section = assertNotNull(ed.doc.spaceSection(ed.activeSpace, Evaluator()))
        assertTrue(!section.approximated, "plane ∩ planar facet is exact")
        for (e in listOf(Vec2(25.0, 25.0), Vec2(75.0, 25.0), Vec2(75.0, 75.0), Vec2(25.0, 75.0))) {
            assertTrue(section.cornerPoints.any { (it - e).length() < 1e-9 }, "a corner at $e: ${section.cornerPoints}")
        }

        // a segment between two corners, and a point at its midpoint — the construction that must follow
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(25.0, 25.0))
        ed.click(Vec2(75.0, 25.0))
        val seg = ed.doc.elements.last { it.kind == ElementKind.SEGMENT }
        val ends = assertNotNull(Evaluator().valueOf(seg.ref) as? constructit.core.SegmentValue).seg
        assertClose(ends.a.x, 25.0, 1e-9, "it starts at a section corner, not at the click")
        assertClose(ends.b.x, 75.0, 1e-9)
        ed.setTool(Tools.MIDPOINT)
        ed.click(Vec2(25.0, 25.0))
        ed.click(Vec2(75.0, 25.0))
        val mid = ed.doc.elements.last { it.isPoint }
        assertClose(ed.pointAt(mid).x, 50.0, 1e-9)
        val before = DocumentFormat.save(ed.doc)
        assertTrue(before.contains("sectioninput"), "the inputs are recorded by index: $before")
        roundTrip(ed)

        // retype the height: the section shrinks by 2/3 and everything anchored on it follows
        val h = assertNotNull(ed.doc.spaces.last().offset, "the datum's offset is an ordinary parameter")
        ed.doc.setParameter(h, constructit.units.Quantity.mm(30.0))
        val after = assertNotNull(ed.doc.spaceSection(ed.doc.spaces.last(), Evaluator()))
        val two3 = 200.0 / 3.0
        val lo = (100.0 - two3) / 2.0
        assertTrue(after.cornerPoints.any { (it - Vec2(lo, lo)).length() < 1e-9 }, "the section grew: ${after.cornerPoints}")
        val moved = assertNotNull(Evaluator().valueOf(seg.ref) as? constructit.core.SegmentValue).seg
        assertClose(moved.a.x, lo, 1e-9, "the anchored segment followed its corner")
        assertClose(moved.b.x, lo + two3, 1e-9)
        assertClose(ed.pointAt(mid).x, 50.0, 1e-9, "and the midpoint stayed a midpoint")
        roundTrip(ed)
    }

    // ---- 3. the cylinder, and where the conic honesty line falls (OP-15) ----

    /**
     * A cylinder (a circle extruded, r = 30, h = 80) cut **perpendicular to its axis**: the section input is
     * the **exact circle**, radius 30, derived from the profile and not fitted to the mesh — and it is a real
     * `CircleRef` a construction can be built on.
     */
    @Test
    fun aPerpendicularCutThroughACylinderIsAnExactCircleInput() {
        val c = constructit.dsl.Construction()
        val solid =
            c.extrude(
                c.sketchOn(c.planeXY(), c.region(c.loop(c.circleCR(c.freePoint("o", 0.0.mm, 0.0.mm), c.parameter("r", 30.0.mm))))),
                c.parameter("h", 80.0.mm),
            )
        val height = c.parameter("z", 40.0.mm)
        val section = c.section(solid, c.planeOffset(c.planeXY(), height))
        val ev = Evaluator()
        val s = assertNotNull((ev.valueOf(section) as? constructit.core.SectionValue)?.section)
        assertTrue(!s.approximated, "an axis-perpendicular cut of a cylinder is exact")
        val i = s.edges.indexOfFirst { it.curve is constructit.geom.ProfileElement.CircleE }
        assertTrue(i >= 0, "the cylindrical face's cut is a circle: ${s.edges.map { it.reason }}")
        val input = ev.valueOf(c.sectionCircle(section, i)) as constructit.core.CircleValue
        assertClose(input.circle.radius, 30.0, 1e-9, "the profile's own radius, as an input")
        // …and it follows the cut height, being a node: raise the plane and the circle is still the circle
        c.set(height, 70.0.mm)
        val again = Evaluator().valueOf(c.sectionCircle(section, i)) as constructit.core.CircleValue
        assertClose(again.circle.radius, 30.0, 1e-9)
    }

    /**
     * An **inclined** cut through the same cylinder is a true ellipse, which this vocabulary has no name for:
     * the section draws (flagged, sampled, exact at every sample — `PlaneSectionTest` checks the ellipse
     * itself) and the *input* accessor **refuses by name**, saying what is exact instead.
     *
     * That is the conic honesty line, asserted from the side that matters: no construction is ever silently
     * anchored on a chord.
     */
    @Test
    fun anInclinedCutThroughACylinderRefusesToBeAnInput() {
        val c = constructit.dsl.Construction()
        val solid =
            c.extrude(
                c.sketchOn(c.planeXY(), c.region(c.loop(c.circleCR(c.freePoint("o", 0.0.mm, 100.0.mm), c.parameter("r", 30.0.mm))))),
                c.parameter("h", 80.0.mm),
            )
        val hinge = c.lineThrough(c.freePoint("a", 0.0.mm, 0.0.mm), c.freePoint("b", 10.0.mm, 0.0.mm))
        val plane = c.datumPlane(c.planeXY(), hinge, c.parameter("t", 30.0.deg))
        val section = c.section(solid, plane)
        val ev = Evaluator()
        val s = assertNotNull((ev.valueOf(section) as? constructit.core.SectionValue)?.section)
        assertTrue(s.approximated, "an inclined cylinder section is a conic, and is flagged")
        val i = s.edges.indexOfFirst { it.sampled != null }
        assertTrue(i >= 0, "…and drawn: ${s.edges.map { it.reason }}")
        val why = Evaluator().eval(c.sectionSegment(section, i).node)
        assertTrue(why is constructit.core.EvalResult.Invalid, "a chord is not the curve, so it is not an input")
        val reason = (why as constructit.core.EvalResult.Invalid).reason
        assertTrue(reason.contains("ellipse"), reason)
        assertTrue(reason.contains("perpendicular"), "and it names what is exact: $reason")
    }

    /**
     * A **rounded** plate cut perpendicular to its axis: eight curves, four of them the rounded corners' own
     * **arcs**, all exact — and an arc is an input like a straight edge is, so a tangent can be constructed to
     * the corner the part actually has.
     *
     * This is the exactness claim doing real work: the cut of a cylindrical face is that face's own arc,
     * derived from the profile, so the tangency is a tangency and not a tangency-to-a-chord.
     */
    @Test
    fun aRoundedPlatesSectionKeepsItsArcsAndTheyAreInputsToo() {
        val ed = Editor()
        ed.setTool(Tools.ROUNDED_RECT)
        ed.type("10")
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 60.0))
        ed.setTool(Tools.EXTRUDE)
        ed.type("30")
        ed.click(Vec2(50.0, 0.0))
        ed.setTool(Tools.SKETCH_PLANE)
        ed.type("0")
        ed.type("15")
        ed.click(Vec2(50.0, 0.0))
        val section = assertNotNull(ed.doc.spaceSection(ed.activeSpace, Evaluator()))
        assertTrue(!section.approximated, "a cut perpendicular to the axis keeps the arcs exact")
        assertEquals(4, section.edges.count { it.curve is constructit.geom.ProfileElement.ArcE }, "the four rounded corners")
        assertEquals(4, section.edges.count { it.curve is constructit.geom.ProfileElement.Seg }, "and the four flats")
        assertEquals(8, section.cornerPoints.size, "eight corners, where the flats meet the arcs")

        // a tangent from a point to the section's own rounded corner — an arc filling a circle slot
        val corner = assertNotNull(section.edges.firstNotNullOfOrNull { it.curve as? constructit.geom.ProfileElement.ArcE }).arc
        assertClose(corner.radius, 10.0, 1e-9, "the corner radius the part was drawn with")
        val mid = constructit.geom.GeomMath.arcPointAt(corner, corner.startAngle + constructit.geom.GeomMath.sweep(corner) / 2.0)
        val centre =
            section.cornerPoints.let { cs ->
                Vec2(cs.sumOf { it.x } / cs.size, cs.sumOf { it.y } / cs.size)
            }
        ed.setTool(Tools.TANGENT)
        ed.click(centre)
        ed.click(mid)
        val arc = ed.doc.elements.last { it.kind == ElementKind.ARC }
        assertEquals(ed.activeSpace.name, arc.space, "the input was taken in this plane's own coordinates")
        val touches = ed.doc.elements.filter { it.kind == ElementKind.DERIVED_POINT }.takeLast(2)
        assertEquals(2, touches.size, "two tangents from a point: ${ed.statusHint}")
        for (t in touches) {
            assertClose(
                (ed.pointAt(t) - corner.center).length(),
                corner.radius,
                1e-9,
                "the tangency is to the corner's true radius, not to a chord",
            )
        }
        roundTrip(ed)
    }

    // ---- 4. the mesh route: it draws, and it refuses inputs by name ----

    /**
     * A **mesh-route** part (the general boolean's result, OP-9's sink rule) has no faces to name: its section
     * draws, so the user can see where the plane cuts, and every input on it is refused by name with the
     * alternative in the message.
     */
    @Test
    fun aMeshRoutePartsSectionDrawsButNamesNothing() {
        requireEngine()
        val ed = pyramid()
        // a cut on a datum plane makes the part mesh-only
        ed.setTool(Tools.SKETCH_PLANE)
        ed.type("90")
        ed.click(Vec2(30.0, 0.0))
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(20.0, 10.0))
        ed.click(Vec2(40.0, 30.0))
        ed.setTool(Tools.CUT)
        ed.type("120")
        ed.click(Vec2(30.0, 10.0))
        val part = ed.solids().last()
        assertTrue(
            Evaluator().solid(part.ref as SolidRef).feature is constructit.geom.Feature3.MeshBoolean,
            "a tilted cut through a loft takes the general engine",
        )

        // a second datum plane, whose part is that mesh-only result
        assertTrue(ed.setActiveSpace(Document.PLAN_SPACE))
        ed.setTool(Tools.SKETCH_PLANE)
        ed.type("0")
        ed.type("45")
        ed.click(Vec2(30.0, 0.0))
        assertEquals(part.id, ed.activeSpace.anchor?.id, "this plane cuts the part as it stands")
        val section = assertNotNull(ed.doc.spaceSection(ed.activeSpace, Evaluator()))
        assertTrue(section.drawn.isNotEmpty(), "it draws — chords of the mesh")
        assertTrue(section.approximated, "and says so")
        assertTrue(section.edges.isEmpty(), "but there is nothing to name")
        assertTrue(ed.statusHint.contains("cannot be anchored on"), ed.statusHint)
        assertTrue(ed.statusHint.contains("mesh-only"), ed.statusHint)

        // …and a click on it while a tool collects says why rather than missing silently
        ed.setTool(Tools.SEGMENT)
        val on = constructit.geom.GeomMath.startOf(section.drawn.first())
        ed.click(on)
        ed.setTool(Tools.CIRCLE_LLL)
        ed.click(on)
        assertTrue(ed.statusHint.contains("mesh-only"), "the refusal names the reason: ${ed.statusHint}")
    }

    // ---- 5. provenance survives edits ----

    /**
     * A face space on face *k* still means face *k* after the base is stretched: the address is structural
     * (the footprint boundary piece, OP-8), so the space's boundary follows the part and nothing re-scores.
     */
    @Test
    fun theFaceSpaceStillMeansTheSameFaceAfterTheBaseIsStretched() {
        val ed = pyramid()
        ed.setTool(Tools.SKETCH_ON_FACE)
        ed.click(Vec2(30.0, 0.0))
        val space = ed.activeSpace
        assertEquals(0, space.piece, "the first boundary piece (OP-8)")
        val slant = kotlin.math.sqrt(50.0 * 50.0 + 90.0 * 90.0)
        ed.setTool(Tools.CIRCLE_LLL)
        ed.click(Vec2(0.0, slant))
        ed.click(Vec2(-25.0, slant / 2.0))
        ed.click(Vec2(25.0, slant / 2.0))
        ed.click(onIncircle(Vec2(0.0, 0.0), Vec2(-50.0, slant), Vec2(50.0, slant)))
        val circle = ed.doc.elements.last { it.kind == ElementKind.CIRCLE }

        // stretch the base: drag the far corner of the plan rectangle
        assertTrue(ed.setActiveSpace(Document.PLAN_SPACE))
        ed.setTool(Tools.SELECT)
        ed.drag(Vec2(100.0, 100.0), Vec2(140.0, 100.0))
        assertTrue(ed.setActiveSpace(space.name))
        assertEquals(0, ed.activeSpace.piece, "the space still names the same boundary piece")
        val section = assertNotNull(ed.doc.spaceSection(ed.activeSpace, Evaluator()))
        assertEquals(3, section.cornerPoints.size, "still that face's triangle")
        val cs = section.cornerPoints
        val apexPt = assertNotNull(cs.minByOrNull { it.length() })
        val base = cs.filter { it !== apexPt }
        val expect = incentreOf(apexPt, base[0], base[1])
        val c = assertNotNull(Evaluator().valueOf(circle.ref) as? constructit.core.CircleValue).circle
        assertClose(c.center.x, expect.x, 1e-6, "and the circle is still that triangle's incircle")
        assertClose(c.center.y, expect.y, 1e-6)
    }

    // ---- 6. the old rectangle, subsumed ----

    /**
     * An extrude's side-face space draws **the same rectangle as before** — corner for corner and in the same
     * order — because that rectangle *is* the degenerate section of the plate at that plane. The mechanism
     * generalized; the behaviour did not change.
     */
    @Test
    fun anExtrudesSideFaceStillDrawsItsRectangle() {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(80.0, 50.0))
        ed.setTool(Tools.EXTRUDE)
        ed.type("20")
        ed.click(Vec2(40.0, 0.0))
        ed.setTool(Tools.SKETCH_ON_FACE)
        ed.click(Vec2(40.0, 0.0))
        val r = assertNotNull(ed.doc.faceOutline(ed.activeSpace, Evaluator()))
        assertEquals(4, r.size)
        assertClose(r[0].x, 0.0)
        assertClose(r[0].y, 0.0)
        assertClose(r[1].x, 80.0)
        assertClose(r[1].y, 0.0)
        assertClose(r[2].x, 80.0, msg = "as wide as the edge is long")
        assertClose(r[2].y, 20.0, msg = "as tall as the plate is thick")
        assertClose(r[3].x, 0.0)
        assertClose(r[3].y, 20.0)
        // …and its four edges and corners are inputs now, which they were not before
        val section = assertNotNull(ed.doc.spaceSection(ed.activeSpace, Evaluator()))
        assertEquals(4, section.edges.count { it.curve != null })
        assertEquals(4, section.cornerPoints.size)
    }

    // ---- 6b. persistence and the delete cone (OP-18) ----

    /**
     * A section input is an ordinary part of the construction, so it rides the ordinary rules: the step records
     * the **index** and nothing else, a replay takes it verbatim, and deleting the solid the plane is derived
     * from takes the space, its drawing and its inputs with it.
     */
    @Test
    fun aSectionInputIsRecordedByIndexAndGoesWithItsSpace() {
        val ed = pyramid()
        ed.setTool(Tools.SKETCH_PLANE)
        ed.type("0")
        ed.type("45")
        ed.click(Vec2(30.0, 0.0))
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(25.0, 25.0))
        ed.click(Vec2(75.0, 75.0))
        val saved = DocumentFormat.save(ed.doc)
        assertTrue(saved.contains("sectioninput"), saved)
        assertTrue(Regex("sectioninput \"plane1\" corner=\\d+").containsMatchIn(saved), "the index is the whole of the choice: $saved")
        val back = roundTrip(ed)
        assertEquals(
            ed.doc.elements.count { it.kind == ElementKind.DERIVED_POINT },
            back.elements.count { it.kind == ElementKind.DERIVED_POINT },
            "the same inputs came back",
        )

        // delete the base rectangle's leg the plane hinges on: the space and everything on it goes
        assertTrue(ed.setActiveSpace(Document.PLAN_SPACE))
        ed.setTool(Tools.SELECT)
        ed.click(Vec2(30.0, 0.0))
        ed.deleteSelection()
        assertEquals(1, ed.doc.spaces.size, "the space went with the line its plane is derived from")
        assertEquals(0, ed.doc.elements.count { it.kind == ElementKind.SEGMENT && it.space == "plane1" }, "and its drawing with it")
        roundTrip(ed)
    }

    // ---- 7. refusals speak ----

    /** A datum plane that misses the part draws nothing, and the space's note says the plane cuts nothing. */
    @Test
    fun aDatumThatMissesThePartSaysSo() {
        val ed = pyramid()
        ed.setTool(Tools.SKETCH_PLANE)
        ed.type("0")
        ed.type("200") // above the apex
        ed.click(Vec2(30.0, 0.0))
        val section = assertNotNull(ed.doc.spaceSection(ed.activeSpace, Evaluator()))
        assertTrue(section.isEmpty, "nothing is cut: ${section.drawn}")
        assertTrue(ed.statusHint.contains("cuts nothing"), ed.statusHint)
        assertTrue(ed.doc.spaceContext(ed.activeSpace, Evaluator()).size == 1, "only the hinge is drawn")
    }

    /**
     * A **ruled** face of a loft refuses to be a working plane by name, with the plane that does work in the
     * message: a cone's lateral surface is one sampled ruled patch, not a face.
     */
    @Test
    fun aRuledLoftFaceRefusesToBeAWorkingPlane() {
        val ed = Editor()
        ed.setTool(Tools.CIRCLE_R)
        ed.type("40")
        ed.click(Vec2(0.0, 0.0))
        ed.setTool(Tools.EXTRUDE_TO_POINT)
        ed.type("60")
        ed.click(Vec2(40.0, 0.0))
        ed.click(Vec2(0.0, 0.0))
        assertEquals(1, ed.solids().size, "a cone: ${ed.statusHint}")

        ed.setTool(Tools.SKETCH_ON_FACE)
        ed.click(Vec2(40.0, 0.0))
        assertTrue(ed.activeSpace.isPlan, "no space was opened: ${ed.statusHint}")
        assertTrue(ed.statusHint.contains("ruled"), ed.statusHint)
        assertTrue(ed.statusHint.contains("datum plane"), "and it names what does work: ${ed.statusHint}")
    }
}
