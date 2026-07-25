package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.BoltCircleArgs
import constructit.dsl.Construction
import constructit.dsl.PointRef
import constructit.dsl.RegionRef
import constructit.dsl.RoundedRectArgs
import constructit.dsl.ScalarRef
import constructit.dsl.boltCircle
import constructit.dsl.circle
import constructit.dsl.instance
import constructit.dsl.plane
import constructit.dsl.region
import constructit.dsl.resultOf
import constructit.dsl.roundedRect
import constructit.dsl.scalar
import constructit.dsl.solid
import constructit.geom.Axis3
import constructit.geom.Circle
import constructit.geom.Geom3
import constructit.geom.GeomMath
import constructit.geom.Loop
import constructit.geom.Plane3
import constructit.geom.ProfileElement
import constructit.geom.Region
import constructit.geom.Segment
import constructit.geom.Sketch3
import constructit.geom.SolidFace
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.units.Dimension
import constructit.units.deg
import constructit.units.mm
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * OP-17 — the 2D↔3D seam, as worked spec examples. The three slices the design record names, in the
 * order it names them: the flanged plate (multi-loop regions, orientation, watertightness), the turned
 * part (a second feature kind, and the open-vs-closed profile rule as a validity case), and a boss on a
 * face (the sketch→feature→sketch loop through a provenance accessor, OP-8).
 *
 * Every solid in this file is checked with [assertManifold]: the mesh is a sink (OP-9), so nothing
 * downstream will notice a leak on our behalf.
 */
class SolidTest {
    // ---- slice 1: the flanged plate ----

    /** The OP-14 region of `RegionTest.flangedPlateRegionArea`, rebuilt here as the sketch to extrude. */
    private fun Construction.flangedPlate(
        w: ScalarRef,
        h: ScalarRef,
    ): RegionRef {
        val centre = freePoint("centre", 0.mm, 0.mm)
        val rr = instance(roundedRect, "plate", RoundedRectArgs(centre, w, h, parameter("cornerR", 8.mm)))
        val bc =
            instance(
                boltCircle,
                "bolts",
                BoltCircleArgs(centre, parameter("pitchDia", 60.mm), 4, parameter("phase", 45.0.deg), parameter("holeDia", 6.mm)),
            )
        val outer =
            loop(
                rr.segments[1],
                rr.arcs[3],
                rr.segments[2],
                rr.arcs[2],
                rr.segments[3],
                rr.arcs[1],
                rr.segments[0],
                rr.arcs[0],
            )
        return region(outer, *bc.holes.map { loop(it) }.toTypedArray())
    }

