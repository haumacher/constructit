package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.Construction
import constructit.dsl.RegionRef
import constructit.dsl.ScalarRef
import constructit.dsl.plane
import constructit.dsl.region
import constructit.dsl.resultOf
import constructit.dsl.scalar
import constructit.dsl.solid
import constructit.geom.Axis3
import constructit.geom.Feature3
import constructit.geom.Geom3
import constructit.geom.Justification
import constructit.geom.MeshBool
import constructit.geom.SolidFace
import constructit.units.deg
import constructit.units.mm
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **Exact prismatic booleans** (OP-22), as worked spec examples: the counterbore that OP-17's first
 * slice deliberately ends with, a storey stack, and a boss on a shared plane.
 *
 * Every result is checked with [assertManifold] — a boolean is exactly where a mesh engine leaks, so a
 * volume that looks right proves nothing on its own. Volumes are asserted **twice** where curves are
 * involved: exactly against the tessellated polygons the mesh is actually made of, and against the
 * analytic figure to within what the tessellation can explain.
 */
class PrismBooleanTest {
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

    private fun Construction.disc(
        cx: Double,
        cy: Double,
        r: ScalarRef,
        tag: String,
    ): RegionRef = region(loop(circleCR(freePoint("$tag.c", cx.mm, cy.mm), r)))

    private fun tessAreaOf(
        ev: Evaluator,
        r: RegionRef,
    ): Double = Geom3.tessArea(Geom3.tessellateRegion(ev.region(r)).first!!)

    private fun prismOf(
        ev: Evaluator,
        s: constructit.dsl.SolidRef,
    ): Feature3.Prism = ev.solid(s).feature as Feature3.Prism

    // ---- OP-17 slice 1, completed: the counterbored plate ----

    /**
     * The step OP-17's first slice was left one short of: a plate, a through bore, and a **counterbore** —
     * a wider, shallower disc taken out of the top, whose annular shoulder is not special-cased anywhere
     * but is simply what "the area below minus the area above" comes to at that level.
     */
    @Test
    fun aCounterboredPlateIsWatertightAndItsVolumeIsExact() {
        val c = Construction()
        val h = c.parameter("thickness", 12.mm)
        val boreR = c.parameter("boreR", 5.mm)
        val cboreR = c.parameter("cboreR", 8.mm)
        val cboreDepth = c.parameter("cboreDepth", 4.mm)

        val plateRegion = c.rect(-40.0, -25.0, 40.0, 25.0, "plate")
        val plate = c.extrude(c.sketchOn(c.planeXY(), plateRegion), h)
        val boreRegion = c.disc(0.0, 0.0, boreR, "bore")
        val bore = c.extrude(c.sketchOn(c.planeXY(), boreRegion), h)
        val drilled = c.subtract(plate, bore)
        // the counterbore sits on a *derived* plane: the top face lowered by its own depth, so it stays
        // flush with the top however the plate's thickness is edited (OP-8's accessor doing real work)
        val cbPlane = c.planeOffset(c.facePlane(drilled, SolidFace.TOP), c.neg(cboreDepth))
        val cboreRegion = c.disc(0.0, 0.0, cboreR, "cbore")
        val cbore = c.extrude(c.sketchOn(cbPlane, cboreRegion), cboreDepth)
        val part = c.subtract(drilled, cbore)
        val vol = c.measureVolume(part)

        val ev = Evaluator()
        assertTrue(ev.resultOf(part) is EvalResult.Ok, "the counterbore should build: ${ev.resultOf(part)}")
        assertManifold(ev.solid(part).mesh, "counterbored plate")

        val plateArea = tessAreaOf(ev, plateRegion)
        val boreArea = tessAreaOf(ev, boreRegion)
        val cboreArea = tessAreaOf(ev, cboreRegion)
        val exact = (plateArea - boreArea) * 8.0 + (plateArea - cboreArea) * 4.0
        assertClose(ev.scalar(vol).base, exact, tol = 1e-6, msg = "slab volumes are cap area times height, exactly")

        // ...and against the analytic part, where only the two circles are approximated
        val analytic = 80.0 * 50.0 * 12.0 - PI * 25.0 * 8.0 - PI * 64.0 * 4.0
        val err = (ev.scalar(vol).base - analytic) / analytic
        assertTrue(err > 0.0, "an inscribed bore removes slightly too little, so the part comes out heavy")
        assertTrue(err < 1e-3, "volume is $err off the analytic part, more than the tessellation explains")

        // the shape of the stack is the shape of the part: material to 8 mm, then the counterbored slice
        val prism = prismOf(ev, part)
        assertEquals(2, prism.slabs.size, "a through slab and the counterbored one")
        assertClose(prism.slabs[0].z1, 8.0, tol = 1e-9)
        assertEquals(1, prism.slabs[0].regions.single().holes.size, "the bore is a hole below")
        assertEquals(1, prism.slabs[1].regions.single().holes.size, "the counterbore swallows the bore above")
        assertClose(ev.scalar(c.measureBBoxExtent(part, Axis3.Z)).base, 12.0, tol = 1e-9)

        // a named face still works through two booleans (OP-8): the top is where the top is
        assertClose(ev.plane(c.facePlane(part, SolidFace.TOP)).origin.z, 12.0, tol = 1e-9)
    }

