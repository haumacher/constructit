package constructit

import constructit.expr.ExprParser
import constructit.geom.Curve3Element
import constructit.geom.Curves3
import constructit.geom.Frames3
import constructit.geom.Geom3
import constructit.geom.GeomMath
import constructit.geom.Loop
import constructit.geom.Mesh3
import constructit.geom.Path3
import constructit.geom.ProfileElement
import constructit.geom.Region
import constructit.geom.Segment
import constructit.geom.SizeLaw
import constructit.geom.SizeLaws
import constructit.geom.Solid3
import constructit.geom.SweepProfile
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.units.Dimension
import constructit.units.Quantity
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **The variable-section sweep, at the level of the geometry itself** (OP-26, session 77 — queue entry 7).
 *
 * Session 42 parked this as *"the only thing that would relax the single derived reach"*, with no way to
 * **state** that a section changes size; the expression language is that missing vocabulary, so what was
 * unstatable is one [SizeLaw] over the run parameter `t`. Three things are asserted here and they are the
 * three the ruling names:
 *
 * - **the body is what the law says** — a linear radius law over a straight run is a cone frustum, exactly,
 *   and its volume is asserted twice in OP-15's honesty class (the polygon the mesh is made of, to the last
 *   bit, and the analytic frustum to within the tessellation's own sagitta);
 * - **the refusal criteria read the law at their own station** — a run whose *large* end stands where there
 *   is no bend builds, and the same run with the law reversed refuses in the bend's own words;
 * - **a law that reads no `t` builds byte-identically** to the constant section it is, which is the
 *   frozen-reading half of the ruling said as a mesh comparison.
 *
 * Every solid built here goes through [assertManifold]: watertight or refused (OP-9), no exception for the
 * newest reading of the oldest feature.
 */
class SweepLawTest {
    private val up = Vec3.Z

    private fun straight(
        a: Vec3,
        b: Vec3,
    ) = Path3(Curves3.straightThrough(listOf(a, b)))

    /** A radius law `r(t)` from its text — the tube's own reading, a length (OP-26, session 77). */
    private fun radiusLaw(
        text: String,
        env: Map<String, Quantity> = emptyMap(),
    ) = SizeLaw(ExprParser.parse(text), env, Dimension.LENGTH, "t", text)

    /** A scale law `scale(t)` from its text — an arbitrary section's own reading, a plain number. */
    private fun scaleLaw(
        text: String,
        env: Map<String, Quantity> = emptyMap(),
    ) = SizeLaw(ExprParser.parse(text), env, Dimension.NONE, "t", text)

    private fun rectangle(
        w: Double,
        h: Double,
    ): Region =
        Region(
            Loop(
                listOf(Vec2(-w / 2, -h / 2), Vec2(w / 2, -h / 2), Vec2(w / 2, h / 2), Vec2(-w / 2, h / 2)).let { pts ->
                    pts.indices.map { ProfileElement.Seg(Segment(pts[it], pts[(it + 1) % pts.size])) }
                },
            ),
            emptyList(),
        )

    private fun sweptOrFail(
        path: Path3,
        profile: SweepProfile,
    ): Solid3 {
        val (solid, why) = Geom3.sweep(path, up, profile)
        return assertNotNull(solid, "the sweep was refused: $why")
    }

    private fun refusal(
        path: Path3,
        profile: SweepProfile,
    ): String {
        val (solid, why) = Geom3.sweep(path, up, profile)
        assertNull(solid, "this sweep was expected to be refused")
        return assertNotNull(why, "a refusal says why")
    }

    /** The tessellated area of a section at its stated size — what an exact volume is measured against. */
    private fun tessArea(profile: SweepProfile): Double {
        val (t, why) = Geom3.tessellateRegion(profile.region)
        return Geom3.tessArea(assertNotNull(t, why))
    }

    /** The greatest distance any vertex on the plane `x = [x]` stands from the run's axis (the world x axis). */
    private fun ringRadius(
        mesh: Mesh3,
        x: Double,
    ): Double {
        val on = mesh.vertices.filter { kotlin.math.abs(it.x - x) < 1e-9 }
        assertTrue(on.size >= 3, "there is a ring at x = $x, and it has ${on.size} vertices")
        return on.maxOf { hypot(it.y, it.z) }
    }

    // ---- 1. the taper: a linear radius law over a straight run is a cone frustum ----

