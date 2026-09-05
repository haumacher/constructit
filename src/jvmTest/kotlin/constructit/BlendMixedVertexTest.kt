package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.Construction
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.editor.Camera3
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.Viewport3
import constructit.geom.Blend3
import constructit.geom.BlendKind
import constructit.geom.BlendSection
import constructit.geom.FaceName
import constructit.geom.Geom3
import constructit.geom.Mesh3
import constructit.geom.Section3
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.units.mm
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **The corner where a concave band meets two convex ones** — the pivot about a *band* (session 81, the
 * user's report on top of GitHub #31 and #32).
 *
 * > *"Issue #31 now looks fine, but when then also adding a fillet to the extrusion edge of that corner
 * > things look really weird: The fillet rounding stands out of the edge. Also filleting the extrusion edge
 * > of an outer corner produces invalid geometry."*
 *
 * Session 80 wrote the inside corner as *"the ball turns about the upright, **its centre on a circle of
 * radius `r`**"*, and that sentence assumed the upright is a **sharp** edge. Round the upright itself and it
 * is no longer there: the ball pivots about the *band* that stands in its place, its centre on a circle of
 * radius `r + r_U` about that band's own axis, and the surface is a **ring** torus where the horn torus was.
 * Said once for every kind of upright: *the pair's section follows the upright band's own end-section curve,
 * piece by piece, turning about the vertical through each joint by the angle that curve's tangent turns
 * there* — so a sharp upright is the degenerate curve of one point and one turn (session 80 exactly), a
 * chamfer upright is a turn, a slide and a turn, and a drawn one is its own chain.
 *
 * The second half is **when** the tools run. Rounding the upright changes a corner two *earlier* bands
 * share, and the material their old corner took cannot be given back by another cut — so the chain is
 * rebuilt from its own undressed root, the upright's tool before the pair's: `(root ∪ U) − chain'` for two
 * convex bands round a concave upright, and `(root − U) ∪ fills'` for the other sector.
 *
 * The figures are closed form. `w = r² − πr²/4` is a round's wedge area and `c²/2` a bevel's; a corner
 * between two bands at `θ` takes `cot(θ/2)·∫δ²` off their sum; a pivot of `φ` about a curve whose section's
 * centroid stands `ρ` from the axis **adds** Pappus' `w·φ·ρ`, which for `ρ = 0` is session 80's own
 * `φ·∫δ²/2`. Everything planar is asserted exactly; everything with an arc in it is bracketed by the chords
 * the arc reaches the engine as.
 */
class BlendMixedVertexTest {
    // ---- the reporter's own file, verbatim ----

    private val fixture =
        """
        constructit 4
        orthostart -26.875,-32.375 -> e1
        orthovertex -26.875,15.375 -> e2,e3
        orthovertex 61.875,15.375 -> e4,e5
        orthovertex 61.875,0.375 -> e6,e7
        orthovertex -5.521648428788623,0.375 -> e8,e9
        orthovertex -5.521648428788623,-32.375 -> e10,e11
        orthoclose -> e12
        param "h" = 20mm
        tool extrude els=e11 clicks=-48.125,37.875 scalar="h" -> e13
        hide els=e1,e2,e3,e4,e5,e6,e7,e8,e9,e10,e11,e12,e13
        show els=e1,e2,e3,e4,e5,e6,e7,e8,e9,e10,e11,e12,e13
        param "r" = 5mm
        tool filletfaceedges els=e13 clicks=-49.04165673681413,26.764787231242565 scalar="r" signs=7;-1;1;0;1;-1;1;0;1;-1;1;0;1;-1;1;0;1;-1;1;0;1;-1;1;0;1 -> e14
        tool filletedge els=e14 clicks=-12.42301760578684,8.71293599354776 scalar="r" signs=2;-1;1;0;-1 -> e15
        tool filletedge els=e15 clicks=-26.1259433757467,-23.874512114885874 scalar="r" signs=0;-1;1;0;1 -> e16
        """.trimIndent().lines().joinToString("\n") { it.trim() } + "\n"

    /** The L's own plan: the reporter's six corners, in his own order. */
    private val plan =
        listOf(
            Vec2(-26.875, -32.375),
            Vec2(-26.875, 15.375),
            Vec2(61.875, 15.375),
            Vec2(61.875, 0.375),
            Vec2(-5.521648428788623, 0.375),
            Vec2(-5.521648428788623, -32.375),
        )

    /** Where the plan turns its **inside** corner — the reflex one the whole package is about. */
    private val inside = plan[4]

    private val height = 20.0

    // ---- the arithmetic, closed form ----

    private fun wedgeArea(
        size: Double,
        kind: BlendKind,
    ): Double = if (kind == BlendKind.CHAMFER) size * size / 2.0 else (1.0 - PI / 4.0) * size * size

    /** `∫₀^size δ(h)² dh` for the section's own inset — a round's rolling one, a bevel's straight one. */
    private fun moment(
        size: Double,
        kind: BlendKind,
    ): Double = if (kind == BlendKind.CHAMFER) size * size * size / 3.0 else size * size * size * (5.0 / 3.0 - PI / 2.0)

    /** How far the section's own centroid stands from the crease, along the shared face. */
    private fun centroidReach(
        size: Double,
        kind: BlendKind,
    ): Double = (moment(size, kind) / 2.0) / wedgeArea(size, kind)

    /** What a **crossing** of interior angle [rad] takes off the two bands' sum. */
    private fun crossingTakes(
        size: Double,
        kind: BlendKind,
        rad: Double,
    ): Double = moment(size, kind) / kotlin.math.tan(rad / 2.0)

    /** What a **pivot** of [rad] about an axis [rho] from the section's origin adds — Pappus, exactly. */
    private fun pivotTakes(
        size: Double,
        kind: BlendKind,
        rad: Double,
        rho: Double,
    ): Double = wedgeArea(size, kind) * rad * (rho + centroidReach(size, kind))

    /** The L's plan area and the prism's volume. */
    private fun planArea(): Double {
        var s = 0.0
        for (i in plan.indices) {
            val a = plan[i]
            val b = plan[(i + 1) % plan.size]
            s += a.x * b.y - b.x * a.y
        }
        return abs(s) / 2.0
    }

    private fun planPerimeter(): Double = plan.indices.sumOf { (plan[(it + 1) % plan.size] - plan[it]).length() }

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

    private fun refusalOf(ref: SolidRef): String? = (Evaluator().eval(ref.node) as? EvalResult.Invalid)?.reason

    private fun meshOf(ref: SolidRef): Mesh3 = Evaluator().solid(ref).mesh

    @Suppress("UNCHECKED_CAST")
    private fun refOf(el: Element): SolidRef = el.ref as SolidRef

    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerMove(s)
        pointerDown(s)
        pointerUp(s)
    }

    /**
     * The body **stage by stage**: the prism, then the body after each rounding of [script], in order.
     *
     * Since OP-30 a pure chain of roundings loads as **one** dressed body with an entry per rounding, so the
     * intermediate stages are no longer elements of one document — they are what the same script says when
     * it is cut short. Which is the honest reading of what these assertions are about: they compare the body
     * with *n* roundings against the body with *n + 1*, and index stability across that step is exactly the
     * property `Feature3.Blend`'s dressed list promises whether the roundings are entries or levels.
     */
    private fun solidsOf(script: String): List<SolidRef> {
        val lines = script.trim().lines()
        val cuts = lines.indices.filter { lines[it].startsWith("tool fillet") || lines[it].startsWith("tool chamfer") }
        return (listOf(cuts.first() - 1) + cuts).map { at -> bodyOf(lines.take(at + 1).joinToString("\n") + "\n") }
    }

    /** The last solid of [script] — the dressed body it ends with. */
    private fun bodyOf(script: String): SolidRef = refOf(DocumentFormat.load(script).elements.last { it.kind == ElementKind.SOLID })

    /** A prism over the polygon [xy], [h] deep — the same fixture shape [BlendVertexTest] builds. */
    private fun prism(
        cx: Construction,
        xy: List<Vec2>,
        h: Double,
    ): SolidRef {
        val pts = xy.mapIndexed { i, p -> cx.freePoint("p$i", p.x.mm, p.y.mm) }
        val segs = xy.indices.map { cx.segment(pts[it], pts[(it + 1) % xy.size]) }
        return cx.extrude(cx.sketchOn(cx.planeXY(), cx.region(cx.loop(*segs.toTypedArray()))), cx.const(h.mm))
    }

    /** One blend gesture, scored the way a live click scores it and then handed over verbatim. */
    private fun blendOn(
        cx: Construction,
        on: SolidRef,
        size: Double,
        kind: BlendKind,
        whole: Boolean,
        address: Int,
    ): SolidRef {
        val body = Evaluator().solid(on)
        val (targets, whyTargets) = Blend3.targets(body.feature, whole, address)
        assertNotNull(targets, whyTargets?.render())
        val (choices, why) = Blend3.choicesFor(body, targets, BlendSection(kind, size))
        assertNotNull(choices, why?.render())
        return cx.blend(on, on, cx.planeXY(), cx.const(size.mm), kind, whole, address, choices)
    }

    /** Which face of [ref] is the top cap — the one whose plane stands at `z = h` facing up. */
    private fun topFace(
        ref: SolidRef,
        h: Double,
    ): Int {
        val faces = assertNotNull(Section3.faces(Evaluator().solid(ref).feature).first, "the solid names its faces")
        val at =
            faces.indices.firstOrNull { i ->
                val p = faces[i].plane ?: return@firstOrNull false
                abs(p.normal.normalized().z - 1.0) < 1e-9 && abs(p.origin.z - h) < 1e-9
            }
        return assertNotNull(at, "a top face at z = $h among ${faces.map { it.name.label }}")
    }

    /** Which edge of [ref] is the upright standing at plan point [xy]. */
    private fun uprightAt(
        ref: SolidRef,
        xy: Vec2,
        z0: Double,
        z1: Double,
    ): Int {
        val edges = assertNotNull(Section3.edges(Evaluator().solid(ref).feature).first, "the solid names its edges")
        val at =
            edges.indices.firstOrNull { i ->
                val path = Blend3.edgePath(edges[i]).first ?: return@firstOrNull false
                val el = path.elements.singleOrNull() ?: return@firstOrNull false
                val ends = listOf(el.start, el.end).sortedBy { it.z }
                (ends[0] - Vec3(xy.x, xy.y, z0)).length() < 1e-6 && (ends[1] - Vec3(xy.x, xy.y, z1)).length() < 1e-6
            }
        return assertNotNull(at, "an upright at $xy")
    }

    // ---- 1. the reporter's file ----

    /**
     * **The report, end to end.** Three gestures: the six top edges rounded (five convex corners and the one
     * inside corner), then the **concave upright** at that inside corner, then the **convex upright** at the
     * far corner. All three bodies valid and manifold, all three volumes the closed-form figure, and the
     * fill no longer standing proud of the rounded top.
     */
    @Test
    fun theReportersThreeGesturesAreValidAndTheFigureSays() {
        val r = 5.0
        val w = wedgeArea(r, BlendKind.FILLET)
        val base = planArea() * height
        val solids = solidsOf(fixture)
        assertEquals(4, solids.size, "the extrusion and its three roundings")
        val (body, e14, e15, e16) = solids

        assertClose(volumeOf(body, "the L prism"), base, 1e-6, "the L is ${planArea()} mm^2, $height deep")

        // **e14, unchanged.** Five 90° crossings and one sharp-upright pivot, over the whole perimeter.
        val v14 = volumeOf(e14, "e14, the six top edges rounded")
        val exact14 =
            base - (planPerimeter() * w - 5 * crossingTakes(r, BlendKind.FILLET, PI / 2.0) + pivotTakes(r, BlendKind.FILLET, PI / 2.0, 0.0))
        assertClose(exact14, 39197.2947, 0.01, "the session-80 figure for the reporter's cap")
        assertTrue(v14 <= exact14 + 1e-6, "never above the exact rounded body: $v14 vs $exact14")
        assertTrue(v14 >= exact14 * 0.998, "and within the chords of it: $v14 vs $exact14")
        // …and it has not drifted by one part in a million, which is what says no existing drawing moved
        assertClose(v14, 39171.698256, 1e-3, "e14's own mesh is the mesh it always was")

        // **e15**: the fill added, and the top chain re-cut with the two bands set back by r_U and the pivot
        // turning on the ring torus of radius r + r_U about the fill's own axis.
        val v15 = volumeOf(e15, "e15, the concave upright rounded too")
        val exact15 =
            base + w * height -
                ((planPerimeter() - 2 * r) * w - 5 * crossingTakes(r, BlendKind.FILLET, PI / 2.0) + pivotTakes(r, BlendKind.FILLET, PI / 2.0, r))
        assertClose(exact15, 39316.1091, 0.01, "the ring-torus figure")
        assertTrue(v15 <= exact15 + 1e-6, "never above the exact body: $v15 vs $exact15")
        assertTrue(v15 >= exact15 * 0.998, "and within the chords of it: $v15 vs $exact15")

        // **e16**: the convex upright at the far corner, where three bands meet at the #32 ball.
        val v16 = volumeOf(e16, "e16, the convex upright rounded")
        val exact16 =
            exact15 - (
                w * height - (2.0 - 7.0 * PI / 12.0) * r * r * r +
                    crossingTakes(r, BlendKind.FILLET, PI / 2.0)
            )
        assertClose(exact16, 39217.7499, 0.01, "the ball figure, one corner along")
        assertTrue(v16 <= exact16 + 1e-6, "never above the exact body: $v16 vs $exact16")
        assertTrue(v16 >= exact16 * 0.998, "and within the chords of it: $v16 vs $exact16")

        // **the report itself**: the fill's tube used to run flat to z = 20 and stand out of the rounded top.
        // It now ends at the torus, so nothing of it is left in the corner quadrant above the band.
        for (ref in listOf(e15, e16)) {
            val stray =
                meshOf(ref).vertices.filter {
                    it.x > inside.x + 1e-6 && it.y < inside.y - 1e-6 && it.z > height - 0.1
                }
            assertTrue(stray.isEmpty(), "the fill ends at the torus, not at the top face: ${stray.take(3)}")
        }
        // …and there is still material where the fill belongs, below the band
        assertTrue(Geom3.encloses(meshOf(e15), Vec3(inside.x + 1.0, inside.y - 1.0, 5.0)), "the fill is there below")
        assertTrue(!Geom3.encloses(meshOf(e15), Vec3(inside.x + 1.0, inside.y - 1.0, height - 1.0)), "…and rounded away above")
    }

    // ---- 2. the two orders are one body ----

    /**
     * **Upright first, or face first — one body.** The corner is a fact about which bands meet where, not
     * about which gesture arrived last, so rounding the concave upright before the top face gives the very
     * same solid the reporter's order gives.
     */
    @Test
    fun theTwoOrdersBuildTheSameBody() {
        val r = 5.0
        val faceFirst =
            Construction().let { cx ->
                val body = prism(cx, plan, height)
                val top = topFace(body, height)
                val a = blendOn(cx, body, r, BlendKind.FILLET, whole = true, address = top)
                blendOn(cx, a, r, BlendKind.FILLET, whole = false, address = uprightAt(a, inside, 0.0, height))
            }
        val uprightFirst =
            Construction().let { cx ->
                val body = prism(cx, plan, height)
                val a = blendOn(cx, body, r, BlendKind.FILLET, whole = false, address = uprightAt(body, inside, 0.0, height))
                blendOn(cx, a, r, BlendKind.FILLET, whole = true, address = topFace(a, height))
            }
        val v1 = volumeOf(faceFirst, "the face rounded first")
        val v2 = volumeOf(uprightFirst, "the upright rounded first")
        assertClose(v2, v1, 1e-3, "the two orders are one body")

        // …and both dressed lists name a ring-torus corner, which is what says the pivot is the same one
        for ((what, ref) in listOf("face first" to faceFirst, "upright first" to uprightFirst)) {
            val faces = assertNotNull(Section3.faces(Evaluator().solid(ref).feature).first, "$what names its faces")
            val corners = faces.filter { it.name is FaceName.BlendCorner && it.surface?.band is constructit.geom.Revolve3.Band.Torus }
            val ring = corners.filter { (it.surface!!.band as constructit.geom.Revolve3.Band.Torus).rc > 1e-9 }
            assertTrue(ring.isNotEmpty(), "$what states the ring torus: ${faces.map { it.name.label }}")
            val torus = ring.first().surface!!.band as constructit.geom.Revolve3.Band.Torus
            assertClose(torus.rc, 2 * r, 1e-9, "$what: the ball's centre runs on a circle of radius r + r_U")
            assertClose(torus.minor, r, 1e-9, "$what: …with the ball's own radius as the tube")
        }
    }

    // ---- 3. the whole thing bevelled, exactly ----

    /**
     * **Everything planar, so everything exact.** A chamfer on the top face and a chamfer on the concave
     * upright: the pivot is then a turn about the bevel's first rail, a slide the length of the bevel, and a
     * turn about its second — two cones and one plane, and the figure has no chord in it anywhere.
     */
    @Test
    fun aBevelledCornerTurnsAboutABevelExactly() {
        val c = 5.0
        val cx = Construction()
        val body = prism(cx, plan, height)
        val top = topFace(body, height)
        val a = blendOn(cx, body, c, BlendKind.CHAMFER, whole = true, address = top)
        val out = blendOn(cx, a, c, BlendKind.CHAMFER, whole = false, address = uprightAt(a, inside, 0.0, height))
        val v = volumeOf(out, "the L bevelled on its cap and on its inside upright")

        val w = wedgeArea(c, BlendKind.CHAMFER)
        val bevel = c * sqrt(2.0)
        val straight = planPerimeter() - 2 * c + bevel
        val exact =
            planArea() * height + w * height -
                (
                    straight * w - 5 * crossingTakes(c, BlendKind.CHAMFER, PI / 2.0) +
                        2 * pivotTakes(c, BlendKind.CHAMFER, PI / 4.0, 0.0)
                )
        assertClose(exact, 37661.1653, 0.01, "the machinist's figure")
        // the general engine's own float32 noise is the only slack an all-planar figure gets
        assertClose(v, exact, abs(exact) * 1e-5, "three legs — a cone, a plane and a cone — exactly")
    }

    // ---- 4. the two mixed kinds ----

    /** **A bevelled cap turning about a rounded upright**: the bevel revolved about the fill's own axis. */
    @Test
    fun aBevelledCapTurnsAboutARoundedUpright() {
        val c = 5.0
        val r = 5.0
        val cx = Construction()
        val body = prism(cx, plan, height)
        val a = blendOn(cx, body, c, BlendKind.CHAMFER, whole = true, address = topFace(body, height))
        val out = blendOn(cx, a, r, BlendKind.FILLET, whole = false, address = uprightAt(a, inside, 0.0, height))
        val v = volumeOf(out, "a bevelled cap and a rounded inside upright")

        val exact =
            planArea() * height + wedgeArea(r, BlendKind.FILLET) * height -
                (
                    (planPerimeter() - 2 * r) * wedgeArea(c, BlendKind.CHAMFER) -
                        5 * crossingTakes(c, BlendKind.CHAMFER, PI / 2.0) +
                        pivotTakes(c, BlendKind.CHAMFER, PI / 2.0, r)
                )
        assertClose(exact, 37508.6798, 0.01, "the bevel swept round the fill's axis")
        assertTrue(abs(v - exact) <= exact * 0.002, "within the chords the fill's own arc reaches the engine as: $v vs $exact")
    }

    /** **A rounded cap turning about a bevelled upright**: the arc carried along the bevel, a horn torus at each rail. */
    @Test
    fun aRoundedCapTurnsAboutABevelledUpright() {
        val r = 5.0
        val c = 5.0
        val cx = Construction()
        val body = prism(cx, plan, height)
        val a = blendOn(cx, body, r, BlendKind.FILLET, whole = true, address = topFace(body, height))
        val out = blendOn(cx, a, c, BlendKind.CHAMFER, whole = false, address = uprightAt(a, inside, 0.0, height))
        val v = volumeOf(out, "a rounded cap and a bevelled inside upright")

        val w = wedgeArea(r, BlendKind.FILLET)
        val exact =
            planArea() * height + wedgeArea(c, BlendKind.CHAMFER) * height -
                (
                    (planPerimeter() - 2 * c) * w - 5 * crossingTakes(r, BlendKind.FILLET, PI / 2.0) +
                        c * sqrt(2.0) * w + 2 * pivotTakes(r, BlendKind.FILLET, PI / 4.0, 0.0)
                )
        assertClose(exact, 39463.0085, 0.01, "the arc slid along the bevel between two horn tori")
        assertTrue(abs(v - exact) <= exact * 0.002, "within the chords of it: $v vs $exact")

        // …and the corner's **straight** leg is cut by the very route a band along a straight edge is: a
        // level plane crosses its rulings, so it comes back drawn rather than refused
        val faces = assertNotNull(Section3.faces(Evaluator().solid(out).feature).first, "it names its faces")
        val legs = faces.filter { it.name is FaceName.BlendCorner }
        assertTrue(legs.size >= 3, "a bevelled upright turns the pair over three legs: ${legs.map { it.name.label }}")
        val sec = Section3.sectionOf(Evaluator().solid(out), constructit.geom.Plane3(Vec3(0.0, 0.0, 17.5), Vec3.X, Vec3.Y))
        val cut = sec.edges.filter { it.provenance.contains("rounded corner") }
        assertTrue(cut.any { it.curve != null || it.sampled != null }, "the corner is drawn in the section: ${cut.map { it.reason }}")
    }

    // ---- 7. the file, and the addresses ----

    /** **The file is a fixed point and no address moved.** */
    @Test
    fun theFileRoundTripsAndNoAddressMoves() {
        val once = DocumentFormat.save(DocumentFormat.load(fixture))
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "the whole drawing round-trips byte-equal")

        val solids = solidsOf(fixture)
        val e14 = Evaluator().solid(solids[1]).feature
        val e15 = Evaluator().solid(solids[2]).feature
        val f14 = assertNotNull(Section3.faces(e14).first, "e14 names its faces")
        val f15 = assertNotNull(Section3.faces(e15).first, "e15 names its faces")
        // **Every face of e14 that a drawing can address keeps its index in e15** — the base's own faces and
        // the bands, which are what a sketch space and a section record (OP-17). Since OP-30 the roundings of
        // one body are entries of **one** dressing, so a further rounding appends its band after the bands
        // that are there and pushes the **corner** patches — which stand after all the bands in the dressed
        // list — along by one. That is stated rather than asserted away: the cure is a slot per rounding kept
        // for the life of the dressing, which is a change to `Blend3`'s own layout (see DESIGN.md, OP-30).
        val addressable14 = f14.filter { it.name !is constructit.geom.FaceName.BlendCorner }
        assertEquals(
            addressable14.map { it.name },
            f15.take(addressable14.size).map { it.name },
            "every base face and every band of e14 keeps its index in e15",
        )
        assertTrue(
            f14.any { it.name is constructit.geom.FaceName.BlendCorner },
            "…and e14 does carry a corner patch, so the exception above is a real one and not a vacuous claim",
        )
        val g14 = assertNotNull(Section3.edges(e14).first, "e14 names its edges")
        val g15 = assertNotNull(Section3.edges(e15).first, "e15 names its edges")
        assertEquals(g14.map { it.name }, g15.take(g14.size).map { it.name }, "every edge of e14 keeps its index in e15")

        // **e14's own pivot is the horn torus** about the sharp upright — the corner the cap chain makes on
        // its own — and it is a real surface there
        val hornAt = f14.indexOfFirst { it.name is FaceName.BlendCorner }
        assertTrue(hornAt >= 0, "e14 states its pivot: ${f14.map { it.name.label }}")
        assertTrue(f14[hornAt].surface?.band is constructit.geom.Revolve3.Band.Torus, "…as a torus")
        // …and **e15 has one pivot, the ring torus, with nothing superseded**. Under a chain the horn was
        // built at the first level and then re-turned at the second, so it stayed in the list carrying its
        // reason. Since OP-30 both roundings are entries of one dressing evaluated in one pass, so the corner
        // is built once, about the band — there is no dead corner to keep a slot for (session 81's rule
        // getting what it wanted).
        val corners15 = f15.filter { it.name is FaceName.BlendCorner }
        assertEquals(1, corners15.size, "e15 states exactly one corner: ${f15.map { it.name.label }}")
        assertTrue(f15.none { it.reason?.contains("re-turned about") == true }, "…and nothing was re-turned after the fact")
        assertClose(
            ((corners15.first().surface!!.band) as constructit.geom.Revolve3.Band.Torus).rc,
            10.0,
            1e-9,
            "the ring torus about the fill's own axis",
        )
    }

    /** **Undo of the last gesture leaves the one before it standing** — the rebuilt chain and all. */
    @Test
    fun undoOfTheLastGestureLeavesTheOneBeforeIt() {
        val ed = Editor()
        // the reporter's drawing up to e15, then his own last gesture made here so there is a step to undo
        ed.replaceDocument(DocumentFormat.load(fixture.lines().dropLast(2).joinToString("\n") + "\n"))
        val before = ed.doc.elements.count { it.kind == ElementKind.DRESSING }
        val v15 = volumeOf(refOf(ed.doc.elements.last { it.kind == ElementKind.SOLID }), "e15")
        ed.activeScalar = ed.doc.scalars.first { it.name == "r" }
        ed.setTool(constructit.editor.Tools.BLEND_EDGE)
        // the reporter's own last gesture, made here so there is a step to undo: the convex upright at the
        // far corner, aimed at in the 3D view the way a pick on a vertical edge is made
        val cam = Camera3(target = Vec3(0.0, -8.0, 10.0), distance = 260.0, yaw = 2.4, pitch = 0.2)
        val vp = Viewport3(camera = cam, widthPx = 800.0, heightPx = 600.0)
        vp.editor = ed
        vp.shown = true
        val screen = assertNotNull(cam.project(Vec3(plan[0].x, plan[0].y, 10.0), vp.widthPx, vp.heightPx), "the upright has an image")
        vp.pointerDown(screen)
        vp.pointerUp(screen)
        val solids = ed.doc.elements.filter { it.kind == ElementKind.SOLID }
        assertEquals(before + 1, ed.doc.elements.count { it.kind == ElementKind.DRESSING }, "the convex upright was rounded: ${ed.statusHint}")
        assertEquals(null, refusalOf(refOf(solids.last())), "…and it is a body")
        assertTrue(ed.undo(), "the gesture is one undo step")
        val back = ed.doc.elements.filter { it.kind == ElementKind.SOLID }
        assertEquals(before, ed.doc.elements.count { it.kind == ElementKind.DRESSING }, "one rounding fewer")
        assertClose(volumeOf(refOf(back.last()), "e15 after the undo"), v15, 1e-9, "and e15 is exactly what it was")
    }

    // ---- 8. refusals speak ----

    // ---- 5. the other sector: one convex band between two concave ones ----

    /**
     * **A room's own corner, from the inside**: an L-shaped box hollowed out, and the three creases at the
     * cavity's *reflex* plan corner — two concave ones where the floor meets the walls, and the **convex**
     * upright between them. The ball is on the other side of the material there, so the two roundings are
     * fills and the upright is a cut, and the rebuild is `(root − U) ∪ fills'` where the reporter's was
     * `(root ∪ U) − chain'`. Both orders, one body, and the pivot is the torus of radius `r + r_U`.
     */
    @Test
    fun theOtherSectorPivotsTheSameWay() {
        val r = 3.0
        val box = listOf(Vec2(0.0, 0.0), Vec2(60.0, 0.0), Vec2(60.0, 20.0), Vec2(25.0, 20.0), Vec2(25.0, 50.0), Vec2(0.0, 50.0))
        val deep = 30.0
        val wall = 6.0
        val floor = Vec2(19.0, 14.0)

        fun hollow(cx: Construction): SolidRef {
            val body = prism(cx, box, deep)
            return cx.shell(body, cx.const(wall.mm), listOf(topFace(body, deep)))
        }

        // the two fills first, then the upright — the rebuild path, where the pair's group is stale
        val fillsFirst =
            Construction().let { cx ->
                var on = hollow(cx)
                for (other in listOf(Vec2(54.0, 14.0), Vec2(19.0, 44.0))) {
                    on = blendOn(cx, on, r, BlendKind.FILLET, whole = false, address = straightEdge(on, floor, other, wall, wall))
                }
                blendOn(cx, on, r, BlendKind.FILLET, whole = false, address = straightEdge(on, floor, floor, wall, deep))
            }
        // …and the upright first, which is the ordinary path with the fills fresh against an existing band
        val uprightFirst =
            Construction().let { cx ->
                var on = hollow(cx)
                on = blendOn(cx, on, r, BlendKind.FILLET, whole = false, address = straightEdge(on, floor, floor, wall, deep))
                for (other in listOf(Vec2(54.0, 14.0), Vec2(19.0, 44.0))) {
                    on = blendOn(cx, on, r, BlendKind.FILLET, whole = false, address = straightEdge(on, floor, other, wall, wall))
                }
                on
            }
        val v1 = volumeOf(fillsFirst, "the cavity's fills, then its upright")
        val v2 = volumeOf(uprightFirst, "the cavity's upright, then its fills")
        assertClose(v2, v1, abs(v1) * 1e-5, "one body whichever order the three gestures came in")

        // …and the corner really is the ball pivoting on a circle of radius r + r_U about the upright's axis
        for ((what, ref) in listOf("fills first" to fillsFirst, "upright first" to uprightFirst)) {
            val faces = assertNotNull(Section3.faces(Evaluator().solid(ref).feature).first, "$what names its faces")
            val ring =
                faces.filter { it.name is FaceName.BlendCorner }
                    .mapNotNull { it.surface?.band as? constructit.geom.Revolve3.Band.Torus }
                    .filter { abs(it.rc - 2 * r) < 1e-9 }
            assertTrue(ring.isNotEmpty(), "$what states the ring torus: ${faces.map { it.name.label }}")
            assertClose(ring.first().minor, r, 1e-9, "$what: the ball's own radius is the tube")
        }
    }

    // ---- 6. the section through the pivot, exactly ----

    /**
     * **A working plane through the ring torus is answered exactly.** At `z = 17.5` the ball's tube stands
     * `√(r² − 2.5²)` off its centre circle, so the body's own surface there is the circle of radius
     * `2r − √(r² − 2.5²)` about the fill's axis — and that is what the section draws, as a real arc with no
     * chord in it, not as a refusal.
     */
    @Test
    fun theSectionThroughThePivotIsExact() {
        val r = 5.0
        val e15 = solidsOf(fixture)[2]
        val solid = Evaluator().solid(e15)
        val at = 17.5
        val sec = Section3.sectionOf(solid, constructit.geom.Plane3(Vec3(0.0, 0.0, at), Vec3.X, Vec3.Y))
        val corners = sec.edges.filter { it.provenance.contains("rounded corner") }
        // **One pivot, and it draws.** Under a chain the sharp-upright corner was built first and then
        // *superseded* by the ring one, and the section carried both — the dead one with its reason. Since
        // OP-30 the two roundings of this body are entries of one dressing evaluated in one pass, so the
        // corner is built once, about the band, and there is no superseded one to address at all. That is
        // the pivot-about-a-band rule getting what it always wanted (session 81).
        assertTrue(corners.all { it.curve != null }, "no superseded pivot: ${corners.map { it.provenance to it.reason }}")
        val drawn = assertNotNull(corners.firstOrNull { it.curve != null }, "the pivot is in the section: ${corners.map { it.provenance }}")
        val arc = assertNotNull(drawn.curve as? constructit.geom.ProfileElement.ArcE, "…drawn as a curve, not chords: ${drawn.reason}")
        assertTrue(!drawn.approximated, "…and nothing about it is sampled")
        val rho = 2 * r - sqrt(r * r - (height - at) * (height - at))
        assertClose(rho, 5.6698729810778, 1e-9, "the tube stands that far in at z = $at")
        assertClose(arc.arc.radius, rho, 1e-9, "the exact circle a level plane cuts the ring torus in")
        val axis = Vec2(inside.x + r, inside.y - r)
        assertClose((arc.arc.center - axis).length(), 0.0, 1e-6, "centred on the fill's own axis")
        // …and the point on the sector's own bisector is on that outline, to the micron
        val on = axis + Vec2(-1.0, 1.0).normalized() * rho
        assertClose((constructit.geom.GeomMath.arcPointAt(arc.arc, (on - arc.arc.center).angle()) - on).length(), 0.0, 1e-6, "the outline runs through it")

        // …and *Section* hands the whole level back as an ordinary 2D area whose outline runs through it
        val doc = DocumentFormat.load(fixture)
        val el = DocumentFormat.load(fixture.trim().lines().dropLast(1).joinToString("\n") + "\n").elements.last { it.kind == ElementKind.SOLID }
        val cut = doc.newParameter("cut", at.mm)
        val area = assertNotNull(doc.sectionSolid(el, cut.ref), "Section makes an area of it: ${doc.note}")
        assertEquals(ElementKind.AREA, area.kind, "…an ordinary 2D area")
        val region =
            assertNotNull(
                ((Evaluator().eval(area.ref.node) as? EvalResult.Ok)?.value as? constructit.core.RegionValue)?.region,
                "…with a value: ${(Evaluator().eval(area.ref.node) as? EvalResult.Invalid)?.reason}",
            )
        val outline = region.outer.elements.flatMap { piece -> constructit.geom.GeomMath.tessellatePiece(piece, 1e-4) }
        assertTrue(outline.minOf { q -> (q - on).length() } <= 1e-4, "the area's own outline runs through $on")
    }

    // ---- 8. refusals speak ----

    /**
     * **A rounding that re-turns an earlier corner needs the chain it stands on, and says so when there is
     * none.** After an ordinary boolean the body being cut is not the body being addressed, so there is no
     * undressed chain to rebuild — and rebuilding the analytic one would throw the fusion away. The refusal
     * names the edge and says what to do instead.
     */
    @Test
    fun aReTurnedCornerWithNoChainUnderItSaysSo() {
        val r = 5.0
        val cx = Construction()
        val body = prism(cx, plan, height)
        val rounded = blendOn(cx, body, r, BlendKind.FILLET, whole = true, address = topFace(body, height))
        val lug = prism(cx, listOf(Vec2(20.0, 4.0), Vec2(40.0, 4.0), Vec2(40.0, 12.0), Vec2(20.0, 12.0)), height + 8.0)
        val fused = cx.union(rounded, lug)
        assertEquals(null, refusalOf(fused), "the fusion is a body")
        val addr = uprightAt(rounded, inside, 0.0, height)
        val on = Evaluator().solid(rounded)
        val (targets, whyT) = Blend3.targets(on.feature, false, addr)
        assertNotNull(targets, whyT?.render())
        val (choices, whyC) = Blend3.choicesFor(on, targets, BlendSection(BlendKind.FILLET, r))
        assertNotNull(choices, whyC?.render())
        val out = cx.blend(fused, rounded, cx.planeXY(), cx.const(r.mm), BlendKind.FILLET, false, addr, choices)
        val why = assertNotNull(refusalOf(out), "the re-turn is refused when there is no chain to rebuild")
        assertTrue(why.contains("re-turns a corner an earlier rounding made"), "…by name: $why")
        assertTrue(why.contains("before the fusion"), "…and with the cure: $why")
    }

    /** Which edge of [ref] is the straight run between the two plan points, at the two given heights. */
    private fun straightEdge(
        ref: SolidRef,
        a: Vec2,
        b: Vec2,
        za: Double = height,
        zb: Double = height,
    ): Int {
        val edges = assertNotNull(Section3.edges(Evaluator().solid(ref).feature).first, "the solid names its edges")
        val want = listOf(Vec3(a.x, a.y, za), Vec3(b.x, b.y, zb))
        val at =
            edges.indices.firstOrNull { i ->
                if (edges[i].reason != null) return@firstOrNull false
                val el = Blend3.edgePath(edges[i]).first?.elements?.singleOrNull() ?: return@firstOrNull false
                val ends = listOf(el.start, el.end)
                want.all { q -> ends.any { (it - q).length() < 1e-6 } } && (ends[0] - ends[1]).length() > 1e-6
            }
        return assertNotNull(at, "an edge from $a at $za to $b at $zb")
    }
}
