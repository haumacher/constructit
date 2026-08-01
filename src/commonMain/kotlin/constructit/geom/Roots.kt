package constructit.geom

import kotlin.math.abs
import kotlin.math.max

/**
 * Real roots of a real polynomial, by **derivative isolation** — the deterministic solver the conic
 * package's quartic needs (OP-1's ordered solution sets; see *Conics* in DESIGN.md).
 *
 * Why this and not Ferrari's closed form: a quartic's radical solution goes through a resolvent cubic
 * whose conditioning collapses exactly where two intersections approach each other, which is precisely
 * the case a drawing walks through while a parameter is dragged. Rolle's theorem gives a solver with no
 * such cliff: **between two real roots of `p` lies a real root of `p'`**, so the roots of the derivative
 * partition the line into intervals on which `p` is monotone, and each interval holds at most one root —
 * found by a sign test and bisected to the last bit. Applied recursively, degree *n* costs degree *n−1*,
 * and the base case is a line.
 *
 * Two properties make it fit this codebase. It is **deterministic**: the same coefficients give the same
 * roots in the same order, bit for bit, on every platform and every run, which is what a persisted branch
 * choice (OP-1) and a byte-equal save (OP-18) rest on. And it **degrades honestly**: a double root shows
 * up as a critical point where `p` is (numerically) zero rather than as a sign change, so it is reported
 * once — a near-tangency comes back as *one* solution, never as two spurious ones.
 */
object Roots {
    /**
     * A leading coefficient this small **relative to the largest one** is treated as absent, i.e. the
     * polynomial is read one degree lower. A quartic in `tan(t/2)` loses its leading term exactly when
     * `t = π` is a root (see [Conics.intersect]), so this is the ordinary case rather than a guard.
     */
    private const val LEAD_EPS = 1e-13

    /** Relative residual under which a critical point counts as a (double) root. */
    private const val TOUCH_EPS = 1e-11

    /** Two roots closer than this (absolutely) are the same root. */
    private const val SAME = 1e-12

    /** Bisection steps — a double's mantissa is 53 bits, so this is convergence with room to spare. */
    private const val STEPS = 100

    /** `p(x)` for `p = c[0] + c[1]·x + … + c[n]·xⁿ` (Horner). */
    fun eval(
        c: DoubleArray,
        x: Double,
    ): Double {
        var v = 0.0
        for (i in c.indices.reversed()) v = v * x + c[i]
        return v
    }

    /** The scale `p` is measured against at [x] — the sum of its terms' magnitudes there. */
    private fun scaleAt(
        c: DoubleArray,
        x: Double,
    ): Double {
        var v = 0.0
        var p = 1.0
        for (e in c) {
            v += abs(e) * abs(p)
            p *= x
        }
        return max(v, 1e-300)
    }

    /**
     * The real roots of `c[0] + c[1]·x + … + c[n]·xⁿ`, ascending and deduplicated.
     *
     * The all-zero polynomial (every real number a root) comes back **empty**, which is the honest answer
     * here: two identical conics have a whole curve in common and no ordered solution set at all, so the
     * caller reports that as its own refusal rather than as a list of points.
     */
    fun real(c: DoubleArray): List<Double> {
        val scale = c.maxOfOrNull { abs(it) } ?: 0.0
        if (scale <= 0.0) return emptyList()
        var n = c.size - 1
        while (n > 0 && abs(c[n]) <= LEAD_EPS * scale) n--
        if (n <= 0) return emptyList()
        val p = c.copyOf(n + 1)
        if (n == 1) return listOf(-p[0] / p[1])
        val d = DoubleArray(n) { (it + 1) * p[it + 1] }
        val crit = real(d)
        // Cauchy's bound: every real root lies in [-bound, bound]. A critical point outside it separates
        // no roots (there are none out there), so filtering to the window loses nothing — Rolle guarantees
        // that the critical point *between* two roots is itself inside it.
        val bound = 1.0 + (0 until n).maxOf { abs(p[it]) } / abs(p[n])
        val pts = ArrayList<Double>(crit.size + 2)
        pts.add(-bound)
        for (x in crit.sorted()) if (x > -bound && x < bound) pts.add(x)
        pts.add(bound)
        val out = ArrayList<Double>(n)

        fun addRoot(x: Double) {
            if (out.none { abs(it - x) <= SAME }) out.add(x)
        }
        // a critical point where p vanishes is a repeated root — the near-tangent case, reported once
        for (x in pts) if (abs(eval(p, x)) <= TOUCH_EPS * scaleAt(p, x)) addRoot(x)
        for (i in 0 until pts.size - 1) {
            var lo = pts[i]
            var hi = pts[i + 1]
            val flo = eval(p, lo)
            val fhi = eval(p, hi)
            if (flo == 0.0 || fhi == 0.0) continue
            if ((flo < 0.0) == (fhi < 0.0)) continue
            val negLo = flo < 0.0
            repeat(STEPS) {
                val mid = 0.5 * (lo + hi)
                if (mid <= lo || mid >= hi) return@repeat
                if ((eval(p, mid) < 0.0) == negLo) lo = mid else hi = mid
            }
            addRoot(0.5 * (lo + hi))
        }
        out.sort()
        return out
    }
}