    /** The counterbore is parametric: both radii and the depth recompute, and nothing is rebuilt. */
    @Test
    fun editingTheBoreRecomputesWithoutGrowingTheGraph() {
        val c = Construction()
        val h = c.parameter("thickness", 12.mm)
        val boreR = c.parameter("boreR", 5.mm)
        val cboreDepth = c.parameter("cboreDepth", 4.mm)
        val plateRegion = c.rect(-40.0, -25.0, 40.0, 25.0, "plate")
        val boreRegion = c.disc(0.0, 0.0, boreR, "bore")
        val cboreRegion = c.disc(0.0, 0.0, c.parameter("cboreR", 8.mm), "cbore")
        val drilled = c.subtract(c.extrude(c.sketchOn(c.planeXY(), plateRegion), h), c.extrude(c.sketchOn(c.planeXY(), boreRegion), h))
        val cbore =
            c.extrude(
                c.sketchOn(c.planeOffset(c.facePlane(drilled, SolidFace.TOP), c.neg(cboreDepth)), cboreRegion),
                cboreDepth,
            )
        val part = c.subtract(drilled, cbore)
        val vol = c.measureVolume(part)
        assertTrue(Evaluator().resultOf(part) is EvalResult.Ok)

        val nodesBefore = c.nodesCreated
        c.set(boreR, 3.mm)
        val ev2 = Evaluator()
        assertManifold(ev2.solid(part).mesh, "narrower bore")
        val plateArea = tessAreaOf(ev2, plateRegion)
        assertClose(
            ev2.scalar(vol).base,
            (plateArea - tessAreaOf(ev2, boreRegion)) * 8.0 + (plateArea - tessAreaOf(ev2, cboreRegion)) * 4.0,
            tol = 1e-6,
        )

        c.set(cboreDepth, 6.mm)
        val ev3 = Evaluator()
        assertManifold(ev3.solid(part).mesh, "deeper counterbore")
        assertClose(
            ev3.scalar(vol).base,
            (plateArea - tessAreaOf(ev3, boreRegion)) * 6.0 + (plateArea - tessAreaOf(ev3, cboreRegion)) * 6.0,
            tol = 1e-6,
        )
        assertClose(prismOf(ev3, part).slabs[0].z1, 6.0, tol = 1e-9, msg = "the shoulder followed its parameter")
        assertEquals(nodesBefore, c.nodesCreated, "a parameter edit must not create nodes")

        // and a counterbore as deep as the plate is not a counterbore: the shoulder disappears, leaving
        // one slab — still a solid, still watertight
        c.set(cboreDepth, 12.mm)
        val ev4 = Evaluator()
        assertManifold(ev4.solid(part).mesh, "counterbore through")
        assertEquals(1, prismOf(ev4, part).slabs.size)
    }

