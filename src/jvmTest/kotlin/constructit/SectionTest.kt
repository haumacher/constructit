package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.Construction
import constructit.dsl.RegionRef
import constructit.dsl.ScalarRef
import constructit.dsl.SolidRef
import constructit.dsl.region
import constructit.dsl.resultOf
import constructit.dsl.scalar
import constructit.dsl.solid
import constructit.geom.Feature3
import constructit.geom.Geom3
import constructit.geom.ProfileElement
import constructit.geom.SolidFace
import constructit.geom.Vec2
import constructit.units.Dimension
import constructit.units.deg
import constructit.units.mm
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **Sections** — the downward direction of the seam (OP-17: `section(solid, plane) → Region`), at the
 * engine and DSL level. The tool half is in `SeamDownToolTest`.
 *
 * The claim under test is that this direction is not an approximation: a prismatic solid *is* a stack of
 * areas over z-intervals (OP-22), so its cross-section at a height **is** the slab there, corner for
 * corner — and a plain extrude is answered from its own analytic sketch, so a cut through a bored plate
 * keeps its exact circles. What cannot be answered exactly is refused with a reason and heals (OP-3):
 * a revolve, a prism whose axis is not vertical, a height outside the material, and a cut that falls into
 * several disjoint areas (which the *type* refuses — a region is one area).
 */
class SectionTest {
    /** An axis-aligned rectangle region through four free points, named so ids stay stable. */
    private fun Construction.rect(
        x0: Double,
        y0: Double,
        x1: Double,
        y1: Double,
        tag: String,
    ): RegionRef {
        val a = freePoint("${tag}0", x0.mm, y0.mm)
        val b = freePoint("${tag}1", x1.mm, y0.mm)
        val c = freePoint("${tag}2", x1.mm, y1.mm)
        val d = freePoint("${tag}3", x0.mm, y1.mm)
        return region(loop(segment(a, b), segment(b, c), segment(c, d), segment(d, a)))
    }

    private fun areaOf(
        c: Construction,
        r: RegionRef,
    ): Double = Evaluator().scalar(c.regionArea(r)).base

    /** The corners of a section's outer boundary — the honest way to ask *where* it landed. */
    private fun cornersOf(
        c: Construction,
        r: RegionRef,
    ): List<Vec2> = Geom3.tessellateLoop(Evaluator().region(r).outer)

    /** A 100 x 100 plate 0..10 with a 50 x 50 block on its top face: a two-slab prism. */
    private class Stack(val c: Construction, val stack: SolidRef, val plateDepth: ScalarRef)

    private fun twoStoreyStack(): Stack {
        val c = Construction()
        val d1 = c.parameter("d1", 10.mm)
        val plate = c.extrude(c.sketchOn(c.planeXY(), c.rect(0.0, 0.0, 100.0, 100.0, "p")), d1)
        val block =
            c.extrude(
                c.sketchOn(c.facePlane(plate, SolidFace.TOP), c.rect(0.0, 0.0, 50.0, 50.0, "b")),
                c.parameter("d2", 10.mm),
            )
        return Stack(c, c.union(plate, block), d1)
    }

    private fun section(
        c: Construction,
        solid: SolidRef,
        h: Double,
    ): RegionRef = c.sectionAt(solid, c.parameter("h${(h * 1000).toInt()}", h.mm))

    // ---- exactness ----

    @Test
    fun theSectionOfAPrismIsTheSlabItself() {
        val s = twoStoreyStack()
        val prism = Evaluator().solid(s.stack).feature as Feature3.Prism
        assertEquals(listOf(0.0, 10.0), prism.slabs.map { it.z0 }, "the stack is two slabs")

        val low = section(s.c, s.stack, 5.0)
        val high = section(s.c, s.stack, 15.0)
        assertEquals(Dimension.AREA, Evaluator().scalar(s.c.regionArea(low)).dim)
        assertClose(areaOf(s.c, low), 100.0 * 100.0, tol = 1e-9, msg = "the lower slab, exactly")
        assertClose(areaOf(s.c, high), 50.0 * 50.0, tol = 1e-9, msg = "the upper slab, exactly")
        // corner for corner, not merely by area
        assertEquals(
            listOf(Vec2(0.0, 0.0), Vec2(50.0, 0.0), Vec2(50.0, 50.0), Vec2(0.0, 50.0)).sortedWith(compareBy({ it.x }, { it.y })),
            cornersOf(s.c, high).sortedWith(compareBy({ it.x }, { it.y })),
        )
    }

