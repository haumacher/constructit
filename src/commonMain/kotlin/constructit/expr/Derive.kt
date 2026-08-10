package constructit.expr

import constructit.units.Quantity
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.round
import kotlin.math.roundToInt

/**
 * Why an expression has no derivative **this vocabulary can state** — a function whose slope is a
 * different function at every side of a jump, or a power whose shape the dimension rules forbid.
 *
 * A distinct error from [ExprError] because of what the caller does with it: an unparseable text is a
 * bad text, while this is a perfectly good curve whose *tangent* nobody can name. The consumer refuses
 * the tangent-dependent construction **by name** and keeps everything position-along still exact
 * (OP-24's honesty line, the session-71 entry's curve half).
 */
class DeriveError(message: String) : RuntimeException(message)

/**
 * **Symbolic differentiation of the expression AST** (the session-71 entry, curve half): one
 * differentiator, closed form, deterministic — never a numeric difference.
 *
 * The reason it is symbolic rather than a finite difference is the same reason the scalar half stores the
 * text rather than a compiled graph: a tangent is a *construction input* (a normal to build on, a fillet's
 * direction, a swept section's frame), and a construction anchored on a difference quotient would be
 * anchored on a step size nobody chose. So where a derivative is statable it is exact, and where it is not
 * the construction **refuses by name** rather than quietly differencing (OP-15's *exact paths never degrade
 * silently*, one layer down).
 *
 * **The refusal set, decided rather than discovered**: `min`, `max`, `mod`, `floor`, `ceil`, `round` and
 * `sign`. Each is continuous-almost-everywhere with a derivative that exists off a set of jumps and is a
 * *different function* on each side, so the honest answer is not "0 almost everywhere" — it is that this
 * curve's tangent has no name. `abs` is deliberately **in** the statable set, written as `u·u'/|u|`: that is
 * exact wherever `abs` is differentiable and divides by zero exactly where it is not, so the one point that
 * has no tangent reports itself as the ordinary invalidity that heals (OP-3) instead of costing the whole
 * function its derivative.
 *
 * **The dimensions come out right, and that took two deliberate moves.**
 *
 * - The parameter is dimensionless, so `d/dt` keeps its operand's dimension. A derivative that is
 *   *identically zero* is the exception — it has no dimension to carry — which is why every zero is folded
 *   away here ([times], [plus]) and the one that survives is read as "zero in whatever unit is wanted" by
 *   [constructit.geom.FuncCurves].
 * - `sin`/`cos`/`tan` take an angle **or a plain number read as radians**, so their derivative must strip
 *   the radian: `d sin(u) = cos(u)·num(u')`, where [ExprEval]'s `num` reads an angle as its plain number of
 *   radians and leaves a plain number alone. Without it, `sin` of an *angle* would hand back a slope of
 *   dimension angle, and adding it to a plain term would be the `DimensionError` a perfectly good curve
 *   does not deserve. The inverse functions carry the radian the other way, as a literal `1rad` factor.
 */
object Derive {
    /** The dimensionless zero every fold collapses onto — see the class note on what it means. */
    private val ZERO = Expr.Lit(Quantity.number(0.0))

    private val ONE = Expr.Lit(Quantity.number(1.0))

    private fun isZero(e: Expr): Boolean = e is Expr.Lit && e.q.base == 0.0 && !e.hadUnit

    private fun isOne(e: Expr): Boolean = e is Expr.Lit && e.q.base == 1.0 && !e.hadUnit

    /**
     * The derivative of [e] with respect to the variable named [v] — every other name in it is a
     * **constant of the drawing** (a named scalar), which is exactly what makes this the right derivative:
     * a function curve's parameter is its only variable, and its scalars move the curve rather than run
     * along it.
     *
     * @throws DeriveError when the vocabulary cannot state it, naming the function that stopped it.
     */
    fun d(
        e: Expr,
        v: String,
    ): Expr =
        when (e) {
            is Expr.Lit -> ZERO
            is Expr.Ref -> if (e.name == v) ONE else ZERO
            is Expr.Apply -> apply(e, v)
        }

    /** Whether [e] mentions [v] at all — what decides which of the power rules applies. */
    fun mentions(
        e: Expr,
        v: String,
    ): Boolean =
        when (e) {
            is Expr.Lit -> false
            is Expr.Ref -> e.name == v
            is Expr.Apply -> e.args.any { mentions(it, v) }
        }

