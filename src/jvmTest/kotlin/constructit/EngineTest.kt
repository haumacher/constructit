package constructit

import constructit.core.Evaluator
import constructit.dsl.Construction
import constructit.dsl.isValid
import constructit.dsl.point
import constructit.dsl.scalar
import constructit.units.cm
import constructit.units.deg
import constructit.units.mm
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Core engine behaviours: units/dimensions (OP-7), measurements (OP-4), invalid propagation (OP-3). */
class EngineTest {
    @Test
    fun unitConversion() {
        val c = Construction()
        val a = c.parameter("a", 2.0.cm) // 20 mm
        assertClose(Evaluator().scalar(a).mm, 20.0)
    }

    @Test
    fun dimensionalArithmeticCombinesLengths() {
        val c = Construction()
        val sum = c.add(c.parameter("a", 30.mm), c.parameter("b", 12.mm))
        assertClose(Evaluator().scalar(sum).mm, 42.0)
    }

    @Test
    fun dimensionMismatchIsInvalid() {
        val c = Construction()
        // adding a length and an angle is a dimension error -> node invalid (OP-3/OP-7)
        val bad = c.add(c.parameter("len", 30.mm), c.parameter("ang", 45.deg))
        assertFalse(Evaluator().isValid(bad))
    }

    @Test
    fun measurementIsForwardDataflow() {
        val c = Construction()
        val p1 = c.freePoint("P1", 0.mm, 0.mm)
        val p2 = c.freePoint("P2", 3.mm, 4.mm)
        val d = c.measureDistance(p1, p2)
        assertClose(Evaluator().scalar(d).mm, 5.0)
        // a measured scalar may drive a new independent construction (OP-4)
        val doubled = c.scale(d, 2.0)
        assertClose(Evaluator().scalar(doubled).mm, 10.0)
    }

    @Test
    fun invalidPropagatesTransitively() {
        val c = Construction()
        val zeroR = c.parameter("r", 0.mm) // non-positive radius -> invalid circle
        val circle = c.circleCR(c.freePoint("C", 0.mm, 0.mm), zeroR)
        val pair = c.intersectCC(circle, c.circleCR(c.freePoint("D", 10.mm, 0.mm), c.parameter("r2", 5.mm)))
        val pt = c.select(pair, +1)
        assertFalse(Evaluator().isValid(circle))
        assertFalse(Evaluator().isValid(pt), "invalidity must propagate transitively (OP-3)")
    }

    /**
     * Regression: evaluation memoizes by node id (OP-5), so a repeated name hint used to alias two
     * nodes — a parameter named like an existing free point evaluated to the point's cached value
     * (a PointValue where a ScalarValue was needed). Ids must be unique no matter what callers name
     * things.
     */
    @Test
    fun aRepeatedNameHintDoesNotAliasNodes() {
        val c = Construction()
        val p = c.freePoint("dp", 1.mm, 2.mm)
        val s = c.parameter("dp", 20.mm)
        assertTrue(p.node.id != s.node.id, "same hint, distinct ids")
        assertClose(Evaluator().scalar(s).mm, 20.0)
        assertClose(Evaluator().point(p).x, 1.0)
    }
}
