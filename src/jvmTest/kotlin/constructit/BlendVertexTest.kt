package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.Construction
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.editor.Camera3
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.editor.Viewport3
import constructit.geom.Blend3
import constructit.geom.BlendKind
import constructit.geom.BlendSection
import constructit.geom.FaceName
import constructit.geom.Geom3
import constructit.geom.GeomMath
import constructit.geom.Mesh3
import constructit.geom.Plane3
import constructit.geom.ProfileElement
import constructit.geom.Section3
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.l10n.contains
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
 * **The two places the rolling ball stands still** (session 80, GitHub #31 and #32).
 *
 * Session 79 built the corner where two bands **cross**: they overlap, and the removal splits on the plane
 * equidistant from the two edges. It named two cuts, and both came back as reports. Where the shared face
 * turns an **inside** corner the two bands do not overlap at all — each stops on the plane square to its own
 * edge and the face's sharp corner stands between the two ends (#31: *"keeps a spike at the corner"*). Where
 * **three** bands meet at a convex vertex, only two of them could claim each other and the third butted
 * (#32: *"this produces sharp edges, not a round surface … shouldn't the filleted extrusion edge also fillet
 * the fillets of the top-face edges?"*).
 *
 * Both are the one case the crossing cannot be: the ball is not rolling along an edge, it is **standing
 * still**, and there the corner is its own surface.
 *
 * - **Inside corner.** Having reached the end of its edge the ball pivots about the upright, its centre
 *   turning on a circle of radius `r` while it stays tangent to the shared face. The corner is therefore the
 *   band's own section carried round that axis through the corner's exterior angle — a horn torus for a
 *   round, a cone for a bevel — and at the two ends of the turn it *is* the two bands' own end sections, so
 *   there is nothing to match. Pappus gives its volume exactly: `φ · ∫δ(h)²/2 dh`, which is
 *   `φ·r³(5/6 − π/4)` and `φ·c³/6`.
 * - **Convex vertex.** The ball sits touching all three faces at once. Each band's section circle at the
 *   station through the ball's centre **is** a great circle of that ball, so the three bands end there and
 *   the spherical triangle between the three end arcs closes the tool. It takes *more* than the three bands
 *   do: three cylinders keep a point sticking out toward the vertex — on a box corner at `(1−1/√2)r` along
 *   the diagonal, `1.22 r` from the ball's centre — and that point is exactly the reporter's sharp corner.
 *   A **chamfer**'s three planes already meet in a point, so its patch is the three bevel triangles running
 *   to that apex and it takes nothing extra.
 *
 * The figures are closed form. On a box corner with three equal roundings the cell `[0,r]³` keeps the ball's
 * own octant, so the three bands' own sum loses `3(1−π/4)r³ − (1−π/6)r³ = (2 − 7π/12) r³` there; with three
 * equal bevels the cell keeps a quarter of itself, so the sum loses `(3/2 − 3/4)c³ = (3/4)c³`. Both are
 * asserted — the bevel exactly, the round bracketed by the chords its arc reaches the engine as.
 */
class BlendVertexTest {
    // ---- the arithmetic ----

    /** `∫₀^r δ(h)² dh` for a round's rolling inset, and `∫₀^c` for a bevel's straight one. */
    private fun cornerMoment(
        size: Double,
        kind: BlendKind,
    ): Double = if (kind == BlendKind.CHAMFER) size * size * size / 3.0 else size * size * size * (5.0 / 3.0 - PI / 2.0)

    /** What a **turn** of [rad] takes: Pappus' figure over the section's own first moment. */
    private fun turnTakes(
        size: Double,
        kind: BlendKind,
        rad: Double,
    ): Double = rad * cornerMoment(size, kind) / 2.0

    /** What a **crossing** of interior angle [rad] takes off the two bands' sum. */
    private fun crossingTakes(
        size: Double,
        kind: BlendKind,
        rad: Double,
    ): Double = cornerMoment(size, kind) / tan(rad / 2.0)

    private fun wedgeArea(
        size: Double,
        kind: BlendKind,
    ): Double = if (kind == BlendKind.CHAMFER) size * size / 2.0 else (1.0 - PI / 4.0) * size * size

    /** The chord surplus a round's inscribed arc adds over [length] mm of edge — [EdgeBlendTest]'s model. */
    private fun chordSurplus(
        r: Double,
        length: Double,
    ): Double = PI * r * GeomMath.TESS_TOL_MM * length / 3.0

    // ---- the plumbing ----

    private fun volumeOf(
        ref: SolidRef,
        what: String,
    ): Double {
        val ev = Evaluator()
        val r = ev.eval(ref.node)
        assertTrue(r is EvalResult.Ok, "$what: ${(r as? EvalResult.Invalid)?.reason}")
        val mesh = ev.solid(ref).mesh
        assertManifold(mesh, what)
        return Geom3.volume(mesh)
    }

    private fun meshOf(ref: SolidRef): Mesh3 = Evaluator().solid(ref).mesh

    private fun refusalOf(ref: SolidRef): String? = (Evaluator().eval(ref.node) as? EvalResult.Invalid)?.reason

    private fun blend(
        cx: Construction,
        base: SolidRef,
        size: Double,
        kind: BlendKind,
        targets: List<Int>,
    ): SolidRef {
        val body = Evaluator().solid(base)
        val (choices, why) = Blend3.choicesFor(body, targets, BlendSection(kind, size))
        assertNotNull(choices, why?.render())
        // one address per gesture, so a many-edge fixture is many gestures — which is also what makes the
        // order-independence assertions below say something
        var out = base
        for ((k, i) in targets.withIndex()) {
            out = cx.blend(out, out, cx.planeXY(), cx.const(size.mm), kind, whole = false, address = i, choices = listOf(choices[k]))
        }
        return out
    }

    /** A prism over the polygon [xy], [h] deep. */
    private fun prism(
        cx: Construction,
        xy: List<Vec2>,
        h: Double,
    ): SolidRef {
        val pts = xy.mapIndexed { i, p -> cx.freePoint("p$i", p.x.mm, p.y.mm) }
        val segs = xy.indices.map { cx.segment(pts[it], pts[(it + 1) % xy.size]) }
        return cx.extrude(cx.sketchOn(cx.planeXY(), cx.region(cx.loop(*segs.toTypedArray()))), cx.const(h.mm))
    }

    /** The indices of the base's edges that touch [at], with each one's length. */
    private fun edgesAt(
        base: SolidRef,
        at: Vec3,
    ): List<Pair<Int, Double>> {
        val edges = assertNotNull(Section3.edges(Evaluator().solid(base).feature).first, "the solid names its edges")
        val out = ArrayList<Pair<Int, Double>>()
        for (i in edges.indices) {
            val path = Blend3.edgePath(edges[i]).first ?: continue
            val el = path.elements.singleOrNull() ?: continue
            val ends = listOf(el.start, el.end)
            if (ends.any { (it - at).length() <= 1e-6 }) out.add(i to (el.end - el.start).length())
        }
        return out
    }

    // ---- the box corner: three bands, one ball ----

    /**
     * **Three edges at a box corner, rounded** — the cell keeps the ball's own octant, so the three bands'
     * sum loses `(2 − 7π/12) r³` there. Bracketed: never below the exact figure, never above it by more than
     * the chords the arcs reach the engine as.
     */
    @Test
    fun aBoxCornersThreeRoundsLeaveTheBallsOctant() {
        val cx = Construction()
        val box = prism(cx, listOf(Vec2(0.0, 0.0), Vec2(40.0, 0.0), Vec2(40.0, 30.0), Vec2(0.0, 30.0)), 20.0)
        val before = volumeOf(box, "the box")
        assertClose(before, 40.0 * 30.0 * 20.0, 1e-6, "the box is 40 x 30 x 20")
        val r = 4.0
        val corner = Vec3(0.0, 0.0, 20.0)
        val three = edgesAt(box, corner)
        assertTrue(three.size == 3, "three edges meet at $corner — ${three.size} do")
        val rounded = blend(cx, box, r, BlendKind.FILLET, three.map { it.first })
        val after = volumeOf(rounded, "the box with the three edges at one corner rounded")

        val exact = wedgeArea(r, BlendKind.FILLET) * three.sumOf { it.second } - (2.0 - 7.0 * PI / 12.0) * r * r * r
        val chords = chordSurplus(r, three.sumOf { it.second })
        assertTrue(before - after >= exact - 1e-6, "the three bands take at least the exact $exact mm^3 — they took ${before - after}")
        assertTrue(before - after <= exact + chords, "…and at most that plus the chords — they took ${before - after}")

        // **the discriminator**: with no ball at the vertex the three cylinders keep a point sticking out
        // toward it, and the loss would be the three-cylinder figure — which even with every chord in its
        // favour cannot reach what was measured. (The surface itself is asserted in
        // [theVertexSurfaceIsTheBallsOwn], which is where the two constructions really part company.)
        val noBall = wedgeArea(r, BlendKind.FILLET) * three.sumOf { it.second } - (3.0 * (1.0 - PI / 4.0) - (sqrt(2.0) - 1.0)) * r * r * r
        assertTrue(before - after > noBall + chords, "with no ball the loss could not pass ${noBall + chords} — it was ${before - after}")
    }

    /** The same three edges bevelled — three planes meeting at a point, so the figure is **exact**. */
    @Test
    fun aBoxCornersThreeBevelsMeetInTheirOwnApex() {
        val cx = Construction()
        val box = prism(cx, listOf(Vec2(0.0, 0.0), Vec2(40.0, 0.0), Vec2(40.0, 30.0), Vec2(0.0, 30.0)), 20.0)
        val before = volumeOf(box, "the box")
        val c = 4.0
        val three = edgesAt(box, Vec3(0.0, 0.0, 20.0))
        val bevelled = blend(cx, box, c, BlendKind.CHAMFER, three.map { it.first })
        val after = volumeOf(bevelled, "the box with the three edges at one corner bevelled")
        val exact = wedgeArea(c, BlendKind.CHAMFER) * three.sumOf { it.second } - 0.75 * c * c * c
        assertClose(before - after, exact, abs(exact) * 1e-5, "three bevels and the apex where their planes cross, exactly")
    }

    /**
     * **The corner is round, and that is asked of the body rather than of its triangles.** Every direction
     * inside the patch, walked out from the ball's own centre, meets the surface at the ball's radius.
     * Before this session the diagonal met it at `1.22 r` — the point three cylinders keep — which is the
     * reporter's *"sharp edges, not a round surface"*.
     */
    @Test
    fun theVertexSurfaceIsTheBallsOwn() {
        val cx = Construction()
        val box = prism(cx, listOf(Vec2(0.0, 0.0), Vec2(40.0, 0.0), Vec2(40.0, 30.0), Vec2(0.0, 30.0)), 20.0)
        val r = 4.0
        val three = edgesAt(box, Vec3(0.0, 0.0, 20.0))
        val mesh = meshOf(blend(cx, box, r, BlendKind.FILLET, three.map { it.first }))
        assertManifold(mesh, "the rounded box corner")

        val centre = Vec3(r, r, 20.0 - r)
        for (u in ballDirections()) {
            var lo = 0.0
            var hi = 3.0 * r
            repeat(40) {
                val mid = (lo + hi) / 2.0
                if (Geom3.encloses(mesh, centre + u * mid)) lo = mid else hi = mid
            }
            val reach = (lo + hi) / 2.0
            assertClose(reach, r, 0.05, "the corner stands at the ball's own radius along $u — it stands at $reach")
        }
    }

    /** A spread of directions inside the patch: the diagonal, the three corners' own, and between them. */
    private fun ballDirections(): List<Vec3> {
        val corners = listOf(Vec3(-1.0, 0.0, 0.0), Vec3(0.0, -1.0, 0.0), Vec3(0.0, 0.0, 1.0))
        val out = ArrayList<Vec3>()
        out.add((corners[0] + corners[1] + corners[2]).normalized())
        for (i in 0 until 3) {
            for (t in listOf(0.25, 0.5, 0.75)) {
                out.add((corners[i] * t + corners[(i + 1) % 3] * (1.0 - t)).normalized())
            }
        }
        return out
    }

    /**
     * **A vertex at 30°, 60° and 120°** — the two side faces stand at the plan's own angle, so nothing about
     * the ball is square here. It sits at `r` from all three faces whatever they do, which is exactly why
     * the round works at every angle where the bevel's own three planes need not meet the same way.
     */
    @Test
    fun aVertexAtEveryPlanAngleBuildsWatertight() {
        for ((what, xy) in listOf(
            "a 30 degree plan corner" to listOf(Vec2(0.0, 0.0), Vec2(200.0, 0.0), Vec2(200.0 * cos(PI / 6.0), 200.0 * sin(PI / 6.0))),
            "a 60 degree plan corner" to listOf(Vec2(0.0, 0.0), Vec2(120.0, 0.0), Vec2(60.0, 60.0 * sqrt(3.0))),
            "a 120 degree plan corner" to
                listOf(Vec2(0.0, 0.0), Vec2(120.0, 0.0), Vec2(180.0, 60.0 * sqrt(3.0)), Vec2(-60.0, 60.0 * sqrt(3.0))),
        )) {
            val cx = Construction()
            val base = prism(cx, xy, 40.0)
            val before = volumeOf(base, "$what before")
            val r = 3.0
            val three = edgesAt(base, Vec3(xy[0].x, xy[0].y, 40.0))
            assertTrue(three.size == 3, "$what: three edges meet at its first corner — ${three.size} do")
            val rounded = blend(cx, base, r, BlendKind.FILLET, three.map { it.first })
            val after = volumeOf(rounded, "$what, its three edges rounded")
            assertTrue(after < before, "$what loses material")
            // …and the ball's own tangency with the cap is on the surface, which is what says it is there
            val mesh = meshOf(rounded)
            val a = (xy[1] - xy[0]).normalized()
            val b = (xy[xy.size - 1] - xy[0]).normalized()
            val bis = (a + b).normalized()
            val half = acos(a.dot(b).coerceIn(-1.0, 1.0)) / 2.0
            val touch = xy[0] + bis * (r / sin(half))
            assertTrue(Geom3.encloses(mesh, Vec3(touch.x, touch.y, 40.0 - 0.05)), "$what keeps the ball's own tangency with the cap")
            assertTrue(!Geom3.encloses(mesh, Vec3(touch.x, touch.y, 40.0 + 0.05)), "$what: and nothing above it")
        }
    }

    // ---- order independence ----

    /**
     * **Any order gives the one body.** The vertex is a fact about which bands share a point, not about
     * which was cut first — so the three edges of a box corner, taken in any of the six orders, come out the
     * same to a part in ten million.
     */
    @Test
    fun theVertexIsTheSameBodyInEveryOrder() {
        val box = listOf(Vec2(0.0, 0.0), Vec2(40.0, 0.0), Vec2(40.0, 30.0), Vec2(0.0, 30.0))
        val r = 4.0
        val orders = listOf(listOf(0, 1, 2), listOf(0, 2, 1), listOf(1, 0, 2), listOf(1, 2, 0), listOf(2, 0, 1), listOf(2, 1, 0))
        var first: Double? = null
        for (order in orders) {
            val cx = Construction()
            val base = prism(cx, box, 20.0)
            val three = edgesAt(base, Vec3(0.0, 0.0, 20.0)).map { it.first }
            val got = volumeOf(blend(cx, base, r, BlendKind.FILLET, order.map { three[it] }), "the corner rounded in the order $order")
            val known = first
            if (known == null) first = got else assertClose(got, known, abs(known) * 1e-6, "the order $order is the same body")
        }
    }

    // ---- the refusal ----

    /**
     * **A vertex with no room for the ball says so, and heals** (OP-3). The ball sits `r/ sin(θ/2)` in from
     * the corner along each face's own bisector and its bands reach that far along every edge, so on a plate
     * whose edges are short enough there is nowhere for it to sit — named, with the largest that fits.
     */
    @Test
    fun aVertexWithNoRoomSaysSoAndHeals() {
        // a plate 40 x 12, 40 deep: every face is far wider than the radius, so nothing here is a rounding
        // outgrowing a face. What is too small is the 12 mm rim between two corners — the upright at one of
        // them is rounded third, which makes that corner a vertex, and the ball wants 7 mm of the rim while
        // the crossing at its other end wants 7 more.
        val plate = listOf(Vec2(0.0, 0.0), Vec2(40.0, 0.0), Vec2(40.0, 12.0), Vec2(0.0, 12.0))

        fun rim(base: SolidRef): List<Int> =
            listOf(
                edgeBetween(base, Vec3(0.0, 12.0, 40.0), Vec3(0.0, 0.0, 40.0)),
                edgeBetween(base, Vec3(0.0, 0.0, 40.0), Vec3(40.0, 0.0, 40.0)),
                edgeBetween(base, Vec3(0.0, 0.0, 0.0), Vec3(0.0, 0.0, 40.0)),
                edgeBetween(base, Vec3(40.0, 12.0, 40.0), Vec3(0.0, 12.0, 40.0)),
            )

        val cx = Construction()
        val base = prism(cx, plate, 40.0)
        volumeOf(base, "the plate")
        val said = assertNotNull(refusalOf(blend(cx, base, 7.0, BlendKind.FILLET, rim(base))), "a 7 mm ball at one end of a 12 mm rim")
        assertTrue(said.contains("is too sharp for a fillet of radius 7 mm"), "it names the size that does not fit: $said")
        assertTrue(said.contains("The largest that fits there is about"), "…and the one that would: $said")
        assertTrue(said.contains("the vertex where"), "…and that it is the vertex: $said")
        val fits =
            assertNotNull(
                Regex("about ([0-9.]+) mm").findAll(said).last().groupValues[1].toDoubleOrNull(),
                "the reason states a number: $said",
            )
        assertTrue(fits > 0.0 && fits < 7.0, "and it is smaller than the one asked for: $fits")

        val cx2 = Construction()
        val base2 = prism(cx2, plate, 40.0)
        val ok = blend(cx2, base2, 4.0, BlendKind.FILLET, rim(base2))
        assertTrue(volumeOf(ok, "the same plate at 4 mm") < 40.0 * 12.0 * 40.0, "…which builds, so the refusal healed")
    }

    /** The index of the base's edge running between [p] and [q]. */
    private fun edgeBetween(
        base: SolidRef,
        p: Vec3,
        q: Vec3,
    ): Int {
        val edges = assertNotNull(Section3.edges(Evaluator().solid(base).feature).first, "the solid names its edges")
        val at =
            edges.indices.firstOrNull { i ->
                val el = Blend3.edgePath(edges[i]).first?.elements?.singleOrNull() ?: return@firstOrNull false
                val ends = listOf(el.start, el.end)
                ends.any { (it - p).length() <= 1e-6 } && ends.any { (it - q).length() <= 1e-6 }
            }
        assertTrue(at != null, "an edge runs from $p to $q")
        return at!!
    }

    // ---- the corner is a face of its own ----

    /**
     * **The ball is a named face**, so everything that reads a face list reads it too (design point 2): it is
     * appended after the bands ([FaceName.BlendCorner]), the 3D face pick names it, and a working plane cuts
     * it in the **exact circle arc** a plane cuts a sphere in — clipped to the spherical triangle by its own
     * three great-circle sides, so what is drawn is the piece of the circle the solid actually has.
     *
     * It carries a reason rather than a plane, exactly as a band does: there is nothing to sketch on a ball,
     * and the message says where to put a datum plane instead (OP-3, session 65's rule).
     */
    @Test
    fun theBallIsANamedFaceThatSectionsAsItsOwnArc() {
        val cx = Construction()
        val box = prism(cx, listOf(Vec2(0.0, 0.0), Vec2(40.0, 0.0), Vec2(40.0, 30.0), Vec2(0.0, 30.0)), 20.0)
        val r = 4.0
        val three = edgesAt(box, Vec3(0.0, 0.0, 20.0))
        val rounded = blend(cx, box, r, BlendKind.FILLET, three.map { it.first })
        val solid = Evaluator().solid(rounded)
        assertManifold(solid.mesh, "the rounded box corner")

        val faces = assertNotNull(Section3.faces(solid.feature).first, "the dressed part names its faces")
        val corner = assertNotNull(faces.firstOrNull { it.name is FaceName.BlendCorner }, "a corner face: ${faces.map { it.name.label }}")
        assertTrue(corner.name.label.render().startsWith("the rounded corner where edge #"), "it says what it is: ${corner.name.label}")
        assertTrue(corner.name.label.contains(" and edge #"), "…and which three edges meet there: ${corner.name.label}")
        assertEquals(faces.last().name, corner.name, "the corners are appended last, so no other face's index moved")
        assertTrue(corner.plane == null, "a ball is not a plane")
        assertTrue(assertNotNull(corner.reason, "and it says so").contains("a sphere"), "naming the surface: ${corner.reason}")
        assertTrue(corner.reason!!.contains("datum plane"), "…and what to do instead: ${corner.reason}")

        // the 3D face pick lands on it, aimed at a point of the ball inside the patch
        val centre = Vec3(r, r, 20.0 - r)
        val u = Vec3(-1.0, -1.0, 1.0).normalized()
        val pick = assertNotNull(Section3.faceAt(solid.feature, centre + u * r, u, 1e-3).first, "the pick finds a face there")
        assertEquals(corner.name, pick.patch.name, "and it is the corner: ${pick.patch.name.label}")

        // …and a working plane a millimetre above the ball's centre cuts it in a quarter of the exact circle
        val sec = Section3.sectionOf(solid, Plane3(Vec3(0.0, 0.0, 20.0 - r + 1.0), Vec3.X, Vec3.Y))
        val drawn = assertNotNull(sec.edges.firstOrNull { it.provenance.contains("rounded corner") }, "the corner is in the section")
        val arc = assertNotNull(drawn.curve as? ProfileElement.ArcE, "and it is drawn as a curve, not chords: ${drawn.reason}")
        assertClose(arc.arc.radius, sqrt(r * r - 1.0), 1e-9, "the exact circle a plane cuts a sphere in")
        assertClose(abs(GeomMath.sweep(arc.arc)), PI / 2.0, 1e-9, "clipped to the patch's own quarter")
        assertClose(arc.arc.center.x, r, 1e-9, "centred under the ball")
        assertClose(arc.arc.center.y, r, 1e-9, "centred under the ball")
        assertTrue(!drawn.approximated, "and nothing about it is sampled")
    }

    // ---- composing with the run rule and the ortho path's own corner radius ----

    /**
     * **A rounded ortho corner is a hand-over, not a vertex** (design point 5): the arc a path's corner
     * radius puts in its rim meets both legs *tangentially*, so there is nothing there for a corner to be —
     * and nothing is built. Asserted as the absence of any corner face at all on a rim whose one inside
     * corner carries a radius and whose other four turn convex crossings, which add no surface either.
     *
     * The fixture is GitHub #25's own script (session 79's other package): a five-corner ortho loop with two
     * corners filleted in 2D, extruded — so this also says the two packages compose.
     */
    @Test
    fun aRoundedOrthoCornerIsAHandOverAndNothingIsPatched() {
        val script =
            """
constructit 3
orthostart -73.625,28.875 -> e1
orthovertex -73.625,84.125 -> e2,e3
orthovertex 67.125,84.125 -> e4,e5
orthovertex 67.125,56.125 -> e6,e7
orthovertex -37.375,56.125 -> e8,e9
orthovertex -37.375,28.875 -> e10,e11
orthoclose -> e12
param "r" = 5mm
tool fillet els=e3,e5 clicks=-73.625,71.125;-64.875,84.125 scalar="r" signs=-1;1 -> e13
tool fillet els=e9,e11 clicks=-28.875,56.125;-37.625,46.625 scalar="r" signs=-1;1 -> e14
param "h" = 18mm
tool extrude els=e12 clicks=-57.375,28.625 scalar="h" -> e15
param "b" = 2mm
""".trimStart()
        for (tool in listOf("filletfaceedges", "chamferfaceedges")) {
            val ed = Editor()
            ed.replaceDocument(DocumentFormat.load(script))
            ed.activeScalar = ed.doc.scalars.first { it.name == "b" }
            ed.setTool(tool)
            val s = ed.camera.worldToScreen(Vec2(-37.375, 28.875))
            ed.pointerMove(s)
            ed.pointerDown(s)
            ed.pointerUp(s)
            val said = assertNotNull(ed.statusHint, "$tool says what it did")
            assertTrue(said.contains("(8 edges)"), "$tool takes the rim's eight pieces: $said")
            val tip = ed.doc.elements.last { it.kind == ElementKind.SOLID }

            @Suppress("UNCHECKED_CAST")
            val solid = Evaluator().solid(tip.ref as SolidRef)
            assertManifold(solid.mesh, "the ortho cap rim, $tool")
            val faces = assertNotNull(Section3.faces(solid.feature).first, "the dressed part names its faces")
            assertTrue(
                faces.none { it.name is FaceName.BlendCorner },
                "a tangent hand-over is no corner and a crossing adds no surface: ${faces.map { it.name.label }}",
            )
        }
    }

    // ---- the reporters' own scripts ----

    private val orthoPrefix31 =
        """
constructit 3
orthostart -13.18902721970728,-16.006179064359657 -> e1
orthovertex -13.18902721970728,31.488369994030137 -> e2,e3
orthovertex 50.93454370701417,31.488369994030137 -> e4,e5
orthovertex 50.93454370701417,21.488369994030137 -> e6,e7
orthovertex -3.1890272197072793,21.488369994030137 -> e8,e9
orthovertex -3.1890272197072793,-16.006179064359657 -> e10,e11
orthoclose -> e12
param "h" = 20mm
tool extrude els=e11 clicks=-48.125,37.875 scalar="h" -> e13
hide els=e1,e2,e3,e4,e5,e6,e7,e8,e9,e10,e11,e12,e13
show els=e1,e2,e3,e4,e5,e6,e7,e8,e9,e10,e11,e12,e13
tool makerel els=e10,e1 clicks=-5.003059576596437,-32.398104273234466;-27.16684620319296,-32.96159037391065 dofs=10mm
tool makerel els=e6,e4 clicks=61.86395770364392,0.09626086575873762;62.80310120477089,14.934728183564884 dofs=-10mm
param "r" = 3mm
""".trimStart()

    /** **GitHub #31's script, verbatim.** */
    private val issue31 =
        orthoPrefix31 +
            """
tool filletedge els=e13 clicks=-4.752493982761422,46.49095575601497 scalar="r" signs=13;-1;1;0;1 -> e14
tool filletedge els=e14 clicks=30.09910577599922,89.3378106087082 scalar="r" signs=14;-1;1;0;1 -> e15
"""

    /** The same two edges the other way round — the order the corner must not care about. */
    private val issue31Reversed =
        orthoPrefix31 +
            """
tool filletedge els=e13 clicks=30.09910577599922,89.3378106087082 scalar="r" signs=14;-1;1;0;1 -> e14
tool filletedge els=e14 clicks=-4.752493982761422,46.49095575601497 scalar="r" signs=13;-1;1;0;1 -> e15
"""

    /** **GitHub #32's script, verbatim.** */
    private val issue32 =
        """
constructit 3
orthostart 13.508170548459765,9.135142749812118 -> e1
orthovertex 13.508170548459765,58.951258452291455 -> e2,e3
orthovertex 75.0230090157776,58.951258452291455 -> e4,e5
orthovertex 75.0230090157776,48.951258452291455 -> e6,e7
orthovertex 23.508170548459766,48.951258452291455 -> e8,e9
orthovertex 23.508170548459766,9.135142749812118 -> e10,e11
orthoclose -> e12
param "h" = 20mm
tool extrude els=e11 clicks=-48.125,37.875 scalar="h" -> e13
hide els=e1,e2,e3,e4,e5,e6,e7,e8,e9,e10,e11,e12,e13
show els=e1,e2,e3,e4,e5,e6,e7,e8,e9,e10,e11,e12,e13
tool makerel els=e10,e1 clicks=-5.003059576596437,-32.398104273234466;-27.16684620319296,-32.96159037391065 dofs=10mm
tool makerel els=e6,e4 clicks=61.86395770364392,0.09626086575873762;62.80310120477089,14.934728183564884 dofs=-10mm
param "r" = 2mm
tool filletfaceedges els=e13 clicks=26.833273700809244,95.74436041407131 scalar="r" signs=7;-1;1;0;1;-1;1;0;1;-1;1;0;1;-1;1;0;1;-1;1;0;1;-1;1;0;1 -> e14
tool filletfaceedges els=e14 clicks=32.34073371634995,53.47538087164619 scalar="r" signs=6;1;-1;0;1;1;-1;0;1;1;-1;0;1;1;-1;0;1;1;-1;0;1;1;-1;0;1 -> e15
tool filletedge els=e15 clicks=56.593958131928304,53.50252059386834 scalar="r" signs=3;-1;1;0;1 -> e16
tool filletedge els=e16 clicks=58.65246448986616,64.0194288924451 scalar="r" signs=4;-1;1;0;1 -> e17
tool filletedge els=e17 clicks=16.108281549317358,18.17634880663195 scalar="r" signs=0;-1;1;0;1 -> e18
""".trimStart()

    /** The same seven roundings with the three uprights taken first — the order the vertices must not care about. */
    private val issue32Reordered =
        """
constructit 3
orthostart 13.508170548459765,9.135142749812118 -> e1
orthovertex 13.508170548459765,58.951258452291455 -> e2,e3
orthovertex 75.0230090157776,58.951258452291455 -> e4,e5
orthovertex 75.0230090157776,48.951258452291455 -> e6,e7
orthovertex 23.508170548459766,48.951258452291455 -> e8,e9
orthovertex 23.508170548459766,9.135142749812118 -> e10,e11
orthoclose -> e12
param "h" = 20mm
tool extrude els=e11 clicks=-48.125,37.875 scalar="h" -> e13
param "r" = 2mm
tool filletedge els=e13 clicks=56.593958131928304,53.50252059386834 scalar="r" signs=3;-1;1;0;1 -> e14
tool filletedge els=e14 clicks=58.65246448986616,64.0194288924451 scalar="r" signs=4;-1;1;0;1 -> e15
tool filletedge els=e15 clicks=16.108281549317358,18.17634880663195 scalar="r" signs=0;-1;1;0;1 -> e16
tool filletfaceedges els=e16 clicks=26.833273700809244,95.74436041407131 scalar="r" signs=7;-1;1;0;1;-1;1;0;1;-1;1;0;1;-1;1;0;1;-1;1;0;1;-1;1;0;1 -> e17
tool filletfaceedges els=e17 clicks=32.34073371634995,53.47538087164619 scalar="r" signs=6;1;-1;0;1;1;-1;0;1;1;-1;0;1;1;-1;0;1;1;-1;0;1;1;-1;0;1 -> e18
""".trimStart()

    @Suppress("UNCHECKED_CAST")
    private fun tipOf(script: String): SolidRef = DocumentFormat.load(script).elements.last { it.kind == ElementKind.SOLID }.ref as SolidRef

    /**
     * **GitHub #31, verbatim** — *"Filleting two adjacent 3D edges building a concave corner keeps a spike at
     * the corner"*. Both roundings build watertight, the spike is gone, and either order gives one body.
     *
     * The figure says exactly what the turn took: the two bands never overlapped at that corner, so its whole
     * quarter turn is new removal — `(π/2)·r³(5/6 − π/4)`, less the facets its revolution reaches the engine
     * as.
     */
    @Test
    fun issue31TheInsideCornerIsRoundedRatherThanSpiked() {
        val before = volumeOf(tipOf(orthoPrefix31), "the reporter's ortho prism")
        val r = 3.0
        val got = volumeOf(tipOf(issue31), "the reporter's two roundings")
        val other = volumeOf(tipOf(issue31Reversed), "the same two, the other way round")
        assertClose(other, got, abs(got) * 1e-6, "the inside corner does not care which edge came first")

        val lengths = 21.488369994030137 - (-16.006179064359657) + (50.93454370701417 - (-3.1890272197072793))
        val turn = turnTakes(r, BlendKind.FILLET, PI / 2.0)
        val bands = wedgeArea(r, BlendKind.FILLET) * lengths
        assertTrue(before - got >= bands + turn - chordSurplus(r, lengths) * 0.0 - 0.02 * turn, "the turn is taken: ${before - got}")
        assertTrue(before - got <= bands + turn + chordSurplus(r, lengths), "…and no more than the bands plus their chords: ${before - got}")

        // **no spike**: on the corner's own bisector the cap now stops at the ball's arc about the vertex,
        // so a point a whisker inside that arc is off the body and one a whisker outside is on it
        val mesh = meshOf(tipOf(issue31))
        val v = Vec2(-3.1890272197072793, 21.488369994030137)
        val bis = (Vec2(-1.0, -1.0)).normalized()
        for (t in listOf(0.3, 0.5, 0.7)) {
            val q = v + bis * (r * t)
            assertTrue(!Geom3.encloses(mesh, Vec3(q.x, q.y, 20.0 - 0.02)), "the spike is gone at $t of the ball's radius")
        }
        val out = v + bis * (r * 2.5)
        assertTrue(Geom3.encloses(mesh, Vec3(out.x, out.y, 20.0 - 0.02)), "…and the cap is still there beyond the ball")
    }

    /**
     * **GitHub #32, verbatim** — *"Adjacent filleted edges do not produce 'round' corners"*. Seven roundings:
     * both cap rims and three uprights, and every vertex where three of them meet is the ball's own patch.
     *
     * Asserted three ways: every body watertight, the surface at each such vertex standing at the ball's
     * radius from the ball's own centre (which the three-cylinder point did not), and the whole thing
     * order-independent — the same seven roundings with the uprights taken first.
     */
    @Test
    fun issue32EveryVertexWhereThreeBandsMeetIsRound() {
        val doc = DocumentFormat.load(issue32)
        val ev = Evaluator()
        for (el in doc.elements.filter { it.kind == ElementKind.SOLID }) {
            val r = ev.eval(el.ref.node)
            assertTrue(r is EvalResult.Ok, "${doc.nameOf(el)}: ${(r as? EvalResult.Invalid)?.reason}")
            assertManifold(ev.solid(el.ref as SolidRef).mesh, doc.nameOf(el))
        }
        val got = volumeOf(tipOf(issue32), "the reporter's seven roundings")
        assertClose(volumeOf(tipOf(issue32Reordered), "the uprights first"), got, abs(got) * 1e-6, "any order, one body")

        // the three uprights the reporter rounded stand at the plan corners below; at each of their two ends
        // three bands meet, and the corner there is the ball's own surface
        val r = 2.0
        val mesh = meshOf(tipOf(issue32))
        val corners =
            listOf(
                Vec2(75.0230090157776, 58.951258452291455) to Vec2(-1.0, -1.0),
                Vec2(75.0230090157776, 48.951258452291455) to Vec2(-1.0, 1.0),
                Vec2(13.508170548459765, 9.135142749812118) to Vec2(1.0, 1.0),
            )
        for ((at, inward) in corners) {
            // the ball stands r in from **each** of the three faces, so its plan position steps r along
            // each of the two side faces' own normals — not r along the corner's bisector
            for (z in listOf(20.0 - r, r)) {
                val centre = Vec3(at.x + inward.x * r, at.y + inward.y * r, z)
                for (u in listOf(
                    Vec3(-inward.x, -inward.y, 0.0).normalized(),
                    Vec3(-inward.x, -inward.y, if (z > 10.0) 1.0 else -1.0).normalized(),
                )) {
                    var lo = 0.0
                    var hi = 3.0 * r
                    repeat(40) {
                        val mid = (lo + hi) / 2.0
                        if (Geom3.encloses(mesh, centre + u * mid)) lo = mid else hi = mid
                    }
                    assertClose((lo + hi) / 2.0, r, 0.06, "the vertex at $at, $z stands at the ball's radius along $u")
                }
            }
        }
    }

    /**
     * **A face whose boundary is half rounded already still rounds the rest** (session 80's own gap, found by
     * the orchestrator's probe).
     *
     * *"Fillet the edges of a face"* took the face's whole boundary and refused the moment one piece of it had
     * been rounded away by an earlier gesture — *"boundary edge #4 of the bottom face was rounded away by the
     * fillet of 4 mm"* — so a box could not be finished by faces at all, and the only route to a rounded
     * corner was one upright at a time, which is exactly the detour GitHub #32's reporter had to take. The
     * gesture now takes the edges of that face that are **still creases** and says what it skipped; the ones
     * already round are the *existing* bands the chain stitches into the same tool, so the three-band vertex
     * comes out the same whichever gesture arrived last.
     *
     * Asserted on the reporter's own drawing: rounding the face over boundary edge #4 — two of whose four
     * edges the cap gestures already took — and then the one remaining upright gives the body its three
     * separate upright gestures give, to a part in ten million.
     */
    @Test
    fun aFaceWhoseEdgesAreHalfRoundedRoundsTheRest() {
        val reference = volumeOf(tipOf(issue32), "the reporter's caps and three uprights")

        val ed = Editor()
        ed.replaceDocument(DocumentFormat.load(issue32.lines().takeWhile { !it.startsWith("tool filletfaceedges") }.joinToString("\n") + "\n"))
        ed.activeScalar = ed.doc.scalars.first { it.name == "r" }
        val eye = Vec3(44.0, 34.0, 10.0)
        // the two caps, then the side face whose own two uprights are the reporter's #4 and #5 corners
        blendInView(ed, Tools.BLEND_FACE, Camera3(target = eye, distance = 260.0, yaw = -1.0, pitch = 1.1), Vec3(18.5, 30.0, 20.0), "(6 edges)")
        blendInView(ed, Tools.BLEND_FACE, Camera3(target = eye, distance = 260.0, yaw = -1.0, pitch = -1.1), Vec3(18.5, 30.0, 0.0), "(6 edges)")
        blendInView(
            ed,
            Tools.BLEND_FACE,
            Camera3(target = eye, distance = 260.0, yaw = 0.0, pitch = 0.2),
            Vec3(75.0230090157776, 53.951258452291455, 10.0),
            "(2 edges — 2 were already rounded)",
        )
        blendInView(ed, Tools.BLEND_EDGE, Camera3(target = eye, distance = 260.0, yaw = 2.4, pitch = 0.2), Vec3(13.508170548459765, 9.135142749812118, 10.0), null)

        @Suppress("UNCHECKED_CAST")
        val got = volumeOf(ed.doc.elements.last { it.kind == ElementKind.SOLID }.ref as SolidRef, "the caps, one side face and one upright")
        assertClose(got, reference, abs(reference) * 1e-6, "a face gesture that skips what is round already builds the same body")
    }

    /** One blend gesture made in the 3D view, aimed at [at]; asserts a new solid and what the note said. */
    private fun blendInView(
        ed: Editor,
        tool: String,
        cam: Camera3,
        at: Vec3,
        says: String?,
    ) {
        ed.activeScalar = ed.doc.scalars.first { it.name == "r" }
        ed.setTool(tool)
        // one gesture, one **rounding**: since OP-30 that is one entry under one dressed body
        val before = ed.doc.elements.count { it.kind == ElementKind.DRESSING }
        val vp = Viewport3(camera = cam, widthPx = 800.0, heightPx = 600.0)
        vp.editor = ed
        vp.shown = true
        val screen = assertNotNull(cam.project(at, vp.widthPx, vp.heightPx), "$at has an image on screen")
        vp.pointerDown(screen)
        vp.pointerUp(screen)
        assertEquals(before + 1, ed.doc.elements.count { it.kind == ElementKind.DRESSING }, "$tool at $at: ${ed.statusHint}")
        if (says != null) assertTrue(says in assertNotNull(ed.statusHint, "it says what it did"), "$says: ${ed.statusHint}")
    }

    /** Both reporters' files replay to the patched bodies and save back byte-equal (OP-18). */
    @Test
    fun bothReportersFilesReplayAndRoundTrip() {
        for (script in listOf(issue31, issue32)) {
            val once = DocumentFormat.save(DocumentFormat.load(script))
            assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "the file is a fixed point")
            assertManifold(meshOf(tipOf(once)), "the replayed body")
        }
    }
}
