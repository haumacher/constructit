package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.Construction
import constructit.dsl.RegionRef
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.geom.Blend3
import constructit.geom.BlendKind
import constructit.geom.EdgeName
import constructit.geom.Geom3
import constructit.geom.GeomMath
import constructit.geom.Section3
import constructit.geom.SolidFace
import constructit.units.deg
import constructit.units.mm
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **The edge blend as a construction** (session 71, slice 2) — the kernel half: the 2D fillet run in the
 * edge's normal section, swept along the edge, applied by a boolean.
 *
 * The assertions are volumes, because a volume is the one number that cannot be right by accident: a convex
 * 90° edge of straight length `L` loses exactly `(1 − π/4)·r²·L` to a quarter-round fillet and `c²·L/2` to a
 * 45° chamfer of setback `c`, and those are the numbers a machinist would compute.
 *
 * **The tolerance is stated as a model rather than tuned as a percentage** ([assertQuarterRound]): the result
 * takes the mesh route, so the fillet arc reaches the general engine as an *inscribed* chord polygon at
 * `GeomMath.TESS_TOL_MM` (0.02 mm) and the wedge it bounds is therefore a little **larger** than the true one
 * — by about `π·r·t/3` per millimetre of edge, which is 2.3% of a 4 mm round and shrinks as the radius grows.
 * So the loss is asserted to be at least the exact figure and at most the exact figure plus that. Where a leg
 * is **curved** the base body's own surface is a chord polygon too, and the blend is then tangent to the
 * polygon rather than to the cylinder: those cases say so and take a stated band instead.
 */
class EdgeBlendTest {
    private fun Construction.rect(
        x0: Double,
        y0: Double,
        x1: Double,
        y1: Double,
        tag: String,
    ): RegionRef {
        val p0 = freePoint("${tag}0", x0.mm, y0.mm)
        val p1 = freePoint("${tag}1", x1.mm, y0.mm)
        val p2 = freePoint("${tag}2", x1.mm, y1.mm)
        val p3 = freePoint("${tag}3", x0.mm, y1.mm)
        return region(loop(segment(p0, p1), segment(p1, p2), segment(p2, p3), segment(p3, p0)))
    }

    private fun volume(
        ev: Evaluator,
        ref: SolidRef,
        what: String,
    ): Double {
        val r = ev.eval(ref.node)
        assertTrue(r is EvalResult.Ok, "$what: ${(r as? EvalResult.Invalid)?.reason}")
        val mesh = ev.solid(ref).mesh
        assertManifold(mesh, what)
        return Geom3.volume(mesh)
    }

    /** The index of one named edge in the feature's own ordered list. */
    private fun edgeIndex(
        ev: Evaluator,
        ref: SolidRef,
        name: EdgeName,
    ): Int {
        val edges = assertNotNull(Section3.edges(ev.solid(ref).feature).first, "the solid names its edges")
        val i = edges.indexOfFirst { it.name == name }
        assertTrue(i >= 0, "the solid has ${name.label}")
        return i
    }

    private fun faceIndex(
        ev: Evaluator,
        ref: SolidRef,
        label: String,
    ): Int {
        val faces = assertNotNull(Section3.faces(ev.solid(ref).feature).first, "the solid names its faces")
        val i = faces.indexOfFirst { it.name.label == label }
        assertTrue(i >= 0, "the solid has $label — it has ${faces.map { it.name.label }}")
        return i
    }

    /**
     * **What a quarter-round takes, and what the mesh route costs on top of it** — the tolerance stated as a
     * model rather than tuned as a percentage.
     *
     * The exact figure is the machinist's: a convex 90° crease of straight length `l` loses `(1 − π/4)·r²·l`.
     * The measured one is never below it and never much above it, and the difference is arithmetic nobody
     * chose: the fillet arc reaches the general engine as an **inscribed** chord polygon at
     * [GeomMath.TESS_TOL_MM], so the wedge it bounds is larger than the true one by about `π·r·t/3` per
     * millimetre of edge. Asserted as a two-sided bound — exact from below, exact plus the chords from above
     * — so the assertion says what it means and would notice a construction error of any size.
     */
    private fun assertQuarterRound(
        loss: Double,
        r: Double,
        l: Double,
        what: String,
    ) {
        val exact = (1.0 - PI / 4.0) * r * r * l
        val chords = PI * r * GeomMath.TESS_TOL_MM * l / 3.0
        assertTrue(loss >= exact - 1e-6, "$what takes at least the exact $exact mm^3 — it took $loss")
        assertTrue(
            loss <= exact + 1.5 * chords,
            "$what takes the exact $exact mm^3 plus at most the chords (${1.5 * chords} mm^3) — it took $loss",
        )
    }

