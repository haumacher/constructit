package constructit.geom

import constructit.expr.Expr
import constructit.expr.ExprError
import constructit.expr.ExprEval
import constructit.l10n.Msg
import constructit.l10n.Msgs
import constructit.units.Dimension
import constructit.units.DimensionError
import constructit.units.Quantity
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A **function curve** (the session-71 expressions entry, curve half): the piece of plane traced by
 * `x(t)`, `y(t)` as the dimensionless parameter [param] runs from [t0] to [t1].
 *
 * The whole of the mechanism is *the same expressions plus a parameter* — deliberately **no new primitive
 * curve kind per curve family**, which is the user's own design: an involute, a cycloid, a spiral, a
 * catenary and a lemniscate are one element with five different texts, not five element kinds. The two
 * expressions are the record (OP-18): stored verbatim, parsed deterministically on load, resolved through
 * the naming authority, and evaluated closed-form every pass.
 *
 * [env] holds the named scalars the two texts read, resolved to values by the node that built this — so a
 * scalar is an ordinary DAG input and editing it moves the curve by nothing but the recompute every other
 * edit uses.
 *
 * [dx] and [dy] are the **symbolic derivatives** ([constructit.expr.Derive]), or null with [noTangent]
 * saying which function stopped them. That split is the honesty line of OP-24 read one curve family on:
 * **position-along is exact** — a rider lives at the parameter, and the point there is the expression
 * itself — the tangent and the normal are exact where the derivative is statable, and where it is not the
 * tangent-dependent constructions **refuse by name** rather than differencing numerically.
 *
 * [map] is the affine image this curve is under. An affine map **composes with the function** rather than
 * being fitted to samples, so mirror, rotate, scale and a pattern's every copy are exact — and
 * [GeomMath.transformArc]'s similarity caveat, which the rest of the drawing still lives with, can never
 * apply here.
 */
data class FuncCurve(
    val x: Expr,
    val y: Expr,
    val dx: Expr?,
    val dy: Expr?,
    val noTangent: Msg?,
    val env: Map<String, Quantity>,
    val t0: Double,
    val t1: Double,
    val param: String = "t",
    val map: Affine = FuncCurves.IDENTITY,
    /** The two texts as the user wrote them, for a refusal or an invalidity that has to quote the curve. */
    val text: String = "",
) {
    val span: Double get() = t1 - t0
}

/**
 * The maths of function curves: points, tangents, tessellation, area, length, nearest-parameter and the
 * intersections.
 *
 * Pure functions of values, exactly as [GeomMath] and [Conics] are. The evaluator is the expression
 * evaluator itself ([ExprEval]), so there is one arithmetic and one dimension check for a formula in the
 * panel and for a curve on the sheet.
 */
object FuncCurves {
    val IDENTITY = Affine(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)

    /**
     * How many parametric steps the node checks the whole domain on before calling the curve valid.
     *
     * A function may leave its own domain part-way (a `sqrt` going negative, a `log` reaching zero), and a
     * curve that draws over half its span and throws over the other half would be a value with a hole in it.
     * So the *value* is only built when the expressions evaluate to lengths on this grid and at both ends,
     * and a failure is the named invalidity that heals (OP-3), quoting the parameter it failed at.
     */
    const val VALIDATE_STEPS = 256

    /**
     * How many chords the **renderer** draws a function curve with: a fixed count, for exactly the reason
     * [GeomMath.renderArcSteps] and [Conics.renderSteps] are fixed — an adaptive count would make every SVG
     * golden depend on the camera. What the *mesh* uses instead is [chordSteps], which is adaptive and
     * scale-relative like everything else that becomes triangles.
     */
    const val RENDER_STEPS = 96

    /** The stated tolerance of a **measured** function-curve length and area, in mm (OP-15's class). */
    const val LENGTH_TOL_MM = 1e-9

    /** How close two intersections may be, in the curve's own parameter, before they are the same one. */
    const val SAME_PARAM = 1e-9

    private const val EPS = Vec2.EPS

    // ---- position along: exact, at the parameter ----

