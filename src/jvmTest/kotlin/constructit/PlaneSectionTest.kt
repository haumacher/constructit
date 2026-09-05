package constructit

import constructit.geom.BoolOp
import constructit.geom.Circle
import constructit.geom.FaceName
import constructit.geom.Feature3
import constructit.geom.Geom3
import constructit.geom.GeomMath
import constructit.geom.LoftSection
import constructit.geom.Loop
import constructit.geom.Plane3
import constructit.geom.ProfileElement
import constructit.geom.Region
import constructit.geom.Section3
import constructit.geom.Segment
import constructit.geom.Sketch3
import constructit.geom.Solid3
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.l10n.contains
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The **section of a solid at a plane** — the geometry half of the section-inputs package (OP-17), and where
 * OP-15's honesty line is actually drawn.
 *
 * What is asserted here is the mechanism: a structural face list per feature kind (OP-8), the degenerate *on
 * a face* section, exactness where the cut is statable (a planar facet, a cylinder cut perpendicular to its
 * axis, a ruling), sampling-with-a-flag where it is a conic, and the mesh route that draws but names nothing.
 * The gestures are in `SectionInputTest`.
 */
class PlaneSectionTest {
    private fun rect(
        w: Double,
        h: Double,
        x0: Double = 0.0,
        y0: Double = 0.0,
    ): Region {
        val pts = listOf(Vec2(x0, y0), Vec2(x0 + w, y0), Vec2(x0 + w, y0 + h), Vec2(x0, y0 + h))
        return Region(Loop(pts.indices.map { ProfileElement.Seg(Segment(pts[it], pts[(it + 1) % pts.size])) }), emptyList())
    }

    private fun box(
        w: Double = 100.0,
        d: Double = 100.0,
        h: Double = 20.0,
    ): Feature3.Extrusion = Feature3.Extrusion(Sketch3(Plane3(Vec3.ZERO, Vec3.X, Vec3.Y), listOf(rect(w, d))), h)

    private fun cylinder(
        r: Double = 30.0,
        h: Double = 80.0,
        at: Vec2 = Vec2(0.0, 0.0),
    ): Feature3.Extrusion =
        Feature3.Extrusion(
            Sketch3(
                Plane3(Vec3.ZERO, Vec3.X, Vec3.Y),
                listOf(Region(Loop(listOf(ProfileElement.CircleE(Circle(at, r), ccw = true))), emptyList())),
            ),
            h,
        )

    /** The acceptance pyramid: 100 × 100 at z = 0, apex 90 mm over its centre. */
    private fun pyramid(apex: Vec3 = Vec3(50.0, 50.0, 90.0)): Feature3.Loft =
        Feature3.Loft(
            listOf(
                LoftSection.Area(Sketch3(Plane3(Vec3.ZERO, Vec3.X, Vec3.Y), listOf(rect(100.0, 100.0)))),
                LoftSection.Apex(apex),
            ),
            listOf(0, 0),
            emptyList(),
        )

    private fun solidOf(f: Feature3.Extrusion): Solid3 = assertNotNull(Geom3.extrude(f.sketch, f.depth).first)

    private fun solidOf(f: Feature3.Loft): Solid3 = assertNotNull(Geom3.loft(f.sections, f.seams).first)

    // ---- the structural face list (OP-8) ----

    /**
     * An extrusion's faces are its profile's boundary pieces plus its two caps, in OP-8's own order — so
     * `Side(piece)` sits at index `piece`, which is what makes the stored address durable.
     */
    @Test
    fun anExtrusionNamesOneFacePerBoundaryPiecePlusItsCaps() {
        val (fs, why) = Section3.faces(box())
        assertNull(why)
        val faces = assertNotNull(fs)
        assertEquals(6, faces.size, "four sides and two caps")
        for (i in 0..3) {
            assertEquals(FaceName.Side(i), faces[i].name)
            assertNotNull(faces[i].plane, "a straight boundary piece sweeps a plane")
        }
        val south = assertNotNull(faces[0].plane)
        assertClose(south.normal.normalized().y, -1.0, msg = "the first face looks out of the material")
        assertClose(south.v.z, 1.0, msg = "and v runs along the sweep")
    }

