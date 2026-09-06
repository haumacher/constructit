package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.SolidValue
import constructit.dsl.Construction
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.dsl.valueOf
import constructit.editor.Camera3
import constructit.editor.Document
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.Viewport3
import constructit.geom.Blend3
import constructit.geom.BlendKind
import constructit.geom.BlendSection
import constructit.geom.Feature3
import constructit.geom.Geom3
import constructit.geom.GeomMath
import constructit.geom.Mesh3
import constructit.geom.Plane3
import constructit.geom.ProfileElement
import constructit.geom.Revolve3
import constructit.geom.Section3
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.units.mm
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **The mixed-sign pair — a fill that meets a band's end** (OP-31, item (b); GitHub #36's first script).
 *
 * *What was wrong.* [Blend3.cornersOf] skipped a pair whose two members differ in **sign**: session 79's
 * default, and right for a pair that overlaps, since the boolean trims it exactly. This pair does not
 * overlap. The band has **rounded away** the very face the fill is tangent to over the last `r_U` of the
 * fill's run, so above that the fill stands on nothing — a vertical ledge beside the band — and the body
 * came out at exactly the naive figure, band over the whole edge and fill over the whole upright, with no
 * corner and nothing refused. That is OP-31's *"a pair the catalogue does not know and does not refuse"*.
 *
 * *What is built.* **The concave piece travels and the convex one is pivoted about**, and which is which is
 * derived rather than chosen: a fill's ball lives in the air, so where its face has become the band's convex
 * surface it rolls round the **outside** of it — external tangency, its centre on the circle of radius
 * `r + r_U` about the band's own axis, which exists for every pair of sizes. A band's ball lives in the
 * material and would have to be tangent to a fill's concave surface from *inside*, which is the air the fill
 * was put there to keep; so the band's spine simply ends and the fill's carries on.
 *
 * The walk itself is session 81's [Blend3] pivot **one end short**: the fill's section follows the band's own
 * end-section curve piece by piece, turning about the axis square to the face the fill shares with nothing
 * else — and where a two-ended pivot lands on a second band's end section, this one runs to the end of the
 * band's curve and is **capped by the third face** at the vertex, every ring clipped to that plane.
 *
 * *The figure, in closed form.* With the three faces square to one another, `r_U` the band's size and the
 * fill's own section standing at reach `x` with height `h(x)`, the corner about a **round** band adds
 *
 * ```
 * V = ∫_{r_U}^{r_U+r} ρ · arcsin(r_U / ρ) · h(ρ − r_U) dρ
 * ```
 *
 * — the section's own first moment about the band's axis, each ring counted only over the turn it stands
 * below the third face for, which is `arcsin(r_U/ρ)` and nothing else. About a **bevelled** band the walk is
 * a quarter-turn, a slide and a quarter-turn that the cap takes away entirely, so it adds
 * `(π/4)·∫₀^r x·h(x) dx + ∫₀^{c_U√2} A(K) dK` with `A(K)` the section's area out to reach `K`. Everything
 * with an arc in it is bracketed by the chords the arc reaches the engine as; the bevelled pair's own
 * quarter-turns are chorded too, so it is bracketed as well and the bracket is stated rather than assumed.
 */
class BlendMixedPairTest {
    // ---- the reporter's own file, verbatim (GitHub #36, script 1) ----

    private val fixture =
        """
        constructit 6
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
        param "r" = 4mm
        tool filletedge els=e13 clicks=-31.252365457721638,11.31587462139538 scalar="r" signs=13;-1;1;0;1 -> e14,e15
        tool filletedge els=e14 clicks=-15.659687663642714,14.554138077719443 scalar="r" signs=2;-1;1;0;-1 -> e16
        """.trimIndent().lines().joinToString("\n") { it.trim() } + "\n"