    /**
     * Slice 1 (minus the counterbore — booleans are the next task): outer rounded rectangle plus four
     * bolt holes, extruded into a plate.
     *
     * The volume is asserted **twice**, on purpose, because there are two honest questions:
     * exactly against the polygons the mesh is actually made of (a prism's volume *is* its cap area
     * times its depth, whatever the triangulation), and to within the tessellation tolerance against
     * the exact area — which the mesh must fall short of, since every arc is inscribed.
     */
    @Test
    fun flangedPlateExtrudesToAWatertightPrism() {
        val c = Construction()
        val w = c.parameter("width", 80.mm)
        val h = c.parameter("height", 50.mm)
        val depth = c.parameter("depth", 6.mm)
        val plate = c.flangedPlate(w, h)
        val solid = c.extrude(c.sketchOn(c.planeXY(), plate), depth)
        val vol = c.measureVolume(solid)
        val extX = c.measureBBoxExtent(solid, Axis3.X)
        val extY = c.measureBBoxExtent(solid, Axis3.Y)
        val extZ = c.measureBBoxExtent(solid, Axis3.Z)
        val minZ = c.measureBBoxMin(solid, Axis3.Z)
        val maxZ = c.measureBBoxMax(solid, Axis3.Z)

        val ev = Evaluator()
        assertManifold(ev.solid(solid).mesh, "flanged plate")
        assertEquals(Dimension.VOLUME, ev.scalar(vol).dim)

        val tess = Geom3.tessellateRegion(ev.region(plate)).first!!
        assertClose(ev.scalar(vol).base, Geom3.tessArea(tess) * 6.0, tol = 1e-6, msg = "prism volume = cap area x depth")

        // ...and against the exact area, to within what the tessellation can explain. The sign is *not*
        // determined: an inscribed corner arc removes a sagitta of material, while an inscribed hole
        // fails to remove one, so a plate with holes comes out marginally heavy (here ~1e-4 relative).
        // Asserting a direction would be asserting which of the two happens to dominate.
        val exactArea = 80.0 * 50.0 - (4.0 - PI) * 64.0 - 4.0 * PI * 9.0
        val exactVol = exactArea * 6.0
        val err = (exactVol - ev.scalar(vol).base) / exactVol
        assertTrue(abs(err) < 1e-3, "tessellated volume is $err off the exact one, more than the tolerance explains")

        assertClose(ev.scalar(extX).base, 80.0, tol = 1e-9)
        assertClose(ev.scalar(extY).base, 50.0, tol = 1e-9)
        assertClose(ev.scalar(extZ).base, 6.0, tol = 1e-9)
        assertClose(ev.scalar(minZ).base, 0.0, tol = 1e-9)
        assertClose(ev.scalar(maxZ).base, 6.0, tol = 1e-9)

        // A parameter edit *computes*, it does not regenerate (OP-21): the graph stays the same size.
        val nodesBefore = c.nodesCreated
        c.set(depth, 10.mm)
        val ev2 = Evaluator()
        assertManifold(ev2.solid(solid).mesh, "deeper plate")
        assertClose(ev2.scalar(vol).base, Geom3.tessArea(tess) * 10.0, tol = 1e-6)

        c.set(w, 120.mm)
        val ev3 = Evaluator()
        assertManifold(ev3.solid(solid).mesh, "wider plate")
        val tess3 = Geom3.tessellateRegion(ev3.region(plate)).first!!
        assertClose(ev3.scalar(vol).base, Geom3.tessArea(tess3) * 10.0, tol = 1e-6)
        assertClose(ev3.scalar(extX).base, 120.0, tol = 1e-9)
        assertEquals(nodesBefore, c.nodesCreated, "a parameter edit must not create nodes")
    }

    /** The mesh is a pure function of the parameters: two passes produce the identical triangle soup. */
    @Test
    fun theMeshIsDeterministic() {
        val c = Construction()
        val plate = c.flangedPlate(c.parameter("width", 80.mm), c.parameter("height", 50.mm))
        val solid = c.extrude(c.sketchOn(c.planeXY(), plate), c.parameter("depth", 6.mm))
        assertEquals(Evaluator().solid(solid).mesh, Evaluator().solid(solid).mesh)
    }

    /**
     * The 3D→2D scalar seam (OP-4/OP-9): a number measured from a mesh is just a number, so it may
     * drive a *new* 2D construction forward. This is the path a papercraft net's edge lengths will take.
     */
    @Test
    fun aScalarMeasuredFromASolidDrivesA2dConstruction() {
        val c = Construction()
        val plate = c.flangedPlate(c.parameter("width", 80.mm), c.parameter("height", 50.mm))
        val depth = c.parameter("depth", 7.mm)
        val solid = c.extrude(c.sketchOn(c.planeXY(), plate), depth)
        val thickness = c.measureBBoxExtent(solid, Axis3.Z)
        val gauge = c.circleCR(c.freePoint("g", 0.mm, 0.mm), thickness)

        assertClose(Evaluator().circle(gauge).radius, 7.0, tol = 1e-9)
        c.set(depth, 11.mm)
        assertClose(Evaluator().circle(gauge).radius, 11.0, tol = 1e-9)
    }

    // ---- slice 2: the turned part ----