    /** A curved boundary piece sweeps a cylinder, and the face list says so rather than inventing a plane. */
    @Test
    fun aCurvedBoundaryPieceIsNamedButIsNoPlane() {
        val faces = assertNotNull(Section3.faces(cylinder()).first)
        assertEquals(3, faces.size, "one side, two caps")
        assertNull(faces[0].plane)
        assertTrue(faces[0].reason!!.contains("cylinder"), "${faces[0].reason!!}")
    }

    /** A pyramid's four lateral faces are named — and every one of them is flat. */
    @Test
    fun aPyramidNamesFourFlatLateralFacesAndItsBase() {
        val faces = assertNotNull(Section3.faces(pyramid()).first)
        assertEquals(5, faces.size, "four lateral faces and the base: $faces")
        assertEquals(4, faces.count { it.name is FaceName.Band })
        for (f in faces) assertNotNull(f.plane, "${f.name.label} should be flat: ${f.reason}")
        val lateral = faces.first { it.name is FaceName.Band }
        assertEquals(3, lateral.outline.size, "a triangle, once the apex's duplicate corner is dropped")
    }

    /** A general boolean's result has no faces to name — OP-9's sink rule inside the section mechanism. */
    @Test
    fun aMeshOnlySolidNamesNoFaces() {
        val (fs, why) = Section3.faces(Feature3.MeshBoolean(BoolOp.SUBTRACT))
        assertNull(fs)
        assertTrue(why!!.contains("mesh-only"), "$why")
    }

    // ---- the degenerate section: the plane lies ON a face ----

    /**
     * The face case, which is the whole reason there is one mechanism: sectioning a solid *at one of its own
     * faces* gives that face's boundary, in the cutting plane's coordinates, with one input per edge and one
     * per corner.
     */
    @Test
    fun aPlaneOnAFaceSectionsToThatFacesBoundary() {
        val f = box(w = 100.0, d = 60.0, h = 20.0)
        val solid = solidOf(f)
        val face = assertNotNull(Section3.faces(f).first)[0]
        val sec = Section3.sectionOf(solid, assertNotNull(face.plane))
        assertEquals(FaceName.Side(0), sec.onFace)
        assertEquals(4, sec.edges.size, "one input per edge of the face")
        assertEquals(4, sec.corners.size)
        assertTrue(!sec.approximated, "a flat face's boundary is exact")
        val corners = sec.cornerPoints
        assertTrue(corners.any { hypot(it.x, it.y) < 1e-9 }, "the face's own origin is a corner: $corners")
        assertTrue(corners.any { hypot(it.x - 100.0, it.y - 20.0) < 1e-9 }, "and so is the far one: $corners")
    }

    // ---- the general structural section ----

    /**
     * The user's own scenario, exactly: a plane parallel to the pyramid's base at half its height sections it
     * into the **exact** 50 × 50 square, corner for corner, with four corner inputs.
     */
    @Test
    fun aPyramidCutAtHalfHeightIsTheExactHalfSizeSquare() {
        val f = pyramid()
        val sec = Section3.sectionOf(solidOf(f), Plane3(Vec3(0.0, 0.0, 45.0), Vec3.X, Vec3.Y))
        assertNull(sec.onFace)
        assertNull(sec.inputsRefusal)
        assertTrue(!sec.approximated, "plane ∩ planar facet is a segment, and nothing here is sampled")
        val named = sec.edges.filter { it.curve != null }
        assertEquals(4, named.size, "the four lateral faces are cut, the base is not")
        val corners = sec.cornerPoints
        assertEquals(4, corners.size, "the four rails are crossed: $corners")
        for (e in listOf(Vec2(25.0, 25.0), Vec2(75.0, 25.0), Vec2(75.0, 75.0), Vec2(25.0, 75.0))) {
            assertTrue(corners.any { (it - e).length() < 1e-9 }, "a corner at $e; got $corners")
        }
        for (e in named) {
            val seg = (e.curve as ProfileElement.Seg).segment
            assertClose((seg.b - seg.a).length(), 50.0, 1e-9, "a side of the half-size square")
        }
    }

