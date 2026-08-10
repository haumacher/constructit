package constructit.expr

import constructit.units.Dimension
import constructit.units.DimensionError
import constructit.units.Quantity
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Why a text is not an expression — a malformed one, an unknown name or function, an arithmetic domain
 * a value has left (a square root of a negative, a division by zero).
 *
 * Distinct from [DimensionError] only in *what* went wrong: both end the same way, as the named node
 * invalidity that heals (OP-3), never as an exception that escapes and never as a silent zero.
 */
class ExprError(message: String) : RuntimeException(message)

/**
 * The AST of the expression language (OP-7, the session-71 entry): **`boundTo` generalized to a pure
 * function** of named scalars. One direction only — `a = b + c` *defines* `a` — so this is an ordinary
 * set of DAG edges and the no-solver stance is untouched.
 *
 * Three node kinds and no more, because every operator *is* a function: `+`, `-`, `*`, `/`, `^` and the
 * unary minus (`neg`) are [Apply] nodes under those names, exactly like `sqrt` or `atan2`. That is what
 * lets the dimension rules live in one table ([ExprEval.rule]) rather than one per syntax form, and it is
 * what the curve half of the entry will differentiate over later.
 */
sealed interface Expr {
    /**
     * A dimensioned literal, **canonicalized at parse** (OP-7: mm and rad are the base units, `15°`
     * becomes 0.2618 rad here and never later). [hadUnit] records whether the user wrote one, which is
     * the difference between *the number 5* and *5 mm* when a bare literal is offered as a plain value.
     */
    class Lit(val q: Quantity, val hadUnit: Boolean = false) : Expr

    /**
     * A reference to a named scalar, with the half-open span `[start, end)` it occupies in the source
     * text. The span is what lets a **rename re-stamp** the stored text in place (OP-23's move, and the
     * parameter-rename pattern of OP-7) instead of orphaning the expression.
     */
    class Ref(val name: String, val start: Int, val end: Int) : Expr

    /** An operator or function applied to its arguments — see the class note for why these are one kind. */
    class Apply(val op: String, val args: List<Expr>) : Expr
}

/** Every reference in [this], in source order — what a re-stamp rewrites and what a binding resolves. */
fun Expr.refs(): List<Expr.Ref> {
    val out = ArrayList<Expr.Ref>()

    fun walk(e: Expr) {
        when (e) {
            is Expr.Lit -> {}
            is Expr.Ref -> out.add(e)
            is Expr.Apply -> e.args.forEach { walk(it) }
        }
    }
    walk(this)
    return out.sortedBy { it.start }
}

/** The distinct names [this] reads, in first-appearance order — the inputs an expression node takes. */
fun Expr.refNames(): List<String> = refs().map { it.name }.distinct()

/**
 * The **vocabulary**, and its arity. The user's phrase names `java.lang.Math`; the implementation is
 * `kotlin.math` because `commonMain` stays platform-free, and the names are Math's own so that what the
 * user knows is what he can type.
 */
private val ARITY =
    mapOf(
        "abs" to 1, "min" to 2, "max" to 2, "sqrt" to 1, "cbrt" to 1, "hypot" to 2,
        "sin" to 1, "cos" to 1, "tan" to 1, "asin" to 1, "acos" to 1, "atan" to 1, "atan2" to 2,
        "pow" to 2, "exp" to 1, "log" to 1, "log10" to 1,
        "floor" to 1, "ceil" to 1, "round" to 1, "sign" to 1, "mod" to 2,
    )

/** The function names, for a refusal that can say what *was* expected. */
val EXPR_FUNCTIONS: List<String> = ARITY.keys.sorted()

/** The unit suffixes a literal may carry, canonicalized to mm and rad on the spot (OP-7). */
private val UNITS: Map<String, (Double) -> Quantity> =
    mapOf(
        "mm" to { v: Double -> Quantity.mm(v) },
        "cm" to { v: Double -> Quantity.cm(v) },
        "m" to { v: Double -> Quantity.mm(v * 1000.0) },
        "deg" to { v: Double -> Quantity.deg(v) },
        "°" to { v: Double -> Quantity.deg(v) },
        "rad" to { v: Double -> Quantity.rad(v) },
    )

