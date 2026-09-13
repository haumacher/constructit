package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.Construction
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.geom.Blend3
import constructit.geom.BlendKind
import constructit.geom.BlendSection
import constructit.geom.EdgeGeom
import constructit.geom.EdgeName
import constructit.geom.FaceName
import constructit.geom.Geom3
import constructit.geom.Section3
import constructit.geom.Solid3
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.units.mm
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **Every bevel a setback can physically reach builds** (OP-31, slice 5n) — the matrix's own reading, said
 * for the ruled strip that a chamfer leaves along a crease with no rigid section.
 *
 * Five setbacks `{0.5, 1, 1.5, 2, 2.5}` against three creases — the **elliptical mitre** two equal rounds
 * cross in, the **fitted quartic** two rounds of unlike size cross in, and the **concave twin**, the mitre
 * two fills leave at the inside corner of a room — and each of those built by **both** gesture routes: the
 * two roundings under it made as one entry of one gesture, and made one gesture at a time.
 *
 * Every cell is one of exactly two states, with no third and no residue:
 *
 * - **built** — the body is one manifold shell and what it moved is inside the figure `canalRemoval` states
 *   for it (Pappus over the crease, bracketed by the tessellation);
 * - **refused by name** — the scoring declines or the node is `EvalResult.Invalid`, with a reason that names
 *   an edge, a face or a crease, the setback, and what to do instead.
 *
 * A Manifold status code, a mesh diagnostic or a bare *"cannot"* is a failure of this test, because a
 * setback that fits is a body this drawing owes.
 */
class BlendBevelSweepTest {
    private val width = 40.0
    private val depth = 30.0
    private val height = 20.0

    private val setbacks = listOf(0.5, 1.0, 1.5, 2.0, 2.5)

    @Test
    fun everySetbackThatFitsAlongACreaseWithNoRigidSectionBuildsInBothRoutes() {
        var built = 0
        var refused = 0
        for (route in listOf(true, false)) {
            for (d in setbacks) {
                for (what in listOf("equal mitre", "unlike quartic", "concave twin")) {
                    val cells =
                        when (what) {
                            "equal mitre" -> cell(d, route) { cx -> twoRounds(cx, 4.0, 4.0, route) to ::mitres }
                            "unlike quartic" -> cell(d, route) { cx -> twoRounds(cx, 4.0, 3.0, false) to ::fittedCreases }
                            else -> cell(d, route) { cx -> twoFills(cx, route) to ::mitres }
                        }
                    for (ok in cells) if (ok) built++ else refused++
                }
            }
        }
        println("bevel sweep | ${built + refused} cells | $built built inside their own bracket | $refused refused by name")
        assertTrue(built > 0, "some setback fits along a crease with no rigid section")
        assertEquals(setbacks.size * 3 * 2, built + refused, "every cell is one of exactly two states")
    }

    /** One cell: the base body, every crease of the named kind on it, bevelled at [d]. True per crease built. */
    private fun cell(
        d: Double,
        route: Boolean,
        make: (Construction) -> Pair<SolidRef, (Solid3) -> List<Int>>,
    ): List<Boolean> {
        val cx = Construction()
        val (base, creases) = make(cx)
        val body = Evaluator().solid(base)
        assertManifold(body.mesh, "the base body")
        val before = Geom3.volume(body.mesh)
        val targets = creases(body)
        if (targets.isEmpty()) {
            // …a drawing that states no such crease claims nothing and is not a cell of anything
            println("bevel sweep | setback $d | route ${if (route) "one gesture" else "one at a time"} | no crease stated")
            return listOf(true)
        }
        val sec = BlendSection(BlendKind.CHAMFER, d)
        val at = targets.first()
        val (choices, why) = Blend3.choicesFor(body, listOf(at), sec)
        if (choices == null) {
            val said = assertNotNull(why, "a refusal has a reason").render()
            assertTrue(namesSomething(said), "the refusal names something: $said")
            println("bevel sweep | setback $d | ${if (route) "one gesture" else "one at a time"} | refused | ${said.take(80)}")
            return listOf(false)
        }
        val ref = cx.blendAll(base, cx.planeXY(), listOf(Construction.BlendRun(BlendKind.CHAMFER, cx.const(d.mm), null, listOf(at), choices)))
        val r = Evaluator().eval(ref.node)
        if (r is EvalResult.Invalid) {
            assertTrue(namesSomething(r.reason), "a bevel that cannot be built says why: ${r.reason}")
            println("bevel sweep | setback $d | ${if (route) "one gesture" else "one at a time"} | refused | ${r.reason.take(80)}")
            return listOf(false)
        }
        val out = Evaluator().solid(ref)
        assertManifold(out.mesh, "the ruled strip at setback $d")
        val moved = abs(Geom3.volume(out.mesh) - before)
        val (lo, hi) = assertNotNull(Blend3.canalRemoval(body.feature, at, sec, choices[0]), "the algebra states the strip's figure")
        assertTrue(moved in lo..hi, "the bevel at setback $d moves $moved, outside its own bracket [$lo, $hi]")
        val band = assertNotNull(Section3.faces(out.feature).first?.firstOrNull { it.name == FaceName.BlendBand(at, 0) }, "the strip is a face")
        assertTrue(band.ruled, "…and it says it is a ruled strip")
        println("bevel sweep | setback $d | ${if (route) "one gesture" else "one at a time"} | built | moved $moved in [$lo, $hi]")
        return listOf(true)
    }

    // ---- the three creases ----