    /** The environment [c]'s expressions are evaluated in at parameter [t] — the scalars, plus `t` itself. */
    private fun envAt(
        c: FuncCurve,
        t: Double,
    ): (String) -> Quantity? {
        val p = Quantity.number(t)
        // the parameter **shadows** a drawing scalar of the same name: it is a binder, the way a lambda's
        // argument is, and the rule is permanent rather than a growing vocabulary (see DESIGN.md — a rename
        // that would capture a reference is refused by name)
        return { n -> if (n == c.param) p else c.env[n] }
    }

    /**
     * A length read off one of the two expressions — or a [DimensionError] naming what came out instead.
     *
     * The zero exception is [constructit.expr.Derive]'s: a derivative that folded to an identical zero has
     * no dimension to carry, and zero is zero in every unit, so a plain zero is read as `0 mm`. Any other
     * plain number is the honest dimension violation it looks like.
     */
    private fun mm(
        q: Quantity,
        what: Msg,
    ): Double {
        if (q.dim == Dimension.LENGTH) return q.base
        if (q.dim == Dimension.NONE && q.base == 0.0) return 0.0
        throw DimensionError(Msgs.refusalDimensionMustBeALength(what = what, dim = q.dim.toString()))
    }

    /**
     * The point at parameter [t] — the expressions themselves, **exact**, then the affine image.
     *
     * Null where the expressions cannot be evaluated there. Unreachable for a curve the drawing holds (the
     * node validates the whole domain, see [VALIDATE_STEPS]); returning null rather than throwing is what
     * keeps a stray one out of a repaint.
     */
    fun pointAt(
        c: FuncCurve,
        t: Double,
    ): Vec2? =
        try {
            pointAtOrThrow(c, t)
        } catch (_: ExprError) {
            null
        } catch (_: DimensionError) {
            null
        }

    /** [pointAt], with the reason instead of a null — what the node's validation reports (OP-3). */
    fun pointAtOrThrow(
        c: FuncCurve,
        t: Double,
    ): Vec2 {
        val env = envAt(c, t)
        val px = mm(ExprEval.eval(c.x, env), Msg.text("x(${c.param})"))
        val py = mm(ExprEval.eval(c.y, env), Msg.text("y(${c.param})"))
        return c.map.apply(Vec2(px, py))
    }

    /**
     * The derivative `dP/dt` at [t] — **exact**, from the symbolic derivative, and carried through the
     * affine map by its linear part (which is what makes a mirrored or patterned curve's tangent the mirror
     * or the rotation of this one, with nothing re-fitted).
     *
     * Null exactly when the curve has no statable derivative ([FuncCurve.noTangent] then says which function
     * stopped it) or when the derivative cannot be evaluated at [t].
     */
    fun tangentAt(
        c: FuncCurve,
        t: Double,
    ): Vec2? {
        val ex = c.dx ?: return null
        val ey = c.dy ?: return null
        return try {
            val env = envAt(c, t)
            val vx = mm(ExprEval.eval(ex, env), Msg.text("dx/d${c.param}"))
            val vy = mm(ExprEval.eval(ey, env), Msg.text("dy/d${c.param}"))
            c.map.linear(Vec2(vx, vy))
        } catch (_: ExprError) {
            null
        } catch (_: DimensionError) {
            null
        }
    }

    /**
     * The unit **left** normal at [t] — the tangent turned +90°, so it is the same side of the walk
     * direction a thick path's *left* is (OP-21) and turns with the curve rather than with the world.
     */
    fun normalAt(
        c: FuncCurve,
        t: Double,
    ): Vec2? {
        val d = tangentAt(c, t) ?: return null
        if (d.length() < EPS) return null
        return d.normalized().perp()
    }

    fun start(c: FuncCurve): Vec2? = pointAt(c, c.t0)

    fun end(c: FuncCurve): Vec2? = pointAt(c, c.t1)

    /** The parameter [k] of the way along the domain — the one map between "how far along" and `t`. */
    fun paramOfFraction(
        c: FuncCurve,
        k: Double,
    ): Double = c.t0 + c.span * k

    // ---- validation: the whole domain, once, at the node ----

