package constructit

import constructit.expr.ExprParser
import constructit.geom.Curve3Element
import constructit.geom.Curves3
import constructit.geom.Frames3
import constructit.geom.Geom3
import constructit.geom.GeomMath
import constructit.geom.Handedness
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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **The warp of a twisted sweep's own facets** (OP-26, session 80) — the refinement term that measures what
 * a twist actually deviates by, and the volumes that says are now right.
 *
 * What was wrong, in the words session 79 recorded it in: a twisted sweep's sampling was refined by
 * [GeomMath.chordSteps], which measures the **rails** — a section vertex `reach` mm off the axis turning by
 * `Δ` leaves its own arc by `reach·(1 − cos(Δ/2))`. That is the right measure for a rail and the wrong one
 * for the surface between two rails: between consecutive stations each lateral quad's four corners stop
 * being coplanar, the diagonal that splits it cuts inward across the whole face, and the loss is **first**
 * order in the turn where the rail's is second. Measured then: a constant 200 × 24 mm section swept 1000 mm
 * at a stated 15° meshed to 4 204 506 mm³ against an exact 4 800 000 — **12.4% low**, watertight, every
 * dimension right, on the ordinary constant route that has always existed.
 *
 * What is exact, and why every assertion below can be made at all: **the volume of a twisted sweep of a
 * constant section along a straight run is `area × length` whatever the twist is** — every cross-section is
 * the section turned, so Cavalieri gives the same answer a prism gives. For a section that varies it is
 * `∫ area(t) dt × length` by the same argument.
 *
 * **The bound, stated in the unit the answer is read in** (OP-15). Carrying a polygon `{vᵢ}` of area `A`
 * from height 0 to `h` while turning it by `Δ`, the two triangles each quad is split into enclose
 * `(h/6)·[4A + 2A·cos Δ − (sin Δ/2)·Σeᵢ²]` where `eᵢ = |vᵢ₊₁ − vᵢ|`, against an exact `hA` — a deficit of
 * `(h/6)·[2A(1 − cos Δ) + (sin Δ/2)·Σeᵢ²]`, whose leading term is `h·Δ·Σeᵢ²/12`. Spread over `n` spans of a
 * run of length `L` and total turn `T` that is `L·T·Σeᵢ²/(12n)`, and the warp rule sets
 * `n = T·e_max/effectiveTol(e_max)`, so:
 *
 * ```
 * ΔV / V  ≤  REL_TOL · Σeᵢ² / (12·A)
 * ```
 *
 * — independent of the twist, of the length and of the reach, and a property of the **section's shape**
 * alone. 0.141% for a 200 × 24 rectangle, 0.033% for a square, 0.129% for the 200 × 120 × 20 hollow below,
 * and larger for a thinner wall (7.5 per mille for a 4 mm wall on that same 200 × 24 outline, which is the
 * honest cost of a rule that is scale-invariant rather than absolute — see [GeomMath.warpSteps]). Every
 * fixture here is asserted against **both** its own such bound and the flat 0.2% the package was set.
 */
class SweepWarpTest {
    private val up = Vec3.Z

    private fun run(length: Double) = Path3(Curves3.straightThrough(listOf(Vec3.ZERO, Vec3(length, 0.0, 0.0))))

    private fun loopOf(pts: List<Vec2>) = Loop(pts.indices.map { ProfileElement.Seg(Segment(pts[it], pts[(it + 1) % pts.size])) })

    private fun box(
        w: Double,
        h: Double,
    ) = listOf(Vec2(-w / 2, -h / 2), Vec2(w / 2, -h / 2), Vec2(w / 2, h / 2), Vec2(-w / 2, h / 2))

    private fun rect(
        w: Double,
        h: Double,
    ) = Region(loopOf(box(w, h)), emptyList())

    /** A rectangular hollow section — a wall of [t] all round, so the section is **two** loops. */
    private fun tube(
        w: Double,
        h: Double,
        t: Double,
    ) = Region(loopOf(box(w, h)), listOf(loopOf(box(w - 2 * t, h - 2 * t).reversed())))

