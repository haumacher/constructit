package constructit.geom

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * One fillet **leg** as values: a carrier line, or a carrier circle (OP-14's generalized fillet).
 *
 * Values rather than refs, because this is the numeric half of the fillet — the part that scores which
 * variant a pair of clicks meant. It has two callers now: the *build*, which scores once and stores the
 * answer (OP-1), and the *preview*, which scores the same way on every hover and stores nothing. One
 * implementation, so "what the preview shows" and "what the click builds" cannot drift apart.
 */
class FilletLeg(val line: Line?, val circle: Circle?) {
    /** Where a fillet centred at [centre] touches this leg: a projection, or a scaled radial. */
    fun tangency(centre: Vec2): Vec2 =
        if (line != null) {
            line.origin + line.dir * (centre - line.origin).dot(line.dir)
        } else {
            val c = circle!!
            val d = centre - c.center
            if (d.length() < Vec2.EPS) c.center + Vec2(c.radius, 0.0) else c.center + d.normalized() * c.radius
        }

    companion object {
        fun of(line: Line) = FilletLeg(line, null)

        fun of(circle: Circle) = FilletLeg(null, circle)
    }
}

/**
 * The discrete choices a mixed (at least one round leg) fillet stores: [side1]/[side2] offset each leg
 * (which side of a line; R+r or R−r for a circle), [branch] picks between the two intersections of those
 * offsets. A stored choice, never re-derived (OP-1).
 */
class FilletVariant(val side1: Int, val side2: Int, val branch: Int)

/**
 * The discrete choices a **chamfer** stores: [side1]/[side2] are which way along each leg the corner opens
 * (the quadrant [FilletMath.legSigns] scores for two lines, `+1` along the carrier's own sense), and
 * [branch] picks which of the two carrier crossings is the corner when a round leg makes there be two.
 *
 * The same shape as [FilletVariant] and the same rule: decided once from the clicks, then stored (OP-1). The
 * first two positions mean exactly what they meant when both legs were lines, which is why a line–line
 * chamfer's stored `signs=1;-1` keeps its meaning byte for byte.
 */
class ChamferVariant(val side1: Int, val side2: Int, val branch: Int)

/**
 * The numeric geometry of a fillet: which variant a pair of clicks means, and what arc that variant is.
 *
 * Extracted from `Document` so the live preview can run **exactly** the scoring the build runs (OP-1's
 * "decided once from the clicks" is about *when* the answer is stored, not about who may compute it), and
 * so a preview can never touch the graph to find out — everything here is plain values.
 */
object FilletMath {
    /** An unsolvable variant scores worse than every solvable one by this margin (mm). */
    private const val UNSOLVABLE = 1.0e9

    /**
     * Which variant the two clicks meant, scored by how near each variant's two tangencies fall to where
     * the legs were clicked — the user pointed at the two places the rounding should touch, which is the
     * whole of the information the clicks carry.
     *
     * When no variant has a solution at all (r larger than the geometry admits) the one *closest* to having
     * one wins, so an invalid fillet (OP-3) heals into the one the user was reaching for.
     */
    fun variantFor(
        leg1: FilletLeg,
        leg2: FilletLeg,
        r: Double,
        clickA: Vec2,
        clickB: Vec2,
    ): FilletVariant? {
        if (r <= 0.0) return null
        var best: FilletVariant? = null
        var bestScore = Double.MAX_VALUE
        for (s1 in listOf(1, -1)) {
            for (s2 in listOf(1, -1)) {
                val hits = centres(leg1, leg2, r, s1, s2) ?: continue
                if (hits.isEmpty()) {
                    val score = UNSOLVABLE + gap(leg1, leg2, r, s1, s2)
                    if (score < bestScore) {
                        bestScore = score
                        best = FilletVariant(s1, s2, +1)
                    }
                    continue
                }
                for (branch in listOf(1, -1)) {
                    val centre = if (branch >= 0) hits.first() else hits.last()
                    val score = (leg1.tangency(centre) - clickA).length() + (leg2.tangency(centre) - clickB).length()
                    if (score < bestScore) {
                        bestScore = score
                        best = FilletVariant(s1, s2, branch)
                    }
                }
            }
        }
        return best
    }

    /** The candidate fillet centres of one variant, or null when the offsets themselves are degenerate. */
    fun centres(
        leg1: FilletLeg,
        leg2: FilletLeg,
        r: Double,
        s1: Int,
        s2: Int,
    ): List<Vec2>? {
        fun offsetLine(
            l: Line,
            s: Int,
        ) = Line(l.origin + l.dir.perp() * (s * r), l.dir)

        fun offsetCircle(
            c: Circle,
            s: Int,
        ) = (c.radius + s * r).takeIf { it > 0.0 }?.let { Circle(c.center, it) }
        return when {
            leg1.line != null && leg2.circle != null ->
                offsetCircle(leg2.circle, s2)?.let { GeomMath.intersectLC(offsetLine(leg1.line, s1), it).points }
            leg2.line != null && leg1.circle != null ->
                offsetCircle(leg1.circle, s1)?.let { GeomMath.intersectLC(offsetLine(leg2.line, s2), it).points }
            leg1.circle != null && leg2.circle != null -> {
                val c1 = offsetCircle(leg1.circle, s1)
                val c2 = offsetCircle(leg2.circle, s2)
                if (c1 == null || c2 == null) null else GeomMath.intersectCC(c1, c2).points
            }
            else -> null
        }
    }

    /** How far one unsolvable variant is from having a solution (mm) — see [variantFor]. */
    fun gap(
        leg1: FilletLeg,
        leg2: FilletLeg,
        r: Double,
        s1: Int,
        s2: Int,
    ): Double {
        val line = leg1.line ?: leg2.line
        if (line != null) {
            val c = (leg1.circle ?: leg2.circle) ?: return 0.0
            val s = if (leg1.line != null) s2 else s1
            val sLine = if (leg1.line != null) s1 else s2
            val off = Line(line.origin + line.dir.perp() * (sLine * r), line.dir)
            return abs(abs((c.center - off.origin).cross(off.dir)) - (c.radius + s * r))
        }
        val c1 = leg1.circle ?: return 0.0
        val c2 = leg2.circle ?: return 0.0
        val r1 = c1.radius + s1 * r
        val r2 = c2.radius + s2 * r
        val d = (c2.center - c1.center).length()
        return maxOf(0.0, d - (r1 + r2)) + maxOf(0.0, abs(r1 - r2) - d)
    }

    /**
     * The centre of one scored variant, or null when that variant has no solution — the value form of the
     * `intersect(offset, offset)` + `Select` composition the build makes out of nodes.
     */
    fun centreOf(
        leg1: FilletLeg,
        leg2: FilletLeg,
        r: Double,
        v: FilletVariant,
    ): Vec2? {
        val hits = centres(leg1, leg2, r, v.side1, v.side2)?.takeIf { it.isNotEmpty() } ?: return null
        return if (v.branch >= 0) hits.first() else hits.last()
    }

    /**
     * The fillet arc of a scored variant: the minor arc between the two tangencies — `Construction.filletArc`
     * on values, the same rule (a fillet fills a corner, so its sweep is under a half turn).
     */
    fun arcOf(
        leg1: FilletLeg,
        leg2: FilletLeg,
        r: Double,
        v: FilletVariant,
    ): Arc? {
        val c = centreOf(leg1, leg2, r, v) ?: return null
        val a = leg1.tangency(c) - c
        val b = leg2.tangency(c) - c
        if (a.length() < Vec2.EPS || (a + b).length() < Vec2.EPS) return null
        return Arc(c, a.length(), a.angle(), b.angle(), a.cross(b) > 0)
    }

    /**
     * Which way along each of two lines the clicked corner opens: `+1` along the line's own direction, `-1`
     * against it. The line–line fillet's and the chamfer's stored quadrant (OP-1), on values.
     */
    fun legSigns(
        l1: Line,
        l2: Line,
        clickA: Vec2,
        clickB: Vec2,
    ): Pair<Int, Int> {
        val corner = cornerOf(l1, l2) ?: return 1 to 1 // parallel legs: no corner to sit in
        return (if ((clickA - corner).dot(l1.dir) < 0) -1 else 1) to (if ((clickB - corner).dot(l2.dir) < 0) -1 else 1)
    }

    /** Where two lines cross, or null when they are parallel. */
    fun cornerOf(
        l1: Line,
        l2: Line,
    ): Vec2? {
        val denom = l1.dir.cross(l2.dir)
        if (abs(denom) <= Vec2.EPS) return null
        return l1.origin + l1.dir * ((l2.origin - l1.origin).cross(l2.dir) / denom)
    }

    /**
     * The line–line fillet arc of radius [r] in the corner named by [sign1]/[sign2] — `filletBetweenLines`
     * on values, so a preview shows the arc that op will produce.
     */
    fun lineLineArc(
        l1: Line,
        l2: Line,
        r: Double,
        sign1: Int,
        sign2: Int,
    ): Arc? {
        val corner = cornerOf(l1, l2) ?: return null
        val u1 = l1.dir * sign1.toDouble()
        val u2 = l2.dir * sign2.toDouble()
        val bis = u1 + u2
        if (bis.length() < Vec2.EPS) return null
        val bisU = bis.normalized()
        val half = acos(u1.dot(bisU).coerceIn(-1.0, 1.0))
        val sinH = sin(half)
        val tanH = tan(half)
        if (sinH < Vec2.EPS || tanH < Vec2.EPS) return null
        val center = corner + bisU * (r / sinH)
        val t1 = corner + u1 * (r / tanH)
        val t2 = corner + u2 * (r / tanH)
        return Arc(center, r, (t1 - center).angle(), (t2 - center).angle(), (t1 - center).cross(t2 - center) > 0)
    }

    /**
     * The two ends of the chamfer of [distance] across the corner named by [sign1]/[sign2] —
     * `pointAlongLine` twice, on values. The two-straight-legs case of [chamferBevel].
     */
    fun chamferEnds(
        l1: Line,
        l2: Line,
        distance: Double,
        sign1: Int,
        sign2: Int,
    ): Segment? {
        val corner = cornerOf(l1, l2) ?: return null
        return Segment(corner + l1.dir * (sign1 * distance), corner + l2.dir * (sign2 * distance))
    }

    // ---- the chamfer, generalized to a round leg: the convention, decided once (session 76, item c) ----

    /**
     * **The chamfer-on-arc convention**, and the whole of it: a chamfer's setback is measured
     * **along the carrier** — arc distance on a round leg — and the bevel is the straight segment between the
     * two setback points so found.
     *
     * The parked note that this retires named the fork: *"a bevel across a round leg has two honest readings,
     * a chord and an arc of the same length"*. The arc reading is chosen, on four grounds and not on taste:
     *
     * 1. **It is the same sentence one leg kind on.** On a straight leg the setback *is* distance along the
     *    leg from the corner (`pointAlongLine`), so "along the carrier" states the existing rule rather than a
     *    new one — exactly as the generalized fillet found its tangency *on* the carrier (a projection on a
     *    line, a radial on a circle) instead of gaining a rule per leg kind.
     * 2. **Equal setback means equal material off each leg.** A chord of `d` on a circle of radius `R` eats
     *    `2R·asin(d/2R) > d` of that leg, so one typed number would take different amounts out of the two legs
     *    of one corner — and the number a chamfer is typed with is a machinist's setback, i.e. travel along
     *    the edge.
     * 3. **It costs no new stored choice.** The arc reading is one angle, `θ ± d/R`, closed form and exact;
     *    the chord reading is a circle of radius `d` about the corner met with the carrier — exact too, but
     *    with *two* solutions, so it would owe a second persisted branch per round leg for nothing.
     * 4. **The limit is continuous, so nothing stored changes meaning.** As `R → ∞` the arc setback tends to
     *    the straight one, and for two straight legs this *is* [chamferEnds] — the same points, the same
     *    signs, the same file.
     *
     * Null when the leg carries neither a line nor a circle, or the circle is degenerate.
     */
    fun setback(
        leg: FilletLeg,
        corner: Vec2,
        distance: Double,
        sign: Int,
    ): Vec2? {
        leg.line?.let { return corner + it.dir.normalized() * (sign * distance) }
        val c = leg.circle ?: return null
        if (c.radius <= Vec2.EPS) return null
        val d = corner - c.center
        if (d.length() < Vec2.EPS) return null
        // the corner is on the carrier by construction, so its own angle is where the travel starts; the arc
        // distance is an angle, `d / R`, taken the way [legDirection] calls positive
        val a = d.angle() + sign * distance / c.radius
        return c.center + Vec2(cos(a), sin(a)) * c.radius
    }

    /**
     * The direction of travel along [leg] at [at] in the `+1` sense — a line's own direction, and a circle's
     * **counter-clockwise** tangent, which is the sense [setback] turns in.
     */
    fun legDirection(
        leg: FilletLeg,
        at: Vec2,
    ): Vec2? {
        leg.line?.let { return it.dir.normalized() }
        val c = leg.circle ?: return null
        val d = at - c.center
        return if (d.length() < Vec2.EPS) null else d.perp().normalized()
    }

    /**
     * Where the two legs' **carriers** meet — the corner a chamfer is cut across, as an ordered list.
     *
     * One point for two lines (so the branch is not a choice at all, which is why the line–line build stores
     * only two signs). Up to two for a round leg, ordered exactly as [GeomMath.intersectLC] /
     * [GeomMath.intersectCC] order them, so a stored branch means the same crossing on every reload (OP-1).
     */
    fun chamferCorners(
        leg1: FilletLeg,
        leg2: FilletLeg,
    ): List<Vec2> =
        when {
            leg1.line != null && leg2.line != null -> listOfNotNull(cornerOf(leg1.line, leg2.line))
            leg1.line != null && leg2.circle != null -> GeomMath.intersectLC(leg1.line, leg2.circle).points
            leg2.line != null && leg1.circle != null -> GeomMath.intersectLC(leg2.line, leg1.circle).points
            leg1.circle != null && leg2.circle != null -> GeomMath.intersectCC(leg1.circle, leg2.circle).points
            else -> emptyList()
        }

    /** The corner of one scored variant, or null when that variant has none. */
    fun chamferCorner(
        leg1: FilletLeg,
        leg2: FilletLeg,
        v: ChamferVariant,
    ): Vec2? {
        val hits = chamferCorners(leg1, leg2).takeIf { it.isNotEmpty() } ?: return null
        return if (v.branch >= 0) hits.first() else hits.last()
    }

    /** The bevel of one scored variant: the segment between the two setback points — see [setback]. */
    fun chamferBevel(
        leg1: FilletLeg,
        leg2: FilletLeg,
        distance: Double,
        v: ChamferVariant,
    ): Segment? {
        val corner = chamferCorner(leg1, leg2, v) ?: return null
        val a = setback(leg1, corner, distance, v.side1) ?: return null
        val b = setback(leg2, corner, distance, v.side2) ?: return null
        if ((b - a).length() < Vec2.EPS) return null
        return Segment(a, b)
    }

    /**
     * Which variant the two clicks meant, scored exactly as a fillet's is ([variantFor]): by how near the
     * bevel's two ends fall to where the legs were clicked. The user pointed at the two places the bevel
     * should reach, which is the whole of the information the clicks carry.
     */
    fun chamferVariantFor(
        leg1: FilletLeg,
        leg2: FilletLeg,
        distance: Double,
        clickA: Vec2,
        clickB: Vec2,
    ): ChamferVariant? {
        if (distance <= 0.0) return null
        val corners = chamferCorners(leg1, leg2)
        if (corners.isEmpty()) return null
        var best: ChamferVariant? = null
        var bestScore = Double.MAX_VALUE
        for (branch in if (corners.size > 1) listOf(1, -1) else listOf(1)) {
            for (s1 in listOf(1, -1)) {
                for (s2 in listOf(1, -1)) {
                    val v = ChamferVariant(s1, s2, branch)
                    val bevel = chamferBevel(leg1, leg2, distance, v) ?: continue
                    val score = (bevel.a - clickA).length() + (bevel.b - clickB).length()
                    if (score < bestScore) {
                        bestScore = score
                        best = v
                    }
                }
            }
        }
        return best
    }

    /**
     * One of the two **bisectors** of two lines, as the composition the graph builds: the crossing, a unit
     * step along each leg ([sign] flipping the second), and the bisector of that angle. `sign = +1` bisects
     * the sector the two directions open, `-1` the other one — which is the choice the LLL circle stores.
     */
    fun bisector(
        l1: Line,
        l2: Line,
        sign: Int,
    ): Line? {
        val corner = cornerOf(l1, l2) ?: return null
        val u1 = l1.dir.normalized()
        val u2 = l2.dir.normalized() * sign.toDouble()
        val bis = u1 + u2
        if (bis.length() < Vec2.EPS) return null
        return Line(corner, bis.normalized())
    }

    /** Distance from [p] to [l] — a fillet's, and a tangent circle's, radius when the centre is [p]. */
    fun distanceTo(
        l: Line,
        p: Vec2,
    ): Double {
        val u = l.dir.normalized()
        return (p - (l.origin + u * (p - l.origin).dot(u))).length()
    }

    /**
     * The four circles tangent to three lines (the **LLL** Apollonius case): the incircle and the three
     * excircles of the triangle they make, each keyed by the two bisector branches that produce it.
     *
     * Each candidate is `(signs, circle)` where the signs are the branch of `bisector(l1, l2)` and of
     * `bisector(l1, l3)` — exactly what the tool stores (OP-1), so replay rebuilds the same one of the four
     * by construction rather than by re-scoring. Empty when the three lines make no triangle (two of them
     * parallel, or all three concurrent).
     */
    fun tangentCircles(
        l1: Line,
        l2: Line,
        l3: Line,
    ): List<Pair<Pair<Int, Int>, Circle>> {
        val out = ArrayList<Pair<Pair<Int, Int>, Circle>>(4)
        for (s12 in listOf(1, -1)) {
            for (s13 in listOf(1, -1)) {
                val b12 = bisector(l1, l2, s12) ?: continue
                val b13 = bisector(l1, l3, s13) ?: continue
                val centre = GeomMath.intersectLL(b12, b13).points.firstOrNull() ?: continue
                val r = distanceTo(l1, centre)
                if (r < Vec2.EPS) continue
                out.add((s12 to s13) to Circle(centre, r))
            }
        }
        return out
    }

    /**
     * Which of the four tangent circles the final click meant: the one whose **circumference** is nearest it,
     * as its signs and the circle itself. Null when the lines admit none.
     *
     * Scored on the circumference rather than on the centre because that is what the user is pointing at —
     * "the circle that goes there" — and because the four centres can be far from every one of them.
     */
    fun nearestTangentCircle(
        l1: Line,
        l2: Line,
        l3: Line,
        near: Vec2,
    ): Pair<Pair<Int, Int>, Circle>? =
        tangentCircles(l1, l2, l3).minByOrNull { abs((near - it.second.center).length() - it.second.radius) }
}
