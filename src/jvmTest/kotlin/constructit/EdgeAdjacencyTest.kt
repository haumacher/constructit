package constructit

import constructit.dsl.solid
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.Arc
import constructit.geom.BoolOp
import constructit.geom.Circle
import constructit.geom.EdgeGeom
import constructit.geom.EdgeName
import constructit.geom.FaceName
import constructit.geom.Feature3
import constructit.geom.Geom3
import constructit.geom.GeomMath
import constructit.geom.LoftSection
import constructit.geom.Loop
import constructit.geom.Path3
import constructit.geom.Plane3
import constructit.geom.ProfileElement
import constructit.geom.Region
import constructit.geom.Revolve3
import constructit.geom.Section3
import constructit.geom.Segment
import constructit.geom.Sketch3
import constructit.geom.Slab
import constructit.geom.SolidEdge
import constructit.geom.SolidFace
import constructit.geom.SweepProfile
import constructit.geom.Turn3
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.units.mm
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **The edge, first-class** — slice 1 of the session-71 edge-blend package.
 *
 * What is asserted here is the mechanism, not one part: every [SolidEdge] names the **two faces it bounds**
 * ([SolidEdge.between]), stated from the feature's own structure and never discovered from triangles (OP-8);
 * cap edges everywhere follow the one convention [Section3.CAP_EDGE_CONVENTION] states, so the revolve's
 * reflected **bottom** cap is in profile-piece order like everything else; the generic accessors
 * [Section3.edgesOfFace] and [Section3.edgeBetween] read that adjacency and nothing feature-specific; and an
 * extrusion's arc-swept face carries the **typed cylinder** its prose used to name alone.
 *
 * The features that have no constructed face list keep their named refusals, word for word — a face list is
 * not invented to make an accessor total.
 */
class EdgeAdjacencyTest {
    // ---- fixtures ----

    private val plan = Plane3(Vec3.ZERO, Vec3.X, Vec3.Y)

    private fun poly(vararg pts: Vec2): Loop =
        Loop(pts.indices.map { ProfileElement.Seg(Segment(pts[it], pts[(it + 1) % pts.size])) })

    private fun rect(
        w: Double,
        h: Double,
        x0: Double = 0.0,
        y0: Double = 0.0,
    ): Loop = poly(Vec2(x0, y0), Vec2(x0 + w, y0), Vec2(x0 + w, y0 + h), Vec2(x0, y0 + h))

    /**
     * A turned shaft, [len] long and [r] in radius about the sketch's own x axis, swept [turn].
     *
     * The profile is one four-piece loop: #1 the edge **on** the axis, #2 the far end, #3 the barrel, #4 the
     * near end — [Geom3.boundaryPieces]'s order, which is the whole address space here.
     */
    private fun shaft(
        turn: Turn3,
        r: Double = 20.0,
        len: Double = 100.0,
    ): Feature3.Revolution =
        Feature3.Revolution(
            Sketch3(plan, listOf(Region(rect(len, r), emptyList()))),
            Vec2(0.0, 0.0),
            Vec2(1.0, 0.0),
            turn,
        )

    /** A 100 × 100 plate 20 thick with a 40 × 40 square hole — two loops, so the wrap has something to get wrong. */
    private fun plateWithHole(): Feature3.Extrusion =
        Feature3.Extrusion(
            Sketch3(
                plan,
                listOf(
                    Region(
                        rect(100.0, 100.0),
                        listOf(poly(Vec2(30.0, 30.0), Vec2(30.0, 70.0), Vec2(70.0, 70.0), Vec2(70.0, 30.0))),
                    ),
                ),
            ),
            20.0,
        )

