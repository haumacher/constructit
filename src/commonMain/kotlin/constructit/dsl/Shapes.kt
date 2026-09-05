package constructit.dsl

import constructit.l10n.Msgs
import constructit.units.Dimension
import constructit.units.Quantity
import constructit.units.deg
import constructit.units.mm
import constructit.units.rad
import kotlin.math.PI

/**
 * A reusable custom construction (OP-6). [build] runs against a [Construction] receiver and
 * produces a result of designated output refs; instantiation nests its nodes under a path-id.
 */
class Macro<A, R>(val name: String, val build: Construction.(A) -> R)

/** Instantiate [macro] under instance id [instanceId] -> internal nodes get ids `instanceId/nk` (OP-6). */
fun <A, R> Construction.instance(
    macro: Macro<A, R>,
    instanceId: String,
    args: A,
): R =
    withInstance(instanceId) { macro.build(this, args) }

// ---- Rounded rectangle ----

data class RoundedRectArgs(val center: PointRef, val width: ScalarRef, val height: ScalarRef, val radius: ScalarRef)

/**
 * [boundary] is the same eight pieces as [segments] + [arcs], in the order they run **round the
 * boundary** — side, corner, side, corner. The macro is the only thing that knows that order, so it says
 * it, rather than leaving a caller to re-derive by geometry what the construction already decided (that
 * is how a closed shape becomes an area with no boundary tracing — see `Document.boundaryPiecesOf`).
 */
data class RoundedRect(
    val segments: List<SegmentRef>,
    val arcs: List<ArcRef>,
    val cornerCenters: List<PointRef>,
    val boundary: List<Ref<*>> = segments + arcs,
)

/** width x height rectangle centred on `center`, with rounded corners of `radius`. */
val roundedRect =
    Macro<RoundedRectArgs, RoundedRect>("roundedRect") { a ->
        val hw = scale(a.width, 0.5)
        val hh = scale(a.height, 0.5)
        val ix = sub(hw, a.radius) // inset extent (corner-centre offset)
        val iy = sub(hh, a.radius)
        val nix = neg(ix)
        val niy = neg(iy)
        val nhw = neg(hw)
        val nhh = neg(hh)

        val cTR = translate(a.center, ix, iy)
        val cTL = translate(a.center, nix, iy)
        val cBL = translate(a.center, nix, niy)
        val cBR = translate(a.center, ix, niy)

        val pTopR = translate(a.center, ix, hh)
        val pTopL = translate(a.center, nix, hh)
        val pRightT = translate(a.center, hw, iy)
        val pRightB = translate(a.center, hw, niy)
        val pBotR = translate(a.center, ix, nhh)
        val pBotL = translate(a.center, nix, nhh)
        val pLeftT = translate(a.center, nhw, iy)
        val pLeftB = translate(a.center, nhw, niy)

        val segments =
            listOf(
                segment(pTopL, pTopR),
                segment(pRightT, pRightB),
                segment(pBotR, pBotL),
                segment(pLeftB, pLeftT),
            )
        val arcs =
            listOf(
                arc(cTR, a.radius, const(0.0.deg), const(90.0.deg)),
                arc(cTL, a.radius, const(90.0.deg), const(180.0.deg)),
                arc(cBL, a.radius, const(180.0.deg), const(270.0.deg)),
                arc(cBR, a.radius, const(270.0.deg), const(360.0.deg)),
            )
        RoundedRect(
            segments,
            arcs,
            listOf(cTR, cTL, cBL, cBR),
            // top, top-right corner, right, bottom-right corner, ... : each piece meets the next
            boundary = listOf(segments[0], arcs[0], segments[1], arcs[3], segments[2], arcs[2], segments[3], arcs[1]),
        )
    }

/** Specialization of [roundedRect] with the corner radius fixed to 2 mm (OP-6 partial application). */
data class StandardRectArgs(val center: PointRef, val width: ScalarRef, val height: ScalarRef)

val standardRect =
    Macro<StandardRectArgs, RoundedRect>("standardRect") { a ->
        val radius = parameter("borderRadius", 2.0.mm, constant = true)
        roundedRect.build(this, RoundedRectArgs(a.center, a.width, a.height, radius))
    }

// ---- Bolt circle ----

data class BoltCircleArgs(
    val center: PointRef,
    val pitchDiameter: ScalarRef,
    val count: Int,
    val startAngle: ScalarRef,
    val holeDiameter: ScalarRef,
)

data class BoltCircle(val points: List<PointRef>, val holes: List<CircleRef>)