    private fun blend(
        cx: Construction,
        ev: Evaluator,
        base: SolidRef,
        size: Double,
        kind: BlendKind,
        whole: Boolean,
        address: Int,
    ): SolidRef {
        val body = ev.solid(base)
        val (targets, whyT) = Blend3.targets(body.feature, whole, address)
        assertNotNull(targets, whyT)
        val (choices, why) = Blend3.choicesFor(body, targets, size, kind)
        assertNotNull(choices, why)
        return cx.blend(base, base, cx.planeXY(), cx.const(size.mm), kind, whole, address, choices)
    }

    // ---- an extrusion's cap edge: plane against plane, at 90°, along a straight edge ----

    @Test
    fun aFilletOnAnExtrusionsCapEdgeTakesTheQuarterRound() {
        val cx = Construction()
        val base = cx.extrude(cx.sketchOn(cx.planeXY(), cx.rect(0.0, 0.0, 40.0, 30.0, "p")), cx.const(20.mm))
        val ev = Evaluator()
        val before = volume(ev, base, "the plate")
        assertClose(before, 40.0 * 30.0 * 20.0, 1e-6, "the plate is 40 x 30 x 20")
        // boundary piece #0 is the 40 mm run along y = 0, and its top-cap edge is a convex 90° crease
        val i = edgeIndex(ev, base, EdgeName.CapPiece(SolidFace.TOP, 0))
        val r = 4.0
        val rounded = blend(cx, ev, base, r, BlendKind.FILLET, whole = false, address = i)
        val after = volume(Evaluator(), rounded, "the rounded plate")
        assertQuarterRound(before - after, r, 40.0, "a quarter-round of radius $r along the plate's 40 mm edge")
    }

    @Test
    fun aChamferOnTheSameEdgeTakesTheTriangle() {
        val cx = Construction()
        val base = cx.extrude(cx.sketchOn(cx.planeXY(), cx.rect(0.0, 0.0, 40.0, 30.0, "p")), cx.const(20.mm))
        val ev = Evaluator()
        val before = volume(ev, base, "the plate")
        val i = edgeIndex(ev, base, EdgeName.CapPiece(SolidFace.TOP, 0))
        val c = 4.0
        val bevelled = blend(cx, ev, base, c, BlendKind.CHAMFER, whole = false, address = i)
        val after = volume(Evaluator(), bevelled, "the bevelled plate")
        // a bevel is a triangle, so nothing here is tessellated at all and the figure is exact
        assertClose(before - after, c * c * 40.0 / 2.0, 0.01, "a 45 degree chamfer of setback $c along 40 mm")
    }

    // ---- the motivating case: a profile revolved less than a full turn ----

    private fun Construction.turnedBar(deg: Double): SolidRef {
        val o = freePoint("axisO", 0.mm, 0.mm)
        val axis = direction(o, freePoint("axisX", 1.mm, 0.mm))
        return revolve(sketchOn(planeXY(), rect(0.0, 15.0, 60.0, 25.0, "b")), o, axis, parameter("sweep", deg.deg))
    }

    @Test
    fun onePieceOfAPartialRevolvesCapEdge() {
        val cx = Construction()
        val base = cx.turnedBar(90.0)
        val ev = Evaluator()
        val before = volume(ev, base, "the quarter tube")
        // profile piece #1 is the radial run at s = 60 (from r = 15 out to r = 25): its band is a flat
        // annulus sector, so the crease is plane against plane and the arithmetic is the machinist's
        val i = edgeIndex(ev, base, EdgeName.RevolveCapPiece(SolidFace.TOP, 1))
        val r = 2.0
        val rounded = blend(cx, ev, base, r, BlendKind.FILLET, whole = false, address = i)
        val after = volume(Evaluator(), rounded, "the rounded quarter tube")
        assertQuarterRound(before - after, r, 10.0, "a quarter-round of radius $r along the cap's 10 mm wall")
    }