    /**
     * Why [c] is not a curve — a backwards domain, or a parameter at which the expressions leave their own
     * domain — or null when it is one. What a node turns into [constructit.core.EvalResult.Invalid] (OP-3),
     * so every failure here heals the moment the formula or the domain is corrected.
     */
    fun invalidity(c: FuncCurve): Msg? {
        if (!c.t0.isFinite() || !c.t1.isFinite()) return Msgs.refusalFunccurveDomainIsNotPairNumbers(param = c.param)
        if (c.span <= 0.0) {
            return Msgs.refusalFunccurveDomainRunsWhichIsNot(text = trim(c.t0), text2 = trim(c.t1), param = c.param)
        }
        for (i in 0..VALIDATE_STEPS) {
            val t = c.t0 + c.span * i / VALIDATE_STEPS
            try {
                pointAtOrThrow(c, t)
            } catch (e: ExprError) {
                return Msgs.refusalFunccurveAtParameter(param = c.param, value = trim(t), message = e.message ?: "")
            } catch (e: DimensionError) {
                return Msgs.refusalFunccurveAtParameter(param = c.param, value = trim(t), message = e.message ?: "")
            }
        }
        return null
    }

    private fun trim(v: Double): String {
        val r = v.toString()
        return if (r.endsWith(".0")) r.dropLast(2) else r
    }

    // ---- tessellation: the one scale-relative tolerance, adaptive where curvature demands ----

    /**
     * How many chords [c] needs to stay within the effective tolerance of the true curve.
     *
     * **The rule, stated once.** The sagitta of a chord over a parametric step `Δt` is at most
     * `max|P''|·Δt²/8` — the same bound the arc, the ellipse and the Bézier are all counted by — so with the
     * span `T` and `n` chords the error is at most `max|P''|·T²/(8n²)`, giving
     * `n = ceil(T·sqrt(max|P''| / (8·tol)))`. `max|P''|` has no closed form for an arbitrary function, so it
     * is **estimated by second differences over a coarse probe grid** and taken at its largest — which is
     * where the adaptivity comes from: a curve that barely bends gets the minimum, an involute at large `t`
     * gets hundreds. The tolerance is [GeomMath.effectiveTol] at the curve's own size, so the count is
     * scale-invariant exactly as GitHub #13 requires of everything else.
     *
     * It is *equal parametric steps* rather than a recursive subdivision, for OP-15's reason: determinism is
     * the load-bearing property, and a count is deterministic where a recursion's stopping point is a
     * function of floating-point noise.
     */
    fun chordSteps(
        c: FuncCurve,
        tolMm: Double = GeomMath.TESS_TOL_MM,
        quality: MeshQuality = MeshQuality.FINE,
    ): Int {
        val probes = 64
        var second = 0.0
        var size = 0.0
        val h = c.span / probes
        var prev: Vec2? = null
        var prev2: Vec2? = null
        for (i in 0..probes) {
            val p = pointAt(c, c.t0 + h * i) ?: continue
            size = max(size, p.length())
            val a = prev2
            val b = prev
            if (a != null && b != null) second = max(second, (a - b * 2.0 + p).length() / (h * h))
            prev2 = prev
            prev = p
        }
        val tol = GeomMath.effectiveTol(size, tolMm, quality)
        if (second <= 0.0 || tol <= 0.0) return max(1, RENDER_STEPS / 8)
        return max(4, min(2048, ceil(c.span * sqrt(second / (8.0 * tol))).toInt()))
    }

    /** Points along [c] at [steps] equal **parametric** steps, both ends included. */
    fun sample(
        c: FuncCurve,
        steps: Int,
    ): List<Vec2> {
        val n = max(1, steps)
        val out = ArrayList<Vec2>(n + 1)
        for (i in 0..n) {
            pointAt(c, c.t0 + c.span * i / n)?.let { out.add(it) }
        }
        return out
    }

    /** The polyline every 2D consumer draws and picks against — one policy, so drawing and picking agree. */
    fun renderSample(c: FuncCurve): List<Vec2> = sample(c, RENDER_STEPS)

    // ---- the genuinely metric: approximate, to a stated tolerance, and flagged (OP-15) ----