    /** The mesh of a boolean is a pure function of its parameters — two passes, one triangle soup. */
    @Test
    fun theBooleanMeshIsDeterministic() {
        val c = Construction()
        val h = c.parameter("h", 10.mm)
        val a = c.extrude(c.sketchOn(c.planeXY(), c.rect(0.0, 0.0, 20.0, 20.0, "a")), h)
        val b = c.extrude(c.sketchOn(c.planeXY(), c.disc(10.0, 10.0, c.parameter("r", 4.mm), "b")), h)
        val cut = c.subtract(a, b)
        assertEquals(Evaluator().solid(cut).mesh, Evaluator().solid(cut).mesh)
    }

    // ---- the architectural stack ----

    /**
     * Two storeys with the **same footprint**, unioned: the interface between them is not a face, so the
     * two slabs merge back into one shaft and the mesh has no internal floor in it at all.
     */
    @Test
    fun twoStoreysWithOneFootprintUniteIntoASingleShaft() {
        val c = Construction()
        val v1 = c.freePoint("v1", 0.mm, 0.mm)
        val v2 = c.freePoint("v2", 4000.mm, 0.mm)
        val v3 = c.freePoint("v3", 4000.mm, 3000.mm)
        val v4 = c.freePoint("v4", 0.mm, 3000.mm)
        val t = c.parameter("t", 200.mm)
        val storeyH = c.parameter("storeyH", 2600.mm)
        val footprint = c.thickFootprint(listOf(v1, v2, v3, v4), t, closed = true, justification = Justification.CENTER)
        val ground = c.extrude(c.sketchOn(c.planeXY(), footprint), storeyH)
        val upper = c.extrude(c.sketchOn(c.planeOffset(c.planeXY(), storeyH), footprint), storeyH)
        val block = c.union(ground, upper)

        val ev = Evaluator()
        assertManifold(ev.solid(block).mesh, "two storeys")
        val ringArea = 4200.0 * 3200.0 - 3800.0 * 2800.0
        assertClose(ev.scalar(c.measureVolume(block)).base, ringArea * 5200.0, tol = 1e-6, msg = "the two storeys' volumes add up")
        assertClose(ev.scalar(c.measureBBoxMax(block, Axis3.Z)).base, 5200.0, tol = 1e-9)

        val prism = prismOf(ev, block)
        assertEquals(1, prism.slabs.size, "identical footprints merge: one shaft, no interface")
        // no horizontal face at 2600 mm — a floor there would show up as vertices at that height
        assertTrue(ev.solid(block).mesh.vertices.none { abs(it.z - 2600.0) < 1e-6 }, "the seam is gone, not just invisible")

        // the union is parametric through both operands: thicken the shared footprint
        c.set(t, 300.mm)
        val ev2 = Evaluator()
        assertManifold(ev2.solid(block).mesh, "thicker storeys")
        assertClose(
            ev2.scalar(c.measureVolume(block)).base,
            (4300.0 * 3300.0 - 3700.0 * 2700.0) * 5200.0,
            tol = 1e-6,
        )
    }

    /**
     * **The coplanar interface**: a boss whose base is exactly the plate's top face. The two solids touch
     * over a whole face and share no volume, which is the case a BSP-style mesh boolean gets wrong —
     * here it is the ordinary one, since the level at 6 mm is just a slab boundary.
     */
    @Test
    fun aBossSittingOnAPlateUnitesAcrossTheirSharedPlane() {
        val c = Construction()
        val plateDepth = c.parameter("plateDepth", 6.mm)
        val bossDepth = c.parameter("bossDepth", 4.mm)
        val plate = c.extrude(c.sketchOn(c.planeXY(), c.rect(-20.0, -15.0, 20.0, 15.0, "plate")), plateDepth)
        val boss = c.extrude(c.sketchOn(c.facePlane(plate, SolidFace.TOP), c.rect(-5.0, -5.0, 5.0, 5.0, "boss")), bossDepth)
        val part = c.union(plate, boss)

        val ev = Evaluator()
        assertManifold(ev.solid(part).mesh, "plate with a boss")
        assertClose(ev.scalar(c.measureVolume(part)).base, 40.0 * 30.0 * 6.0 + 10.0 * 10.0 * 4.0, tol = 1e-9)
        val prism = prismOf(ev, part)
        assertEquals(2, prism.slabs.size)
        assertEquals(1, prism.slabs[1].regions.size, "above the plate only the boss is left")

        // the boss follows the plate through the boolean, because the face plane is a node (OP-8)
        c.set(plateDepth, 9.mm)
        val ev2 = Evaluator()
        assertManifold(ev2.solid(part).mesh, "deeper plate with a boss")
        assertClose(ev2.scalar(c.measureVolume(part)).base, 40.0 * 30.0 * 9.0 + 10.0 * 10.0 * 4.0, tol = 1e-9)
        assertClose(ev2.scalar(c.measureBBoxMax(part, Axis3.Z)).base, 13.0, tol = 1e-9)
    }