    /**
     * A stepped shaft: a closed profile whose bottom edge lies **on** the axis, revolved a full turn.
     * Volume against the analytic sum of cylinders, within the tessellation tolerance — the mesh is an
     * inscribed polygon of revolution, so it must come out slightly small.
     */
    @Test
    fun steppedShaftRevolvesToACylinderStack() {
        val c = Construction()
        val o = c.freePoint("axisO", 0.mm, 0.mm)
        val ax = c.freePoint("axisX", 1.mm, 0.mm)
        val axis = c.direction(o, ax)
        val p0 = c.freePoint("p0", 0.mm, 0.mm)
        val p1 = c.freePoint("p1", 0.mm, 10.mm)
        val p2 = c.freePoint("p2", 20.mm, 10.mm)
        val p3 = c.freePoint("p3", 20.mm, 6.mm)
        val p4 = c.freePoint("p4", 50.mm, 6.mm)
        val p5 = c.freePoint("p5", 50.mm, 0.mm)
        val profile =
            c.region(
                c.loop(
                    c.segment(p0, p1),
                    c.segment(p1, p2),
                    c.segment(p2, p3),
                    c.segment(p3, p4),
                    c.segment(p4, p5),
                    c.segment(p5, p0),
                ),
            )
        val full = c.parameter("sweep", 360.0.deg)
        val shaft = c.revolve(c.sketchOn(c.planeXY(), profile), o, axis, full)
        val vol = c.measureVolume(shaft)

        val ev = Evaluator()
        assertManifold(ev.solid(shaft).mesh, "stepped shaft")
        val analytic = PI * 100.0 * 20.0 + PI * 36.0 * 30.0
        val err = (analytic - ev.scalar(vol).base) / analytic
        assertTrue(err > 0.0, "an inscribed revolution must fall short of the analytic volume")
        assertTrue(err < 5e-3, "revolved volume is $err off pi*r^2*h, more than the tolerance explains")

        // The bounding box: length along the axis is exact (the profile's own corners), while the
        // diameter across it is short by up to twice the chord tolerance, once per side.
        assertClose(ev.scalar(c.measureBBoxExtent(shaft, Axis3.X)).base, 50.0, tol = 1e-9)
        assertClose(ev.scalar(c.measureBBoxExtent(shaft, Axis3.Z)).base, 20.0, tol = 2.0 * GeomMath.TESS_TOL_MM)
    }

    /** A quarter turn: the same profile, now capped at both ends — and still closed. */
    @Test
    fun partialRevolveIsCappedAndClosed() {
        val c = Construction()
        val o = c.freePoint("axisO", 0.mm, 0.mm)
        val axis = c.direction(o, c.freePoint("axisX", 1.mm, 0.mm))
        val profile = c.shaftProfile()
        val quarter = c.revolve(c.sketchOn(c.planeXY(), profile), o, axis, c.parameter("sweep", 90.0.deg))
        val vol = c.measureVolume(quarter)

        val ev = Evaluator()
        assertManifold(ev.solid(quarter).mesh, "quarter shaft")
        val analytic = (PI * 100.0 * 20.0 + PI * 36.0 * 30.0) / 4.0
        val err = (analytic - ev.scalar(vol).base) / analytic
        assertTrue(err > 0.0 && err < 5e-3, "quarter-revolve volume is $err off the analytic quarter")
    }

    /**
     * The open-vs-closed profile rule as a genuine validity case (OP-3): touching the axis is how a
     * turned part is built, but *crossing* it would fold the shell through itself — so the feature goes
     * invalid with a reason, and heals when the profile is dragged back.
     */
    @Test
    fun aProfileCrossingTheAxisIsInvalidAndHeals() {
        val c = Construction()
        val o = c.freePoint("axisO", 0.mm, 0.mm)
        val axis = c.direction(o, c.freePoint("axisX", 1.mm, 0.mm))
        val p3 = c.freePoint("p3", 20.mm, 6.mm)
        val profile = c.shaftProfile(p3)
        val shaft = c.revolve(c.sketchOn(c.planeXY(), profile), o, axis, c.parameter("sweep", 360.0.deg))

        assertTrue(Evaluator().resultOf(shaft) is EvalResult.Ok, "the untouched profile revolves")

        c.set(p3, 20.mm, (-4).mm)
        val bad = Evaluator().resultOf(shaft)
        assertTrue(bad is EvalResult.Invalid, "a profile crossing the axis must not produce a solid")
        assertTrue(bad.reason.contains("crosses the axis"), "reason was: ${bad.reason}")

        c.set(p3, 20.mm, 6.mm)
        val healed = Evaluator()
        assertTrue(healed.resultOf(shaft) is EvalResult.Ok, "dragging back must heal the feature")
        assertManifold(healed.solid(shaft).mesh, "healed shaft")
    }

