package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.ChainRef
import constructit.dsl.Construction
import constructit.dsl.Path3Ref
import constructit.dsl.Point3Ref
import constructit.dsl.RegionRef
import constructit.dsl.SolidRef
import constructit.dsl.resultOf
import constructit.dsl.scalar
import constructit.dsl.solid
import constructit.geom.CarryMode
import constructit.geom.Feature3
import constructit.geom.Geom3
import constructit.geom.MeshBool
import constructit.geom.Vec3
import constructit.units.mm
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * **The swept cut** (OP-22's extension, step 2) — the engine half.
 *
 * Step 1 cut with a chain carried straight through the target along its space's normal. Here the second
 * operand is a general curve in space, and with it the one discrete **mode**: the section either rides the
 * moving frame (rotating) or stays parallel to the space it was drawn in (translational). The straight case
 * is not a different operator but this one's degenerate directrix, which is asserted as such rather than
 * described.
 *
 * Every solid produced anywhere is [assertManifold]ed. A swept tool has no prismatic reading, so every cut
 * below goes through the general boolean engine (OP-9) and the tests skip where it is unavailable.
 */
class SweptCutTest {
    // ---- the fixtures ----

    /** A box `[0,w] × [0,d] × [0,h]`, extruded from the plan. */
    private fun Construction.block(
        w: Double,
        d: Double,
        h: Double,
        tag: String = "b",
    ): SolidRef {
        val a = freePoint("$tag.a", 0.mm, 0.mm)
        val b = freePoint("$tag.b", w.mm, 0.mm)
        val c = freePoint("$tag.c", w.mm, d.mm)
        val e = freePoint("$tag.d", 0.mm, d.mm)
        return extrude(sketchOn(planeXY(), region(loop(segment(a, b), segment(b, c), segment(c, e), segment(e, a)))), const(h.mm))
    }

    /** A point in space, stated the way the drawing states one: a plan point lifted off the plan (OP-25). */
    private fun Construction.at(
        x: Double,
        y: Double,
        z: Double,
        tag: String,
    ): Point3Ref = heightPoint(planeXY(), freePoint(tag, x.mm, y.mm), const(z.mm))

    /** The route the cut is carried along, through the points given. */
    private fun Construction.route(
        vararg xyz: Triple<Double, Double, Double>,
        smooth: Boolean = false,
        closed: Boolean = false,
        tag: String = "r",
    ): Path3Ref =
        pathThrough(xyz.mapIndexed { i, p -> at(p.first, p.second, p.third, "$tag$i") }, closed = closed, smooth = smooth)

    /**
     * A route that runs straight up the middle of a 60 × 40 block with one **wiggle** in it: an apex [bump]
     * mm to the side at height [z], with its neighbours 20 mm either side of it so the bend's radius is the
     * bump's own business and not the knot spacing's.
     *
     * The same shape at two heights is what the bounded-reach criterion is proved with: inside the block it
     * folds the cutting surface, 280 mm above it nothing about it matters.
     */
    private fun Construction.wiggle(
        z: Double,
        bump: Double,
        tag: String,
    ): Path3Ref =
        route(
            Triple(30.0, 20.0, -40.0),
            Triple(30.0, 20.0, z - 20.0),
            Triple(30.0 + bump, 20.0, z),
            Triple(30.0, 20.0, z + 20.0),
            Triple(30.0, 20.0, z + 60.0),
            smooth = true,
            tag = tag,
        )

    /** A square of side `2·half` centred on the space origin, as an area. */
    private fun Construction.squareArea(
        half: Double,
        tag: String,
    ): RegionRef {
        val a = freePoint("$tag.a", (-half).mm, (-half).mm)
        val b = freePoint("$tag.b", half.mm, (-half).mm)
        val c = freePoint("$tag.c", half.mm, half.mm)
        val d = freePoint("$tag.d", (-half).mm, half.mm)
        return region(loop(segment(a, b), segment(b, c), segment(c, d), segment(d, a)))
    }

    /** …and the same square as a **closed chain**: the channel a swept cut takes out of a body. */
    private fun Construction.squareChain(
        half: Double,
        tag: String,
    ): ChainRef = closedChain(squareArea(half, tag))

    private fun volumeOf(
        ev: Evaluator,
        s: SolidRef,
        what: String,
    ): Double {
        assertTrue(ev.resultOf(s) is EvalResult.Ok, "$what should build: ${(ev.resultOf(s) as? EvalResult.Invalid)?.reason}")
        val solid = ev.solid(s)
        assertManifold(solid.mesh, what)
        return Geom3.volume(solid.mesh)
    }

    private fun reasonOf(
        ev: Evaluator,
        s: SolidRef,
    ): String = (ev.resultOf(s) as? EvalResult.Invalid)?.reason ?: "«valid»"

    // ---- 1. a curved channel, against a figure computed from the route itself ----

    /**
     * **A closed chain carried along a bent route takes a channel out of a block**, and the volume it removes
     * is stated rather than measured: a section whose own centroid sits on the path sweeps exactly
     * `area × arc length`, because the Jacobian of the sweep is linear in the section's coordinates and the
     * first moment of a centred section is zero — which also makes the mitre at the corner and the oblique
     * faces the channel exits through volume-neutral, each for the same reason.
     *
     * The arc length is the one between the two points where the **route** crosses the block's faces, and it
     * is computed here from the coordinates the route was stated with, not read back out of the mesh.
     */
    @Test
    fun aChainSweptAlongABentRouteTakesAChannelOutOfABlock() {
        if (!MeshBool.available) return
        val c = Construction()
        val block = c.block(80.0, 60.0, 30.0)
        // the route: in along +y at x = 40, then away at an angle, both at mid-height
        val route = c.route(Triple(40.0, -40.0, 15.0), Triple(40.0, 20.0, 15.0), Triple(100.0, 50.0, 15.0))
        val chain = c.squareChain(6.0, "sq")
        val cut = c.splitSolid(block, chain, c.planeXZ(), -1, route, CarryMode.ROTATING)

        // where the route crosses the block: in through y = 0 at (40, 0, 15), out through x = 80
        val leg2 = sqrt(60.0 * 60.0 + 30.0 * 30.0)
        val exitAt = 40.0 / 60.0 * leg2
        val length = 20.0 + exitAt
        val removed = 12.0 * 12.0 * length

        val ev = Evaluator()
        val whole = volumeOf(ev, block, "the block")
        assertClose(whole, 80.0 * 60.0 * 30.0, tol = 1e-6)
        assertClose(
            volumeOf(ev, cut, "the channelled block"),
            whole - removed,
            tol = 1e-3 * whole,
            msg = "a centred section sweeps area × arc length, mitre and oblique exits included",
        )
        assertTrue(
            ev.solid(cut).feature is Feature3.MeshBoolean,
            "a swept tool shares an axis with nothing, so the cut is a general boolean and says so (OP-9)",
        )
    }

    /** The split is still a partition: the two halves of a swept cut are the whole solid between them. */
    @Test
    fun theTwoHalvesOfASweptSplitAreTheWholeSolid() {
        if (!MeshBool.available) return
        val c = Construction()
        val block = c.block(80.0, 60.0, 30.0)
        val route = c.route(Triple(40.0, -40.0, 15.0), Triple(40.0, 20.0, 15.0), Triple(100.0, 50.0, 15.0))
        val chain = c.squareChain(6.0, "sq")
        val inside = c.splitSolid(block, chain, c.planeXZ(), 1, route, CarryMode.ROTATING)
        val outside = c.splitSolid(block, chain, c.planeXZ(), -1, route, CarryMode.ROTATING)

        val ev = Evaluator()
        val whole = volumeOf(ev, block, "the block")
        assertClose(
            volumeOf(ev, inside, "the channel itself") + volumeOf(ev, outside, "the channelled block"),
            whole,
            tol = 1e-3 * whole,
            msg = "the derived section covers the whole target: nothing of the block falls outside both halves",
        )
    }

    // ---- 2. the straight case is the degenerate directrix, and it is the same code ----

    /**
     * **A route running along the chain space's own normal *is* the straight cut**, and it takes the straight
     * cut's code — so the result is not close to step 1's, it is the identical mesh, vertex for vertex. That
     * is the strongest form the claim can take: the straight case cannot drift, because there is nothing to
     * drift.
     *
     * The two carry modes coincide there for the same reason, which is why the mode arrives with the curved
     * directrix and not before.
     */
    @Test
    fun aStraightRouteAlongTheNormalIsExactlyTheStepOneCut() {
        val c = Construction()
        val block = c.block(80.0, 50.0, 20.0)
        val chain =
            c.chainThrough(
                listOf(c.freePoint("c0", (-10.0).mm, 20.mm), c.freePoint("c1", 90.mm, 20.mm)),
            )
        val straight = c.splitSolid(block, chain, c.planeXY(), 1)
        val route = c.route(Triple(40.0, 25.0, -10.0), Triple(40.0, 25.0, 60.0))
        val along = c.splitSolid(block, chain, c.planeXY(), 1, route, CarryMode.ROTATING)
        val alongFlat = c.splitSolid(block, chain, c.planeXY(), 1, route, CarryMode.TRANSLATIONAL)

        val ev = Evaluator()
        val a = ev.solid(straight).mesh
        val b = ev.solid(along).mesh
        val d = ev.solid(alongFlat).mesh
        assertManifold(b, "the cut along a straight route")
        assertClose(Geom3.volume(a), 80.0 * 30.0 * 20.0, tol = 1e-6)
        assertEqualMesh(a, b, "the degenerate directrix is the straight cut, not an approximation of it")
        assertEqualMesh(a, d, "and the two carry modes coincide exactly where the route is straight")
        assertTrue(ev.solid(along).feature is Feature3.Prism, "so it keeps the exact prismatic path (OP-22)")
    }

    /** …and a route stated the other way round through the same plane cuts the same body, not its mirror. */
    @Test
    fun aRouteTravelledTheOtherWayCutsTheSameBody() {
        val c = Construction()
        val block = c.block(80.0, 50.0, 20.0)
        val chain =
            c.chainThrough(
                listOf(c.freePoint("c0", (-10.0).mm, 20.mm), c.freePoint("c1", 90.mm, 20.mm)),
            )
        val up = c.splitSolid(block, chain, c.planeXY(), 1, c.route(Triple(40.0, 25.0, -10.0), Triple(40.0, 25.0, 60.0), tag = "u"), CarryMode.ROTATING)
        val down = c.splitSolid(block, chain, c.planeXY(), 1, c.route(Triple(40.0, 25.0, 60.0), Triple(40.0, 25.0, -10.0), tag = "d"), CarryMode.ROTATING)
        val ev = Evaluator()
        assertClose(volumeOf(ev, up, "the cut along the rising route"), 80.0 * 30.0 * 20.0, tol = 1e-6)
        assertClose(
            volumeOf(ev, down, "the cut along the falling route"),
            80.0 * 30.0 * 20.0,
            tol = 1e-6,
            msg = "which way the route was drawn is not a statement about which half is kept",
        )
    }

    // ---- 3. the two modes differ, and each is right ----

    /**
     * **The two modes on one oblique route, each against its own closed form.**
     *
     * The route is a straight line at 45° through a plate, so both modes are ordinary prisms and both
     * volumes can be written down:
     *
     * - **translational** — the section stays parallel to the plate, so the hole's *horizontal* section is
     *   the square as drawn and the volume is `area × thickness`, whatever the route's angle;
     * - **rotating** — the section stands square to the route, so its horizontal section is the square
     *   stretched by `1/cos θ` and the volume is `area × thickness / cos θ`.
     *
     * That ratio *is* the difference between the modes — the orientation of the section, stated as a number
     * rather than described — and 41 % is not a difference any tolerance could account for.
     */
    @Test
    fun theTwoModesCutDifferentBodiesAndEachMatchesItsOwnClosedForm() {
        if (!MeshBool.available) return
        val c = Construction()
        val plate = c.block(100.0, 100.0, 20.0)
        // 45° in the xz plane, through the plate's middle
        val route = c.route(Triple(20.0, 50.0, -30.0), Triple(80.0, 50.0, 30.0))
        val chain = c.squareChain(10.0, "sq")
        val flat = c.splitSolid(plate, chain, c.planeXY(), -1, route, CarryMode.TRANSLATIONAL)
        val turned = c.splitSolid(plate, chain, c.planeXY(), -1, route, CarryMode.ROTATING)

        val ev = Evaluator()
        val whole = 100.0 * 100.0 * 20.0
        val area = 20.0 * 20.0
        assertClose(
            volumeOf(ev, flat, "the plate cut translationally"),
            whole - area * 20.0,
            tol = 1e-3 * whole,
            msg = "carried flat, the hole's plan section is the square itself",
        )
        assertClose(
            volumeOf(ev, turned, "the plate cut on the frame"),
            whole - area * 20.0 * sqrt(2.0),
            tol = 1e-3 * whole,
            msg = "carried on the frame, the section stands square to the route and its plan section is stretched by 1/cos 45°",
        )
    }

    /**
     * **…and on a *bent* route, where each mode has a different closed form, both of them exact.**
     *
     * Translational: every section lies in a plane parallel to the chain's own space, so the body is a stack
     * of slabs and the volume is `area × the rise through that space` — the lateral wandering of the route
     * cannot add to it. Rotating: the section stays square to the run, so the volume is `area × arc length`.
     * The two are equal only where the run is normal to the chain's plane, and the ratio here is the
     * `1/cos θ` of the two legs.
     */
    @Test
    fun onABentRouteEachModeMatchesItsOwnExactFigure() {
        if (!MeshBool.available) return
        val c = Construction()
        val block = c.block(60.0, 40.0, 30.0)
        // a route that leans in and then straightens, both bends inside the block's own height
        val route = c.route(Triple(5.0, 20.0, -20.0), Triple(30.0, 20.0, 15.0), Triple(30.0, 20.0, 50.0))
        val chain = c.squareChain(8.0, "sq")
        val flat = c.splitSolid(block, chain, c.planeXY(), -1, route, CarryMode.TRANSLATIONAL)
        val turned = c.splitSolid(block, chain, c.planeXY(), -1, route, CarryMode.ROTATING)

        val whole = 60.0 * 40.0 * 30.0
        val area = 16.0 * 16.0
        // the leaning leg crosses z = 0 at x = 5 + 25·(20/35); from there to the bend, then straight up
        val enter = 5.0 + 25.0 * (20.0 / 35.0)
        val arc = sqrt((30.0 - enter) * (30.0 - enter) + 15.0 * 15.0) + 15.0

        val ev = Evaluator()
        assertClose(
            volumeOf(ev, flat, "the block cut translationally"),
            whole - area * 30.0,
            tol = 1e-3 * whole,
            msg = "a stack of parallel slabs removes area × rise, however the route wanders sideways",
        )
        assertClose(
            volumeOf(ev, turned, "the block cut on the frame"),
            whole - area * arc,
            tol = 1e-3 * whole,
            msg = "a section square to the run removes area × arc length",
        )
        assertTrue(
            volumeOf(ev, flat, "flat") - volumeOf(ev, turned, "turned") > 500.0,
            "and the two are different bodies, by more than a tenth of what either removes",
        )
    }

    // ---- 4. the route is unbounded: how far it was drawn is not part of the answer ----

    /**
     * **The route runs on out of both its ends, so lengthening it changes nothing.**
     *
     * One route stops halfway inside the block and the other runs well clear of it on both sides; they are
     * otherwise the same line, and they cut the same body. That is the whole reason the directrix had to be
     * unboundable: a finite one would have reintroduced *"draw it far enough"*, which is the guess this
     * operator exists to remove.
     */
    @Test
    fun lengtheningTheRouteDoesNotChangeTheCut() {
        if (!MeshBool.available) return
        val c = Construction()
        val block = c.block(80.0, 60.0, 30.0)
        val chain = c.squareChain(6.0, "sq")
        // both routes lie on the same line at 20° out of the vertical; the first stops inside the block
        val short = c.route(Triple(30.0, 30.0, 5.0), Triple(40.0, 30.0, 20.0), tag = "s")
        val long = c.route(Triple(20.0, 30.0, -10.0), Triple(60.0, 30.0, 50.0), tag = "l")
        val a = c.splitSolid(block, chain, c.planeXY(), -1, short, CarryMode.ROTATING)
        val b = c.splitSolid(block, chain, c.planeXY(), -1, long, CarryMode.ROTATING)

        val ev = Evaluator()
        val va = volumeOf(ev, a, "the cut by the route that stops inside the block")
        val vb = volumeOf(ev, b, "the cut by the route drawn clear of it")
        assertTrue(va < 80.0 * 60.0 * 30.0 - 1000.0, "the short route still cuts right through: $va")
        assertClose(va, vb, tol = 1e-3 * vb, msg = "how far the route was drawn is not part of the answer")
    }

    // ---- 5. the embedding criterion, in its bounded-reach form ----

    /**
     * **A route that folds inside the solid is refused by name; the same fold outside it is not.**
     *
     * This pair is what proves the reach is *derived* rather than global. An unbounded chain has infinite
     * reach, so the sweep's own criterion (`reach × curvature ≥ 1`) would refuse every bend there is; the
     * effective reach is instead the distance to the far edge of the target's extent, and the run is clipped
     * to the stations whose sections can touch it. So the identical bend is a refusal where the material is
     * and no one's business where it is not — and that is not leniency: a surface that does not meet itself
     * over the solid's extent is exactly a surface for which *"which side"* has an answer there.
     */
    @Test
    fun aFoldInsideTheSolidIsRefusedAndTheSameFoldOutsideItIsNot() {
        if (!MeshBool.available) return
        val inside = Construction()
        val blockIn = inside.block(60.0, 40.0, 40.0)
        val refused = inside.splitSolid(blockIn, inside.squareChain(5.0, "sq"), inside.planeXY(), -1, inside.wiggle(20.0, 18.0, "t"), CarryMode.ROTATING)
        val why = reasonOf(Evaluator(), refused)
        assertTrue(
            why.contains("the cut's reach across this solid") || why.contains("passes within"),
            "the fold is named as one, and the reach it is measured against is the solid's: $why",
        )
        assertTrue(why.contains("cutting surface"), "and it is the cutting surface that is spoken about: $why")

        // the *same* bend, 280 mm further along the route: what folds is now outside everything that matters,
        // the run is clipped before it, and what is left through the block is the straight channel
        val outside = Construction()
        val blockOut = outside.block(60.0, 40.0, 40.0)
        val accepted =
            outside.splitSolid(blockOut, outside.squareChain(5.0, "sq"), outside.planeXY(), -1, outside.wiggle(300.0, 18.0, "p"), CarryMode.ROTATING)
        val ev = Evaluator()
        assertClose(
            volumeOf(ev, accepted, "the cut whose route folds well clear of the block"),
            60.0 * 40.0 * 40.0 - 100.0 * 40.0,
            tol = 1e-3 * 96000.0,
            msg = "through the block the route is straight, so the cut is the straight 10 × 10 channel — the fold beyond it is nobody's business",
        )
    }

    /**
     * **The reach is the *target's*, not the profile's** — the same route and the same chain, refused over a
     * body and accepted over a smaller one.
     *
     * Nothing about the cutting surface changed between the two: the bend is the same bend and the chain is
     * the same chain. What changed is how far the surface has to reach to span the body, which is the whole
     * of the bounded-reach form — and it is why an unbounded chain has a criterion at all instead of failing
     * one that reads `κ · ∞ ≥ 1`.
     */
    @Test
    fun theSameBendIsRefusedOverABodyAndAcceptedOverASmallerOne() {
        if (!MeshBool.available) return
        val c = Construction()
        // a block whose own corners are draggable, so the *only* thing that changes below is the body
        val a = c.freePoint("k.a", 0.mm, 0.mm)
        val b = c.freePoint("k.b", 60.mm, 0.mm)
        val d = c.freePoint("k.c", 60.mm, 40.mm)
        val e = c.freePoint("k.d", 0.mm, 40.mm)
        val block =
            c.extrude(
                c.sketchOn(c.planeXY(), c.region(c.loop(c.segment(a, b), c.segment(b, d), c.segment(d, e), c.segment(e, a)))),
                c.const(40.mm),
            )
        val cut = c.splitSolid(block, c.squareChain(5.0, "sq"), c.planeXY(), -1, c.wiggle(20.0, 3.0, "w"), CarryMode.ROTATING)

        val why = reasonOf(Evaluator(), cut)
        val reach = Regex("the cut's reach across this solid \\(([0-9.]+) mm\\)").find(why)?.groupValues?.get(1)?.toDouble()
        assertTrue(reach != null, "the refusal names the reach it measured: $why")
        assertTrue(
            reach!! > sqrt(30.0 * 30.0 + 20.0 * 20.0) && reach < 50.0,
            "and that reach is the far corner of *this body* seen from the run, not a property of the chain: $reach mm",
        )

        // …now pull the block's own corners in around the same route. Nothing about the cutting surface
        // changed — the bend is the same bend — and the cut builds, because the fold is no longer inside
        // anything that matters.
        c.set(a, 20.mm, 10.mm)
        c.set(b, 40.mm, 10.mm)
        c.set(d, 40.mm, 30.mm)
        c.set(e, 20.mm, 30.mm)
        val ev = Evaluator()
        val v = volumeOf(ev, cut, "the same cut over a smaller body")
        assertTrue(v > 0.0 && v < 20.0 * 20.0 * 40.0, "which builds, and still takes the channel out: $v")
    }

    /** …and the refusal heals: open the bend out and the same construction builds (OP-3). */
    @Test
    fun theFoldRefusalHealsWhenTheBendIsOpenedOut() {
        if (!MeshBool.available) return
        val c = Construction()
        val block = c.block(60.0, 40.0, 40.0)
        val apex = c.freePoint("apex", 48.mm, 20.mm)
        val route =
            c.pathThrough(
                listOf(
                    c.at(30.0, 20.0, -40.0, "q0"),
                    c.at(30.0, 20.0, 0.0, "q1"),
                    c.heightPoint(c.planeXY(), apex, c.const(20.mm)),
                    c.at(30.0, 20.0, 40.0, "q3"),
                    c.at(30.0, 20.0, 80.0, "q4"),
                ),
                smooth = true,
            )
        val cut = c.splitSolid(block, c.squareChain(5.0, "sq"), c.planeXY(), -1, route, CarryMode.ROTATING)
        assertTrue(Evaluator().resultOf(cut) is EvalResult.Invalid, "the tight bend is refused")

        c.set(apex, 31.mm, 20.mm)
        assertTrue(
            Evaluator().resultOf(cut) is EvalResult.Ok,
            "and it comes back the moment the bend is opened: ${reasonOf(Evaluator(), cut)}",
        )
    }

    /**
     * **The translational carry has its own way of folding, and it is refused in its own words**: with every
     * section parallel to the chain's space, two of them meet exactly when the route stops advancing through
     * that space — and while it does advance, no two sections can meet at all, since they lie in distinct
     * parallel planes.
     */
    @Test
    fun aRouteThatStopsAdvancingIsRefusedForTheTranslationalCarry() {
        if (!MeshBool.available) return
        val c = Construction()
        val block = c.block(60.0, 40.0, 40.0)
        // up, then back down: a section carried flat would come back through the one before it
        val route = c.route(Triple(20.0, 20.0, -20.0), Triple(30.0, 20.0, 25.0), Triple(40.0, 20.0, 5.0))
        val chain = c.squareChain(6.0, "sq")
        val flat = c.splitSolid(block, chain, c.planeXY(), -1, route, CarryMode.TRANSLATIONAL)
        val why = reasonOf(Evaluator(), flat)
        assertTrue(why.contains("stops advancing through the chain's own plane"), "refused by name: $why")
        assertTrue(why.contains("rotating"), "and the cure names the other carry: $why")
    }

    // ---- 6. the operand is general: a closed route, and a chain on a plane that is nobody's axis ----

    /**
     * **A closed route needs no case of its own**: it is cut open where it stands furthest from the body —
     * the one place a tool's ends cost nothing — and from there it is the open case exactly, caps and all.
     * That is what makes the revolved cut of the operator's own table fall out rather than be built.
     *
     * The loop passes through the block once, so the figure is the same `area × arc length` as everywhere
     * else, and here the arc is the block's own depth.
     */
    @Test
    fun aClosedRouteIsCutOpenWhereItStandsFurthestFromTheBody() {
        if (!MeshBool.available) return
        val c = Construction()
        val block = c.block(40.0, 40.0, 20.0)
        val loop =
            c.route(
                Triple(20.0, -60.0, 10.0),
                Triple(20.0, 60.0, 10.0),
                Triple(20.0, 60.0, 200.0),
                Triple(20.0, -60.0, 200.0),
                closed = true,
                tag = "cl",
            )
        val cut = c.splitSolid(block, c.squareChain(5.0, "sq"), c.planeXZ(), -1, loop, CarryMode.ROTATING)
        val ev = Evaluator()
        assertClose(
            volumeOf(ev, cut, "the block cut by a closed route"),
            40.0 * 40.0 * 20.0 - 100.0 * 40.0,
            tol = 1e-3 * 32000.0,
            msg = "the leg that crosses the block takes a 10 × 10 channel through its 40 mm depth; the rest of the loop is clipped away",
        )
    }

    /**
     * **The chain's plane is any plane** — here one tilted 45° and standing nowhere near an axis, which is
     * how a draft or a bore into a slanted boss is actually stated. The tilt turns the section about the run
     * and so changes nothing about the volume, which is what makes it assertable: the figure is the same
     * `area × arc length` the canonical planes give.
     */
    @Test
    fun theChainsPlaneMayBeTiltedAndTheFigureIsUnchanged() {
        if (!MeshBool.available) return
        val c = Construction()
        val block = c.block(60.0, 60.0, 40.0)
        val plane = c.plane(Vec3(30.0, 30.0, 20.0), Vec3(0.0, 1.0, 0.0), Vec3(1.0, 0.0, -1.0))
        val route = c.route(Triple(0.0, 30.0, -10.0), Triple(30.0, 30.0, 20.0), Triple(70.0, 30.0, 40.0), tag = "tp")
        val cut = c.splitSolid(block, c.squareChain(6.0, "sq"), plane, -1, route, CarryMode.ROTATING)

        // in through z = 0 at (10, 30, 0), out through x = 60 at (60, 30, 35)
        val arc = sqrt(20.0 * 20.0 + 20.0 * 20.0) + sqrt(30.0 * 30.0 + 15.0 * 15.0)
        val ev = Evaluator()
        assertClose(
            volumeOf(ev, cut, "the block cut on a tilted plane"),
            60.0 * 60.0 * 40.0 - 144.0 * arc,
            tol = 2e-3 * 144000.0,
            msg = "a tilted chain plane rolls the section about the run and removes the same area × arc length",
        )
    }

    // ---- 7. the ordinary obligations ----

    /** A swept cut is a solid like any other: an operand of the next boolean, and measurable. */
    @Test
    fun aSweptCutIsAnOrdinaryOperandOfTheNextBoolean() {
        if (!MeshBool.available) return
        val c = Construction()
        val block = c.block(80.0, 60.0, 30.0)
        val route = c.route(Triple(40.0, 30.0, -20.0), Triple(50.0, 30.0, 20.0), Triple(50.0, 30.0, 60.0))
        val cut = c.splitSolid(block, c.squareChain(6.0, "sq"), c.planeXY(), -1, route, CarryMode.ROTATING)
        val boss = c.extrude(c.sketchOn(c.planeXY(), c.squareArea(5.0, "boss")), c.const(10.mm))
        val part = c.subtract(cut, boss)

        val ev = Evaluator()
        val v = volumeOf(ev, part, "the swept cut with a pocket taken out of it")
        assertTrue(v < volumeOf(ev, cut, "the swept cut"), "the second boolean removed material")
        assertClose(ev.scalar(c.measureVolume(part)).base, Geom3.volume(ev.solid(part).mesh), tol = 1e-6)
    }

    /** The route is live: drag a point it runs through and the cut follows, with no node rebuilt (OP-21). */
    @Test
    fun movingAPointOfTheRouteMovesTheCut() {
        if (!MeshBool.available) return
        val c = Construction()
        val block = c.block(80.0, 60.0, 30.0)
        val top = c.freePoint("top", 40.mm, 30.mm)
        val route =
            c.pathThrough(
                listOf(
                    c.at(40.0, 30.0, -20.0, "s0"),
                    c.at(40.0, 30.0, 15.0, "s1"),
                    c.heightPoint(c.planeXY(), top, c.const(60.mm)),
                ),
            )
        val cut = c.splitSolid(block, c.squareChain(6.0, "sq"), c.planeXY(), -1, route, CarryMode.ROTATING)
        val before = c.nodesCreated
        val straightVolume = volumeOf(Evaluator(), cut, "the cut by the straight route")

        c.set(top, 70.mm, 30.mm)
        val bentVolume = volumeOf(Evaluator(), cut, "the cut by the bent route")
        assertTrue(bentVolume < straightVolume - 100.0, "leaning the route's top takes more material out: $bentVolume vs $straightVolume")
        assertEquals(before, c.nodesCreated, "a drag recomputes the cut; it never rebuilds the graph (OP-21)")
    }

    private fun assertEquals(
        a: Int,
        b: Int,
        msg: String,
    ) = assertTrue(a == b, "$msg (expected $a but was $b)")

    private fun assertEqualMesh(
        a: constructit.geom.Mesh3,
        b: constructit.geom.Mesh3,
        msg: String,
    ) {
        assertTrue(a.vertices.size == b.vertices.size && a.triangles.size == b.triangles.size, "$msg — mesh sizes differ")
        for (i in a.vertices.indices) {
            assertTrue((a.vertices[i] - b.vertices[i]).length() <= 1e-9, "$msg — vertex $i moved")
        }
        for (i in a.triangles.indices) assertTrue(a.triangles[i] == b.triangles[i], "$msg — triangle $i differs")
    }
}