/**
 * The named constants. Deliberately no lowercase `e`: it is far too plausible a parameter name.
 *
 * **They are a fallback, not a reserved word** — see [ExprEval.eval]: a name resolves against the
 * drawing's own scalars first and lands here only when nothing carries it. That is the same rule the
 * function/reference split follows, and it exists for the same reason ([ExprParser]).
 */
private val CONSTANTS =
    mapOf(
        "PI" to PI,
        "pi" to PI,
        "E" to kotlin.math.E,
    )

/** The constant names — what a binding may leave unresolved because the evaluator knows them. */
val EXPR_CONSTANTS: Set<String> = CONSTANTS.keys

/**
 * Recursive-descent parser for the expression language — deterministic, and the **stored text is the
 * record**: this is what a `bind` step is parsed back through on load, so the same text is always the
 * same expression.
 *
 * Grammar (lowest to highest): `+ -`, then `* /`, then unary `-`, then `^` (right-associative), then
 * atoms — a literal with an optional unit, a name, a call, or a parenthesised expression.
 * Positions in refusals are **1-based**, because a user counts characters from one.
 *
 * **There are no reserved words, and that is load-bearing.** A word is a *function* when a `(` follows it
 * and a *reference* otherwise; a word that names nothing in the drawing falls back to a constant. So `sin`
 * is a perfectly ordinary parameter name, `sin/2` reads it, `sin(90°)` calls the function, and the two may
 * stand in one text. The reason is the frozen-literal rule (OP-18): a keyword-style parser would make the
 * *vocabulary* part of what a stored file means, so adding `sec` to it next session would turn every
 * existing file with a parameter named `sec` into a file this build cannot open. A name a rename allows
 * must stay a name a load accepts, for ever.
 */
class ExprParser private constructor(private val src: String) {
    private var i = 0

    companion object {
        fun parse(text: String): Expr {
            val p = ExprParser(text)
            if (text.isBlank()) throw ExprError("an expression is expected, and this is blank")
            val e = p.expr()
            p.skipWs()
            if (p.i < text.length) throw ExprError("unexpected '${text[p.i]}' at position ${p.i + 1}")
            return e
        }
    }

    private fun skipWs() {
        while (i < src.length && src[i].isWhitespace()) i++
    }

    private fun peek(): Char? {
        skipWs()
        return if (i < src.length) src[i] else null
    }

    private fun expr(): Expr {
        var left = term()
        while (true) {
            val c = peek() ?: return left
            if (c != '+' && c != '-') return left
            i++
            left = Expr.Apply(c.toString(), listOf(left, term()))
        }
    }

    private fun term(): Expr {
        var left = unary()
        while (true) {
            val c = peek() ?: return left
            if (c != '*' && c != '/') return left
            i++
            left = Expr.Apply(c.toString(), listOf(left, unary()))
        }
    }

    private fun unary(): Expr {
        val c = peek() ?: throw ExprError("a value is expected at position ${i + 1}, and the expression ends there")
        if (c == '-') {
            i++
            return Expr.Apply("neg", listOf(unary()))
        }
        if (c == '+') {
            i++
            return unary()
        }
        return power()
    }

    private fun power(): Expr {
        val base = atom()
        if (peek() == '^') {
            i++
            return Expr.Apply("^", listOf(base, unary()))
        }
        return base
    }

    private fun atom(): Expr {
        val c = peek() ?: throw ExprError("a value is expected at position ${i + 1}, and the expression ends there")
        if (c == '(') {
            i++
            val e = expr()
            if (peek() != ')') throw ExprError("')' is expected at position ${i + 1}")
            i++
            return e
        }
        if (c.isDigit() || c == '.') return number()
        if (c.isLetter() || c == '_') return name()
        throw ExprError("a number, a name or '(' is expected at position ${i + 1}, and '$c' is there")
    }

