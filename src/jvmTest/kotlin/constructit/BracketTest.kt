package constructit

import constructit.core.Evaluator
import constructit.dsl.Construction
import constructit.dsl.RegionRef
import constructit.dsl.RoundedRectArgs
import constructit.dsl.ScalarRef
import constructit.dsl.SolidRef
import constructit.dsl.instance
import constructit.dsl.region
import constructit.dsl.roundedRect
import constructit.dsl.scalar
import constructit.dsl.solid
import constructit.geom.Axis3
import constructit.geom.Feature3
import constructit.geom.Geom3
import constructit.geom.SolidFace
import constructit.units.mm
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **Showcase 3 — the maker's reverse-engineered spare part.**
 *
 * The spec, told as the job: *a bracket broke; I have the pieces and a caliper.* Every dimension in this
 * file is therefore a **named parameter** standing for one caliper reading — plate width, plate depth, plate
 * thickness, corner radius, bolt spacing, bolt Ø, counterbore Ø and depth, boss Ø, boss height, bore Ø — and
 * nothing is a literal buried in geometry. That is the whole point of the showcase: when the second
 * measurement of the plate says 140 rather than 120, or the axle turns out to be Ø 12 rather than Ø 10, the
 * part is *retyped*, not redrawn, and [retypingTwoMeasurementsRecomputesTheWholePart] asserts that the graph
 * does not grow by a single node while it happens.
 *
 * There is nothing new in the engine here, and that is the claim: a rounded-rect macro (OP-6), a
 * plane-from-face accessor (OP-8), and three exact prismatic booleans (OP-22) compose into a real part. The
 * only thing worth reading closely is where each feature's *datum* comes from — the counterbores are sunk
 * from the **plate's** top face, the boss stands on the same face, and the bore runs the full stack — so
 * every one of them follows a re-measured plate thickness on its own.
 *
 * The part: a 120 × 80 × 8 plate with R10 corners, two Ø 6 bolt holes 90 apart counterbored Ø 12 × 3 from
 * the top, a Ø 24 × 10 boss in the middle, and a Ø 10 bore through the whole stack.
 */
class BracketTest {
    /** One caliper reading each — the "measured off the broken part" list, in one place. */
    private class Measured(val c: Construction) {
        val plateW = c.parameter("plateWidth", 120.mm)
        val plateD = c.parameter("plateDepth", 80.mm)
        val plateT = c.parameter("plateThickness", 8.mm)
        val cornerR = c.parameter("cornerRadius", 10.mm)
        val boltSpacing = c.parameter("boltSpacing", 90.mm)
        val boltR = c.parameter("boltRadius", 3.mm)
        val cboreR = c.parameter("counterboreRadius", 6.mm)
        val cboreDepth = c.parameter("counterboreDepth", 3.mm)
        val bossR = c.parameter("bossRadius", 12.mm)
        val bossH = c.parameter("bossHeight", 10.mm)
        val boreR = c.parameter("boreRadius", 5.mm)
    }

    /** The part, plus the regions a volume assertion needs to be exact about the tessellated caps. */
    private class Bracket(
        val part: SolidRef,
        val plateRegion: RegionRef,
        val boltRegion: RegionRef,
        val cboreRegion: RegionRef,
        val bossRegion: RegionRef,
        val boreRegion: RegionRef,
    )

    private fun Construction.disc(
        cx: ScalarRef,
        cy: ScalarRef,
        r: ScalarRef,
    ): RegionRef = region(loop(circleCR(pointXY(cx, cy), r)))