    /**
     * A plain extrude is answered from its **analytic** sketch, not from its prismatic reading: the
     * boolean's tessellation is what OP-22 needs, and a section needs nothing of the sort — so a cut
     * through a round plate is still a circle, exactly.
     */
    @Test
    fun theSectionOfAPlainExtrudeKeepsItsExactCircles() {
        val c = Construction()
        val plate =
            c.extrude(
                c.sketchOn(c.planeXY(), c.region(c.loop(c.circleCR(c.freePoint("o", 0.mm, 0.mm), c.parameter("r", 20.mm))))),
                c.parameter("d", 8.mm),
            )
        val cut = section(c, plate, 4.0)
        assertClose(areaOf(c, cut), PI * 400.0, tol = 1e-9, msg = "pi r^2 to the last bit, so no tessellation happened")
        val outer = Evaluator().region(cut).outer.elements
        assertEquals(1, outer.size)
        assertTrue(outer.single() is ProfileElement.CircleE, "the boundary is still one analytic circle: ${outer.single()}")
    }

    // ---- the boundary rule, stated in the world ----

    /**
     * A cut landing exactly on a slab interface shows the material **above** it. Consequently the solid's
     * own **bottom** face is a section and its **top** face is not — a face is not a cross-section, and
     * refusing is more useful than an empty area.
     */
    @Test
    fun aCutOnAnInterfaceShowsTheStoreyAbove() {
        val s = twoStoreyStack()
        assertClose(areaOf(s.c, section(s.c, s.stack, 10.0)), 50.0 * 50.0, tol = 1e-9, msg = "at 10 mm the upper storey starts")
        assertClose(areaOf(s.c, section(s.c, s.stack, 0.0)), 100.0 * 100.0, tol = 1e-9, msg = "the bottom face is a section")

        val top = s.c.sectionAt(s.stack, s.c.parameter("hTop", 20.mm))
        val r = Evaluator().resultOf(top)
        assertTrue(r is EvalResult.Invalid && r.reason.contains("no material"), "reason was: $r")
        assertTrue(r.reason.contains("top face is not a section"), r.reason)
    }

    /**
     * The rule is stated in the **world**, so it holds for a solid extruded from a *flipped* face plane,
     * whose own axis runs downwards — and the areas come back in **world plan coordinates**, which for
     * such a plane means genuinely mirrored, not merely renumbered.
     */
    @Test
    fun aSolidGrownDownwardsSectionsInWorldCoordinates() {
        val c = Construction()
        val plate = c.extrude(c.sketchOn(c.planeXY(), c.rect(0.0, 0.0, 100.0, 100.0, "p")), c.parameter("d", 10.mm))
        // on the *bottom* face (u = X, v = -Y, normal -Z): sketch y grows into world -y
        val skirt =
            c.extrude(
                c.sketchOn(c.facePlane(plate, SolidFace.BOTTOM), c.rect(10.0, 10.0, 30.0, 20.0, "s")),
                c.parameter("ds", 5.mm),
            )
        assertManifold(Evaluator().solid(skirt).mesh, "skirt below the plate")

        val cut = section(c, skirt, -2.0)
        assertClose(areaOf(c, cut), 20.0 * 10.0, tol = 1e-9)
        val corners = cornersOf(c, cut)
        assertClose(corners.minOf { it.x }, 10.0, tol = 1e-9)
        assertClose(corners.maxOf { it.x }, 30.0, tol = 1e-9)
        assertClose(corners.minOf { it.y }, -20.0, tol = 1e-9, msg = "the flipped frame mirrors the sketch into the plan")
        assertClose(corners.maxOf { it.y }, -10.0, tol = 1e-9)

        // its own bottom face (world -5) is a section; its own top face (world 0) is not
        assertClose(areaOf(c, section(c, skirt, -5.0)), 20.0 * 10.0, tol = 1e-9)
        assertTrue(Evaluator().resultOf(c.sectionAt(skirt, c.parameter("h0", 0.mm))) is EvalResult.Invalid)
    }

    // ---- what is refused, and heals ----

    @Test
    fun aHeightOutsideTheSolidIsRefusedAndHeals() {
        val c = Construction()
        val plate = c.extrude(c.sketchOn(c.planeXY(), c.rect(0.0, 0.0, 40.0, 30.0, "p")), c.parameter("d", 6.mm))
        val h = c.parameter("h", 20.mm)
        val cut = c.sectionAt(plate, h)
        val nodesBefore = c.nodesCreated

        val bad = Evaluator().resultOf(cut)
        assertTrue(bad is EvalResult.Invalid && bad.reason.contains("no material"), "reason was: $bad")
        c.set(h, 3.mm)
        assertTrue(Evaluator().resultOf(cut) is EvalResult.Ok, "lowering the height must heal the section (OP-3)")
        assertClose(areaOf(c, cut), 40.0 * 30.0, tol = 1e-9)
        assertEquals(nodesBefore + 1, c.nodesCreated, "only the area measurement was created — a height edit computes")
    }