    /**
     * **A tapered handle.** `r(t) = 5mm · (1 − t/2)` along a straight run: the mesh is one band between two
     * rings — the frustum a straight piece needs and no station more, since a linear law's chord *is* the law
     * ([SizeLaws.spans] answers zero) — and it is a cone frustum to the bit.
     *
     * The volume is asserted **twice**, exactly as the cylinder's is ([SweepTest]): the polygonal frustum the
     * mesh is made of, analytically, and `π L (r₀² + r₀r₁ + r₁²)/3` to within what the tessellation itself
     * costs. The polygonal figure is exact because the linear interpolation between two *similar* polygons is
     * the similar polygon at the interpolated size, so the band is `∫ A(s) ds` and nothing is approximated
     * about the taper at all.
     */
    @Test
    fun aTubeWhoseRadiusTapersIsExactlyAConeFrustum() {
        val r0 = 5.0
        val length = 120.0
        val law = radiusLaw("5mm * (1 - t/2)")
        val profile = SweepProfile.of(law)
        assertEquals(r0, profile.radius, "the region is built at the law's value at the start of the run")

        val solid = sweptOrFail(straight(Vec3(0.0, 0.0, 0.0), Vec3(length, 0.0, 0.0)), profile)
        val mesh = solid.mesh
        assertManifold(mesh, "the tapered handle")

        // the two end caps stand at exactly the radii the law states there
        assertClose(ringRadius(mesh, 0.0), r0, 1e-12, "the start ring is the law at t = 0")
        assertClose(ringRadius(mesh, length), r0 / 2.0, 1e-12, "and the end ring is the law at t = 1")
        assertEquals(
            mesh.vertices.size,
            mesh.vertices.count { kotlin.math.abs(it.x) < 1e-9 || kotlin.math.abs(it.x - length) < 1e-9 },
            "a linear law over a straight run is one band: two rings of vertices and nothing between them",
        )

        val k = 0.5
        val a0 = tessArea(SweepProfile.Round(r0))
        assertClose(
            Geom3.volume(mesh),
            a0 * length * (1.0 + k + k * k) / 3.0,
            1e-9,
            "the body is exactly the frustum over its own tessellated circle",
        )
        assertClose(
            Geom3.volume(mesh),
            PI * length * (r0 * r0 + r0 * (r0 * k) + (r0 * k) * (r0 * k)) / 3.0,
            2.0 * PI * r0 * GeomMath.TESS_TOL_MM * length,
            "and the analytic cone frustum to within the tessellation",
        )
    }

    /**
     * **A horn.** `r(t) = 2mm + 8mm·t·t` is *not* a straight taper, and a straight piece is one span by
     * nature — so the run is refined for the **law**, or the picture would be the cone its two end rings
     * would otherwise be joined into.
     *
     * The refinement is a pure function of the law, the reach and the tolerance ([SizeLaws.spans]), never of
     * anything a picture asks for, and what it is asserted by is the shape it buys: the radius grows
     * *monotonically and faster than linearly* along the run, which is the one claim a cone would fail.
     */
    @Test
    fun aHornIsRefinedForItsOwnLawAndNotDrawnAsACone() {
        val length = 100.0
        val profile = SweepProfile.of(radiusLaw("2mm + 8mm * t * t"))
        assertEquals(2.0, profile.radius, "the horn starts at the law's own value")
        assertTrue(SizeLaws.spans(profile, 2.0, GeomMath.TESS_TOL_MM) >= 8, "the law asks the run to be cut up")

        val solid = sweptOrFail(straight(Vec3(0.0, 0.0, 0.0), Vec3(length, 0.0, 0.0)), profile)
        val mesh = solid.mesh
        assertManifold(mesh, "the horn")
        assertClose(ringRadius(mesh, 0.0), 2.0, 1e-12, "the throat is the law at t = 0")
        assertClose(ringRadius(mesh, length), 10.0, 1e-12, "and the mouth is the law at t = 1")

        // the middle station is where a cone and a horn part company: a cone would be at 6 mm there, the
        // law says 4 mm, and the mesh has to be the law's
        val xs = mesh.vertices.map { it.x }.distinct().sorted()
        assertTrue(xs.size > 2, "the run carries stations between its ends: $xs")
        val mid = xs.minByOrNull { kotlin.math.abs(it - length / 2.0) }!!
        assertClose(ringRadius(mesh, mid), 2.0 + 8.0 * (mid / length) * (mid / length), 1e-9, "and every ring is the law there")
    }

