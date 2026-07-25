package constructit.dsl

import constructit.units.deg
import constructit.units.mm

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

data class RoundedRect(val segments: List<SegmentRef>, val arcs: List<ArcRef>, val cornerCenters: List<PointRef>)

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
        RoundedRect(segments, arcs, listOf(cTR, cTL, cBL, cBR))
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