val boltCircle =
    Macro<BoltCircleArgs, BoltCircle>("boltCircle") { a ->
        val r = scale(a.pitchDiameter, 0.5)
        val holeR = scale(a.holeDiameter, 0.5)
        val points = ArrayList<PointRef>()
        val holes = ArrayList<CircleRef>()
        for (i in 0 until a.count) {
            val angle = add(a.startAngle, const((360.0 * i / a.count).deg))
            val p = polarPoint(a.center, r, angle)
            points.add(p)
            holes.add(circleCR(p, holeR))
        }
        BoltCircle(points, holes)
    }

// ---- Spur gear (sampled involute) ----

/**
 * A standard-proportion involute spur gear.
 *
 * [teeth] is **structural** — it decides how many nodes exist, exactly as an array's count does (see
 * *Structural count* in DESIGN.md) — so it is a plain `Int` argument, while everything continuous is a
 * scalar node and therefore editable without rebuilding anything. [module] is the ISO module m (pitch
 * diameter per tooth, a length), [pressureAngle] the generating angle α (20° is the standard).
 */
data class SpurGearArgs(
    val center: PointRef,
    val module: ScalarRef,
    val teeth: Int,
    val pressureAngle: ScalarRef,
    val boreRadius: ScalarRef,
)

/**
 * A gear blank: the toothed [outer] boundary, the [bore] hole, and the [region] of the two — plus the
 * defining radii as scalars, so a test, a dimension or a mating construction can *read* them rather than
 * recompute the standard proportions.
 */
data class SpurGear(
    val region: RegionRef,
    val outer: LoopRef,
    val bore: LoopRef,
    val pitchRadius: ScalarRef,
    val baseRadius: ScalarRef,
    val tipRadius: ScalarRef,
    val rootRadius: ScalarRef,
    val toothCentres: List<ScalarRef>,
)

/**
 * How many chords one tooth flank is sampled into.
 *
 * **Structural, hence a constant** and deliberately *not* derived from the module: a count computed from
 * a parameter's value would change how many nodes exist on every edit, which is precisely the
 * regeneration OP-21 forbids (a tessellation step count inside one `compute` is free to be adaptive —
 * see `GeomMath.chordSteps` — but a count that decides the *graph* cannot be).
 *
 * 12 chords put the sampled flank within about 0.005 mm of the exact involute for a m = 2, z = 20 gear
 * (asserted in `GearTest`) — a quarter of `GeomMath.TESS_TOL_MM`, so the sampling is never the dominant
 * approximation in the mesh that comes out.
 */
const val FLANK_SAMPLES = 12

/**
 * A spur gear whose tooth flanks are a **sampled involute** (OP-15: a sampled curve is honest as long as
 * it is deterministic, and this one is a fixed number of chords at fixed parameter values).
 *
 * The construction, standard proportions throughout: pitch radius `rp = m·z/2`, base radius
 * `rb = rp·cos α`, tip `ra = m(z/2 + 1)`, root `rf = m(z/2 − 1.25)`. One flank is the involute of the
 * base circle, sampled at 13 fixed values of the *pressure angle at the point* β — uniform in β, which
 * bunches the samples towards the base circle where the flank curves most — and rotated so the tooth is
 * symmetric about its own centre line: the offset is `ψ = inv(α) + π/(2z)`, which is what puts the tooth
 * thickness at the pitch circle at exactly half the circular pitch (zero backlash against its own copy).
 * The second flank is the **mirror** of the first, the tip is an arc at `ra`, the root land an arc at
 * `rf`, and below the base circle the flank is closed by a **radial** line rather than the trochoid a
 * generating cutter would leave — stated here because it is a real simplification, and it collapses to
 * nothing when `rf ≥ rb` (a gear with many teeth), which no code has to special-case.
 *
 * The teeth are the same construction rotated by `k·2π/z`, sharing the radii and flank angles as nodes —
 * so "every tooth is the same tooth" is not asserted anywhere, it is what the graph *is*.
 *
 * Its own domain is a node too ([Construction.requirePositive]): once a tooth's half-width at the flank's
 * *foot* reaches half the pitch, the two flanks meet before the root land begins, and the gear refuses with
 * a reason instead of emitting a boundary folded through itself (OP-3). No standard gear comes near it —
 * every pressure angle up to 30° stays inside the domain at every tooth count — but 45° at 20 teeth does
 * not, which is exactly the kind of thing a macro should say out loud rather than draw.
 */