    /**
     * **A section-swept taper**, which is the other half of the one mechanism: an arbitrary area takes a
     * dimensionless `scale(t)` about its anchor, and nothing about its own sketch is re-read.
     *
     * The rectangle is centred on the run, so the scale is about its centre; the volume is the same polygonal
     * frustum figure the tube's is, because *any* two similar polygons interpolate to the similar one.
     */
    @Test
    fun anArbitrarySectionIsScaledRigidlyByItsLaw() {
        val w = 20.0
        val h = 8.0
        val length = 90.0
        val profile = SweepProfile.Section(rectangle(w, h), scaleLaw("1 - t/2"))
        val solid = sweptOrFail(straight(Vec3(0.0, 0.0, 0.0), Vec3(length, 0.0, 0.0)), profile)
        val mesh = solid.mesh
        assertManifold(mesh, "the tapered bar")

        // the frame at the start of a +x run in the plan is (ref = +Z, bi = X x Z = -Y), so the section's own
        // (x, y) maps to world (+Z, -Y): the 20 mm side runs along z and the 8 mm side along y
        val start = mesh.vertices.filter { kotlin.math.abs(it.x) < 1e-9 }
        val end = mesh.vertices.filter { kotlin.math.abs(it.x - length) < 1e-9 }
        assertClose(start.maxOf { it.z } - start.minOf { it.z }, w, 1e-12, "the section is its stated size at the start")
        assertClose(end.maxOf { it.z } - end.minOf { it.z }, w / 2.0, 1e-12, "and half that at the end")
        assertClose(end.maxOf { it.y } - end.minOf { it.y }, h / 2.0, 1e-12, "in both directions at once — one uniform scale")

        val k = 0.5
        assertClose(
            Geom3.volume(mesh),
            w * h * length * (1.0 + k + k * k) / 3.0,
            1e-9,
            "and the volume is the frustum over the rectangle it states",
        )
    }

    // ---- 2. what a law refuses, and in whose words ----

    /**
     * **A radius that crosses zero part-way along refuses, naming the station and the value there** — in the
     * constant refusal's own wording (*"a tube needs a positive radius"*), which heals per OP-3: move the
     * parameter it reads and the body comes back.
     */
    @Test
    fun aRadiusLawThatCrossesZeroRefusesNamingTheStationAndTheValue() {
        val why = refusal(straight(Vec3(0.0, 0.0, 0.0), Vec3(100.0, 0.0, 0.0)), SweepProfile.of(radiusLaw("5mm * (1 - 2*t)")))
        assertTrue(why.contains("a tube needs a positive radius"), "the constant refusal's own words: $why")
        assertTrue(why.contains("r(t) = 5mm * (1 - 2*t)"), "and it quotes the law, so it can be acted on: $why")
        assertTrue(why.contains("at t = 0.5"), "and names the station it goes wrong at: $why")
        assertTrue(why.contains("along the run"), "in the run's own vocabulary: $why")
    }

    /** …and the same sentence for an arbitrary section's **scale**, which is the other reading of one law. */
    @Test
    fun aScaleLawThatGoesNonPositiveRefusesInTheSectionsWords() {
        val why =
            refusal(
                straight(Vec3(0.0, 0.0, 0.0), Vec3(100.0, 0.0, 0.0)),
                SweepProfile.Section(rectangle(20.0, 8.0), scaleLaw("1 - t")),
            )
        assertTrue(why.contains("a swept section needs a positive scale"), "the section's own words: $why")
        assertTrue(why.contains("scale(t) = 1 - t"), "quoting the law: $why")
        assertTrue(why.contains("at t = 1"), "and the station it fails at: $why")
    }

    /**
     * **A radius law of the wrong dimension refuses through the ordinary dimension check** — an angle where a
     * length was wanted, said in the words the panel's own formula field says it in ([SizeLaw.at] throws the
     * very [constructit.units.DimensionError] every other expression does).
     */
    @Test
    fun anAngleValuedRadiusLawRefusesThroughTheDimensionCheck() {
        val why = refusal(straight(Vec3(0.0, 0.0, 0.0), Vec3(100.0, 0.0, 0.0)), SweepProfile.Round(5.0, radiusLaw("30deg * t + 10deg")))
        assertTrue(why.contains("r(t) = 30deg * t + 10deg"), "the law names itself: $why")
        assertTrue(why.contains("must be a length"), "and says what it had to be: $why")
        assertTrue(why.contains("and this is A"), "and what it is instead — the dimension's own word: $why")
    }

