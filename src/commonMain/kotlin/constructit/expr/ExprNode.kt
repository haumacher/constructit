package constructit.expr

import constructit.core.EvalResult
import constructit.core.Node
import constructit.core.ScalarValue
import constructit.core.Value
import constructit.l10n.Msg
import constructit.l10n.Msgs
import constructit.units.DimensionError
import constructit.units.Quantity

/**
 * The node an **expression binding** puts under a [constructit.core.ParameterNode]'s `boundTo`
 * (OP-7, the session-71 entry): one derived scalar, computed closed-form from the named scalars it
 * reads, every evaluation pass.
 *
 * One node rather than a compiled sub-graph of `add`/`mul` ops, for three reasons that all point the
 * same way: the **text is the record**, so the thing that is stored and the thing that computes must be
 * the same object; a rename has one place to re-stamp; and the curve half of the entry needs the AST
 * itself (to differentiate symbolically), not a graph it would have to read back.
 *
 * Binding is [constructit.core.ParameterNode.boundTo] exactly as a plain wire is — the wire *is* the
 * degenerate expression — so it mutates the parameter in place and every existing reference follows,
 * with no input list anywhere rewired.
 *
 * @param source the text as the user wrote it, never rewritten — what a `bind` step stores verbatim.
 * @param text the same expression with its references under their **current** names (OP-18's naming
 *   authority): equal to [source] until a referenced parameter is renamed, and what a refusal or an
 *   invalidity reason quotes.
 * @param names the distinct names read, in the order [inputs] supplies their values.
 */
class ExprNode(
    id: String,
    val source: String,
    val ast: Expr,
    val names: List<String>,
    override val inputs: List<Node>,
) : Node(id) {
    var text: String = source

    override fun compute(args: List<Value>): EvalResult {
        val env = HashMap<String, Quantity>(names.size)
        for ((k, n) in names.withIndex()) {
            val q = (args[k] as? ScalarValue)?.q ?: return EvalResult.Invalid(Msgs.refusalExprNotANumber(text = text, name = n))
            env[n] = q
        }
        // both error kinds end here, as OP-3 invalidity with the reason in the drawing's own words: a
        // dimension violation is not an exception that escapes, and never a silent zero
        return try {
            EvalResult.Ok(ScalarValue(ExprEval.eval(ast) { env[it] }))
        } catch (e: DimensionError) {
            EvalResult.Invalid(Msgs.refusalQualified(name = Msg.text(text), reason = Msg.text(e.message ?: "")))
        } catch (e: ExprError) {
            EvalResult.Invalid(Msgs.refusalQualified(name = Msg.text(text), reason = Msg.text(e.message ?: "")))
        }
    }
}