    @Test
    fun everyPieceOfOneCapEdgeInOneGesture() {
        val cx = Construction()
        val base = cx.turnedBar(90.0)
        val ev = Evaluator()
        val before = volume(ev, base, "the quarter tube")
        val f = faceIndex(ev, base, "the cap at the end of the sweep")
        val rounded = blend(cx, ev, base, 2.0, BlendKind.FILLET, whole = true, address = f)
        val after = volume(Evaluator(), rounded, "the rounded quarter tube")
        assertTrue(after < before, "rounding four convex edges takes material away: $after vs $before")
        // the four wedges overlap at the corners, so the loss is below their sum and above the biggest one
        val single = (1.0 - PI / 4.0) * 4.0 * 60.0
        assertTrue(before - after > single * 0.9, "and it is at least the long edge's own quarter-round")
    }

    // ---- the collapsed cases: an upright and a ring are this same construction, one profile in ----

    /**
     * **An extrusion's upright is the outline's own fillet**, reached through the identical route: the plane
     * normal to the edge *is* the sketch plane there, so the two traces are the two boundary pieces' carriers
     * and the arc that lands between them is what the 2D Fillet tool would have drawn on the profile.
     *
     * Built rather than refused, deliberately (see DESIGN.md, session 71 slice 2): the generality claim is
     * that the already-easy case is this one collapsed, and a route that declined it would be claiming
     * something it had not done. What filleting the *outline* first still buys is exactness — that stays on
     * the analytic path, while this takes the mesh route like every other blend in this slice.
     */
    @Test
    fun anExtrusionsUprightIsTheSameConstruction() {
        val cx = Construction()
        val base = cx.extrude(cx.sketchOn(cx.planeXY(), cx.rect(0.0, 0.0, 40.0, 30.0, "p")), cx.const(20.mm))
        val ev = Evaluator()
        val before = volume(ev, base, "the plate")
        val i = edgeIndex(ev, base, EdgeName.Upright(0))
        val r = 4.0
        val rounded = blend(cx, ev, base, r, BlendKind.FILLET, whole = false, address = i)
        val after = volume(Evaluator(), rounded, "the plate with a rounded corner")
        assertQuarterRound(before - after, r, 20.0, "a quarter-round of radius $r up the plate's 20 mm corner")
    }

    /**
     * **A concave edge is added to, not taken from** — and the arithmetic is the same wedge with the other
     * sign, which is what makes *"subtract on a convex edge, add on a concave one"* one rule rather than two
     * cases. The reflex corner of an L is where the material fills three of the crease's four sides, so the
     * blend fills the fourth.
     */
    @Test
    fun theReflexCornerOfAnLIsFilledIn() {
        val cx = Construction()
        val a = cx.freePoint("a", 0.mm, 0.mm)
        val b = cx.freePoint("b", 60.mm, 0.mm)
        val c = cx.freePoint("c", 60.mm, 20.mm)
        val d = cx.freePoint("d", 20.mm, 20.mm)
        val e = cx.freePoint("e", 20.mm, 50.mm)
        val f = cx.freePoint("f", 0.mm, 50.mm)
        val ell =
            cx.region(
                cx.loop(
                    cx.segment(a, b),
                    cx.segment(b, c),
                    cx.segment(c, d),
                    cx.segment(d, e),
                    cx.segment(e, f),
                    cx.segment(f, a),
                ),
            )
        val base = cx.extrude(cx.sketchOn(cx.planeXY(), ell), cx.const(10.mm))
        val ev = Evaluator()
        val before = volume(ev, base, "the L")
        // the upright at the start of piece #3 (d -> e) stands where pieces #2 and #3 meet: the reflex corner
        val i = edgeIndex(ev, base, EdgeName.Upright(3))
        val r = 3.0
        val filled = blend(cx, ev, base, r, BlendKind.FILLET, whole = false, address = i)
        val after = volume(Evaluator(), filled, "the L with its inside corner filled")
        assertTrue(after > before, "an inside corner gains material: $after vs $before")
        assertQuarterRound(after - before, r, 10.0, "the fillet run into the L's 10 mm inside corner")
    }