    /**
     * A boss **overhanging** the plate it sits on: the horizontal boundary at the shared plane crosses the
     * vertical one, which puts a cap corner in the middle of a wall edge. That T-junction is the one thing
     * that would silently leak, so it has its own case (OP-22's conforming pass).
     */
    @Test
    fun aBossOverhangingItsPlateStillClosesAtTheCrossing() {
        val c = Construction()
        val plate = c.extrude(c.sketchOn(c.planeXY(), c.rect(0.0, 0.0, 40.0, 30.0, "plate")), c.parameter("d1", 6.mm))
        val boss = c.extrude(c.sketchOn(c.planeOffset(c.planeXY(), c.parameter("z", 6.mm)), c.rect(30.0, 10.0, 50.0, 20.0, "boss")), c.parameter("d2", 5.mm))
        val part = c.union(plate, boss)
        val ev = Evaluator()
        assertManifold(ev.solid(part).mesh, "overhanging boss")
        assertClose(ev.scalar(c.measureVolume(part)).base, 40.0 * 30.0 * 6.0 + 20.0 * 10.0 * 5.0, tol = 1e-9)
    }

    /** Intersection: the common part of two overlapping prisms, in all three dimensions at once. */
    @Test
    fun theIntersectionOfTwoOverlappingBlocksIsTheirCommonBox() {
        val c = Construction()
        val a = c.extrude(c.sketchOn(c.planeXY(), c.rect(0.0, 0.0, 20.0, 20.0, "a")), c.parameter("da", 10.mm))
        val b = c.extrude(c.sketchOn(c.planeOffset(c.planeXY(), c.parameter("zb", 4.mm)), c.rect(10.0, 5.0, 30.0, 25.0, "b")), c.parameter("db", 10.mm))
        val meet = c.intersect(a, b)
        val ev = Evaluator()
        assertManifold(ev.solid(meet).mesh, "intersection")
        assertClose(ev.scalar(c.measureVolume(meet)).base, 10.0 * 15.0 * 6.0, tol = 1e-9)
        assertClose(ev.scalar(c.measureBBoxMin(meet, Axis3.Z)).base, 4.0, tol = 1e-9)
        assertClose(ev.scalar(c.measureBBoxMax(meet, Axis3.Z)).base, 10.0, tol = 1e-9)
    }

    // ---- what the exact algebra hands on, and what it still refuses (OP-3) ----

