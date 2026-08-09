package constructit.geom

/**
 * How finely a body's triangles are asked for — **a property of the picture, and never of a number the
 * drawing reports** (slice B of the responsiveness item).
 *
 * A heavy body is expensive to mesh, and while a gesture is live nobody is reading its surface: they are
 * watching it move. So the 3D view asks for a [COARSE] mesh for as long as an interaction is running, and
 * for the [FINE] one the moment it settles. Both are memoized on the immutable value ([Solid3.meshAt]), so
 * this changes *how finely* triangles are built and never *whether the value is the same value* — purity is
 * untouched for exactly the reason the deferral's own note gives: a [Feature3] is enough to rebuild either
 * mesh, so the same solid always yields the same two meshes, however late and however often either is asked
 * for.
 *
 * **Quality enters through exactly one door, and this is what makes the law enforceable rather than
 * merely stated.** [Solid3.meshAt] is the only function anywhere that takes a quality. Every number the
 * drawing reports — a volume, an extent, a section, a boolean, an export, watertightness — reads
 * `Solid3.mesh`, which *is* `meshAt(FINE)`, so none of them can see a coarse triangle without a call that
 * does not exist. And every number a **feature** yields — a swept body's plan hint and therefore its 2D
 * pick target ([Silhouette.ofSwept]), a station's transported frame ([Stations3]), every refusal — is
 * computed at evaluation time from the feature at the one fine rule and never passes through that door at
 * all. So a picture's quality cannot move a pick target, a construction input or a refusal, structurally
 * rather than by audit. A volume readout during a gesture therefore shows the **stale fine** number rather
 * than a fresh coarse one, which is the law's own preference.
 *
 * **What coarse means geometrically**: the chord tolerance that [GeomMath.effectiveTol] hands out,
 * multiplied by [coarsen]. It is the one chokepoint session 56's scale-relative rule already funnels
 * through, so a coarse mesh is coarsened by the *same* factor at every radius — the scale invariance that
 * rule bought is not spent here. What it does **not** touch is stated at [Solid3.meshAt].
 */
enum class MeshQuality(
    /**
     * How many times the fine chord tolerance this quality allows — see [GeomMath.effectiveTol].
     *
     * **Ten is pinned by an argument, not guessed**, in the same spirit as [GeomMath.REL_TOL]. A chord count
     * goes as `1/sqrt(tol)`, so one order of magnitude is very nearly a **third** of the chords on every
     * curve, and about a **tenth** of the triangles on a body curved in two directions (a turned part, a
     * revolve). Less than that does not pay for a second mesh at all; much more than that stops being the
     * same body's picture. It is deliberately *not* derived from the camera: a tolerance that varied
     * continuously with the zoom would be a third quality on every frame, and the memo has two levels
     * because a gesture has two states — running, and settled.
     *
     * Coarse is honest about what it is. It does not claim to be invisible: at ten times the tolerance a
     * tube of radius 3 mm is a nine-sided prism rather than a twenty-eight-sided one, which is recognisably
     * faceted and is *on screen only while the pointer is moving*. The settled picture is the exact one.
     */
    val coarsen: Double,
) {
    /**
     * The mesh every number is read from, and the mesh every picture settles to. `Solid3.mesh` is this one,
     * so a consumer that says nothing gets it — the law is the default.
     */
    FINE(1.0),

    /** The mesh a live interaction draws, and nothing else ever reads. */
    COARSE(10.0),
}