val spurGear =
    Macro<SpurGearArgs, SpurGear>("spurGear") { a ->
        val z = a.teeth
        val half = z / 2.0
        val pitchR = scale(a.module, half)
        val baseR = mul(pitchR, cosS(a.pressureAngle))
        val tipR = scale(a.module, half + 1.0)
        val rootR = scale(a.module, half - 1.25)
        val pitchAngle = const((2.0 * PI / z).rad)

        // inv(alpha) = tan(alpha) - alpha, and the half-tooth offset that centres the tooth on its own axis
        val invAlpha = sub(radians(tanS(a.pressureAngle)), a.pressureAngle)
        val psi = add(invAlpha, const((PI / (2.0 * z)).rad))

        // the flank's parameter range: beta = the pressure angle at the point, from the root (clamped to
        // the base circle, where the involute begins) to the tip
        val baseSq = powS(baseR, 2)
        val betaLo = atan2S(sqrtS(maxS(sub(powS(rootR, 2), baseSq), const(Quantity(0.0, Dimension.AREA)))), baseR)
        val betaHi = atan2S(sqrtS(sub(powS(tipR, 2), baseSq)), baseR)

        // one flank, shared by every tooth: radius and polar angle per sample
        val radii = ArrayList<ScalarRef>(FLANK_SAMPLES + 1)
        val angles = ArrayList<ScalarRef>(FLANK_SAMPLES + 1)
        for (i in 0..FLANK_SAMPLES) {
            val beta = add(betaLo, scale(sub(betaHi, betaLo), i.toDouble() / FLANK_SAMPLES))
            radii.add(div(baseR, cosS(beta)))
            angles.add(sub(sub(radians(tanS(beta)), beta), psi))
        }
        // the flank's own foot angle, so the root land is what is left of the pitch between two teeth
        val rootHalf = neg(angles[0])
        val land =
            requirePositive(
                sub(pitchAngle, scale(rootHalf, 2.0)),
                Msgs.refusalShapeThisPressureAngleToothTooth(z = z),
            )

        val pieces = ArrayList<Ref<*>>(z * (2 * FLANK_SAMPLES + 4))
        val centres = ArrayList<ScalarRef>(z)
        for (k in 0 until z) {
            val off = const((2.0 * PI * k / z).rad)
            centres.add(off)
            val onA = angles.map { add(it, off) }
            val onB = angles.map { sub(off, it) }
            val flankA = radii.indices.map { polarPoint(a.center, radii[it], onA[it]) }
            val flankB = radii.indices.map { polarPoint(a.center, radii[it], onB[it]) }

            // up from the root circle to the involute's foot (zero-length once rf >= rb), then the flank
            pieces.add(segment(polarPoint(a.center, rootR, onA[0]), flankA[0]))
            for (i in 0 until FLANK_SAMPLES) pieces.add(segment(flankA[i], flankA[i + 1]))
            pieces.add(arc(a.center, tipR, onA[FLANK_SAMPLES], onB[FLANK_SAMPLES]))
            for (i in FLANK_SAMPLES downTo 1) pieces.add(segment(flankB[i], flankB[i - 1]))
            pieces.add(segment(flankB[0], polarPoint(a.center, rootR, onB[0])))
            pieces.add(arc(a.center, rootR, onB[0], add(onB[0], land)))
        }

        val outer = loop(*pieces.toTypedArray())
        // The second half of the domain, and the same mechanism: the bore has to leave a **web** between
        // itself and the root circle. Expressing the bore as "the root radius less that web" is what puts
        // the guard in the chain the geometry actually reads, so a bore that eats the teeth makes the gear
        // invalid *with a reason* and heals (OP-3). The bound is strict and no minimum rim is imposed: a
        // bore *at* the root circle already touches the boundary at every root land, which is not a hole,
        // while how thin a rim may be is the part's business and not the macro's.
        val web =
            requirePositive(
                sub(rootR, a.boreRadius),
                Msgs.refusalShapeBoreIsWideGearRoot(),
            )
        val bore = loop(circleCR(a.center, sub(rootR, web)))
        SpurGear(region(outer, bore), outer, bore, pitchR, baseR, tipR, rootR, centres)
    }

// ---- Rectangular hole pattern ----

data class HolePatternArgs(val origin: PointRef, val rows: Int, val cols: Int, val dx: ScalarRef, val dy: ScalarRef)

data class HolePattern(val points: List<List<PointRef>>)

val holePattern =
    Macro<HolePatternArgs, HolePattern>("holePattern") { a ->
        val grid = ArrayList<List<PointRef>>()
        for (row in 0 until a.rows) {
            val line = ArrayList<PointRef>()
            for (col in 0 until a.cols) {
                line.add(translate(a.origin, scale(a.dx, col.toDouble()), scale(a.dy, row.toDouble())))
            }
            grid.add(line)
        }
        HolePattern(grid)
    }
