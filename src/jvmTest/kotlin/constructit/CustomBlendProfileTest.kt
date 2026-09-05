package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.Construction
import constructit.dsl.PointRef
import constructit.dsl.ProfileRef
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.geom.Blend3
import constructit.geom.BlendKind
import constructit.geom.BlendSection
import constructit.geom.EdgeGeom
import constructit.geom.EdgeName
import constructit.geom.FaceName
import constructit.geom.Geom3
import constructit.geom.GeomMath
import constructit.geom.Mesh3
import constructit.geom.Section3
import constructit.geom.SolidFace
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.units.deg
import constructit.units.mm
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **Custom blend profiles — the general tier of the edge blend** (GitHub #30, session 80).
 *
 * *Fillet edge* and *Chamfer edge* already sweep a 2D profile along an edge and take it out of the corner —
 * an arc for the one, a bevel for the other. The general tier is the **drawn** section: any open chain whose
 * two ends land on the two faces, read in the corner's own frame, where its two coordinates **are** the
 * setbacks along the two faces. So a segment from `(3, 0)` to `(0, 6)` bevels 3 mm off one face and 6 mm off
 * the other whatever angle they stand at — which is the report's own *"the cut must extend the length of the
 * edge to produce the result of a rasped edge"*, answered by the frame rather than by a case.
 *
 * The two built-ins are this tier's fixtures: at a right dihedral a one-segment profile **is** the chamfer
 * and a quarter-arc **is** the fillet, and both are asserted here triangle for triangle.
 */
class CustomBlendProfileTest {
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

    private var ids = 0

    private fun pt(
        cx: Construction,
        p: Vec2,
    ): PointRef = cx.freePoint("q${ids++}", p.x.mm, p.y.mm)

    /** A profile of straight runs through [xy], in order — the everyday drawn chain. */
    private fun polyline(
        cx: Construction,
        xy: List<Vec2>,
    ): ProfileRef {
        val pts = xy.map { pt(cx, it) }
        return cx.profile(*xy.indices.drop(1).map { cx.segment(pts[it - 1], pts[it]) }.toTypedArray())
    }

    /** A profile of one arc through the three points — the way a user draws a round or a cove. */
    private fun arcProfile(
        cx: Construction,
        a: Vec2,
        b: Vec2,
        c: Vec2,
    ): ProfileRef = cx.profile(cx.arc3(pt(cx, a), pt(cx, b), pt(cx, c)))

    /** A prism over the polygon [xy], [h] deep. */
    private fun prism(
        cx: Construction,
        xy: List<Vec2>,
        h: Double,
    ): SolidRef {
        val pts = xy.mapIndexed { i, p -> cx.freePoint("p${ids++}", p.x.mm, p.y.mm) }
        val segs = xy.indices.map { cx.segment(pts[it], pts[(it + 1) % xy.size]) }
        return cx.extrude(cx.sketchOn(cx.planeXY(), cx.region(cx.loop(*segs.toTypedArray()))), cx.const(h.mm))
    }

    private fun plate(cx: Construction) = prism(cx, listOf(Vec2(0.0, 0.0), Vec2(40.0, 0.0), Vec2(40.0, 30.0), Vec2(0.0, 30.0)), 20.0)

    private fun faceIndex(
        base: SolidRef,
        name: FaceName,
    ): Int {
        val faces = assertNotNull(Section3.faces(Evaluator().solid(base).feature).first, "the solid names its faces")
        val at = faces.indexOfFirst { it.name == name }
        assertTrue(at >= 0, "$name is one of ${faces.map { it.name }}")
        return at
    }

    /** The index of the one-piece edge running from [a] to [b] (either way round). */
    private fun edgeIndex(
        base: SolidRef,
        a: Vec3,
        b: Vec3,
    ): Int {
        val edges = assertNotNull(Section3.edges(Evaluator().solid(base).feature).first, "the solid names its edges")
        val at =
            edges.indices.firstOrNull { i ->
                val el = Blend3.edgePath(edges[i]).first?.elements?.singleOrNull() ?: return@firstOrNull false
                ((el.start - a).length() <= 1e-6 && (el.end - b).length() <= 1e-6) ||
                    ((el.start - b).length() <= 1e-6 && (el.end - a).length() <= 1e-6)
            }
        assertNotNull(at, "an edge runs from $a to $b")
        return at
    }

    /** How long the base's edge [i] is. */
    private fun edgeLength(
        base: SolidRef,
        i: Int,
    ): Double {
        val edges = assertNotNull(Section3.edges(Evaluator().solid(base).feature).first, "the solid names its edges")
        val el = assertNotNull(Blend3.edgePath(edges[i]).first?.elements?.singleOrNull(), "edge #${i + 1} is one piece")
        return (el.end - el.start).length()
    }

    /** The indices of the base's edges that touch [at]. */
    private fun edgesAt(
        base: SolidRef,
        at: Vec3,
    ): List<Int> {
        val edges = assertNotNull(Section3.edges(Evaluator().solid(base).feature).first, "the solid names its edges")
        return edges.indices.filter { i ->
            val el = Blend3.edgePath(edges[i]).first?.elements?.singleOrNull() ?: return@filter false
            listOf(el.start, el.end).any { (it - at).length() <= 1e-6 }
        }
    }

    /**
     * One profile blend, scored exactly as the tool scores it: the choices come off [Blend3.choicesFor] with
     * the face the click named, and are then handed to the node verbatim (OP-1/OP-18).
     */
    private fun profileBlend(
        cx: Construction,
        base: SolidRef,
        profile: ProfileRef,
        drawn: List<Vec2>,
        whole: Boolean,
        address: Int,
        targets: List<Int>,
        onFace: FaceName? = null,
    ): SolidRef {
        val body = Evaluator().solid(base)
        val sec = BlendSection(BlendKind.PROFILE, 0.0, sectionOf(profile))
        val (choices, why) = Blend3.choicesFor(body, targets, sec, onFace)
        assertNotNull(choices, why)
        return cx.blend(base, base, cx.planeXY(), null, BlendKind.PROFILE, whole, address, choices, run = if (whole) emptyList() else targets, profile = profile)
    }

    private fun sectionOf(profile: ProfileRef): List<constructit.geom.ProfileElement> =
        ((Evaluator().eval(profile.node) as EvalResult.Ok).value as constructit.core.ProfileValue).profile.elements

    /** A built-in blend of one edge, for the two identity fixtures. */
    private fun builtIn(
        cx: Construction,
        base: SolidRef,
        size: Double,
        kind: BlendKind,
        target: Int,
    ): SolidRef {
        val body = Evaluator().solid(base)
        val (choices, why) = Blend3.choicesFor(body, listOf(target), BlendSection(kind, size))
        assertNotNull(choices, why)
        return cx.blend(base, base, cx.planeXY(), cx.const(size.mm), kind, whole = false, address = target, choices = choices)
    }

    /** Every triangle of [m] as a sorted key, so two meshes can be compared as *sets* of triangles. */
    private fun triangleSet(m: Mesh3): Set<String> {
        fun q(x: Double) = ((x * 1e6).toLong()).toString()

        fun key(p: Vec3) = q(p.x) + "," + q(p.y) + "," + q(p.z)
        return m.triangles.map { t -> listOf(key(m.vertices[t.a]), key(m.vertices[t.b]), key(m.vertices[t.c])).sorted().joinToString("|") }.toSet()
    }

    private fun assertSameBody(
        a: SolidRef,
        b: SolidRef,
        what: String,
    ) {
        val ma = meshOf(a)
        val mb = meshOf(b)
        assertEquals(triangleSet(ma), triangleSet(mb), what)
        assertClose(Geom3.volume(ma), Geom3.volume(mb), 1e-9, "$what: the same volume")
    }

    // ---- the two built-ins are the general tier's own fixtures ----

    /** **A one-segment profile is the chamfer**, triangle for triangle: `(3,0) → (0,3)` on a 40 mm rim. */
    @Test
    fun aOneSegmentProfileIsTheChamferItself() {
        val cx = Construction()
        val box = plate(cx)
        val before = volumeOf(box, "the plate")
        val rim = edgeIndex(box, Vec3(0.0, 0.0, 20.0), Vec3(40.0, 0.0, 20.0))
        val top = Section3.faces(Evaluator().solid(box).feature).first!![faceIndex(box, FaceName.Cap(SolidFace.TOP))].name

        val drawn = listOf(Vec2(3.0, 0.0), Vec2(0.0, 3.0))
        val shaped = profileBlend(cx, box, polyline(cx, drawn), drawn, whole = false, address = rim, targets = listOf(rim), onFace = top)
        val bevelled = builtIn(cx, box, 3.0, BlendKind.CHAMFER, rim)
        assertSameBody(shaped, bevelled, "a segment from (3,0) to (0,3) is the 3 mm chamfer itself")
        assertClose(before - volumeOf(shaped, "the shaped plate"), 0.5 * 3.0 * 3.0 * 40.0, 1e-6, "180 mm^3, exactly")
    }

    /** **A quarter-arc profile is the fillet**: the arc centred at `(r, r)` from `(r,0)` to `(0,r)`. */
    @Test
    fun aQuarterArcProfileIsTheFilletItself() {
        val cx = Construction()
        val box = plate(cx)
        val before = volumeOf(box, "the plate")
        val rim = edgeIndex(box, Vec3(0.0, 0.0, 20.0), Vec3(40.0, 0.0, 20.0))
        val top = Section3.faces(Evaluator().solid(box).feature).first!![faceIndex(box, FaceName.Cap(SolidFace.TOP))].name
        val r = 4.0
        val k = r - r / sqrt(2.0)
        val profile = arcProfile(cx, Vec2(r, 0.0), Vec2(k, k), Vec2(0.0, r))
        val shaped = profileBlend(cx, box, profile, emptyList(), whole = false, address = rim, targets = listOf(rim), onFace = top)
        val rounded = builtIn(cx, box, r, BlendKind.FILLET, rim)
        assertSameBody(shaped, rounded, "a quarter-arc about (r, r) is the r = 4 mm fillet itself")
        val exact = (1.0 - PI / 4.0) * r * r * 40.0
        val took = before - volumeOf(shaped, "the shaped plate")
        assertTrue(took >= exact - 1e-6, "at least the exact $exact mm^3 — it took $took")
        assertTrue(took <= exact + PI * r * GeomMath.TESS_TOL_MM * 40.0 / 3.0, "…and at most that plus the chords")
    }

    // ---- what the built-ins cannot say ----

    /** **An asymmetric bevel**: `(3, 0) → (0, 6)` takes `½·3·6·40 = 360 mm³`, exactly. */
    @Test
    fun anAsymmetricBevelTakesItsOwnTwoSetbacks() {
        val cx = Construction()
        val box = plate(cx)
        val before = volumeOf(box, "the plate")
        val rim = edgeIndex(box, Vec3(0.0, 0.0, 20.0), Vec3(40.0, 0.0, 20.0))
        val top = Section3.faces(Evaluator().solid(box).feature).first!![faceIndex(box, FaceName.Cap(SolidFace.TOP))].name
        val drawn = listOf(Vec2(3.0, 0.0), Vec2(0.0, 6.0))
        val shaped = profileBlend(cx, box, polyline(cx, drawn), drawn, whole = false, address = rim, targets = listOf(rim), onFace = top)
        assertClose(before - volumeOf(shaped, "the plate with an asymmetric bevel"), 0.5 * 3.0 * 6.0 * 40.0, 1e-6, "360 mm^3, exactly")

        // and the two setbacks are the two numbers drawn: the rails stand 3 mm in on the top face and 6 mm
        // down the side, which is what "the drawing's coordinates are the setbacks" means
        val rails = railsOf(shaped, rim)
        assertClose(rails.first, 3.0, 1e-9, "3 mm in along the face the click named")
        assertClose(rails.second, 6.0, 1e-9, "6 mm down the other face")
    }

    /** How far the two rails of the band along [edge] stand from that edge's own line. */
    private fun railsOf(
        blended: SolidRef,
        edge: Int,
    ): Pair<Double, Double> {
        val f = Evaluator().solid(blended).feature as constructit.geom.Feature3.Blend
        val base = assertNotNull(Section3.edges(f.base).first, "the base names its edges")
        val el = assertNotNull(Blend3.edgePath(base[edge]).first?.elements?.singleOrNull(), "the edge is one piece")
        val from = el.start
        val dir = (el.end - el.start).normalized()
        val edges = assertNotNull(Section3.edges(f).first, "the dressed body names its edges")

        fun reach(side: Int): Double {
            val rail = assertNotNull(edges.firstOrNull { it.name == EdgeName.BlendRail(edge, side) }, "rail $side of edge $edge")
            val geom = rail.geom as EdgeGeom.Straight
            val rel = geom.a - from
            return (rel - dir * rel.dot(dir)).length()
        }
        return reach(0) to reach(1)
    }

    /** **A step**: four straight runs, `320 mm³` exactly — and four flats that are faces you can sketch on. */
    @Test
    fun aStepProfileTakesItsOwnAreaAndLeavesFlatsToSketchOn() {
        val cx = Construction()
        val box = plate(cx)
        val before = volumeOf(box, "the plate")
        val rim = edgeIndex(box, Vec3(0.0, 0.0, 20.0), Vec3(40.0, 0.0, 20.0))
        val top = Section3.faces(Evaluator().solid(box).feature).first!![faceIndex(box, FaceName.Cap(SolidFace.TOP))].name
        val drawn = listOf(Vec2(4.0, 0.0), Vec2(4.0, 1.0), Vec2(2.0, 1.0), Vec2(2.0, 3.0), Vec2(0.0, 3.0))
        val shaped = profileBlend(cx, box, polyline(cx, drawn), drawn, whole = false, address = rim, targets = listOf(rim), onFace = top)
        assertClose(before - volumeOf(shaped, "the plate with a stepped edge"), 8.0 * 40.0, 1e-6, "320 mm^3, exactly")

        // the band is **four** faces, one per piece of the profile, and every one of them is a plane with a
        // rectangle: a step's flats are faces a sketch opens on
        val faces = assertNotNull(Section3.faces(Evaluator().solid(shaped).feature).first, "the dressed body names its faces")
        val bands = faces.filter { it.name is FaceName.BlendBand }
        assertEquals(4, bands.size, "one band face per piece of the profile: ${bands.map { it.name }}")
        for (b in bands) assertNotNull(b.plane, "${b.name.label} is a plane — it sweeps a straight run")
        val widths = bands.map { b -> b.outline.sumOf { e -> (GeomMath.endOf(e) - GeomMath.startOf(e)).length() } / 2.0 - 40.0 }
        assertEquals(4, widths.size, "four flats: $widths")
        for ((k, want) in listOf(1.0, 2.0, 2.0, 2.0).withIndex()) {
            assertClose(widths[k], want, 1e-9, "flat #${k + 1} is as wide as its own run and as long as the edge: $widths")
        }
    }

    /** **A cove** — an arc bulging away from the crease — removes the quarter disc, `4π·40 mm³`. */
    @Test
    fun aCoveScoopsTheQuarterDiscOut() {
        val cx = Construction()
        val box = plate(cx)
        val before = volumeOf(box, "the plate")
        val rim = edgeIndex(box, Vec3(0.0, 0.0, 20.0), Vec3(40.0, 0.0, 20.0))
        val top = Section3.faces(Evaluator().solid(box).feature).first!![faceIndex(box, FaceName.Cap(SolidFace.TOP))].name
        val r = 4.0
        val k = r / sqrt(2.0)
        val profile = arcProfile(cx, Vec2(r, 0.0), Vec2(k, k), Vec2(0.0, r))
        val shaped = profileBlend(cx, box, profile, emptyList(), whole = false, address = rim, targets = listOf(rim), onFace = top)
        val took = before - volumeOf(shaped, "the coved plate")
        val exact = PI * r * r / 4.0 * 40.0
        // the arc reaches the engine as an **inscribed** chord polygon, and here that pulls the boundary
        // *toward* the crease — so the bracket is the fillet's own, with the sign the other way round
        assertTrue(took <= exact + 1e-6, "never more than the exact $exact mm^3 — it took $took")
        assertTrue(took >= exact - PI * r * GeomMath.TESS_TOL_MM * 40.0 / 3.0, "…and never less by more than the chords")
        assertTrue(took > 0.5 * r * r * 40.0, "a cove scoops out more than the straight bevel between the same two ends")
    }

    /**
     * **A skewed corner is cut to the length a rasp reaches** — the report's own "tricky part". The upright
     * of an equilateral prism stands at a 60° dihedral, and the very drawing that takes 180 mm³ off a right
     * angle takes `½·6·3·sin 60°·20 = 155.884572681 mm³` there.
     */
    @Test
    fun aSkewedCornerIsCutToTheSetbacksItWasGiven() {
        val cx = Construction()
        val tri = prism(cx, listOf(Vec2(0.0, 0.0), Vec2(40.0, 0.0), Vec2(20.0, 20.0 * sqrt(3.0))), 20.0)
        val before = volumeOf(tri, "the prism")
        val upright = edgeIndex(tri, Vec3(0.0, 0.0, 0.0), Vec3(0.0, 0.0, 20.0))
        val drawn = listOf(Vec2(6.0, 0.0), Vec2(0.0, 3.0))
        val shaped = profileBlend(cx, tri, polyline(cx, drawn), drawn, whole = false, address = upright, targets = listOf(upright))
        val took = before - volumeOf(shaped, "the prism with a shaped upright")
        val exact = 0.5 * 6.0 * 3.0 * (sqrt(3.0) / 2.0) * 20.0
        assertClose(took, exact, abs(exact) * 1e-5, "155.884572681 mm^3 — the wedge the two setbacks make at 60 degrees")
        assertTrue(abs(took - 0.5 * 6.0 * 3.0 * 20.0) > 20.0, "…and not the 180 mm^3 a right angle would give")

        // the setbacks themselves are the drawing's own two numbers, measured **in the faces**
        val rails = railsOf(shaped, upright)
        val near = minOf(rails.first, rails.second)
        val far = maxOf(rails.first, rails.second)
        assertClose(near, 3.0, 1e-9, "3 mm along one face")
        assertClose(far, 6.0, 1e-9, "6 mm along the other")
    }

    // ---- corners ----

    /** **The whole rim with one asymmetric profile**: session 79's corner figure, with `δ(h) = 3(1 − h/6)`. */
    @Test
    fun aWholeFaceChainMitresItsCornersOnTheFaceItWasPickedOn() {
        val cx = Construction()
        val box = plate(cx)
        val before = volumeOf(box, "the plate")
        val topAt = faceIndex(box, FaceName.Cap(SolidFace.TOP))
        val top = FaceName.Cap(SolidFace.TOP)
        val targets = assertNotNull(Blend3.targets(Evaluator().solid(box).feature, true, topAt).first, "the top face's edges")
        assertEquals(4, targets.size, "four edges round the top face")
        val drawn = listOf(Vec2(3.0, 0.0), Vec2(0.0, 6.0))
        val shaped = profileBlend(cx, box, polyline(cx, drawn), drawn, whole = true, address = topAt, targets = targets, onFace = top)
        val took = before - volumeOf(shaped, "the plate with its whole top rim shaped")
        // Σ(area · L) − Σ_corners cot(θ/2)·∫δ², with δ(h) = 3(1 − h/6) and ∫₀⁶ δ² = 18
        val exact = 9.0 * 140.0 - 4.0 * 18.0
        assertClose(took, exact, abs(exact) * 1e-5, "1188 mm^3 — four bands and four mitres, exactly")
    }

    /** **A box corner takes its apex whichever order its three edges are shaped in.** */
    @Test
    fun aBoxCornerIsOneBodyInEveryOrder() {
        val drawn = listOf(Vec2(3.0, 0.0), Vec2(0.0, 3.0))
        val volumes = ArrayList<Double>()
        var faces = -1
        for (order in permutations(listOf(0, 1, 2))) {
            val cx = Construction()
            val box = plate(cx)
            val three = edgesAt(box, Vec3(0.0, 0.0, 20.0))
            assertEquals(3, three.size, "three edges meet at that corner")
            var out = box
            for (k in order) {
                val i = three[k]
                out = profileBlend(cx, out, polyline(cx, drawn), drawn, whole = false, address = i, targets = listOf(i))
            }
            volumes.add(volumeOf(out, "the box corner shaped in order $order"))
            val n = assertNotNull(Section3.faces(Evaluator().solid(out).feature).first, "faces").size
            if (faces < 0) faces = n else assertEquals(faces, n, "the same face list in every order")
        }
        for (v in volumes) assertClose(v, volumes[0], abs(volumes[0]) * 1e-6, "one body in all six orders: $volumes")
        // the apex is taken: three bevel planes crossing lose (3/4)c³ off the three bands' own sum
        val cx = Construction()
        val box = plate(cx)
        val before = volumeOf(box, "the plate")
        val three = edgesAt(box, Vec3(0.0, 0.0, 20.0))
        val lengths = three.sumOf { edgeLength(box, it) }
        assertClose(before - volumes[0], 4.5 * lengths - 0.75 * 27.0, abs(before - volumes[0]) * 1e-5, "three bands and their apex, exactly")
    }

    private fun permutations(xs: List<Int>): List<List<Int>> =
        if (xs.size <= 1) listOf(xs) else xs.flatMap { x -> permutations(xs - x).map { listOf(x) + it } }

    /** **Two different profiles cannot share a corner**, and the refusal says what to do about it. */
    @Test
    fun twoDifferentProfilesAtOneCornerRefuseByName() {
        val cx = Construction()
        val box = plate(cx)
        val top = FaceName.Cap(SolidFace.TOP)
        val a = edgeIndex(box, Vec3(0.0, 0.0, 20.0), Vec3(40.0, 0.0, 20.0))
        val b = edgeIndex(box, Vec3(40.0, 0.0, 20.0), Vec3(40.0, 30.0, 20.0))
        val one = listOf(Vec2(3.0, 0.0), Vec2(0.0, 3.0))
        val other = listOf(Vec2(5.0, 0.0), Vec2(0.0, 2.0))
        var out = profileBlend(cx, box, polyline(cx, one), one, whole = false, address = a, targets = listOf(a), onFace = top)
        out = profileBlend(cx, out, polyline(cx, other), other, whole = false, address = b, targets = listOf(b), onFace = top)
        val why = assertNotNull(refusalOf(out), "two different profiles at one corner refuse")
        assertTrue("carries two roundings that are not the same section on that face" in why, why)
        assertTrue("Give both edges the same profile" in why, why)
    }

    /** …and a custom profile meeting a **built-in** fillet at a corner refuses in the same words. */
    @Test
    fun aProfileMeetingAFilletAtACornerRefusesByName() {
        val cx = Construction()
        val box = plate(cx)
        val top = FaceName.Cap(SolidFace.TOP)
        val a = edgeIndex(box, Vec3(0.0, 0.0, 20.0), Vec3(40.0, 0.0, 20.0))
        val b = edgeIndex(box, Vec3(40.0, 0.0, 20.0), Vec3(40.0, 30.0, 20.0))
        val drawn = listOf(Vec2(3.0, 0.0), Vec2(0.0, 3.0))
        var out = builtIn(cx, box, 3.0, BlendKind.FILLET, a)
        out = profileBlend(cx, out, polyline(cx, drawn), drawn, whole = false, address = b, targets = listOf(b), onFace = top)
        val why = assertNotNull(refusalOf(out), "a profile meeting a fillet at a corner refuses")
        assertTrue("not the same section on that face" in why, why)
    }

    /** …while **two equal** profiles at a corner mitre, and take the crossing figure exactly. */
    @Test
    fun twoEqualProfilesAtOneCornerMitre() {
        val cx = Construction()
        val box = plate(cx)
        val before = volumeOf(box, "the plate")
        val top = FaceName.Cap(SolidFace.TOP)
        val a = edgeIndex(box, Vec3(0.0, 0.0, 20.0), Vec3(40.0, 0.0, 20.0))
        val b = edgeIndex(box, Vec3(40.0, 0.0, 20.0), Vec3(40.0, 30.0, 20.0))
        val drawn = listOf(Vec2(3.0, 0.0), Vec2(0.0, 6.0))
        var out = profileBlend(cx, box, polyline(cx, drawn), drawn, whole = false, address = a, targets = listOf(a), onFace = top)
        out = profileBlend(cx, out, polyline(cx, drawn), drawn, whole = false, address = b, targets = listOf(b), onFace = top)
        val took = before - volumeOf(out, "two shaped rims meeting at one corner")
        val exact = 9.0 * (40.0 + 30.0) - 18.0
        assertClose(took, exact, abs(exact) * 1e-5, "two bands and one mitre, exactly")
    }

    /**
     * **A step profile round a whole rim** — the mitre is affine in the section's own coordinates, so it does
     * not care how many pieces the section has: `Σ(8·L) − 4·∫δ²` with `δ = 4` for the first millimetre and
     * `2` for the next two, i.e. `∫₀³ δ² = 24`.
     */
    @Test
    fun aStepProfileMitresAWholeRim() {
        val cx = Construction()
        val box = plate(cx)
        val before = volumeOf(box, "the plate")
        val topAt = faceIndex(box, FaceName.Cap(SolidFace.TOP))
        val targets = assertNotNull(Blend3.targets(Evaluator().solid(box).feature, true, topAt).first, "the top face's edges")
        val drawn = listOf(Vec2(4.0, 0.0), Vec2(4.0, 1.0), Vec2(2.0, 1.0), Vec2(2.0, 3.0), Vec2(0.0, 3.0))
        val shaped =
            profileBlend(cx, box, polyline(cx, drawn), drawn, whole = true, address = topAt, targets = targets, onFace = FaceName.Cap(SolidFace.TOP))
        val took = before - volumeOf(shaped, "the plate with a stepped rim")
        val exact = 8.0 * 140.0 - 4.0 * 24.0
        assertClose(took, exact, abs(exact) * 1e-5, "1024 mm^3 — four stepped bands and four mitres")
        // …and every piece of the section is its own face, four per band
        val faces = assertNotNull(Section3.faces(Evaluator().solid(shaped).feature).first, "faces")
        assertEquals(16, faces.count { it.name is FaceName.BlendBand }, "four flats on each of four edges")
    }

    /**
     * **An inside corner pivots the drawn section about the upright** — session 80's [Blend3] `Turn`, which
     * a chain of pieces turns into a chain of surfaces. On an L-shaped cap the five convex corners mitre and
     * the reflex one *adds* `φ·∫δ²/2`, which for a `(3,0) → (0,6)` bevel and a right-angle turn is `4.5π`.
     */
    @Test
    fun anInsideCornerPivotsTheDrawnSection() {
        val cx = Construction()
        val ell =
            prism(
                cx,
                listOf(Vec2(0.0, 0.0), Vec2(60.0, 0.0), Vec2(60.0, 40.0), Vec2(30.0, 40.0), Vec2(30.0, 20.0), Vec2(0.0, 20.0)),
                20.0,
            )
        val before = volumeOf(ell, "the L")
        assertClose(before, 1800.0 * 20.0, 1e-6, "the L is 1800 mm^2 by 20 mm")
        val topAt = faceIndex(ell, FaceName.Cap(SolidFace.TOP))
        val targets = assertNotNull(Blend3.targets(Evaluator().solid(ell).feature, true, topAt).first, "the top face's edges")
        assertEquals(6, targets.size, "six edges round an L-shaped cap")
        val drawn = listOf(Vec2(3.0, 0.0), Vec2(0.0, 6.0))
        val shaped =
            profileBlend(cx, ell, polyline(cx, drawn), drawn, whole = true, address = topAt, targets = targets, onFace = FaceName.Cap(SolidFace.TOP))
        val took = before - volumeOf(shaped, "the L with its whole top rim shaped")
        val turn = PI / 2.0 * 18.0 / 2.0
        val exact = 9.0 * 200.0 - 5.0 * 18.0 + turn
        // the bands and the mitres are planar and exact; the **pivot** is the section carried round an arc
        // and reaches the engine as inscribed rings, so it is bracketed on its own figure — never above the
        // exact one, never below it by more than the chords of the 14.14 mm³ it adds
        assertTrue(took <= exact + abs(exact) * 1e-5, "never more than the exact $exact mm^3 — it took $took")
        assertTrue(took >= exact - 0.02 * turn, "…and never less by more than the pivot's own chords: $took vs $exact")
        assertTrue(took > 9.0 * 200.0 - 5.0 * 18.0, "and the pivot *adds* to the removal, which is what an inside corner does")

        // the pivot is a surface of its own, and it is named
        val faces = assertNotNull(Section3.faces(Evaluator().solid(shaped).feature).first, "faces")
        val corner = assertNotNull(faces.firstOrNull { it.name is FaceName.BlendCorner }, "the pivot is a face: ${faces.map { it.name }}")
        assertNotNull(corner.surface, "${corner.name.label} names the surface the section sweeps round the upright")
    }

    // ---- what is refused ----

    private fun refusalFor(
        drawn: List<Vec2>,
        closed: Boolean = false,
    ): String {
        val cx = Construction()
        val box = plate(cx)
        val top = FaceName.Cap(SolidFace.TOP)
        val rim = edgeIndex(box, Vec3(0.0, 0.0, 20.0), Vec3(40.0, 0.0, 20.0))
        val profile = polyline(cx, if (closed) drawn + drawn.first() else drawn)
        val body = Evaluator().solid(box)
        val sec = BlendSection(BlendKind.PROFILE, 0.0, sectionOf(profile))
        val (choices, why) = Blend3.choicesFor(body, listOf(rim), sec, top)
        assertNotNull(choices, why)
        val out = cx.blend(box, box, cx.planeXY(), null, BlendKind.PROFILE, false, rim, choices, run = listOf(rim), profile = profile)
        return assertNotNull(refusalOf(out), "that profile is refused")
    }

    @Test
    fun aProfileWhoseEndsMissTheFacesRefusesByName() {
        val why = refusalFor(listOf(Vec2(3.0, 1.5), Vec2(0.0, 4.0)))
        assertTrue("do not state a setback on each face" in why, why)
        assertTrue("Move them onto the axes" in why, why)
    }

    @Test
    fun aBeadThatWouldAddMaterialRefusesByName() {
        // the middle vertex stands outside the corner's own quadrant, so the section would *add* material
        val why = refusalFor(listOf(Vec2(4.0, 0.0), Vec2(3.0, -2.0), Vec2(0.0, 4.0)))
        assertTrue("reaches outside the corner" in why, why)
        assertTrue("sweep a closed section along the edge and fuse it" in why, why)
    }

    @Test
    fun aProfileThatCrossesItselfRefusesByName() {
        val why = refusalFor(listOf(Vec2(4.0, 0.0), Vec2(1.0, 4.0), Vec2(4.0, 3.0), Vec2(0.0, 1.0)))
        assertTrue("crosses itself" in why, why)
    }

    @Test
    fun aProfileWhoseSetbackOutgrowsAFaceSaysHowMuchFits() {
        val cx = Construction()
        val plateRef = prism(cx, listOf(Vec2(0.0, 0.0), Vec2(40.0, 0.0), Vec2(40.0, 30.0), Vec2(0.0, 30.0)), 6.0)
        val top = FaceName.Cap(SolidFace.TOP)
        val rim = edgeIndex(plateRef, Vec3(0.0, 0.0, 6.0), Vec3(40.0, 0.0, 6.0))
        val drawn = listOf(Vec2(3.0, 0.0), Vec2(0.0, 12.0))
        val out = profileBlend(cx, plateRef, polyline(cx, drawn), drawn, whole = false, address = rim, targets = listOf(rim), onFace = top)
        val why = assertNotNull(refusalOf(out), "12 mm down a 6 mm plate cannot fit")
        assertTrue("reaches past" in why, why)
        assertTrue("% of the profile you drew" in why, why)
    }

    @Test
    fun aProfileAgainstACurvedLegRefusesAndNamesTheBuiltIns() {
        val cx = Construction()
        val o = cx.freePoint("axisO", 0.mm, 0.mm)
        val axis = cx.direction(o, cx.freePoint("axisX", 1.mm, 0.mm))
        val bar =
            cx.revolve(
                cx.sketchOn(
                    cx.planeXY(),
                    cx.region(
                        cx.loop(
                            cx.segment(pt(cx, Vec2(0.0, 15.0)), pt(cx, Vec2(60.0, 15.0))),
                            cx.segment(pt(cx, Vec2(60.0, 15.0)), pt(cx, Vec2(60.0, 25.0))),
                            cx.segment(pt(cx, Vec2(60.0, 25.0)), pt(cx, Vec2(0.0, 25.0))),
                            cx.segment(pt(cx, Vec2(0.0, 25.0)), pt(cx, Vec2(0.0, 15.0))),
                        ),
                    ),
                ),
                o,
                axis,
                cx.parameter("sweep", 90.0.deg),
            )
        val body = Evaluator().solid(bar)
        // piece #0 runs along the axis at r = 15 (the bore), so on the cap it meets a **cylinder** side-on
        val edges = assertNotNull(Section3.edges(body.feature).first, "the bar names its edges")
        val rim = edges.indices.first { edges[it].name == EdgeName.RevolveCapPiece(SolidFace.TOP, 0) }
        val drawn = listOf(Vec2(3.0, 0.0), Vec2(0.0, 3.0))
        val profile = polyline(cx, drawn)
        val sec = BlendSection(BlendKind.PROFILE, 0.0, sectionOf(profile))
        val (choices, whyC) = Blend3.choicesFor(body, listOf(rim), sec, null)
        assertNotNull(choices, whyC)
        val out = cx.blend(bar, bar, cx.planeXY(), null, BlendKind.PROFILE, false, rim, choices, run = listOf(rim), profile = profile)
        val why = assertNotNull(refusalOf(out), "a drawn profile against a bore refuses")
        assertTrue("in a circle rather than in a straight leg" in why, why)
        assertTrue("round that edge with Fillet edge or Chamfer edge" in why, why)
        assertTrue("future extension" in why, why)
    }

    // ---- honesty ----

    /** A **Bézier** piece builds and is watertight, and its band carries the honesty note instead of a surface. */
    @Test
    fun aBezierProfileBuildsAndSaysWhatItCannotName() {
        val cx = Construction()
        val box = plate(cx)
        val before = volumeOf(box, "the plate")
        val top = FaceName.Cap(SolidFace.TOP)
        val rim = edgeIndex(box, Vec3(0.0, 0.0, 20.0), Vec3(40.0, 0.0, 20.0))
        val profile =
            cx.profile(
                cx.bezier(pt(cx, Vec2(4.0, 0.0)), pt(cx, Vec2(4.0, 2.5)), pt(cx, Vec2(2.5, 4.0)), pt(cx, Vec2(0.0, 4.0))),
            )
        val shaped = profileBlend(cx, box, profile, emptyList(), whole = false, address = rim, targets = listOf(rim), onFace = top)
        val took = before - volumeOf(shaped, "the plate shaped by a Bézier")
        assertTrue(took > 0.0 && took < 16.0 * 40.0, "it takes something, and less than the whole corner square: $took")
        val faces = assertNotNull(Section3.faces(Evaluator().solid(shaped).feature).first, "faces")
        val band = assertNotNull(faces.firstOrNull { it.name is FaceName.BlendBand }, "the band is a face")
        assertNull(band.plane, "there is nothing to sketch on a Bézier band")
        assertTrue("no surface for" in (band.reason ?: ""), band.reason ?: "no reason")
    }

    /** …and an **arc** band still refuses sketch-on-face in the arc's own words. */
    @Test
    fun anArcBandRefusesASketchInTheRoundingsOwnWords() {
        val cx = Construction()
        val box = plate(cx)
        val top = FaceName.Cap(SolidFace.TOP)
        val rim = edgeIndex(box, Vec3(0.0, 0.0, 20.0), Vec3(40.0, 0.0, 20.0))
        val r = 4.0
        val k = r - r / sqrt(2.0)
        val profile = arcProfile(cx, Vec2(r, 0.0), Vec2(k, k), Vec2(0.0, r))
        val shaped = profileBlend(cx, box, profile, emptyList(), whole = false, address = rim, targets = listOf(rim), onFace = top)
        val faces = assertNotNull(Section3.faces(Evaluator().solid(shaped).feature).first, "faces")
        val band = assertNotNull(faces.firstOrNull { it.name is FaceName.BlendBand }, "the band is a face")
        assertNull(band.plane, "a cylinder is not a plane")
        assertTrue("put a datum plane where you want to sketch" in (band.reason ?: ""), band.reason ?: "no reason")
    }

    /** **The profile is an ordinary drawing**: move an end and the body re-cuts, with no node minted. */
    @Test
    fun draggingTheProfileReCutsTheBody() {
        val cx = Construction()
        val box = plate(cx)
        val before = volumeOf(box, "the plate")
        val top = FaceName.Cap(SolidFace.TOP)
        val rim = edgeIndex(box, Vec3(0.0, 0.0, 20.0), Vec3(40.0, 0.0, 20.0))
        val a = cx.freePoint("a", 3.mm, 0.mm)
        val b = cx.freePoint("b", 0.mm, 6.mm)
        val profile = cx.profile(cx.segment(a, b))
        val drawn = listOf(Vec2(3.0, 0.0), Vec2(0.0, 6.0))
        val shaped = profileBlend(cx, box, profile, drawn, whole = false, address = rim, targets = listOf(rim), onFace = top)
        assertClose(before - volumeOf(shaped, "before the drag"), 360.0, 1e-6, "360 mm^3")
        val nodesBefore = cx.nodesCreated
        (a.node as constructit.core.SourceNode).value = constructit.core.PointValue(Vec2(5.0, 0.0))
        assertEquals(nodesBefore, cx.nodesCreated, "a drag mints no node")
        assertClose(before - volumeOf(shaped, "after the drag"), 0.5 * 5.0 * 6.0 * 40.0, 1e-6, "600 mm^3 — the drawing is the section")
    }
}