    private fun swept(
        path: Path3,
        region: Region,
        twistRad: Double,
    ): Solid3 {
        val (s, why) = Geom3.sweep(path, up, SweepProfile.Section(region), twistRad = twistRad)
        return assertNotNull(s, "the sweep was refused: $why")
    }

    /**
     * The bound this file's KDoc derives, as a **fraction** of the body's volume:
     * `REL_TOL · Σeᵢ² / (12·A)`, read off the tessellation the mesh is actually made of and over every loop
     * the section has, holes included.
     */
    private fun warpBound(region: Region): Double {
        val tess = assertNotNull(Geom3.tessellateRegion(region).first, "the section tessellates")
        var sumE2 = 0.0
        for (loop in listOf(tess.outer) + tess.holes) {
            for (i in loop.indices) {
                val e = loop[(i + 1) % loop.size] - loop[i]
                sumE2 += e.x * e.x + e.y * e.y
            }
        }
        return GeomMath.REL_TOL * sumE2 / (12.0 * abs(Geom3.tessArea(tess)))
    }

    /** How many stations the run is cut into for [region] carried along [path] with [twistRad] of turn. */
    private fun stations(
        path: Path3,
        region: Region,
        twistRad: Double,
    ): Int {
        val tess = assertNotNull(Geom3.tessellateRegion(region).first, "the section tessellates")
        val frame =
            assertNotNull(
                Frames3.along(path, up, 0.0, twistRad, tess.outer.maxOf { it.length() }, Geom3.tessEdge(tess)).first,
                "the frame builds",
            )
        return frame.stations.size
    }

    private fun assertWithinBound(
        got: Double,
        exact: Double,
        region: Region,
        what: String,
    ) {
        val err = abs(got - exact) / exact
        val bound = warpBound(region)
        assertTrue(err <= bound * 1.05, "$what: ${err * 100}% is past its own derived bound of ${bound * 100}%")
        assertTrue(err <= 0.002, "$what: ${err * 100}% is past the package's 0.2%")
    }

    // ---- 1. the bar the defect was measured on ----

    /**
     * **The fixture session 79 measured, at three stated turns.** 200 × 24 mm swept 1000 mm: 4 800 000 mm³
     * exactly, whatever the twist, and now within a fifth of a per cent of it at 15°, 90° and half a turn.
     *
     * The three turns are one assertion and not three, because the error is the same at all of them and
     * that is itself the claim: the rule cuts the run into `n ∝ T` spans, and the deficit goes as `T/n`, so
     * a body twisted twelve times as far is meshed twelve times as finely and comes out just as right. It
     * was the same before the fix too — 12.406% at all three — which is what said the term was missing
     * rather than merely too coarse.
     */
    @Test
    fun aTwistedBarKeepsItsVolumeAtEveryStatedTurn() {
        val section = rect(200.0, 24.0)
        val exact = 200.0 * 24.0 * 1000.0
        for (deg in listOf(15.0, 90.0, 180.0)) {
            val solid = swept(run(1000.0), section, deg * PI / 180.0)
            assertManifold(solid.mesh, "the bar twisted by $deg°")
            assertWithinBound(Geom3.volume(solid.mesh), exact, section, "a 200 × 24 bar twisted $deg°")
        }
    }

    /**
     * **What the refinement costs, stated rather than discovered** — the station counts the rule asks for,
     * and the cap it can never pass.
     *
     * Before the warp term the run took 4, 19 and 37 stations for the three turns (the rail rule's own
     * count, at the relative tolerance a 100.7 mm reach earns). After it, 263, 1572 and 3143: the rail
     * rule's step is `2·acos(1 − tol/reach)` and the warp rule's is `2·asin(tol/2e)`, and with a 200 mm
     * edge against a 100.7 mm reach the second is about eighty times the finer. That is the price of the
     * missing order, paid in triangles that are all still linear in the turn — and it is bounded:
     * [SizeLaws.MAX_SPANS] caps the term per piece, silently, exactly as it caps a size law's own
     * refinement (a clamp, not a refusal — a wild formula cannot mesh for ever, and nothing about a shape
     * is decided by reaching it).
     */
    @Test
    fun theWarpRulesStationCountIsStatedAndCapped() {
        val section = rect(200.0, 24.0)
        assertEquals(2, stations(run(1000.0), section, 0.0), "an untwisted run is the two stations it always was")
        assertEquals(263, stations(run(1000.0), section, 15.0 * PI / 180.0), "15° of twist")
        assertEquals(1572, stations(run(1000.0), section, 90.0 * PI / 180.0), "a quarter turn")
        assertEquals(3143, stations(run(1000.0), section, PI), "half a turn")
        assertTrue(stations(run(1000.0), section, PI) <= SizeLaws.MAX_SPANS + 1, "and never past the cap")
    }