    /**
     * The **measured length** of [c], in mm — numeric, to [LENGTH_TOL_MM], and *flagged as such* wherever it
     * is shown, exactly as an elliptic arc's is (OP-15's approximated class, OP-24's line).
     *
     * `∫|P'(t)|dt` has no closed form for an arbitrary pair of expressions, so it is adaptive Simpson over
     * the symbolic speed where there is one, and the chord length of the adaptive tessellation where there
     * is not — the second is *stated* rather than silently substituted, because a length is a measurement
     * and a measurement may be approximate; it is a *construction* that may not be anchored on a chord.
     */
    fun arcLength(c: FuncCurve): Double {
        val speed = speedOf(c) ?: return chordLength(c)
        return simpson(speed, c.t0, c.t1, LENGTH_TOL_MM)
    }

    /** The chord length of the adaptive tessellation — the honest fallback where there is no derivative. */
    private fun chordLength(c: FuncCurve): Double {
        val pts = sample(c, chordSteps(c, LENGTH_TOL_MM))
        var total = 0.0
        for (i in 0 until pts.size - 1) total += (pts[i + 1] - pts[i]).length()
        return total
    }

    private fun speedOf(c: FuncCurve): ((Double) -> Double)? {
        if (c.dx == null || c.dy == null) return null
        return { t -> tangentAt(c, t)?.length() ?: 0.0 }
    }

    /**
     * Twice the signed area this piece contributes to a loop, as the line integral `∮(x·dy − y·dx)`.
     *
     * Numeric — the one place a function curve differs from every other boundary piece, all of which have a
     * closed form. `x·y' − y·x'` is an ordinary smooth integrand where the derivative exists, so adaptive
     * Simpson converges predictably and deterministically; where it does not, the piece's own chord polygon
     * is summed, which is what the mesh under it is anyway. Either way a region with a function curve in its
     * boundary has an **approximated area**, and the reading says so (OP-15).
     */
    fun doubleSignedArea(c: FuncCurve): Double {
        if (c.dx != null && c.dy != null) {
            val f = { t: Double ->
                val p = pointAt(c, t)
                val d = tangentAt(c, t)
                if (p == null || d == null) 0.0 else p.x * d.y - p.y * d.x
            }
            return simpson(f, c.t0, c.t1, LENGTH_TOL_MM)
        }
        val pts = sample(c, chordSteps(c))
        var total = 0.0
        for (i in 0 until pts.size - 1) total += pts[i].cross(pts[i + 1])
        return total
    }

    /**
     * The parameter at signed arc **distance** [s] from [from] — the sampled arc-length→parameter map, and
     * OP-15's approximated class stated where it belongs: the *map* is numeric (monotone bisection on
     * [arcLength]), and the point it lands on is then the curve's own, exact.
     */
    fun paramAtDistance(
        c: FuncCurve,
        from: Double,
        s: Double,
    ): Double {
        if (abs(s) < 1e-15) return from
        val dir = if (s >= 0.0) 1.0 else -1.0
        var lo = 0.0
        var hi = c.span
        val target = abs(s)
        repeat(64) {
            val mid = 0.5 * (lo + hi)
            val a = min(from, from + dir * mid)
            val b = max(from, from + dir * mid)
            val len = arcLength(c.copy(t0 = a, t1 = b))
            if (len < target) lo = mid else hi = mid
        }
        return (from + dir * 0.5 * (lo + hi)).coerceIn(c.t0, c.t1)
    }

    private fun simpson(
        f: (Double) -> Double,
        lo: Double,
        hi: Double,
        tol: Double,
    ): Double {
        if (abs(hi - lo) < 1e-18) return 0.0
        // a fixed outer split keeps the recursion shallow and the answer independent of where the domain
        // happens to start — the same shape [Conics.arcLength] uses, one curve family on
        val panels = max(8, min(256, ceil(abs(hi - lo) * 4.0).toInt()))
        var total = 0.0
        for (i in 0 until panels) {
            val a = lo + (hi - lo) * i / panels
            val b = lo + (hi - lo) * (i + 1) / panels
            total += adaptive(f, a, b, panel(f, a, b), tol / panels, 20)
        }
        return total
    }

    private fun panel(
        f: (Double) -> Double,
        a: Double,
        b: Double,
    ): Double = (b - a) / 6.0 * (f(a) + 4.0 * f((a + b) / 2.0) + f(b))