    /** Every corner of the section is a **function of the apex**: move it and the square moves with it. */
    @Test
    fun theSectionFollowsTheApex() {
        val sec = Section3.sectionOf(solidOf(pyramid(Vec3(20.0, 50.0, 90.0))), Plane3(Vec3(0.0, 0.0, 45.0), Vec3.X, Vec3.Y))
        val corners = sec.cornerPoints
        assertEquals(4, corners.size)
        for (b in listOf(Vec2(0.0, 0.0), Vec2(100.0, 0.0), Vec2(100.0, 100.0), Vec2(0.0, 100.0))) {
            val mid = Vec2((b.x + 20.0) / 2.0, (b.y + 50.0) / 2.0)
            assertTrue(corners.any { (it - mid).length() < 1e-9 }, "a corner at $mid; got $corners")
        }
    }

    // ---- OP-15's honesty line: the cylinder ----

    /** A cylinder cut **perpendicular to its axis** is its own circle — exactly, from the profile. */
    @Test
    fun aPerpendicularCutThroughACylinderIsTheExactCircle() {
        val sec = Section3.sectionOf(solidOf(cylinder(r = 30.0, h = 80.0)), Plane3(Vec3(0.0, 0.0, 40.0), Vec3.X, Vec3.Y))
        assertTrue(!sec.approximated, "the section is a circle, not a barrel of chords")
        val circle = assertNotNull(sec.edges.firstNotNullOfOrNull { it.curve as? ProfileElement.CircleE })
        assertClose(circle.circle.radius, 30.0, 1e-9, "the profile's own radius")
        assertClose(circle.circle.center.x, 0.0, 1e-9)
        assertClose(circle.circle.center.y, 0.0, 1e-9)
    }

    /**
     * An **inclined** plane through a cylinder is a true ellipse — and since the conics package (OP-24) the
     * section says so **exactly**: semi-axes `r` and `r / cos θ` to 1e-12, centre where the axis meets the
     * plane, and the whole section no longer flagged approximated.
     *
     * This test replaces `anInclinedCutThroughACylinderIsAFlaggedEllipse`, which asserted the opposite —
     * that the same cut came back as a flagged fan of chords. Nothing about any *stored file* changed; the
     * honesty line moved outward by a change in compute, at eval time.
     */
    @Test
    fun anInclinedCutThroughACylinderIsAnExactEllipse() {
        val solid = solidOf(cylinder(r = 30.0, h = 80.0, at = Vec2(0.0, 100.0)))
        val theta = PI / 6.0
        val plane = Plane3(Vec3.ZERO, Vec3.X, Vec3(0.0, cos(theta), sin(theta)))
        val sec = Section3.sectionOf(solid, plane)
        assertTrue(!sec.approximated, "an inclined cylinder section is an exact conic now")
        val e = assertNotNull(sec.edges.firstNotNullOfOrNull { it.curve as? ProfileElement.EllipseE }).ellipse
        assertClose(e.minor, 30.0, 1e-12, "the minor semi-axis is the cylinder's own radius")
        assertClose(e.major, 30.0 / cos(theta), 1e-12, "the major semi-axis is r / cos θ")
        assertClose(e.center.x, 0.0, 1e-12)
        assertClose(e.center.y, 100.0 / cos(theta), 1e-12)
        assertTrue(sec.edges.none { it.sampled != null }, "nothing about it is sampled any more")
    }