    /** The L's own plan — the reporter's six corners in his own order. */
    private val plan =
        listOf(
            Vec2(-26.875, -32.375),
            Vec2(-26.875, 15.375),
            Vec2(61.875, 15.375),
            Vec2(61.875, 0.375),
            Vec2(-5.521648428788623, 0.375),
            Vec2(-5.521648428788623, -32.375),
        )

    private val height = 20.0

    /** The concave upright at the L's reflex corner, and the two top edges that end at it. */
    private val fillEdge = 2
    private val topAlongX = 13
    private val topAlongY = 14

    // ---- the arithmetic, closed form ----

    private fun wedgeArea(
        size: Double,
        kind: BlendKind,
    ): Double = if (kind == BlendKind.CHAMFER) size * size / 2.0 else (1.0 - PI / 4.0) * size * size

    /** How high the section stands at reach [x] from the crease along the face it is measured on. */
    private fun heightAt(
        size: Double,
        kind: BlendKind,
        x: Double,
    ): Double = if (kind == BlendKind.CHAMFER) size - x else size - sqrt((size * size - (x - size) * (x - size)).coerceAtLeast(0.0))

    /** Simpson over [a, b] — deterministic, and enough for a closed-form integrand stated to six figures. */
    private fun integral(
        a: Double,
        b: Double,
        n: Int = 2000,
        f: (Double) -> Double,
    ): Double {
        val h = (b - a) / n
        var s = f(a) + f(b)
        for (i in 1 until n) s += f(a + h * i) * (if (i % 2 == 0) 2.0 else 4.0)
        return s * h / 3.0
    }

    /** What the corner adds where the band is **round**: the section's first moment, turn by turn. */
    private fun pivotAboutRound(
        rU: Double,
        size: Double,
        kind: BlendKind,
    ): Double = integral(rU, rU + size) { rho -> rho * asin(min(1.0, rU / rho)) * heightAt(size, kind, rho - rU) }

    /** …and where it is **bevelled**: a quarter-turn about the first rail, then the slide up the bevel. */
    private fun pivotAboutBevel(
        cU: Double,
        size: Double,
        kind: BlendKind,
    ): Double {
        val moment = integral(0.0, size) { x -> x * heightAt(size, kind, x) }
        val area = { k: Double -> if (k <= 0.0) 0.0 else integral(0.0, min(k, size)) { x -> heightAt(size, kind, x) } }
        return (PI / 4.0) * moment + integral(0.0, cU * sqrt(2.0)) { k -> area(k) }
    }

    /** How much larger a **round** wedge is when its arc reaches the engine as inscribed chords. */
    private fun chordExcess(size: Double): Double {
        val n = GeomMath.chordSteps(size, PI / 2.0, GeomMath.TESS_TOL_MM)
        val th = (PI / 2.0) / n
        return n * (size * size / 2.0) * (th - sin(th))
    }

    private fun planArea(): Double {
        var s = 0.0
        for (i in plan.indices) {
            val a = plan[i]
            val b = plan[(i + 1) % plan.size]
            s += a.x * b.y - b.x * a.y
        }
        return abs(s) / 2.0
    }

    /**
     * The closed-form volume of the L with one **band** on [bandEdge] and the **fill** on the reflex upright,
     * and the two-sided bracket the chords put round it.
     *
     * *Never below* the exact figure by more than what the band's own inscribed arc takes extra off the whole
     * of its run, *never above* it by more than what the fill's inscribed arc adds extra over its own, and
     * either way within a twentieth of the corner itself, whose turn is chorded like every arc here.
     */
    private fun figure(
        bandEdge: Int,
        bandSize: Double,
        bandKind: BlendKind,
        fillSize: Double,
        fillKind: BlendKind,
    ): Triple<Double, Double, Double> {
        val runs = mapOf(topAlongX to (0.375 - -32.375), topAlongY to (61.875 - -5.521648428788623))
        val length = assertNotNull(runs[bandEdge], "a band on edge $bandEdge")
        val wBand = wedgeArea(bandSize, bandKind)
        val wFill = wedgeArea(fillSize, fillKind)
        val corner =
            if (bandKind == BlendKind.CHAMFER) pivotAboutBevel(bandSize, fillSize, fillKind) else pivotAboutRound(bandSize, fillSize, fillKind)
        val exact = planArea() * height - wBand * length + wFill * (height - bandSize) + corner
        val slack = 0.05 * corner
        val lo = exact - (if (bandKind == BlendKind.CHAMFER) 0.0 else chordExcess(bandSize)) * length - slack
        val hi = exact + (if (fillKind == BlendKind.CHAMFER) 0.0 else chordExcess(fillSize)) * (height - bandSize) + slack
        return Triple(exact, lo, hi)
    }

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