    private fun bracket(m: Measured): Bracket {
        val c = m.c
        val centre = c.freePoint("centre", 0.mm, 0.mm)
        val zero = c.const(0.mm)

        // the plate: the rounded-rect macro, walked into one closed boundary
        val rr = c.instance(roundedRect, "plate", RoundedRectArgs(centre, m.plateW, m.plateD, m.cornerR))
        val plateRegion =
            c.region(
                c.loop(
                    rr.segments[1],
                    rr.arcs[3],
                    rr.segments[2],
                    rr.arcs[2],
                    rr.segments[3],
                    rr.arcs[1],
                    rr.segments[0],
                    rr.arcs[0],
                ),
            )
        val plate = c.extrude(c.sketchOn(c.planeXY(), plateRegion), m.plateT)
        val plateTop = c.facePlane(plate, SolidFace.TOP)

        // the boss, on the plate's own top face — so it rides a re-measured thickness (OP-8)
        val bossRegion = c.disc(zero, zero, m.bossR)
        val boss = c.extrude(c.sketchOn(plateTop, bossRegion), m.bossH)
        val body = c.union(plate, boss)

        // the two bolt holes, at ±spacing/2, each counterbored flush with the plate's top face
        val halfSpacing = c.scale(m.boltSpacing, 0.5)
        val cborePlane = c.planeOffset(plateTop, c.neg(m.cboreDepth))
        var cut = body
        val boltRegions = ArrayList<RegionRef>()
        val cboreRegions = ArrayList<RegionRef>()
        for (x in listOf(c.neg(halfSpacing), halfSpacing)) {
            val bolt = c.disc(x, zero, m.boltR)
            val cbore = c.disc(x, zero, m.cboreR)
            boltRegions.add(bolt)
            cboreRegions.add(cbore)
            cut = c.subtract(cut, c.extrude(c.sketchOn(c.planeXY(), bolt), m.plateT))
            cut = c.subtract(cut, c.extrude(c.sketchOn(cborePlane, cbore), m.cboreDepth))
        }

        // the bore, through plate and boss in one go
        val boreRegion = c.disc(zero, zero, m.boreR)
        val bore = c.extrude(c.sketchOn(c.planeXY(), boreRegion), c.add(m.plateT, m.bossH))
        return Bracket(c.subtract(cut, bore), plateRegion, boltRegions[0], cboreRegions[0], bossRegion, boreRegion)
    }

    private fun tessArea(
        ev: Evaluator,
        r: RegionRef,
    ): Double = Geom3.tessArea(Geom3.tessellateRegion(ev.region(r)).first!!)

    /**
     * The part's volume, computed from the tessellated caps the mesh is actually made of, level by level:
     * plate below the counterbores, plate between them and its top face, then the boss.
     */
    private fun expectedVolume(
        ev: Evaluator,
        b: Bracket,
        plateT: Double,
        cboreDepth: Double,
        bossH: Double,
    ): Double {
        val plate = tessArea(ev, b.plateRegion)
        val bolt = tessArea(ev, b.boltRegion)
        val cbore = tessArea(ev, b.cboreRegion)
        val boss = tessArea(ev, b.bossRegion)
        val bore = tessArea(ev, b.boreRegion)
        return (plate - 2 * bolt - bore) * (plateT - cboreDepth) +
            (plate - 2 * cbore - bore) * cboreDepth +
            (boss - bore) * bossH
    }

    @Test
    fun theBracketIsWatertightAndItsVolumeIsExact() {
        val m = Measured(Construction())
        val c = m.c
        val b = bracket(m)
        val vol = c.measureVolume(b.part)

        val ev = Evaluator()
        assertManifold(ev.solid(b.part).mesh, "bracket")
        assertClose(
            ev.scalar(vol).base,
            expectedVolume(ev, b, 8.0, 3.0, 10.0),
            tol = 1e-6,
            msg = "each slab's volume is its cap area times its height, exactly",
        )

        // ...and against the analytic part, where only the five circles and four corner arcs are inscribed
        val plate = 120.0 * 80.0 - (4.0 - PI) * 100.0
        val analytic =
            (plate - 2 * PI * 9.0 - PI * 25.0) * 5.0 +
                (plate - 2 * PI * 36.0 - PI * 25.0) * 3.0 +
                (PI * 144.0 - PI * 25.0) * 10.0
        assertTrue(
            kotlin.math.abs(ev.scalar(vol).base - analytic) / analytic < 1e-3,
            "the volume is more than the tessellation off the analytic part",
        )

        // the shape of the stack *is* the shape of the part (OP-22): three levels, and where they change
        val prism = ev.solid(b.part).feature as Feature3.Prism
        assertEquals(3, prism.slabs.size, "below the counterbores, the counterbored slice, and the boss")
        assertClose(prism.slabs[0].z1, 5.0, tol = 1e-9, msg = "the counterbore shoulder is 3 mm below the top")
        assertClose(prism.slabs[1].z1, 8.0, tol = 1e-9, msg = "the plate's top face")
        assertClose(prism.slabs[2].z1, 18.0, tol = 1e-9, msg = "the boss's top")
        assertEquals(1, prism.slabs[2].regions.size, "above the plate only the bored boss is left")

        // the box: a rounded rectangle's extremes are its straight edges, so these are exact
        assertClose(ev.scalar(c.measureBBoxExtent(b.part, Axis3.X)).base, 120.0, tol = 1e-9)
        assertClose(ev.scalar(c.measureBBoxExtent(b.part, Axis3.Y)).base, 80.0, tol = 1e-9)
        assertClose(ev.scalar(c.measureBBoxExtent(b.part, Axis3.Z)).base, 18.0, tol = 1e-9)
    }

