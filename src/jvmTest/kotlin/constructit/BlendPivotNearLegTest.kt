package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.Construction
import constructit.dsl.LoftPart
import constructit.dsl.RegionRef
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.geom.Blend3
import constructit.geom.BlendKind
import constructit.geom.BlendSection
import constructit.geom.FaceName
import constructit.geom.Feature3
import constructit.geom.Geom3
import constructit.geom.MeshBool
import constructit.geom.MeshCanon
import constructit.geom.Section3
import constructit.geom.Solid3
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.units.Dimension
import constructit.units.Quantity
import constructit.units.mm
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.math.PI
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **A pivot's near leg clears the wall it walks by the step it states** (OP-31, slice 5x; GitHub #36).
 *
 * A pivot's second contact is the body's own *upright*, not a tangency, so the wall its near leg walks leans
 * out of the station plane — at a revolve's ring, `|n·t|` climbs from 0 at the first station to 0.997 at the
 * hand-over — and a step taken in the plane clears that wall by only `step·|flat(n)|`. On the ring pivot of
 * `r = 1.5` that was **1.4 µm of a stated 80**, and the from-source engine named the result: a zero-thickness
 * flap 18 µm inside the body's own cylinder of radius 20, the last verdict that engine had left. The section
 * now leans along the spine by what the plane falls short of, so the leg stands the stated step off the wall
 * along the wall's **true** normal at every ring.
 *
 * Each claim is measured on the tool's own vertices ([Blend3.cornerNearLegClearances]), against the exact
 * wall (a plane's or a cylinder's own signed distance), at every ring of the walk — and on a second ring of
 * another radius, ball and sweep, and on a loft whose upright is slanted, so the cure is not one fixture's.
 */
class BlendPivotNearLegTest {
    private var ids = 0

    /**
     * How far below the stated step a vertex may stand: the arithmetic of a lean read off a wall's gradient
     * against that wall's own signed distance — a part in 10⁶ of the 80 µm step, and five decades below the
     * 18 µm the kernel's flap stood at.
     */
    private val tol = 1e-7

    private class Ring(val ring: Double, val step: Double, val sweep: Double, val r: Double)

    /** The ring the from-source engine refused, the radius it built, and a ring of another size, ball and sweep. */
    private val rings = listOf(Ring(20.0, 10.0, 90.0, 1.5), Ring(20.0, 10.0, 90.0, 2.0), Ring(12.0, 6.0, 140.0, 2.5))

    private fun polygon(
        cx: Construction,
        pts: List<Vec2>,
    ): RegionRef {
        val ps = pts.map { cx.freePoint("N${ids++}", it.x.mm, it.y.mm) }
        return cx.region(cx.loop(*ps.indices.map { cx.segment(ps[it], ps[(it + 1) % ps.size]) }.toTypedArray()))
    }

    /** An L meridian turned about `x` — its start cap's reflex corner stands on a ring of radius `ring` at `x = step`. */
    private fun turned(
        cx: Construction,
        g: Ring,
    ): SolidRef {
        val inner = g.ring / 2.0
        val prof =
            listOf(Vec2(0.0, inner), Vec2(0.0, g.ring + 10.0), Vec2(g.step, g.ring + 10.0), Vec2(g.step, g.ring), Vec2(2.0 * g.step, g.ring), Vec2(2.0 * g.step, inner))
        val o = cx.freePoint("No${ids++}", 0.mm, 0.mm)
        val axis = cx.direction(o, cx.freePoint("Nx${ids++}", 1.mm, 0.mm))
        return cx.revolve(cx.sketchOn(cx.planeXY(), polygon(cx, prof)), o, axis, cx.const(Quantity(g.sweep * PI / 180.0, Dimension.ANGLE)))
    }

    /** The two edges meeting at [at] on the face [face] names, rounded at [r] in one gesture. */
    private fun pivot(
        cx: Construction,
        base: SolidRef,
        at: Vec3,
        face: String,
        r: Double,
    ): Solid3 {
        val body = Evaluator().solid(base)
        val es = assertNotNull(Section3.edges(body.feature).first, "the body names its edges")
        val pair =
            es.indices.filter { i ->
                val e = es[i]
                if (!e.between.a.label.render().contains(face) && !e.between.b.label.render().contains(face)) return@filter false
                val path = Blend3.edgePath(e).first ?: return@filter false
                val s = path.start ?: return@filter false
                val t = path.end ?: return@filter false
                (s - at).length() < 1e-6 || (t - at).length() < 1e-6
            }
        assertEquals(2, pair.size, "the reflex pair at $at")
        val (choices, why) = Blend3.choicesFor(body, pair, BlendSection(BlendKind.FILLET, r))
        val cs = assertNotNull(choices, "the pair at $at is scored: ${why?.render()}")
        val ref = cx.blendAll(base, cx.planeXY(), listOf(Construction.BlendRun(BlendKind.FILLET, cx.const(r.mm), null, pair, cs)))
        val res = Evaluator().eval(ref.node)
        assertTrue(res is EvalResult.Ok, "the pivot at $at of r = $r builds: ${(res as? EvalResult.Invalid)?.why?.render()}")
        val b = Evaluator().solid(ref)
        assertManifold(b.mesh, "the pivot at $at of r = $r")
        assertEquals(1, assertNotNull(Section3.faces(b.feature).first).count { it.name is FaceName.BlendCorner }, "one corner face")
        return b
    }