    /**
     * A revolve has **no** prismatic reading, so the exact algebra has nothing to say — and since OP-9's
     * engine landed, that is a *hand-off* rather than a refusal: the boolean is computed as a mesh boolean
     * and the result is a [Feature3.MeshBoolean], which is how the value states which engine ran.
     *
     * The refusal is still the behaviour where the engine cannot run (an unsupported platform, a WASM
     * module still loading), and it still names what would answer it — so both arms are asserted here.
     */
    @Test
    fun aBooleanWithARevolveTakesTheGeneralPath() {
        val c = Construction()
        val plate = c.extrude(c.sketchOn(c.planeXY(), c.rect(0.0, 0.0, 20.0, 20.0, "p")), c.parameter("d", 5.mm))
        val o = c.freePoint("axisO", 0.mm, 0.mm)
        val axis = c.direction(o, c.freePoint("axisX", 1.mm, 0.mm))
        val shaft =
            c.revolve(
                c.sketchOn(c.planeXY(), c.rect(0.0, 2.0, 30.0, 8.0, "prof")),
                o,
                axis,
                c.parameter("sweep", 360.0.deg),
            )
        val cut = c.subtract(plate, shaft)
        val ev = Evaluator()
        val r = ev.resultOf(cut)
        if (MeshBool.available) {
            assertTrue(r is EvalResult.Ok, "the general engine answers what the exact algebra cannot: $r")
            assertTrue(ev.solid(cut).feature is Feature3.MeshBoolean, "and the result says which engine ran")
            assertManifold(ev.solid(cut).mesh, "plate minus a turned shaft")
        } else {
            assertTrue(r is EvalResult.Invalid, "a revolve is not a prism, so this boolean has no exact answer")
            assertTrue(r.reason.contains("Manifold (OP-9)"), "reason was: ${r.reason}")
        }
    }

    /**
     * Two prisms on perpendicular planes have no common axis: the exact algebra is **not** stretched to
     * cover them (that is the OP-22 line), the general engine is asked instead, and the result carries the
     * fact that it is mesh-only.
     */
    @Test
    fun twoPrismsOnDifferentAxesTakeTheGeneralPath() {
        val c = Construction()
        val a = c.extrude(c.sketchOn(c.planeXY(), c.rect(0.0, 0.0, 20.0, 20.0, "a")), c.parameter("da", 5.mm))
        val b = c.extrude(c.sketchOn(c.planeXZ(), c.rect(0.0, 0.0, 20.0, 20.0, "b")), c.parameter("db", 5.mm))
        val fused = c.union(a, b)
        val ev = Evaluator()
        val r = ev.resultOf(fused)
        if (MeshBool.available) {
            assertTrue(r is EvalResult.Ok, "a cross-axis union is the general engine's case: $r")
            assertTrue(ev.solid(fused).feature is Feature3.MeshBoolean, "a mesh boolean has no prismatic form")
            assertManifold(ev.solid(fused).mesh, "two prisms across each other")
        } else {
            assertTrue(r is EvalResult.Invalid && r.reason.contains("common axis"), "reason was: $r")
        }
    }

    /**
     * A subtraction that removes everything is **invalid with a reason** rather than a solid with no
     * material in it — and it heals when the cutter shrinks again (OP-3).
     */
    @Test
    fun aBooleanThatRemovesEverythingIsInvalidAndHeals() {
        val c = Construction()
        val d = c.parameter("d", 5.mm)
        val plate = c.extrude(c.sketchOn(c.planeXY(), c.rect(0.0, 0.0, 20.0, 20.0, "p")), d)
        val cutterCorner = c.freePoint("cut.far", 30.mm, 30.mm)
        val cutter =
            c.extrude(
                c.sketchOn(
                    c.planeXY(),
                    c.region(
                        c.loop(
                            c.segment(c.freePoint("cut.a", (-10).mm, (-10).mm), c.freePoint("cut.b", 30.mm, (-10).mm)),
                            c.segment(c.freePoint("cut.b2", 30.mm, (-10).mm), cutterCorner),
                            c.segment(cutterCorner, c.freePoint("cut.d", (-10).mm, 30.mm)),
                            c.segment(c.freePoint("cut.d2", (-10).mm, 30.mm), c.freePoint("cut.a2", (-10).mm, (-10).mm)),
                        ),
                    ),
                ),
                d,
            )
        val part = c.subtract(plate, cutter)
        val gone = Evaluator().resultOf(part)
        assertTrue(gone is EvalResult.Invalid, "nothing is left, so there is no solid")
        assertTrue(gone.reason.contains("leaves nothing"), "reason was: ${gone.reason}")

        // pull the cutter back so it only takes a corner: the part returns
        c.set(cutterCorner, 30.mm, 5.mm)
        val ev = Evaluator()
        assertTrue(ev.resultOf(part) is EvalResult.Ok, "it must heal: ${ev.resultOf(part)}")
        assertManifold(ev.solid(part).mesh, "healed part")
    }