    /** The stepped-shaft profile, optionally sharing an externally supplied corner point. */
    private fun Construction.shaftProfile(p3: PointRef? = null): RegionRef {
        val a = freePoint("q0", 0.mm, 0.mm)
        val b = freePoint("q1", 0.mm, 10.mm)
        val d = freePoint("q2", 20.mm, 10.mm)
        val e = p3 ?: freePoint("q3", 20.mm, 6.mm)
        val f = freePoint("q4", 50.mm, 6.mm)
        val g = freePoint("q5", 50.mm, 0.mm)
        return region(loop(segment(a, b), segment(b, d), segment(d, e), segment(e, f), segment(f, g), segment(g, a)))
    }

    /**
     * The same shaft mirrored to the *other* side of the axis. Both signs of a direction describe the
     * same axis, so the feature picks the one with the profile on its positive side — a rotation by pi,
     * hence orientation-preserving, which is why the winding rules do not depend on how the axis
     * happened to be drawn. Same solid, same volume, still wound outward.
     */
    @Test
    fun aProfileOnTheFarSideOfTheAxisRevolvesTheSameWay() {
        val c = Construction()
        val o = c.freePoint("axisO", 0.mm, 0.mm)
        val axis = c.direction(o, c.freePoint("axisX", 1.mm, 0.mm))
        val mirrored =
            c.region(
                c.loop(
                    c.segment(c.freePoint("m0", 0.mm, 0.mm), c.freePoint("m1", 0.mm, (-10).mm)),
                    c.segment(c.freePoint("m1b", 0.mm, (-10).mm), c.freePoint("m2", 20.mm, (-10).mm)),
                    c.segment(c.freePoint("m2b", 20.mm, (-10).mm), c.freePoint("m3", 20.mm, (-6).mm)),
                    c.segment(c.freePoint("m3b", 20.mm, (-6).mm), c.freePoint("m4", 50.mm, (-6).mm)),
                    c.segment(c.freePoint("m4b", 50.mm, (-6).mm), c.freePoint("m5", 50.mm, 0.mm)),
                    c.segment(c.freePoint("m5b", 50.mm, 0.mm), c.freePoint("m0b", 0.mm, 0.mm)),
                ),
            )
        val shaft = c.revolve(c.sketchOn(c.planeXY(), mirrored), o, axis, c.parameter("sweep", 360.0.deg))
        val ev = Evaluator()
        assertManifold(ev.solid(shaft).mesh, "mirrored shaft")
        val analytic = PI * 100.0 * 20.0 + PI * 36.0 * 30.0
        assertTrue(abs(ev.scalar(c.measureVolume(shaft)).base - analytic) / analytic < 5e-3)
    }

    /**
     * A sketch on a non-canonical plane, to pin the embedding itself: the XZ plane's normal is −Y
     * (`u × v` = X × Z), so an extrude on it grows towards −y and the sketch's own v axis is world +z.
     */
    @Test
    fun aSketchOnTheXzPlaneExtrudesAlongItsOwnNormal() {
        val c = Construction()
        val solid = c.extrude(c.sketchOn(c.planeXZ(), c.rect(0.0, 0.0, 30.0, 20.0, "xz")), c.parameter("d", 5.mm))
        val ev = Evaluator()
        assertManifold(ev.solid(solid).mesh, "plate on the XZ plane")
        assertClose(ev.scalar(c.measureBBoxExtent(solid, Axis3.X)).base, 30.0, tol = 1e-9)
        assertClose(ev.scalar(c.measureBBoxExtent(solid, Axis3.Z)).base, 20.0, tol = 1e-9)
        assertClose(ev.scalar(c.measureBBoxMin(solid, Axis3.Y)).base, -5.0, tol = 1e-9)
        assertClose(ev.scalar(c.measureBBoxMax(solid, Axis3.Y)).base, 0.0, tol = 1e-9)
        assertClose(ev.scalar(c.measureVolume(solid)).base, 30.0 * 20.0 * 5.0, tol = 1e-9)
    }