    /** …and a **scale** that is a length rather than a plain number is the same refusal, one dimension over. */
    @Test
    fun aLengthValuedScaleLawRefusesThroughTheSameCheck() {
        val why =
            refusal(
                straight(Vec3(0.0, 0.0, 0.0), Vec3(100.0, 0.0, 0.0)),
                SweepProfile.Section(rectangle(20.0, 8.0), scaleLaw("2mm")),
            )
        assertTrue(why.contains("must be a plain number"), "a scale is dimensionless, and it says so: $why")
    }

    // ---- 3. `t` is a binder, and it outranks a drawing scalar of that name ----

    /**
     * **`t` is the run's own parameter and wins over a drawing scalar called `t`** — the same contract the
     * function curves carry, context-local and permanent.
     *
     * Asserted where it is decided: the law's environment here *does* carry a value named `t` (50 mm, as
     * hostile a value as one could pick), and the body that comes out is the one `t = 0 … 1` states.
     */
    @Test
    fun theRunParameterOutranksADrawingScalarOfTheSameName() {
        val hostile = mapOf("t" to Quantity.mm(50.0))
        val profile = SweepProfile.of(radiusLaw("5mm * (1 - t/2)", hostile))
        assertEquals(5.0, profile.radius, "the start of the run is t = 0, not the drawing's 50 mm")
        val mesh = sweptOrFail(straight(Vec3(0.0, 0.0, 0.0), Vec3(60.0, 0.0, 0.0)), profile).mesh
        assertManifold(mesh, "the tube whose law shadows a drawing scalar")
        assertClose(ringRadius(mesh, 60.0), 2.5, 1e-12, "and the end of the run is t = 1")
    }

    /** …and a law that reads an ordinary parameter reads **its** value, which is what makes a taper follow one. */
    @Test
    fun aLawReadsTheParametersItNames() {
        val small = sweptOrFail(straight(Vec3.ZERO, Vec3(80.0, 0.0, 0.0)), SweepProfile.of(radiusLaw("r * (1 - t/2)", mapOf("r" to Quantity.mm(4.0)))))
        val large = sweptOrFail(straight(Vec3.ZERO, Vec3(80.0, 0.0, 0.0)), SweepProfile.of(radiusLaw("r * (1 - t/2)", mapOf("r" to Quantity.mm(8.0)))))
        assertClose(ringRadius(small.mesh, 0.0), 4.0, 1e-12, "the law is the parameter it reads")
        assertClose(ringRadius(large.mesh, 0.0), 8.0, 1e-12, "and it follows it")
        // each is exactly the frustum over *its own* tessellated circle — the two polygon counts differ, so
        // the honest comparison is against each body's own section rather than a ratio of the two
        val k = 0.5
        for ((r, body) in listOf(4.0 to small, 8.0 to large)) {
            assertClose(
                Geom3.volume(body.mesh),
                tessArea(SweepProfile.Round(r)) * 80.0 * (1.0 + k + k * k) / 3.0,
                1e-9,
                "a $r mm law sweeps the frustum over its own circle",
            )
        }
    }

    // ---- 4. the criteria are functions of the station — the load-bearing half of the ruling ----

    /**
     * A quarter arc of 60 mm radius followed by a straight tail tangent to it — the fixture the per-station
     * reading is asserted on, and deliberately **corner-free** (the join is tangential, so no mitre exists
     * there and only the local and global terms can speak).
     */
    private fun bendThenStraight(): Path3 {
        val arc =
            Curve3Element.Arc3(
                center = Vec3(0.0, 0.0, 0.0),
                u = Vec3.X,
                v = Vec3.Y,
                radius = 60.0,
                startAngle = 0.0,
                sweepAngle = PI / 2.0,
            )
        return Path3(listOf(arc, Curve3Element.Seg3(arc.end, arc.end + Vec3(-220.0, 0.0, 0.0))))
    }

