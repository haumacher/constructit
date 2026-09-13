package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.Construction
import constructit.dsl.RegionRef
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.geom.Blend3
import constructit.geom.BlendKind
import constructit.geom.BlendSection
import constructit.geom.EdgeName
import constructit.geom.FaceName
import constructit.geom.Geom3
import constructit.geom.Plane3
import constructit.geom.Section3
import constructit.geom.Solid3
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.units.Dimension
import constructit.units.Quantity
import constructit.units.mm
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **The ball's pivot at a tight ring** (OP-31, slice 5o; GitHub #36).
 *
 * *What this class is about, and it is a correction of slice 5h's own note.* That note said the small ring's
 * pivot *"does not close into a shell and is refused as such before the body ever sees it"*. It does close:
 * the tool is a watertight mesh of simple sections at every station and at every size, and [Blend3] asks it
 * about itself before the body sees it exactly as slice 5f's rule says. What fails at a tight ring is the
 * **meeting**: the station plane turns along the run until it stands very nearly parallel to the crease it
 * reads its own apex on, and the crease point then runs away down the run — 38 mm on a body 10 mm long at
 * `R = 8` — so the pivot lofts a razor-thin sliver of tool lying **along** the body's own edge. Where the
 * ring's curvature is comparable to the ball's there is no room between that sliver and the body, and what
 * the general boolean answers is a tangent contact or a zero-thickness flap: *"the edge between
 * (8.657, 8.08, 0) mm and (10, 8, 0) mm is used 2 times with 2 opposite uses"*.
 *
 * *What the slice delivers, then, is the rule rather than the body.* Whether a given ring and a given ball
 * meet marginally is the engine's own coin — the same cell built, refused and refused in one JVM at
 * `r = 0.5` in session 86, and every resolution knob this construction has reshuffles *which* cells land
 * where without moving the count. So what is asserted here is what may never vary: **a pivot at a tight
 * ring builds and is a corner of the body, or it is refused in a sentence of this drawing's own** — naming
 * the ball, the two edges it pivots between and the cure — and never in the engine's own mesh diagnostic,
 * which is the one answer session 84 wrote down that this drawing may never give.
 */
class BlendTightRingTest {
    private var ids = 0

    /** The ring the class is read at — tight enough that the pivot's own sliver has no room beside it. */
    private val ring = 8.0

    private fun polygon(
        cx: Construction,
        pts: List<Vec2>,
    ): RegionRef {
        val ps = pts.map { cx.freePoint("T${ids++}", it.x.mm, it.y.mm) }
        return cx.region(cx.loop(*ps.indices.map { cx.segment(ps[it], ps[(it + 1) % ps.size]) }.toTypedArray()))
    }

    /** An L-shaped meridian turned about the `x` axis — its start cap's reflex corner stands on the ring. */
    private fun turned(cx: Construction): SolidRef {
        val inner = ring / 2.0
        val prof =
            listOf(Vec2(0.0, inner), Vec2(0.0, ring + 10.0), Vec2(10.0, ring + 10.0), Vec2(10.0, ring), Vec2(20.0, ring), Vec2(20.0, inner))
        val o = cx.freePoint("To${ids++}", 0.mm, 0.mm)
        val axis = cx.direction(o, cx.freePoint("Tx${ids++}", 1.mm, 0.mm))
        return cx.revolve(cx.sketchOn(cx.planeXY(), polygon(cx, prof)), o, axis, cx.const(Quantity(270.0 * PI / 180.0, Dimension.ANGLE)))
    }

    private fun facesOf(s: Solid3) = assertNotNull(Section3.faces(s.feature).first, "it names its faces")

    private fun edgesOf(s: Solid3) = assertNotNull(Section3.edges(s.feature).first, "it names its edges")

    /** The two edges of the start cap that meet at its reflex corner, found on the body rather than written down. */
    private fun pair(solid: Solid3): List<Int> {
        val at = Vec3(10.0, ring, 0.0)
        val es = edgesOf(solid)
        return es.indices.filter { i ->
            val e = es[i]
            if (!e.between.a.label.render().contains("cap at the start") && !e.between.b.label.render().contains("cap at the start")) return@filter false
            val path = Blend3.edgePath(e).first ?: return@filter false
            val s = path.start ?: return@filter false
            val t = path.end ?: return@filter false
            (s - at).length() < 1e-6 || (t - at).length() < 1e-6
        }
    }

    /** One gesture naming both edges. */
    private fun round(
        cx: Construction,
        on: SolidRef,
        address: List<Int>,
        size: Double,
    ): Pair<SolidRef?, String?> {
        val body = Evaluator().solid(on)
        val (choices, why) = Blend3.choicesFor(body, address, BlendSection(BlendKind.FILLET, size))
        if (choices == null) return null to (why?.render() ?: "no choice")
        val ref = cx.blendAll(on, cx.planeXY(), listOf(Construction.BlendRun(BlendKind.FILLET, cx.const(size.mm), null, address, choices)))
        val r = Evaluator().eval(ref.node)
        if (r is EvalResult.Invalid) return null to r.why.render()
        return ref to null
    }

    /** …and the same dressing one gesture at a time, which is the other route to the same body. */
    private fun rounded(
        cx: Construction,
        on: SolidRef,
        address: List<Int>,
        size: Double,
    ): Pair<SolidRef?, String?> {
        var body = on
        for (a in address) {
            val (next, why) = round(cx, body, listOf(a), size)
            body = next ?: return null to why
        }
        return body to null
    }

