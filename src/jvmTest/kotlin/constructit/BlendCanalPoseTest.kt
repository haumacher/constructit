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
import constructit.geom.EdgeName
import constructit.geom.Geom3
import constructit.geom.Section3
import constructit.geom.Solid3
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.units.Dimension
import constructit.units.Quantity
import constructit.units.mm
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **A rounding is what it is wherever the drawing was sketched** (OP-31, slice 5h's second probe; GitHub #36).
 *
 * The tool a rounding is cut with is built in the **crease's own frame** — the section in the plane square to
 * the run, the step-offs along that section's own normals, the caps on the planes the run ends in — so a rigid
 * motion of the whole construction must carry the tool with it and change nothing else. It did not: a band
 * along a straight cap edge of a revolve built on `XY` and folded on a plane turned 30° about `y`, and a canal
 * corner built at one pose and refused at another, because three things in the shared tool path were **exact**
 * in an axis-aligned pose and rounding noise in any other — a section swept with no end-step at all, a cap
 * placed exactly in a face of the body, and two solvers whose walk stopped at a thousand times the tolerance
 * their answer was accepted at.
 *
 * This is the class that holds the whole tool path to it, in the five poses below and for each of the three
 * kinds of tool the drawing builds: the **ordinary band** (a rigid section swept along a straight run), the
 * **canal band** of slice 5f (a section that changes along an elliptical mitre) and the **pivot** of slice 5h.
 * Poses are the identity, a shift, two turns and a turn with a shift — a shift is harmless to any arithmetic
 * and is here to say so, and the turns are what a coordinate that was exactly zero becomes.
 */
class BlendCanalPoseTest {
    private var ids = 0
    private val width = 40.0
    private val depth = 30.0
    private val height = 20.0
    private val ring = 20.0
    private val inner = 10.0

    private class Pose(val what: String, val origin: Vec3, val u: Vec3, val v: Vec3)

    private val poses =
        listOf(
            Pose("XY", Vec3.ZERO, Vec3.X, Vec3.Y),
            Pose("shifted YZ", Vec3(17.0, -9.0, 4.0), Vec3.Y, Vec3.Z),
            Pose("turned 30° about x", Vec3.ZERO, Vec3.X, Vec3(0.0, cos(PI / 6), sin(PI / 6))),
            Pose("turned 30° about y", Vec3.ZERO, Vec3(cos(PI / 6), 0.0, -sin(PI / 6)), Vec3.Y),
            Pose("turned 45° about z and shifted", Vec3(-3.0, 11.0, -7.0), Vec3(1.0, 1.0, 0.0) * (1.0 / sqrt(2.0)), Vec3.Z),
        )

    // ---- fixtures, each sketched on the pose's own plane ----

    private fun prismOn(
        pose: Pose,
        cx: Construction,
        xy: List<Vec2>,
        h: Double,
    ): SolidRef {
        val pts = xy.mapIndexed { i, p -> cx.freePoint("p${ids++}_$i", p.x.mm, p.y.mm) }
        val segs = xy.indices.map { cx.segment(pts[it], pts[(it + 1) % xy.size]) }
        return cx.extrude(cx.sketchOn(cx.plane(pose.origin, pose.u, pose.v), cx.region(cx.loop(*segs.toTypedArray()))), cx.const(h.mm))
    }

    /** The 35° loft of two L-sections, sketched on the pose's own plane and on that plane offset — the slanted upright. */
    private fun loftedOn(
        pose: Pose,
        cx: Construction,
        mirrored: Boolean,
    ): SolidRef {
        val lo = listOf(Vec2(0.0, 0.0), Vec2(60.0, 0.0), Vec2(60.0, 30.0), Vec2(30.0, 30.0), Vec2(30.0, 60.0), Vec2(0.0, 60.0))
        val shift = height * kotlin.math.tan(35.0 * PI / 180.0) / sqrt(2.0)
        val scale = 1.0 - shift / 30.0

        fun mirror(pts: List<Vec2>): List<Vec2> = if (mirrored) pts.map { Vec2(60.0 - it.x, it.y) }.reversed() else pts

        fun area(pts: List<Vec2>): RegionRef {
            val ps = pts.mapIndexed { i, p -> cx.freePoint("F${ids++}_$i", p.x.mm, p.y.mm) }
            return cx.region(cx.loop(*pts.indices.map { cx.segment(ps[it], ps[(it + 1) % pts.size]) }.toTypedArray()))
        }
        val plane = cx.plane(pose.origin, pose.u, pose.v)
        return cx.loft(
            listOf(
                LoftPart.Area(cx.sketchOn(plane, area(mirror(lo)))),
                LoftPart.Area(cx.sketchOn(cx.planeOffset(plane, cx.const(height.mm)), area(mirror(lo.map { it * scale })))),
            ),
        )
    }

    /** The L-meridian revolved about the sketch's own `x` axis — the ring upright's own fixture. */
    private fun turnedOn(
        pose: Pose,
        cx: Construction,
        sweep: Double,
        ring: Double = this.ring,
    ): SolidRef {
        val prof =
            listOf(Vec2(0.0, ring / 2.0), Vec2(0.0, ring + 10.0), Vec2(10.0, ring + 10.0), Vec2(10.0, ring), Vec2(20.0, ring), Vec2(20.0, ring / 2.0))
        val pts = prof.mapIndexed { i, p -> cx.freePoint("L${ids++}_$i", p.x.mm, p.y.mm) }
        val segs = prof.indices.map { cx.segment(pts[it], pts[(it + 1) % prof.size]) }
        val o = cx.freePoint("Ro${ids++}", 0.mm, 0.mm)
        val axis = cx.direction(o, cx.freePoint("Rx${ids++}", 1.mm, 0.mm))
        val sketch = cx.sketchOn(cx.plane(pose.origin, pose.u, pose.v), cx.region(cx.loop(*segs.toTypedArray())))
        return cx.revolve(sketch, o, axis, cx.const(Quantity(sweep * PI / 180.0, Dimension.ANGLE)))
    }

    /** The pose's own frame, orthonormal — what a sketch point of the fixture lands at in space. */
    private fun frame(pose: Pose): Triple<Vec3, Vec3, Vec3> {
        val uu = pose.u.normalized()
        val vv = (pose.v - uu * pose.v.dot(uu)).normalized()
        return Triple(uu, vv, uu.cross(vv))
    }

    private fun at(
        pose: Pose,
        x: Double,
        y: Double,
        z: Double,
    ): Vec3 {
        val (uu, vv, n) = frame(pose)
        return pose.origin + uu * x + vv * y + n * z
    }

    private fun edgesOf(s: Solid3) = assertNotNull(Section3.edges(s.feature).first, "it names its edges")

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

    /** The edges that meet at [v] and lie on the face named by [face]. */
    private fun edgesAt(
        solid: Solid3,
        v: Vec3,
    ): List<Int> {
        val es = edgesOf(solid)
        return es.indices.filter { i ->
            if (es[i].reason != null) return@filter false
            val path = Blend3.edgePath(es[i]).first ?: return@filter false
            val a = path.start ?: return@filter false
            val b = path.end ?: return@filter false
            (a - v).length() < 1e-6 || (b - v).length() < 1e-6
        }
    }

    /**
     * The same claim in every pose: [taken] builds and takes what it takes on `XY`, to a part in ten million
     * of the body — the general boolean's own float32 snap of a curved body's tessellation (queued as (5q)).
     */
    private fun assertSameInEveryPose(
        what: String,
        taken: (Pose) -> Pair<Double, Double>,
    ) {
        val all = poses.map { it.what to taken(it) }
        println("pose | $what | " + all.joinToString(" | ") { "${it.first} ${it.second.second}" })
        val (v0, reference) = all[0].second
        assertTrue(reference > 0.5, "$what takes material on XY: $reference")
        for ((pose, pair) in all.drop(1)) {
            assertTrue(abs(pair.second - reference) <= 1e-7 * v0, "$what in the pose '$pose' takes ${pair.second}, not the $reference it takes on XY")
        }
    }

    // ---- (a) the ordinary band: a rigid section swept along a straight run ----

    /**
     * **A band along a straight crease between a plane and a cylinder.** The revolve's cap edge is the case
     * that found the defect: its wedge has one straight leg and one **round** one, so it used to be swept
     * with no step-off and no end-step at all, and its cap lay exactly in the annulus the crease ends on.
     */
    @Test
    fun aBandAlongACapEdgeTakesTheSameMaterialInEveryPose() {
        assertSameInEveryPose("one band along a revolve's cap edge") { pose ->
            val cx = Construction()
            val t = turnedOn(pose, cx, 200.0)
            val s = Evaluator().solid(t)
            assertManifold(s.mesh, "${pose.what}: the 200° turn")
            val pair = edgesAt(s, at(pose, inner, ring, 0.0)).filter { Blend3.choicesFor(s, listOf(it), BlendSection(BlendKind.FILLET, 2.0)).first?.get(0)?.convex == true }
            assertEquals(2, pair.size, "${pose.what}: the cap's reflex pair")
            val (ref, why) = round(cx, t, pair.take(1), 2.0)
            val body = Evaluator().solid(assertNotNull(ref, "${pose.what}: one band builds: $why"))
            assertManifold(body.mesh, "${pose.what}: one band")
            Geom3.volume(s.mesh) to Geom3.volume(s.mesh) - Geom3.volume(body.mesh)
        }
    }

    /** **A band along an ordinary straight crease between two planes** — the case that was always right, kept as the control. */
    @Test
    fun aBandAlongABlocksOwnEdgeTakesTheSameMaterialInEveryPose() {
        assertSameInEveryPose("one band along a block's top edge") { pose ->
            val cx = Construction()
            val box = prismOn(pose, cx, listOf(Vec2(0.0, 0.0), Vec2(width, 0.0), Vec2(width, depth), Vec2(0.0, depth)), height)
            val s = Evaluator().solid(box)
            val top =
                edgesAt(s, at(pose, width, depth, height)).filter { i ->
                    val p = assertNotNull(Blend3.edgePath(edgesOf(s)[i]).first)
                    abs((p.start!! - at(pose, 0.0, 0.0, height)).dot(frame(pose).third)) < 1e-9 &&
                        abs((p.end!! - at(pose, 0.0, 0.0, height)).dot(frame(pose).third)) < 1e-9
                }
            assertEquals(2, top.size, "${pose.what}: two top edges at the far corner")
            val (ref, why) = round(cx, box, top.take(1), 4.0)
            val body = Evaluator().solid(assertNotNull(ref, "${pose.what}: one band builds: $why"))
            assertManifold(body.mesh, "${pose.what}: one band")
            Geom3.volume(s.mesh) to Geom3.volume(s.mesh) - Geom3.volume(body.mesh)
        }
    }

    /**
     * **A band whose free end closes on a cap of its own** (OP-31, slice 5p) — one rounded top edge of a
     * regular **pentagonal** prism, in every pose.
     *
     * The block above is the case that always worked, and the reason is that its plan corner is a right
     * angle: the wall the band's free end runs into is square to the crease, so the cap stands *in* that wall
     * and is its notch. A pentagon's is not, so the band closes on a flat cap that is a face of its own and
     * the wall keeps a triangle past it — two more pieces of tool geometry, both built in the crease's own
     * frame, and so both owed the same claim as everything else in this class: they take what they take on
     * `XY` wherever the drawing was sketched.
     */
    @Test
    fun aFreeEndsOwnCapTakesTheSameMaterialInEveryPose() {
        assertSameInEveryPose("one band on a pentagonal prism's top edge") { pose ->
            val cx = Construction()
            val plan = (0 until 5).map { Vec2(30.0 * cos(2 * PI * it / 5), 30.0 * sin(2 * PI * it / 5)) }
            val prism = prismOn(pose, cx, plan, height)
            val s = Evaluator().solid(prism)
            assertManifold(s.mesh, "${pose.what}: the pentagonal prism")
            val es = edgesOf(s)
            val top =
                es.indices.filter { i ->
                    val p = Blend3.edgePath(es[i]).first ?: return@filter false
                    val a = p.start ?: return@filter false
                    val b = p.end ?: return@filter false
                    val n = frame(pose).third
                    abs((a - at(pose, 0.0, 0.0, height)).dot(n)) < 1e-9 && abs((b - at(pose, 0.0, 0.0, height)).dot(n)) < 1e-9
                }
            assertEquals(5, top.size, "${pose.what}: one top edge per side")
            val (ref, why) = round(cx, prism, top.take(1), 3.0)
            val body = Evaluator().solid(assertNotNull(ref, "${pose.what}: the top edge rounds: $why"))
            assertManifold(body.mesh, "${pose.what}: the pentagon's rounded top edge")
            Geom3.volume(s.mesh) to Geom3.volume(s.mesh) - Geom3.volume(body.mesh)
        }
    }

    // ---- (b) the canal band of slice 5f: a section that changes along an elliptical mitre ----

    /**
     * **The elliptical mitre of two equal rounds, rounded as a canal band** (slice 5f) — in every pose. Its
     * section changes from station to station and its legs lie on two **curved** walls, so every step-off it
     * takes is the curved-wall reading of the same rule the ordinary band takes.
     */
    @Test
    fun theCanalBandAlongAnEllipticalMitreTakesTheSameMaterialInEveryPose() {
        assertSameInEveryPose("the canal band along an elliptical mitre") { pose ->
            val cx = Construction()
            val box = prismOn(pose, cx, listOf(Vec2(0.0, 0.0), Vec2(width, 0.0), Vec2(width, depth), Vec2(0.0, depth)), height)
            val s0 = Evaluator().solid(box)
            val corner = at(pose, width, depth, height)
            val top =
                edgesAt(s0, corner).filter { i ->
                    val p = assertNotNull(Blend3.edgePath(edgesOf(s0)[i]).first)
                    abs((p.start!! - at(pose, 0.0, 0.0, height)).dot(frame(pose).third)) < 1e-9 &&
                        abs((p.end!! - at(pose, 0.0, 0.0, height)).dot(frame(pose).third)) < 1e-9
                }
            assertEquals(2, top.size, "${pose.what}: two top edges share the far corner")
            val (two, whyTwo) = round(cx, box, top, 4.0)
            val rounded = Evaluator().solid(assertNotNull(two, "${pose.what}: two rounds build: $whyTwo"))
            assertManifold(rounded.mesh, "${pose.what}: two rounds")
            val before = Geom3.volume(rounded.mesh)
            val es = edgesOf(rounded)
            val mitre = assertNotNull(es.indices.firstOrNull { es[it].name is EdgeName.BlendMitre && es[it].reason == null }, "${pose.what}: the two bands cross in a mitre")
            val (out, why) = round(cx, assertNotNull(two), listOf(mitre), 1.0)
            val body = Evaluator().solid(assertNotNull(out, "${pose.what}: the canal band builds: $why"))
            assertManifold(body.mesh, "${pose.what}: the canal band")
            before to before - Geom3.volume(body.mesh)
        }
    }

    // ---- (c) the pivot of slice 5h about a **slanted** upright — a loft's own inside corner ----

    /**
     * **The loft's slanted upright carries the same pivot in every pose, mirrored or not.**
     *
     * Its walls are all **planes**, so nothing about a curved wall's skin touches this fixture: what it holds
     * is the pivot's own section where it meets the face the two roundings share. The ball touches that face,
     * so the section's first vertex stands *exactly in* it, and the pivot puts one down at every station —
     * a whole rail of tool vertices in a plane of the body, which cancels cleanly only while the arithmetic
     * is exact. On `XY` the shared face is `z = 20` and it did; turned 30° about `x` it did not.
     */
    @Test
    fun theLoftsSlantedUprightTakesTheSameMaterialInEveryPose() {
        for (mirrored in listOf(false, true)) {
            assertSameInEveryPose("the loft's slanted pivot${if (mirrored) ", mirrored" else ""}") { pose ->
                val cx = Construction()
                val t = loftedOn(pose, cx, mirrored)
                val s = Evaluator().solid(t)
                assertManifold(s.mesh, "${pose.what}: the loft")
                val shift = height * kotlin.math.tan(35.0 * PI / 180.0) / sqrt(2.0)
                val scale = 1.0 - shift / 30.0
                val vx = if (mirrored) 60.0 - 30.0 * scale else 30.0 * scale
                val v = at(pose, vx, 30.0 * scale, height)
                val es = edgesOf(s)
                val pair =
                    edgesAt(s, v).filter { i ->
                        es[i].between.a.label.render().contains("section 2's own face") || es[i].between.b.label.render().contains("section 2's own face")
                    }
                assertEquals(2, pair.size, "${pose.what}: the loft's cap turns a reflex corner at $v")
                val (ref, why) = round(cx, t, pair, 3.0)
                val body = Evaluator().solid(assertNotNull(ref, "${pose.what}: the slanted pivot builds: $why"))
                assertManifold(body.mesh, "${pose.what}: the slanted pivot")
                Geom3.volume(s.mesh) to Geom3.volume(s.mesh) - Geom3.volume(body.mesh)
            }
        }
    }

    // ---- (d) the pivot of slice 5h about a **ring**, at a sweep that is neither a right angle nor a half turn ----

    /**
     * **The *tight* ring's pivot takes the same material in every pose** (OP-31, slice 5o).
     *
     * `R = 8` is four ball radii rather than ten, so the station plane turns far enough along the run to
     * stand nearly parallel to the crease it reads its apex on — which is what made the pivot's section
     * reach thirty-eight millimetres down a ten-millimetre tube and lay a sliver of tool along the body's
     * own edge. The section has a **fourth side** now, the band's own cap plane carried along the crease,
     * so the tool is local to the corner; and a tool built in the crease's own frame owes this class the
     * same claim every other tool does — it takes what it takes on `XY` wherever the drawing was sketched.
     */
    @Test
    fun theTightRingPivotTakesTheSameMaterialInEveryPose() {
        val tight = 8.0
        assertSameInEveryPose("the tight ring's pivot at R = $tight") { pose ->
            val cx = Construction()
            val t = turnedOn(pose, cx, 270.0, tight)
            val s = Evaluator().solid(t)
            assertManifold(s.mesh, "${pose.what}: the tight 270° turn")
            val (uu, vv, _) = frame(pose)
            val v = pose.origin + uu * 10.0 + vv * tight
            val pair = edgesAt(s, v).filter { Blend3.choicesFor(s, listOf(it), BlendSection(BlendKind.FILLET, 2.0)).first?.get(0)?.convex == true }
            assertEquals(2, pair.size, "${pose.what}: the tight cap's reflex pair")
            val (ref, why) = round(cx, t, pair, 2.0)
            val body = Evaluator().solid(assertNotNull(ref, "${pose.what}: the tight ring's pivot builds: $why"))
            assertManifold(body.mesh, "${pose.what}: the tight ring's pivot")
            Geom3.volume(s.mesh) to Geom3.volume(s.mesh) - Geom3.volume(body.mesh)
        }
    }

    /** **The ring pivot at both caps of a 200° turn**, in every pose — the slice's own tool, held to the same rule. */
    @Test
    fun theRingPivotTakesTheSameMaterialInEveryPose() {
        for (which in listOf(true, false)) {
            assertSameInEveryPose("the ${if (which) "start" else "end"} cap's pivot at 200°") { pose ->
                val cx = Construction()
                val t = turnedOn(pose, cx, 200.0)
                val s = Evaluator().solid(t)
                val (uu, vv, n) = frame(pose)
                val v =
                    if (which) {
                        pose.origin + uu * inner + vv * ring
                    } else {
                        pose.origin + uu * inner + (vv * cos(200.0 * PI / 180.0) + n * sin(200.0 * PI / 180.0)) * ring
                    }
                val pair = edgesAt(s, v).filter { Blend3.choicesFor(s, listOf(it), BlendSection(BlendKind.FILLET, 2.0)).first?.get(0)?.convex == true }
                assertEquals(2, pair.size, "${pose.what}: the cap's reflex pair")
                val (ref, why) = round(cx, t, pair, 2.0)
                val body = Evaluator().solid(assertNotNull(ref, "${pose.what}: the pivot builds: $why"))
                assertManifold(body.mesh, "${pose.what}: the pivot")
                Geom3.volume(s.mesh) to Geom3.volume(s.mesh) - Geom3.volume(body.mesh)
            }
        }
    }
}
