package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.Construction
import constructit.dsl.LoftPart
import constructit.dsl.RegionRef
import constructit.dsl.SolidRef
import constructit.dsl.arc
import constructit.dsl.region
import constructit.dsl.resultOf
import constructit.dsl.scalar
import constructit.dsl.solid
import constructit.geom.Feature3
import constructit.geom.Geom3
import constructit.geom.LoftSection
import constructit.geom.Loop
import constructit.geom.Mesh3
import constructit.geom.Plane3
import constructit.geom.ProfileElement
import constructit.geom.Region
import constructit.geom.Segment
import constructit.geom.Sketch3
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.units.deg
import constructit.units.mm
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The **loft** — the multi-section solid (OP-17's third feature), at the level of the construction itself:
 * one node whose inputs are its sections, its apex and its guides.
 *
 * The exactness claims are asserted as exact numbers (OP-15): a loft between polygons, apex included, is a
 * polyhedron whose facets *are* the solid, so its volume is analytic — 300000 mm³ for the acceptance
 * pyramid, not 300000 ± something. A curved section is the approximated class, and there the expectation is
 * computed from the tessellation the mesh is actually made of rather than from πr²h/3.
 *
 * Every solid here goes through [assertManifold]: watertight or refused (OP-9), with no exception for the
 * newest feature.
 */
class LoftTest {
    // ---- the geometry the tests are built from ----

    /** The axis-aligned rectangle `(x0, y0)–(x1, y1)` as a region, its boundary starting at `(x0, y0)`. */
    private fun Construction.rect(
        x0: Double,
        y0: Double,
        x1: Double,
        y1: Double,
    ): RegionRef {
        val a = freePoint("a", x0.mm, y0.mm)
        val b = freePoint("b", x1.mm, y0.mm)
        val c = freePoint("c", x1.mm, y1.mm)
        val d = freePoint("d", x0.mm, y1.mm)
        return region(loop(segment(a, b), segment(b, c), segment(c, d), segment(d, a)))
    }

    /** A loft of the sections given, with no guide and the default seam. */
    private fun Construction.loftOf(vararg parts: LoftPart): SolidRef = loft(parts.toList())

    private fun Construction.areaOn(
        z: Double,
        region: RegionRef,
    ): LoftPart.Area = LoftPart.Area(sketchOn(planeOffset(planeXY(), const(z.mm)), region))

    /**
     * How many **flat faces** a mesh has: its triangles grouped by the plane they lie in.
     *
     * The honest way to state "a pyramid has five faces" about a mesh: a face is not a triangle (the square
     * base is two of them) but a maximal set of coplanar ones, and the count is what says the loft's facets
     * are planar rather than warped — a skew ruling would put its two triangles in two planes and the count
     * would rise.
     */
    private fun facePlanes(mesh: Mesh3): List<Pair<Vec3, Double>> {
        val planes = ArrayList<Pair<Vec3, Double>>()
        for (t in mesh.triangles) {
            val a = mesh.vertices[t.a]
            val b = mesh.vertices[t.b]
            val c = mesh.vertices[t.c]
            val n = (b - a).cross(c - a).normalized()
            val d = n.dot(a)
            if (planes.none { (n - it.first).length() <= 1e-9 && abs(d - it.second) <= 1e-6 }) planes.add(n to d)
        }
        return planes
    }

    // ---- 1. the pyramid: an area and a point ----

    /**
     * A 100×100 square with an apex 90 mm above its centre is a pyramid of **exactly** 300000 mm³, with five
     * planar faces — and it stays that volume when the apex is dragged sideways, which is Cavalieri's
     * principle and the reason an oblique pyramid needs no special case here.
     */
    @Test
    fun aSquareAndAnApexMakeAPyramidOfExactlyOneThirdTheBox() {
        val c = Construction()
        val base = c.rect(0.0, 0.0, 100.0, 100.0)
        val apex = c.freePoint("apex", 50.mm, 50.mm)
        val height = c.parameter("height", 90.mm)
        val solid = c.loftOf(c.areaOn(0.0, base), LoftPart.Apex(c.heightPoint(c.planeXY(), apex, height)))
        val vol = c.measureVolume(solid)

        val ev = Evaluator()
        assertManifold(ev.solid(solid).mesh, "pyramid")
        assertClose(ev.scalar(vol).base, 300000.0, 1e-6, "a pyramid is a third of its box, exactly")
        assertEquals(5, facePlanes(ev.solid(solid).mesh).size, "a square pyramid has five planar faces")
        assertTrue(!(ev.solid(solid).feature as Feature3.Loft).approximated, "straight sections are the exact class (OP-15)")

        // drag the apex sideways: an oblique pyramid, the same volume (Cavalieri), still watertight
        c.set(apex, 260.mm, 40.mm)
        val ev2 = Evaluator()
        assertManifold(ev2.solid(solid).mesh, "oblique pyramid")
        assertClose(ev2.scalar(vol).base, 300000.0, 1e-6, "leaning the pyramid over does not change its volume")
        assertEquals(5, facePlanes(ev2.solid(solid).mesh).size, "and it still has five flat faces")
    }

    /** The apex is a node like any other: retyping the height scales the solid, with nothing rebuilt. */
    @Test
    fun theApexHeightIsAnOrdinaryParameter() {
        val c = Construction()
        val apex = c.freePoint("apex", 50.mm, 50.mm)
        val height = c.parameter("height", 90.mm)
        val solid = c.loftOf(c.areaOn(0.0, c.rect(0.0, 0.0, 100.0, 100.0)), LoftPart.Apex(c.heightPoint(c.planeXY(), apex, height)))
        val vol = c.measureVolume(solid)
        val nodesBefore = c.nodesCreated

        c.set(height, 30.mm)
        assertClose(Evaluator().scalar(vol).base, 100000.0, 1e-6, "a third of the base times the new height")
        assertEquals(nodesBefore, c.nodesCreated, "a parameter edit recomputes; it never builds a node (OP-21)")
    }

    /**
     * A point may **begin** the run as well as end it — the same solid, since which end of a list is which is
     * not a fact about the shape (an upside-down cone is a cone).
     */
    @Test
    fun anApexMayBeginTheRunAsWellAsEndIt() {
        val c = Construction()
        val apexFirst =
            c.loftOf(
                LoftPart.Apex(c.heightPoint(c.planeXY(), c.freePoint("apex", 50.mm, 50.mm), c.parameter("h", 90.mm))),
                c.areaOn(0.0, c.rect(0.0, 0.0, 100.0, 100.0)),
            )
        val ev = Evaluator()
        assertManifold(ev.solid(apexFirst).mesh, "pyramid, apex first")
        assertClose(ev.scalar(c.measureVolume(apexFirst)).base, 300000.0, 1e-6, "the same pyramid, listed the other way round")
    }

    // ---- 2. the frustum: two areas ----

    /** 100×100 to 60×60 over 60 mm is **exactly** 392000 mm³ — `h/3·(A₁+A₂+√(A₁A₂))`. */
    @Test
    fun twoSquaresMakeAFrustumOfTheExactPrismatoidVolume() {
        val c = Construction()
        val solid =
            c.loftOf(
                c.areaOn(0.0, c.rect(0.0, 0.0, 100.0, 100.0)),
                c.areaOn(60.0, c.rect(20.0, 20.0, 80.0, 80.0)),
            )
        val vol = c.measureVolume(solid)
        val ev = Evaluator()
        assertManifold(ev.solid(solid).mesh, "frustum")
        assertClose(ev.scalar(vol).base, 392000.0, 1e-6, "the prismatoid formula, exactly")
        assertEquals(6, facePlanes(ev.solid(solid).mesh).size, "four sides and two caps, all planar")
    }

    // ---- 3. the cone: a curved section is the approximated class ----

    /**
     * A circle and an apex make a cone — **approximated** (OP-15), and the volume is the one its own
     * tessellation implies: a third of the inscribed polygon's area times the height, exactly. πr²h/3 is
     * *not* the expectation, and asserting it would be asserting that a chord is an arc.
     */
    @Test
    fun aCircleAndAnApexMakeAConeThatKnowsItIsApproximated() {
        val c = Construction()
        val centre = c.freePoint("centre", 0.mm, 0.mm)
        val disc = c.region(c.loop(c.circleCR(centre, c.parameter("r", 40.mm))))
        val solid = c.loftOf(c.areaOn(0.0, disc), LoftPart.Apex(c.heightPoint(c.planeXY(), c.freePoint("apex", 0.mm, 0.mm), c.parameter("h", 90.mm))))
        val vol = c.measureVolume(solid)

        val ev = Evaluator()
        assertManifold(ev.solid(solid).mesh, "cone")
        val feature = ev.solid(solid).feature as Feature3.Loft
        assertTrue(feature.approximated, "a curved section makes the loft approximated (OP-15)")
        val tess = Geom3.tessellateRegion(ev.region(disc)).first!!
        assertClose(ev.scalar(vol).base, Geom3.tessArea(tess) * 90.0 / 3.0, 1e-6, "a third of the polygon it is made of, times the height")
        // ...and within what the tessellation can explain of the true cone
        val exact = kotlin.math.PI * 1600.0 * 90.0 / 3.0
        assertTrue(ev.scalar(vol).base < exact, "an inscribed polygon is smaller than its circle")
        assertTrue((exact - ev.scalar(vol).base) / exact < 1e-3, "and only by as much as the chord tolerance explains")
    }

    // ---- 4. the seam is a choice, and it changes the solid ----

    /**
     * A square and a square turned 45° above it: the seam — which boundary piece the correspondence starts at
     * — decides which corner rises to which, and the choices are **different solids**.
     *
     * Seam 2 is the aligned pairing (each corner rises towards the corner in its own direction) and seam 3 is
     * the quarter-turn one; both are watertight, their rails differ, and so do their volumes.
     */
    @Test
    fun theSeamDecidesWhichCornerRisesToWhich() {
        val a = turnedFrustum(2)
        val b = turnedFrustum(3)
        // the turned square's corners are at 45° + 90°k on a radius of 50 about (50, 50): the first is the
        // north-east one. Seam 2 starts the top's correspondence there two pieces on, i.e. at the south-west
        // corner, so the base's own first corner (0, 0) rails to it; seam 3 rails it to the south-east one.
        val sw = Vec3(50.0 - 50.0 * COS45, 50.0 - 50.0 * COS45, 80.0)
        val se = Vec3(50.0 + 50.0 * COS45, 50.0 - 50.0 * COS45, 80.0)
        assertTrue(hasEdge(a, Vec3(0.0, 0.0, 0.0), sw), "seam 2 pairs the origin corner with the corner over it")
        assertTrue(!hasEdge(b, Vec3(0.0, 0.0, 0.0), sw), "seam 3 does not")
        assertTrue(hasEdge(b, Vec3(0.0, 0.0, 0.0), se), "seam 3 pairs it with the next corner round instead")
        assertTrue(
            abs(Geom3.volume(a) - Geom3.volume(b)) > 1000.0,
            "a quarter-turn twist is a different solid, not the same one relabelled (${Geom3.volume(a)} vs ${Geom3.volume(b)})",
        )
    }

    /**
     * The seam turned **all the way round** pairs each corner with the opposite one, and the two diagonal rails
     * then meet in mid-run: refused by name rather than built as a shell that passes through itself.
     */
    @Test
    fun aSeamThatFoldsTheShellIsRefusedByName() {
        val c = Construction()
        val solid = c.loft(listOf(c.areaOn(0.0, c.rect(0.0, 0.0, 100.0, 100.0)), c.areaOn(80.0, turnedSquare(c))), listOf(0, 0))
        val result = Evaluator().resultOf(solid)
        assertTrue(result is EvalResult.Invalid, "a folded shell is refused, not wound closed around itself")
        val why = (result as EvalResult.Invalid).reason
        assertTrue(why.contains("rails cross"), "the reason names the fold: $why")
        assertTrue(why.contains("another vertex"), "and what to do about it: $why")
    }

    private val COS45 = kotlin.math.cos(kotlin.math.PI / 4)

    /** A square of circumradius 50 about (50, 50), its first corner at 45° — so it is the plan square turned. */
    private fun turnedSquare(c: Construction): RegionRef {
        val o = c.freePoint("o", 50.mm, 50.mm)
        val pts = (0..3).map { c.polarPoint(o, c.const(50.mm), c.const((45.0 + 90.0 * it).deg)) }
        return c.region(c.loop(*(0..3).map { c.segment(pts[it], pts[(it + 1) % 4]) }.toTypedArray()))
    }

    private fun turnedFrustum(seam: Int): Mesh3 {
        val c = Construction()
        val solid = c.loft(listOf(c.areaOn(0.0, c.rect(0.0, 0.0, 100.0, 100.0)), c.areaOn(80.0, turnedSquare(c))), listOf(0, seam))
        val mesh = Evaluator().solid(solid).mesh
        assertManifold(mesh, "turned frustum, seam $seam")
        return mesh
    }

    /** Whether the mesh has an edge between these two positions — the rail an assertion can name. */
    private fun hasEdge(
        mesh: Mesh3,
        a: Vec3,
        b: Vec3,
    ): Boolean {
        val ia = mesh.vertices.indexOfFirst { (it - a).length() <= 1e-6 }
        val ib = mesh.vertices.indexOfFirst { (it - b).length() <= 1e-6 }
        if (ia < 0 || ib < 0) return false
        return mesh.triangles.any { t ->
            listOf(t.a to t.b, t.b to t.c, t.c to t.a).any { (p, q) -> (p == ia && q == ib) || (p == ib && q == ia) }
        }
    }

    // ---- 5. three sections, and guides ----

    /** Three sections blend piecewise: a waisted column is one node, and its volume is the two frusta. */
    @Test
    fun threeSectionsBlendPiecewise() {
        val c = Construction()
        val solid =
            c.loftOf(
                c.areaOn(0.0, c.rect(0.0, 0.0, 100.0, 100.0)),
                c.areaOn(50.0, c.rect(30.0, 30.0, 70.0, 70.0)),
                c.areaOn(100.0, c.rect(0.0, 0.0, 100.0, 100.0)),
            )
        val ev = Evaluator()
        assertManifold(ev.solid(solid).mesh, "waisted column")
        // two frusta, each h/3·(A1+A2+√(A1A2)) with A1 = 10000, A2 = 1600
        val one = 50.0 / 3.0 * (10000.0 + 1600.0 + 4000.0)
        assertClose(ev.scalar(c.measureVolume(solid)).base, 2.0 * one, 1e-6, "the sum of the two frusta, exactly")
        assertEquals(3, (ev.solid(solid).feature as Feature3.Loft).sections.size, "three sections, one node")
    }

    /**
     * One **guide** displaces the whole run and is followed exactly: the rail through the guide's own
     * boundary parameter lies *on* the guide at every sample.
     *
     * The volume is unchanged, and that is Cavalieri again rather than a coincidence: a single guide is the
     * only rail information there is, so the sections slide along it without changing area (see
     * `Geom3.weightsAt`). The shape does change — the box it needs is 50 mm wider — which is what the guide
     * was for.
     */
    @Test
    fun oneGuideCarriesTheWholeRunAndIsFollowedExactly() {
        val c = Construction()
        val straight = c.loftOf(c.areaOn(0.0, c.rect(0.0, 0.0, 100.0, 100.0)), c.areaOn(100.0, c.rect(0.0, 0.0, 100.0, 100.0)))
        // a vertical plane through the base's first corner, u along +x and v along +z, and an arc in it from
        // (0,0,0) to (0,0,100) bowing out to x = 50 at half height
        val vertical = c.plane(Vec3.ZERO, Vec3.X, Vec3.Z)
        val bow = c.arc(c.freePoint("gc", 0.mm, 50.mm), c.const(50.mm), c.const((-90.0).deg), c.const(90.0.deg))
        val guided =
            c.loft(
                listOf(
                    c.areaOn(0.0, c.rect(0.0, 0.0, 100.0, 100.0)),
                    c.areaOn(100.0, c.rect(0.0, 0.0, 100.0, 100.0)),
                    LoftPart.Guide(vertical, bow),
                ),
            )
        val ev = Evaluator()
        assertManifold(ev.solid(guided).mesh, "guided loft")
        assertClose(
            ev.scalar(c.measureVolume(guided)).base,
            ev.scalar(c.measureVolume(straight)).base,
            1e-6,
            "one guide slides the sections along itself, which preserves volume",
        )
        val bounds = Geom3.bounds(ev.solid(guided).mesh)!!
        assertClose(bounds.second.x, 150.0, 1e-6, "the run bows 50 mm out at half height")

        // the guide is honoured: every vertex of the arc it was tessellated from is a vertex of the shell
        val arc = ev.arc(bow)
        for (p in constructit.geom.GeomMath.sampleArc(arc, 8)) {
            val w = Vec3(p.x, 0.0, p.y)
            assertTrue(
                ev.solid(guided).mesh.vertices.any { (it - w).length() <= 1e-6 },
                "the rail passes through the guide at $w",
            )
        }
        assertTrue((ev.solid(guided).feature as Feature3.Loft).approximated, "a curved guide is the approximated class")
    }

    /**
     * Two guides — one bowed, one straight — shape their own sides and blend between: the volume **differs**
     * from the straight ruling's, because the section is genuinely deformed rather than translated.
     */
    @Test
    fun twoGuidesShapeTheirOwnSidesAndTheVolumeChanges() {
        val c = Construction()
        val straight = c.loftOf(c.areaOn(0.0, c.rect(0.0, 0.0, 100.0, 100.0)), c.areaOn(100.0, c.rect(0.0, 0.0, 100.0, 100.0)))
        val vertical = c.plane(Vec3.ZERO, Vec3.X, Vec3.Z)
        val bow = c.arc(c.freePoint("gc", 0.mm, 50.mm), c.const(50.mm), c.const((-90.0).deg), c.const(90.0.deg))
        // the opposite corner's rail, stated straight: the diagonal boundary parameter 0.5 of the square
        val far = c.plane(Vec3(100.0, 100.0, 0.0), Vec3.X, Vec3.Z)
        val pin = c.segment(c.freePoint("p0", 0.mm, 0.mm), c.freePoint("p1", 0.mm, 100.mm))
        val guided =
            c.loft(
                listOf(
                    c.areaOn(0.0, c.rect(0.0, 0.0, 100.0, 100.0)),
                    c.areaOn(100.0, c.rect(0.0, 0.0, 100.0, 100.0)),
                    LoftPart.Guide(vertical, bow),
                    LoftPart.Guide(far, pin),
                ),
            )
        val ev = Evaluator()
        assertManifold(ev.solid(guided).mesh, "two-guide loft")
        val vg = ev.scalar(c.measureVolume(guided)).base
        val vs = ev.scalar(c.measureVolume(straight)).base
        assertTrue(abs(vg - vs) > 1000.0, "shaping one side and pinning the other changes the volume ($vg vs $vs)")
        // the pinned corner stays on its straight rail: nothing at (100, 100) has moved in x
        assertTrue(
            ev.solid(guided).mesh.vertices.any { (it - Vec3(100.0, 100.0, 50.0)).length() <= 1e-6 },
            "the far corner's rail is still the straight one",
        )
    }

    // ---- refusals: named, and healing (OP-3) ----

    @Test
    fun oneSectionIsNotALoft() {
        val c = Construction()
        val solid = c.loft(listOf(c.areaOn(0.0, c.rect(0.0, 0.0, 100.0, 100.0))))
        val why = (Evaluator().resultOf(solid) as EvalResult.Invalid).reason
        assertTrue(why.contains("at least two sections"), "the reason says what is missing: $why")
    }

    @Test
    fun aPointInTheMiddleOfTheRunIsRefused() {
        val c = Construction()
        val solid =
            c.loft(
                listOf(
                    c.areaOn(0.0, c.rect(0.0, 0.0, 100.0, 100.0)),
                    LoftPart.Apex(c.heightPoint(c.planeXY(), c.freePoint("apex", 50.mm, 50.mm), c.parameter("h", 50.mm))),
                    c.areaOn(100.0, c.rect(0.0, 0.0, 100.0, 100.0)),
                ),
            )
        val why = (Evaluator().resultOf(solid) as EvalResult.Invalid).reason
        assertTrue(why.contains("first or last"), "the reason names the rule: $why")
    }

    @Test
    fun anApexInTheSectionsOwnPlaneIsRefusedAndHeals() {
        val c = Construction()
        val height = c.parameter("height", 0.mm)
        // off the centre, deliberately: an apex *at* the centre with no height is the other degeneracy (the
        // two sections sit at the same place), and each reason should be reachable on its own
        val solid = c.loftOf(c.areaOn(0.0, c.rect(0.0, 0.0, 100.0, 100.0)), LoftPart.Apex(c.heightPoint(c.planeXY(), c.freePoint("apex", 20.mm, 30.mm), height)))
        val why = (Evaluator().resultOf(solid) as EvalResult.Invalid).reason
        assertTrue(why.contains("no height"), "the reason says the apex is in the plane: $why")
        c.set(height, 20.mm)
        assertClose(Evaluator().scalar(c.measureVolume(solid)).base, 200000.0 / 3.0, 1e-6, "and it heals (OP-3)")
    }

    /**
     * A section enclosing no area is refused **by number**, and from both directions: the region node itself
     * declines a flattened boundary (so the loft is invalid on an invalid input, with that reason quoted), and
     * the kernel declines one handed to it directly.
     */
    @Test
    fun aSectionWithNoAreaIsRefusedByName() {
        val c = Construction()
        val flat = c.rect(0.0, 0.0, 100.0, 0.0)
        val solid = c.loftOf(c.areaOn(0.0, flat), LoftPart.Apex(c.heightPoint(c.planeXY(), c.freePoint("apex", 50.mm, 50.mm), c.parameter("h", 50.mm))))
        val why = (Evaluator().resultOf(solid) as EvalResult.Invalid).reason
        assertTrue(why.contains("area"), "the reason reaches back to the flattened boundary: $why")

        val squashed =
            Region(
                Loop(
                    listOf(
                        ProfileElement.Seg(Segment(Vec2(0.0, 0.0), Vec2(100.0, 0.0))),
                        ProfileElement.Seg(Segment(Vec2(100.0, 0.0), Vec2(0.0, 0.0))),
                    ),
                ),
                emptyList(),
            )
        val (built, direct) =
            Geom3.loft(
                listOf(
                    LoftSection.Area(Sketch3(Plane3(Vec3.ZERO, Vec3.X, Vec3.Y), listOf(squashed))),
                    LoftSection.Apex(Vec3(50.0, 20.0, 40.0)),
                ),
            )
        assertNull(built, "a section with no area builds nothing")
        assertTrue(direct!!.contains("section 1"), "and the kernel says which section it was: $direct")
    }

    @Test
    fun sectionsAtTheSamePlaceHaveNoRunAndSaySo() {
        val c = Construction()
        val solid =
            c.loftOf(
                c.areaOn(0.0, c.rect(0.0, 0.0, 100.0, 100.0)),
                c.areaOn(0.0, c.rect(10.0, 10.0, 90.0, 90.0)),
            )
        val why = (Evaluator().resultOf(solid) as EvalResult.Invalid).reason
        assertTrue(why.contains("same place"), "the reason says the run has no direction: $why")
    }

    /** A hole in a section is refused **with the construction that does work** named (a recorded cut). */
    @Test
    fun aSectionWithAHoleIsRefusedWithTheAlternativeNamed() {
        val c = Construction()
        val outer = c.loop(*ringSegments(c, 0.0, 0.0, 100.0, 100.0))
        val hole = c.loop(c.circleCR(c.freePoint("hc", 50.mm, 50.mm), c.const(10.mm)))
        val ringed = c.region(outer, hole)
        val solid = c.loftOf(c.areaOn(0.0, ringed), LoftPart.Apex(c.heightPoint(c.planeXY(), c.freePoint("apex", 50.mm, 50.mm), c.parameter("h", 50.mm))))
        val why = (Evaluator().resultOf(solid) as EvalResult.Invalid).reason
        assertTrue(why.contains("hole"), "the reason names the hole: $why")
        assertTrue(why.contains("subtract"), "and the construction that does work: $why")
    }

    private fun ringSegments(
        c: Construction,
        x0: Double,
        y0: Double,
        x1: Double,
        y1: Double,
    ): Array<constructit.dsl.SegmentRef> {
        val a = c.freePoint("a", x0.mm, y0.mm)
        val b = c.freePoint("b", x1.mm, y0.mm)
        val d = c.freePoint("c", x1.mm, y1.mm)
        val e = c.freePoint("d", x0.mm, y1.mm)
        return arrayOf(c.segment(a, b), c.segment(b, d), c.segment(d, e), c.segment(e, a))
    }

    /** A guide that meets one section elsewhere than the corresponding point is refused, with the numbers. */
    @Test
    fun aGuideThatMissesTheCorrespondingPointIsRefusedWithTheNumbers() {
        val c = Construction()
        val vertical = c.plane(Vec3.ZERO, Vec3.X, Vec3.Z)
        // a straight guide from the base's first corner to a point of the top square that is *not* the
        // corresponding one: it leaves at boundary parameter 0 and arrives at 0.5
        val bad = c.segment(c.freePoint("g0", 0.mm, 0.mm), c.freePoint("g1", 100.mm, 100.mm))
        val solid =
            c.loft(
                listOf(
                    c.areaOn(0.0, c.rect(0.0, 0.0, 100.0, 100.0)),
                    c.areaOn(100.0, c.rect(0.0, 0.0, 100.0, 100.0)),
                    LoftPart.Guide(vertical, bad),
                ),
            )
        val why = (Evaluator().resultOf(solid) as EvalResult.Invalid).reason
        assertTrue(why.contains("corresponding points"), "the reason states the rule: $why")
        assertTrue(why.contains("%"), "and where the guide actually meets each section: $why")
    }

    @Test
    fun aGuideThroughOnlyOneSectionIsRefused() {
        val c = Construction()
        val vertical = c.plane(Vec3.ZERO, Vec3.X, Vec3.Z)
        val stub = c.segment(c.freePoint("g0", 0.mm, 0.mm), c.freePoint("g1", 20.mm, 20.mm))
        val solid =
            c.loft(
                listOf(
                    c.areaOn(0.0, c.rect(0.0, 0.0, 100.0, 100.0)),
                    c.areaOn(100.0, c.rect(0.0, 0.0, 100.0, 100.0)),
                    LoftPart.Guide(vertical, stub),
                ),
            )
        val why = (Evaluator().resultOf(solid) as EvalResult.Invalid).reason
        assertTrue(why.contains("at least two"), "the reason says a guide needs two sections: $why")
    }

    /** Two sections whose planes cross *inside* the run fold the shell, and that is refused by name. */
    @Test
    fun sectionPlanesThatCrossInsideTheLoftAreRefused() {
        val c = Construction()
        // the upper section sits on a plane tilted so steeply about the y axis that its own extent reaches
        // back below the base plane: the rulings then reverse, which is the fold
        val tilted = c.plane(Vec3(0.0, 0.0, 30.0), Vec3(0.0, 0.0, 1.0), Vec3(0.0, 1.0, 0.0))
        val solid =
            c.loft(
                listOf(
                    c.areaOn(0.0, c.rect(0.0, 0.0, 100.0, 100.0)),
                    LoftPart.Area(c.sketchOn(tilted, c.rect(-50.0, 0.0, 50.0, 100.0))),
                ),
            )
        val result = Evaluator().resultOf(solid)
        assertTrue(result is EvalResult.Invalid, "a folded shell is refused rather than built")
        val why = (result as EvalResult.Invalid).reason
        assertTrue(why.contains("fold") || why.contains("along the loft"), "the reason names the fold: $why")
    }

    // ---- what a loft is *not*, stated where it is asked ----

    /**
     * A loft has no prismatic reading, no named top face and no prismatic section — each refused with a
     * reason that names the route that does work, exactly as a revolve's are (OP-3, OP-22).
     */
    @Test
    fun aLoftDeclinesThePrismaticAccessorsByName() {
        val c = Construction()
        val solid = c.loftOf(c.areaOn(0.0, c.rect(0.0, 0.0, 100.0, 100.0)), c.areaOn(60.0, c.rect(20.0, 20.0, 80.0, 80.0)))
        val feature = Evaluator().solid(solid).feature
        assertNull(Geom3.prismatic(feature).first, "a loft is not a prism")
        assertNotNull(Geom3.prismatic(feature).second, "and says so")
        val (plane, whyFace) = Geom3.facePlane(feature, constructit.geom.SolidFace.TOP)
        assertNull(plane, "a loft has no named top face")
        assertTrue(whyFace!!.contains("datum plane"), "the refusal names the route that works: $whyFace")
        val (region, whySection) = Geom3.sectionAt(feature, 30.0)
        assertNull(region, "and no prismatic cross-section")
        assertTrue(whySection!!.contains("changes along the run"), "with the reason why: $whySection")
        assertTrue(feature.footprint.isNotEmpty(), "what it does have in plan is its first section")
    }

    /** A section's own plane is what places it: the same 2D region lofted on two planes is two solids. */
    @Test
    fun aSectionIsPlacedByItsPlaneAlone() {
        val c = Construction()
        val square = c.rect(0.0, 0.0, 100.0, 100.0)
        val tall = c.loftOf(LoftPart.Area(c.sketchOn(c.planeXY(), square)), c.areaOn(200.0, c.rect(20.0, 20.0, 80.0, 80.0)))
        val ev = Evaluator()
        assertManifold(ev.solid(tall).mesh, "tall frustum")
        assertClose(
            ev.scalar(c.measureVolume(tall)).base,
            200.0 / 3.0 * (10000.0 + 3600.0 + 6000.0),
            1e-6,
            "the same region on a further plane is a longer run",
        )
        assertClose(Geom3.bounds(ev.solid(tall).mesh)!!.second.z, 200.0, 1e-9, "and it reaches the second plane")
    }
}
