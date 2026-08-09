package constructit

import constructit.geom.Arc
import constructit.geom.EdgeGeom
import constructit.geom.EdgeName
import constructit.geom.FaceName
import constructit.geom.Feature3
import constructit.geom.Loop
import constructit.geom.Plane3
import constructit.geom.ProfileElement
import constructit.geom.Region
import constructit.geom.Revolve3
import constructit.geom.Section3
import constructit.geom.Segment
import constructit.geom.Sketch3
import constructit.geom.SolidFace
import constructit.geom.Turn3
import constructit.geom.Vec2
import constructit.geom.Vec3
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Probe review of slice 1 of the session-71 edge-blend package — compositions the delivery never saw.
 *
 * The three questions: does the adjacency hold on the drawing the whole package exists for (a **filleted
 * profile, partially revolved** — the cap edge that runs along the fillet arc, whose band must be a typed
 * torus); does [Section3.edgeBetween] stay honest when two faces meet along **more than one** edge (a
 * two-piece lens, whose sides meet at both corners); and does the typed cylinder's frame live in **world**
 * coordinates when the sketch plane is not the plan (an offset, rotated base plane).
 */
class EdgeAdjacencyProbeTest {
    private val plan = Plane3(Vec3.ZERO, Vec3.X, Vec3.Y)

    /**
     * A shaft with a **filleted shoulder**, revolved [turn] about the sketch's x axis: piece #0 on the axis,
     * #1 the far end, #2 the fillet (centre (50, 20), radius 10), #3 the barrel, #4 the near end.
     */
    private fun filletedShaft(turn: Turn3): Feature3.Revolution {
        val pieces =
            listOf(
                ProfileElement.Seg(Segment(Vec2(0.0, 0.0), Vec2(60.0, 0.0))),
                ProfileElement.Seg(Segment(Vec2(60.0, 0.0), Vec2(60.0, 20.0))),
                ProfileElement.ArcE(Arc(Vec2(50.0, 20.0), 10.0, 0.0, PI / 2, true)),
                ProfileElement.Seg(Segment(Vec2(50.0, 30.0), Vec2(0.0, 30.0))),
                ProfileElement.Seg(Segment(Vec2(0.0, 30.0), Vec2(0.0, 0.0))),
            )
        return Feature3.Revolution(
            Sketch3(plan, listOf(Region(Loop(pieces), emptyList()))),
            Vec2(0.0, 0.0),
            Vec2(1.0, 0.0),
            turn,
        )
    }

    /** A half-disc: one segment and one arc, meeting at **two** corners — two pieces, two uprights. */
    private fun lens(
        depth: Double,
        base: Plane3 = plan,
    ): Feature3.Extrusion {
        val pieces =
            listOf(
                ProfileElement.Seg(Segment(Vec2(0.0, 0.0), Vec2(40.0, 0.0))),
                ProfileElement.ArcE(Arc(Vec2(20.0, 0.0), 20.0, 0.0, PI, true)),
            )
        return Feature3.Extrusion(Sketch3(base, listOf(Region(Loop(pieces), emptyList()))), depth)
    }

    private fun assertAt(
        actual: Vec3,
        expected: Vec3,
        msg: String,
    ) = assertTrue((actual - expected).length() < 1e-9, "$msg: $actual vs $expected")

