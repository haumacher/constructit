package constructit

import constructit.core.Evaluator
import constructit.dsl.Construction
import constructit.dsl.point
import constructit.dsl.segment
import constructit.geom.Vec2
import constructit.svg.Drawable
import constructit.svg.Svg
import constructit.units.mm
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Showcase: the four classical triangle centres and the Euler line.
 * Exercises perpBisector, perpendicularThrough (altitudes), angleBisector, midpoint, line∩line.
 * Invariant: circumcentre O, centroid G, orthocentre H are collinear and H = O + 3·(G − O).
 */
class TriangleCentersTest {
    private fun dist(
        p: Vec2,
        origin: Vec2,
        dir: Vec2,
    ) = abs((p - origin).cross(dir))

    @Test
    fun eulerLineAndIncenter() {
        val c = Construction()
        val A = c.freePoint("A", 0.mm, 0.mm)
        val B = c.freePoint("B", 60.mm, 0.mm)
        val C = c.freePoint("C", 15.mm, 40.mm)

        // centroid via two medians
        val G = c.select(c.intersectLL(c.lineThrough(A, c.midpoint(B, C)), c.lineThrough(B, c.midpoint(C, A))), +1)
        // circumcentre via two perpendicular bisectors
        val O = c.select(c.intersectLL(c.perpBisector(A, B), c.perpBisector(B, C)), +1)
        // orthocentre via two altitudes
        val H = c.select(c.intersectLL(c.perpendicularThrough(c.lineThrough(B, C), A), c.perpendicularThrough(c.lineThrough(A, C), B)), +1)
        // incentre via two angle bisectors
        val I = c.select(c.intersectLL(c.angleBisector(B, A, C), c.angleBisector(A, B, C)), +1)

        val ev = Evaluator()
        val g = ev.point(G)
        val o = ev.point(O)
        val h = ev.point(H)
        val i = ev.point(I)

        // known coordinates
        assertClose(g.x, 25.0)
        assertClose(g.y, 40.0 / 3.0)
        assertClose(o.x, 30.0)
        assertClose(o.y, 11.5625)
        assertClose(h.x, 15.0)
        assertClose(h.y, 16.875)

        // Euler line: collinear, and H = O + 3(G-O)
        assertClose((g - o).cross(h - o), 0.0, tol = 1e-6)
        val predictedH = o + (g - o) * 3.0
        assertClose(h.x, predictedH.x)
        assertClose(h.y, predictedH.y)

        // incentre equidistant from the three sides
        val va = ev.point(A)
        val vb = ev.point(B)
        val vc = ev.point(C)
        val dAB = dist(i, va, (vb - va).normalized())
        val dBC = dist(i, vb, (vc - vb).normalized())
        val dCA = dist(i, vc, (va - vc).normalized())
        assertClose(dAB, dBC)
        assertClose(dBC, dCA)
        assertTrue(dAB > 0.0)
    }

    @Test
    fun svgGolden() {
        val c = Construction()
        val A = c.freePoint("A", 0.mm, 0.mm)
        val B = c.freePoint("B", 60.mm, 0.mm)
        val C = c.freePoint("C", 15.mm, 40.mm)
        val O = c.select(c.intersectLL(c.perpBisector(A, B), c.perpBisector(B, C)), +1)
        val G = c.select(c.intersectLL(c.lineThrough(A, c.midpoint(B, C)), c.lineThrough(B, c.midpoint(C, A))), +1)
        val H = c.select(c.intersectLL(c.perpendicularThrough(c.lineThrough(B, C), A), c.perpendicularThrough(c.lineThrough(A, C), B)), +1)

        val svg =
            Svg.render(
                Evaluator(),
                listOf(
                    // circumcircle
                    Drawable(c.circleCP(O, A), stroke = "#dddddd"),
                    Drawable(c.segment(A, B)), Drawable(c.segment(B, C)), Drawable(c.segment(C, A)),
                    // Euler line
                    Drawable(c.lineThrough(O, H), stroke = "#d62728"),
                    Drawable(A), Drawable(B), Drawable(C),
                    Drawable(O, stroke = "#d62728"), Drawable(G, stroke = "#2ca02c"), Drawable(H, stroke = "#9467bd"),
                ),
            )
        Golden.check("triangle_centers", svg)
    }
}