    // ---- a closed, tangent-continuous chain: an extruded circle's rim ----

    /**
     * **A whole rim in one gesture, with no crack in it**: the cap edge of an extruded circle is a single
     * closed piece, so the wedge is swept round a closed path and the result is one continuous torus-quadrant
     * of removed material.
     *
     * The exact figure is Pappus': the wedge has area `(1 − π/4)ρ²` and its centroid stands
     * `ρ(5/6 − π/4)/(1 − π/4)` in from the rim, so the volume removed is
     * `2π·ρ²·[R(1 − π/4) − ρ(5/6 − π/4)]`. It is asserted within **4%** rather than to the chord model,
     * because here the *body's* own wall is a chord polygon as well: the blend is tangent to the polygon and
     * not to the cylinder, and that error is about `t/((1 − π/4)ρ)` of the figure.
     */
    @Test
    fun theRimOfAnExtrudedCircleBlendsAsOneClosedRun() {
        val cx = Construction()
        val o = cx.freePoint("o", 0.mm, 0.mm)
        val disc = cx.region(cx.loop(cx.circleCR(o, cx.const(25.mm))))
        val base = cx.extrude(cx.sketchOn(cx.planeXY(), disc), cx.const(30.mm))
        val ev = Evaluator()
        val before = volume(ev, base, "the cylinder")
        val i = edgeIndex(ev, base, EdgeName.CapPiece(SolidFace.TOP, 0))
        val rho = 6.0
        val rounded = blend(cx, ev, base, rho, BlendKind.FILLET, whole = false, address = i)
        val after = volume(Evaluator(), rounded, "the cylinder with a rounded rim")
        val exact = 2.0 * PI * rho * rho * (25.0 * (1.0 - PI / 4.0) - rho * (5.0 / 6.0 - PI / 4.0))
        assertClose(before - after, exact, exact * 0.04, "Pappus on the rim's quarter-round")
    }

    // ---- what refuses, and what heals ----

    @Test
    fun aRadiusThatOutgrowsTheWallRefusesByNameAndHealsWhenItShrinks() {
        val cx = Construction()
        val base = cx.extrude(cx.sketchOn(cx.planeXY(), cx.rect(0.0, 0.0, 40.0, 30.0, "p")), cx.const(6.mm))
        val ev = Evaluator()
        val body = ev.solid(base)
        val i = edgeIndex(ev, base, EdgeName.CapPiece(SolidFace.TOP, 0))
        val (targets, _) = Blend3.targets(body.feature, false, i)
        val choices = assertNotNull(Blend3.choicesFor(body, targets!!, 10.0, BlendKind.FILLET).first)
        val (tooBig, why) = Blend3.blended(body, body, targets, 10.0, BlendKind.FILLET, choices)
        assertTrue(tooBig == null, "a 10 mm round cannot be had on a 6 mm plate")
        val said = assertNotNull(why)
        assertTrue(said.contains("boundary edge #1 of the top face"), "the refusal names the edge: $said")
        assertTrue(said.contains("the face over boundary edge #1"), "and the leg it reaches past: $said")
        assertTrue(said.contains("largest that fits"), "and what to type instead: $said")
        // …and it heals the moment the radius comes down (OP-3), with the very same stored choice
        val (ok, whyOk) = Blend3.blended(body, body, targets, 2.0, BlendKind.FILLET, choices)
        assertNotNull(ok, whyOk)
        assertManifold(ok.mesh, "the healed blend")
    }