    /**
     * A cut that falls into **several** disjoint areas is refused, and it is the *type* refusing: a
     * `Region` is one outer boundary with holes, so "the wall the door splits in two" has no single-region
     * answer. The count is a value, so the node exists either way and heals when the geometry reconnects.
     */
    @Test
    fun aCutThatFallsIntoTwoPiecesIsRefusedWithAReason() {
        val c = Construction()
        val wall = c.extrude(c.sketchOn(c.planeXY(), c.rect(0.0, 0.0, 100.0, 20.0, "w")), c.parameter("d", 10.mm))
        // a slot right through the wall, drawn as four movable corners so it can be dragged clear again
        val g0 = c.freePoint("g0", 40.mm, (-10).mm)
        val g1 = c.freePoint("g1", 40.mm, 30.mm)
        val g2 = c.freePoint("g2", 60.mm, 30.mm)
        val g3 = c.freePoint("g3", 60.mm, (-10).mm)
        val gap =
            c.extrude(
                c.sketchOn(c.planeXY(), c.region(c.loop(c.segment(g0, g1), c.segment(g1, g2), c.segment(g2, g3), c.segment(g3, g0)))),
                c.parameter("dg", 10.mm),
            )
        val split = c.subtract(wall, gap)
        val cut = c.sectionAt(split, c.parameter("h", 5.mm))

        val r = Evaluator().resultOf(cut)
        assertTrue(r is EvalResult.Invalid && r.reason.contains("2 separate areas"), "reason was: $r")

        // drag the slot off the end of the wall and it is one piece again, so the section heals
        c.set(g0, 200.mm, (-10).mm)
        c.set(g1, 200.mm, 30.mm)
        c.set(g2, 220.mm, 30.mm)
        c.set(g3, 220.mm, (-10).mm)
        assertTrue(Evaluator().resultOf(cut) is EvalResult.Ok, "a reconnected wall has a section again")
        assertClose(areaOf(c, cut), 100.0 * 20.0, tol = 1e-9)
    }

    @Test
    fun aRevolveHasNoPrismaticSection() {
        val c = Construction()
        val o = c.freePoint("axisO", 0.mm, 0.mm)
        val axis = c.direction(o, c.freePoint("axisX", 1.mm, 0.mm))
        val shaft =
            c.revolve(
                c.sketchOn(c.planeXY(), c.rect(0.0, 2.0, 40.0, 12.0, "prof")),
                o,
                axis,
                c.parameter("sweep", 360.0.deg),
            )
        val r = Evaluator().resultOf(c.sectionAt(shaft, c.parameter("h", 0.mm)))
        assertTrue(r is EvalResult.Invalid && r.reason.contains("no prismatic cross-section"), "reason was: $r")
    }

    @Test
    fun aSolidExtrudedSidewaysHasNoHorizontalSection() {
        val c = Construction()
        val plate = c.extrude(c.sketchOn(c.planeXZ(), c.rect(0.0, 0.0, 30.0, 20.0, "xz")), c.parameter("d", 5.mm))
        val r = Evaluator().resultOf(c.sectionAt(plate, c.parameter("h", 10.mm)))
        assertTrue(r is EvalResult.Invalid && r.reason.contains("not extruded vertically"), "reason was: $r")
    }

    /** A section is an ordinary region node, so extruding it again is an ordinary extrude (OP-17). */
    @Test
    fun aSectionCanBeExtrudedAgain() {
        val s = twoStoreyStack()
        val cut = section(s.c, s.stack, 15.0)
        val again = s.c.extrude(s.c.sketchOn(s.c.planeXY(), cut), s.c.parameter("d3", 4.mm))
        val ev = Evaluator()
        assertManifold(ev.solid(again).mesh, "a solid extruded from a section")
        assertClose(Geom3.volume(ev.solid(again).mesh), 50.0 * 50.0 * 4.0, tol = 1e-6)

        // and it *follows*: the section is derived, so raising the plate past the cut changes what the
        // cut is — at 15 mm the section is now the plate's own full footprint, and the new solid with it
        s.c.set(s.plateDepth, 30.mm)
        val ev2 = Evaluator()
        assertClose(areaOf(s.c, cut), 100.0 * 100.0, tol = 1e-9)
        assertClose(Geom3.volume(ev2.solid(again).mesh), 100.0 * 100.0 * 4.0, tol = 1e-6)
    }
}