    // ---- 2. a section with a hole in it ----

    /**
     * **A hollow section warps at both its loops**, and the inner one's facets cut *outward* into the wall,
     * so the two deficits add rather than cancelling. A 200 × 120 rectangular tube with a 20 mm wall lost
     * a per cent and a half of its 11 200 000 mm³ before the fix; it is now inside its own derived bound of
     * 0.129%, which is a larger number than the solid bar's for the reason the bound says it is — a hollow
     * section has more edge per unit of area.
     */
    @Test
    fun aTwistedHollowSectionIsRefinedAtBothItsLoops() {
        val section = tube(200.0, 120.0, 20.0)
        val exact = (200.0 * 120.0 - 160.0 * 80.0) * 1000.0
        val solid = swept(run(1000.0), section, 15.0 * PI / 180.0)
        assertManifold(solid.mesh, "the twisted hollow section")
        assertWithinBound(Geom3.volume(solid.mesh), exact, section, "a 200 × 120 × 20 tube twisted 15°")
        // …and the hole is genuinely there: the same outline with no hole is the whole rectangle
        val solidBar = swept(run(1000.0), rect(200.0, 120.0), 15.0 * PI / 180.0)
        assertTrue(
            Geom3.volume(solidBar.mesh) - Geom3.volume(solid.mesh) > 0.99 * 160.0 * 80.0 * 1000.0,
            "the bore takes its own volume out",
        )
    }

    // ---- 3. a twist on a run that is itself curved ----

    /**
     * **A twist on a helix costs the run nothing it did not already cost.** A curved run has no exact
     * `area × length` to be measured against — the slab at a station is thinner on the inside of the bend —
     * so the claim is stated the way the defect was: the **same** section on the **same** run, twisted and
     * not, encloses the same volume to the tolerance's own order, because turning a section about its own
     * run moves no material.
     *
     * It is the case that says the warp term rides the *path's* sampling rather than replacing it: the
     * untwisted coil's 72 stations come from the helix's own curvature and the twisted one's from the warp
     * rule, and the run is the same run either way.
     */
    @Test
    fun aTwistedSectionOnAHelixKeepsTheVolumeItsUntwistedTwinHas() {
        val coil = Path3(listOf(Curve3Element.Helix3(Vec3.ZERO, Vec3.Z, Vec3.X, 200.0, 300.0, 1.0, Handedness.RIGHT)))
        val section = rect(40.0, 12.0)
        val flat = swept(coil, section, 0.0)
        val turned = swept(coil, section, 15.0 * PI / 180.0)
        assertManifold(flat.mesh, "the untwisted coil")
        assertManifold(turned.mesh, "the twisted coil")
        assertWithinBound(Geom3.volume(turned.mesh), Geom3.volume(flat.mesh), section, "a section twisted 15° along a helix")
    }

    // ---- 4. nothing that was not twisted moved ----