    /**
     * **The one thing this slice does not reach, refused by name.** A revolve's cap edge over a *slanted*
     * profile piece stands against a **cone**: the plane square to the edge cuts that cone in a conic, and
     * the true blend's spine is not the edge offset at all, so there is no rigid section to sweep. Recorded as
     * a future extension rather than approximated — and it declines out loud.
     */
    @Test
    fun aRevolvesCapEdgeOverASlantedPieceRefusesByName() {
        val cx = Construction()
        val o = cx.freePoint("axisO", 0.mm, 0.mm)
        val axis = cx.direction(o, cx.freePoint("axisX", 1.mm, 0.mm))
        val p0 = cx.freePoint("q0", 0.mm, 10.mm)
        val p1 = cx.freePoint("q1", 40.mm, 10.mm)
        val p2 = cx.freePoint("q2", 40.mm, 30.mm)
        val p3 = cx.freePoint("q3", 0.mm, 20.mm)
        val cone =
            cx.region(cx.loop(cx.segment(p0, p1), cx.segment(p1, p2), cx.segment(p2, p3), cx.segment(p3, p0)))
        val base = cx.revolve(cx.sketchOn(cx.planeXY(), cone), o, axis, cx.parameter("sweep", 120.0.deg))
        val ev = Evaluator()
        val body = ev.solid(base)
        assertManifold(body.mesh, "the tapered sector")
        // piece #2 runs from (40, 30) to (0, 20): slanted, so its band is a cone
        val i = edgeIndex(ev, base, EdgeName.RevolveCapPiece(SolidFace.TOP, 2))
        val (targets, _) = Blend3.targets(body.feature, false, i)
        val (choices, why) = Blend3.choicesFor(body, targets!!, 2.0, BlendKind.FILLET)
        assertTrue(choices == null, "the cone's cap edge is not a rigid section")
        val said = assertNotNull(why)
        assertTrue(said.contains("a cone"), "the refusal names the surface: $said")
        assertTrue(said.contains("conic"), "and why it cannot be said: $said")
        assertTrue(said.contains("future extension"), "and that it is a future extension: $said")
    }

    /**
     * A chamfer across a leg that is **curved in section** stays refused — the parked 2D chamfer-on-arc
     * convention, inherited unchanged.
     *
     * The leg here is a real circle rather than a contrivance: a partial revolve's **cap edge over an
     * axis-parallel profile piece** stands against a cylinder cut square to its own axis, which is that
     * cylinder's own circle. The fillet takes it (the mixed line–circle construction, the 2D tool's own); the
     * chamfer says which leg is curved and points at the fillet.
     */
    @Test
    fun aChamferAcrossACurvedLegPointsAtTheFillet() {
        val cx = Construction()
        val turned = cx.turnedBar(90.0)
        val ev = Evaluator()
        val body = ev.solid(turned)
        // piece #0 runs along the axis at r = 15 (the bore), so on the cap it meets a cylinder side-on
        val i = edgeIndex(ev, turned, EdgeName.RevolveCapPiece(SolidFace.TOP, 0))
        val (targets, _) = Blend3.targets(body.feature, false, i)
        val bevel = assertNotNull(Blend3.choicesFor(body, targets!!, 2.0, BlendKind.CHAMFER).first)
        val (out, why) = Blend3.blended(body, body, targets, 2.0, BlendKind.CHAMFER, bevel)
        assertTrue(out == null, "a chamfer needs two straight legs")
        val said = assertNotNull(why)
        assertTrue(said.contains("fillet it instead"), "and it points at the fillet: $said")
        // …and the fillet across the very same crease is built, which is what makes that a real alternative
        val round = assertNotNull(Blend3.choicesFor(body, targets, 2.0, BlendKind.FILLET).first)
        val (rounded, whyRound) = Blend3.blended(body, body, targets, 2.0, BlendKind.FILLET, round)
        assertNotNull(rounded, whyRound)
        assertManifold(rounded.mesh, "the rounded bore mouth")
        assertTrue(Geom3.volume(rounded.mesh) < Geom3.volume(body.mesh), "and it takes material away")
    }

    /** A body the mesh engine made has no named edges, so a blend on it declines in [Section3]'s own words. */
    @Test
    fun aMeshOnlyBodyHasNoEdgesToBlend() {
        val cx = Construction()
        val plate = cx.extrude(cx.sketchOn(cx.planeXY(), cx.rect(0.0, 0.0, 40.0, 30.0, "p")), cx.const(20.mm))
        val bar = cx.turnedBar(90.0)
        val fused = cx.union(plate, bar)
        val ev = Evaluator()
        val body = ev.solid(fused)
        assertManifold(body.mesh, "the fused body")
        val (targets, why) = Blend3.targets(body.feature, false, 0)
        assertTrue(targets == null, "a mesh boolean's result names no edges")
        assertTrue(assertNotNull(why).contains("mesh-only"), "and says so: $why")
    }
}