    /**
     * The cap of a revolved fillet — the exact drawing the blend package exists for. The fillet's band is a
     * typed **torus**, the cap edge along it names {cap, that band}, both tangency rings name the band and
     * its neighbour, and the cap has exactly one edge per profile piece, the on-axis piece included.
     */
    @Test
    fun theCapEdgeAlongARevolvedFilletNamesTheTorusItBounds() {
        val f = filletedShaft(Turn3.Arc(0.0, 250.0 * PI / 180.0))

        val (faces, whyF) = Section3.faces(f)
        assertNull(whyF)
        val arcFace = assertNotNull(faces)[2]
        val surface = assertNotNull(arcFace.surface, "the fillet band carries its typed surface")
        val torus = assertIs<Revolve3.Band.Torus>(surface.band)
        assertEquals(50.0, torus.sc, 1e-9)
        assertEquals(20.0, torus.rc, 1e-9)
        assertEquals(10.0, torus.minor, 1e-9)

        for (which in listOf(SolidFace.BOTTOM, SolidFace.TOP)) {
            val cap = FaceName.RevolveCap(which)
            val (capEdges, whyE) = Section3.edgesOfFace(f, cap)
            assertNull(whyE)
            assertEquals(5, assertNotNull(capEdges).size, "one cap edge per profile piece on ${cap.label}")

            val (edge, why) = Section3.edgeBetween(f, cap, FaceName.Side(2))
            assertNull(why, "the cap and the fillet band meet along exactly one edge")
            val named = assertNotNull(edge)
            assertEquals(EdgeName.RevolveCapPiece(which, 2), named.name)
            val onPlane = assertIs<EdgeGeom.OnPlane>(named.geom)
            val piece = assertIs<ProfileElement.ArcE>(onPlane.piece)
            assertEquals(10.0, piece.arc.radius, 1e-9, "the cap edge along the fillet is the fillet's own arc")
        }

        // The two tangency corners are rings like any other corner: a joint is smooth, not absent (OP-14).
        assertEquals(
            setOf(FaceName.Side(1), FaceName.Side(2)),
            setOf(edge(f, EdgeName.RevolveRing(2)).between.a, edge(f, EdgeName.RevolveRing(2)).between.b),
        )
        assertEquals(
            setOf(FaceName.Side(2), FaceName.Side(3)),
            setOf(edge(f, EdgeName.RevolveRing(3)).between.a, edge(f, EdgeName.RevolveRing(3)).between.b),
        )
    }

    private fun edge(
        f: Feature3,
        name: EdgeName,
    ) = assertNotNull(assertNotNull(Section3.edges(f).first).firstOrNull { it.name == name }, "edge $name exists")

    /**
     * Two faces that meet along **two** edges: the lens's segment side and arc side share both uprights, so
     * `edgeBetween` must refuse and say why, while `edgesOfFace` still hands back all four bounding edges.
     */
    @Test
    fun twoFacesMeetingAlongTwoEdgesRefuseTheSingleEdgeQuestion() {
        val f = lens(15.0)

        val (edge, why) = Section3.edgeBetween(f, FaceName.Side(0), FaceName.Side(1))
        assertNull(edge)
        val reason = assertNotNull(why, "two shared edges cannot answer a one-edge question")
        assertTrue("2 separate edges" in reason, "the refusal counts the edges: $reason")

        val (arcEdges, whyE) = Section3.edgesOfFace(f, FaceName.Side(1))
        assertNull(whyE)
        assertEquals(
            setOf<EdgeName>(
                EdgeName.Upright(0),
                EdgeName.Upright(1),
                EdgeName.CapPiece(SolidFace.BOTTOM, 1),
                EdgeName.CapPiece(SolidFace.TOP, 1),
            ),
            assertNotNull(arcEdges).map { it.name }.toSet(),
        )
    }

    /**
     * The typed cylinder's frame is a **world** fact: sketched on an offset plane whose normal is world X,
     * the lens's arc face must carry its axis through the arc centre's world position, along world X, and
     * `world(0, r, turnStart)` must land on the arc's own start corner as the plane embeds it.
     */
    @Test
    fun theTypedCylinderStandsInWorldCoordinatesOnANonTrivialPlane() {
        val base = Plane3(Vec3(10.0, 5.0, 7.0), Vec3.Y, Vec3.Z)
        val f = lens(15.0, base)

        val (faces, whyF) = Section3.faces(f)
        assertNull(whyF)
        val surface = assertNotNull(assertNotNull(faces)[1].surface, "the arc face is a typed cylinder")
        val cyl = assertIs<Revolve3.Band.Cylinder>(surface.band)
        assertEquals(20.0, cyl.r, 1e-9)
        assertEquals(15.0, cyl.s1 - cyl.s0, 1e-9, "the band spans the extrusion's depth")

        assertAt(surface.origin, Vec3(10.0, 25.0, 7.0), "axis through the arc centre, in the world")
        assertAt(surface.axis, Vec3(1.0, 0.0, 0.0), "axis along the base plane's normal")
        assertAt(
            surface.world(cyl.s0, 20.0, surface.turnStart),
            base.toWorld(Vec2(40.0, 0.0)),
            "the surface's own frame reaches the arc's start corner",
        )
    }
}
