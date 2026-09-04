package constructit.dsl

import constructit.core.ScalarValue
import constructit.core.Value
import constructit.expr.Expr
import constructit.expr.ExprError
import constructit.units.Dimension
import constructit.units.Quantity

/**
 * An **expression handed to a construction as a law over a parameter** — the shape
 * [Construction.tube] and [Construction.sweep] take a variable section's size in (OP-26, the
 * variable-section sweep, session 77).
 *
 * Four things travel together and it is one argument rather than four because they are one statement: the
 * parsed [ast], the [names] it reads in the order [refs] supplies their values, and the [text] as it stands
 * under the current names of those (OP-18's naming authority — what a refusal quotes and what the step
 * stores verbatim).
 *
 * [refs] are **ordinary DAG inputs** of the node that takes this, exactly as a function curve's are
 * ([Construction.funcCurve]): retyping a parameter a taper reads re-tapers the body by the recompute every
 * other edit uses, with no input list rewired anywhere.
 */
class ExprLaw(
    val ast: Expr,
    val names: List<String>,
    val refs: List<ScalarRef>,
    val text: String,
    /** The binder the law is read in — `t`, the run parameter, 0 → 1 along the whole run. */
    val param: String = "t",
) {
    /**
     * The named values this law reads, taken from [args] starting at [from] — the environment the
     * expression is evaluated in, with the parameter itself supplied per station by the law.
     *
     * Throws [ExprError] where an input is not a number, which the taking node turns into the named
     * invalidity that heals (OP-3), never a silent zero.
     */
    fun env(
        args: List<Value>,
        from: Int,
    ): Map<String, Quantity> {
        val out = HashMap<String, Quantity>(names.size)
        for ((k, n) in names.withIndex()) {
            val q = (args.getOrNull(from + k) as? ScalarValue)?.q ?: throw ExprError("$n is not a number")
            out[n] = q
        }
        return out
    }

    /** How a refusal names this law — `r(t) = 5mm * (1 - t/2)` for a length, `scale(t) = …` otherwise. */
    fun what(dim: Dimension): String = "${if (dim == Dimension.LENGTH) "r" else "scale"}($param) = $text"
}