    /** A square frustum: 100 × 100 at `z = 0`, 60 × 60 at `z = 40`, both terminal sections areas. */
    private fun frustum(): Feature3.Loft =
        Feature3.Loft(
            listOf(
                LoftSection.Area(Sketch3(plan, listOf(Region(rect(100.0, 100.0), emptyList())))),
                LoftSection.Area(
                    Sketch3(
                        Plane3(Vec3(0.0, 0.0, 40.0), Vec3.X, Vec3.Y),
                        listOf(Region(rect(60.0, 60.0, 20.0, 20.0), emptyList())),
                    ),
                ),
            ),
            listOf(0, 0),
            emptyList(),
        )

    private fun edgesOf(f: Feature3): List<SolidEdge> {
        val (es, why) = Section3.edges(f)
        assertNull(why, "this feature names its edges")
        return assertNotNull(es)
    }

    private fun edgeNamed(
        f: Feature3,
        name: EdgeName,
    ): SolidEdge = assertNotNull(edgesOf(f).firstOrNull { it.name == name }, "there is an edge $name")

    /** The two ends of an edge in the world, as an order-free pair of coordinates. */
    private fun endsOf(e: SolidEdge): Set<String> {
        val pts =
            when (val g = e.geom) {
                is EdgeGeom.Straight -> listOf(g.a, g.b)
                is EdgeGeom.OnPlane ->
                    listOf(g.plane.toWorld(GeomMath.startOf(g.piece)), g.plane.toWorld(GeomMath.endOf(g.piece)))
            }
        return pts.map { "${round6(it.x)},${round6(it.y)},${round6(it.z)}" }.toSet()
    }

    private fun round6(v: Double): Double = kotlin.math.round(v * 1e6) / 1e6

    // ---- the partial revolution: both caps, and the bottom one in particular ----

    /**
     * **A partial revolve's every edge names its two faces** — the rings between consecutive bands (wrapping
     * inside the loop), and each cap's pieces between that cap and the band over the same profile piece.
     */
    @Test
    fun aPartialRevolvesEdgesNameTheirTwoFaces() {
        val f = shaft(Turn3.Arc.of(0.0, PI / 2))
        val n = Geom3.boundaryPieces(f).size
        assertEquals(4, n, "the profile is four pieces")
        val edges = edgesOf(f)
        assertEquals(3 * n, edges.size, "a ring per corner, and both caps' pieces")

        for (i in 0 until n) {
            val ring = edgeNamed(f, EdgeName.RevolveRing(i))
            assertTrue(
                ring.between.sameAs(FaceName.Side((i + n - 1) % n), FaceName.Side(i)),
                "ring #${i + 1} is where band #${(i + n - 1) % n + 1} meets band #${i + 1}: ${ring.between}",
            )
        }
        // the seam of the loop: ring #1 is traced by the corner between the *last* piece and the first
        assertTrue(
            edgeNamed(f, EdgeName.RevolveRing(0)).between.sameAs(FaceName.Side(3), FaceName.Side(0)),
            "the wrap closes inside the loop, not off the end of the list",
        )

        for (which in listOf(SolidFace.TOP, SolidFace.BOTTOM)) {
            for (i in 0 until n) {
                val e = edgeNamed(f, EdgeName.RevolveCapPiece(which, i))
                assertTrue(
                    e.between.sameAs(FaceName.RevolveCap(which), FaceName.Side(i)),
                    "cap piece $which #${i + 1} is where that cap meets band #${i + 1}: ${e.between}",
                )
            }
        }
    }