    private fun adaptive(
        f: (Double) -> Double,
        a: Double,
        b: Double,
        whole: Double,
        tol: Double,
        depth: Int,
    ): Double {
        val m = (a + b) / 2.0
        val left = panel(f, a, m)
        val right = panel(f, m, b)
        val delta = left + right - whole
        if (depth <= 0 || abs(delta) <= 15.0 * tol) return left + right + delta / 15.0
        return adaptive(f, a, m, left, tol / 2.0, depth - 1) + adaptive(f, m, b, right, tol / 2.0, depth - 1)
    }

    // ---- nearest parameter: what a click on the curve means, and what a rider's drag writes ----

    /**
     * The parameter of the point of [c] **nearest** [p] — what a click means and what a drag records.
     *
     * Seeded from the render sampling and refined by **golden-section search** on the bracket around the
     * winner, deliberately rather than by Newton: the refinement then needs no derivative at all, so a rider
     * rides a curve whose tangent this drawing cannot state, and only the tangent-dependent constructions
     * refuse. A pure function of the curve and the click, exactly as [GeomMath.bezierNearestParam] is: what
     * a drag records is the parameter it returned, so a reload never re-runs the search (OP-18).
     */
    fun nearestParam(
        c: FuncCurve,
        p: Vec2,
        samples: Int = RENDER_STEPS * 2,
    ): Double {
        var best = c.t0
        var bestD = Double.MAX_VALUE
        for (i in 0..samples) {
            val t = c.t0 + c.span * i / samples
            val q = pointAt(c, t) ?: continue
            val d = (q - p).length()
            if (d < bestD) {
                bestD = d
                best = t
            }
        }
        val step = c.span / samples
        var lo = max(c.t0, best - step)
        var hi = min(c.t1, best + step)
        val phi = 0.6180339887498949
        repeat(60) {
            val m1 = hi - (hi - lo) * phi
            val m2 = lo + (hi - lo) * phi
            val d1 = pointAt(c, m1)?.let { (it - p).length() } ?: Double.MAX_VALUE
            val d2 = pointAt(c, m2)?.let { (it - p).length() } ?: Double.MAX_VALUE
            if (d1 < d2) hi = m2 else lo = m1
        }
        return 0.5 * (lo + hi)
    }

    /** The point of [c] nearest [p] — [nearestParam] evaluated. */
    fun nearestPoint(
        c: FuncCurve,
        p: Vec2,
    ): Vec2? = pointAt(c, nearestParam(c, p))

    // ---- transforms: composed with the function, never fitted ----

    /**
     * [c] under an affine map — **composed**, so the image is the exact image of the function and not a
     * re-fit of its samples. This is what makes mirror, rotate, scale and every copy of a pattern exact over
     * a function curve, and it is why [GeomMath.transformArc]'s similarity caveat never reaches here.
     */
    fun transform(
        c: FuncCurve,
        t: Affine,
    ): FuncCurve = c.copy(map = compose(t, c.map))

    /**
     * The same piece **walked the other way** — what a loop's normalisation asks of every boundary piece.
     *
     * Done by substituting `t → t0 + t1 − t` into the expressions themselves rather than by carrying a
     * direction flag, so a reversed function curve is an ordinary function curve over the same domain and
     * every consumer that reads a parameter reads one meaning of it. The derivative reverses with it, by the
     * chain rule's own minus sign — exactly, with nothing re-fitted.
     */
    fun reverse(c: FuncCurve): FuncCurve {
        val flip = Expr.Apply("-", listOf(Expr.Lit(Quantity.number(c.t0 + c.t1)), Expr.Ref(c.param, 0, 0)))
        val neg = { e: Expr -> Expr.Apply("neg", listOf(substitute(e, c.param, flip))) }
        return c.copy(
            x = substitute(c.x, c.param, flip),
            y = substitute(c.y, c.param, flip),
            dx = c.dx?.let(neg),
            dy = c.dy?.let(neg),
        )
    }