    /**
     * **An untwisted sweep is the mesh it always was, vertex for vertex and triangle for triangle.**
     *
     * The expectations below were read off the **pre-fix** code (the working tree stashed, the probe run,
     * the tree restored) and are pasted here as literals: a count of vertices, a count of triangles, an
     * order-sensitive hash of every coordinate's raw bits and every triangle's three indices, and the
     * volume. Four fixtures, chosen so that all four of the run's sampling rules are represented — the
     * straight segment that needs one span, a smooth cubic that needs its second derivative's count, a
     * helix that meets its chord tolerance exactly, and a round section on a bend.
     *
     * It holds for the plainest of reasons rather than by luck: the warp term is
     * `warpSteps(edge, |turn|·share)` and a turn of zero asks for one span, so with nothing turning the
     * `max` at the chokepoint is the one it always took.
     */
    @Test
    fun anUntwistedSweepIsTheMeshItAlwaysWas() {
        fun check(
            what: String,
            mesh: Mesh3,
            vertices: Int,
            triangles: Int,
            hash: String,
            volume: Double,
        ) {
            assertEquals(vertices, mesh.vertexCount, "$what keeps its vertex count")
            assertEquals(triangles, mesh.triangleCount, "$what keeps its triangle count")
            assertEquals(hash, fingerprint(mesh), "$what keeps every vertex and every triangle, in order")
            assertEquals(volume, Geom3.volume(mesh), "$what keeps its volume to the bit")
        }
        check("a straight bar", swept(run(1000.0), rect(200.0, 24.0), 0.0).mesh, 8, 12, "-592a8a40f369d0db", 4800000.0)
        val bend = Path3(Curves3.smoothThrough(listOf(Vec3.ZERO, Vec3(300.0, 120.0, 40.0), Vec3(700.0, -50.0, 90.0)), false))
        check("a section on a smooth bend", swept(bend, rect(40.0, 12.0), 0.0).mesh, 500, 996, "71f36d1cfdb92ca3", 369611.48843070166)
        val coil = Path3(listOf(Curve3Element.Helix3(Vec3.ZERO, Vec3.Z, Vec3.X, 200.0, 300.0, 1.0, Handedness.RIGHT)))
        check("a section on a helix", swept(coil, rect(40.0, 12.0), 0.0).mesh, 288, 572, "-6e065a7d4c104d21", 619944.9274646784)
        val route = Path3(Curves3.smoothThrough(listOf(Vec3.ZERO, Vec3(200.0, 60.0, 0.0), Vec3(400.0, 0.0, 80.0)), false))
        val tubeOnBend = assertNotNull(Geom3.sweep(route, up, SweepProfile.Round(12.0)).first, "the tube builds")
        check("a round tube on a bend", tubeOnBend.mesh, 4785, 9566, "59f3ca1ecc1097e9", 196522.64687557286)
    }

    /**
     * **The term fires on a turn and on nothing else** — the mechanism behind the fixture above, asserted
     * where it lives rather than only through a mesh.
     */
    @Test
    fun theWarpTermAsksForNothingWhereThereIsNoTurn() {
        assertEquals(1, GeomMath.warpSteps(200.0, 0.0, GeomMath.TESS_TOL_MM), "no turn is one span")
        assertEquals(1, GeomMath.warpSteps(0.0, PI, GeomMath.TESS_TOL_MM), "and a section with no edge has nothing to warp")
        val constant = SizeLaw(ExprParser.parse("15deg"), emptyMap(), Dimension.ANGLE, "t", "15deg")
        assertEquals(0, SizeLaws.warpSpans(constant, 200.0, GeomMath.TESS_TOL_MM), "a twist law that does not turn asks for no spans")
        // …and the rule really is first order in the turn: twice the turn, twice the spans
        val one = GeomMath.warpSteps(200.0, 0.4, GeomMath.TESS_TOL_MM)
        assertEquals(2 * one, GeomMath.warpSteps(200.0, 0.8, GeomMath.TESS_TOL_MM), "the warp rule is linear in the turn")
    }

    /**
     * An order-sensitive fingerprint of every coordinate and every index of [mesh] — "the same triangles in
     * the same order over the same vertices in the same order", as one comparable string.
     */
    private fun fingerprint(mesh: Mesh3): String {
        var h = 1125899906842597L
        for (v in mesh.vertices) {
            for (c in listOf(v.x, v.y, v.z)) h = h * 31L + c.toRawBits()
        }
        for (t in mesh.triangles) h = ((h * 31L + t.a) * 31L + t.b) * 31L + t.c
        return h.toString(16)
    }
}