    private fun number(): Expr {
        val start = i
        while (i < src.length && (src[i].isDigit() || src[i] == '.')) i++
        val digits = src.substring(start, i)
        val v = digits.toDoubleOrNull() ?: throw ExprError("'$digits' at position ${start + 1} is not a number")
        // a unit binds to the digits it touches, so `2 * pi` is a product and `2mm` is a length
        val us = i
        while (i < src.length && (src[i].isLetter() || src[i] == '°')) i++
        if (us == i) return Expr.Lit(Quantity.number(v))
        val unit = src.substring(us, i)
        val make =
            UNITS[unit]
                ?: throw ExprError("unknown unit '$unit' at position ${us + 1} — mm, cm, m, deg, ° and rad are the units")
        return Expr.Lit(make(v), hadUnit = true)
    }

    private fun name(): Expr {
        val start = i
        // one word of letters, digits and underscores: a name a hyphen splits would be indistinguishable
        // from a subtraction, so such a name simply cannot be referenced (the binding says so, by name)
        while (i < src.length && (src[i].isLetterOrDigit() || src[i] == '_')) i++
        val word = src.substring(start, i)
        // the reference's span ends **at the word**, taken before the look-ahead below: [peek] skips
        // whitespace, so reading it first would stretch the span over the space after the name and a rename
        // would then eat that space out of the stored text (`r * 2` re-stamping to `module* 2`). The text is
        // the record, so a re-stamp must rewrite exactly the name and not one character more.
        val end = i
        // **the '(' is what makes a word a function** — see the class note. Nothing else does, so no name
        // is spent and no future vocabulary addition can change what a stored file means.
        if (peek() == '(') {
            i++
            val args = ArrayList<Expr>()
            if (peek() == ')') {
                i++
            } else {
                while (true) {
                    args.add(expr())
                    val c = peek() ?: throw ExprError("')' is expected at position ${i + 1}")
                    if (c == ',') {
                        i++
                        continue
                    }
                    if (c == ')') {
                        i++
                        break
                    }
                    throw ExprError("',' or ')' is expected at position ${i + 1}, and '$c' is there")
                }
            }
            val arity =
                ARITY[word]
                    ?: throw ExprError("unknown function '$word' at position ${start + 1} — ${EXPR_FUNCTIONS.joinToString(", ")} are the functions")
            if (args.size != arity) {
                throw ExprError("$word takes $arity argument${if (arity == 1) "" else "s"}, and ${args.size} ${if (args.size == 1) "is" else "are"} given")
            }
            return Expr.Apply(word, args)
        }
        return Expr.Ref(word, start, end)
    }
}

/**
 * Evaluates an [Expr] against an environment of named quantities, **dimension-checked at every step**
 * (OP-7). Every violation leaves as a [DimensionError] or an [ExprError], which the node wrapping this
 * turns into named invalidity that heals (OP-3).
 *
 * The dimension rules, one line each:
 *
 * | operation | rule |
 * |---|---|
 * | `+` `-` `min` `max` `mod` `hypot` | equal dimension, and it is kept |
 * | unary `-`, `abs` | dimension kept |
 * | `*` `/` | exponents combine |
 * | `^` / `pow` | exponent dimensionless; a **dimensioned base is allowed only under an integer literal exponent**, which scales the exponents (`r^2` is an area) |
 * | `sqrt` | halves the exponents, refusing an odd one by name |
 * | `cbrt` | thirds them, refusing a non-multiple of three by name |
 * | `sin` `cos` `tan` | an angle **or a plain number of radians** in, a plain number out — see [radians] |
 * | `asin` `acos` `atan` | a plain number in, an angle out |
 * | `atan2` | two of equal dimension in, an angle out |
 * | `exp` `log` `log10` | plain number in and out |
 * | `floor` `ceil` `round` `sign` | plain number in and out — see [rounding] |
 */