    private fun bodyOf(doc: Document): Element = doc.elements.last { it.kind == ElementKind.SOLID }

    private fun featureOf(ref: SolidRef): Feature3 = (Evaluator().valueOf(ref) as SolidValue).solid.feature

    private fun prism(cx: Construction): SolidRef {
        val pts = plan.mapIndexed { i, p -> cx.freePoint("p$i", p.x.mm, p.y.mm) }
        val segs = plan.indices.map { cx.segment(pts[it], pts[(it + 1) % plan.size]) }
        return cx.extrude(cx.sketchOn(cx.planeXY(), cx.region(cx.loop(*segs.toTypedArray()))), cx.const(height.mm))
    }

    /** One rounding gesture, scored the way a live click scores it and then handed over verbatim. */
    private fun blendOn(
        cx: Construction,
        on: SolidRef,
        size: Double,
        kind: BlendKind,
        address: Int,
    ): SolidRef {
        val body = Evaluator().solid(on)
        val (targets, whyTargets) = Blend3.targets(body.feature, false, address)
        assertNotNull(targets, whyTargets?.render())
        val (choices, why) = Blend3.choicesFor(body, targets, BlendSection(kind, size))
        assertNotNull(choices, why?.render())
        return cx.blend(on, on, cx.planeXY(), cx.const(size.mm), kind, false, address, choices)
    }

    /** The L with [gestures] applied in order, each on the body the one before it made. */
    private fun chain(vararg gestures: Triple<Int, Double, BlendKind>): SolidRef {
        val cx = Construction()
        var body = prism(cx)
        for ((a, s, k) in gestures) body = blendOn(cx, body, s, k, a)
        return body
    }

    /** The same drawing written as a **script**, so it loads as one dressing of two entries (OP-30). */
    private fun script(
        first: Triple<Int, Double, BlendKind>,
        second: Triple<Int, Double, BlendKind>,
        chained: Boolean,
    ): String {
        val sb = StringBuilder("constructit 6\n")
        sb.append("orthostart ${plan[0].x},${plan[0].y} -> e1\n")
        var n = 2
        for (i in 1 until plan.size) {
            sb.append("orthovertex ${plan[i].x},${plan[i].y} -> e$n,e${n + 1}\n")
            n += 2
        }
        sb.append("orthoclose -> e$n\n")
        n++
        sb.append("param \"h\" = 20mm\n")
        sb.append("tool extrude els=e${n - 2} clicks=-48.125,37.875 scalar=\"h\" -> e$n\n")
        // the first rounding of a plain body declares **two** elements — the dressed body and its own entry
        // — and every one after it declares only the entry it adds (OP-30)
        var next = n + 1
        val tool = { k: BlendKind -> if (k == BlendKind.CHAMFER) "chamferedge" else "filletedge" }
        val sign = { e: Int -> if (e == fillEdge) -1 else 1 }
        sb.append("param \"r0\" = ${first.second}mm\n")
        sb.append("tool ${tool(first.third)} els=e$n clicks=0,0 scalar=\"r0\" signs=${first.first};-1;1;0;${sign(first.first)} -> e$next,e${next + 1}\n")
        val body = "e$next"
        next += 2
        sb.append("param \"r1\" = ${second.second}mm\n")
        sb.append("tool ${tool(second.third)} els=$body clicks=0,0 scalar=\"r1\" signs=${second.first};-1;1;0;${sign(second.first)} -> e$next\n")
        if (chained) sb.append("show els=$body\n")
        return sb.toString()
    }