    /**
     * **The refusal criteria became functions of the station** (session 77's ruling (c)), and this is the
     * claim in one test: a run that bends tightly at its start and runs straight afterwards carries a section
     * that is *small where the bend is and large where it is not* — and it builds.
     *
     * Under the reading this feature replaces there was **one** derived reach for the whole run
     * (`tess.outer.maxOf { it.length() }`), so the 70 mm end would have been asked about at the 60 mm bend
     * and the body refused. The constant tube at that size still is, in the same test and in the same words,
     * which is what makes this a change of *where the size is read* rather than a relaxed criterion.
     */
    @Test
    fun theLocalTermReadsTheSizeAtItsOwnStationAndNotTheRunsLargest() {
        val path = bendThenStraight()
        // what the old whole-run reading would have asked, and still asks of a section of one size
        val constant = refusal(path, SweepProfile.Round(70.0))
        assertTrue(constant.contains("larger than the bend"), "a 70 mm tube does not fit a 60 mm bend: $constant")

        // …and the same 70 mm, stated where the run is straight, builds
        val tapered = sweptOrFail(path, SweepProfile.of(radiusLaw("15mm + 55mm * t")))
        assertManifold(tapered.mesh, "the tube that is thin where the run bends")
        assertTrue(Geom3.volume(tapered.mesh) > 0.0, "and it encloses material")
    }

    /** …**and the converse refuses**, in the bend's own words: the same law, run the other way round. */
    @Test
    fun theLocalTermStillRefusesWhereTheLawIsLargeAtTheBend() {
        val why = refusal(bendThenStraight(), SweepProfile.of(radiusLaw("70mm - 55mm * t")))
        assertTrue(why.contains("the run starts with"), "the bend is named as the one the run starts with: $why")
        assertTrue(why.contains("radius 60 mm"), "and the bend's own radius is quoted: $why")
        assertTrue(why.contains("the tube's radius there"), "and the size *there*, which is the whole point: $why")
        assertTrue(why.contains("70 mm"), "which is 70 mm at the start of this run: $why")
        assertTrue(why.contains("pass through itself"), "and what would go wrong: $why")
        assertTrue(!why.contains("station") && !why.contains("sampl"), "and nothing about the mesh (session 65): $why")
    }

    /**
     * **The global term reads it per station too** — the bottleneck between two legs of a U needs what the
     * two sections reach *towards each other*, each at its own size.
     *
     * A 40 mm gap between two parallel legs: a constant 24 mm tube needs 48 mm and refuses; the same tube
     * tapered so that one leg is thin and the other thick needs less than the gap and builds. The refusal
     * quotes the gap and the need, and neither figure is the mesh's.
     */
    @Test
    fun theGlobalTermAddsTheTwoStationsOwnSizes() {
        // a U in the plan: out along +x, across, and back — the two long legs 40 mm apart
        val path =
            Path3(
                Curves3.straightThrough(
                    listOf(
                        Vec3(0.0, 0.0, 0.0),
                        Vec3(300.0, 0.0, 0.0),
                        Vec3(300.0, 40.0, 0.0),
                        Vec3(0.0, 40.0, 0.0),
                    ),
                ),
            )
        val constant = refusal(path, SweepProfile.Round(24.0))
        assertTrue(constant.contains("passes within"), "the two legs are the bottleneck: $constant")
        assertTrue(constant.contains("48 mm"), "and a 24 mm tube needs 48 mm between them: $constant")

        // …and a law that is 24 mm on the way out and 10 mm on the way back needs 34 mm, which fits
        val tapered = sweptOrFail(path, SweepProfile.of(radiusLaw("24mm - 14mm * t")))
        assertManifold(tapered.mesh, "the U whose return leg is thin")
    }