    /** [e] with every reference to [name] replaced by [by] — the one AST rewrite this file performs. */
    private fun substitute(
        e: Expr,
        name: String,
        by: Expr,
    ): Expr =
        when (e) {
            is Expr.Lit -> e
            is Expr.Ref -> if (e.name == name) by else e
            is Expr.Apply -> Expr.Apply(e.op, e.args.map { substitute(it, name, by) })
        }

    /** `outer ∘ inner` — apply [inner] first. */
    fun compose(
        outer: Affine,
        inner: Affine,
    ): Affine =
        Affine(
            outer.a * inner.a + outer.c * inner.b,
            outer.b * inner.a + outer.d * inner.b,
            outer.a * inner.c + outer.c * inner.d,
            outer.b * inner.c + outer.d * inner.d,
            outer.a * inner.e + outer.c * inner.f + outer.e,
            outer.b * inner.e + outer.d * inner.f + outer.f,
        )

    // ---- intersections: numeric but deterministic, ordered along the first operand (OP-1) ----

    /**
     * Where [c] meets a curve given by its **implicit function** [g] — zero on the partner, and of opposite
     * signs on its two sides. One mechanism for the line (a signed distance), the circle and the ellipse
     * (`|P−C|²−r²` and `x²/a²+y²/b²−1`), because what all three have in common is exactly that.
     *
     * *Deterministic, and that is the whole claim* (OP-15's rule for splines, verbatim). The seeding is
     * **fixed**: the tessellation's own parametric grid, at a stated resolution that is a function of the
     * curve alone — not of the camera, not of the click. Every sign change on that grid is bisected to
     * [SAME_PARAM] in the parameter and then polished by the same bisection, so the answer is a pure
     * function of the two operands and a reload reproduces it bit for bit.
     *
     * *The ordering convention*, which a stored branch index is meaningless without: **ascending parameter
     * along [c]** — OP-1's canonical rule for parametric curves, stated in the spline note years before this
     * entry, with the function curve as the *first* operand so the rule reads directly. It is a property of
     * the operands alone, it turns with the pair under a rigid motion, and it needs no tie-breaking, since
     * two solutions at one parameter are one solution.
     *
     * What is **not** built and refuses by name is function curve ∩ function curve — see
     * `Document.intersect`, which names the intersection that does exist.
     */
    fun intersectImplicit(
        c: FuncCurve,
        g: (Vec2) -> Double,
    ): PointSet {
        val steps = max(RENDER_STEPS, chordSteps(c))
        val ts = ArrayList<Double>()
        val vs = ArrayList<Double>()
        for (i in 0..steps) {
            val t = c.t0 + c.span * i / steps
            val p = pointAt(c, t) ?: continue
            ts.add(t)
            vs.add(g(p))
        }
        val found = ArrayList<Double>()

        fun offer(t: Double) {
            if (found.none { abs(it - t) <= SAME_PARAM }) found.add(t)
        }
        for (i in vs.indices) {
            if (vs[i] == 0.0) {
                offer(ts[i])
                continue
            }
            if (i + 1 >= vs.size) break
            if (vs[i] > 0.0 == vs[i + 1] > 0.0) continue
            var lo = ts[i]
            var hi = ts[i + 1]
            var flo = vs[i]
            repeat(80) {
                val mid = 0.5 * (lo + hi)
                val p = pointAt(c, mid)
                val fm = if (p == null) 0.0 else g(p)
                if (fm == 0.0) {
                    lo = mid
                    hi = mid
                } else if ((fm > 0.0) == (flo > 0.0)) {
                    lo = mid
                    flo = fm
                } else {
                    hi = mid
                }
            }
            offer(0.5 * (lo + hi))
        }
        found.sort()
        return PointSet(found.mapNotNull { pointAt(c, it) })
    }

    /** The signed distance to a line — the implicit form [intersectImplicit] takes a line through. */
    fun lineImplicit(line: Line): (Vec2) -> Double {
        val n = line.dir.normalized().perp()
        return { p -> (p - line.origin).dot(n) }
    }

    /** `|P−C|² − r²`: negative inside the circle, positive outside. */
    fun circleImplicit(circle: Circle): (Vec2) -> Double {
        return { p -> (p - circle.center).length() - circle.radius }
    }
}