    private fun ringPivot(g: Ring): Solid3 {
        ids = 0
        val cx = Construction()
        return pivot(cx, turned(cx, g), Vec3(g.step, g.ring, 0.0), "cap at the start", g.r)
    }

    /** Every ring of [body]'s one pivot, its near leg asserted against the step it states; the steepest `|n·t|`. */
    private fun assertClears(
        body: Solid3,
        what: String,
    ): Double {
        val turns = Blend3.cornerNearLegClearances(body.feature as Feature3.Blend)
        assertEquals(1, turns.size, "$what: one pivot")
        val walk = turns[0]
        assertTrue(walk.size >= 16, "$what: a walk of stations, not ${walk.size}")
        for (c in walk) {
            assertTrue(
                c.least >= c.stated - tol,
                "$what, ring ${c.station} (|n·t| = ${c.slant}): the near leg stands ${c.least} mm off the wall it walks, not the ${c.stated} mm it states",
            )
        }
        val steepest = walk.maxOf { it.slant }
        println("near leg | $what | ${walk.size} rings | least ${walk.minOf { it.least }} of ${walk[0].stated} | steepest |n·t| $steepest")
        return steepest
    }

    /**
     * **The ring pivot the from-source engine refused**, at the radius it refused (`r = 1.5`) and the one it
     * built (`r = 2`): every ring's near leg stands the stated 80 µm off its wall, and the walk really does
     * lean — `|n·t|` passes 0.99, where a step in the plane alone would clear the wall by a seventh of itself.
     */
    @Test
    fun theRingPivotsNearLegClearsItsWallAtEveryRing() {
        for (g in rings.take(2)) {
            val steepest = assertClears(ringPivot(g), "the ring pivot of R = ${g.ring}, r = ${g.r}")
            assertTrue(steepest > 0.99, "r = ${g.r}: the near wall leans almost wholly out of the station plane, $steepest")
            assertTrue(sqrt(1.0 - steepest * steepest) < 1.0 / 7.0, "r = ${g.r}: …so the plane alone would have cleared a seventh of the step")
        }
    }

    /**
     * **A ring of another radius, ball and sweep** — the same class (a cylinder walked by a leg whose station
     * plane turns until it is nearly tangent to it) at numbers the old reading was never shown: `R = 12`,
     * `r = 2.5`, a 140° turn, the step at `x = 6`.
     */
    @Test
    fun aSecondRingOfAnotherRadiusClearsToo() {
        val g = rings[2]
        val steepest = assertClears(ringPivot(g), "the ring pivot of R = ${g.ring}, r = ${g.r}")
        assertTrue(steepest > 0.9, "the second ring's near wall leans out of the plane as well, $steepest")
    }

    /**
     * **A slanted upright between two plane walls** — a loft whose cap turns a reflex L and whose sides lean
     * 35°, so the near wall is a *plane* leaning out of the station plane and the hand-over falls mid-walk.
     * The leg clears the plane it walks by the stated step at every ring, on both sides of the hand-over.
     */
    @Test
    fun aSlantedLoftsNearLegClearsItsPlane() {
        ids = 0
        val cx = Construction()
        val h = 20.0
        val lo = listOf(Vec2(0.0, 0.0), Vec2(60.0, 0.0), Vec2(60.0, 30.0), Vec2(30.0, 30.0), Vec2(30.0, 60.0), Vec2(0.0, 60.0))
        val scale = 1.0 - h * tan(35.0 * PI / 180.0) / sqrt(2.0) / 30.0
        val loft =
            cx.loft(
                listOf(
                    LoftPart.Area(cx.sketchOn(cx.planeXY(), polygon(cx, lo))),
                    LoftPart.Area(cx.sketchOn(cx.planeOffset(cx.planeXY(), cx.const(h.mm)), polygon(cx, lo.map { it * scale }))),
                ),
            )
        val body = pivot(cx, loft, Vec3(30.0 * scale, 30.0 * scale, h), "section 2's own face", 3.0)
        val steepest = assertClears(body, "the slanted loft's pivot")
        assertTrue(steepest > 0.5, "the slanted walls lean out of the station plane, $steepest")
    }

    /** **The leaning tool is a closed shell** at every ring of the class, asked of the tool itself before any body sees it. */
    @Test
    fun theLeaningToolIsAClosedShell() {
        for (g in rings) {
            val tools = Blend3.cornerToolMeshes(ringPivot(g).feature as Feature3.Blend)
            assertEquals(1, tools.size, "R = ${g.ring}, r = ${g.r}: one pivot tool")
            assertNull(MeshCanon.notClosed(tools[0]), "R = ${g.ring}, r = ${g.r}: the pivot's tool is closed")
            assertTrue(Geom3.volume(tools[0]) > 0.0, "R = ${g.ring}, r = ${g.r}: …and encloses material")
        }
    }

    /**
     * **The from-source engine's last verdict is gone** (OP-31's (5u)). Skipped unless that engine is what
     * [MeshBool] loaded (`-Dconstructit.manifold.native=<dir>`): under it the ring pivot of `r = 1.5` came
     * back as *"a zero-thickness flap at the edge between (10.002, 19.953, 1.071) mm and (10.003, 19.953,
     * 1.071) mm"*, and now every ring of the class builds a manifold body with its one corner face.
     */
    @Test
    fun theFromSourceEngineBuildsEveryRingOfTheClass() {
        assumeTrue(MeshBool.available && MeshBool.isNative, "not the from-source engine (-Dconstructit.manifold.native=<dir>): ${MeshBool.status}")
        for (g in rings) ringPivot(g)
    }
}