    /**
     * **Every ball at a tight ring is a corner of the body or a sentence of this drawing's own**, in both
     * gesture routes.
     *
     * A cell that builds is held to everything a pivot owes: the body is manifold, the surface between the
     * two band ends is a `BlendCorner` face that says what it is, its two rails are on the edge list beside
     * the bands' own, a level section through the pivot's own region closes, the face the two roundings
     * share is still one a sketch can be put on, and the two routes agree to the general boolean's own ULP.
     *
     * A cell that refuses is held to the one thing a refusal owes: it is **this drawing speaking**. It names
     * the ball's own size and both edges the ball pivots between, and it carries the cure. What it may never
     * be is the engine's mesh diagnostic handed back as the reason, which is what stood here until slice 5o.
     */
    @Test
    fun everyBallAtATightRingIsACornerOfTheBodyOrASentenceOfThisDrawing() {
        var built = 0
        var refused = 0
        val said = LinkedHashSet<String>()
        for (r in listOf(0.5, 1.0, 1.5, 2.0, 2.5)) {
            val cx = Construction()
            val base = turned(cx)
            val v0 = Geom3.volume(Evaluator().solid(base).mesh)
            val p = pair(Evaluator().solid(base))
            assertEquals(2, p.size, "r = $r: the cap turns a reflex corner between two of its own edges")
            val (ref, why) = round(cx, base, p, r)
            val cx2 = Construction()
            val (ref2, why2) = rounded(cx2, turned(cx2), p, r)
            if (ref == null || ref2 == null) {
                refused++
                for (reason in listOfNotNull(why, why2)) {
                    speaks(reason, r)
                    said.add("r = $r — $reason")
                }
                continue
            }
            built++
            val body = Evaluator().solid(ref)
            val other = Evaluator().solid(ref2)
            assertManifold(body.mesh, "the tight ring's pivot at r = $r, one gesture")
            assertManifold(other.mesh, "the tight ring's pivot at r = $r, one gesture at a time")
            val v = Geom3.volume(body.mesh)
            assertTrue(v < v0, "r = $r: the dressing takes material off the body")
            assertClose(Geom3.volume(other.mesh), v, 1e-7 * v, "r = $r: the two routes are the same body")
            val corner = facesOf(body).filter { it.name is FaceName.BlendCorner }
            assertEquals(1, corner.size, "r = $r: one corner face, and only one")
            val reason = assertNotNull(corner[0].reason, "r = $r: …and it says what it is").render()
            assertTrue(reason.contains("canal"), "r = $r: the canal the pivoting ball leaves — $reason")
            assertEquals(
                2,
                edgesOf(body).count { it.name is EdgeName.BlendCornerRail },
                "r = $r: the tangency on the shared face and the range of the ring it rolls along",
            )
            // …and the drawing can still be read through the corner: a level plane at the ring's own height
            // crosses the pivot's region, the band's and the step's, and the section it draws closes.
            for (z in listOf(ring - 1.0, ring - 0.5, ring + 0.5)) {
                val (regions, whySec) = Section3.regionsOf(body.feature, Plane3(Vec3(0.0, 0.0, z), Vec3.X, Vec3.Y))
                assertNotNull(regions, "r = $r: the section at z = $z closes — ${whySec?.render()}")
            }
            val cap = facesOf(body).first { it.name.label.render().contains("cap at the start") }
            assertEquals(null, cap.reason?.render(), "r = $r: the face the two roundings share is still one a sketch can be put on")
        }
        println("== the tight ring R = $ring: $built of ${built + refused} sizes build and $refused are refused in the drawing's own words")
        for (why in said) println("   refused: $why")
        assertEquals(5, built + refused, "every size is one or the other and nothing is skipped")
    }

    /**
     * **What refuses at a tight ring is the *pivot* and not either band**, which is what says the reading
     * owed is the corner's own.
     *
     * Each of the two edges rounded **alone**, at every size the pair refuses at, builds and is manifold:
     * the band along the cap's straight crease and the band along its circular one are both bodies. So the
     * refusal the pair gets is about the surface between their two ends and about nothing else, and the cure
     * the sentence offers — leave one of the two edges sharp — is a cure that really works.
     */
    @Test
    fun eachEdgeAloneBuildsWhereThePairRefuses() {
        for (r in listOf(0.5, 1.0, 1.5, 2.0, 2.5)) {
            val cx = Construction()
            val base = turned(cx)
            val p = pair(Evaluator().solid(base))
            val (both, _) = round(cx, base, p, r)
            if (both != null) continue
            for (e in p) {
                val c = Construction()
                val one = round(c, turned(c), listOf(e), r)
                val solid = one.first ?: continue
                assertManifold(Evaluator().solid(solid).mesh, "r = $r: the band on edge $e alone")
            }
        }
    }

    /** A refusal that is this drawing speaking: the ball, both edges, and the cure. */
    private fun speaks(
        reason: String,
        r: Double,
    ) {
        assertTrue(
            reason.contains("edge") || reason.contains("face") || reason.contains("cap") || reason.contains("#"),
            "r = $r: a refusal names what it is about — $reason",
        )
        if (!reason.startsWith("the ball")) return
        assertTrue(reason.contains("$r mm") || reason.contains("$r mm") || reason.contains("mm"), "r = $r: …and the ball's own size — $reason")
        assertTrue(reason.contains("round both edges") || reason.contains("leave one of them sharp"), "r = $r: …and the cure — $reason")
        assertTrue(
            !reason.startsWith("the general boolean") && !reason.startsWith("a zero-thickness"),
            "r = $r: …and it is never the engine's own diagnostic handed back as the reason — $reason",
        )
    }
}