    /**
     * The job as it actually goes: two of the caliper readings were wrong. The plate is 140 wide, and the
     * axle it takes is Ø 12 rather than Ø 10 — so the bore opens up.
     *
     * Both are typed, both recompute through the **existing** graph (`nodesCreated` does not move), and the
     * part stays watertight. This is the difference between a parametric model and a drawing.
     */
    @Test
    fun retypingTwoMeasurementsRecomputesTheWholePart() {
        val m = Measured(Construction())
        val c = m.c
        val b = bracket(m)
        val vol = c.measureVolume(b.part)
        val width = c.measureBBoxExtent(b.part, Axis3.X)
        assertManifold(Evaluator().solid(b.part).mesh, "bracket as first measured")
        assertClose(Evaluator().scalar(width).base, 120.0, tol = 1e-9)

        val nodesBefore = c.nodesCreated
        c.set(m.plateW, 140.mm)
        c.set(m.boreR, 6.mm)
        val ev = Evaluator()
        assertManifold(ev.solid(b.part).mesh, "bracket as re-measured")
        assertClose(ev.scalar(vol).base, expectedVolume(ev, b, 8.0, 3.0, 10.0), tol = 1e-6)
        assertClose(ev.scalar(width).base, 140.0, tol = 1e-9)
        assertEquals(nodesBefore, c.nodesCreated, "retyping a measurement must not create nodes")
    }

    /**
     * A thicker plate carries every feature that was placed *on* it: the counterbore shoulder stays 3 mm
     * below the top face, the boss stands on the new face, and the bore still runs the whole stack. Nothing
     * here is re-derived from the mesh — each datum is the plate's own face plane (OP-8), so it moves with
     * the parameter.
     */
    @Test
    fun aThickerPlateCarriesTheCounterboresTheBossAndTheBore() {
        val m = Measured(Construction())
        val c = m.c
        val b = bracket(m)
        val vol = c.measureVolume(b.part)

        c.set(m.plateT, 11.mm)
        val ev = Evaluator()
        assertManifold(ev.solid(b.part).mesh, "thicker bracket")
        val prism = ev.solid(b.part).feature as Feature3.Prism
        assertEquals(3, prism.slabs.size)
        assertClose(prism.slabs[0].z1, 8.0, tol = 1e-9, msg = "the shoulder followed the top face")
        assertClose(prism.slabs[1].z1, 11.0, tol = 1e-9)
        assertClose(prism.slabs[2].z1, 21.0, tol = 1e-9, msg = "the boss rose with the plate")
        assertClose(ev.scalar(vol).base, expectedVolume(ev, b, 11.0, 3.0, 10.0), tol = 1e-6)
        assertClose(ev.scalar(c.measureBBoxExtent(b.part, Axis3.Z)).base, 21.0, tol = 1e-9)
    }

    /** The mesh of a five-boolean part is still a pure function of its parameters. */
    @Test
    fun theBracketMeshIsDeterministic() {
        val m = Measured(Construction())
        val b = bracket(m)
        assertEquals(Evaluator().solid(b.part).mesh, Evaluator().solid(b.part).mesh)
    }
}
