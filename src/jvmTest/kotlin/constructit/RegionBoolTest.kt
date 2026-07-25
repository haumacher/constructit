package constructit

import constructit.geom.BoolOp
import constructit.geom.RegionBool
import constructit.geom.Vec2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The 2D region boolean kernel (OP-22), on its own — the degenerate classes are the whole point, so
 * they are named one per test rather than hidden inside a solid.
 *
 * Areas are asserted **exactly** (1e-9 mm²): every input here is polygonal and axis-aligned, so the
 * arrangement's intersection points are exact in floating point and the result's area is a finite sum of
 * them. Nothing about the kernel is approximate except the tessellation that happens *before* it.
 */
class RegionBoolTest {
    /** An axis-aligned rectangle, counter-clockwise — a material ring under OP-14's convention. */
    private fun rect(
        x0: Double,
        y0: Double,
        x1: Double,
        y1: Double,
    ): List<Vec2> = listOf(Vec2(x0, y0), Vec2(x1, y0), Vec2(x1, y1), Vec2(x0, y1))

    private fun combine(
        a: List<List<Vec2>>,
        b: List<List<Vec2>>,
        kind: BoolOp,
    ): List<List<Vec2>> {
        val (rings, why) = RegionBool.combine(a, b, kind)
        assertTrue(rings != null, "the kernel refused $kind: $why")
        return rings!!
    }

    private fun areaOf(rings: List<List<Vec2>>): Double = RegionBool.area(rings)

    // ---- the plain overlap, all three operations ----

    @Test
    fun overlappingSquaresUniteIntersectAndSubtract() {
        val a = listOf(rect(0.0, 0.0, 10.0, 10.0))
        val b = listOf(rect(5.0, 5.0, 15.0, 15.0))

        val union = combine(a, b, BoolOp.UNION)
        assertEquals(1, union.size, "the union is one connected area")
        assertClose(areaOf(union), 175.0, tol = 1e-9)

        val meet = combine(a, b, BoolOp.INTERSECT)
        assertEquals(1, meet.size)
        assertClose(areaOf(meet), 25.0, tol = 1e-9)

        val diff = combine(a, b, BoolOp.SUBTRACT)
        assertEquals(1, diff.size)
        assertClose(areaOf(diff), 75.0, tol = 1e-9)

        // and the other way round, which is not the same operation
        assertClose(areaOf(combine(b, a, BoolOp.SUBTRACT)), 75.0, tol = 1e-9)
    }

    /** Subtracting an area from itself leaves nothing — an empty list, not a degenerate loop. */
    @Test
    fun aSquareMinusItselfIsEmpty() {
        val a = listOf(rect(0.0, 0.0, 10.0, 10.0))
        assertTrue(combine(a, a, BoolOp.SUBTRACT).isEmpty(), "nothing is left, and it is stated as nothing")
        assertClose(areaOf(combine(a, a, BoolOp.UNION)), 100.0, tol = 1e-9)
        assertClose(areaOf(combine(a, a, BoolOp.INTERSECT)), 100.0, tol = 1e-9)
    }

    /**
     * **Shared edge.** Two squares side by side: their union is one region and the shared edge is simply
     * gone — no zero-width slit reaching into the material, which is the classic failure of a
     * clip-and-stitch boolean.
     */
    @Test
    fun squaresSharingAnEdgeUniteIntoOneRegionWithNoSlit() {
        val a = listOf(rect(0.0, 0.0, 10.0, 10.0))
        val b = listOf(rect(10.0, 0.0, 20.0, 10.0))
        val union = combine(a, b, BoolOp.UNION)
        assertEquals(1, union.size, "one region, not two touching ones")
        assertClose(areaOf(union), 200.0, tol = 1e-9)
        // the boundary runs round the 20x10 rectangle, through the two corners the shared edge left
        // behind (they are kept: a corner of one polygon may be needed by a neighbour, see OP-22)
        assertEquals(6, union[0].size)
        assertTrue(union[0].none { p -> p.y > 0.0 && p.y < 10.0 }, "no vertex reaches into the material: ${union[0]}")

        // the intersection of two areas that only share an edge is empty — an edge has no area
        assertTrue(combine(a, b, BoolOp.INTERSECT).isEmpty())
        // and taking one from the other leaves the other whole
        assertClose(areaOf(combine(a, b, BoolOp.SUBTRACT)), 100.0, tol = 1e-9)
    }

    /** **A hole made by subtraction**: a donut, as an outer boundary plus a clockwise inner one. */
    @Test
    fun subtractingAnInteriorSquareMakesAHole() {
        val a = listOf(rect(0.0, 0.0, 20.0, 20.0))
        val b = listOf(rect(5.0, 5.0, 15.0, 15.0))
        val rings = combine(a, b, BoolOp.SUBTRACT)
        assertEquals(2, rings.size, "an outer boundary and a hole")
        assertEquals(1, rings.count { RegionBool.ringArea(it) > 0.0 })
        assertEquals(1, rings.count { RegionBool.ringArea(it) < 0.0 }, "the hole must run the other way")
        assertClose(areaOf(rings), 300.0, tol = 1e-9)

        val (regions, why) = RegionBool.regionsOf(rings)
        assertTrue(regions != null, "nesting failed: $why")
        assertEquals(1, regions!!.size)
        assertEquals(1, regions[0].holes.size, "the hole belongs to the boundary around it")
    }

