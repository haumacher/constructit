package constructit.geom

import constructit.l10n.Msg
import constructit.l10n.Msgs

/**
 * A **station**: one stated position along a curve in space, together with the plane the curve pierces there
 * (OP-26, step 4).
 *
 * The word is borrowed rather than invented — a hull is defined by transverse *stations*, an aircraft has
 * fuselage stations, and a road or a railway measures position along an alignment as *chainage*. What it
 * produces is a **sketch space**, the same kind of thing a datum plane is, so nothing downstream learns a new
 * concept: draw in it, dimension in it, extrude and cut from it, place a group into it.
 *
 * [at] is the point on the path at the stated arc length, [tangent] the direction the curve leaves in there —
 * which is the plane's **normal** — and [ref] the parallel-transport frame's reference direction, which is
 * the plane's own x axis. So (ref, bi, tangent) is a right-handed frame and [plane]'s normal is the tangent
 * by construction rather than by arrangement.
 */
data class Station3(
    /** The stated distance from the path's start, in millimetres — what this station *is*. */
    val s: Double,
    val at: Vec3,
    val tangent: Vec3,
    val ref: Vec3,
) {
    /** The frame's second in-plane axis: `tangent × ref`, so (ref, bi, tangent) is right-handed. */
    val bi: Vec3 get() = tangent.cross(ref)

    /** This station as a sketch plane — origin on the path, normal along the run, axes the frame's. */
    val plane: Plane3 get() = Plane3(at, ref, bi)
}

/**
 * **Stations along a path** (OP-26, step 4): the frame at a *stated* arc length, as against [Frames3]'s
 * frame at every *sampled* one.
 *
 * The two are adjacent and deliberately not the same call. A sweep asks for a frame wherever its own chord
 * tolerance put a station, and takes the arc length it happens to land on; a station asks for a frame at a
 * number the user typed, and the number is the whole feature. What they **share** is the transport rule and
 * the sampling rule — [Frames3.startReference], [Frames3.transport] and [Frames3.baseSteps], reached rather
 * than re-derived — because two frames on one curve that disagreed about which way is up would be two
 * different curves as far as anything drawn on them is concerned.
 *
 * **Where the exactness lies, said rather than left to be discovered** (OP-15's rule about stating an error
 * in the unit a made part is wrong by):
 *
 * - The **position and the normal are analytic**. The piece is found by half-open interval, the parameter
 *   inside it by inverting the piece's own arc length ([Curves3.paramAtLength] — exact for a segment and for
 *   a helix, which both travel at constant speed; a bisection-safeguarded Newton on a Gauss–Legendre integral
 *   for a cubic, driven to `1e-9` mm, over an integral itself good to about `5e-12` mm), and the point and
 *   tangent are then read off the analytic piece. No
 *   polyline is involved in either, so a station 340 mm along a Bézier stands 340 mm along the **curve** and
 *   not 340 mm along a chord approximation of it.
 * - The **in-plane axes are transported**, and transport is a sampled quantity by its own definition: the
 *   rotation-minimizing frame is a limit of chord-to-chord rotations, and the discrete rule this project uses
 *   is the one [Frames3] states. So the reference direction carries the chord tolerance
 *   ([GeomMath.TESS_TOL_MM] = 0.02 mm) that every mesh in this kernel carries, and nothing sharper is
 *   claimed for it.
 *
 * **Out of range is node invalidity and never a refusal of the gesture** (OP-3, and OP-26's own doctrinal
 * point): the distance is a *live value*, so a distance past the end of the run makes the station's node
 * invalid with a named reason and everything sketched on it hides until the number is sane again. Healing is
 * required behaviour, and it is what a gesture refusal on a value could not give.
 */
