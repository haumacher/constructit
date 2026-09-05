package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.Construction
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.geom.Blend3
import constructit.geom.BlendKind
import constructit.geom.BlendSection
import constructit.geom.Geom3
import constructit.geom.Mesh3
import constructit.geom.Section3
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.units.mm
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sin
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **Orchestrator's probe of "a tool never shares a face with the body" (GitHub #33)** on fixtures the delivery
 * never saw: a **concave** 20° notch filled (the union tool's legs, into the material), a **chamfer** on the
 * 19° tip (planar, so exact), the whole top face of the dart rounded — a 19° `Joint`, two obtuse ones and the
 * reflex corner's `Turn` — and then the tip upright on top of that (a ball patch at a 19° vertex).
 */
class FlapFreeBlendProbeTest {
    private val h = 20.0

    /** The reporter's dart without its two 2D fillets: e1, e2, e6 (reflex), e4 (the 19° tip). */
    private val dart =
        listOf(
            Vec2(-69.47203733974055, -45.25896945666625),
            Vec2(-26.451147843545805, -97.56526864553598),
            Vec2(-54.515412407715694, -55.42163010985958),
            Vec2(-23.282652808360613, -41.472580496979674),
        )
    private val tip = dart[3]

    /** A 60 x 40 slab with a 20° V-notch cut into its top side, apex at the origin pointing down. */
    private val notched: List<Vec2> by lazy {
        val half = 10.0 * PI / 180
        val depth = 25.0
        val w = depth * tan(half)
        listOf(
            Vec2(-30.0, 15.0),
            Vec2(-30.0, -25.0),
            Vec2(30.0, -25.0),
            Vec2(30.0, 15.0),
            Vec2(w, 15.0),
            Vec2(0.0, 15.0 - depth),
            Vec2(-w, 15.0),
        )
    }

    private fun area(pts: List<Vec2>): Double = abs(pts.indices.sumOf { pts[it].cross(pts[(it + 1) % pts.size]) }) / 2

    /** The interior angle at vertex [i] of a counter-clockwise polygon, in (0, 2π). */
    private fun interior(
        pts: List<Vec2>,
        i: Int,
    ): Double {
        val ccw = pts.indices.sumOf { pts[it].cross(pts[(it + 1) % pts.size]) } > 0
        val p = pts[i]
        val prev = pts[(i + pts.size - 1) % pts.size] - p
        val next = pts[(i + 1) % pts.size] - p
        // the interior lies to the left of travel on a counter-clockwise ring: turn from the outgoing edge to the incoming one
        var t = if (ccw) atan2(next.cross(prev), next.dot(prev)) else atan2(prev.cross(next), prev.dot(next))
        if (t < 0) t += 2 * PI
        return t
    }

    /** A fillet wedge's section area at a corner of opening angle [theta]. */
    private fun filletWedge(
        r: Double,
        theta: Double,
    ): Double = r * r * (1 / tan(theta / 2) - (PI - theta) / 2)

    // ---- DSL ----

    private fun prism(
        cx: Construction,
        xy: List<Vec2>,
    ): SolidRef {
        val pts = xy.mapIndexed { i, p -> cx.freePoint("p$i", p.x.mm, p.y.mm) }
        val segs = xy.indices.map { cx.segment(pts[it], pts[(it + 1) % xy.size]) }
        return cx.extrude(cx.sketchOn(cx.planeXY(), cx.region(cx.loop(*segs.toTypedArray()))), cx.const(h.mm))
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
        assertNotNull(targets, whyTargets?.render())
        val (choices, why) = Blend3.choicesFor(body, targets, BlendSection(kind, size))
        assertNotNull(choices, why?.render())
        return cx.blend(on, on, cx.planeXY(), cx.const(size.mm), kind, whole, address, choices)
    }

    private fun topFace(ref: SolidRef): Int {
        val faces = assertNotNull(Section3.faces(Evaluator().solid(ref).feature).first)
        return assertNotNull(
            faces.indices.firstOrNull { i ->
                val p = faces[i].plane ?: return@firstOrNull false
                abs(p.normal.normalized().z - 1.0) < 1e-9 && abs(p.origin.z - h) < 1e-9
            },
            "a top face",
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

    private fun mesh(ref: SolidRef): Mesh3 {
        val res = Evaluator().eval(ref.node)
        assertTrue(res !is EvalResult.Invalid, "valid: ${(res as? EvalResult.Invalid)?.reason}")
        val m = Evaluator().solid(ref).mesh
        assertManifold(m, "the blended body")
        return m
    }

    private fun noVertexAt(
        m: Mesh3,
        xy: Vec2,
        what: String,
    ) {
        val stray = m.vertices.filter { abs(it.x - xy.x) < 1e-6 && abs(it.y - xy.y) < 1e-6 }
        assertTrue(stray.isEmpty(), "$what: the sharp corner at $xy is gone from the mesh, but $stray remain")
    }

    // ---- 1. the concave notch: a union tool's legs lie in the walls ----

    @Test
    fun aConcaveTwentyDegreeNotchIsFilledWithoutAFlap() {
        val r = 2.0
        val cx = Construction()
        val body = prism(cx, notched)
        val apex = notched[5]
        val fill = blendOn(cx, body, r, false, uprightAt(body, apex))
        val m = mesh(fill)
        // (the apex itself may stay as an interior vertex of the merged cap — what must be gone is the flap, which assertManifold now sees)
        val theta = 20.0 * PI / 180
        val exact = area(notched) * h + filletWedge(r, theta) * h
        val v = Geom3.volume(m)
        // the fill's arc is inscribed chords lying on the notch's side of it, so the mesh holds a little *more* than the exact fill
        assertTrue(v >= exact - 1e-6 && v <= exact + filletWedge(r, theta) * h * 0.02, "the filled notch: $v vs $exact")
    }

    // ---- 2. a chamfer on the 19° tip: all planar, exact ----

    @Test
    fun aChamferOnTheSharpTipIsExactAndFlapFree() {
        val c = 2.0
        val cx = Construction()
        val body = prism(cx, dart)
        val cut = blendOn(cx, body, c, false, uprightAt(body, tip), BlendKind.CHAMFER)
        val m = mesh(cut)
        noVertexAt(m, tip, "the tip")
        val theta = interior(dart, 3)
        assertTrue(theta < 0.4, "the tip is sharp: ${theta * 180 / PI}°")
        // the bevel's triangle: two setbacks c along the faces, so its area is c²·sin(θ)/2
        val exact = area(dart) * h - c * c * sin(theta) / 2 * h
        assertClose(Geom3.volume(m), exact, 1e-5 * exact, "the chamfered tip, exactly")
    }

    // ---- 3. the whole top face of the dart, then the tip upright on top ----

    @Test
    fun theDartsTopFaceRoundsThroughItsSharpJointAndItsReflexTurnAndThenTheTipUpright() {
        // the dart's lower tip is a 5.5° sliver, so only a small radius fits along its two edges (the refusal names 0.685 mm for r = 2)
        val r = 0.3
        val cx = Construction()
        val body = prism(cx, dart)
        val cap = blendOn(cx, body, r, true, topFace(body))
        val mCap = mesh(cap)
        val before = area(dart) * h
        assertTrue(Geom3.volume(mCap) < before && Geom3.volume(mCap) > before - 60.0, "a rounded cap takes a little: ${Geom3.volume(mCap)} of $before")
        // then the tip's upright: a ball patch closes the three bands at a 19° vertex
        val all = blendOn(cx, cap, r, false, uprightAt(cap, tip))
        val mAll = mesh(all)
        noVertexAt(mAll, tip, "the tip after the upright's own rounding")
        val theta = interior(dart, 3)
        val wedge = filletWedge(r, theta) * h
        val taken = Geom3.volume(mCap) - Geom3.volume(mAll)
        // the upright's own wedge runs the height less the cap band, and the vertex takes a little more
        assertTrue(taken > wedge * (h - r) / h * 0.98 && taken < wedge * 1.05, "the tip's rounding takes about its wedge: $taken vs $wedge")
    }
}