    /** A revolved region with a hole: the cavity is toroidal, and the shell is still closed. */
    @Test
    fun revolvingARegionWithAHoleLeavesAClosedCavity() {
        val c = Construction()
        val o = c.freePoint("axisO", 0.mm, 0.mm)
        val axis = c.direction(o, c.freePoint("axisX", 1.mm, 0.mm))
        val outer =
            c.loop(
                c.segment(c.freePoint("r0", 0.mm, 4.mm), c.freePoint("r1", 40.mm, 4.mm)),
                c.segment(c.freePoint("r1b", 40.mm, 4.mm), c.freePoint("r2", 40.mm, 24.mm)),
                c.segment(c.freePoint("r2b", 40.mm, 24.mm), c.freePoint("r3", 0.mm, 24.mm)),
                c.segment(c.freePoint("r3b", 0.mm, 24.mm), c.freePoint("r0b", 0.mm, 4.mm)),
            )
        val hole = c.loop(c.circleCR(c.freePoint("hc", 20.mm, 14.mm), c.parameter("hr", 5.mm)))
        val ring = c.revolve(c.sketchOn(c.planeXY(), c.region(outer, hole)), o, axis, c.parameter("sweep", 360.0.deg))

        val ev = Evaluator()
        assertManifold(ev.solid(ring).mesh, "revolved ring with a toroidal cavity")
        // The cavity is a torus of minor radius 5 about a circle of radius 14: V = 2 pi^2 R r^2.
        val body = 2.0 * PI * 14.0 * (40.0 * 20.0)
        val cavity = 2.0 * PI * PI * 14.0 * 25.0
        val err = abs(ev.scalar(c.measureVolume(ring)).base - (body - cavity)) / (body - cavity)
        assertTrue(err < 1e-2, "revolved volume is $err off body-minus-torus")
    }

    // ---- slice 3: a sketch on a face ----

    /**
     * The slice that actually tests the seam: extrude a plate, take its **top face** as a plane (OP-8
     * provenance accessor), sketch on it, extrude a boss.
     *
     * Two things are asserted, and both are the point: the boss starts exactly *on* the plate's top
     * plane, and editing the **plate's** depth carries the boss with it — the sketch→feature→sketch chain
     * recomputes, because the face plane is a derived node and not a snapshot.
     */
    @Test
    fun aBossSketchedOnThePlatesTopFaceFollowsThePlate() {
        val c = Construction()
        val plateDepth = c.parameter("plateDepth", 6.mm)
        val bossDepth = c.parameter("bossDepth", 4.mm)
        val plate = c.extrude(c.sketchOn(c.planeXY(), c.rect(-20.0, -15.0, 20.0, 15.0, "plate")), plateDepth)
        val top = c.facePlane(plate, SolidFace.TOP)
        val boss = c.extrude(c.sketchOn(top, c.rect(-5.0, -5.0, 5.0, 5.0, "boss")), bossDepth)

        val ev = Evaluator()
        assertManifold(ev.solid(plate).mesh, "plate")
        assertManifold(ev.solid(boss).mesh, "boss")
        assertClose(ev.plane(top).origin.z, 6.0, tol = 1e-9)
        assertClose(ev.scalar(c.measureBBoxMin(boss, Axis3.Z)).base, 6.0, tol = 1e-9, msg = "the boss sits on the plate")
        assertClose(ev.scalar(c.measureBBoxMax(boss, Axis3.Z)).base, 10.0, tol = 1e-9)

        // Deepen the plate: the face moves, so the boss moves. Nothing is rebuilt.
        val nodesBefore = c.nodesCreated
        c.set(plateDepth, 9.mm)
        val ev2 = Evaluator()
        assertClose(ev2.scalar(c.measureBBoxMin(boss, Axis3.Z)).base, 9.0, tol = 1e-9)
        assertClose(ev2.scalar(c.measureBBoxMax(boss, Axis3.Z)).base, 13.0, tol = 1e-9)
        assertEquals(nodesBefore + 2, c.nodesCreated, "only the two new measurements were created")
    }