    /**
     * **The corner term reads it at the corner** — a mitre bites `k·(w·g)` for the ring's own size, so a leg
     * between two corners is judged by the two factors its two corners carry.
     *
     * Session 65's fixture, restated: two ~85° corners 30.114 mm apart with an 18 mm tube take 34.508 mm off
     * a 30.114 mm leg and refuse. Made **thin where those corners are** the same route builds — and the
     * refusal, where it still fires, quotes a figure scaled by the law and nothing about the sampling.
     */
    @Test
    fun theCornerTermMitresAtTheRingsOwnSize() {
        val route =
            Path3(
                Curves3.straightThrough(
                    listOf(
                        Vec3(0.0, 0.0, 0.0),
                        Vec3(300.0, 0.0, 0.0),
                        Vec3(302.62, 30.0, 0.0),
                        Vec3(0.0, 56.24, 0.0),
                    ),
                ),
            )
        val constant = refusal(route, SweepProfile.Round(18.0))
        assertTrue(constant.contains("mitre"), "the two corners eat more run than there is: $constant")
        assertTrue(constant.contains("fold back on itself"), "and it says what would go wrong: $constant")

        // the two corners stand at about t = 0.44 and t = 0.49 of this run, so a law that thins the middle
        // is exactly the statement "the mitres bite a smaller ring there"
        val tapered = sweptOrFail(route, SweepProfile.of(radiusLaw("18mm * (1 - 0.85 * min(t, 1 - t) * 2)")))
        assertManifold(tapered.mesh, "the tube that is thin where its corners are")
    }

    // ---- 5. the frozen reading: a law that reads no `t` is the constant section it is ----

    /**
     * **A law that never reads the station builds exactly what the constant section builds** — vertex for
     * vertex and triangle for triangle.
     *
     * That is the ruling's own frozen-reading clause said as a mesh comparison: `scaleAt` answers exactly
     * `1.0`, so not one coordinate is multiplied by anything but one, and every criterion's arithmetic is
     * the arithmetic it always was.
     */
    @Test
    fun aLawThatReadsNoStationBuildsTheConstantBodyByteForByte() {
        val path = Path3(Curves3.straightThrough(listOf(Vec3(0.0, 0.0, 0.0), Vec3(90.0, 0.0, 0.0), Vec3(90.0, 70.0, 0.0))))
        val plain = sweptOrFail(path, SweepProfile.Round(7.0))
        val lawed = sweptOrFail(path, SweepProfile.of(radiusLaw("7mm")))
        assertEquals(plain.mesh.vertices, lawed.mesh.vertices, "the same vertices, in the same order")
        assertEquals(plain.mesh.triangles, lawed.mesh.triangles, "and the same triangles")
        assertEquals(0, SizeLaws.spans(SweepProfile.of(radiusLaw("7mm")), 7.0, GeomMath.TESS_TOL_MM), "and it asks for no refinement")
    }

    /** …and the same for an arbitrary section whose scale law is the identity. */
    @Test
    fun aScaleLawOfOneBuildsTheUnscaledSectionByteForByte() {
        val path = Path3(Curves3.straightThrough(listOf(Vec3(0.0, 0.0, 0.0), Vec3(120.0, 0.0, 0.0))))
        val region = rectangle(20.0, 8.0)
        val plain = sweptOrFail(path, SweepProfile.Section(region))
        val lawed = sweptOrFail(path, SweepProfile.Section(region, scaleLaw("1")))
        assertEquals(plain.mesh.vertices, lawed.mesh.vertices, "the same vertices, in the same order")
        assertEquals(plain.mesh.triangles, lawed.mesh.triangles, "and the same triangles")
    }

    /**
     * **The plan hint follows the law** ([constructit.geom.Silhouette.ofSwept]), because it is read off the
     * run rather than off the triangles and the law travels with the feature.
     *
     * A tapered tube's footprint therefore reaches its stated radius at each end — which is what makes a
     * click on the wide end of a horn land on the horn.
     */
    @Test
    fun thePlanOfATaperedTubeReachesItsStatedRadiusAtEachEnd() {
        val plane = constructit.geom.Plane3(Vec3.ZERO, Vec3.X, Vec3.Y)
        val (solid, why) =
            Geom3.sweep(
                straight(Vec3(0.0, 0.0, 0.0), Vec3(150.0, 0.0, 0.0)),
                up,
                SweepProfile.of(radiusLaw("4mm + 16mm * t")),
                plan = plane,
            )
        val body = assertNotNull(solid, why)
        val feature = body.feature as constructit.geom.Feature3.Sweep
        val ys = feature.plan.flatMap { r -> r.outer.elements.map { GeomMath.startOf(it).y } }
        assertTrue(ys.isNotEmpty(), "the tapered tube shows a plan")
        assertClose(ys.maxOf { kotlin.math.abs(it) }, 20.0, 1e-9, "and it reaches the law's own value at the wide end")
    }