    /** A cylinder cut so steeply that the plane leaves through its ends is still refused — and sampled. */
    @Test
    fun aCylinderCutThatRunsOffItsEndsStaysSampled() {
        val solid = solidOf(cylinder(r = 30.0, h = 10.0, at = Vec2(0.0, 0.0)))
        val theta = PI / 3.0
        val plane = Plane3(Vec3(0.0, 0.0, 5.0), Vec3.X, Vec3(0.0, cos(theta), sin(theta)))
        val sec = Section3.sectionOf(solid, plane)
        assertTrue(sec.edges.none { it.curve is ProfileElement.EllipseE }, "the cut is not one whole ellipse")
    }

    /** A cut **along** a cylinder's axis is two rulings — straight and exact, but two curves for one index. */
    @Test
    fun aCutAlongTheAxisOfACylinderIsRulings() {
        val sec = Section3.sectionOf(solidOf(cylinder(r = 30.0, h = 80.0)), Plane3(Vec3.ZERO, Vec3.X, Vec3.Z))
        val side = sec.edges[0]
        assertNull(side.curve, "two rulings are two curves, and one index is one curve")
        assertTrue(side.reason!!.contains("2 separate pieces"), "${side.reason!!}")
        assertTrue(sec.drawn.size >= 2, "both rulings are drawn even though neither is named")
    }

    // ---- the mesh route ----

    /** A mesh-only solid's section draws, and refuses inputs by name. */
    @Test
    fun aMeshOnlySectionDrawsAndRefusesInputs() {
        val solid = solidOf(box())
        val fake = Solid3.of(Feature3.MeshBoolean(BoolOp.SUBTRACT), solid.mesh)
        val sec = Section3.sectionOf(fake, Plane3(Vec3(0.0, 0.0, 10.0), Vec3.X, Vec3.Y))
        assertTrue(sec.drawn.isNotEmpty(), "it draws")
        assertTrue(sec.edges.isEmpty() && sec.corners.isEmpty(), "and names nothing")
        assertTrue(sec.inputsRefusal!!.contains("mesh-only"), "${sec.inputsRefusal!!}")
        assertTrue(sec.approximated)
    }

    /** A plane that misses the part sections to nothing at all — and keeps its structural ordering. */
    @Test
    fun aPlaneThatMissesThePartHasAnEmptySection() {
        val sec = Section3.sectionOf(solidOf(box()), Plane3(Vec3(0.0, 0.0, 500.0), Vec3.X, Vec3.Y))
        assertTrue(sec.isEmpty, "nothing is cut: ${sec.drawn}")
        assertEquals(6, sec.edges.size, "the ordering is structural, so nothing drops out of it")
        assertTrue(sec.edges.all { it.curve == null && it.reason != null })
        assertTrue(sec.corners.all { it.at == null && it.reason != null })
    }

    /** A box cut at 45° through two sides and both caps is a rectangle of exact segments. */
    @Test
    fun aBoxCutAtFortyFiveDegreesIsExact() {
        val s = 1.0 / sqrt(2.0)
        val sec = Section3.sectionOf(solidOf(box(w = 100.0, d = 100.0, h = 20.0)), Plane3(Vec3.ZERO, Vec3.X, Vec3(0.0, s, s)))
        assertTrue(!sec.approximated, "every face it cuts is flat")
        val named = sec.edges.filter { it.curve != null }
        assertEquals(4, named.size, "two sides, the top cap and the bottom cap: ${sec.edges.map { it.reason }}")
        val total =
            named.sumOf { e ->
                GeomMath.tessellatePiece(e.curve!!).zipWithNext().sumOf { (a, b) -> (b - a).length() }
            }
        assertClose(total, 2.0 * 100.0 + 2.0 * 20.0 * sqrt(2.0), 1e-9, "the rectangle's perimeter")
    }
}
