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
import constructit.geom.Geom3
import constructit.geom.Section3
import constructit.geom.Solid3
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.units.Dimension
import constructit.units.Quantity
import constructit.units.mm
import kotlin.math.PI
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **Probe of the canal corner's pose independence** (OP-31 slice 5h, second probe; GitHub #36).
 *
 * The pivot's construction reads the material at the face in question and nothing about the frame the body
 * was sketched in — that is what the slice claims, and a pivot that fails at one cap of a revolve and builds
 * at the other (the first probe's finding) is exactly a frame read where the material should have been. So:
 * the same ring pivot on a revolve sketched in a **turned and shifted** plane takes the same volume as the one
 * sketched on `XY`; a **mirrored** loft carries the pivot about its mirrored slanted upright and takes what
 * the original does; and two **chamfers** at a ring upright either build as a corner or refuse in words —
 * never silently and never with a crash.
 */
class BlendCornerCanalPoseProbeTest {
    private var ids = 0
    private val ring = 20.0
    private val inner = 10.0

    private fun ang(deg: Double) = Quantity(deg * PI / 180.0, Dimension.ANGLE)

    private fun polygon(
        cx: Construction,
        pts: List<Vec2>,
    ): RegionRef {
        val ps = pts.map { cx.freePoint("L${ids++}", it.x.mm, it.y.mm) }
        return cx.region(cx.loop(*ps.indices.map { cx.segment(ps[it], ps[(it + 1) % ps.size]) }.toTypedArray()))
    }

    private fun profile(): List<Vec2> =
        listOf(Vec2(0.0, inner), Vec2(0.0, ring + 10.0), Vec2(10.0, ring + 10.0), Vec2(10.0, ring), Vec2(20.0, ring), Vec2(20.0, inner))

    /** The L-meridian revolved about the sketch's own `x` axis, on the sketch plane [origin] + u·[u] + v·[v]. */
    private fun turnedOn(
        cx: Construction,
        origin: Vec3,
        u: Vec3,
        v: Vec3,
        sweep: Double,
    ): SolidRef {
        val o = cx.freePoint("Ro${ids++}", 0.mm, 0.mm)
        val axis = cx.direction(o, cx.freePoint("Rx${ids++}", 1.mm, 0.mm))
        return cx.revolve(cx.sketchOn(cx.plane(origin, u, v), polygon(cx, profile())), o, axis, cx.const(ang(sweep)))
    }

    private fun reflexPair(
        solid: Solid3,
        at: Vec3,
        kind: BlendKind = BlendKind.FILLET,
        r: Double = 2.0,
    ): List<Int> {
        val es = assertNotNull(Section3.edges(solid.feature).first, "it names its edges")
        return es.indices.filter { i ->
            val e = es[i]
            if (e.reason != null) return@filter false
            val path = Blend3.edgePath(e).first ?: return@filter false
            val s = path.start ?: return@filter false
            val t = path.end ?: return@filter false
            if ((s - at).length() > 1e-6 && (t - at).length() > 1e-6) return@filter false
            Blend3.choicesFor(solid, listOf(i), BlendSection(kind, r)).first?.get(0)?.convex == true
        }
    }

    /** One gesture over [address]; the body, or the words it refused with. */
    private fun round(
        cx: Construction,
        on: SolidRef,
        address: List<Int>,
        size: Double,
        kind: BlendKind = BlendKind.FILLET,
    ): Pair<Solid3?, String?> {
        val body = Evaluator().solid(on)
        val (choices, why) = Blend3.choicesFor(body, address, BlendSection(kind, size))
        if (choices == null) return null to (why?.render() ?: "no choice")
        val ref = cx.blendAll(on, cx.planeXY(), listOf(Construction.BlendRun(kind, cx.const(size.mm), null, address, choices)))
        val r = Evaluator().eval(ref.node)
        if (r is EvalResult.Invalid) return null to r.why.render()
        return Evaluator().solid(ref) to null
    }

    private fun corners(s: Solid3) = assertNotNull(Section3.faces(s.feature).first, "it names its faces").filter { it.name is FaceName.BlendCorner }

    private class Pose(val what: String, val origin: Vec3, val u: Vec3, val v: Vec3)

    /** The sketch plane poses every claim below is made in: the canonical one, a shift, two turns and a turn with a shift. */
    private val poses =
        listOf(
            Pose("XY", Vec3.ZERO, Vec3.X, Vec3.Y),
            Pose("shifted YZ", Vec3(17.0, -9.0, 4.0), Vec3.Y, Vec3.Z),
            Pose("turned 30° about x", Vec3.ZERO, Vec3.X, Vec3(0.0, kotlin.math.cos(PI / 6), kotlin.math.sin(PI / 6))),
            Pose("turned 30° about y", Vec3.ZERO, Vec3(kotlin.math.cos(PI / 6), 0.0, -kotlin.math.sin(PI / 6)), Vec3.Y),
            Pose("turned 45° about z and shifted", Vec3(-3.0, 11.0, -7.0), Vec3(1.0, 1.0, 0.0) * (1.0 / sqrt(2.0)), Vec3.Z),
        )

    /** The reflex vertices of a [sweep] turn's two caps in [pose]. */
    private fun capVertices(
        pose: Pose,
        sweep: Double,
    ): Pair<Vec3, Vec3> {
        val uu = pose.u.normalized()
        val vv = (pose.v - uu * pose.v.dot(uu)).normalized()
        val n = uu.cross(vv)
        val start = pose.origin + uu * inner + vv * ring
        // the sketch's +v turned by the sweep about the sketch's +u
        val end = pose.origin + uu * inner + (vv * kotlin.math.cos(sweep * PI / 180.0) + n * kotlin.math.sin(sweep * PI / 180.0)) * ring
        return start to end
    }

    /** The base body's volume and the volume the rounding of [which] cap edges takes, sketched in [pose]. */
    private fun takenOn(
        pose: Pose,
        sweep: Double,
        which: (start: List<Int>, end: List<Int>) -> List<Int>,
        corners: Int,
        what: String,
    ): Pair<Double, Double> {
        val cx = Construction()
        val t = turnedOn(cx, pose.origin, pose.u, pose.v, sweep)
        val s = Evaluator().solid(t)
        assertManifold(s.mesh, "${pose.what}: $what")
        val (startV, endV) = capVertices(pose, sweep)
        val start = reflexPair(s, startV)
        val end = reflexPair(s, endV)
        assertEquals(2, start.size, "${pose.what}: the start cap's reflex pair at $startV")
        assertEquals(2, end.size, "${pose.what}: the end cap's reflex pair at $endV")
        val (body, why) = round(cx, t, which(start, end), 2.0)
        val b = assertNotNull(body, "${pose.what}: $what builds: $why")
        assertManifold(b.mesh, "${pose.what}: $what")
        assertEquals(corners, corners(b).size, "${pose.what}: $what has $corners corner face(s)")
        return Geom3.volume(s.mesh) to Geom3.volume(s.mesh) - Geom3.volume(b.mesh)
    }

    /**
     * The same rounding in every pose takes the same volume, to the **float32 snap of the general boolean**
     * (queued as (5q)): the first boolean re-snaps the body's own tessellation, which moves the volume by
     * about 1e-8 of it in a way that depends on the coordinates, so the poses are compared to 1e-7 of the body.
     */
    private fun assertSameInEveryPose(
        sweep: Double,
        which: (start: List<Int>, end: List<Int>) -> List<Int>,
        corners: Int,
        what: String,
    ) {
        val taken = poses.map { it.what to takenOn(it, sweep, which, corners, what) }
        println("probe | $what at $sweep° | " + taken.joinToString(" | ") { "${it.first} ${it.second.second}" })
        val (v0, reference) = taken[0].second
        assertTrue(reference > 1.0, "$what takes material: $reference")
        for ((pose, pair) in taken.drop(1)) {
            assertClose(pair.second, reference, 1e-7 * v0, "$what in the pose '$pose' takes what it takes on XY")
        }
    }

    /**
     * **A rigid motion of the whole construction moves a rounding with it and nothing else.** One band
     * along a cap edge (which is cut by the general boolean on a revolve), the pivot at the start cap, the
     * pivot at the end cap, and both pivots in one gesture: each builds in every pose and takes what it
     * takes on the canonical plane, at a sweep that is neither a right angle nor a half turn and at one that is.
     */
    @Test
    fun oneBandAlongACapEdgeBuildsInEveryPose() {
        for (sweep in listOf(90.0, 200.0)) assertSameInEveryPose(sweep, { start, _ -> start.take(1) }, 0, "one band along a cap edge")
    }

    @Test
    fun theStartCapsPivotIsTheSameBodyInEveryPose() {
        for (sweep in listOf(90.0, 200.0)) assertSameInEveryPose(sweep, { start, _ -> start }, 1, "the start cap's pivot")
    }

    @Test
    fun theEndCapsPivotIsTheSameBodyInEveryPose() {
        for (sweep in listOf(90.0, 200.0)) assertSameInEveryPose(sweep, { _, end -> end }, 1, "the end cap's pivot")
    }

    @Test
    fun bothCapsPivotsAreTheSameBodyInEveryPose() {
        for (sweep in listOf(90.0, 200.0)) assertSameInEveryPose(sweep, { start, end -> start + end }, 2, "both caps' pivots")
    }

    // ---- the loft, mirrored ----

    private fun lofted(
        cx: Construction,
        mirrored: Boolean,
        slant: Double = 35.0,
        pose: Pose = poses[0],
    ): SolidRef {
        val lo = listOf(Vec2(0.0, 0.0), Vec2(60.0, 0.0), Vec2(60.0, 30.0), Vec2(30.0, 30.0), Vec2(30.0, 60.0), Vec2(0.0, 60.0))
        val shift = HEIGHT * tan(slant * PI / 180.0) / sqrt(2.0)
        val scale = 1.0 - shift / 30.0

        fun pose(pts: List<Vec2>): List<Vec2> = if (mirrored) pts.map { Vec2(60.0 - it.x, it.y) }.reversed() else pts
        return cx.loft(
            listOf(
                LoftPart.Area(cx.sketchOn(cx.plane(pose.origin, pose.u, pose.v), polygon(cx, pose(lo)))),
                LoftPart.Area(cx.sketchOn(cx.planeOffset(cx.plane(pose.origin, pose.u, pose.v), cx.const(HEIGHT.mm)), polygon(cx, pose(lo.map { it * scale })))),
            ),
        )
    }

    private fun loftTaken(
        mirrored: Boolean,
        pose: Pose = poses[0],
    ): Double {
        val cx = Construction()
        val t = lofted(cx, mirrored, pose = pose)
        val s = Evaluator().solid(t)
        val shift = HEIGHT * tan(35.0 * PI / 180.0) / sqrt(2.0)
        val scale = 1.0 - shift / 30.0
        val vx = if (mirrored) 60.0 - 30.0 * scale else 30.0 * scale
        val uu = pose.u.normalized()
        val vv = (pose.v - uu * pose.v.dot(uu)).normalized()
        val at = pose.origin + uu * vx + vv * (30.0 * scale) + uu.cross(vv) * HEIGHT
        val es = assertNotNull(Section3.edges(s.feature).first)
        val pair =
            es.indices.filter { i ->
                val e = es[i]
                val cap = e.between.a.label.render().contains("section 2's own face") || e.between.b.label.render().contains("section 2's own face")
                if (!cap) return@filter false
                val path = Blend3.edgePath(e).first ?: return@filter false
                val a = path.start ?: return@filter false
                val b = path.end ?: return@filter false
                (a - at).length() < 1e-6 || (b - at).length() < 1e-6
            }
        assertEquals(2, pair.size, "mirrored=$mirrored, pose ${pose.what}: the loft's cap turns a reflex corner at $at")
        val (body, why) = round(cx, t, pair, 3.0)
        val b = assertNotNull(body, "mirrored=$mirrored, pose ${pose.what}: the slanted upright carries the pivot: $why")
        assertManifold(b.mesh, "mirrored=$mirrored, pose ${pose.what} loft")
        assertEquals(1, corners(b).size, "mirrored=$mirrored, pose ${pose.what}: one corner face")
        return Geom3.volume(s.mesh) - Geom3.volume(b.mesh)
    }

    /** **A mirrored loft is the same pivot the other way round**, and takes what the original takes. */
    @Test
    fun aMirroredLoftCarriesTheSamePivot() {
        val plain = loftTaken(false)
        val mirror = loftTaken(true)
        println("probe | mirrored loft | plain $plain | mirrored $mirror")
        // to the general boolean's float32 snap of the body's own tessellation (queued as (5q)); the loft is ~4e4 mm³
        assertClose(mirror, plain, 4e-3, "the mirror image takes what the original takes, to the float32 snap")
    }

    /** **The loft's pivot in every pose** takes what it takes on XY, to the float32 snap. */
    @Test
    fun theLoftsPivotIsTheSameBodyInEveryPose() {
        val plain = loftTaken(false)
        for (pose in poses.drop(1)) {
            val posed = loftTaken(false, pose)
            println("probe | loft in pose ${pose.what} | $posed against $plain")
            assertClose(posed, plain, 4e-3, "the loft's pivot in the pose '${pose.what}' takes what it takes on XY, to the float32 snap")
        }
    }

    /** **Two chamfers at a ring upright** build as a named corner or refuse in words — never silently. */
    @Test
    fun twoChamfersAtARingUprightBuildOrSpeak() {
        val cx = Construction()
        val t = turnedOn(cx, Vec3.ZERO, Vec3.X, Vec3.Y, 90.0)
        val s = Evaluator().solid(t)
        val pair = reflexPair(s, Vec3(inner, ring, 0.0), BlendKind.CHAMFER)
        assertEquals(2, pair.size, "the cap's reflex pair, for a chamfer")
        val (body, why) = round(cx, t, pair, 2.0, BlendKind.CHAMFER)
        if (body == null) {
            val words = assertNotNull(why, "a refusal speaks")
            assertTrue(words.isNotBlank(), "a refusal says why")
            println("probe | chamfer pivot | refused: $words")
        } else {
            assertManifold(body.mesh, "two chamfers at a ring upright")
            assertEquals(1, corners(body).size, "one corner face")
            println("probe | chamfer pivot | built, took ${Geom3.volume(s.mesh) - Geom3.volume(body.mesh)}")
        }
    }

    private companion object {
        const val HEIGHT = 20.0
    }
}