object Stations3 {
    /**
     * The station of [path] at [s] millimetres from its start, with the in-plane frame started from the
     * direction [up] says — or null with the reason there is none.
     *
     * [up] is the normal of the space the path is parented to, exactly as it is for a sweep
     * ([Frames3.startReference]): a curve's construction is always parented (OP-26), so the space it was drawn
     * in already says which way is up along it, and tilting that datum rolls every station on the run with it.
     *
     * **The domain is `[0, L]` and it covers a closed path exactly once** — no wrap is offered, because there
     * is nothing to decide: the closing piece is one of the path's elements and its far end is its start.
     */
    fun at(
        path: Path3,
        up: Vec3,
        s: Double,
        tolMm: Double = GeomMath.TESS_TOL_MM,
    ): Pair<Station3?, Msg?> {
        if (path.elements.isEmpty()) return null to Msgs.refusalStationThisCurveHasNoPieces()
        val total = Curves3.length(path)
        if (total <= Geom3.WELD_TOL) return null to Msgs.refusalStationThisCurveHasNoLength()
        if (s < 0.0) {
            return null to
                Msgs.refusalStationStationIsMeasuredStartRun(mm = Frames3.mm(-s), mm2 = Frames3.mm(total))
        }
        if (s > total) {
            return null to
                Msgs.refusalStationStationMmAlongIsPast(mm = Frames3.mm(s), mm2 = Frames3.mm(total))
        }
        val (index, t) =
            Curves3.pieceAtLength(path, s)
                ?: return null to Msgs.refusalStationThereIsNoPieceThis(mm = Frames3.mm(s))
        val piece = path.elements[index]
        val at = Frames3.pointAt(piece, t)
        val tangent =
            Curves3.tangentAt(piece, t)
                ?: return null to
                    Msgs.refusalStationThisCurveHasNoDirection(mm = Frames3.mm(s))
        val ref =
            reference(path, index, t, tangent, up, tolMm)
                ?: return null to
                    Msgs.refusalStationThisCurveDoublesBackItself(mm = Frames3.mm(s))
        return Station3(s, at, tangent, ref) to null
    }

    /**
     * The transported reference direction at the station, or null where the run reverses on the way to it.
     *
     * **The walk is the sweep's walk, stopped at the station.** The pieces before the station are sampled at
     * [Frames3.baseSteps] — one chord for a straight run, the cubic's own second-derivative count, the helix's
     * closed-form count — and the piece the station is on is sampled the same way up to the station's own
     * parameter, with a final partial chord landing **exactly** on it. Then the reference is carried across
     * one last step onto the piece's *analytic* tangent, which is what makes the plane's normal the curve's
     * direction rather than a chord's.
     *
     * Continuous in the stated distance, which matters because that distance is something a user drags a
     * parameter through: as the station crosses a sample point the last chord shrinks to nothing and a new
     * one of zero length is added, and transport across a zero rotation is the identity.
     */
    private fun reference(
        path: Path3,
        index: Int,
        t: Double,
        tangent: Vec3,
        up: Vec3,
        tolMm: Double,
    ): Vec3? {
        val dirs = ArrayList<Vec3>()
        var prev: Vec3? = null

        fun push(p: Vec3) {
            val q = prev
            if (q != null) {
                val d = p - q
                if (d.length() > Geom3.WELD_TOL) dirs.add(d.normalized())
            }
            prev = p
        }
        for (i in 0..index) {
            val el = path.elements[i]
            val steps = Frames3.baseSteps(el, tolMm)
            val stop = if (i == index) t else 1.0
            push(Frames3.pointAt(el, 0.0))
            for (j in 1..steps) {
                val tj = j.toDouble() / steps
                if (tj >= stop) break
                push(Frames3.pointAt(el, tj))
            }
            if (i == index) push(Frames3.pointAt(el, stop))
        }
        // a station at the very start has no chord behind it: the analytic tangent is the whole walk
        if (dirs.isEmpty()) dirs.add(tangent)
        var ref = Frames3.startReference(dirs[0], up)
        for (k in 1 until dirs.size) ref = Frames3.transport(ref, dirs[k - 1], dirs[k]) ?: return null
        return Frames3.transport(ref, dirs.last(), tangent)
    }
}
