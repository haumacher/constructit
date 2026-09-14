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
import constructit.geom.EdgeGeom
import constructit.geom.EdgeName
import constructit.geom.FacePatch
import constructit.geom.Feature3
import constructit.geom.Frames3
import constructit.geom.Geom3
import constructit.geom.GeomMath
import constructit.geom.MeshBool
import constructit.geom.Plane3
import constructit.geom.Section3
import constructit.geom.Solid3
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.units.mm
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **A canal band as a carrier through the general boolean** (OP-31, slice 5l; GitHub #36).
 *
 * *What this class is about.* [BoolFace3][constructit.geom.BoolFace3] places a result triangle on an operand
 * face by asking each carrier whether the triangle sits on it. Slice 5c gave the vocabulary its curved
 * carriers — the cylinder, the cone, the sphere, the ring — and slice 5f's own note said a canal band needed
 * no fourth one, *"a canal is an **ordinary entry** of an ordinary dressing, so no fourth carrier"*. That was
 * true of every reader the drawing had then, because a canal band's surface was only ever **drawn**. Through
 * a boolean it has to be **looked a triangle up against**, and a body carrying one refused its whole face
 * list by name.
 *
 * *The fourth carrier.* The pipe surface of the rolling ball along its own spine ([Pipe3][constructit.geom.Pipe3]):
 * a `sits` predicate solved by the nearest point of the spine, an `(arc, station)` chart to state a trim in,
 * creases fitted through points exact on both surfaces, its own place in the result face list, and every
 * reader answering on it. What is exact is every station of the spine; what is fitted is the curve between
 * two of them, and that number is carried on the face and on every crease measured against it.
 *
 * *What this class asserts.* A canal body bored and a canal body fused: every face named, no whole-list
 * refusal, the band's trim stated, and the level, vertical and tilted sections closed — with the level
 * section's own area stated as the unbored body's less the bore's circle. A pivot's corner face — a canal
 * too — through the same boolean. Every pose of the bored body. And the tolerance every fitted crease
 * reached, asserted against both surfaces it runs between.
 */
class BoolCanalCarrierTest {
    private var ids = 0
    private val width = 40.0
    private val depth = 30.0
    private val height = 20.0

    private fun requireEngine() = assumeTrue(MeshBool.available, "no general boolean engine: ${MeshBool.status}")

    // ---- the fixtures ----

    private fun prismOn(
        plane: Plane3,
        cx: Construction,
        xy: List<Vec2>,
        h: Double,
    ): SolidRef {
        val pts = xy.mapIndexed { i, p -> cx.freePoint("p${ids++}", p.x.mm, p.y.mm) }
        val segs = xy.indices.map { cx.segment(pts[it], pts[(it + 1) % xy.size]) }
        return cx.extrude(cx.sketchOn(cx.plane(plane.origin, plane.u, plane.v), cx.region(cx.loop(*segs.toTypedArray()))), cx.const(h.mm))
    }

    /** The block with its two far top edges rounded at 4 mm — `BlendCanalTest`'s own fixture, on any plane. */
    private fun twoRounds(
        cx: Construction,
        plane: Plane3,
    ): SolidRef {
        val box = prismOn(plane, cx, listOf(Vec2(0.0, 0.0), Vec2(width, 0.0), Vec2(width, depth), Vec2(0.0, depth)), height)
        val es = edgesOf(Evaluator().solid(box))
        val corner = plane.toWorld(Vec2(width, depth)) + plane.normal.normalized() * height
        val tops =
            es.indices.filter { i ->
                val path = Blend3.edgePath(es[i]).first ?: return@filter false
                val a = path.start ?: return@filter false
                val b = path.end ?: return@filter false
                val n = plane.normal.normalized()
                val top = plane.origin.dot(n) + height
                abs(a.dot(n) - top) < 1e-9 && abs(b.dot(n) - top) < 1e-9 && ((a - corner).length() < 1e-6 || (b - corner).length() < 1e-6)
            }
        assertEquals(2, tops.size, "two top edges share the block's far corner")
        val body = Evaluator().solid(box)
        val choices = assertNotNull(Blend3.choicesFor(body, tops, BlendSection(BlendKind.FILLET, 4.0)).first, "the two top edges are scored")
        return cx.blendAll(box, cx.planeXY(), listOf(Construction.BlendRun(BlendKind.FILLET, cx.const(4.0.mm), null, tops, choices)))
    }

    /** …and the elliptical mitre they cross in rounded at 1 mm — the canal band itself. */
    private fun canalBody(
        cx: Construction,
        plane: Plane3 = Plane3(Vec3.ZERO, Vec3.X, Vec3.Y),
    ): SolidRef {
        val two = twoRounds(cx, plane)
        val es = edgesOf(Evaluator().solid(two))
        val mitre =
            assertNotNull(
                es.indices.firstOrNull { es[it].name is EdgeName.BlendMitre && es[it].reason == null && es[it].geom is EdgeGeom.OnPlane },
                "the two bands cross in a mitre",
            )
        return round(cx, two, listOf(mitre), 1.0)
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

    /** A drill of radius [r] along the plane's own normal, through the whole body. */
    private fun drill(
        cx: Construction,
        plane: Plane3,
        at: Vec2,
        r: Double,
    ): SolidRef {
        val c = cx.freePoint("d${ids++}", at.x.mm, at.y.mm)
        val circle = cx.region(cx.loop(cx.circleCR(c, cx.const(r.mm))))
        val base = cx.plane(plane.origin - plane.normal.normalized() * 5.0, plane.u, plane.v)
        return cx.extrude(cx.sketchOn(base, circle), cx.const((height + 10.0).mm))
    }

    // ---- readers ----

    private fun facesOf(s: Solid3): List<FacePatch> {
        val (fs, why) = Section3.faces(s.feature)
        return assertNotNull(fs, "it names its faces: ${why?.render()}")
    }

    private fun edgesOf(s: Solid3) = assertNotNull(Section3.edges(s.feature).first, "it names its edges")

    private fun areaAt(
        s: Solid3,
        plane: Plane3,
        what: String,
    ): Double {
        val (regions, why) = Section3.regionsOf(s.feature, plane)
        val rs = assertNotNull(regions, "$what closes: ${why?.render()}")
        var a = 0.0
        for (r in rs) {
            a += abs(GeomMath.signedArea(r.outer))
            for (h in r.holes) a -= abs(GeomMath.signedArea(h))
        }
        return a
    }

    private fun closes(
        s: Solid3,
        plane: Plane3,
        what: String,
    ) {
        val (regions, why) = Section3.regionsOf(s.feature, plane)
        assertNotNull(regions, "$what closes: ${why?.render()}")
    }

    /**
     * **A section closes, or it says at which face it did not** (OP-3) — and never draws a boundary that
     * does not close. The count of the ones that only say so is the slice's own open measure.
     */
    private fun closesOrSaysWhy(
        s: Solid3,
        plane: Plane3,
        what: String,
    ): Boolean {
        val (regions, why) = Section3.regionsOf(s.feature, plane)
        if (regions != null) return true
        val reason = assertNotNull(why, "$what that does not close says why").render()
        assertTrue(reason.contains("#") || reason.contains("face") || reason.contains("band"), "…naming the face it broke at: $reason")
        println("canal carrier | $what | does not close, and says where | ${reason.take(110)}")
        return false
    }

    /**
     * **Named, or refused by name — never half drawn** (OP-31, slice 5l). Where the drawing can trace every
     * triangle of the result back to an operand carrier it says so and every section of it closes; where it
     * cannot it refuses **wholly**, with a reason that names something, and the body stays mesh-only. The one
     * state that must never happen is a face list that is nearly right, and this is what says so.
     */
    private fun namedOrRefused(
        s: Solid3,
        what: String,
    ): List<FacePatch>? {
        val (fs, why) = Section3.faces(s.feature)
        if (fs == null) {
            val reason = assertNotNull(why, "$what that cannot be named says why").render()
            assertTrue(reason.contains("#") || reason.contains("face") || reason.contains("band"), "…naming something: $reason")
            println("canal carrier | $what | refused by name | ${reason.take(110)}")
            return null
        }
        for (f in fs) {
            assertTrue(
                f.plane != null || f.surface != null || f.pipe != null || f.reason != null,
                "${f.name.label.render()} of $what says what it is",
            )
        }
        return fs
    }

    /** Every face of the body is named — no slot without either a carrier or a reason of its own. */
    private fun everyFaceNamed(
        s: Solid3,
        what: String,
    ) {
        val fs = facesOf(s)
        assertTrue(fs.isNotEmpty(), "$what has faces")
        for (f in fs) {
            assertTrue(
                f.plane != null || f.surface != null || f.pipe != null || f.reason != null,
                "${f.name.label.render()} of $what says what it is",
            )
        }
    }

    // ---- (a) a bore clear of the band: the figure ----

    /**
     * **A canal body with a bore clear of the corner.** The bored body names its faces, the canal band is
     * still one of them and carries its own pipe, and the level section through the canal region is the
     * unbored body's own area less the bore's circle exactly — the bore standing clear of the band's trace,
     * so nothing else moved.
     */
    @Test
    fun aBoreClearOfTheBandTakesItsOwnCircleAndNothingElse() {
        requireEngine()
        val cx = Construction()
        val canal = canalBody(cx)
        val plain = Evaluator().solid(canal)
        assertManifold(plain.mesh, "the canal body")
        val r = 3.0
        val bored = Evaluator().solid(cx.subtract(canal, drill(cx, Plane3(Vec3.ZERO, Vec3.X, Vec3.Y), Vec2(12.0, 12.0), r)))
        assertManifold(bored.mesh, "the canal body bored clear of the corner")
        assertTrue(bored.feature is Feature3.MeshBoolean, "it takes the general path")
        everyFaceNamed(bored, "the bored canal body")

        val band = assertNotNull(facesOf(bored).firstOrNull { it.pipe != null }, "the canal band is a face of the bored body, and it is a pipe")
        assertTrue(band.outline.isNotEmpty(), "…whose trim is stated in the pipe's own chart")
        assertNotNull(band.fitted, "…and which says how far its own statement may be")

        val level = Plane3(Vec3(0.0, 0.0, 17.5), Vec3.X, Vec3.Y)
        val was = areaAt(plain, level, "the unbored canal's level section")
        val now = areaAt(bored, level, "the bored canal's level section")
        // **the bore stands clear of the band's own trace**, so what the level section keeps of the canal is
        // exactly what it kept before and what it loses is exactly the bore's own circle — a loop of the
        // section in its own right (OP-31, slice 5l's last case: before the bore's cylinder could state
        // which side of itself the material stands on, that loop was dropped and the section came back with
        // the block's own area and no hole in it). The tolerance is the chord tolerance of the two curved
        // traces the plane leaves on the band and its neighbours.
        assertClose(
            now,
            was - PI * r * r,
            tol = 1e-1,
            msg = "the canal's own trace in the level section is untouched by a bore clear of it, and the bore's own circle is a hole in it",
        )
        assertTrue(Geom3.volume(bored.mesh) < Geom3.volume(plain.mesh), "and the bore took material")
        assertClose(
            Geom3.volume(plain.mesh) - Geom3.volume(bored.mesh),
            PI * r * r * height,
            tol = 0.02 * PI * r * r * height,
            msg = "the bore takes its own cylinder and nothing else",
        )
        closesOrSaysWhy(bored, Plane3(Vec3(0.0, 12.0, 0.0), Vec3.X, Vec3.Z), "a vertical section of the bored canal body, through the bore")
        closesOrSaysWhy(
            bored,
            Plane3(Vec3(12.0, 12.0, 10.0), Vec3.X, Vec3(0.0, cos(PI / 5), sin(PI / 5))),
            "a tilted section of the bored canal body, through the bore",
        )
        println("canal carrier | bore clear of the band | level section $was -> $now, band fitted ${band.fitted}")
    }

    // ---- (b) a bore straight through the band ----

    /**
     * **A bore through the band region itself**, which is where the carrier earns its name: the bore's own
     * cylinder crosses the pipe, so the result has a crease between two surfaces neither of which is a plane
     * and one of which is the canal. Every face is still named and every section still closes.
     */
    @Test
    fun aBoreThroughTheBandRegionIsNamedAndEachSectionClosesOrSaysWhy() {
        requireEngine()
        val cx = Construction()
        val canal = canalBody(cx)
        val bored = Evaluator().solid(cx.subtract(canal, drill(cx, Plane3(Vec3.ZERO, Vec3.X, Vec3.Y), Vec2(38.0, 28.0), 1.5)))
        assertManifold(bored.mesh, "the canal body bored through its own band")
        val fs = namedOrRefused(bored, "the canal body bored through its own band") ?: return
        val bands = fs.filter { it.pipe != null }
        assertTrue(bands.isNotEmpty(), "the canal band is still named, in the pieces the bore left of it")
        for (b in bands) assertTrue(b.outline.isNotEmpty(), "${b.name.label.render()} states its own trim")
        closes(bored, Plane3(Vec3(0.0, 0.0, 5.0), Vec3.X, Vec3.Y), "the level section at z = 5, below the roundings")
        var closed = 0
        if (closesOrSaysWhy(bored, Plane3(Vec3(0.0, 0.0, 17.5), Vec3.X, Vec3.Y), "the level section at z = 17.5")) closed++
        val vertical = Plane3(Vec3(38.0, 0.0, 0.0), Vec3.Y, Vec3.Z)
        if (closesOrSaysWhy(bored, vertical, "a vertical section through the bore")) closed++
        if (closesOrSaysWhy(bored, Plane3(Vec3(30.0, 22.0, 14.0), Vec3.X, Vec3(0.0, cos(PI / 7), sin(PI / 7))), "a tilted section through the bore")) closed++
        // **all three close** (OP-31, slice 5l). Before the trim was bridged and the section's pieces
        // carried their trim's own tolerance, *none* of the three closed and each broke at a neighbouring
        // band; the level and the tilted closed once it was, and the **vertical** — which stands on the
        // bore's own cylinder, whose trim goes **round** the chart and closes on no loop the boolean ever
        // wrote down — closes since that boundary is shut on the carrier's own natural end, the sense of
        // each run read off the face's own triangles rather than guessed at from the nearest piece.
        assertEquals(3, closed, "every section through a bored canal band closes")
        println("canal carrier | bore through the band | $closed of 3 sections close")

        // …and the vertical section's own **area** is stated: the unbored body's area in that plane less
        // what the bore takes out of it there, and the bore's trace is asked of the drawing itself — the
        // section of the very intersection of the two operands, in the same plane.
        val plain = Evaluator().solid(canal)
        val trace = Evaluator().solid(cx.intersect(canal, drill(cx, Plane3(Vec3.ZERO, Vec3.X, Vec3.Y), Vec2(38.0, 28.0), 1.5)))
        val was = areaAt(plain, vertical, "the unbored canal body's vertical section at x = 38")
        val now = areaAt(bored, vertical, "the bored canal body's vertical section at x = 38")
        val took = areaAt(trace, vertical, "the bore's own trace in that plane")
        // …to the tolerance the three are carried at: each of the three areas is read off curved traces
        // stated as chains — the band's own trim says 0.017 mm — and the trace body is itself a boolean of
        // two tessellated curved solids, so what the three may disagree by is a few hundredths of the
        // millimetre over the three millimetres the bore is wide.
        assertClose(now, was - took, tol = 0.2, msg = "the vertical section at x = 38 is the unbored body's less the bore's trace there")
        println("canal carrier | bore through the band | vertical at x = 38: $was - $took -> $now")
        println("canal carrier | bore through the band | ${fs.size} faces, ${bands.size} band piece(s)")
    }

    /**
     * **A plane that grazes the bore** — tangent to the bore's own cylinder along a ruling, so it touches
     * the rim the bore leaves on the band at one point and takes nothing away from the unbored body's own
     * section (OP-31, slice 5l).
     *
     * *Why it is asked here.* A trim that goes **round** its chart is closed on the carrier's own natural
     * end, and a grazing plane is where that closure is thinnest: the section of the bore's cylinder in it
     * is a single point, not a curve, so whichever side of the trim the reader stands on it has no width to
     * stand in. The contract is this class's own — **closed, or said where** — and what is asserted is
     * which: the plane closes, on both tangent sides, and states the unbored body's own area, the tangency
     * contributing nothing at all.
     */
    @Test
    fun aPlaneGrazingTheBoresRimClosesAndStatesTheTangencyOnce() {
        requireEngine()
        val cx = Construction()
        val canal = canalBody(cx)
        val at = Vec2(38.0, 28.0)
        val r = 1.5
        val bored = Evaluator().solid(cx.subtract(canal, drill(cx, Plane3(Vec3.ZERO, Vec3.X, Vec3.Y), at, r)))
        assertManifold(bored.mesh, "the canal body bored through its own band")
        val plain = Evaluator().solid(canal)
        for (side in listOf(-1.0, 1.0)) {
            val x = at.x + side * r
            val plane = Plane3(Vec3(x, 0.0, 0.0), Vec3.Y, Vec3.Z)
            val what = "a vertical plane grazing the bore at x = $x"
            val (regions, why) = Section3.regionsOf(bored.feature, plane)
            val rs = assertNotNull(regions, "$what closes: ${why?.render()}")
            assertTrue(rs.isNotEmpty(), "…with an area")
            var now = 0.0
            for (g in rs) {
                now += abs(GeomMath.signedArea(g.outer))
                for (h in g.holes) now -= abs(GeomMath.signedArea(h))
            }
            // **the tangency is stated once and takes nothing**: a plane touching the bore's cylinder along
            // one ruling removes no area at all, so the grazed section is the unbored body's own, to the
            // chord tolerance of the curved traces it is read off. Where the *unbored* body's own section
            // in that plane does not close the comparison is not this slice's to make — a plane within two
            // millimetres of the mitre's corner breaks at the **dressed** body's rounded band, which is the
            // one case the (5l) line still carries and no boolean is involved in it at all — and the
            // grazed section is held to closing, which is what this fixture is about.
            val (ref, refWhy) = Section3.regionsOf(plain.feature, plane)
            if (ref == null) {
                val reason = assertNotNull(refWhy, "the unbored body says why it does not close").render()
                assertTrue(reason.contains("band"), "…naming the band: $reason")
                println("canal carrier | grazing at x = $x | closes at $now; the unbored body itself does not, at its own band")
                continue
            }
            var was = 0.0
            for (g in ref) {
                was += abs(GeomMath.signedArea(g.outer))
                for (h in g.holes) was -= abs(GeomMath.signedArea(h))
            }
            assertClose(now, was, tol = 0.2, msg = "$what states the unbored body's own area")
            println("canal carrier | grazing at x = $x | closes | $was -> $now")
        }
    }

    /** **A boss fused on** — the other gesture route, and the union rather than the difference. */
    @Test
    fun aBossFusedOntoACanalBodyIsNamedTheSameWay() {
        requireEngine()
        val cx = Construction()
        val canal = canalBody(cx)
        val before = Geom3.volume(Evaluator().solid(canal).mesh)
        val boss = drill(cx, Plane3(Vec3(0.0, 0.0, height), Vec3.X, Vec3.Y), Vec2(36.0, 27.0), 2.5)
        val fused = Evaluator().solid(cx.union(canal, boss))
        assertManifold(fused.mesh, "a boss fused onto the canal body")
        assertTrue(Geom3.volume(fused.mesh) > before, "a boss adds material")
        val fs = namedOrRefused(fused, "the canal body with a boss") ?: return
        assertTrue(fs.any { it.pipe != null }, "the canal band survives the union as a face of its own")
        closesOrSaysWhy(fused, Plane3(Vec3(0.0, 0.0, 17.5), Vec3.X, Vec3.Y), "a level section through the boss and the band")
        closes(fused, Plane3(Vec3(0.0, 0.0, 5.0), Vec3.X, Vec3.Y), "a level section below the roundings")
        println("canal carrier | boss fused | ${fs.size} faces")
    }

    // ---- (c) the pivot's corner face ----

    /** The 35° loft of two L-sections whose side faces lean — `BlendCornerCanalTest`'s own slanted upright. */
    private fun lofted(cx: Construction): SolidRef {
        val lo = listOf(Vec2(0.0, 0.0), Vec2(60.0, 0.0), Vec2(60.0, 30.0), Vec2(30.0, 30.0), Vec2(30.0, 60.0), Vec2(0.0, 60.0))
        val shift = height * tan(35.0 * PI / 180.0) / sqrt(2.0)
        val scale = 1.0 - shift / 30.0

        fun area(pts: List<Vec2>): RegionRef {
            val ps = pts.mapIndexed { i, p -> cx.freePoint("F${ids++}", p.x.mm, p.y.mm) }
            return cx.region(cx.loop(*pts.indices.map { cx.segment(ps[it], ps[(it + 1) % pts.size]) }.toTypedArray()))
        }
        return cx.loft(
            listOf(
                LoftPart.Area(cx.sketchOn(cx.planeXY(), area(lo))),
                LoftPart.Area(cx.sketchOn(cx.planeOffset(cx.planeXY(), cx.const(height.mm)), area(lo.map { it * scale }))),
            ),
        )
    }

    /**
     * **A pivot's corner face bored through.** The surface between the two band ends at a loft's leaning
     * inside corner is a canal too (slice 5h), so it is the same carrier — and a bore straight through the
     * corner has to find it.
     */
    @Test
    fun aPivotsCornerFaceGoesThroughTheBooleanToo() {
        requireEngine()
        val cx = Construction()
        val base = lofted(cx)
        val shift = height * tan(35.0 * PI / 180.0) / sqrt(2.0)
        val scale = 1.0 - shift / 30.0
        val v = Vec3(30.0 * scale, 30.0 * scale, height)
        val es = edgesOf(Evaluator().solid(base))
        val pair =
            es.indices.filter { i ->
                val e = es[i]
                if (!e.between.a.label.render().contains("section 2's own face") && !e.between.b.label.render().contains("section 2's own face")) return@filter false
                val path = Blend3.edgePath(e).first ?: return@filter false
                val s = path.start ?: return@filter false
                val t = path.end ?: return@filter false
                (s - v).length() < 1e-6 || (t - v).length() < 1e-6
            }
        assertEquals(2, pair.size, "the loft's cap turns a reflex corner between two of its own edges")
        val ref = round(cx, base, pair, 3.0)
        val r = Evaluator().eval(ref.node)
        assumeTrue(r !is EvalResult.Invalid, "the slanted upright carries the pivot")
        val body = Evaluator().solid(ref)
        assertManifold(body.mesh, "the loft's inside corner rounded")
        val corner = assertNotNull(facesOf(body).firstOrNull { it.pipe != null }, "the corner face is a canal, and it is a pipe")
        assertTrue(corner.outline.isNotEmpty(), "…which states its own trim")

        val bored = Evaluator().solid(cx.subtract(ref, drill(cx, Plane3(Vec3.ZERO, Vec3.X, Vec3.Y), Vec2(v.x, v.y), 2.0)))
        assertManifold(bored.mesh, "the pivot bored through its own corner")
        val fs = namedOrRefused(bored, "the pivot bored through its own corner") ?: return
        assertTrue(fs.any { it.pipe != null }, "the pivot's canal is still named after the boolean")
        closesOrSaysWhy(bored, Plane3(Vec3(0.0, 0.0, height - 1.0), Vec3.X, Vec3.Y), "a level section through the bored pivot")
        println("canal carrier | pivot bored through its corner | ${fs.size} faces")
    }

    // ---- (d) every pose ----

    /**
     * **Every pose.** The carrier is a statement about surfaces and must not know where the world's axes
     * are: the same body sketched on five planes builds, is manifold, takes the same volume, and its level
     * section — in the pose's own plane — closes every time.
     */
    @Test
    fun everyPoseOfABoredCanalBodyBuildsAndIsNamedOrSaysWhy() {
        requireEngine()
        val poses =
            listOf(
                "XY" to Plane3(Vec3.ZERO, Vec3.X, Vec3.Y),
                "shifted YZ" to Plane3(Vec3(17.0, -9.0, 4.0), Vec3.Y, Vec3.Z),
                "turned 30° about x" to Plane3(Vec3.ZERO, Vec3.X, Vec3(0.0, cos(PI / 6), sin(PI / 6))),
                "turned 30° about y" to Plane3(Vec3.ZERO, Vec3(cos(PI / 6), 0.0, -sin(PI / 6)), Vec3.Y),
                "turned 45° about z and shifted" to Plane3(Vec3(-3.0, 11.0, -7.0), Vec3(1.0, 1.0, 0.0) * (1.0 / sqrt(2.0)), Vec3.Z),
            )
        var first: Double? = null
        var refused = 0
        for ((what, plane) in poses) {
            val cx = Construction()
            val canal = canalBody(cx, plane)
            val bored = Evaluator().solid(cx.subtract(canal, drill(cx, plane, Vec2(12.0, 12.0), 3.0)))
            assertManifold(bored.mesh, "the bored canal body on $what")
            val v = Geom3.volume(bored.mesh)
            if (first == null) first = v else assertClose(v, first, tol = 2e-2, msg = "the same body on $what takes the same volume")
            val fs = namedOrRefused(bored, "the bored canal body on $what")
            if (fs == null) {
                refused++
                continue
            }
            assertTrue(fs.any { it.pipe != null }, "the canal band is named on $what")
            closesOrSaysWhy(
                bored,
                Plane3(plane.origin + plane.normal.normalized() * 17.5, plane.u, plane.v),
                "the level section of the bored canal body on $what",
            )
            println("canal carrier | pose $what | volume $v")
        }
        // the body is the same body in every pose, so the drawing may not be *wrong* in any of them; the
        // one it cannot name it refuses by name, and the count is the slice's own open measure (see the
        // class note)
        assertTrue(refused < poses.size, "at least one pose of the bored canal body is named")
        println("canal carrier | poses | ${poses.size - refused} named of ${poses.size}")
    }

    // ---- (e) what a fitted crease reached ----

    /**
     * **The tolerance every fitted crease of the bored body reached**, asserted the way slice 5c asserts a
     * skew cylinder pair's: each point of the chain stands within the stated distance of **both** surfaces it
     * runs between, and the chain says the distance.
     */
    @Test
    fun everyFittedCreaseOfTheBoredBodyStandsWithinTheToleranceItStates() {
        requireEngine()
        val cx = Construction()
        val canal = canalBody(cx)
        val plain = Evaluator().solid(canal)
        val pipe = assertNotNull(facesOf(plain).firstOrNull { it.pipe != null }, "the canal band is a pipe").pipe!!
        val bore = 1.5
        val bored = Evaluator().solid(cx.subtract(canal, drill(cx, Plane3(Vec3.ZERO, Vec3.X, Vec3.Y), Vec2(38.0, 28.0), bore)))
        val fitted = edgesOf(bored).filter { it.reason == null && it.geom is EdgeGeom.InSpace && it.fitted != null }
        assertTrue(fitted.isNotEmpty(), "the bore through the band leaves creases this drawing has no name for")
        var worst = 0.0
        var onThePipe = 0
        for (e in fitted) {
            val tol = assertNotNull(e.fitted)
            val pts = (e.geom as EdgeGeom.InSpace).chain.flatMap { span -> (0..8).map { Frames3.pointAt(span, it / 8.0) } }

            // **which crease runs on the pipe is read off the pipe itself**, not off a name: every point of
            // it stands on the canal band's own surface
            fun offBore(p: Vec3) = abs(sqrt((p.x - 38.0) * (p.x - 38.0) + (p.y - 28.0) * (p.y - 28.0)) - bore)
            if (pts.any { abs(pipe.offset(it)) > max(tol, 1e-2) || offBore(it) > max(tol, 1e-2) }) continue
            onThePipe++
            for (p in pts) {
                val d = abs(sqrt((p.x - 38.0) * (p.x - 38.0) + (p.y - 28.0) * (p.y - 28.0)) - bore)
                val q = abs(pipe.offset(p))
                worst = max(worst, max(d, q))
                assertTrue(d <= tol + 1e-9, "${e.name.label.render()} stands within its stated $tol mm of the bore — $d")
                assertTrue(q <= tol + 1e-9, "…and of the canal's own pipe — $q")
            }
        }
        assertTrue(onThePipe > 0, "the bore leaves at least one crease running on the canal's own pipe")
        println("canal carrier | fitted creases | ${fitted.size}, $onThePipe on the pipe, worst $worst")
    }
}
