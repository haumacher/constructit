package constructit

import constructit.core.Evaluator
import constructit.dsl.SolidRef
import constructit.dsl.plane
import constructit.dsl.solid
import constructit.editor.Document
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.Feature3
import constructit.geom.Geom3
import constructit.geom.MeshBool
import constructit.geom.Vec2
import constructit.units.Quantity
import constructit.units.mm
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **Datum planes: any line, any angle** (OP-17's datum extension, GitHub issue #6). Reported as
 * *"in general it should be possible to define an arbitrary 2D sketching plane … a new plane can be either
 * parallel to the base plane (already possible with Section) or intersect the base plane in a line under
 * some angle. Sketch-on-face is the special case where the line is a boundary segment and the angle is 90°.
 * Any line in the base sketch can be used, and any angle."*
 *
 * So the acceptance test is exactly that claim of specialness, asserted both ways:
 * [ninetyDegreesOnAFootprintEdgeIsTheSketchOnFacePlane] — a 90° datum on a footprint edge *is* the face
 * plane sketch-on-face derives, and a −90° one is the space that tool actually opens — and
 * [aFortyFiveDegreeDatumExtrudesAtFortyFive], the general case the special one never reached.
 *
 * The rest pins the three conventions a datum needs and a face did not: the frame (the base frame rotated
 * about the line, right-hand rule), the **absolute** origin (the carrier's nearest-origin point — OP-20's
 * anchoring rule, so stretching the host cannot slide the plane's coordinates), and which way a feature
 * builds (Extrude along +normal, Cut along −normal, with the angle's *sign* choosing which is which).
 */
class DatumPlaneTest {
    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerDown(s)
        pointerUp(s)
    }

    private fun Editor.drag(
        from: Vec2,
        to: Vec2,
    ) {
        pointerDown(camera.worldToScreen(from))
        pointerMove(camera.worldToScreen(to))
        pointerUp(camera.worldToScreen(to))
    }

    private fun Editor.solids(): List<Element> = doc.elements.filter { it.kind == ElementKind.SOLID }

    @Suppress("UNCHECKED_CAST")
    private fun Editor.meshOf(el: Element) = Evaluator().solid(el.ref as SolidRef).mesh

    private fun Editor.type(digits: String) {
        for (c in digits) key(c.toString())
        key("Enter")
    }

    private fun requireEngine() =
        assumeTrue(
            MeshBool.available,
            "a tilted cut is cross-axis and needs the general boolean engine (Manifold, OP-9): ${MeshBool.status}",
        )

    private val root2 = 0.5 * kotlin.math.sqrt(2.0)

    // ---- fixture A: a plain plan segment, and a datum turned out of the plan about it ----

    /**
     * A segment from (20, 30) to (80, 30): its carrier is the line y = 30, whose nearest-origin point —
     * the datum's anchor — is (0, 30), deliberately *not* the segment's own start.
     */
    private fun sketch(): Editor {
        val ed = Editor()
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(20.0, 30.0))
        ed.click(Vec2(80.0, 30.0))
        return ed
    }

    private fun Editor.hinge(): Element = doc.elements.last { it.kind == ElementKind.SEGMENT }

    /** Arm *Sketch plane*, type [deg] and click the hinge — the whole gesture, one click and one number. */
    private fun Editor.sketchPlaneOn(
        at: Vec2,
        deg: String? = null,
    ) {
        setTool(Tools.SKETCH_PLANE)
        if (deg != null) type(deg)
        click(at)
    }

    // ---- the frame, and the conventions it states ----

    /**
     * The frame, spelled out: `u` along the line, `v` rising out of the base plane as the angle grows, and
     * the whole thing the base frame **rotated about the line by the right-hand rule** — so the normal is
     * the base normal rotated the same way, and the *sign* of the angle is what turns it round.
     */
    @Test
    fun theFrameIsTheBasePlaneRotatedAboutTheLine() {
        val ed = sketch()
        ed.sketchPlaneOn(Vec2(50.0, 30.0), "45")
        val space = ed.activeSpace
        assertFalse(space.isPlan, "the view switched to the new plane: ${ed.statusHint}")
        assertTrue(space.isDatum, "it is a datum plane, not a face")
        assertEquals(Document.PLAN_SPACE, space.from, "turned out of the plan, which is where the line is drawn")

        val p = Evaluator().plane(assertNotNull(space.plane))
        assertClose(p.origin.x, 0.0, msg = "the origin is the carrier's nearest-origin point (OP-20's rule)")
        assertClose(p.origin.y, 30.0)
        assertClose(p.origin.z, 0.0, msg = "...on the base plane itself")
        assertClose(p.u.x, 1.0, msg = "u runs along the line")
        assertClose(p.u.y, 0.0)
        assertClose(p.v.y, root2, msg = "v is the base's in-plane perpendicular rotated by 45 deg...")
        assertClose(p.v.z, root2, msg = "...so it rises out of the plan")
        assertClose(p.normal.y, -root2, msg = "and the normal is the base normal (+Z) rotated the same way")
        assertClose(p.normal.z, root2)

        // the hinge lies in *both* planes — that is what "intersect the base plane in a line" means
        val world = p.toWorld(Vec2(50.0, 0.0))
        assertClose(world.y, 30.0, msg = "v = 0 is the line itself")
        assertClose(world.z, 0.0)
        assertClose(world.x, 50.0, msg = "50 mm along it from the anchor")
    }

    /**
     * A **negative** angle turns the plane the other way, and that is the one control a datum has over which
     * side counts as out (a face has its material to point away from; a datum has nothing). Typed into the
     * panel, which is where a sign can be typed at all — and it takes effect live, on the plane that exists.
     */
    @Test
    fun theSignOfTheAngleFlipsTheNormal() {
        val ed = sketch()
        ed.sketchPlaneOn(Vec2(50.0, 30.0), "45")
        val angle = assertNotNull(ed.activeSpace.angle)
        ed.doc.setParameter(angle, Quantity.deg(-45.0))
        val p = Evaluator().plane(assertNotNull(ed.activeSpace.plane))
        assertClose(p.v.z, -root2, msg = "v dips below the plan instead")
        assertClose(p.normal.y, root2, msg = "and the normal points the other way — Extrude and Cut swap")
        assertClose(p.normal.z, root2)
    }

    /** At zero degrees a datum *is* the space it came from, re-anchored on the line. Legal, and stated. */
    @Test
    fun aDatumAtZeroDegreesIsTheSpaceItCameFrom() {
        val ed = sketch()
        ed.sketchPlaneOn(Vec2(50.0, 30.0), "0")
        val p = Evaluator().plane(assertNotNull(ed.activeSpace.plane))
        assertClose(p.normal.z, 1.0, msg = "the plan's own normal")
        assertClose(p.v.y, 1.0, msg = "and v is the in-plane perpendicular, unrotated")
        assertClose(p.origin.z, 0.0)
    }

    /**
     * **The origin is absolute** (OP-20's anchoring rule, applied one dimension up): stretching the host
     * segment must not slide the datum's coordinates along it. Dragging the segment's own *start* along its
     * carrier is the discriminating case — an origin at the "defining start" would move by exactly that
     * drag, and the carrier's nearest-origin point does not move at all.
     */
    @Test
    fun stretchingTheHostDoesNotMoveTheDatumsOrigin() {
        val ed = sketch()
        ed.sketchPlaneOn(Vec2(50.0, 30.0), "45")
        val plane = assertNotNull(ed.activeSpace.plane)
        val before = Evaluator().plane(plane).toWorld(Vec2(25.0, 8.0))

        // back to the plan and drag the segment's start along its own carrier, 15 mm
        ed.setActiveSpace(Document.PLAN_SPACE)
        ed.setTool(Tools.SELECT)
        ed.drag(Vec2(20.0, 30.0), Vec2(5.0, 30.0))
        val after = Evaluator().plane(plane)
        assertClose(after.origin.x, 0.0, msg = "the anchor is the carrier's, not the segment's")
        assertClose(after.origin.y, 30.0)
        val moved = after.toWorld(Vec2(25.0, 8.0))
        assertClose(moved.x, before.x, msg = "so a point drawn on the plane stays exactly where it was")
        assertClose(moved.y, before.y)
        assertClose(moved.z, before.z)

        // ...and the hinge's *drawn extent* does follow, because that is what it reports
        val h = assertNotNull(ed.doc.datumHinge(ed.doc.spaces.last(), Evaluator()))
        assertClose(h[0].x, 5.0, msg = "the reference hinge shows how far the picked segment reaches")
        assertClose(h[1].x, 80.0)
    }

    // ---- THE ACCEPTANCE TEST: sketch-on-face is the special case ----

    /**
     * **90° on a boundary segment reproduces sketch-on-face's plane** (the issue's own claim) — and since the
     * session-32 frame rule it reproduces the *space* that tool opens as well, axes and normal alike, because
     * a face space is no longer flipped: both normals point out of the material.
     *
     * The one thing that differs is the **anchoring**, deliberately and in one direction only: a face frame
     * stands on the picked segment's own **midpoint**, because a hole is dimensioned from the part's own
     * edge, while a datum is anchored on the base plane, absolutely (the carrier's foot). So the two frames
     * are the same plane with the same axes, offset purely along `u` — asserted as exactly that.
     */
    @Test
    fun ninetyDegreesOnAFootprintEdgeIsTheSketchOnFacePlane() {
        val ed = plate()
        val solid = ed.solids().single()
        // the face the tool would give us, for comparison: piece 0 is the edge (0,0) -> (80,0)
        val (face, why) = Geom3.sideFace(Evaluator().solid(solid.ref as SolidRef).feature, 0)
        assertNull(why, "the front edge is a planar side face")
        val f = assertNotNull(face).plane

        ed.sketchPlaneOn(Vec2(40.0, 0.0), "90")
        val d = Evaluator().plane(assertNotNull(ed.activeSpace.plane))
        assertClose(d.u.x, f.u.x, msg = "the same u: along the picked edge")
        assertClose(d.u.y, f.u.y)
        assertClose(d.v.z, f.v.z, msg = "the same v: world +Z")
        assertClose(d.normal.x, f.normal.x, msg = "and the same normal, out of the material")
        assertClose(d.normal.y, f.normal.y)
        assertClose(d.normal.z, f.normal.z)
        // the same plane, then: the face's origin lies in it, offset purely along v (the plate's thickness)
        val off = f.origin - d.origin
        assertClose(off.dot(d.normal), 0.0, msg = "the face's frame sits in the datum's plane")
        assertClose(off.dot(d.v), 0.0, msg = "both stand on the same edge")
        assertClose(off.dot(d.u), 40.0, msg = "offset along u to the segment's midpoint — the anchoring, and only that")
    }

    /**
     * ...and the space *Sketch on face* opens is that same **+90°** datum, differing only by where its origin
     * stands — which is what "sketch-on-face is the datum's special case" now means end to end.
     *
     * It used to be the −90° one, because the face space's plane was the face's flipped: that is the reversal
     * this test records. A drawing made on the face at (u, v) is the same world point as the same drawing made
     * on the datum at (u + 40, v), 40 being half the picked edge.
     */
    @Test
    fun theSpaceSketchOnFaceOpensIsThePlusNinetyDatumMovedOntoItsEdge() {
        val faceSide = plate()
        faceSide.setTool(Tools.SKETCH_ON_FACE)
        faceSide.click(Vec2(40.0, 0.0))
        val fs = Evaluator().plane(assertNotNull(faceSide.activeSpace.plane))

        val ed = plate()
        ed.sketchPlaneOn(Vec2(40.0, 0.0), "90")
        val d = Evaluator().plane(assertNotNull(ed.activeSpace.plane))

        assertClose(d.u.x, fs.u.x, msg = "u along the edge, both of them")
        assertClose(d.v.z, fs.v.z, msg = "v up out of the base plane, and up into the face: the same direction")
        assertClose(d.normal.y, fs.normal.y, msg = "and both normals point out of the material")
        val a = d.toWorld(Vec2(-15.0 + 40.0, 12.0))
        val b = fs.toWorld(Vec2(-15.0, 12.0))
        assertClose(a.x, b.x, msg = "the two frames differ by the 40 mm anchor offset along u and nothing else")
        assertClose(a.y, b.y)
        assertClose(a.z, b.z)
    }

    // ---- the general case: a 45 degree plane, and a solid that follows its angle ----

    /**
     * **A 45° datum from a plan segment, drawn on and extruded.** The rectangle (0,0)–(40,10) in the plane's
     * own (u, v) maps to the world corners (0,30,0), (40,30,0), (0,37.07,7.07), (40,37.07,7.07), and a 10 mm
     * extrude follows the plane's **+normal** — (0, −√½, √½) — so the prism's axis stands at 45° to Z and
     * the solid occupies y ∈ 22.93…37.07, z ∈ 0…14.14. Its volume is exactly 40·10·10, because a prism's
     * volume is its cap area times its depth whatever the triangulation.
     */
    @Test
    fun aFortyFiveDegreeDatumExtrudesAtFortyFive() {
        val ed = sketch()
        ed.sketchPlaneOn(Vec2(50.0, 30.0), "45")
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(40.0, 10.0))
        ed.setTool(Tools.EXTRUDE)
        ed.type("10")
        ed.click(Vec2(20.0, 0.0)) // the rectangle's bottom edge, in the plane's own coordinates
        val solid = assertNotNull(ed.solids().singleOrNull(), "one solid came out: ${ed.statusHint}")
        assertEquals(ed.activeSpace.name, solid.space, "and it was built in the datum space")

        val mesh = ed.meshOf(solid)
        assertManifold(mesh, "prism on a 45 deg datum")
        assertClose(Geom3.volume(mesh), 40.0 * 10.0 * 10.0, tol = 1e-6, msg = "40 x 10 swept 10")
        val b = assertNotNull(Geom3.bounds(mesh))
        assertClose(b.first.x, 0.0, msg = "u ran along the line, so x is the sketch's own u")
        assertClose(b.second.x, 40.0)
        assertClose(b.first.y, 30.0 - 10.0 * root2, tol = 1e-9, msg = "the sweep leans out of the plan by 45 deg")
        assertClose(b.second.y, 30.0 + 10.0 * root2, tol = 1e-9)
        assertClose(b.first.z, 0.0, tol = 1e-9, msg = "it starts on the hinge, which lies in the plan")
        assertClose(b.second.z, 20.0 * root2, tol = 1e-9)

        // the honest consequence of a tilted axis, stated rather than discovered: the accessors that need a
        // vertical prism refuse it with a reason and heal (OP-3)
        val feature = Evaluator().solid(solid.ref as SolidRef).feature
        assertNotNull(Geom3.sideFace(feature, 0).second, "a tilted prism has no upright side faces")
        assertNotNull(Geom3.sectionAt(feature, 5.0).second, "and a horizontal cut is not one of its slabs")
    }

    /**
     * **The angle is a live parameter**, which is the whole point of it being a node: retyping it tilts the
     * plane and every feature built on it follows, by recompute and not by rebuilding (`nodesCreated` flat —
     * OP-21's rule).
     */
    @Test
    fun retypingTheAngleTiltsEverythingOnThePlane() {
        val ed = sketch()
        ed.sketchPlaneOn(Vec2(50.0, 30.0), "45")
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(40.0, 10.0))
        ed.setTool(Tools.EXTRUDE)
        ed.type("10")
        ed.click(Vec2(20.0, 0.0))
        val solid = ed.solids().single()
        val angle = assertNotNull(ed.activeSpace.angle, "the space owns the angle as a panel parameter")
        assertClose(ed.doc.spaceAngleDeg(ed.activeSpace), 45.0, tol = 1e-9)

        val nodes = ed.doc.cx.nodesCreated
        ed.doc.setParameter(angle, Quantity.deg(30.0))
        assertEquals(nodes, ed.doc.cx.nodesCreated, "an angle edit recomputes; it never rebuilds the graph")

        val p = Evaluator().plane(assertNotNull(ed.activeSpace.plane))
        assertClose(p.v.z, sin(PI / 6), msg = "the plane now stands at 30 deg")
        assertClose(p.normal.z, cos(PI / 6))
        val b = assertNotNull(Geom3.bounds(ed.meshOf(solid)))
        // the same rigid prism, turned: 10 mm of sketch v rises sin30, and 10 mm of sweep rises cos30
        assertClose(b.second.z, 10.0 * sin(PI / 6) + 10.0 * cos(PI / 6), tol = 1e-9, msg = "the solid followed the plane")
        assertClose(Geom3.volume(ed.meshOf(solid)), 4000.0, tol = 1e-6, msg = "...rigidly")
        assertClose(ed.doc.spaceAngleDeg(ed.activeSpace), 30.0, tol = 1e-9, msg = "and the space says so")
    }

    // ---- the fixture the issue is about: a part, and a tilted cut through it ----

    /**
     * The plate of `FaceSketchTest`: a rectangle (0,0)–(80,50) — a closed ortho path, hence an area — raised
     * 20 mm. Its footprint's first boundary piece is the edge (0,0)→(80,0), which is both the face
     * *Sketch on face* offers and the line the datum tests hinge on.
     */
    private fun plate(): Editor {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(80.0, 50.0))
        ed.activeScalar = ed.doc.newParameter("thickness", 20.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(40.0, 0.0))
        return ed
    }

    /**
     * **A 45° miter through the plate, by clicking** — the cross-axis boolean a datum makes reachable
     * (OP-9's `MeshBool`, since a tilted prism shares no axis with an upright one).
     *
     * The numbers are exact, which is why this is the probe: the datum hinges on the plate's front bottom
     * edge and leans into the material at 45°, so its plane is `z = y`. *Cut* sweeps **−normal**, i.e. into
     * the material and downward, and with a sketch rectangle and a depth that both overhang the part the
     * removed set is precisely `plate ∩ {z ≤ y}` = 80·(50·20 − ½·20²) = 64000 mm³. What is left is the
     * triangular prism `{z > y}`: ½·20·20·80 = **16000 mm³**, spanning y ∈ 0…20 and z ∈ 0…20.
     */
    @Test
    fun aTiltedCutThroughThePlateGoesThroughTheGeneralEngine() {
        requireEngine()
        val ed = plate()
        val base = ed.solids().single()
        ed.sketchPlaneOn(Vec2(40.0, 0.0), "45")
        assertEquals(base, ed.activeSpace.anchor, "the datum's hinge is part of the plate, so that is the part it cuts")

        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(-10.0, -10.0))
        ed.click(Vec2(90.0, 60.0))
        ed.setTool(Tools.CUT)
        ed.type("40")
        ed.click(Vec2(40.0, -10.0)) // the sketch rectangle's bottom edge
        assertEquals(3, ed.solids().size, "one gesture, two solids — the tool and the cut part: ${ed.statusHint}")

        val part = ed.solids().last()
        val mesh = ed.meshOf(part)
        assertManifold(mesh, "mitred plate")
        assertTrue(
            Evaluator().solid(part.ref as SolidRef).feature is Feature3.MeshBoolean,
            "a tilted cut shares no axis with the plate, so it takes the general engine (OP-9/OP-22)",
        )
        assertClose(Geom3.volume(mesh), 16000.0, tol = 1.0, msg = "the 45 deg wedge that is left")
        val b = assertNotNull(Geom3.bounds(mesh))
        assertClose(b.first.x, 0.0, tol = 1e-3, msg = "the cut overhangs in x, so the part keeps its width")
        assertClose(b.second.x, 80.0, tol = 1e-3)
        assertClose(b.first.y, 0.0, tol = 1e-3)
        assertClose(b.second.y, 20.0, tol = 1e-3, msg = "the miter reaches the top face at y = z = 20")
        assertClose(b.second.z, 20.0, tol = 1e-3)
        assertClose(Geom3.volume(ed.meshOf(base)), 80.0 * 50.0 * 20.0, tol = 1e-6, msg = "the plate itself is untouched")
    }

    /**
     * A datum whose hinge belongs to **no solid** has nothing to cut, and *Cut* says so rather than doing
     * something. *Extrude* is what works there, and the refusal names it.
     */
    @Test
    fun cutOnAFreeStandingDatumDeclinesWithAReason() {
        val ed = sketch()
        ed.sketchPlaneOn(Vec2(50.0, 30.0), "45")
        assertNull(ed.activeSpace.anchor, "nothing was built from that segment, so the datum cuts nothing")
        assertNull(ed.doc.facePartTip(), "...and there is no part to chain onto")
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(40.0, 10.0))
        ed.setTool(Tools.CUT)
        ed.type("10")
        ed.click(Vec2(20.0, 0.0))
        assertEquals(0, ed.solids().size, "no part, no cut")
        assertTrue(ed.statusHint.contains("Extrude"), ed.statusHint)
    }

    // ---- spaces compose: a datum on a line drawn in another datum ----

    /**
     * **A datum on a datum** — the two-level chain, and the reason a space's plane is an ordinary node: the
     * second plane is rotated out of the *first* one, whose own plane is rotated out of the plan.
     *
     * A segment along the first plane's u at v = 10, turned 90° about itself: `u` is unchanged, `v` becomes
     * the first plane's **normal**, and the origin is where (0, 10) of the first plane is in the world.
     */
    @Test
    fun aDatumOnADatumIsATwoLevelChain() {
        val ed = sketch()
        ed.sketchPlaneOn(Vec2(50.0, 30.0), "45")
        val first = ed.activeSpace
        val fp = Evaluator().plane(assertNotNull(first.plane))

        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(0.0, 10.0))
        ed.click(Vec2(50.0, 10.0))
        ed.sketchPlaneOn(Vec2(25.0, 10.0), "90")
        val second = ed.activeSpace
        assertTrue(second !== first, "a second space: ${ed.statusHint}")
        assertEquals(first.name, second.from, "turned out of the first datum, not out of the plan")

        val sp = Evaluator().plane(assertNotNull(second.plane))
        assertClose(sp.u.x, fp.u.x, msg = "u still runs along the hinge, which was drawn along the first plane's u")
        assertClose(sp.v.y, fp.normal.y, msg = "at 90 deg, v is the plane it came out of")
        assertClose(sp.v.z, fp.normal.z)
        val expect = fp.toWorld(Vec2(0.0, 10.0))
        assertClose(sp.origin.x, expect.x, msg = "anchored where the carrier's nearest-origin point is, in the first plane")
        assertClose(sp.origin.y, expect.y)
        assertClose(sp.origin.z, expect.z)

        // and geometry drawn here is stamped with *this* space (OP-17's one stamping seam)
        ed.setTool(Tools.CIRCLE_R)
        ed.type("5")
        ed.click(Vec2(10.0, 10.0))
        assertEquals(second.name, ed.doc.elements.last { it.kind == ElementKind.CIRCLE }.space)
    }

    // ---- the parallel case: a datum moved along its own normal (the loft's stack of sections) ----

    /**
     * **0° and an offset is the parallel plane** — the one thing a hinge and an angle cannot state, and what a
     * stack of loft sections needs: at 0° a datum *is* the space it came from, so the plane 60 mm above the
     * plan is that plane offset along its own normal.
     */
    @Test
    fun anOffsetDatumIsTheParallelPlaneCase() {
        val ed = sketch()
        ed.setTool(Tools.SKETCH_PLANE)
        ed.type("0")
        ed.type("60")
        ed.click(Vec2(50.0, 30.0))
        val space = ed.activeSpace
        assertTrue(space.isDatum, "still a datum: a line, an angle and now an offset")
        val p = Evaluator().plane(assertNotNull(space.plane))
        assertClose(p.normal.z, 1.0, msg = "parallel to the plan, normal and all")
        assertClose(p.origin.z, 60.0, msg = "and 60 mm along that normal")
        val world = p.toWorld(Vec2(20.0, 5.0))
        assertClose(world.z, 60.0, msg = "so everything drawn here is 60 mm up")
        assertClose(world.x, 20.0, msg = "with the plan's own x")
        assertClose(world.y, 35.0, msg = "and y measured from the hinge, as an unoffset datum's would be")
        assertTrue(ed.statusHint.contains("offset"), "the space's note says it is offset: ${ed.statusHint}")
        assertTrue(ed.doc.spaceLabel(space).contains("60"), "and so does the space list: ${ed.doc.spaceLabel(space)}")
    }

    /** The offset is a parameter like the angle: retype it and the plane slides, with its drawing on it. */
    @Test
    fun theOffsetIsALiveParameter() {
        val ed = sketch()
        ed.setTool(Tools.SKETCH_PLANE)
        ed.type("0")
        ed.type("60")
        ed.click(Vec2(50.0, 30.0))
        val entry = assertNotNull(ed.activeSpace.offset, "the space knows which parameter moves it")
        ed.doc.setParameter(entry, Quantity.mm(15.0))
        assertClose(Evaluator().plane(assertNotNull(ed.activeSpace.plane)).origin.z, 15.0, msg = "the plane followed the number")
        assertClose(ed.doc.spaceOffsetMm(ed.activeSpace), 15.0)
    }

    /**
     * The offset rides the step as `offset="name"`, byte-equal — and a script written **without** one is a
     * datum through its hinge, which is every datum written before offsets existed (OP-18: a new argument
     * changes no stored literal's meaning, so there is no version bump and nothing to migrate).
     */
    @Test
    fun theOffsetRidesTheStepAndAScriptWithoutOneStillLoads() {
        val ed = sketch()
        ed.setTool(Tools.SKETCH_PLANE)
        ed.type("0")
        ed.type("60")
        ed.click(Vec2(50.0, 30.0))
        val text = DocumentFormat.save(ed.doc)
        assertTrue(Regex("sketchspace \"plane1\" line=e\\d+ angle=\"angle\" offset=\"offset\"").containsMatchIn(text), text)
        assertEquals(text, DocumentFormat.save(DocumentFormat.load(text)), "save -> load -> save must be byte-equal")

        // the pre-offset form, as a permanent load test: same two steps, no `offset=`
        val old =
            """
            constructit 2
            point 20,30 -> e1
            point 80,30 -> e2
            tool segment pts=e1,e2 clicks=20,30;80,30 -> e3
            param "angle" = 0deg
            sketchspace "plane1" line=e3 angle="angle"
            """.trimIndent() + "\n"
        val back = DocumentFormat.load(old)
        val plane = Evaluator().plane(assertNotNull(assertNotNull(back.spaceNamed("plane1")).plane))
        assertClose(plane.origin.z, 0.0, msg = "a datum with no offset goes through its hinge, as it always did")
        assertEquals(null, back.spaceNamed("plane1")!!.offset, "and carries no offset parameter at all")
    }

    // ---- persistence (OP-18) ----

    /** The step is a description — the line, the angle's parameter, the part — and it round-trips. */
    @Test
    fun theDatumStepRoundTripsByteEqual() {
        val ed = plate()
        ed.sketchPlaneOn(Vec2(40.0, 0.0), "45")
        ed.setTool(Tools.CIRCLE_R)
        ed.type("5")
        ed.click(Vec2(25.0, 8.0))

        val text = DocumentFormat.save(ed.doc)
        assertTrue(Regex("sketchspace \"plane1\" line=e\\d+ angle=\"angle\" part=e\\d+").containsMatchIn(text), text)
        assertTrue(text.contains("param \"angle\" = 45deg"), text)
        val again = DocumentFormat.load(text)
        assertEquals(text, DocumentFormat.save(again), "save -> load -> save must be byte-equal")

        // ...and the plane is *re-derived* on load, not stored: same frame, from the same description
        val reloaded = assertNotNull(again.spaceNamed("plane1"))
        val a = Evaluator().plane(assertNotNull(ed.activeSpace.plane))
        val b = Evaluator().plane(assertNotNull(reloaded.plane))
        assertClose(b.origin.y, a.origin.y)
        assertClose(b.normal.y, a.normal.y)
        assertClose(b.normal.z, a.normal.z)
        assertEquals(2, again.elements.count { it.space == "plane1" }, "with its drawing in it — the circle and its centre")
        assertNotNull(reloaded.anchor, "and the part it cuts came back by name, not by re-deriving it")
    }

    /**
     * The **ordering** rule, which a datum is the first step to put under real pressure: a datum is rotated
     * out of the space it was defined in, so the `space` switch the file writes lazily has to land *before*
     * the `sketchspace` step and not after it (OP-18's ordering rule — [Document.noteSpace]).
     */
    @Test
    fun aDatumBuiltAfterSwitchingBackRecordsTheSwitchFirst() {
        val ed = sketch()
        ed.sketchPlaneOn(Vec2(50.0, 30.0), "45")
        val first = ed.activeSpace.name
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(0.0, 10.0))
        ed.click(Vec2(50.0, 10.0))
        // ...off to the plan for a while, then back, and only *then* the second plane
        ed.setActiveSpace(Document.PLAN_SPACE)
        ed.setTool(Tools.CIRCLE_R)
        ed.type("4")
        ed.click(Vec2(0.0, 0.0))
        ed.setActiveSpace(first)
        ed.sketchPlaneOn(Vec2(25.0, 10.0), "90")

        val text = DocumentFormat.save(ed.doc)
        val lines = text.lines()
        val switch = lines.indexOfLast { it.startsWith("space \"$first\"") }
        val second = lines.indexOfLast { it.startsWith("sketchspace \"plane2\"") }
        assertTrue(switch in 0 until second, "the switch back must precede the datum built there:\n$text")
        assertEquals(text, DocumentFormat.save(DocumentFormat.load(text)), "and it replays byte-equal")
    }

    /** Deleting the hinge takes the space and everything drawn in it (OP-17's cascade, unchanged). */
    @Test
    fun deletingTheHingeTakesTheDatumWithIt() {
        val ed = sketch()
        val hinge = ed.hinge()
        ed.sketchPlaneOn(Vec2(50.0, 30.0), "45")
        ed.setTool(Tools.CIRCLE_R)
        ed.type("5")
        ed.click(Vec2(25.0, 8.0))
        assertEquals(2, ed.doc.spaces.size)

        ed.setActiveSpace(Document.PLAN_SPACE)
        ed.setTool(Tools.SELECT)
        ed.click(Vec2(50.0, 30.0))
        assertEquals(hinge.id, assertNotNull(ed.selection).id, "the hinge is selected")
        ed.deleteSelection()
        assertEquals(1, ed.doc.spaces.size, "the space went with the line its plane is derived from")
        assertEquals(0, ed.doc.elements.count { it.kind == ElementKind.CIRCLE }, "and so did the drawing on it")
        assertTrue(ed.activeSpace.isPlan)
    }

    // ---- the view, and the panel ----

    /**
     * The reference context of a datum view is the **hinge**, drawn in the plane's own (u, v) — the datum's
     * u axis, over the extent the picked element reaches. The base space's silhouette is deliberately not
     * projected into it (see DESIGN.md); this is what says *where on the drawing am I standing*.
     */
    @Test
    fun theHingeIsTheDatumsReferenceContext() {
        val ed = sketch()
        ed.sketchPlaneOn(Vec2(50.0, 30.0), "45")
        val h = assertNotNull(ed.doc.spaceOutline(ed.activeSpace, Evaluator()))
        assertEquals(2, h.size, "a hinge is a line, not a rectangle")
        assertClose(h[0].x, 20.0, msg = "from where the picked segment starts, measured from the anchor")
        assertClose(h[1].x, 80.0)
        assertClose(h[0].y, 0.0, msg = "and it lies at v = 0, being the plane's own u axis")
        val target = constructit.editor.SvgDrawTarget()
        ed.render(target)
        assertTrue(target.svg().contains("#cfd8e3"), "drawn at the grid's weight, as context: ${target.svg()}")
    }

    /** An **unbounded** hinge has no extent to draw, and none is invented for it. */
    @Test
    fun anInfiniteHingeShowsNoExtent() {
        val ed = Editor()
        ed.setTool(Tools.LINE)
        ed.click(Vec2(20.0, 30.0))
        ed.click(Vec2(80.0, 30.0))
        ed.sketchPlaneOn(Vec2(50.0, 30.0), "45")
        assertTrue(ed.activeSpace.isDatum, "an infinite line carries a sketch plane like any other: ${ed.statusHint}")
        assertNull(ed.doc.spaceOutline(ed.activeSpace, Evaluator()), "but there is no extent to show")
    }

    /**
     * The panel rule of GitHub #2 applies to a datum with no case of its own (it is stated over spaces, not
     * over faces): the active space's 2D elements, plus every solid.
     */
    @Test
    fun thePanelListsADatumsOwnDrawingPlusTheSolids() {
        val ed = plate()
        val plan = ed.doc.listedElements().toList()
        ed.sketchPlaneOn(Vec2(40.0, 0.0), "45")
        ed.setTool(Tools.CIRCLE_R)
        ed.type("5")
        ed.click(Vec2(25.0, 8.0))
        val here = ed.doc.listedElements()
        assertTrue(here.any { it.kind == ElementKind.CIRCLE }, "the drawing on this plane")
        assertTrue(here.any { it.kind == ElementKind.SOLID }, "and the solids, which belong to no space")
        assertTrue(here.none { it.kind == ElementKind.SEGMENT }, "but not the plan's own geometry: $here")
        assertTrue(plan.none { it.kind == ElementKind.CIRCLE })
        // ...and the partition holds: every 2D element in exactly one space's list
        val circle = ed.doc.elements.last { it.kind == ElementKind.CIRCLE }
        ed.setActiveSpace(Document.PLAN_SPACE)
        assertFalse(ed.doc.listedIn(circle), "the circle belongs to the plane it was drawn on")
    }

    /** How a space names itself in the toolbar — the document's answer, so the shell only renders it. */
    @Test
    fun theSpaceListNamesADatumByItsAngleAndItsLine() {
        val ed = plate()
        ed.sketchPlaneOn(Vec2(40.0, 0.0), "45")
        val labels = ed.doc.spaces.map { ed.doc.spaceLabel(it) }
        assertEquals("plan", labels[0])
        val hinge = assertNotNull(ed.activeSpace.hinge)
        assertEquals("plane1 (45° on ${ed.doc.nameOf(hinge)})", labels[1], "the angle and the line it turns about")

        // a datum on a datum says where it came from too
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(0.0, 10.0))
        ed.click(Vec2(50.0, 10.0))
        ed.sketchPlaneOn(Vec2(25.0, 10.0), "30")
        assertTrue(ed.doc.spaceLabel(ed.activeSpace).endsWith(", from plane1)"), ed.doc.spaceLabel(ed.activeSpace))
    }

    /** An **ortho leg** carries a sketch plane like any other line — the ordinary carrier coercion. */
    @Test
    fun aWallLegCanCarryASketchPlane() {
        val ed = Editor()
        ed.activeScalar = ed.doc.newParameter("thickness", 10.0.mm)
        ed.setTool(Tools.WALL)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 0.0))
        ed.key("Escape")
        val leg = ed.doc.elements.first { it.kind == ElementKind.SEGMENT }
        ed.sketchPlaneOn(Vec2(50.0, 0.0), "90")
        assertTrue(ed.activeSpace.isDatum, "the wall's centreline is a line: ${ed.statusHint}")
        assertEquals(leg.id, assertNotNull(ed.activeSpace.hinge).id, "and it is the hinge")
        val p = Evaluator().plane(assertNotNull(ed.activeSpace.plane))
        assertClose(p.u.x, 1.0, msg = "u along the leg")
        assertClose(p.v.z, 1.0, msg = "v straight up — a vertical plane through the wall's centreline")
    }
}