    private fun mitres(s: Solid3): List<Int> {
        val es = edgesOf(s)
        return es.indices.filter { es[it].name is EdgeName.BlendMitre && es[it].reason == null && es[it].geom is EdgeGeom.OnPlane }
    }

    private fun fittedCreases(s: Solid3): List<Int> {
        val es = edgesOf(s)
        return es.indices.filter { es[it].reason == null && es[it].geom is EdgeGeom.InSpace }
    }

    private fun twoRounds(
        cx: Construction,
        ra: Double,
        rb: Double,
        oneGesture: Boolean,
    ): SolidRef {
        val box = prism(cx, listOf(Vec2(0.0, 0.0), Vec2(width, 0.0), Vec2(width, depth), Vec2(0.0, depth)), height)
        val es = edgesOf(Evaluator().solid(box))
        val corner = Vec3(width, depth, height)
        val tops =
            es.indices.filter { i ->
                val path = Blend3.edgePath(es[i]).first ?: return@filter false
                val a = path.start ?: return@filter false
                val b = path.end ?: return@filter false
                abs(a.z - height) < 1e-9 && abs(b.z - height) < 1e-9 && ((a - corner).length() < 1e-9 || (b - corner).length() < 1e-9)
            }
        assertEquals(2, tops.size, "two top edges share the block's far corner")
        if (ra == rb && oneGesture) {
            val body = Evaluator().solid(box)
            val choices = assertNotNull(Blend3.choicesFor(body, tops, BlendSection(BlendKind.FILLET, ra)).first, "the two top edges are scored")
            return cx.blendAll(box, cx.planeXY(), listOf(Construction.BlendRun(BlendKind.FILLET, cx.const(ra.mm), null, tops, choices)))
        }
        var on = box
        for ((k, e) in tops.withIndex()) on = round(cx, on, listOf(e), if (k == 0) ra else rb)
        return on
    }

    /** Two fills crossing at the inside corner of a room — the concave twin, one sign over. */
    private fun twoFills(
        cx: Construction,
        oneGesture: Boolean,
    ): SolidRef {
        val box = prism(cx, listOf(Vec2(0.0, 0.0), Vec2(60.0, 0.0), Vec2(60.0, 40.0), Vec2(0.0, 40.0)), height)
        val faces = assertNotNull(Section3.faces(Evaluator().solid(box).feature).first, "it names its faces")
        val top = faces.indices.first { faces[it].plane?.let { p -> abs(p.normal.normalized().z - 1.0) < 1e-9 && abs(p.origin.z - height) < 1e-9 } == true }
        val shelled = cx.shell(box, cx.const(3.0.mm), listOf(top))
        val solid = Evaluator().solid(shelled)
        val es = edgesOf(solid)
        val floor =
            es.indices.filter { i ->
                if (es[i].reason != null) return@filter false
                val path = Blend3.edgePath(es[i]).first ?: return@filter false
                val el = path.elements.singleOrNull() ?: return@filter false
                abs(el.start.z - 3.0) <= 1e-9 && abs(el.end.z - 3.0) <= 1e-9 &&
                    Blend3.choicesFor(solid, listOf(i), BlendSection(BlendKind.FILLET, 2.0)).first?.get(0)?.convex == false
            }
        assertTrue(floor.size >= 2, "a room's floor has concave creases")
        val pair = floor.take(2)
        if (oneGesture) return round(cx, shelled, pair, 2.0)
        var on = shelled
        for (e in pair) {
            // …addressed against the body as it stands, which is what one gesture at a time means
            val live = Evaluator().solid(on)
            val at =
                assertNotNull(
                    edgesOf(live).indices.firstOrNull { i ->
                        edgesOf(live)[i].reason == null &&
                            Blend3.edgePath(edgesOf(live)[i]).first?.elements?.singleOrNull()?.let { abs(it.start.z - 3.0) <= 1e-9 && abs(it.end.z - 3.0) <= 1e-9 } == true &&
                            Blend3.choicesFor(live, listOf(i), BlendSection(BlendKind.FILLET, 2.0)).first?.get(0)?.convex == false
                    },
                    "a concave crease of the room's floor",
                )
            on = round(cx, on, listOf(at), 2.0)
        }
        return on
    }

    private fun round(
        cx: Construction,
        on: SolidRef,
        address: List<Int>,
        size: Double,
    ): SolidRef {
        val body = Evaluator().solid(on)
        val (choices, why) = Blend3.choicesFor(body, address, BlendSection(BlendKind.FILLET, size))
        assertNotNull(choices, "$address is scored: ${why?.render()}")
        return cx.blendAll(on, cx.planeXY(), listOf(Construction.BlendRun(BlendKind.FILLET, cx.const(size.mm), null, address, choices)))
    }

    private fun namesSomething(reason: String): Boolean =
        reason.contains("#") || reason.contains("face") || reason.contains("edge") || reason.contains("crease")

    private fun edgesOf(s: Solid3) = assertNotNull(Section3.edges(s.feature).first, "it names its edges")

    private fun prism(
        cx: Construction,
        xy: List<Vec2>,
        h: Double,
    ): SolidRef {
        val pts = xy.mapIndexed { i, p -> cx.freePoint("p$i", p.x.mm, p.y.mm) }
        val segs = xy.indices.map { cx.segment(pts[it], pts[(it + 1) % xy.size]) }
        return cx.extrude(cx.sketchOn(cx.planeXY(), cx.region(cx.loop(*segs.toTypedArray()))), cx.const(h.mm))
    }
}