    /**
     * **The bottom cap, pinned.** Its `Affine` is a reflection, so [GeomMath.transform] re-orients the whole
     * loop (OP-14) and the cap **face's** outline comes back reversed — piece *i* of that outline is profile
     * piece `n − 1 − i`. The cap **edges** are not indexed there any more
     * ([Section3.CAP_EDGE_CONVENTION]): `RevolveCapPiece(BOTTOM, i)` *is* profile piece `i`, and the
     * adjacency comes out right for both caps.
     *
     * The reversal itself is asserted, not merely worked around, because it is the fact that silently breaks
     * any index arithmetic done from outside.
     */
    @Test
    fun theBottomCapsReversedOutlineDoesNotReachTheEdgeIndices() {
        val f = shaft(Turn3.Arc.of(0.0, PI / 2))
        val pieces = Geom3.boundaryPieces(f)
        val n = pieces.size
        val faces = assertNotNull(Section3.faces(f).first)
        val bottom = assertNotNull(faces.firstOrNull { it.name == FaceName.RevolveCap(SolidFace.BOTTOM) })
        val bottomPlane = assertNotNull(bottom.plane)
        val top = assertNotNull(faces.firstOrNull { it.name == FaceName.RevolveCap(SolidFace.TOP) })
        val topPlane = assertNotNull(top.plane)

        // where profile piece #i actually stands at each end of the sweep, said in the revolve's own terms
        // and not through either cap's affine — so this pins the geometry rather than restating the code
        val fr = assertNotNull(Revolve3.frameOf(f))

        fun profileEnds(
            i: Int,
            th: Double,
        ): Set<String> =
            listOf(GeomMath.startOf(pieces[i]), GeomMath.endOf(pieces[i]))
                .map { fr.sr(it) }
                .map { fr.world(it.x, it.y, th) }
                .map { "${round6(it.x)},${round6(it.y)},${round6(it.z)}" }
                .toSet()

        fun outlineEnds(
            plane: Plane3,
            e: ProfileElement,
        ): Set<String> =
            listOf(GeomMath.startOf(e), GeomMath.endOf(e))
                .map { plane.toWorld(it) }
                .map { "${round6(it.x)},${round6(it.y)},${round6(it.z)}" }
                .toSet()

        val low = fr.turnStart
        val high = fr.turnEnd

        // 1. the reversal is real: the bottom cap face's outline runs the other way round
        for (i in 0 until n) {
            assertEquals(
                profileEnds(n - 1 - i, low),
                outlineEnds(bottomPlane, bottom.outline[i]),
                "the bottom cap face's outline piece #${i + 1} is profile piece #${n - i} — reversed",
            )
        }
        assertTrue(
            profileEnds(0, low) != outlineEnds(bottomPlane, bottom.outline[0]),
            "…so outline order and profile order genuinely disagree down there",
        )
        // …and the top cap's does not, which is why only one of the two ever showed the bug
        for (i in 0 until n) {
            assertEquals(profileEnds(i, high), outlineEnds(topPlane, top.outline[i]), "the top cap's outline is in profile order")
        }

        // 2. the edges are in **profile** order on both caps, whatever the outline does
        for (which in listOf(SolidFace.BOTTOM, SolidFace.TOP)) {
            for (i in 0 until n) {
                val e = edgeNamed(f, EdgeName.RevolveCapPiece(which, i))
                assertEquals(FaceName.Side(i), e.between.other(FaceName.RevolveCap(which)), "$which #${i + 1} names its own band")
            }
        }
        // 3. and the geometry at that index is that piece, standing at that end of the sweep
        for (i in 0 until n) {
            assertEquals(
                profileEnds(i, low),
                endsOf(edgeNamed(f, EdgeName.RevolveCapPiece(SolidFace.BOTTOM, i))),
                "bottom cap edge #${i + 1} is profile piece #${i + 1}, in the world",
            )
            assertEquals(
                profileEnds(i, high),
                endsOf(edgeNamed(f, EdgeName.RevolveCapPiece(SolidFace.TOP, i))),
                "top cap edge #${i + 1} is profile piece #${i + 1}, in the world",
            )
        }
    }