object ExprEval {
    /**
     * **Why rounding refuses a dimensioned argument.** `round(x)` over a length would round to whole
     * *millimetres*, because mm is the canonical base unit — deterministic, but a rule the panel's own
     * display unit would hide the moment the panel showed anything else. The alternative considered was to
     * round in the base unit and say so; refusing by name was chosen because the honest form is already
     * writable — `round(x/1mm) * 1mm` states the unit it rounds in.
     */
    const val ROUNDING_NOTE = "rounds a plain number: divide by the unit you mean to round in (round(x/1mm)*1mm)"

    /**
     * The one operation the **parser never produces**: an angle read as its plain number of radians (a plain
     * number passing through unchanged, anything else a [DimensionError]).
     *
     * It exists for [Derive], which needs it to state `d sin(u) = cos(u)·num(u')` without the radian
     * surviving into a slope that is then added to a plain one. Not in the vocabulary table, so `num(x)`
     * typed into a formula is the ordinary unknown-function refusal — the derived AST is evaluated, never
     * parsed, so nothing is spent and no stored text changes meaning.
     */
    const val NUM = "num"

    fun eval(
        e: Expr,
        env: (String) -> Quantity?,
    ): Quantity =
        when (e) {
            is Expr.Lit -> e.q
            // the drawing's own names win, and a constant is what is left when nothing carries the name
            is Expr.Ref ->
                env(e.name)
                    ?: CONSTANTS[e.name]?.let { Quantity.number(it) }
                    ?: throw ExprError("there is no value named '${e.name}'")
            is Expr.Apply -> {
                // the one rule that reads the *tree* rather than the values: a dimensioned base needs a
                // literal integer exponent, since an exponent that moved would move the result's dimension
                if ((e.op == "^" || e.op == "pow")) {
                    power(eval(e.args[0], env), e.args[1], eval(e.args[1], env))
                } else {
                    rule(e.op, e.args.map { eval(it, env) })
                }
            }
        }

    private fun power(
        base: Quantity,
        exponentAst: Expr,
        exponent: Quantity,
    ): Quantity {
        if (exponent.dim != Dimension.NONE) throw DimensionError("an exponent is a plain number, and this one is ${exponent.dim}")
        val n = exponent.base
        if (base.dim == Dimension.NONE) return Quantity.number(base.base.pow(n))
        val whole = exponentAst is Expr.Lit && n == round(n) && abs(n) <= 12
        if (!whole) {
            throw DimensionError(
                "a value of dimension ${base.dim} can only be raised to a whole-number power written out in the expression",
            )
        }
        val k = n.roundToInt()
        return Quantity(base.base.pow(n), Dimension(base.dim.length * k, base.dim.angle * k))
    }