    private fun bodyOfScript(s: String): SolidRef = refOf(bodyOf(DocumentFormat.load(s)))

    // ---- 1. the reporter's own script ----

    /**
     * **GitHub #36, script 1, end to end.** A top edge rounded, then the concave upright it ends at: the
     * corner is built, the body is watertight, its volume is the closed-form figure, and it is **not** the
     * naive one the build used to give.
     */
    @Test
    fun theReportersScriptBuildsTheCornerAndNotTheLedge() {
        val body = bodyOfScript(fixture)
        val v = volumeOf(body, "the reporter's two roundings")
        val (exact, lo, hi) = figure(topAlongX, 4.0, BlendKind.FILLET, 4.0, BlendKind.FILLET)
        assertClose(exact, 40570.6791, 0.01, "the one-ended pivot's own figure")
        assertTrue(v in lo..hi, "inside the chords' own bracket: $v not in [$lo, $hi]")
        // …and the naive body — band over the whole edge, fill over the whole upright, no corner — is not it
        val naive = 40566.65846687463
        assertTrue(naive < lo, "the bracket tells the two apart: the naive figure $naive is below $lo")
        assertTrue(v - naive > 2.0, "and the body built is strictly the other one: $v")
        assertClose(v, 40569.752961, 1e-3, "…and it has not drifted")

        // the ledge itself: above the band's tangency the fill used to stand in the wall planes it was
        // tangent to. It now turns onto the band, so nothing of it is left standing in the old quadrant.
        // a point in the fill's own wedge close under the crease: material all the way up in the old body,
        // and in the new one only as far as the band leaves the face the fill was tangent to
        val mesh = meshOf(body)
        assertTrue(Geom3.encloses(mesh, Vec3(-5.5, -0.5, 5.0)), "the fill is there below the band")
        assertTrue(!Geom3.encloses(mesh, Vec3(-5.5, -0.5, 19.9)), "…and the ledge above it is gone")

        val once = DocumentFormat.save(DocumentFormat.load(fixture))
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "the whole drawing round-trips byte-equal")
    }

    // ---- 2. the orders, and the one dressing ----

    /**
     * **Band first, upright first, or both in one dressing — one body.** The corner is a fact about which
     * roundings meet where, so the order the gestures arrive in cannot move it; and the pivot rebuilds the
     * chain from its own undressed root, which is what makes the upright-first order right rather than lucky.
     */
    @Test
    fun theTwoOrdersAndTheOneDressingAreOneBody() {
        val band = Triple(topAlongX, 4.0, BlendKind.FILLET)
        val fill = Triple(fillEdge, 4.0, BlendKind.FILLET)
        val a = volumeOf(chain(band, fill), "band then fill")
        val b = volumeOf(chain(fill, band), "fill then band")
        val one = volumeOf(bodyOfScript(script(band, fill, chained = false)), "one dressing of two entries")
        val two = volumeOf(bodyOfScript(script(band, fill, chained = true)), "the same two as a chain")
        for ((what, v) in listOf("the other order" to b, "one dressing" to one, "the chain" to two)) {
            assertClose(v, a, abs(a) * 1e-6, "$what is the same body: $v vs $a")
        }
    }

    // ---- 3. the pair the other way round ----

    /**
     * **The symmetric pair.** The reflex upright ends at *two* top edges; rounding the other one puts the
     * same construction one face over, with the walk running in the other of the fill's two faces.
     */
    @Test
    fun theSymmetricPairIsTheSameConstruction() {
        val band = Triple(topAlongY, 4.0, BlendKind.FILLET)
        val fill = Triple(fillEdge, 4.0, BlendKind.FILLET)
        val v = volumeOf(chain(band, fill), "the other top edge and the upright")
        val (exact, lo, hi) = figure(topAlongY, 4.0, BlendKind.FILLET, 4.0, BlendKind.FILLET)
        assertClose(exact, 40451.7247, 0.01, "the same figure over the other run")
        assertTrue(v in lo..hi, "inside the chords' bracket: $v not in [$lo, $hi]")
        assertClose(volumeOf(chain(fill, band), "the other order"), v, abs(v) * 1e-6, "and the two orders are one body")
    }

    // ---- 4. the bevelled twins, and the two mixed kinds ----

    /**
     * **The bevelled pair, and each kind against the other.** A bevelled band leaves the section sliding
     * along its own plane, so the ball still pivots about the bevel's far rail before the cap takes it —
     * which is the very last leg a two-ended walk takes onto its partner, taken here onto the plane instead.
     * Nothing about the pair asks the two sizes or the two kinds to agree.
     */
    @Test
    fun theBevelledPairAndTheMixedKindsAreTheSameWalk() {
        val cases =
            listOf(
                Triple(BlendKind.CHAMFER, BlendKind.CHAMFER, 40520.4110),
                Triple(BlendKind.FILLET, BlendKind.CHAMFER, 40664.5070),
                Triple(BlendKind.CHAMFER, BlendKind.FILLET, 40423.1485),
            )
        for ((bandKind, fillKind, want) in cases) {
            val v = volumeOf(chain(Triple(topAlongX, 4.0, bandKind), Triple(fillEdge, 4.0, fillKind)), "$bandKind band, $fillKind fill")
            val (exact, lo, hi) = figure(topAlongX, 4.0, bandKind, 4.0, fillKind)
            assertClose(exact, want, 0.01, "the figure for a $bandKind band and a $fillKind fill")
            assertTrue(v in lo..hi, "$bandKind/$fillKind inside its bracket: $v not in [$lo, $hi]")
        }
    }

    // ---- 5. two sizes ----

    /**
     * **Unlike sizes turn on their own circle, and are built rather than refused.** The ball's centre runs on
     * the circle of radius `r + r_U` about the band's axis, which exists for *every* pair of sizes — there is
     * no ring for the two to share here and so nothing to be congruent about. The surface says so: the corner
     * is the ring torus of centre radius `r + r_U` and tube `r`.
     */
    @Test
    fun unlikeSizesTurnOnTheirOwnCircle() {
        for ((rU, r, want) in listOf(Triple(3.0, 4.0, 40618.8556), Triple(4.0, 3.0, 40539.5216))) {
            val body = chain(Triple(topAlongX, rU, BlendKind.FILLET), Triple(fillEdge, r, BlendKind.FILLET))
            val v = volumeOf(body, "a band of $rU and a fill of $r")
            val (exact, lo, hi) = figure(topAlongX, rU, BlendKind.FILLET, r, BlendKind.FILLET)
            assertClose(exact, want, 0.01, "the figure for $rU and $r")
            assertTrue(v in lo..hi, "$rU/$r inside its bracket: $v not in [$lo, $hi]")

            val faces = assertNotNull(Section3.faces(featureOf(body)).first, "the dressed faces")
            val torus =
                assertNotNull(
                    faces.mapNotNull { it.surface?.band as? Revolve3.Band.Torus }.firstOrNull(),
                    "the corner names a torus among ${faces.map { it.name.label.render() }}",
                )
            assertClose(torus.rc, r + rU, 1e-9, "its centre circle is r + r_U")
            assertClose(torus.minor, r, 1e-9, "…and its tube is the fill's own size")
        }
    }

    // ---- 6. what the drawing says ----

    /**
     * **A level section through the corner closes into one area, and the top face says where it now reaches.**
     *
     * Two corrections make it so, and both are the corner's own. The face the walk runs in loses the corner
     * of its boundary to the section's **tangency curve** — an exact arc of the plane, since the tangency,
     * the pivot and the axis all stand in it — and the third face **gains** the flat top the cap leaves on
     * it, whose boundary is a torus met by a plane parallel to its own axis: a quartic with no name here, and
     * so a fitted cubic chain, which is OP-31's Tier B decision (*"an approximation is better than nothing at
     * all"*) taken at the one place this item needs it.
     */
    @Test
    fun theLevelSectionClosesAndTheTopFaceSaysWhereItReaches() {
        val body = bodyOfScript(fixture)
        val solid = Evaluator().solid(body)
        for (z in listOf(19.9, 18.0, 17.0, 10.0)) {
            val plane = Plane3(Vec3(0.0, 0.0, z), Vec3(1.0, 0.0, 0.0), Vec3(0.0, 1.0, 0.0))
            val (regions, why) = Section3.regionsOf(solid.feature, plane)
            assertNotNull(regions, "the level at $z closes: ${why?.render()}")
            assertEquals(1, regions.size, "…into one area at $z")
        }
        // the corner's cut is the drawing's approximated class — exact at every point of it, chords between
        val plane = Plane3(Vec3(0.0, 0.0, 19.9), Vec3(1.0, 0.0, 0.0), Vec3(0.0, 1.0, 0.0))
        val section = Section3.sectionOf(solid, plane)
        val faces = assertNotNull(Section3.faces(solid.feature).first, "the dressed faces")
        val corner = faces.indexOfFirst { it.surface?.band is Revolve3.Band.Torus }
        assertTrue(corner >= 0, "the corner is a face")
        assertTrue(section.edges[corner].approximated, "and its cut says it is chords: ${section.edges[corner].reason?.render()}")

        // …and the top face's own boundary reaches past the corner the band left it, on a fitted chain
        val top = assertNotNull(faces.firstOrNull { abs((it.plane?.origin?.z ?: 0.0) - height) < 1e-9 }, "the top face")
        assertTrue(top.outline.any { it is ProfileElement.BezierE }, "its boundary carries the cap's fitted curve")
        val cap = top.outline.filterIsInstance<ProfileElement.BezierE>()
        val ends = cap.map { GeomMath.startOf(it) } + GeomMath.endOf(cap.last())
        // the two ends of the chain are exact: the band's own rail on this face, and the fill's tangency
        assertClose(ends.minOf { it.x }, -9.521648428788623, 1e-6, "the chain begins on the band's rail")
        assertClose(ends.minOf { it.y }, -3.625, 1e-6, "…at the fill's own tangency depth")
        assertClose(ends.maxOf { it.y }, 0.375, 1e-6, "and ends on the top edge the fill runs out into")
        assertClose(ends.maxOf { it.x }, -9.521648428788623 + (4.0 + 4.0) * cos(PI / 6.0), 1e-3, "…at the turn the third face caps")
    }

    // ---- 7. the incongruent inside corner, refused by name ----

    /**
     * **Two roundings that are not congruent make no inside corner, and now say so** (OP-31's matrix).
     *
     * At a **convex** corner a non-congruent pair costs nothing: the two tools overlap and the boolean trims
     * them exactly, which is session 79's own cut and stays. At an **inside** corner they never overlap at
     * all, so leaving the pair alone leaves GitHub #31's spike standing between the two band ends — silently,
     * whenever the two sizes or the two kinds differ. It is named here instead, and it heals.
     */
    @Test
    fun theIncongruentInsideCornerIsRefusedByNameAndHeals() {
        val cases =
            listOf(
                Triple(4.0, BlendKind.FILLET, 3.0) to BlendKind.FILLET,
                Triple(4.0, BlendKind.FILLET, 4.0) to BlendKind.CHAMFER,
            )
        for ((first, secondKind) in cases) {
            val why =
                assertNotNull(
                    refusalOf(chain(Triple(topAlongX, first.first, first.second), Triple(topAlongY, first.third, secondKind))),
                    "a ${first.second} of ${first.first} beside a $secondKind of ${first.third} at the inside corner",
                )
            assertTrue("inside corner" in why, "the refusal names the corner: $why")
            assertTrue("boundary edge #3 of the top face" in why && "boundary edge #2 of the top face" in why, "…and both edges: $why")
            assertTrue("the top face" in why, "…and the face they meet on: $why")
            assertTrue("same rounding" in why || "sharp" in why, "…and what to do instead: $why")
        }
        // …and the very same pair with one size and one kind builds, which is what says the refusal is the
        // congruence and not the corner
        val ok = chain(Triple(topAlongX, 4.0, BlendKind.FILLET), Triple(topAlongY, 4.0, BlendKind.FILLET))
        assertNull(refusalOf(ok), "the congruent pair is a body")
        assertTrue(volumeOf(ok, "the congruent inside corner") > 0.0)
    }

    // ---- 8. the entry is a row like any other ----

    /**
     * **The second gesture undone leaves the first exactly as it was**, corner, rebuild and all.
     *
     * The pivot rebuilds the whole chain from its own undressed root, so the body the fill makes is not the
     * body the band made with something added — which is precisely why one undo has to give the band's own
     * body back to the last bit rather than to a tolerance.
     */
    @Test
    fun undoOfTheSecondGestureLeavesTheFirst() {
        val ed = Editor()
        ed.replaceDocument(DocumentFormat.load(fixture.lines().dropLast(2).joinToString("\n") + "\n"))
        val before = ed.doc.elements.count { it.kind == ElementKind.DRESSING }
        val band = volumeOf(refOf(bodyOf(ed.doc)), "the band alone")

        // the reporter's own second gesture, made here so there is a step to undo: the concave upright,
        // aimed at in the 3D view the way a pick on a vertical edge is made
        ed.activeScalar = ed.doc.scalars.first { it.name == "r" }
        ed.setTool(constructit.editor.Tools.BLEND_EDGE)
        val cam = Camera3(target = Vec3(0.0, -8.0, 10.0), distance = 260.0, yaw = 2.4, pitch = 0.2)
        val vp = Viewport3(camera = cam, widthPx = 800.0, heightPx = 600.0)
        vp.editor = ed
        vp.shown = true
        val screen = assertNotNull(cam.project(Vec3(plan[4].x, plan[4].y, 10.0), vp.widthPx, vp.heightPx), "the upright has an image")
        vp.pointerDown(screen)
        vp.pointerUp(screen)
        assertEquals(before + 1, ed.doc.elements.count { it.kind == ElementKind.DRESSING }, "the upright was rounded: ${ed.statusHint}")
        val both = volumeOf(refOf(bodyOf(ed.doc)), "band and fill")
        assertTrue(both > band, "the fill added its own material: $both vs $band")

        assertTrue(ed.undo(), "the gesture is one undo step")
        assertEquals(before, ed.doc.elements.count { it.kind == ElementKind.DRESSING }, "one rounding fewer")
        assertClose(volumeOf(refOf(bodyOf(ed.doc)), "the band after the undo"), band, 1e-9, "and the band is exactly what it was")
    }

    /** **The mixed pair's own entry comes off like any other row, and the body below it builds.** */
    @Test
    fun theEntryIsRemovableAndTheBodyBelowStands() {
        val ed = Editor()
        ed.replaceDocument(DocumentFormat.load(script(Triple(topAlongX, 4.0, BlendKind.FILLET), Triple(fillEdge, 4.0, BlendKind.FILLET), chained = false)))
        val entries = ed.doc.elements.filter { it.kind == ElementKind.DRESSING }
        assertEquals(2, entries.size, "two entries of one dressing")
        val whole = volumeOf(refOf(bodyOf(ed.doc)), "both roundings")
        ed.selectElement(entries.last())
        assertTrue(ed.deleteSelection(), "the fill comes off: ${ed.statusHint}")
        val left = volumeOf(refOf(bodyOf(ed.doc)), "the band alone")
        assertTrue(left < whole - 50.0, "the fill's own material went with it: $left vs $whole")
        assertNull(refusalOf(refOf(bodyOf(ed.doc))), "and what is left is a body")
    }
}