    /** A **complete** revolution has rings and nothing else — no caps, and the wrap still closes in the loop. */
    @Test
    fun aFullRevolveHasRingsOnlyAndTheWrapIsRight() {
        val f = shaft(Turn3.Full)
        val edges = edgesOf(f)
        assertEquals(4, edges.size, "a complete turn has no start and no end, hence no cap edges")
        assertTrue(edges.all { it.name is EdgeName.RevolveRing }, "rings only")
        assertTrue(edges.none { it.between.has(FaceName.RevolveCap(SolidFace.TOP)) }, "…so nothing names a cap")
        for (i in 0..3) {
            assertTrue(
                edges[i].between.sameAs(FaceName.Side((i + 3) % 4), FaceName.Side(i)),
                "ring #${i + 1}: ${edges[i].between}",
            )
        }
        // the degenerate entry stays in the list with its adjacency stated: the corner on the axis traces a
        // point, and it is still where two bands meet
        val onAxis = edges[1]
        assertTrue(onAxis.geom is EdgeGeom.OnPlane || onAxis.geom is EdgeGeom.Straight)
        assertEquals(
            EdgeGeom.Straight::class,
            edges[0].geom::class,
            "the corner at the origin lies on the axis, so its ring is one point",
        )
        assertTrue(edges[0].between.sameAs(FaceName.Side(3), FaceName.Side(0)), "and it still names its two bands")
    }

    // ---- the extrusion: the loop wrap, and the caps ----

    /**
     * **An extrusion's uprights wrap inside their own loop.** The corner at the start of a hole's first piece
     * is where that hole's *last* face meets its first — never where the outer boundary's last face does.
     */
    @Test
    fun anExtrusionsUprightsWrapWithinTheirOwnLoop() {
        val f = plateWithHole()
        val pieces = Geom3.boundaryPieces(f)
        assertEquals(8, pieces.size, "four outer pieces and four hole pieces")
        assertEquals(listOf(0 until 4, 4 until 8), Section3.loopSpans(f), "two loops, in provenance order")

        val expected = mapOf(0 to 3, 1 to 0, 2 to 1, 3 to 2, 4 to 7, 5 to 4, 6 to 5, 7 to 6)
        for ((i, prev) in expected) {
            val e = edgeNamed(f, EdgeName.Upright(i))
            assertTrue(
                e.between.sameAs(FaceName.Side(prev), FaceName.Side(i)),
                "upright #${i + 1} is where face #${prev + 1} meets face #${i + 1}: ${e.between}",
            )
        }
        // the load-bearing one: the hole's first upright must not reach across into the region around it
        assertTrue(
            edgeNamed(f, EdgeName.Upright(4)).between.sameAs(FaceName.Side(7), FaceName.Side(4)),
            "the hole's wrap is the hole's own",
        )
        assertTrue(
            !edgeNamed(f, EdgeName.Upright(4)).between.has(FaceName.Side(3)),
            "…and never the outer boundary's last face",
        )

        for (which in listOf(SolidFace.BOTTOM, SolidFace.TOP)) {
            for (i in pieces.indices) {
                val e = edgeNamed(f, EdgeName.CapPiece(which, i))
                assertTrue(
                    e.between.sameAs(FaceName.Cap(which), FaceName.Side(i)),
                    "cap piece $which #${i + 1}: ${e.between}",
                )
                val g = assertNotNull(e.geom as? EdgeGeom.OnPlane)
                val face = assertNotNull(Section3.faces(f).first!!.firstOrNull { it.name == FaceName.Cap(which) })
                assertEquals(face.plane, g.plane, "a cap edge lies in the plane of the cap face it bounds")
            }
        }
    }