    /** The dimension rule and the arithmetic of every operation but `^` — see the table on this object. */
    private fun rule(
        op: String,
        a: List<Quantity>,
    ): Quantity =
        when (op) {
            "+" -> a[0] + a[1]
            "-" -> a[0] - a[1]
            "*" -> a[0] * a[1]
            "/" -> {
                if (a[1].base == 0.0) throw ExprError("division by zero")
                a[0] / a[1]
            }
            "neg" -> -a[0]
            "abs" -> Quantity(abs(a[0].base), a[0].dim)
            "min" -> Quantity(min(a[0].base, same(op, a).base), a[0].dim)
            "max" -> Quantity(max(a[0].base, same(op, a).base), a[0].dim)
            "mod" -> {
                same(op, a)
                if (a[1].base == 0.0) throw ExprError("mod by zero")
                Quantity(a[0].base.mod(a[1].base), a[0].dim)
            }
            "hypot" -> {
                same(op, a)
                Quantity(hypot(a[0].base, a[1].base), a[0].dim)
            }
            "sqrt" -> {
                if (a[0].base < 0.0) throw ExprError("sqrt of a negative value")
                Quantity(sqrt(a[0].base), root(op, a[0].dim, 2))
            }
            "cbrt" -> Quantity(cbrt(a[0].base), root(op, a[0].dim, 3))
            "sin" -> Quantity.number(sin(radians(op, a[0])))
            "cos" -> Quantity.number(cos(radians(op, a[0])))
            "tan" -> Quantity.number(tan(radians(op, a[0])))
            NUM -> Quantity.number(radians(op, a[0]))
            "asin" -> Quantity.rad(asin(domain(op, plain(op, a[0]), -1.0, 1.0)))
            "acos" -> Quantity.rad(acos(domain(op, plain(op, a[0]), -1.0, 1.0)))
            "atan" -> Quantity.rad(atan(plain(op, a[0])))
            "atan2" -> {
                same(op, a)
                Quantity.rad(atan2(a[0].base, a[1].base))
            }
            "exp" -> Quantity.number(exp(plain(op, a[0])))
            "log" -> {
                val v = plain(op, a[0])
                if (v <= 0.0) throw ExprError("log of a value that is not positive")
                Quantity.number(ln(v))
            }
            "log10" -> {
                val v = plain(op, a[0])
                if (v <= 0.0) throw ExprError("log10 of a value that is not positive")
                Quantity.number(log10(v))
            }
            "floor" -> Quantity.number(floor(rounding(op, a[0])))
            "ceil" -> Quantity.number(ceil(rounding(op, a[0])))
            // `Math.round`'s rule and not `kotlin.math.round`'s: the vocabulary the user named rounds a
            // tie **up**, where kotlin.math rounds it to the even neighbour — and 2.5 becoming 2 would
            // read as a bug in the drawing rather than as a convention
            "round" -> Quantity.number(floor(rounding(op, a[0]) + 0.5))
            "sign" -> Quantity.number(sign(rounding(op, a[0])))
            else -> throw ExprError("unknown operation '$op'")
        }

    private fun same(
        op: String,
        a: List<Quantity>,
    ): Quantity {
        if (a[0].dim != a[1].dim) throw DimensionError("$op takes two values of the same dimension, and these are ${a[0].dim} and ${a[1].dim}")
        return a[1]
    }

    /**
     * An operand read **in radians**: an angle as itself, and a plain number as the radians it already is.
     *
     * The plain-number reading is the session-71 curve half's one widening of the table, and it is a
     * widening in the strict sense — nothing that computed a number before computes a different one now,
     * only texts that were invalid have become valid. Three reasons it is the right rule rather than a
     * concession: it is `java.lang.Math`'s own (`Math.sin(double)` takes radians, and the user's phrase named
     * that vocabulary); the radian is dimensionless in SI, so this engine's `ANGLE` is a bookkeeping
     * convenience rather than a physical dimension; and a function curve's parameter `t` is dimensionless by
     * construction, so `cos(t)` — the involute as the user wrote it — must mean what it says. What the rule
     * still refuses is the case that was ever in doubt: `cos` of a *length* is a dimension error as before.
     */
    private fun radians(
        op: String,
        q: Quantity,
    ): Double {
        if (q.dim != Dimension.ANGLE && q.dim != Dimension.NONE) {
            throw DimensionError("$op takes an angle or a plain number of radians, and this is ${q.dim}")
        }
        return q.base
    }

    private fun plain(
        op: String,
        q: Quantity,
    ): Double {
        if (q.dim != Dimension.NONE) throw DimensionError("$op takes a plain number, and this is ${q.dim}")
        return q.base
    }

    private fun rounding(
        op: String,
        q: Quantity,
    ): Double {
        if (q.dim != Dimension.NONE) throw DimensionError("$op $ROUNDING_NOTE — this is ${q.dim}")
        return q.base
    }

    private fun domain(
        op: String,
        v: Double,
        lo: Double,
        hi: Double,
    ): Double {
        if (v < lo || v > hi) throw ExprError("$op is defined between $lo and $hi, and this is $v")
        return v
    }

    private fun root(
        op: String,
        dim: Dimension,
        n: Int,
    ): Dimension {
        if (dim.length % n != 0 || dim.angle % n != 0) throw DimensionError("$op of $dim has no dimension — the exponents are not divisible by $n")
        return Dimension(dim.length / n, dim.angle / n)
    }
}