    /** A boolean is an ordinary node: an invalid operand makes it invalid, transitively (OP-3). */
    @Test
    fun anInvalidOperandHidesTheBoolean() {
        val c = Construction()
        val depth = c.parameter("d", 5.mm)
        val a = c.extrude(c.sketchOn(c.planeXY(), c.rect(0.0, 0.0, 20.0, 20.0, "a")), depth)
        val b = c.extrude(c.sketchOn(c.planeXY(), c.rect(5.0, 5.0, 10.0, 10.0, "b")), c.parameter("db", 5.mm))
        val cut = c.subtract(a, b)
        assertTrue(Evaluator().resultOf(cut) is EvalResult.Ok)
        c.set(depth, 0.mm)
        assertTrue(Evaluator().resultOf(cut) is EvalResult.Invalid, "a zero-depth operand has no solid to cut")
        c.set(depth, 5.mm)
        assertTrue(Evaluator().resultOf(cut) is EvalResult.Ok, "and it heals")
    }

    /** Prisms are closed under booleans: a result is an operand, three deep, still watertight. */
    @Test
    fun booleansCompose() {
        val c = Construction()
        val d = c.parameter("d", 10.mm)
        val block = c.extrude(c.sketchOn(c.planeXY(), c.rect(0.0, 0.0, 30.0, 30.0, "blk")), d)
        val hole1 = c.extrude(c.sketchOn(c.planeXY(), c.rect(5.0, 5.0, 10.0, 10.0, "h1")), d)
        val hole2 = c.extrude(c.sketchOn(c.planeXY(), c.rect(20.0, 20.0, 25.0, 25.0, "h2")), d)
        val lid = c.extrude(c.sketchOn(c.planeOffset(c.planeXY(), d), c.rect(0.0, 0.0, 30.0, 30.0, "lid")), c.parameter("dl", 2.mm))
        val part = c.union(c.subtract(c.subtract(block, hole1), hole2), lid)
        val ev = Evaluator()
        assertManifold(ev.solid(part).mesh, "two pockets and a lid")
        assertClose(
            ev.scalar(c.measureVolume(part)).base,
            30.0 * 30.0 * 10.0 - 2.0 * 25.0 * 10.0 + 30.0 * 30.0 * 2.0,
            tol = 1e-9,
        )
        assertEquals(2, prismOf(ev, part).slabs.size, "the pockets' slab and the lid's")
    }

    /** An operand extruded from a *flipped* plane grows the other way — still a common axis (OP-22). */
    @Test
    fun anOperandOnAFlippedPlaneIsStillOnTheSameAxis() {
        val c = Construction()
        // both are symmetric about the x axis, because flipping a plane *mirrors* its in-plane frame
        // (Plane3.flipped negates v) — a pocket drawn on a flipped face is drawn in mirrored coordinates
        val plate = c.extrude(c.sketchOn(c.planeXY(), c.rect((-10).toDouble(), (-10).toDouble(), 10.0, 10.0, "p")), c.parameter("d", 8.mm))
        // a pocket sketched on the plate's *top* face flipped, so it grows downward into the material
        val top = c.facePlane(plate, SolidFace.TOP)
        val pocket = c.extrude(c.sketchOn(c.planeFlipped(top), c.rect(-5.0, -5.0, 5.0, 5.0, "pk")), c.parameter("pd", 3.mm))
        val part = c.subtract(plate, pocket)
        val ev = Evaluator()
        assertManifold(ev.solid(part).mesh, "pocket from the top face")
        assertClose(ev.scalar(c.measureVolume(part)).base, 20.0 * 20.0 * 8.0 - 10.0 * 10.0 * 3.0, tol = 1e-9)
        val prism = prismOf(ev, part)
        assertEquals(2, prism.slabs.size)
        assertClose(prism.slabs[0].z1, 5.0, tol = 1e-9, msg = "the pocket floor is 3 mm below the top")
    }
}