    /**
     * **The generic accessors**: the edges of a face, and the edge between two — the two questions a blend
     * asks, answered off the stated adjacency and nothing feature-specific.
     */
    @Test
    fun theGenericAccessorsReadTheStatedAdjacency() {
        val f = plateWithHole()
        val side0 = assertNotNull(Section3.edgesOfFace(f, FaceName.Side(0)).first)
        assertEquals(
            setOf(
                EdgeName.Upright(0),
                EdgeName.Upright(1),
                EdgeName.CapPiece(SolidFace.BOTTOM, 0),
                EdgeName.CapPiece(SolidFace.TOP, 0),
            ),
            side0.map { it.name }.toSet(),
            "a side face of a box is bounded by two uprights and its two cap pieces",
        )
        val cap = assertNotNull(Section3.edgesOfFace(f, FaceName.Cap(SolidFace.TOP)).first)
        assertEquals(8, cap.size, "the top cap is bounded by every boundary piece, holes included")

        val between = assertNotNull(Section3.edgeBetween(f, FaceName.Side(0), FaceName.Side(1)).first)
        assertEquals(EdgeName.Upright(1), between.name, "faces #1 and #2 meet at the upright between them")
        val symmetric = assertNotNull(Section3.edgeBetween(f, FaceName.Side(1), FaceName.Side(0)).first)
        assertEquals(between, symmetric, "the pair is unordered")
        assertEquals(
            EdgeName.CapPiece(SolidFace.TOP, 5),
            assertNotNull(Section3.edgeBetween(f, FaceName.Cap(SolidFace.TOP), FaceName.Side(5)).first).name,
        )

        // two faces that do not meet, and a face that does not exist, each refuse by name
        val (none, whyNone) = Section3.edgeBetween(f, FaceName.Side(0), FaceName.Side(5))
        assertNull(none)
        assertTrue("do not meet" in assertNotNull(whyNone), whyNone)
        val (gone, whyGone) = Section3.edgesOfFace(f, FaceName.Side(99))
        assertNull(gone)
        assertTrue("has no" in assertNotNull(whyGone), whyGone)
        // …and a feature with no face list refuses in the words it always used
        val (noEdges, whyNoEdges) = Section3.edgesOfFace(Feature3.MeshBoolean(BoolOp.UNION), FaceName.Side(0))
        assertNull(noEdges)
        assertTrue("mesh-only" in assertNotNull(whyNoEdges), whyNoEdges)
    }

    /**
     * A cylinder extruded from a **whole circle** has one side face, and its upright is the seam where that
     * face meets **itself** — a fact about the construction, stated rather than guarded against.
     */
    @Test
    fun aCylindersSeamNamesTheSameFaceTwice() {
        val f =
            Feature3.Extrusion(
                Sketch3(plan, listOf(Region(Loop(listOf(ProfileElement.CircleE(Circle(Vec2(0.0, 0.0), 30.0), true))), emptyList()))),
                80.0,
            )
        val seam = edgeNamed(f, EdgeName.Upright(0))
        assertTrue(seam.between.sameAs(FaceName.Side(0), FaceName.Side(0)), "the barrel meets itself: ${seam.between}")
        assertEquals(FaceName.Side(0), seam.between.other(FaceName.Side(0)))
    }

    // ---- the extrusion's typed surfaces ----

    /**
     * **A filleted profile, extruded**: the arc's side face carries the typed **cylinder** it always was in
     * prose, and its two flanking uprights each name that face and the straight neighbour beside it.
     *
     * Built through the fillet tool, so what is asserted is the shape a user actually makes.
     */
    @Test
    fun aFilletedProfilesArcFaceIsATypedCylinderBetweenTwoUprights() {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(80.0, 50.0))
        ed.activeScalar = ed.doc.newParameter("r", 10.0.mm)
        ed.setTool(Tools.FILLET)
        ed.click(Vec2(80.0, 25.0))
        ed.click(Vec2(40.0, 50.0))
        // the fillet trimmed two of the rectangle's sides, so the ring is traced once and then extruded
        ed.setTool(Tools.OUTLINE)
        ed.click(Vec2(40.0, 0.0))
        ed.click(Vec2(80.0, 20.0))
        assertEquals(1, ed.doc.elements.count { it.kind == ElementKind.OUTLINE }, "the filleted ring closed: ${ed.statusHint}")
        ed.activeScalar = ed.doc.newParameter("h", 20.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(40.0, 0.0))