    /** The bottom face is the sketch plane flipped, so its normal points out of the solid. */
    @Test
    fun theBottomFacePlaneFacesOutOfTheSolid() {
        val c = Construction()
        val plate = c.extrude(c.sketchOn(c.planeXY(), c.rect(0.0, 0.0, 10.0, 10.0, "p")), c.parameter("d", 3.mm))
        val ev = Evaluator()
        val bottom = ev.plane(c.facePlane(plate, SolidFace.BOTTOM))
        assertClose(bottom.normal.z, -1.0, tol = 1e-12)
        assertClose(bottom.origin.z, 0.0, tol = 1e-12)
    }

    /** A revolve has no face this slice can name — refused with a reason rather than guessed (OP-3). */
    @Test
    fun aRevolveHasNoTopFace() {
        val c = Construction()
        val o = c.freePoint("axisO", 0.mm, 0.mm)
        val axis = c.direction(o, c.freePoint("axisX", 1.mm, 0.mm))
        val shaft = c.revolve(c.sketchOn(c.planeXY(), c.shaftProfile()), o, axis, c.parameter("sweep", 360.0.deg))
        val r = Evaluator().resultOf(c.facePlane(shaft, SolidFace.TOP))
        assertTrue(r is EvalResult.Invalid && r.reason.contains("no top or bottom face"), "reason was: $r")
    }

    /** An axis-aligned rectangle region, as four segments through four free points. */
    private fun Construction.rect(
        x0: Double,
        y0: Double,
        x1: Double,
        y1: Double,
        tag: String,
    ): RegionRef {
        val a = freePoint("$tag.a", x0.mm, y0.mm)
        val b = freePoint("$tag.b", x1.mm, y0.mm)
        val d = freePoint("$tag.c", x1.mm, y1.mm)
        val e = freePoint("$tag.d", x0.mm, y1.mm)
        return region(loop(segment(a, b), segment(b, d), segment(d, e), segment(e, a)))
    }

    // ---- the triangulation kernel, tested directly ----

    private fun polyLoop(vararg pts: Vec2): Loop =
        GeomMath.orient(
            Loop(pts.indices.map { ProfileElement.Seg(Segment(pts[it], pts[(it + 1) % pts.size])) }),
            ccw = true,
        )

    private fun circleHole(
        cx: Double,
        cy: Double,
        r: Double,
    ): Loop = Loop(listOf(ProfileElement.CircleE(Circle(Vec2(cx, cy), r), ccw = false)))

    private fun triangulated(region: Region): List<Geom3.Tri3> {
        val (tess, why) = Geom3.tessellateRegion(region)
        assertTrue(tess != null, "tessellation failed: $why")
        val (tris, reason) = Geom3.triangulate(tess!!)
        assertTrue(tris != null, "triangulation failed: $reason")
        return tris!!
    }

    private fun area(tris: List<Geom3.Tri3>): Double = tris.sumOf { (it.b - it.a).cross(it.c - it.a) / 2.0 }

    /** A square with two round holes: bridging, then ear clipping. The area must come out exactly. */
    @Test
    fun squareWithTwoRoundHolesTriangulates() {
        val region =
            Region(
                polyLoop(Vec2(-20.0, -20.0), Vec2(20.0, -20.0), Vec2(20.0, 20.0), Vec2(-20.0, 20.0)),
                listOf(circleHole(-8.0, 0.0, 4.0), circleHole(8.0, 0.0, 4.0)),
            )
        val tess = Geom3.tessellateRegion(region).first!!
        val tris = triangulated(region)
        assertTrue(tris.all { (it.b - it.a).cross(it.c - it.a) > 0.0 }, "every triangle must be counter-clockwise")
        assertClose(area(tris), Geom3.tessArea(tess), tol = 1e-9, msg = "the triangles must tile the region exactly")

        val (solid, why) = Geom3.extrude(Sketch3(Plane3(Vec3.ZERO, Vec3.X, Vec3.Y), listOf(region)), 5.0)
        assertTrue(solid != null, "extrude failed: $why")
        assertManifold(solid!!.mesh, "plate with two holes")
    }

