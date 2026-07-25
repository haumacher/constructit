package constructit

import constructit.core.Evaluator
import constructit.dsl.Construction
import constructit.dsl.circle
import constructit.dsl.point
import constructit.dsl.scalar
import constructit.units.deg
import constructit.units.mm
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/** Showcase constructions with clean invariants: Thales, tangents-from-point, golden-ratio pentagon. */
class ConstructionsTest {
    @Test
    fun thalesRightAngle() {
        val c = Construction()
        val circle = c.circleCR(c.freePoint("O", 0.mm, 0.mm), c.parameter("r", 25.mm))
        val a = c.pointOnCircle(circle, c.const(180.0.deg)) // (-25, 0)
        val b = c.pointOnCircle(circle, c.const(0.0.deg)) // ( 25, 0)  -> diameter
        val p = c.pointOnCircle(circle, c.const(50.0.deg)) // any other point
        // inscribed angle subtending a diameter is a right angle
        assertClose(Evaluator().scalar(c.measureAngle(a, p, b)).deg, 90.0)
    }

    @Test
    fun tangentsFromExternalPoint() {
        val c = Construction()
        val center = c.freePoint("O", 0.mm, 0.mm)
        val circle = c.circleCR(center, c.parameter("r", 20.mm))
        val p = c.freePoint("P", 50.mm, 0.mm)
        val ts = c.tangentPointsFromPoint(p, circle)
        val t1 = c.select(ts, +1)
        val t2 = c.select(ts, -1)

        val ev = Evaluator()
        val vo = ev.point(center)
        val vp = ev.point(p)
        val vt1 = ev.point(t1)
        val vt2 = ev.point(t2)

        // tangent point lies on the circle
        assertClose((vt1 - vo).length(), 20.0)
        // radius is perpendicular to the tangent line (PT · OT = 0)
        assertClose((vp - vt1).dot(vo - vt1), 0.0, tol = 1e-6)
        // equal tangent lengths = sqrt(50^2 - 20^2)
        assertClose((vp - vt1).length(), sqrt(2500.0 - 400.0))
        assertClose((vp - vt1).length(), (vp - vt2).length())
    }

    @Test
    fun regularPentagonGoldenRatio() {
        val c = Construction()
        val circle = c.circleCR(c.freePoint("O", 0.mm, 0.mm), c.parameter("r", 50.mm))
        val pts = (0 until 5).map { c.pointOnCircle(circle, c.const((90.0 + it * 72.0).deg)) }
        val ev = Evaluator()
        val v = pts.map { ev.point(it) }
        val side = (v[0] - v[1]).length()
        val diagonal = (v[0] - v[2]).length()
        // diagonal / side of a regular pentagon is the golden ratio
        assertClose(diagonal / side, (1.0 + sqrt(5.0)) / 2.0, tol = 1e-6)
        assertTrue(side > 0.0)
    }
}