        val solid = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.SOLID }, ed.statusHint)

        @Suppress("UNCHECKED_CAST")
        val f = constructit.core.Evaluator().solid(solid.ref as constructit.dsl.SolidRef).feature as Feature3.Extrusion
        val pieces = Geom3.boundaryPieces(f)
        val arc = pieces.indexOfFirst { it is ProfileElement.ArcE }
        assertTrue(arc >= 0, "the fillet put an arc in the outline: $pieces")

        val faces = assertNotNull(Section3.faces(f).first)
        val patch = faces[arc]
        assertNull(patch.plane, "a cylinder is no plane, so it still declines a sketch")
        assertTrue("cylinder" in assertNotNull(patch.reason), patch.reason!!)
        val surface = assertNotNull(patch.surface, "…and now it says so in the vocabulary too")
        val band = assertNotNull(surface.band as? Revolve3.Band.Cylinder)
        assertClose(band.r, 10.0, tol = 1e-9, msg = "the fillet's own radius")
        assertClose(band.s0, 0.0, tol = 1e-12, msg = "the band runs the extrusion's own depth")
        assertClose(band.s1, 20.0, tol = 1e-12)
        assertClose(surface.axis.z, 1.0, tol = 1e-12, msg = "about the sweep direction")
        assertTrue(!surface.full, "an arc sweeps part of the cylinder, not all of it")
        // the axis passes through the arc's own centre, and the surface's own (s, r, θ) lands on the arc
        val a = (pieces[arc] as ProfileElement.ArcE).arc
        assertClose(surface.origin.x, a.center.x, tol = 1e-9, msg = "the axis stands on the arc's centre")
        assertClose(surface.origin.y, a.center.y, tol = 1e-9)
        val onArc = surface.world(7.0, band.r, a.startAngle)
        assertClose(onArc.z, 7.0, tol = 1e-9, msg = "s runs along the sweep")
        assertClose(onArc.x, plan.toWorld(GeomMath.startOf(pieces[arc])).x, tol = 1e-9, msg = "…and θ is the profile's own angle")
        assertClose(onArc.y, plan.toWorld(GeomMath.startOf(pieces[arc])).y, tol = 1e-9)

        // the two uprights flanking the arc: each names the cylinder and the straight face beside it
        val n = pieces.size
        val before = (arc + n - 1) % n
        val after = (arc + 1) % n
        assertTrue(
            edgeNamed(f, EdgeName.Upright(arc)).between.sameAs(FaceName.Side(before), FaceName.Side(arc)),
            "the upright where the arc starts",
        )
        assertTrue(
            edgeNamed(f, EdgeName.Upright(after)).between.sameAs(FaceName.Side(arc), FaceName.Side(after)),
            "…and the one where it ends",
        )
        assertNull(faces[before].reason, "both neighbours are straight, hence planes")
        assertNull(faces[after].reason)
    }

    /**
     * A **whole circle** extruded is the whole cylinder: the surface says so with [Surface3.full] rather than
     * with a turn interval that happens to measure 2π.
     */
    @Test
    fun aCircleExtrudesToAClosedCylinderSurface() {
        val f =
            Feature3.Extrusion(
                Sketch3(plan, listOf(Region(Loop(listOf(ProfileElement.CircleE(Circle(Vec2(5.0, 7.0), 30.0), true))), emptyList()))),
                -12.0,
            )
        val surface = assertNotNull(assertNotNull(Section3.faces(f).first)[0].surface)
        assertTrue(surface.full, "it closes on itself")
        val band = assertNotNull(surface.band as? Revolve3.Band.Cylinder)
        assertClose(band.r, 30.0, tol = 1e-12)
        assertClose(band.s0, -12.0, tol = 1e-12, msg = "a negative depth is still an interval, low end first")
        assertClose(band.s1, 0.0, tol = 1e-12)
    }

    /**
     * An **ellipse** sweeps an elliptic cylinder, which this drawing has no word for: it refuses **wholly**
     * and by name, and carries no half-exact surface.
     */
    @Test
    fun anExtrudedEllipseRefusesByNameAndCarriesNoSurface() {
        val ell = constructit.geom.Ellipse(Vec2(0.0, 0.0), 40.0, 20.0, 0.0)
        val f =
            Feature3.Extrusion(
                Sketch3(plan, listOf(Region(Loop(listOf(ProfileElement.EllipseE(ell, true))), emptyList()))),
                30.0,
            )
        val patch = assertNotNull(Section3.faces(f).first)[0]
        assertNull(patch.plane)
        assertNull(patch.surface, "no name means no surface, not an approximate one")
        val why = assertNotNull(patch.reason)
        assertTrue("elliptic cylinder" in why, "it refuses by the name of the surface it is: $why")
        assertTrue("no name for" in why, why)
    }

    /** A **planar** side face has no axis to name, so its exact statement stays its plane and its outline. */
    @Test
    fun aPlanarSideFaceCarriesNoSurface() {
        val faces = assertNotNull(Section3.faces(plateWithHole()).first)
        for (p in faces) {
            assertNotNull(p.plane, "every face of a square plate is a plane")
            assertNull(p.surface, "…and a plane is stated by being one")
        }
    }

    // ---- the loft ----

    /** **An exact loft's rails name their two bands**, and its section rings a band and a terminal face. */
    @Test
    fun anExactLoftsRailsAndRingsNameTheirFaces() {
        val f = frustum()
        val edges = edgesOf(f)
        val rails = edges.filter { it.name is EdgeName.Rail }
        assertTrue(rails.isNotEmpty(), "a frustum has rails")
        val m = rails.size
        for ((j, e) in rails.withIndex()) {
            assertEquals(EdgeName.Rail(0, j), e.name)
            assertTrue(
                e.between.sameAs(FaceName.Band(0, (j + m - 1) % m), FaceName.Band(0, j)),
                "rail #${j + 1} is the crease between two bands of the same row: ${e.between}",
            )
        }
        for (j in 0 until m) {
            assertTrue(
                edgeNamed(f, EdgeName.SectionRing(0, j)).between.sameAs(FaceName.SectionFace(0), FaceName.Band(0, j)),
                "the first section's ring edge is where its own face meets the band above it",
            )
            assertTrue(
                edgeNamed(f, EdgeName.SectionRing(1, j)).between.sameAs(FaceName.Band(0, j), FaceName.SectionFace(1)),
                "…and the last section's, where the band meets its face",
            )
        }
        // every face an edge names is a face the feature actually has (OP-8: nothing invented)
        val names = assertNotNull(Section3.faces(f).first).map { it.name }.toSet()
        for (e in edges) {
            assertTrue(e.between.a in names && e.between.b in names, "${e.name} names faces that exist: ${e.between}")
        }
    }

    /** A **pyramid**'s apex has no ring and no face, so nothing names one — the list simply stops there. */
    @Test
    fun aPyramidsApexHasNeitherRingNorFace() {
        val f =
            Feature3.Loft(
                listOf(
                    LoftSection.Area(Sketch3(plan, listOf(Region(rect(100.0, 100.0), emptyList())))),
                    LoftSection.Apex(Vec3(50.0, 50.0, 90.0)),
                ),
                listOf(0, 0),
                emptyList(),
            )
        val edges = edgesOf(f)
        assertTrue(edges.none { (it.name as? EdgeName.SectionRing)?.section == 1 }, "the apex has no ring")
        assertTrue(edges.none { it.between.has(FaceName.SectionFace(1)) }, "…and nothing names a face it has not got")
        val names = assertNotNull(Section3.faces(f).first).map { it.name }.toSet()
        for (e in edges) assertTrue(e.between.a in names && e.between.b in names, "${e.name}: ${e.between}")
    }

    // ---- the features that refuse, and go on refusing in the same words ----

    /**
     * The four features with no constructed face list keep their refusals **word for word** — a face list is
     * not invented to make an accessor total (OP-9's sink rule, and the prism's own recorded limit).
     */
    @Test
    fun theRefusingFeaturesStillRefuseInTheSameWords() {
        val meshOnly =
            "this solid is mesh-only (a general boolean's result, OP-9), so its section has no faces to name — " +
                "its curves draw as chords and cannot be used as construction inputs; build the geometry you want " +
                "to anchor on from the operands' own sketches instead"
        val prismOnly =
            "this solid is a stack of slabs from the exact boolean algebra (OP-22), whose internal interfaces are " +
                "not faces — its section draws from the mesh and offers no construction inputs; a horizontal cut " +
                "through it is exact via the Section tool"
        val sweepOnly =
            "this solid is a profile swept along a curve (OP-26), whose faces are the moving frame's and not a " +
                "constructed list — its section draws from the mesh and offers no construction inputs; put a datum " +
                "plane where you want to sketch"
        val importOnly =
            "this body was imported from a file, so it is triangles and nothing else (OP-9) — its section draws " +
                "from the mesh and offers no construction inputs; build what you want to anchor on beside it, " +
                "and place the body against that"

        val prism = Feature3.Prism(plan, listOf(Slab(listOf(Region(rect(50.0, 50.0), emptyList())), 0.0, 10.0)))
        val sweep = Feature3.Sweep(Path3(emptyList()), SweepProfile.Round(5.0), Vec3.Z, 0.0, 0.0)
        val imported = Feature3.Imported("a.stl")
        val boolean = Feature3.MeshBoolean(BoolOp.UNION)

        for ((f, why) in listOf(prism to prismOnly, sweep to sweepOnly, imported to importOnly, boolean to meshOnly)) {
            val (es, reason) = Section3.edges(f)
            assertNull(es, "${f::class.simpleName} has no constructed edges")
            assertEquals(why, reason, "${f::class.simpleName} refuses in its own words")
        }
        // a prism *does* name faces (one whole side per boundary piece, for sketching on) and still has no
        // edges, which is the recorded asymmetry and not a gap this slice may close
        assertNotNull(Section3.faces(prism).first, "a prism names faces to sketch on")
        assertEquals(prismOnly, Section3.edgesOfFace(prism, FaceName.Side(0)).second)
    }

    // ---- nothing here is stored ----

    /**
     * Adjacency, the cap convention and the typed cylinder are all **derived at eval time**: a drawing with a
     * partial revolve and a sketch on one of its cap faces saves, loads and saves to the same bytes, because
     * none of this slice reached the file.
     */
    @Test
    fun nothingInThisSliceIsStored() {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 20.0))
        ed.setTool(Tools.LINE)
        ed.click(Vec2(-30.0, 0.0))
        ed.click(Vec2(130.0, 0.0))
        ed.setTool(Tools.REVOLVE)
        ed.type("90")
        ed.click(Vec2(50.0, 20.0))
        ed.click(Vec2(-20.0, 0.0))
        assertEquals(1, ed.doc.elements.count { it.kind == ElementKind.SOLID }, ed.statusHint)

        ed.setTool(Tools.SKETCH_ON_FACE)
        ed.click(Vec2(50.0, 10.0))
        assertTrue(!ed.activeSpace.isPlan, "the cap opened as a sketch space: ${ed.statusHint}")
        ed.setTool(Tools.CIRCLE)
        ed.click(Vec2(50.0, 10.0))
        ed.click(Vec2(56.0, 10.0))

        val once = DocumentFormat.save(ed.doc)
        val twice = DocumentFormat.save(DocumentFormat.load(once))
        assertEquals(once, twice, "save -> load -> save is byte-identical")
        assertTrue("revolve" in once, "…and it really is a partial revolve with a face sketch: $once")
        assertTrue("sketchspace" in once, once)
    }

    // ---- editor gestures ----

    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerMove(s)
        pointerDown(s)
        pointerUp(s)
    }

    private fun Editor.type(digits: String) {
        for (c in digits) key(c.toString())
        key("Enter")
    }
}
