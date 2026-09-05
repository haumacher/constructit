package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.Construction
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.editor.DocumentFormat
import constructit.geom.Blend3
import constructit.geom.BlendKind
import constructit.geom.BlendSection
import constructit.geom.Geom3
import constructit.geom.GeomMath
import constructit.geom.Mesh3
import constructit.geom.MeshCanon
import constructit.geom.Section3
import constructit.geom.Tri
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.units.mm
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **A tool never shares a face with the body** — GitHub issue #33, *"3D fillet creates invalid
 * body/rendering artifact"*.
 *
 * The reporter rounded the upright at the 19.4° tip of a chevron and got a body *"rounded at the top and
 * still coming to a sharp point at the base"*. Every structural check passed it: the mesh was closed,
 * consistently wound, of the right volume to the last digit of the closed form. What it carried was a
 * **zero-thickness flap** — the old tip vertex still in the mesh at `z = 0`, the wall's triangulation
 * running out to it and folding back over itself. Edge-use counts are 1/1 across a fold, so nothing saw it.
 *
 * The cause was a tool face lying *exactly* in a body face. A single-edge blend went through
 * `Geom3.sweep` with the plain wedge, whose two legs lie in the two faces they are tangent to; and even the
 * stitched tool grew its section only at a corner (*"grown at a corner, plain at a free end: the growth
 * tapers along the run"*), so at every free end the leg was coplanar with the face again. Cancelling two
 * coincident sheets in float32 is a coin, and at this tip it came up tails.
 *
 * What this class pins: the step-off is now uniform along the whole run and the free-end caps are stepped
 * too, so no tool face and no tool cap lies in a face of the body; and the fold itself is now a **named
 * defect** in the vocabulary (`MeshCanon.flap`, asserted by every `assertManifold` in the suite).
 */
class FlapFreeBlendTest {
    // ---- the reporter's own drawing ----

    /** GitHub #33's script, verbatim. */
    private val issue33 =
        """
constructit 4
point -69.47203733974055,-45.25896945666625 -> e1
point -26.451147843545805,-97.56526864553598 -> e2
tool segment pts=e1,e2 clicks=-70.875,-36.375;-45.625,-85.625 -> e3
point -23.282652808360613,-41.472580496979674 -> e4
tool segment pts=e1,e4 clicks=-70.875,-36.375;2.875,-30.625 -> e5
point -54.515412407715694,-55.42163010985958 -> e6
tool segment pts=e4,e6 clicks=2.125,-31.375;3.125,-64.125 -> e7
tool segment pts=e6,e2 clicks=1.625,-63.125;-47.625,-84.375 -> e8
param "r" = 5mm
tool fillet els=e3,e5 clicks=-64.875,-47.625;-46.875,-35.125 scalar="r" signs=1;1 -> e9
tool fillet els=e7,e8 clicks=2.375,-57.375;-8.125,-69.875 scalar="r" signs=-1;1 -> e10
tool outline els=e3,e8,e10,e7,e5,e9 clicks=-68.375,-57.625;-53.875,-62.875;-60.10651566019921,-43.78172408508907;-26.874580888061868,-35.96175687686606;-37.43412097231523,-30.847088820784105;-82.21156720852939,-33.881151691321946 -> e11,e12,e13,e14,e15,e16,e17
param "h" = 20mm
tool extrude els=e17 clicks=-71.625,-51.625 scalar="h" -> e18
param "r2" = 2mm
tool filletedge els=e18 clicks=-28.62800083557761,-21.44546916378542 scalar="r2" signs=4;-1;1;0;1 -> e19
"""
            .trimStart()

    /** The tip the reporter rounded: the chevron's own point, at 19.4°. */
    private val tip = Vec2(-23.282652808360613, -41.472580496979674)

    private fun meshNamed(
        text: String,
        name: String,
    ): Mesh3 {
        val doc = DocumentFormat.load(text)
        val el = assertNotNull(doc.elements.firstOrNull { doc.nameOf(it) == name }, "$name is in the drawing")
        val res = Evaluator().eval(el.ref.node)
        assertTrue(res !is EvalResult.Invalid, "$name is a body: ${(res as? EvalResult.Invalid)?.reason}")
        return Evaluator().solid(el.ref as SolidRef).mesh
    }

    /**
     * **The report itself.** Both bodies build, both are watertight *and* flap-free, the sharp tip is gone
     * from the mesh at every height, and the rounding took the wedge the closed form says it should.
     */
    @Test
    fun issue33TheRoundedTipLeavesNoFlapAndNoSharpPointBehind() {
        val plate = meshNamed(issue33, "e18")
        val rounded = meshNamed(issue33, "e19")
        assertManifold(plate, "the chevron plate")
        assertManifold(rounded, "the chevron plate with its tip upright rounded")

        val stray = rounded.vertices.filter { abs(it.x - tip.x) <= 1e-6 && abs(it.y - tip.y) <= 1e-6 }
        assertTrue(stray.isEmpty(), "nothing of the old sharp tip is left at any height: $stray")

        val took = Geom3.volume(plate) - Geom3.volume(rounded)
        assertTrue(took in 356.0..358.0, "the tip's own wedge, and nothing else: $took mm^3")
    }

    /** …and the drawing is still a fixed point of save (OP-18), which the rounding must not disturb. */
    @Test
    fun theReportersFileRoundTripsByteForByte() {
        val once = DocumentFormat.save(DocumentFormat.load(issue33))
        assertTrue(once.isNotEmpty(), "it saves")
        kotlin.test.assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "save -> load -> save is byte-equal")
    }

    // ---- the same tip, at every angle, on both caps ----

    private val h = 20.0

    private fun prism(
        cx: Construction,
        xy: List<Vec2>,
        depth: Double,
    ): SolidRef {
        val pts = xy.mapIndexed { i, p -> cx.freePoint("p$i", p.x.mm, p.y.mm) }
        val segs = xy.indices.map { cx.segment(pts[it], pts[(it + 1) % xy.size]) }
        return cx.extrude(cx.sketchOn(cx.planeXY(), cx.region(cx.loop(*segs.toTypedArray()))), cx.const(depth.mm))
    }

    private fun blendOn(
        cx: Construction,
        on: SolidRef,
        size: Double,
        whole: Boolean,
        address: Int,
        kind: BlendKind = BlendKind.FILLET,
    ): SolidRef {
        val body = Evaluator().solid(on)
        val (targets, whyTargets) = Blend3.targets(body.feature, whole, address)
        assertNotNull(targets, whyTargets)
        val (choices, why) = Blend3.choicesFor(body, targets, BlendSection(kind, size))
        assertNotNull(choices, why)
        return cx.blend(on, on, cx.planeXY(), cx.const(size.mm), kind, whole, address, choices)
    }

    private fun capFace(
        ref: SolidRef,
        z: Double,
    ): Int {
        val faces = assertNotNull(Section3.faces(Evaluator().solid(ref).feature).first)
        val nz = if (z > 0) 1.0 else -1.0
        return assertNotNull(
            faces.indices.firstOrNull { i ->
                val p = faces[i].plane ?: return@firstOrNull false
                abs(p.normal.normalized().z - nz) < 1e-9 && abs(p.origin.z - z) < 1e-9
            },
            "a cap at z = $z",
        )
    }

    private fun uprightAt(
        ref: SolidRef,
        xy: Vec2,
    ): Int {
        val edges = assertNotNull(Section3.edges(Evaluator().solid(ref).feature).first)
        return assertNotNull(
            edges.indices.firstOrNull { i ->
                val path = Blend3.edgePath(edges[i]).first ?: return@firstOrNull false
                val el = path.elements.singleOrNull() ?: return@firstOrNull false
                val ends = listOf(el.start, el.end).sortedBy { it.z }
                (ends[0] - Vec3(xy.x, xy.y, 0.0)).length() < 1e-6 && (ends[1] - Vec3(xy.x, xy.y, h)).length() < 1e-6
            },
            "an upright at $xy",
        )
    }

    private fun meshOf(
        ref: SolidRef,
        what: String,
    ): Mesh3 {
        val res = Evaluator().eval(ref.node)
        assertTrue(res !is EvalResult.Invalid, "$what builds: ${(res as? EvalResult.Invalid)?.reason}")
        val mesh = Evaluator().solid(ref).mesh
        assertManifold(mesh, what)
        return mesh
    }

    /** A wedge of interior angle [deg] at the origin, its two legs [len] long, closed across the back. */
    private fun wedgePlan(
        deg: Double,
        len: Double,
    ): List<Vec2> {
        val half = deg * PI / 360.0
        return listOf(Vec2(0.0, 0.0), Vec2(len * cos(half), len * sin(half)), Vec2(len * cos(half), -len * sin(half)))
    }

    /** What a fillet of [r] takes out of a corner of interior angle [rad]: the kite less the sector. */
    private fun tipWedge(
        rad: Double,
        r: Double,
    ): Double = r * r * (1.0 / tan(rad / 2.0) - (PI - rad) / 2.0)

    /**
     * The same wedge as the **mesh** states it: the arc reaches the engine as its own inscribed chords, so
     * the disc the wedge is the corner *less* comes out a shade small and the wedge a shade large.
     *
     * This is the upper half of every bracket below. It is computed from the tessellation's own step count
     * rather than guessed at as a percentage, so a change of `TESS_TOL_MM` moves the bracket with it.
     */
    private fun tipWedgeByChords(
        rad: Double,
        r: Double,
    ): Double {
        val sweep = PI - rad
        val n = GeomMath.chordSteps(r, sweep, GeomMath.TESS_TOL_MM)
        return r * r * (1.0 / tan(rad / 2.0) - n * sin(sweep / n) / 2.0)
    }

    /**
     * **Every sharpness of tip, and both caps.** The flap in #33 stood at `z = 0`, so the cap at each end of
     * the upright has to be exercised, and the tip's angle is what decides how far the band reaches along
     * the two walls — 11.3 mm at the reporter's 19.4°, and past 22 mm at 10°.
     *
     * The volume is the closed form `h·r²(cot(θ/2) − (π−θ)/2)`, bracketed by chords: a tessellated band
     * takes a shade less than the exact one and never more.
     */
    @Test
    fun aSharpTipAtEveryAngleRoundsFlapFreeOnBothCaps() {
        val r = 2.0
        for (deg in listOf(10.0, 20.0, 45.0, 90.0, 150.0)) {
            val rad = deg * PI / 180.0
            val plan = wedgePlan(deg, 60.0)
            val cx = Construction()
            val body = prism(cx, plan, h)
            val plain = blendOn(cx, body, r, false, uprightAt(body, plan[0]))
            val before = Geom3.volume(meshOf(body, "the $deg° wedge"))
            val after = Geom3.volume(meshOf(plain, "the $deg° wedge, tip rounded"))
            val exact = h * tipWedge(rad, r)
            val chords = h * tipWedgeByChords(rad, r)
            val took = before - after
            assertTrue(took >= exact - 1e-3, "the $deg° tip takes at least the exact $exact mm^3 — it took $took")
            assertTrue(took <= chords + 1e-3, "…and no more than its own chords' $chords: it took $took")
            val stray = meshOf(plain, "the $deg° wedge, tip rounded").vertices.filter { it.x * it.x + it.y * it.y <= 1e-12 }
            assertTrue(stray.isEmpty(), "the $deg° tip is gone at every height: $stray")

            // …and with the cap chain already rounded, at each cap in turn: the upright is then a mixed
            // vertex, and its tool still shares no face with the body
            for (capZ in listOf(h, 0.0)) {
                val cy = Construction()
                val base = prism(cy, plan, h)
                val capped = blendOn(cy, base, 1.0, true, capFace(base, capZ))
                val tipped = blendOn(cy, capped, r, false, uprightAt(capped, plan[0]))
                meshOf(tipped, "the $deg° wedge, cap at z = $capZ rounded, then the tip")
            }
        }
    }

    // ---- the L-block: an end at an inside corner, and a concave fill ----

    /** The L of the mixed-vertex work, whose fifth corner is the reflex one. */
    private val ell =
        listOf(
            Vec2(-26.875, -32.375),
            Vec2(-26.875, 15.375),
            Vec2(61.875, 15.375),
            Vec2(61.875, 0.375),
            Vec2(-5.521648428788623, 0.375),
            Vec2(-5.521648428788623, -32.375),
        )
    private val inside = ell[4]

    /** The boundary edge of the top face that runs into [at] — one edge, one free end at an inside corner. */
    private fun capEdgeInto(
        ref: SolidRef,
        at: Vec2,
    ): Int {
        val edges = assertNotNull(Section3.edges(Evaluator().solid(ref).feature).first)
        return assertNotNull(
            edges.indices.firstOrNull { i ->
                val path = Blend3.edgePath(edges[i]).first ?: return@firstOrNull false
                val el = path.elements.singleOrNull() ?: return@firstOrNull false
                listOf(el.start, el.end).any { (it - Vec3(at.x, at.y, h)).length() < 1e-6 } &&
                    abs(el.start.z - h) < 1e-9 && abs(el.end.z - h) < 1e-9
            },
            "a top-face boundary edge running into $at",
        )
    }

    /**
     * **One edge of the L's top rim, whose far end is the inside corner.** Nothing continues the band
     * there — the wall across the corner stands square to this edge, so the tool's cap would lie exactly in
     * it — and the tube is pulled back the same micron `buttEnds` has always used, which leaves a micron of
     * material at a corner that already keeps a whole spike. Asserted as a bound rather than assumed: the
     * band takes its full wedge less at most that micron's worth.
     */
    @Test
    fun aBandEndingAtAnInsideCornerIsFlapFreeAndKeepsItsMicron() {
        val r = 5.0
        val cx = Construction()
        val body = prism(cx, ell, h)
        val edge = capEdgeInto(body, inside)
        val rounded = blendOn(cx, body, r, false, edge)
        val before = Geom3.volume(meshOf(body, "the L"))
        val after = Geom3.volume(meshOf(rounded, "the L with one rim edge rounded"))
        val took = before - after
        // the edge from the inside corner to the L's own corner at ell[5], less the micron pulled back
        val run = (ell[5] - inside).length()
        val exact = (run - 1e-3) * tipWedge(PI / 2.0, r)
        val chords = run * tipWedgeByChords(PI / 2.0, r)
        assertTrue(took >= exact - 1e-3, "one band, the micron pulled back at the inside corner and no less: $took vs $exact")
        assertTrue(took <= chords + 1e-3, "…and no more than the whole run by its own chords: $took vs $chords")
    }

    /**
     * **A concave single-edge fill** — the L's inside upright, filled rather than cut. Its tool is *added*,
     * so the step-off goes the other way (into material already there) and its caps stay flush with the
     * body's own, which is safe because two coplanar faces pointing the **same** way merge rather than
     * cancel. The figure is the quadrant's own area over the full height.
     */
    @Test
    fun theConcaveUprightFillsFlapFreeAndTakesItsQuadrant() {
        val ru = 5.0
        val cx = Construction()
        val body = prism(cx, ell, h)
        val filled = blendOn(cx, body, ru, false, uprightAt(body, inside))
        val before = Geom3.volume(meshOf(body, "the L"))
        val after = Geom3.volume(meshOf(filled, "the L with its inside upright filled"))
        val exact = h * tipWedge(PI / 2.0, ru)
        val chords = h * tipWedgeByChords(PI / 2.0, ru)
        val added = after - before
        assertTrue(added >= exact - 1e-3, "the fill adds at least the exact quadrant: $added vs $exact")
        assertTrue(added <= chords + 1e-3, "…and no more than its own chords' $chords: it added $added")
    }

    // ---- the check itself ----

    /**
     * **The flap detector, on a mesh built to have one.** Two triangles back to back are a closed,
     * consistently wound surface by every count there is — each directed edge once, each reverse once —
     * and enclose nothing. That is the shape of the defect #33 hid behind, so it is what the check is
     * asserted on; a cube, which has none, passes.
     */
    @Test
    fun aFlapIsNamedAndACleanSolidIsNot() {
        val a = Vec3(0.0, 0.0, 0.0)
        val b = Vec3(10.0, 0.0, 0.0)
        val c = Vec3(0.0, 10.0, 0.0)
        val billboard = Mesh3(listOf(a, b, c), listOf(Tri(0, 1, 2), Tri(0, 2, 1)))
        assertNull(MeshCanon.notClosed(billboard), "it is closed and consistently wound — that is the point")
        val said = assertNotNull(MeshCanon.flap(billboard), "and it is still a flap")
        assertTrue(said.contains("zero-thickness flap"), "the defect is named: $said")
        assertTrue(said.contains("folds back on itself"), "and said in words that can be acted on: $said")
        val threw =
            runCatching {
                assertManifold(billboard, "a bare flap")
            }.exceptionOrNull()
        assertTrue(
            threw?.message?.contains("zero-thickness flap") == true,
            "and the test-side check says the same: ${threw?.message}",
        )

        val cx = Construction()
        val cube = prism(cx, listOf(Vec2(0.0, 0.0), Vec2(10.0, 0.0), Vec2(10.0, 10.0), Vec2(0.0, 10.0)), 10.0)
        val mesh = Evaluator().solid(cube).mesh
        assertNull(MeshCanon.flap(mesh), "a cube folds nowhere")
        assertManifold(mesh, "the cube")
    }

    /** A rounded tip really is round: no vertex of the band stands where the two walls used to meet. */
    @Test
    fun theBandStandsOffTheOldCornerAtEveryHeight() {
        val r = 2.0
        val cx = Construction()
        val plan = wedgePlan(19.4, 60.0)
        val body = prism(cx, plan, h)
        val rounded = blendOn(cx, body, r, false, uprightAt(body, plan[0]))
        val mesh = meshOf(rounded, "the 19.4° tip rounded")
        val half = 19.4 * PI / 360.0
        val centre = Vec2(r / sin(half), 0.0)
        for (v in mesh.vertices) {
            val d = (Vec2(v.x, v.y) - centre).length()
            assertTrue(
                d >= r - 1e-3 || v.x >= centre.x - 1e-9,
                "no vertex stands inside the rolling ball's own circle at $v (d = $d)",
            )
        }
    }
}