    /**
     * **The law is a fact of the feature**, so a body rebuilt from it alone rebuilds the identical plan —
     * which is what a placement into another space asks for (`Geom3.sweptPlan`).
     */
    @Test
    fun theFeatureAloneRebuildsTheTaperedPlan() {
        val plane = constructit.geom.Plane3(Vec3.ZERO, Vec3.X, Vec3.Y)
        val (solid, why) =
            Geom3.sweep(
                straight(Vec3(0.0, 0.0, 0.0), Vec3(150.0, 0.0, 0.0)),
                up,
                SweepProfile.of(radiusLaw("4mm + 16mm * t")),
                plan = plane,
            )
        val feature = assertNotNull(solid, why).feature as constructit.geom.Feature3.Sweep
        val again = Geom3.sweptPlan(feature, plane)
        assertEquals(
            feature.plan.map { r -> r.outer.elements.map { GeomMath.startOf(it) } },
            again.map { r -> r.outer.elements.map { GeomMath.startOf(it) } },
            "the rebuild from the feature is the identical outline",
        )
    }

    /**
     * **The law's own grid is fixed and owes nothing to the mesh** (the session-65 rule): the same drawing
     * meshed at two decades of tessellation tolerance refuses — or does not — identically, and says the same
     * words.
     */
    @Test
    fun refiningTheMeshChangesNeitherTheVerdictNorTheWords() {
        val path = bendThenStraight()
        val profile = SweepProfile.of(radiusLaw("70mm - 55mm * t"))
        val words = HashSet<String>()
        for (tol in listOf(0.2, 0.02, 0.002)) {
            val (solid, why) = Geom3.sweep(path, up, profile, tolMm = tol)
            assertNull(solid, "refused at every tolerance")
            words.add(assertNotNull(why))
        }
        assertEquals(1, words.size, "and in one set of words: $words")

        val fits = SweepProfile.of(radiusLaw("15mm + 55mm * t"))
        for (tol in listOf(0.2, 0.02, 0.002)) {
            val (solid, why) = Geom3.sweep(path, up, fits, tolMm = tol)
            assertManifold(assertNotNull(solid, why).mesh, "the body that fits, at tolerance $tol")
        }
    }

    /**
     * **One `t` over the whole run, by arc length** — never one per piece, which is the alternative session
     * 77 rejected because it does not survive a multi-piece run.
     *
     * The fixture is two straight pieces of *very different lengths*: a per-piece parameter would put the
     * law's half-way value at the join, and the arc-length map puts it where half the run is.
     */
    @Test
    fun theParameterIsArcLengthOverTheWholeRunAndNotPerPiece() {
        val join = Vec3(30.0, 0.0, 0.0)
        val path = Path3(Curves3.straightThrough(listOf(Vec3(0.0, 0.0, 0.0), join, Vec3(300.0, 0.0, 0.0))))
        val mesh = sweptOrFail(path, SweepProfile.of(radiusLaw("10mm - 8mm * t"))).mesh
        assertManifold(mesh, "the two-piece tapered tube")
        // the join stands 30 mm along a 300 mm run, so the law is read at t = 0.1 there — 9.2 mm, not the
        // 6 mm a per-piece reading (half way through piece one of two) would have made it
        assertClose(ringRadius(mesh, 30.0), 10.0 - 8.0 * 0.1, 1e-9, "the join is read at its own arc length")
        assertClose(ringRadius(mesh, 300.0), 2.0, 1e-12, "and the end of the run is t = 1")
    }

    /** …and the arc-length map is the one the frame itself carries, asserted directly on a station. */
    @Test
    fun theStationsCarryTheLawTheArcLengthMapStates() {
        val path = Path3(Curves3.straightThrough(listOf(Vec3(0.0, 0.0, 0.0), Vec3(200.0, 0.0, 0.0))))
        val profile = SweepProfile.of(radiusLaw("10mm - 8mm * t"))
        val (frame, why) = Frames3.along(path, up, reach = 10.0, lawSpans = 4)
        val f = assertNotNull(frame, why)
        val (scales, noScale) = SizeLaws.scalesAlong(profile, f)
        assertNull(noScale, "every station has a size")
        val ks = assertNotNull(scales)
        assertEquals(f.stations.size, ks.size, "one factor per station")
        for ((i, st) in f.stations.withIndex()) {
            assertClose(ks[i] * profile.radius, 10.0 - 8.0 * (st.s / f.length), 1e-12, "station $i carries the law at its own arc length")
        }
    }
}
