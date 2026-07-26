package constructit

import constructit.core.Evaluator
import constructit.dsl.Construction
import constructit.dsl.circle
import constructit.dsl.isValid
import constructit.dsl.point
import constructit.dsl.resultOf
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
     * The dimension system's one **explicit** conversion (OP-7): a plain number read as radians, and back.
     *
     * `sin`/`cos`/`tan`/`atan2` cross from angle to number implicitly, and nothing crossed back — which
     * leaves any closed-form formula that *mixes* the two unstateable (the involute function `tan β − β`,
     * which `dsl.spurGear` is built from, is the canonical case). It is a conversion, so it is explicit and
     * it round-trips; anything else in either direction is a dimension error, hence an invalid node.
     */
    @Test
    fun radiansAndItsInverseConvertBetweenAnAngleAndANumber() {
        val c = Construction()
        val quarter = c.parameter("q", 90.deg)
        val asNumber = c.radianMeasure(quarter)
        assertClose(Evaluator().scalar(asNumber).value, kotlin.math.PI / 2)
        assertClose(Evaluator().scalar(c.radians(asNumber)).deg, 90.0, msg = "the round trip is the identity")

        // the involute function of 20 degrees: tan(a) - a, only writable because the two meet
        val a = c.parameter("a", 20.deg)
        val inv = c.sub(c.radians(c.tanS(a)), a)
        assertClose(Evaluator().scalar(inv).base, kotlin.math.tan(20.0 * kotlin.math.PI / 180) - 20.0 * kotlin.math.PI / 180)

        // each direction refuses the other's argument (OP-3 catches the DimensionError)
        assertFalse(Evaluator().isValid(c.radians(c.parameter("ang", 45.deg))), "an angle is not a number")
        assertFalse(Evaluator().isValid(c.radianMeasure(c.parameter("len", 5.mm))), "a length is not an angle")
    }

    /**
     * A **precondition as a node** (OP-3): [Construction.requirePositive] passes its value through, or makes
     * the chain that uses it invalid *with a reason* — and heals. This is how a macro states its own domain
     * (see `dsl.spurGear`) instead of drawing something folded through itself.
     */
    @Test
    fun requirePositiveStatesADomainAndHeals() {
        val c = Construction()
        val gap = c.parameter("gap", 5.mm)
        val checked = c.requirePositive(gap, "the gap must be positive")
        val circle = c.circleCR(c.freePoint("O", 0.mm, 0.mm), checked)
        assertClose(Evaluator().circle(circle).radius, 5.0)

        c.set(gap, (-1).mm)
        val bad = Evaluator().resultOf(checked)
        assertTrue(bad is constructit.core.EvalResult.Invalid && bad.reason == "the gap must be positive", "reason was: $bad")
        assertFalse(Evaluator().isValid(circle), "the dependent geometry goes with it")

        c.set(gap, 2.mm)
        assertClose(Evaluator().circle(circle).radius, 2.0, msg = "and it heals")
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
