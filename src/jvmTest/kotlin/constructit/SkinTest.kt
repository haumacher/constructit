package constructit

import constructit.geom.Arc
import constructit.geom.Circle
import constructit.geom.Feature3
import constructit.geom.Geom3
import constructit.geom.Loop
import constructit.geom.Plane3
import constructit.geom.ProfileElement
import constructit.geom.Region
import constructit.geom.Section3
import constructit.geom.Segment
import constructit.geom.Sketch3
import constructit.geom.Skin3
import constructit.geom.SkinMatch
import constructit.geom.SkinRow
import constructit.geom.SkinSection
import constructit.geom.Vec2
import constructit.geom.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **The loft as a skin over drawn sections, at the level of the geometry itself** (OP-26's hull route,
 * session 78 — queue entry 1; the correspondence design is the user's own).
 *
 * What is asserted here is the claim the ruling makes, in the order it makes it:
 *
 * - **a ruled skin between two parallel equal-count polygons is a prismatoid**, so its volume is the Simpson
 *   figure `h/6·(A₀ + 4·A½ + A₁)` **exactly** — the correspondence being linear makes the area a quadratic in
 *   the run parameter, and that is what makes an exact number assertable at all (`SweepLawTest`'s own style);
 * - **the correspondence is stated, never discovered**: equal counts pair by traversal order, one stated pair
 *   is the seam and twists the skin, a surplus piece fans to the point where its mapped neighbours' images
 *   meet, and every one of the four things that cannot be decided refuses **by name**;
 * - **a faired row passes exactly through every station** it was drawn on and differs measurably from the
 *   ruled skin between them;
 * - **the faces are constructed**: one strip per (interval × piece) plus two caps, in a stated order.
 *
 * Every body here goes through [assertManifold]: watertight by construction or refused (OP-9).
 */
class SkinTest {
    // The stations of one straight run along +Z, which is what a station's plane is: origin on the run,
    // normal along the tangent, axes the transported frame's. Nothing here needs the run itself — a section
    // carries its own plane and its own stated distance, which is the whole of what the feature reads.
    private fun stationAt(z: Double) = Plane3(Vec3(0.0, 0.0, z), Vec3.X, Vec3.Y)

    private fun polygon(vararg at: Vec2): Loop {
        val pieces = at.indices.map { ProfileElement.Seg(Segment(at[it], at[(it + 1) % at.size])) }
        return Loop(pieces)
    }

    private fun square(half: Double) = polygon(Vec2(-half, -half), Vec2(half, -half), Vec2(half, half), Vec2(-half, half))

    /** A regular hexagon of circumradius [r] about the origin, first corner on the +x axis. */
    private fun hexCorners(r: Double) = (0 until 6).map { Vec2(r * kotlin.math.cos(PI * it / 3.0), r * kotlin.math.sin(PI * it / 3.0)) }

    private fun hexagon(r: Double) = polygon(*hexCorners(r).toTypedArray())

    /** An equilateral triangle of circumradius [r] about the origin, first corner on the +x axis. */
    private fun triangle(r: Double) =
        polygon(*(0 until 3).map { Vec2(r * kotlin.math.cos(2.0 * PI * it / 3.0), r * kotlin.math.sin(2.0 * PI * it / 3.0)) }.toTypedArray())

    private fun section(
        z: Double,
        loop: Loop,
    ) = SkinSection(Sketch3(stationAt(z), listOf(Region(loop, emptyList()))), z)

    /** The area of a polygon given as 2D corners — the test's own arithmetic, never the kernel's. */
    private fun areaOf(pts: List<Vec2>): Double {
        var a = 0.0
        for (i in pts.indices) a += pts[i].cross(pts[(i + 1) % pts.size])
        return abs(a) / 2.0
    }

    /**
     * The prismatoid volume of a ruled skin between two parallel rings, computed from the corner
     * correspondence alone: `h/6·(A₀ + 4·A½ + A₁)`.
     *
     * Exact for this body and stated as such: every ruling is linear in the run parameter, so every corner of
     * the section at `t` is linear in `t`, so the area is a quadratic and Simpson's rule is exact for it.
     */
    private fun prismatoid(
        lower: List<Vec2>,
        upper: List<Vec2>,
        h: Double,
    ): Double {
        val mid = lower.indices.map { (lower[it] + upper[it]) * 0.5 }
        return h / 6.0 * (areaOf(lower) + 4.0 * areaOf(mid) + areaOf(upper))
    }

    private fun skin(
        sections: List<SkinSection>,
        row: SkinRow = SkinRow.RULED,
        matches: List<SkinMatch> = emptyList(),
    ) = Skin3.skin(sections, row, matches)

    /** The divergence volume of a closed triangle soup — stated here, so the test owes the kernel nothing. */
    private fun volumeOf(tris: List<Triple<Vec3, Vec3, Vec3>>): Double =
        tris.sumOf { (a, b, c) -> a.dot(b.cross(c)) } / 6.0

    /**
     * The body a **ruled** skin between two corresponding rings *is*, as triangles: one quad per ruling
     * interval, split from its own lower rail (the emission convention [Skin3] states), plus the two planar
     * caps — which any fan triangulates to the same figure.
     *
     * Written out because a twisted quad's two diagonals are two different bodies, so *the* volume of a
     * rectangle running to a triangle is only a number once the split is stated. For a body whose strips are
     * all flat this agrees with the prismatoid formula exactly, which is asserted where it applies.
     */
    private fun ruledBody(
        lower: List<Vec3>,
        upper: List<Vec3>,
    ): List<Triple<Vec3, Vec3, Vec3>> {
        val out = ArrayList<Triple<Vec3, Vec3, Vec3>>()
        val n = lower.size
        for (j in 0 until n) {
            val j2 = (j + 1) % n
            out.add(Triple(lower[j], lower[j2], upper[j2]))
            out.add(Triple(lower[j], upper[j2], upper[j]))
        }
        for (j in 1 until n - 1) {
            out.add(Triple(lower[0], lower[j + 1], lower[j]))
            out.add(Triple(upper[0], upper[j], upper[j + 1]))
        }
        return out
    }

    @Test
    fun aRuledSkinBetweenTwoParallelSquaresIsExactlyItsPrismatoid() {
        val sections = listOf(section(0.0, square(20.0)), section(50.0, square(10.0)))
        val (solid, why) = skin(sections)
        assertNull(why, "the frustum builds")
        val mesh = assertNotNull(solid).mesh
        assertManifold(mesh, "the square frustum")
        val lower = listOf(Vec2(-20.0, -20.0), Vec2(20.0, -20.0), Vec2(20.0, 20.0), Vec2(-20.0, 20.0))
        val upper = listOf(Vec2(-10.0, -10.0), Vec2(10.0, -10.0), Vec2(10.0, 10.0), Vec2(-10.0, 10.0))
        val want = prismatoid(lower, upper, 50.0)
        assertClose(want, 50.0 / 3.0 * (1600.0 + 400.0 + 800.0), 1e-9, "the prismatoid figure is the frustum's own")
        assertClose(Geom3.volume(mesh), want, 1e-9, "and the body is exactly that volume")
    }

    /**
     * The user's own fixture: **rectangle → triangle**, one stated pair, and the side nobody matched fans to
     * the vertex where its mapped neighbours' images meet.
     *
     * The volume is the prismatoid figure again — a collapsed ruling is still a ruling, so the area is still
     * a quadratic — computed here from the correspondence the design states, which is what makes this an
     * independent check rather than a restatement of the code.
     */
    @Test
    fun aRectangleBecomesATriangleByFanningTheSideNobodyMatched() {
        val rect = polygon(Vec2(-20.0, -10.0), Vec2(20.0, -10.0), Vec2(20.0, 10.0), Vec2(-20.0, 10.0))
        val tri = polygon(Vec2(-12.0, -6.0), Vec2(12.0, -6.0), Vec2(0.0, 8.0))
        val sections = listOf(section(0.0, rect), section(30.0, tri))
        // with no stated pair the counts refuse, naming both and the two cures
        val (none, whyNone) = skin(sections)
        assertNull(none, "4 pieces against 3 refuse with no anchor")
        assertTrue(whyNone!!.contains("4 pieces") && whyNone.contains("3"), "it names the counts: $whyNone")
        assertTrue(whyNone.contains("Match sections") && whyNone.contains("Break"), "and both cures: $whyNone")

        val (solid, why) = skin(sections, matches = listOf(SkinMatch(0, 0, 0)))
        assertNull(why, "one stated pair is enough: $why")
        val mesh = assertNotNull(solid).mesh
        assertManifold(mesh, "the rectangle-to-triangle skin")
        // the walk from the anchor: rect 0↔tri 0, rect 1↔tri 1, rect 2↔tri 2, and rect 3 fans to tri's
        // vertex 0 — the point between the images of its mapped neighbours (piece 2's end, piece 0's start)
        val lower = listOf(Vec3(-20.0, -10.0, 0.0), Vec3(20.0, -10.0, 0.0), Vec3(20.0, 10.0, 0.0), Vec3(-20.0, 10.0, 0.0))
        val upper = listOf(Vec3(-12.0, -6.0, 30.0), Vec3(12.0, -6.0, 30.0), Vec3(0.0, 8.0, 30.0), Vec3(-12.0, -6.0, 30.0))
        // …and the body is exactly the polyhedron that correspondence states, split as [Skin3] says: three
        // strips and the collapsed one, over the rectangle's cap and the triangle's
        assertClose(Geom3.volume(mesh), volumeOf(ruledBody(lower, upper)), 1e-9, "the collapsed body's volume")
        // …and the collapsed strip is still a **face at its own address**, which is what keeps the face list
        // an index space rather than a list of the faces that happened to survive (OP-21)
        val faces = assertNotNull(Section3.faces(assertNotNull(solid).feature).first)
        assertEquals(4 + 2, faces.size, "four strips — the fan among them — and two caps")
        for (i in 0 until 4) {
            val at = Section3.addressOfFace(assertNotNull(solid).feature, faces[i].name)
            assertNotNull(at, "${faces[i].name.label} has an address, collapsed or not")
        }
        // one of those strips is necessarily twisted, which is why the figure above is the stated split's and
        // not the smooth prismatoid's — the two differ, and by how much is a fact of this fixture
        val smooth =
            prismatoid(
                lower.map { Vec2(it.x, it.y) },
                upper.map { Vec2(it.x, it.y) },
                30.0,
            )
        assertTrue(abs(smooth - Geom3.volume(mesh)) > 1.0, "the twisted strip really does make a difference")
    }

    /**
     * A stated pair offset by one piece **twists** the skin — the seam's job, done by the same mechanism —
     * and it is asserted on a hexagon, because a **square** twisted one piece round is a quarter turn and
     * that is the one turn a four-piece correspondence cannot survive (see the test below).
     */
    @Test
    fun aStatedPairOffsetByOnePieceTwistsAnEqualCountSkin() {
        val sections = listOf(section(0.0, hexagon(20.0)), section(40.0, hexagon(20.0)))
        val (straight, _) = skin(sections)
        val (twisted, why) = skin(sections, matches = listOf(SkinMatch(0, 0, 1)))
        assertNull(why, "a twist is an ordinary skin: $why")
        val a = assertNotNull(straight).mesh
        val b = assertNotNull(twisted).mesh
        assertManifold(a, "the hexagonal prism")
        assertManifold(b, "the twisted hexagonal prism")
        // the untwisted body is the prism, exactly; the twisted one is the antiprism, which is smaller
        assertClose(Geom3.volume(a), 40.0 * areaOf(hexCorners(20.0)), 1e-9, "the prism's volume")
        assertTrue(Geom3.volume(b) < Geom3.volume(a) - 1000.0, "the twisted skin is a smaller body: ${Geom3.volume(b)}")
        // and it is the twist the pair states: the strip that leaves the first corner now arrives at the
        // *next* corner round, lifted
        val plan = assertNotNull(Skin3.plan(sections, SkinRow.RULED, listOf(SkinMatch(0, 0, 1))).first)
        val rail0 = plan.railPoint(plan.strips[0][0].family, 0, 0)
        val rail1 = plan.railPoint(plan.strips[0][0].family, 0, 1)
        val corners = hexCorners(20.0)
        assertClose((rail0 - Vec3(corners[0].x, corners[0].y, 0.0)).length(), 0.0, 1e-9, "the strip starts at the matched corner")
        assertClose((rail1 - Vec3(corners[1].x, corners[1].y, 40.0)).length(), 0.0, 1e-9, "and lands one corner round")
    }

    /**
     * **The quarter turn a square cannot take is refused by name** (session 82, family 4 of GitHub #33's
     * by-product; this call site used to assert the fold instead).
     *
     * A ruled strip in this kernel *is* the polyhedron its stated split makes of it — two triangles from the
     * strip's own lower rail, which is what `aRuledSkinBetweenTwoEqualPolygonsIsThePolyhedronOfItsSplit`
     * asserts to the last bit. Turn a **four**-piece correspondence one piece round between two sections of
     * the same shape and that polyhedron folds: the triangle each strip puts against the rail it shares with
     * its neighbour is exactly coplanar with the neighbour's and wound against it, so the surface doubles
     * back along all four rails and encloses a third of what the picture shows (21333 mm³ where the ruled
     * surface would hold 42667). Every structural check passes it — the shell is closed and consistently
     * wound — which is why it stood for three sessions, and it is the flap check that names it.
     *
     * It heals the way every refusal here does: state the pair at another vertex, or twist a section with
     * more pieces (the hexagon above takes the same gesture and builds).
     */
    @Test
    fun aQuarterTurnOfAFourPieceCorrespondenceIsRefusedAsAFold() {
        val sections = listOf(section(0.0, square(20.0)), section(40.0, square(20.0)))
        val (twisted, why) = skin(sections, matches = listOf(SkinMatch(0, 0, 1)))
        assertNull(twisted, "a quarter turn of four pieces is not a body")
        val said = assertNotNull(why, "and it says why")
        assertTrue("folds this skin's shell back on itself" in said, "the fold is named: $said")
        assertTrue("two triangles its own split makes of it face against each other" in said, "…as the quad it is: $said")
        assertTrue("another vertex" in said, "and the cure is stated: $said")
        // …and the plan itself is sound — it is the *shell* that cannot be made, so the correspondence the
        // gesture stated is still exactly the one it stated
        val plan = assertNotNull(Skin3.plan(sections, SkinRow.RULED, listOf(SkinMatch(0, 0, 1))).first)
        assertClose(
            (plan.railPoint(plan.strips[0][0].family, 0, 1) - Vec3(20.0, -20.0, 40.0)).length(),
            0.0,
            1e-9,
            "the pair lands one corner round, refused or not",
        )
        // a section of different size does not save it: the quarter turn is what folds, not the taper
        assertNull(
            skin(listOf(section(0.0, square(20.0)), section(40.0, square(12.0))), matches = listOf(SkinMatch(0, 0, 1))).first,
            "a tapered quarter turn folds the same way",
        )
    }

    /**
     * **A third of a turn of a three-piece correspondence is refused too, and it is the case no *mesh* check
     * can see** (session 82, the orchestrator's probe of the gate).
     *
     * Two congruent triangles turned one corner round send every ruling to the vertical of its neighbour, so
     * the three quads sweep through the axis. The shell that closes over them is closed, consistently wound
     * and has **nothing coplanar in it** — `notClosed` is silent and so is `flap` — and it encloses exactly
     * **zero**: it is a surface with no inside, which is the second degenerate closed shell and which
     * `MeshCanon.hollow` now names (`FlapGateTest`). The correspondence that asks for it is refused a level
     * above that, by the same criterion the quarter turn above meets: the two triangles a quad's own split
     * makes of it face against each other, which is a fact about the **rings** and needs no triangle.
     */
    @Test
    fun aThirdOfATurnOfAThreePieceCorrespondenceIsRefusedAsAFold() {
        val sections = listOf(section(0.0, triangle(20.0)), section(40.0, triangle(20.0)))
        assertNull(skin(sections).second, "the untwisted prism is an ordinary skin")
        val (twisted, why) = skin(sections, matches = listOf(SkinMatch(0, 0, 1)))
        assertNull(twisted, "a third of a turn of three pieces is not a body")
        val said = assertNotNull(why, "and it says why")
        assertTrue("folds this skin's shell back on itself" in said, "the fold is named: $said")
        assertTrue("two triangles its own split makes of it face against each other" in said, "…as the quad it is: $said")
        assertTrue("another vertex" in said, "and the cure is stated: $said")
    }

    /**
     * The user's second fixture, verbatim: **a circle against (half-circle, segment, half-circle, segment)**
     * — one piece against four. It refuses naming *Break*, and after the circle has been broken at the four
     * spots the counts agree and the pieces pair by traversal order with nothing stored.
     */
    @Test
    fun aCircleAgainstAStadiumRefusesUntilBreakHasEqualizedTheCounts() {
        val circle = Loop(listOf(ProfileElement.CircleE(Circle(Vec2(0.0, 0.0), 15.0))))
        // the stadium: the left half-circle, the bottom straight, the right half-circle, the top straight
        val stadium =
            Loop(
                listOf(
                    ProfileElement.ArcE(Arc(Vec2(-10.0, 0.0), 8.0, PI / 2.0, 3.0 * PI / 2.0, true)),
                    ProfileElement.Seg(Segment(Vec2(-10.0, -8.0), Vec2(10.0, -8.0))),
                    ProfileElement.ArcE(Arc(Vec2(10.0, 0.0), 8.0, -PI / 2.0, PI / 2.0, true)),
                    ProfileElement.Seg(Segment(Vec2(10.0, 8.0), Vec2(-10.0, 8.0))),
                ),
            )
        val (none, why) = skin(listOf(section(0.0, circle), section(25.0, stadium)))
        assertNull(none, "one piece against four refuses")
        assertTrue(why!!.contains("1 pieces") || why.contains("has 1"), "naming the counts: $why")
        assertTrue(why.contains("4"), "…both of them: $why")
        assertTrue(why.contains("Break") && why.contains("Match sections"), "and both cures: $why")

        // …and the same drawing after a Break at the four spots the stadium's own corners stand at: the
        // circle is four arcs, and four against four need nothing stated at all
        val cuts = listOf(atan2(8.0, -10.0), atan2(-8.0, -10.0), atan2(-8.0, 10.0), atan2(8.0, 10.0))
        val broken =
            Loop(
                cuts.indices.map { i ->
                    ProfileElement.ArcE(Arc(Vec2(0.0, 0.0), 15.0, cuts[i], cuts[(i + 1) % cuts.size], true))
                },
            )
        val (solid, why2) = skin(listOf(section(0.0, broken), section(25.0, stadium)))
        assertNull(why2, "four against four pair by order: $why2")
        val mesh = assertNotNull(solid).mesh
        assertManifold(mesh, "the circle-to-stadium skin")
        // **a band, stated rather than approximated** (OP-15's rule for a body whose sections are curved):
        // no exact figure is claimed for a skin between two *curved* rings, because the correspondence is
        // arc-length-proportional and the area between the stations is no polynomial. What is stated is the
        // band the transition has to lie in — between the two prisms its own end sections would extrude —
        // together with the exactness that *is* claimed: the two end areas themselves, to the sagitta.
        val outer = PI * 225.0
        val inner = PI * 64.0 + 20.0 * 16.0
        val got = Geom3.volume(mesh)
        assertTrue(got > 25.0 * inner, "the skin holds more than the smaller section's prism: $got")
        assertTrue(got < 25.0 * outer, "and less than the larger one's: $got")
    }

    /** A faired row through three stations passes **exactly** through the middle one, and bulges between. */
    @Test
    fun aFairedSkinPassesThroughEveryStationAndDiffersBetweenThem() {
        val sections = listOf(section(0.0, square(10.0)), section(30.0, square(25.0)), section(60.0, square(10.0)))
        val (ruled, whyR) = skin(sections, SkinRow.RULED)
        val (faired, whyF) = skin(sections, SkinRow.FAIRED)
        assertNull(whyR, "the ruled skin builds: $whyR")
        assertNull(whyF, "the faired skin builds: $whyF")
        val a = assertNotNull(ruled).mesh
        val b = assertNotNull(faired).mesh
        assertManifold(a, "the ruled barrel")
        assertManifold(b, "the faired barrel")
        // every station's own corners are vertices of both bodies — a faired skin interpolates, it does not
        // approximate, so the sections the user drew are *on* the surface
        for (z in listOf(0.0, 30.0, 60.0)) {
            val half = if (z == 30.0) 25.0 else 10.0
            for (c in listOf(Vec3(-half, -half, z), Vec3(half, -half, z), Vec3(half, half, z), Vec3(-half, half, z))) {
                assertTrue(
                    b.vertices.any { (it - c).length() <= 1e-9 },
                    "the faired skin passes through the station corner $c",
                )
            }
        }
        // …and between the stations it is a different body: fairing a waisted run swells it
        assertTrue(
            Geom3.volume(b) > Geom3.volume(a) + 1000.0,
            "the faired body differs measurably: ${Geom3.volume(b)} against ${Geom3.volume(a)}",
        )
    }

    /** Two sections at one distance have no run between them, and the refusal names the distance. */
    @Test
    fun twoSectionsAtOneDistanceRefuseByTheirDistance() {
        val (solid, why) = skin(listOf(section(0.0, square(20.0)), SkinSection(Sketch3(stationAt(0.0), listOf(Region(square(10.0), emptyList()))), 0.0)))
        assertNull(solid, "there is no run between two sections in one plane")
        assertTrue(why!!.contains("same distance") && why.contains("0 mm"), "naming the distance: $why")
    }

    /** A section with a hole pairs one outline with one outline, and says what to do instead. */
    @Test
    fun aSectionWithAHoleRefusesAndNamesTheWayRound() {
        val holed = Region(square(20.0), listOf(square(5.0)))
        val sections =
            listOf(
                SkinSection(Sketch3(stationAt(0.0), listOf(holed)), 0.0),
                section(30.0, square(10.0)),
            )
        val (solid, why) = skin(sections)
        assertNull(solid, "a holed section is refused")
        assertTrue(why!!.contains("hole"), "naming the hole: $why")
        assertTrue(why.contains("subtract"), "and naming the way round it: $why")
    }

    /** Crossed stated pairs are a skin that passes through itself, and the refusal names both pairs. */
    @Test
    fun crossedStatedPairsRefuseNamingBoth() {
        val sections = listOf(section(0.0, square(20.0)), section(40.0, square(20.0)))
        // three pairs, because two pairs on two closed outlines can never cross: the cyclic order of two
        // things is trivial, so the crossing this refuses is a genuine three-pair inversion
        val crossed = listOf(SkinMatch(0, 0, 0), SkinMatch(0, 1, 2), SkinMatch(0, 2, 1))
        val (solid, why) = skin(sections, matches = crossed)
        assertNull(solid, "a crossed mapping is refused")
        assertTrue(why!!.contains("cross"), "naming the crossing: $why")
        assertTrue(why.contains("#2") && why.contains("#3"), "and naming both pairs: $why")
        // …and a mapping that is order-preserving but folds the skin all the same is refused too — by the
        // **stations**, in their own distances, which is what the session-65 law asks of every criterion
        val (folded, whyFold) = skin(sections, matches = listOf(SkinMatch(0, 0, 0), SkinMatch(0, 1, 3)))
        assertNull(folded, "a mapping whose gaps fold the skin is refused")
        assertTrue(whyFold!!.contains("0 mm") && whyFold.contains("40 mm"), "naming both stations: $whyFold")
    }

    /** One curve matched twice is not a correspondence, and it says so. */
    @Test
    fun oneCurveMatchedTwiceRefuses() {
        val sections = listOf(section(0.0, square(20.0)), section(40.0, square(20.0)))
        val (solid, why) = skin(sections, matches = listOf(SkinMatch(0, 0, 0), SkinMatch(0, 0, 2)))
        assertNull(solid, "a piece runs to one piece")
        assertTrue(why!!.contains("matched twice"), "saying so: $why")
    }

    /** A skin's faces are constructed: one strip per (interval × piece), then the two caps, in that order. */
    @Test
    fun theFaceListIsOneStripPerPiecePlusTwoCaps() {
        val sections = listOf(section(0.0, square(20.0)), section(30.0, square(10.0)), section(60.0, square(6.0)))
        val (solid, why) = skin(sections)
        assertNull(why, "the two-interval skin builds: $why")
        val feature = assertNotNull(solid).feature as Feature3.Skin
        val faces = assertNotNull(Section3.faces(feature).first)
        assertEquals(4 * 2 + 2, faces.size, "eight strips and two caps")
        for (i in 0 until 8) {
            val patch = faces[i]
            assertTrue(patch.name is constructit.geom.FaceName.SkinBand, "face $i is a strip: ${patch.name}")
            assertNotNull(patch.plane, "a strip between two aligned squares is flat: ${patch.reason}")
        }
        assertTrue(faces[8].name is constructit.geom.FaceName.SectionFace, "then the caps")
        assertTrue(faces[9].name is constructit.geom.FaceName.SectionFace)
        // …and every one of them has a stored address, past the footprint's own pieces
        for (patch in faces) {
            val at = Section3.addressOfFace(feature, patch.name)
            assertNotNull(at, "${patch.name.label} has an address")
            val back = Section3.facePatchOfFootprintPiece(feature, at).first
            assertEquals(patch.name, assertNotNull(back, "address $at resolves").name, "and it round-trips")
        }
    }

    /** The caps' normals point out of the material, which is what makes the body's volume positive. */
    @Test
    fun theCapsAreTheEndSectionsOwnPlanarRegions() {
        val sections = listOf(section(0.0, square(20.0)), section(50.0, square(20.0)))
        val (solid, _) = skin(sections)
        val feature = assertNotNull(solid).feature as Feature3.Skin
        val faces = assertNotNull(Section3.faces(feature).first)
        val low = faces[4]
        val high = faces[5]
        assertClose(assertNotNull(low.plane).normal.normalized().dot(Vec3.Z), -1.0, 1e-12, "the low cap faces down")
        assertClose(assertNotNull(high.plane).normal.normalized().dot(Vec3.Z), 1.0, 1e-12, "the high cap faces up")
        assertClose(Geom3.volume(assertNotNull(solid).mesh), 50.0 * 1600.0, 1e-9, "so the volume is positive and exact")
    }
}