    /** **A hole that reaches the boundary** is not a hole at all: the result is a C, with one loop. */
    @Test
    fun aCutReachingTheBoundaryLeavesNoHole() {
        val a = listOf(rect(0.0, 0.0, 20.0, 20.0))
        val b = listOf(rect(5.0, 5.0, 20.0, 15.0)) // its right edge lies *on* the outer boundary
        val rings = combine(a, b, BoolOp.SUBTRACT)
        assertEquals(1, rings.size, "a notch, not a hole")
        assertClose(areaOf(rings), 400.0 - 150.0, tol = 1e-9)
        val (regions, _) = RegionBool.regionsOf(rings)
        assertEquals(0, regions!![0].holes.size)
    }

    /** **A disjoint result**: one cut through a bar leaves two separate regions, not one bent loop. */
    @Test
    fun aCutRightThroughSplitsTheAreaInTwo() {
        val bar = listOf(rect(0.0, 0.0, 30.0, 10.0))
        val cut = listOf(rect(10.0, -5.0, 20.0, 15.0))
        val rings = combine(bar, cut, BoolOp.SUBTRACT)
        assertEquals(2, rings.size, "two pieces")
        assertTrue(rings.all { RegionBool.ringArea(it) > 0.0 }, "both are material, neither is a hole")
        assertClose(areaOf(rings), 200.0, tol = 1e-9)
        val (regions, _) = RegionBool.regionsOf(rings)
        assertEquals(2, regions!!.size)
        assertTrue(regions.all { it.holes.isEmpty() })
    }

    /**
     * **A touching corner.** Two squares meeting at a single point: the union stays *two* loops rather
     * than one figure-eight through the pinch, because a self-touching loop is what makes a triangulator
     * downstream produce a leaky cap (OP-22's chaining rule).
     */
    @Test
    fun squaresTouchingAtACornerUniteIntoTwoLoops() {
        val a = listOf(rect(0.0, 0.0, 10.0, 10.0))
        val b = listOf(rect(10.0, 10.0, 20.0, 20.0))
        val rings = combine(a, b, BoolOp.UNION)
        assertEquals(2, rings.size, "two loops meeting at a point, not one loop through it")
        assertClose(areaOf(rings), 200.0, tol = 1e-9)
        assertTrue(rings.all { RegionBool.ringArea(it) > 0.0 })
        assertTrue(combine(a, b, BoolOp.INTERSECT).isEmpty(), "a point has no area")
    }

    /** A hole in the *operand* is respected: subtracting a ring from a square leaves its middle. */
    @Test
    fun anOperandWithAHoleIsReadByTheWindingRule() {
        val plate = listOf(rect(0.0, 0.0, 20.0, 20.0))
        // a ring: outer 4..16 with a 8..12 hole (clockwise)
        val ring = listOf(rect(4.0, 4.0, 16.0, 16.0), rect(8.0, 8.0, 12.0, 12.0).reversed())
        assertClose(areaOf(ring), 144.0 - 16.0, tol = 1e-9)

        val meet = combine(plate, ring, BoolOp.INTERSECT)
        assertClose(areaOf(meet), 128.0, tol = 1e-9, msg = "the ring's hole is not material")
        val diff = combine(plate, ring, BoolOp.SUBTRACT)
        assertClose(areaOf(diff), 400.0 - 128.0, tol = 1e-9)
        val (regions, _) = RegionBool.regionsOf(diff)
        // the frame around the ring, plus the island the ring's own hole left standing
        assertEquals(2, regions!!.size)
        assertEquals(1, regions.count { it.holes.isNotEmpty() })
    }

    /** Two passes, one answer: no hash-order iteration anywhere, so the result is byte-identical. */
    @Test
    fun theKernelIsDeterministic() {
        val a = listOf(rect(0.0, 0.0, 10.0, 10.0))
        val b = listOf(rect(5.0, 5.0, 15.0, 15.0))
        for (kind in BoolOp.entries) {
            assertEquals(combine(a, b, kind), combine(a, b, kind), "$kind must be a pure function of its inputs")
        }
    }

    /** A tessellated circle inside a square: the exactness claim, against the polygon's own area. */
    @Test
    fun aTessellatedCircleSubtractsExactly() {
        val plate = listOf(rect(-20.0, -20.0, 20.0, 20.0))
        val steps = 64
        val hole =
            (0 until steps).map {
                val ang = 2.0 * kotlin.math.PI * it / steps
                Vec2(6.0 * kotlin.math.cos(ang), 6.0 * kotlin.math.sin(ang))
            }
        val exact = RegionBool.ringArea(hole)
        val rings = combine(plate, listOf(hole), BoolOp.SUBTRACT)
        assertEquals(2, rings.size)
        assertClose(areaOf(rings), 1600.0 - exact, tol = 1e-9, msg = "the polygon's area, not the circle's")
        // ...and the polygon's area is within the tessellation tolerance of the circle's
        // 64 chords on r = 6 fall short by the sagitta, quadratically: ~1.8e-3 of the area
        assertTrue(kotlin.math.abs(exact - kotlin.math.PI * 36.0) / (kotlin.math.PI * 36.0) < 3e-3)
    }
}