    private fun apply(
        e: Expr.Apply,
        v: String,
    ): Expr {
        val a = e.args.getOrNull(0)
        val b = e.args.getOrNull(1)
        val da = a?.let { d(it, v) }
        val db = b?.let { d(it, v) }
        return when (e.op) {
            "+" -> plus(da!!, db!!)
            "-" -> minus(da!!, db!!)
            "neg" -> neg(da!!)
            "*" -> plus(times(da!!, b!!), times(a!!, db!!))
            "/" -> div(minus(times(da!!, b!!), times(a!!, db!!)), times(b, b))
            "^", "pow" -> power(a!!, b!!, da!!, db!!, v)
            "sqrt" -> div(da!!, times(Expr.Lit(Quantity.number(2.0)), call("sqrt", a!!)))
            "cbrt" -> div(da!!, times(Expr.Lit(Quantity.number(3.0)), pow(call("cbrt", a!!), 2)))
            // exact wherever `abs` has a derivative at all, and a division by zero exactly where it has
            // none — the one point reports itself, instead of the whole function losing its tangent
            "abs" -> div(times(a!!, da!!), call("abs", a))
            "hypot" -> div(plus(times(a!!, da!!), times(b!!, db!!)), call("hypot", a, b))
            "sin" -> times(call("cos", a!!), num(da!!))
            "cos" -> neg(times(call("sin", a!!), num(da!!)))
            "tan" -> div(num(da!!), pow(call("cos", a!!), 2))
            "asin" -> rad(div(da!!, call("sqrt", minus(ONE, pow(a!!, 2)))))
            "acos" -> neg(rad(div(da!!, call("sqrt", minus(ONE, pow(a!!, 2))))))
            "atan" -> rad(div(da!!, plus(ONE, pow(a!!, 2))))
            "atan2" -> rad(div(minus(times(b!!, da!!), times(a!!, db!!)), plus(pow(a, 2), pow(b, 2))))
            "exp" -> times(call("exp", a!!), da!!)
            "log" -> div(da!!, a!!)
            "log10" -> div(da!!, times(a!!, Expr.Lit(Quantity.number(ln(10.0)))))
            else -> throw DeriveError(refusal(e.op))
        }
    }

    /** What a function with no statable derivative says, in the words the refusal is quoted in. */
    fun refusal(op: String): String =
        "'$op' has no derivative this drawing can state — it steps rather than slopes, so a tangent, a normal " +
            "or anything built on one is refused here rather than guessed by differencing"

    /**
     * `d(a^b)`, in the three readings the dimension table leaves room for.
     *
     * The **literal integer** exponent is the one that matters, because it is the only one a *dimensioned*
     * base is allowed to have (`r^2` is an area, [ExprEval]'s own rule): its derivative writes `k−1` out as
     * a literal too, so the result stays inside that same rule and `r^3`'s slope is an area rather than a
     * dimension error. An exponent that is merely free of the parameter takes the same shape one step
     * looser, and one that moves with the parameter needs the base's logarithm, so it is dimensionless by
     * construction.
     */
    private fun power(
        base: Expr,
        exponent: Expr,
        dBase: Expr,
        dExponent: Expr,
        v: String,
    ): Expr {
        if (!mentions(exponent, v)) {
            if (exponent is Expr.Lit && !exponent.hadUnit && exponent.q.base == round(exponent.q.base) && abs(exponent.q.base) <= 12) {
                val k = exponent.q.base.roundToInt()
                if (k == 0) return ZERO
                return times(Expr.Lit(Quantity.number(k.toDouble())), times(pow(base, k - 1), dBase))
            }
            return times(exponent, times(Expr.Apply("^", listOf(base, minus(exponent, ONE))), dBase))
        }
        // `a^b` with a moving exponent is `exp(b·log a)`, so the base has to be a plain number anyway —
        // which is what the dimension table already says about every non-literal exponent
        return times(
            Expr.Apply("^", listOf(base, exponent)),
            plus(times(dExponent, call("log", base)), div(times(exponent, dBase), base)),
        )
    }

    // ---- the folds: what keeps a zero from becoming a dimension error ----

    private fun plus(
        a: Expr,
        b: Expr,
    ): Expr =
        when {
            isZero(a) -> b
            isZero(b) -> a
            else -> Expr.Apply("+", listOf(a, b))
        }

    private fun minus(
        a: Expr,
        b: Expr,
    ): Expr =
        when {
            isZero(b) -> a
            isZero(a) -> neg(b)
            else -> Expr.Apply("-", listOf(a, b))
        }

    private fun neg(a: Expr): Expr = if (isZero(a)) ZERO else Expr.Apply("neg", listOf(a))

    private fun times(
        a: Expr,
        b: Expr,
    ): Expr =
        when {
            isZero(a) || isZero(b) -> ZERO
            isOne(a) -> b
            isOne(b) -> a
            else -> Expr.Apply("*", listOf(a, b))
        }

    private fun div(
        a: Expr,
        b: Expr,
    ): Expr =
        when {
            isZero(a) -> ZERO
            isOne(b) -> a
            else -> Expr.Apply("/", listOf(a, b))
        }

    private fun pow(
        a: Expr,
        k: Int,
    ): Expr = if (k == 1) a else Expr.Apply("^", listOf(a, Expr.Lit(Quantity.number(k.toDouble()))))

    private fun call(
        op: String,
        vararg args: Expr,
    ): Expr = Expr.Apply(op, args.toList())

    /** An angle read as its plain number of radians — see the class note. A plain number is already one. */
    private fun num(e: Expr): Expr =
        when {
            isZero(e) -> ZERO
            e is Expr.Lit && !e.hadUnit -> e
            else -> Expr.Apply(ExprEval.NUM, listOf(e))
        }

    /** A plain number read as radians: the inverse trigonometric functions' own dimension, carried out. */
    private fun rad(e: Expr): Expr = if (isZero(e)) ZERO else times(e, Expr.Lit(Quantity.rad(1.0), hadUnit = true))
}