    /** An L-shape: one reflex corner, so a naive fan would leave the material. */
    @Test
    fun lShapeTriangulatesAroundItsReflexCorner() {
        val region =
            Region(
                polyLoop(
                    Vec2(0.0, 0.0),
                    Vec2(30.0, 0.0),
                    Vec2(30.0, 10.0),
                    Vec2(10.0, 10.0),
                    Vec2(10.0, 25.0),
                    Vec2(0.0, 25.0),
                ),
                emptyList(),
            )
        val tris = triangulated(region)
        assertEquals(4, tris.size, "a simple hexagon yields n-2 triangles")
        assertTrue(tris.all { (it.b - it.a).cross(it.c - it.a) > 0.0 })
        assertClose(area(tris), 30.0 * 10.0 + 10.0 * 15.0, tol = 1e-9)
    }

    /** A collinear corner carries no area, so it is dropped rather than emitted as a zero-area ear. */
    @Test
    fun collinearCornersAreDropped() {
        val region =
            Region(
                polyLoop(Vec2(0.0, 0.0), Vec2(20.0, 0.0), Vec2(40.0, 0.0), Vec2(40.0, 10.0), Vec2(0.0, 10.0)),
                emptyList(),
            )
        val tris = triangulated(region)
        assertEquals(3, tris.size, "the collinear corner is dropped, leaving a quadrilateral's two ears plus one")
        assertClose(area(tris), 400.0, tol = 1e-9)
    }

    /** A sliver: near-degenerate, so it must not hang the clipper — and must still tile. */
    @Test
    fun nearDegenerateSliverStillTriangulates() {
        val region =
            Region(
                polyLoop(Vec2(0.0, 0.0), Vec2(100.0, 0.0), Vec2(100.0, 0.01), Vec2(0.0, 0.02)),
                emptyList(),
            )
        val tess = Geom3.tessellateRegion(region).first!!
        val tris = triangulated(region)
        assertClose(area(tris), Geom3.tessArea(tess), tol = 1e-9)

        val (solid, why) = Geom3.extrude(Sketch3(Plane3(Vec3.ZERO, Vec3.X, Vec3.Y), listOf(region)), 2.0)
        assertTrue(solid != null, "extrude failed: $why")
        assertManifold(solid!!.mesh, "sliver prism")
    }

    /** Triangulating the same region twice yields the identical list — no hash order anywhere. */
    @Test
    fun triangulationIsDeterministic() {
        val region =
            Region(
                polyLoop(Vec2(-20.0, -20.0), Vec2(20.0, -20.0), Vec2(20.0, 20.0), Vec2(-20.0, 20.0)),
                listOf(circleHole(-8.0, 0.0, 4.0), circleHole(8.0, 0.0, 4.0), circleHole(0.0, 12.0, 3.0)),
            )
        assertEquals(triangulated(region), triangulated(region))
    }

    /** A sketch with no region, a non-positive depth: refused with a reason, not silently empty (OP-3). */
    @Test
    fun degenerateFeaturesAreInvalidWithReasons() {
        val c = Construction()
        val plate = c.rect(0.0, 0.0, 10.0, 10.0, "p")
        val zero = c.extrude(c.sketchOn(c.planeXY(), plate), c.parameter("d", 0.mm))
        val r = Evaluator().resultOf(zero)
        assertTrue(r is EvalResult.Invalid && r.reason.contains("depth must be positive"), "reason was: $r")

        val noRegion = c.sketchOn(c.planeXY())
        assertTrue(Evaluator().resultOf(noRegion) is EvalResult.Invalid, "a sketch needs a region")
    }
}
