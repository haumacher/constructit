package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.Construction
import constructit.dsl.RegionRef
import constructit.dsl.RoundedRectArgs
import constructit.dsl.SolidRef
import constructit.dsl.roundedRect
import constructit.dsl.solid
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.Blend3
import constructit.geom.BlendKind
import constructit.geom.Geom3
import constructit.geom.GeomMath
import constructit.geom.Section3
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.units.mm
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **Where two blends meet there is a corner, and it is built rather than found** (session 79, GitHub #27
 * and #28).
 *
 * Both reports are one fault. Every blend swept its wedge along the *whole* of its own edge and handed the
 * result to the general boolean, so at a shared vertex two bands overlapped and the engine had to discover
 * their crossing curve — two cylinders of one radius that are both **tangent to the shared face** where
 * they meet it, which is the worst-conditioned intersection there is. On the reporter's triangle it came
 * out as no closed shell at all (#28: *"a tangent or self-touching contact"*), and where it did come out
 * it came out with sliver triangles a millionth of a square millimetre across (#27: *"broken … or at least
 * rendering artefacts"*). The rectangle happened to survive it, which is why both reports read as being
 * about the *angle*; it was never the angle.
 *
 * The cure is that the crossing is **not discovered**. Two equal wedges sharing a face are congruent in
 * that face's own frame — in from the edge, down from the face — and at any depth each is everything from
 * the crease out to the rolling curve, so on the side of a point nearer its own edge than the neighbour's,
 * the neighbour's wedge contains it. Splitting the removal on the surface *equidistant from the two edges*
 * therefore loses nothing and takes nothing extra, and for two straight edges that surface is the plane
 * through the corner along the in-face bisector. Both sides place their own section into that plane and
 * land on the **same ring of points**, so the two tubes stitch into one watertight tool and one boolean
 * applies the whole chain.
 *
 * What that buys is asserted here as arithmetic. A **chamfer**'s wedge is a triangle, so nothing about it
 * is tessellated and the whole figure is closed form and exact:
 *
 * ```
 * removed = Σ_edges (c²/2)·L  −  Σ_corners cot(θ/2)·c³/3
 * ```
 *
 * — the second term is the corner. `cot(θ/2)` is the two sweeps' own overlap, read in the shared face: a
 * wedge's cut-off at a corner of interior angle θ is `∫ cot(θ/2)·δ(h)²/2 dh` over the section's own inset
 * `δ`, and there are two sides to it. A **fillet** is the same sentence with `δ(h) = r − √(2rh − h²)`,
 * whose `∫₀^r δ² dh = r³(5/3 − π/2)`, and its arc reaches the engine as an inscribed chord polygon, so its
 * figure is asserted two-sided: never below the exact one, never above it by more than the chords
 * ([EdgeBlendTest]'s own model). The **discriminating** assertion — the one that fails outright if the
 * corner is not there — is the difference between the whole chain and the same edges rounded one at a
 * time, where the chord surplus cancels because both sides sweep the very same tessellated section.
 */
class BlendCornerTest {
    /** `∫₀^r δ(h)² dh` for a fillet's rolling inset — the corner's own volume, per `cot(θ/2)`. */
    private fun filletCorner(r: Double): Double = r * r * r * (5.0 / 3.0 - PI / 2.0)

    /** The same for a chamfer, whose inset falls off straight: `∫₀^c (c − h)² dh`. */
    private fun chamferCorner(c: Double): Double = c * c * c / 3.0

    /** How much two neighbouring sweeps of one size overlap at a corner of [rad] radians. */
    private fun overlapFactor(rad: Double): Double = 1.0 / tan(rad / 2.0)

    private fun volumeOf(
        ev: Evaluator,
        ref: SolidRef,
        what: String,
    ): Double {
        val r = ev.eval(ref.node)
        assertTrue(r is EvalResult.Ok, "$what: ${(r as? EvalResult.Invalid)?.reason}")
        val mesh = ev.solid(ref).mesh
        assertManifold(mesh, what)
        return Geom3.volume(mesh)
    }

    private fun refusalOf(ref: SolidRef): String? = (Evaluator().eval(ref.node) as? EvalResult.Invalid)?.reason

    /** The blend of [address] as the DSL builds it, with the choices a gesture would have scored. */
    private fun blend(
        cx: Construction,
        base: SolidRef,
        size: Double,
        kind: BlendKind,
        whole: Boolean,
        address: Int,
    ): SolidRef {
        val body = Evaluator().solid(base)
        val (targets, whyT) = Blend3.targets(body.feature, whole, address)
        assertNotNull(targets, whyT)
        val (choices, why) = Blend3.choicesFor(body, targets, size, kind)
        assertNotNull(choices, why)
        return cx.blend(base, base, cx.planeXY(), cx.const(size.mm), kind, whole, address, choices)
    }

    private fun faceIndex(
        ref: SolidRef,
        label: String,
    ): Int {
        val faces = assertNotNull(Section3.faces(Evaluator().solid(ref).feature).first, "the solid names its faces")
        val i = faces.indexOfFirst { it.name.label == label }
        assertTrue(i >= 0, "the solid has $label — it has ${faces.map { it.name.label }}")
        return i
    }

    /** The edges of the named face, as the whole-face gesture addresses them. */
    private fun rimOf(
        ref: SolidRef,
        label: String,
    ): List<Int> =
        assertNotNull(
            Blend3.targets(Evaluator().solid(ref).feature, whole = true, address = faceIndex(ref, label)).first,
            "$label has edges",
        )

    /** A prism over the polygon [xy], [h] deep — the fixture every corner angle is tried on. */
    private fun prism(
        cx: Construction,
        xy: List<Vec2>,
        h: Double,
    ): SolidRef {
        val pts = xy.mapIndexed { i, p -> cx.freePoint("p$i", p.x.mm, p.y.mm) }
        val segs = xy.indices.map { cx.segment(pts[it], pts[(it + 1) % xy.size]) }
        return cx.extrude(cx.sketchOn(cx.planeXY(), cx.region(cx.loop(*segs.toTypedArray()))), cx.const(h.mm))
    }

    /** A regular [n]-gon of circumradius [radius], wound counter-clockwise. */
    private fun regular(
        n: Int,
        radius: Double,
    ): List<Vec2> = (0 until n).map { Vec2(radius * cos(2.0 * PI * it / n), radius * sin(2.0 * PI * it / n)) }

    private fun sideLengths(xy: List<Vec2>): List<Double> = xy.indices.map { (xy[(it + 1) % xy.size] - xy[it]).length() }

    /** The interior angles of [xy] at each corner, in radians, in the polygon's own order. */
    private fun interiorAngles(xy: List<Vec2>): List<Double> =
        xy.indices.map {
            val at = xy[it]
            val a = (xy[(it + xy.size - 1) % xy.size] - at).normalized()
            val b = (xy[(it + 1) % xy.size] - at).normalized()
            acos(a.dot(b).coerceIn(-1.0, 1.0))
        }

    /** What a whole-face blend of one size takes off a prism over [xy] — closed form, corners included. */
    private fun exactLoss(
        xy: List<Vec2>,
        size: Double,
        kind: BlendKind,
    ): Double {
        val area = if (kind == BlendKind.CHAMFER) size * size / 2.0 else (1.0 - PI / 4.0) * size * size
        val corner = if (kind == BlendKind.CHAMFER) chamferCorner(size) else filletCorner(size)
        return area * sideLengths(xy).sum() - interiorAngles(xy).sumOf { overlapFactor(it) } * corner
    }

    /** The chord surplus a fillet's inscribed arc adds — [EdgeBlendTest]'s own model, per mm of edge. */
    private fun chordSurplus(
        xy: List<Vec2>,
        r: Double,
    ): Double = PI * r * GeomMath.TESS_TOL_MM * sideLengths(xy).sum() / 3.0

    // ---- the reporters' own drawing ----

    /** The reporter's own script (GitHub #27 and #28 share it), up to the point where the blend is made. */
    private val issueScript =
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
""".trimStart()

    /** **GitHub #27's script, verbatim** — one rim edge rounded, then the rim edge next to it. */
    private val issue27Script =
        issueScript +
            """
tool filletedge els=e11 clicks=3.392929260632428,51.410033777603246 scalar="r" signs=7;-1;1;0;1 -> e12
tool filletedge els=e12 clicks=15.973722224940246,21.624300121530126 scalar="r" signs=6;-1;1;0;1 -> e13
"""

    /** The same two edges in the other order — the order-independence the corner is supposed to buy. */
    private val issue27Reversed =
        issueScript +
            """
tool filletedge els=e11 clicks=15.973722224940246,21.624300121530126 scalar="r" signs=6;-1;1;0;1 -> e12
tool filletedge els=e12 clicks=3.392929260632428,51.410033777603246 scalar="r" signs=7;-1;1;0;1 -> e13
"""

    /** The reporter's triangle as a polygon, in the winding its outline gives it. */
    private val reporterTriangle = listOf(Vec2(30.0, -40.0), Vec2(9.375, 46.375), Vec2(-81.375, -16.125))

    /** The last solid of a replayed script — the drawing's tip. */
    @Suppress("UNCHECKED_CAST")
    private fun tipOf(script: String): SolidRef = DocumentFormat.load(script).elements.last { it.kind == ElementKind.SOLID }.ref as SolidRef

    private fun Editor.clickAt(w: Vec2) {
        val s = camera.worldToScreen(w)
        pointerMove(s)
        pointerDown(s)
        pointerUp(s)
    }

    @Suppress("UNCHECKED_CAST")
    private fun Editor.tip(): SolidRef = doc.elements.last { it.kind == ElementKind.SOLID }.ref as SolidRef

    /** The reporter's drawing with one blend gesture made on it, through the tool row a user would use. */
    private fun reporterGesture(
        tool: String,
        at: Vec2,
    ): Editor {
        val ed = Editor()
        ed.replaceDocument(DocumentFormat.load(issueScript))
        ed.activeScalar = ed.doc.scalars.first { it.name == "r" }
        ed.setTool(tool)
        ed.clickAt(at)
        return ed
    }

    /**
     * **GitHub #28, through the tool row** — *"Fillet edges of face does not work"*: the reporter's
     * triangle, whose corners are 46.7°, 64.5° and 68.9°, with all three edges of its top face rounded in
     * one gesture. It used to refuse with *"the general boolean's result is not a closed shell"*.
     */
    @Test
    fun issue28TheThreeEdgesOfATopFaceRoundInOneGesture() {
        val before = volumeOf(Evaluator(), tipOf(issueScript), "the reporter's triangular prism")
        assertClose(before, 20.0 * 4563.796875, 1e-6, "the prism is its own footprint, 20 mm deep")
        val r = 5.0

        val ed = reporterGesture(Tools.BLEND_FACE, Vec2(19.6875, 3.1875))
        val said = assertNotNull(ed.statusHint, "the gesture says what it did")
        assertTrue(said.contains("the top face"), "it names the face: $said")
        assertTrue(said.contains("(3 edges)"), "…and all three pieces of its boundary: $said")
        val after = volumeOf(Evaluator(), ed.tip(), "the reporter's triangle with its whole top rim rounded")

        val exact = exactLoss(reporterTriangle, r, BlendKind.FILLET)
        val chords = chordSurplus(reporterTriangle, r)
        assertTrue(before - after >= exact - 1e-6, "the rim takes at least the exact $exact mm^3 — it took ${before - after}")
        assertTrue(before - after <= exact + chords, "…and at most that plus the chords — it took ${before - after}")

        // **the bound is the discriminator.** With no corner at all the three bands would be swept full
        // length and the loss would be at least the sum of the three quarter-rounds — which is *above* the
        // upper bound just asserted, so a build that lost the corner could not pass this test.
        val noCorner = (1.0 - PI / 4.0) * r * r * sideLengths(reporterTriangle).sum()
        assertTrue(
            exact + chords < noCorner,
            "with no corner the loss would be at least $noCorner mm^3, above the ${exact + chords} this allows",
        )
        val corners = interiorAngles(reporterTriangle).sumOf { overlapFactor(it) } * filletCorner(r)
        assertClose(noCorner - exact, corners, 1e-9, "…and what separates the two figures is exactly the three corners")
    }

    /** The same three edges chamfered — a bevel is a triangle, so this figure is **exact**. */
    @Test
    fun issue28TheSameThreeEdgesChamferToTheExactFigure() {
        val before = volumeOf(Evaluator(), tipOf(issueScript), "the reporter's triangular prism")
        val c = 5.0
        val ed = reporterGesture(Tools.CHAMFER_FACE, Vec2(19.6875, 3.1875))
        val after = volumeOf(Evaluator(), ed.tip(), "the reporter's triangle with its whole top rim bevelled: ${ed.statusHint}")
        val exact = exactLoss(reporterTriangle, c, BlendKind.CHAMFER)
        assertClose(before - after, exact, abs(exact) * 1e-5, "the three bevels and the three planar mitres between them, exactly")
    }

    /**
     * **GitHub #27, verbatim** — *"Filleting two adjacent 3D edges results in broken result"*: the same
     * triangle, one rim edge rounded and then the rim edge next to it, in two gestures.
     *
     * The body it makes is now the body the one-gesture chain makes: the second blend reads the band under
     * it off the [constructit.geom.Feature3.Blend] it is dressing, builds the corner where the two meet
     * exactly as a chain would, and cuts only what is still there. Asserted as the two volumes agreeing to
     * a part in ten million, and as **order-independence** — either edge first gives the same body.
     */
    @Test
    fun issue27ABlendBesideABlendIsTheOneGestureChain() {
        val before = volumeOf(Evaluator(), tipOf(issueScript), "the reporter's triangular prism")
        val r = 5.0
        val sevenThenSix = volumeOf(Evaluator(), tipOf(issue27Script), "the reporter's own two gestures")
        val sixThenSeven = volumeOf(Evaluator(), tipOf(issue27Reversed), "the same two gestures, the other way round")

        val body = Evaluator().solid(tipOf(issueScript))
        val targets = listOf(7, 6)
        val choices = assertNotNull(Blend3.choicesFor(body, targets, r, BlendKind.FILLET).first, "the pair scores its choices")
        val (chain, whyChain) = Blend3.blended(body, body, targets, r, BlendKind.FILLET, choices)
        assertNotNull(chain, whyChain)
        assertManifold(chain.mesh, "the two-edge chain in one gesture")
        val together = Geom3.volume(chain.mesh)

        assertClose(sevenThenSix, together, abs(together) * 1e-6, "the reporter's two gestures are the one-gesture chain")
        assertClose(sixThenSeven, together, abs(together) * 1e-6, "and so is the other order")

        // …and the figure itself is the machinist's, corner included
        val lengths = (reporterTriangle[1] - reporterTriangle[0]).length() + (reporterTriangle[2] - reporterTriangle[1]).length()
        val exact = (1.0 - PI / 4.0) * r * r * lengths - overlapFactor(interiorAngles(reporterTriangle)[1]) * filletCorner(r)
        assertTrue(before - together >= exact - 1e-6, "the two edges take at least the exact $exact mm^3")
        assertTrue(before - together <= exact + PI * r * GeomMath.TESS_TOL_MM * lengths / 3.0, "…and at most that plus the chords")
    }

    /**
     * **The corner is built the same way every time, and the step that builds it round-trips.** The mesh is
     * a pure function of the parameters (OP-4), so two evaluations of one drawing must agree triangle for
     * triangle — which is a real thing to say about a stitched tool, whose rings come out of maps and whose
     * groups come out of a union-find. And the gesture writes an ordinary `tool` step: same address, same
     * `signs=`, byte-equal on a second save (OP-18).
     */
    @Test
    fun theCornerIsBuiltTheSameWayTwiceAndItsStepRoundTrips() {
        val ed = reporterGesture(Tools.BLEND_FACE, Vec2(19.6875, 3.1875))
        val once = DocumentFormat.save(ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "the whole drawing round-trips byte-equal")
        assertTrue(once.contains("tool filletfaceedges"), "…and the corner rides the generic tool step: $once")

        val a = Evaluator().solid(tipOf(once)).mesh
        val b = Evaluator().solid(tipOf(once)).mesh
        assertEquals(a.vertices, b.vertices, "the same vertices, in the same order, every time")
        assertEquals(a.triangles, b.triangles, "and the same triangles")
    }

    /**
     * **No fold at the corner.** The surface along the corner's own bisector is the rolling ball's, point
     * for point: at in-face distance `s` from both edges it stands `r − √(r² − (r − s)²)` below the top
     * face. Sampled by asking the **body** which side of its own surface a point is on ([Geom3.encloses])
     * and bisecting for the crossing, so this is a statement about the solid rather than about its
     * triangles. A notch or a re-entrant fold would put the crossing at the wrong depth, and the walk in
     * `s` would not come out monotone.
     */
    @Test
    fun theCornerSurfaceIsTheRollingBallsOwn() {
        val r = 5.0
        val mesh = Evaluator().solid(tipOf(issue27Script)).mesh
        assertManifold(mesh, "the reporter's two adjacent fillets")

        val v = reporterTriangle[1]
        val theta = interiorAngles(reporterTriangle)[1]
        val bis = ((reporterTriangle[0] - v).normalized() + (reporterTriangle[2] - v).normalized()).normalized()
        var last = Double.MAX_VALUE
        for (frac in listOf(0.2, 0.4, 0.6, 0.8)) {
            val s = r * frac
            val at = v + bis * (s / sin(theta / 2.0))
            val exact = r - sqrt(r * r - (r - s) * (r - s))
            var lo = 0.0
            var hi = r * 1.5
            repeat(40) {
                val mid = (lo + hi) / 2.0
                if (Geom3.encloses(mesh, Vec3(at.x, at.y, 20.0 - mid))) hi = mid else lo = mid
            }
            val depth = (lo + hi) / 2.0
            assertTrue(depth < last, "the corner surface rises monotonically toward the top face: $depth after $last")
            last = depth
            assertClose(depth, exact, 0.2, "at $s mm in from both edges the ball leaves the surface $exact mm down")
        }
    }

    // ---- every corner angle ----

    /**
     * **30°, 60°, 90°, 120° and 170° corners**, filleted and chamfered, all watertight and all taking the
     * closed-form volume — the chamfers **exactly**, since a bevel and a planar mitre have nothing
     * tessellated about them.
     *
     * The right angle is the case that used to work by luck of the engine and now works by construction;
     * 30° is the case a rolling ball only just fits in (its corner eats `cot 15° = 3.7` radii off each
     * edge); 170° is the near-straight one, where the corner is almost nothing and must still close.
     */
    @Test
    fun everyCornerAngleBuildsAndTakesItsExactFigure() {
        for ((what, xy) in cornerFixtures()) {
            for (kind in listOf(BlendKind.FILLET, BlendKind.CHAMFER)) {
                val cx = Construction()
                val base = prism(cx, xy, 20.0)
                val before = volumeOf(Evaluator(), base, "$what before")
                val size = 3.0
                val dressed = blend(cx, base, size, kind, whole = true, address = faceIndex(base, "the top face"))
                val after = volumeOf(Evaluator(), dressed, "$what with its whole top rim ${kind.word}ed")
                val exact = exactLoss(xy, size, kind)
                if (kind == BlendKind.CHAMFER) {
                    assertClose(before - after, exact, abs(exact) * 1e-5, "$what: the bevels and their planar mitres, exactly")
                } else {
                    val chords = chordSurplus(xy, size)
                    assertTrue(before - after >= exact - 1e-6, "$what takes at least $exact mm^3 — it took ${before - after}")
                    assertTrue(before - after <= exact + chords, "$what takes at most ${exact + chords} — it took ${before - after}")
                }
            }
        }
    }

    /**
     * The polygons those angles are read off: an isoceles triangle whose apex is 30°, the three regular
     * ones for 60°, 90° and 120°, and a 36-gon whose every corner is 170°.
     */
    private fun cornerFixtures(): List<Pair<String, List<Vec2>>> =
        listOf(
            "a 30 degree corner" to listOf(Vec2(0.0, 0.0), Vec2(200.0, 0.0), Vec2(200.0 * cos(PI / 6.0), 200.0 * sin(PI / 6.0))),
            "a 60 degree corner (a triangle)" to regular(3, 40.0),
            "a 90 degree corner (a square)" to regular(4, 40.0),
            "a 120 degree corner (a hexagon)" to regular(6, 40.0),
            "a 170 degree corner (a 36-gon)" to regular(36, 60.0),
        )

    // ---- the refusal ----

    /**
     * **A corner with no room for the size says so, and heals when the size comes down** (OP-3, and
     * session 65's rule that a refusal names the alternative).
     *
     * A corner eats `cot(θ/2)` times the tangency's own setback off each of its two edges, so on a
     * 40 × 30 plate a 16 mm round leaves the 30 mm edges nothing at all to stand in — and it is refused by
     * name, with the largest radius that does fit. At 12 mm the same drawing builds.
     */
    @Test
    fun aCornerWithNoRoomSaysSoAndHeals() {
        val plate = listOf(Vec2(0.0, 0.0), Vec2(40.0, 0.0), Vec2(40.0, 30.0), Vec2(0.0, 30.0))
        val cx = Construction()
        val base = prism(cx, plate, 20.0)
        volumeOf(Evaluator(), base, "the plate")
        val top = faceIndex(base, "the top face")
        val said =
            assertNotNull(
                refusalOf(blend(cx, base, 16.0, BlendKind.FILLET, whole = true, address = top)),
                "a 16 mm round on a 30 mm edge's two corners cannot be had",
            )
        assertTrue(said.contains("the corner where"), "it names the corner: $said")
        assertTrue(said.contains("too sharp for a fillet of radius"), "…and the size that does not fit: $said")
        assertTrue(said.contains("The largest that fits there is about"), "…and the one that would: $said")
        val fits =
            assertNotNull(
                Regex("about ([0-9.]+) mm").findAll(said).last().groupValues[1].toDoubleOrNull(),
                "the reason states a number: $said",
            )
        assertTrue(fits > 0.0 && fits < 16.0, "and it is smaller than the one asked for: $fits")

        val after = volumeOf(Evaluator(), blend(cx, base, 12.0, BlendKind.FILLET, whole = true, address = top), "the same plate at 12 mm")
        assertTrue(after < 40.0 * 30.0 * 20.0, "…which builds, so the refusal healed")
    }

    // ---- what the corner does not touch ----

    /**
     * **An inside corner is rounded too, and the corners beside it are still built** — the cut this test
     * pinned in session 79 is retired by session 80 (GitHub #31).
     *
     * What it used to say: *"at the reflex one the two bands do not overlap — they leave a wedge between
     * them, and the ball that would round it pivots about the upright, which is a different construction;
     * that corner keeps exactly what it kept"*. It is now built: the ball's pivot is the band's own section
     * carried round the upright through the corner's exterior angle ([constructit.geom.Blend3]'s `Turn`), and
     * the chamfer's figure says so — five planar mitres, and a turn that takes Pappus' own `φ·c³/6`.
     *
     * The turn reaches the engine as flat facets round its axis, exactly as every arc reaches it as chords,
     * so it takes `sin(Δ)/Δ` of the exact figure at a step of Δ — under a percent at this drawing's sag, and
     * bounded from both sides here rather than absorbed into a tolerance.
     */
    @Test
    fun anInsideCornerIsRoundedByTheBallsOwnPivot() {
        // an L: five 90 degree corners and one at 270
        val ell =
            listOf(
                Vec2(0.0, 0.0),
                Vec2(60.0, 0.0),
                Vec2(60.0, 25.0),
                Vec2(25.0, 25.0),
                Vec2(25.0, 60.0),
                Vec2(0.0, 60.0),
            )
        val cx = Construction()
        val base = prism(cx, ell, 20.0)
        val before = volumeOf(Evaluator(), base, "the L-shaped prism")
        val c = 3.0
        val targets = rimOf(base, "the top face")
        assertTrue(targets.size == 6, "the cap's rim is six pieces — it is ${targets.size}")
        val after =
            volumeOf(
                Evaluator(),
                blend(cx, base, c, BlendKind.CHAMFER, whole = true, address = faceIndex(base, "the top face")),
                "the L-shaped cap, bevelled all round",
            )
        // five mitres at 90 degrees (cot 45 = 1), and the inside corner's own quarter turn
        val turn = (PI / 2.0) * c * c * c / 6.0
        // …and the turn *adds* to the removal: the two bands never overlapped there, so the patch is all new
        val exact = c * c / 2.0 * sideLengths(ell).sum() - 5.0 * chamferCorner(c) + turn
        assertTrue(before - after <= exact + 1e-6, "the turn cannot take more than the exact $exact mm^3 — it took ${before - after}")
        assertTrue(before - after >= exact - 0.02 * turn, "…nor less than that minus its own facets — it took ${before - after}")
    }

    /**
     * **A smooth hand-over is not a corner and is left exactly as it was.** The rim of an extruded rounded
     * rectangle is eight pieces that run on into each other tangentially; there the two sections already
     * abut on one plane, so nothing is split, nothing is stitched, and the mesh is the one this build made
     * before the corner existed. Asserted as the absence of any corner deduction: the whole rim takes
     * exactly what its eight pieces take one at a time.
     */
    @Test
    fun aTangentHandOverIsUntouched() {
        fun rim(cx: Construction): RegionRef {
            val rr =
                roundedRect.build(
                    cx,
                    RoundedRectArgs(cx.freePoint("ctr", 30.mm, 20.mm), cx.const(60.mm), cx.const(40.mm), cx.const(10.mm)),
                )
            return cx.region(cx.loop(*rr.boundary.toTypedArray()))
        }

        val cx = Construction()
        val base = cx.extrude(cx.sketchOn(cx.planeXY(), rim(cx)), cx.const(20.mm))
        val before = volumeOf(Evaluator(), base, "the rounded plate")
        val r = 3.0
        val targets = rimOf(base, "the top face")
        assertTrue(targets.size == 8, "the rim is eight pieces — it is ${targets.size}")
        val together = before - volumeOf(Evaluator(), blend(cx, base, r, BlendKind.FILLET, whole = true, address = faceIndex(base, "the top face")), "the whole rim")

        var oneAtATime = 0.0
        for (i in targets) {
            val one = Construction()
            val b1 = one.extrude(one.sketchOn(one.planeXY(), rim(one)), one.const(20.mm))
            oneAtATime += before - volumeOf(Evaluator(), blend(one, b1, r, BlendKind.FILLET, whole = false, address = i), "piece #${i + 1}")
        }
        // No corner is deducted, and none should be: a smooth hand-over has `cot(θ/2) = 0` at θ = 180°.
        // The band is the eight separate booleans' own accumulation — each one re-quantises the body into
        // the general engine's float32 (OP-9), which is what the tolerance here is measuring.
        assertClose(together, oneAtATime, abs(together) * 5e-3, "eight tangent pieces take the same either way — no corner is built")
    }
}
