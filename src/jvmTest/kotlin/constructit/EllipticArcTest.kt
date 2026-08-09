package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.PointValue
import constructit.dsl.Construction
import constructit.dsl.ellipticArc
import constructit.dsl.loop
import constructit.dsl.region
import constructit.dsl.scalar
import constructit.dsl.solid
import constructit.dsl.valueOf
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.Conics
import constructit.geom.Ellipse
import constructit.geom.EllipticArc
import constructit.geom.GeomMath
import constructit.geom.ProfileElement
import constructit.geom.Vec2
import constructit.geom.offsetEllipticArc
import constructit.units.mm
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The **elliptic arc**'s share of the curve contract (OP-24): breaking, boundaries and areas, the 2D→3D
 * seam, and thickening over an elliptic carrier.
 *
 * The line this class is really about is OP-15's: an elliptic arc's *area* is exact (the rotation cancels
 * out of the line integral, leaving `a·b·Δt` plus two boundary terms), while its *offset* is not even an
 * ellipse — so a wall over one is sampled and flagged, exactly as a Bézier's is.
 */
class EllipticArcTest {
    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerDown(s)
        pointerUp(s)
    }

    private fun Editor.type(digits: String) {
        for (c in digits) key(c.toString())
        key("Enter")
    }

    private fun Editor.at(el: Element): Vec2 = assertNotNull((Evaluator().valueOf(el.ref) as? PointValue)?.p)

    private fun roundTrip(ed: Editor): String {
        val once = DocumentFormat.save(ed.doc)
        val back = DocumentFormat.load(once)
        assertEquals(once, DocumentFormat.save(back), "save -> load -> save must be byte-equal")
        return once
    }

    // ---- 1. area: exact ----

    /** A whole ellipse encloses exactly `π·a·b`, and half of one exactly half that. */
    @Test
    fun anEllipticBoundaryEnclosesItsExactArea() {
        val e = Ellipse(Vec2(7.0, -3.0), 60.0, 30.0, 0.7)
        val whole = GeomMath.signedArea(constructit.geom.Loop(listOf(ProfileElement.EllipseE(e, ccw = true))))
        assertClose(whole, PI * 60.0 * 30.0, 1e-9, "π·a·b, exactly — no tessellation anywhere near it")
        // the same area assembled from two half-arcs plus nothing else: the split is exact too
        val h1 = ProfileElement.EllipticArcE(EllipticArc(e, 0.0, PI, true))
        val h2 = ProfileElement.EllipticArcE(EllipticArc(e, PI, 2 * PI, true))
        val split = GeomMath.signedArea(constructit.geom.Loop(listOf(h1, h2)))
        assertClose(split, whole, 1e-9, "two arcs recompose the ellipse, area for area")
    }

    // ---- 2. break: two arcs that recompose the curve ----

    /**
     * **Breaking a whole ellipse** gives a rider and two elliptic arcs that are a partition of it: the
     * traced region's area is invariant, which is the `BreakCurveProbeTest` pattern.
     *
     * A closed curve has no ends, so one cut cannot open it; it is cut at the click **and at the antipode**
     * — which is not a second freedom but the same one, half a turn on (see `Document.breakEllipseNow`).
     */
    @Test
    fun breakingAnEllipseGivesTwoArcsThatRecomposeIt() {
        val ed = Editor()
        ed.setTool(Tools.ELLIPSE_AB)
        ed.type("30")
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(60.0, 0.0))
        ed.setTool(Tools.BREAK_LEG)
        ed.click(Vec2(0.0, 30.0))
        val arcs = ed.doc.elements.filter { it.kind == ElementKind.ELLIPTIC_ARC }
        assertEquals(2, arcs.size, "two arcs: ${ed.statusHint}")
        val rider = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.ON_CURVE })
        assertClose(ed.at(rider).x, 0.0, 1e-9, "the rider sits where the click was")
        assertClose(ed.at(rider).y, 30.0, 1e-9)

        // …and they are a partition: chained into a loop, they enclose the whole ellipse's area
        val pieces =
            arcs.map { a -> ProfileElement.EllipticArcE(assertNotNull(Evaluator().valueOf(a.ref) as? constructit.core.EllipticArcValue).arc) }
        val (chained, why) = GeomMath.chainLoop(pieces)
        assertTrue(chained != null, "the two arcs meet end to end: $why")
        assertClose(abs(GeomMath.signedArea(chained!!)), PI * 60.0 * 30.0, 1e-9, "area invariant across the break")

        // the original stays (hidden) — the two halves share it as their carrier
        val ellipse = ed.doc.elements.first { it.kind == ElementKind.ELLIPSE }
        assertTrue(!ellipse.visible, "the carrier stays, hidden: ${ed.statusHint}")
        roundTrip(ed)
    }

    /** Breaking an **elliptic arc** is the exact mirror of breaking a circular one: two pieces, same carrier. */
    @Test
    fun breakingAnEllipticArcGivesTheTwoPiecesBetweenItsEnds() {
        val ed = Editor()
        ed.setTool(Tools.ELLIPTIC_ARC)
        ed.type("30")
        ed.click(Vec2(0.0, 0.0)) // centre
        ed.click(Vec2(60.0, 0.0)) // axis end: a = 60, orientation 0
        ed.click(Vec2(60.0, 0.0)) // start, at t = 0
        ed.click(Vec2(-60.0, 0.0)) // end, at t = π
        val arc = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.ELLIPTIC_ARC })
        val v = assertNotNull(Evaluator().valueOf(arc.ref) as? constructit.core.EllipticArcValue).arc
        assertClose(Conics.sweep(v), PI, 1e-9, "half a turn of parameter, counter-clockwise")

        ed.setTool(Tools.BREAK_LEG)
        ed.click(Vec2(0.0, 30.0))
        val halves = ed.doc.elements.filter { it.kind == ElementKind.ELLIPTIC_ARC && it !== arc }
        assertEquals(2, halves.size, "two halves: ${ed.statusHint}")
        val sweeps =
            halves.map { Conics.sweep(assertNotNull(Evaluator().valueOf(it.ref) as? constructit.core.EllipticArcValue).arc) }
        assertClose(sweeps.sum(), PI, 1e-9, "the two sweeps recompose the original's")
        for (s in sweeps) assertTrue(s > 1e-6, "neither half is empty: $sweeps")
        roundTrip(ed)
    }

    /** A break too near an end is refused with the same sentence every other curve's is. */
    @Test
    fun aBreakAtAnEllipticArcsEndRefusesByName() {
        val ed = Editor()
        ed.setTool(Tools.ELLIPTIC_ARC)
        ed.type("30")
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(60.0, 0.0))
        ed.click(Vec2(60.0, 0.0))
        ed.click(Vec2(-60.0, 0.0))
        ed.setTool(Tools.BREAK_LEG)
        ed.click(Vec2(59.99, 0.05))
        assertTrue(ed.statusHint.contains("zero-length piece"), "it says why: ${ed.statusHint}")
        assertEquals(1, ed.doc.elements.count { it.kind == ElementKind.ELLIPTIC_ARC }, "nothing was built")
    }

    // ---- 3. the 2D→3D seam ----

    /**
     * A profile containing an elliptic arc — a **half ellipse**, closed by its own chord — extrudes into a
     * manifold prism whose volume is the analytic half-ellipse area times the depth, within the bound the
     * tessellation itself states.
     *
     * The bound is not a fudge factor: the boundary is replaced by chords inscribed within
     * `TESS_TOL_MM` of the curve, so the mesh is *smaller* by at most about `⅔ · tol · perimeter` per unit
     * of depth, and the assertion is that inequality in both directions.
     */
    @Test
    fun aProfileWithAnEllipticArcExtrudesToTheAnalyticVolume() {
        val c = Construction()
        val centre = c.freePoint("o", 0.0.mm, 0.0.mm)
        val axis = c.freePoint("ax", 60.0.mm, 0.0.mm)
        val e = c.ellipseCAB(centre, axis, c.parameter("b", 30.0.mm))
        val left = c.freePoint("l", -60.0.mm, 0.0.mm)
        val right = c.freePoint("r", 60.0.mm, 0.0.mm)
        val arc = c.ellipticArcBetween(e, right, left, ccw = true)
        val chord = c.segment(left, right)
        val area = c.regionArea(c.region(c.loop(arc, chord)))
        val analytic = PI * 60.0 * 30.0 / 2.0
        assertClose(Evaluator().scalar(area).base, analytic, 1e-9, "an elliptic half-disc's area is exact")

        val depth = 10.0
        val solid = c.extrude(c.sketchOn(c.planeXY(), c.region(c.loop(arc, chord))), c.parameter("h", depth.mm))
        val mesh = Evaluator().solid(solid).mesh
        assertManifold(mesh, "half-ellipse prism")
        val volume = constructit.geom.Geom3.volume(mesh)
        val perimeter = Conics.arcLength(EllipticArc(Ellipse(Vec2(0.0, 0.0), 60.0, 30.0, 0.0), 0.0, PI, true)) + 120.0
        // The chord tolerance is scale-relative (GitHub #13): an elliptic arc is sampled at its major axis'
        // effective tolerance, so the bound uses that rather than the absolute 0.02 mm.
        val bound = (2.0 / 3.0) * GeomMath.effectiveTol(60.0) * perimeter * depth
        assertTrue(volume <= analytic * depth + 1e-6, "the chords are inscribed, so the mesh cannot be bigger: $volume")
        assertTrue(
            volume >= analytic * depth - bound,
            "…and no smaller than the tessellation bound allows: $volume vs ${analytic * depth} (bound $bound)",
        )
    }

    /** The same profile, drawn: an elliptic arc and its chord are an area the *Extrude* tool consumes. */
    @Test
    fun anEllipticArcAndItsChordAreAnExtrudableArea() {
        val ed = Editor()
        ed.setTool(Tools.ELLIPTIC_ARC)
        ed.type("30")
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(60.0, 0.0))
        ed.click(Vec2(60.0, 0.0))
        ed.click(Vec2(-60.0, 0.0))
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(-60.0, 0.0))
        ed.click(Vec2(60.0, 0.0))
        ed.setTool(Tools.OUTLINE)
        ed.click(Vec2(0.0, 30.0))
        ed.click(Vec2(0.0, 0.0))
        ed.key("Enter")
        val outline = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.OUTLINE }, "a traced area: ${ed.statusHint}")
        val loopValue = assertNotNull(Evaluator().valueOf(outline.ref) as? constructit.core.LoopValue).loop
        assertClose(abs(GeomMath.signedArea(loopValue)), PI * 60.0 * 30.0 / 2.0, 1e-6, "the exact half-disc")
        ed.setTool(Tools.EXTRUDE)
        ed.type("10")
        ed.click(Vec2(0.0, 30.0))
        val solid = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.SOLID })
        assertManifold(Evaluator().solid(solid.ref as constructit.dsl.SolidRef).mesh, "extruded elliptic outline")
        roundTrip(ed)
    }

    // ---- 4. thicken over an elliptic carrier: approximated, and flagged ----

    /**
     * A wall over an **elliptic carrier** is approximated and says so — an ellipse's offset is not an
     * ellipse (OP-15's spline rule verbatim) — and the offset polyline is **exact at every sample**, each
     * one displaced along the curve's true normal there.
     */
    @Test
    fun aWallOverAnEllipticCarrierIsApproximatedAndFlagged() {
        val ed = Editor()
        ed.setTool(Tools.ELLIPTIC_ARC)
        ed.type("30")
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(60.0, 0.0))
        ed.click(Vec2(60.0, 0.0))
        ed.click(Vec2(-60.0, 0.0))
        val arcEl = ed.doc.elements.last { it.kind == ElementKind.ELLIPTIC_ARC }
        ed.setTool(Tools.THICKEN)
        ed.type("8")
        ed.click(Vec2(0.0, 30.0))
        ed.key("Enter")
        assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.AREA }, "a footprint: ${ed.statusHint}")
        assertTrue(ed.statusHint.contains("approximated"), "the status says so: ${ed.statusHint}")
        assertTrue(ed.statusHint.contains("an ellipse's is not an ellipse"), "…and why: ${ed.statusHint}")

        // the offset itself: exact at every sample, along the true normal, to 1e-9
        val arc = assertNotNull(Evaluator().valueOf(arcEl.ref) as? constructit.core.EllipticArcValue).arc
        val off = 4.0
        val poly = offsetEllipticArc(arc, off)
        assertTrue(poly.size >= 24, "sampled at the tessellation's own density: ${poly.size}")
        val sw = Conics.sweep(arc)
        for (k in poly.indices) {
            val t = arc.startT + sw * k / (poly.size - 1)
            val on = Conics.pointAt(arc.ellipse, t)
            val d = poly[k] - on
            assertClose(d.length(), off, 1e-9, "sample $k is exactly $off mm off the curve")
            assertClose(abs(d.normalized().dot(Conics.walkTangent(arc, t))), 0.0, 1e-9, "…along the true normal")
        }
    }

    /** A **whole** ellipse is refused as a wall carrier by the same rule a whole circle is. */
    @Test
    fun aWholeEllipseIsNotAWallCarrier() {
        val ed = Editor()
        ed.setTool(Tools.ELLIPSE_AB)
        ed.type("30")
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(60.0, 0.0))
        ed.setTool(Tools.THICKEN)
        ed.type("8")
        ed.click(Vec2(60.0, 0.0))
        ed.key("Enter")
        assertEquals(0, ed.doc.elements.count { it.kind == ElementKind.AREA }, "no footprint: ${ed.statusHint}")
    }

    // ---- 5. an ellipse bounds an area by itself ----

    /** A whole ellipse closes by itself, exactly as a circle does — so it extrudes with no tracing. */
    @Test
    fun aWholeEllipseIsAnAreaAndExtrudes() {
        val ed = Editor()
        ed.setTool(Tools.ELLIPSE_AB)
        ed.type("30")
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(60.0, 0.0))
        ed.setTool(Tools.EXTRUDE)
        ed.type("12")
        ed.click(Vec2(60.0, 0.0))
        val solid = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.SOLID }, "a solid: ${ed.statusHint}")
        val mesh = Evaluator().solid(solid.ref as constructit.dsl.SolidRef).mesh
        assertManifold(mesh, "elliptic cylinder")
        val analytic = PI * 60.0 * 30.0 * 12.0
        assertTrue(constructit.geom.Geom3.volume(mesh) <= analytic + 1e-6, "inscribed chords cannot exceed it")
        // The tessellation bound is scale-relative (GitHub #13): the whole ellipse's boundary is sampled at
        // its major axis' effective tolerance, so the undershoot the mesh may show scales with that.
        val perimeter = 2.0 * Conics.arcLength(EllipticArc(Ellipse(Vec2(0.0, 0.0), 60.0, 30.0, 0.0), 0.0, PI, true))
        val bound = (2.0 / 3.0) * GeomMath.effectiveTol(60.0) * perimeter * 12.0
        assertTrue(constructit.geom.Geom3.volume(mesh) >= analytic - bound, "…and are within the tessellation bound")
        roundTrip(ed)
    }

    /** Equal-**distance** spacing is the sampled map, and it is honest: the marks land on the curve exactly. */
    @Test
    fun equalDistanceSpacingIsSampledButLandsExactlyOnTheCurve() {
        val e = Ellipse(Vec2(0.0, 0.0), 60.0, 30.0, 0.0)
        val total = Conics.arcLength(EllipticArc(e, 0.0, PI / 2.0, true))
        var t = 0.0
        for (k in 1..8) {
            t = Conics.paramAtDistance(e, 0.0, total * k / 8.0)
            val p = Conics.pointAt(e, t)
            assertClose(Conics.implicit(e, p), 0.0, 1e-12, "mark $k is on the curve, exactly")
            assertClose(Conics.arcLength(EllipticArc(e, 0.0, t, true)), total * k / 8.0, 1e-6, "…at the wanted distance")
        }
        assertClose(t, PI / 2.0, 1e-6, "and the last mark is the arc's own end")
    }

    /** The DSL's own measurement of an elliptic arc: a length, dimensioned, and a plain number of mm. */
    @Test
    fun theMeasuredArcLengthIsALengthQuantity() {
        val c = Construction()
        val e = c.ellipseCAB(c.freePoint("o", 0.0.mm, 0.0.mm), c.freePoint("a", 60.0.mm, 0.0.mm), c.parameter("b", 30.0.mm))
        val arc = c.ellipticArcBetween(e, c.freePoint("s", 60.0.mm, 0.0.mm), c.freePoint("t", 0.0.mm, 30.0.mm), ccw = true)
        val q = Evaluator().scalar(c.measureEllipticArcLength(arc))
        assertEquals(constructit.units.Dimension.LENGTH, q.dim)
        val ref = Conics.arcLength(EllipticArc(Ellipse(Vec2(0.0, 0.0), 60.0, 30.0, 0.0), 0.0, PI / 2.0, true))
        assertClose(q.mm, ref, Conics.LENGTH_TOL_MM)
        // …and the arc itself is a value with the sweep the two cut points give it
        assertClose(Conics.sweep(Evaluator().ellipticArc(arc)), PI / 2.0, 1e-9)
        assertTrue(Evaluator().eval(arc.node) is EvalResult.Ok)
    }
}
