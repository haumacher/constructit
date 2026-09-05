package constructit

import constructit.core.ChainValue
import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.ChainRef
import constructit.dsl.Construction
import constructit.dsl.RegionRef
import constructit.dsl.SolidRef
import constructit.dsl.region
import constructit.dsl.resultOf
import constructit.dsl.scalar
import constructit.dsl.solid
import constructit.dsl.valueOf
import constructit.geom.Chain
import constructit.geom.Chains
import constructit.geom.Feature3
import constructit.geom.Geom3
import constructit.geom.GeomMath
import constructit.geom.MeshBool
import constructit.geom.Plane3
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.units.mm
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **An unbounded tool is a legitimate operand** (OP-22's extension, step 1) — the engine half.
 *
 * A cut does not need the removed operand to be bounded: a chain that runs to infinity at both ends
 * separates its plane into two, and *which side to keep* is then a well-posed question. Everything here is
 * checked on values through the DSL; the gestures, the persisted sign and the file are `ChainCutToolTest`.
 *
 * Every solid produced anywhere is [assertManifold]ed, because a boolean is exactly where a mesh engine
 * leaks and a volume that looks right proves nothing on its own.
 */
class ChainCutTest {
    /** The 80 × 50 × 20 block every case below cuts: 80 000 mm³, extruded along +Z from the plan. */
    private fun Construction.block(): SolidRef {
        val a = freePoint("b.a", 0.mm, 0.mm)
        val b = freePoint("b.b", 80.mm, 0.mm)
        val c = freePoint("b.c", 80.mm, 50.mm)
        val d = freePoint("b.d", 0.mm, 50.mm)
        val r = region(loop(segment(a, b), segment(b, c), segment(c, d), segment(d, a)))
        return extrude(sketchOn(planeXY(), r), parameter("h", 20.mm))
    }

    private fun Construction.chain(vararg xy: Pair<Double, Double>): ChainRef =
        chainThrough(xy.mapIndexed { i, p -> freePoint("c$i", p.first.mm, p.second.mm) })

    private fun Construction.disc(
        cx: Double,
        cy: Double,
        r: Double,
        tag: String,
    ): RegionRef = region(loop(circleCR(freePoint("$tag.c", cx.mm, cy.mm), const(r.mm))))

    private fun volumeOf(
        ev: Evaluator,
        s: SolidRef,
        what: String,
    ): Double {
        assertTrue(ev.resultOf(s) is EvalResult.Ok, "$what should build: ${ev.resultOf(s)}")
        val solid = ev.solid(s)
        assertManifold(solid.mesh, what)
        return Geom3.volume(solid.mesh)
    }

    private fun reasonOf(
        ev: Evaluator,
        s: SolidRef,
    ): String = (ev.resultOf(s) as? EvalResult.Invalid)?.reason ?: "«$s is valid»"

    // ---- 1. a half-space: an infinite line through a block ----

    /**
     * **The simplest unbounded tool there is**: two clicks give a line with a ray at each end, and cutting
     * with it removes exactly one half-space's worth of material. The two halves of the *split* sum to the
     * original, which is the property that says the operator really is a partition and not two independent
     * subtractions that happen to look complementary.
     */
    @Test
    fun anInfiniteLineTakesExactlyOneSideOffABlock() {
        val c = Construction()
        val block = c.block()
        val line = c.chain(-10.0 to 20.0, 90.0 to 20.0)
        val left = c.splitSolid(block, line, c.planeXY(), 1)
        val right = c.splitSolid(block, line, c.planeXY(), -1)

        val ev = Evaluator()
        val whole = volumeOf(ev, block, "the block")
        assertClose(whole, 80000.0, tol = 1e-6)
        // travelling +x, the left of the run is +y: 30 mm of the block's 50
        assertClose(volumeOf(ev, left, "the left half"), 80.0 * 30.0 * 20.0, tol = 1e-6)
        assertClose(volumeOf(ev, right, "the right half"), 80.0 * 20.0 * 20.0, tol = 1e-6)
        assertClose(
            Geom3.volume(ev.solid(left).mesh) + Geom3.volume(ev.solid(right).mesh),
            whole,
            tol = 1e-6,
            msg = "a split is a partition: the two halves are the whole solid",
        )
    }

    /** The chain is live: drag a point it runs through and the cut follows, with no node rebuilt. */
    @Test
    fun movingAPointOfTheChainMovesTheCut() {
        val c = Construction()
        val block = c.block()
        val p0 = c.freePoint("p0", (-10.0).mm, 20.mm)
        val p1 = c.freePoint("p1", 90.mm, 20.mm)
        val cut = c.splitSolid(block, c.chainThrough(listOf(p0, p1)), c.planeXY(), 1)
        val before = c.nodesCreated

        assertClose(volumeOf(Evaluator(), cut, "the cut"), 80.0 * 30.0 * 20.0, tol = 1e-6)
        c.set(p0, (-10.0).mm, 10.mm)
        c.set(p1, 90.mm, 10.mm)
        assertClose(volumeOf(Evaluator(), cut, "the moved cut"), 80.0 * 40.0 * 20.0, tol = 1e-6)
        assertEquals(before, c.nodesCreated, "a drag recomputes the cut; it never rebuilds the graph (OP-21)")
    }

    // ---- 2. a step: ray, corner, ray ----

    /**
     * **A ray–corner–ray chain takes an inclined face off a casting.** The chain is one span at 45° between
     * two rays, so the removed piece is a wedge with a sloping face — the everyday shaping cut, and the case
     * a bounded box could only approximate by being drawn large enough.
     */
    @Test
    fun aRayCornerRayChainTakesAnInclinedFaceOffTheBlock() {
        val c = Construction()
        val block = c.block()
        // in from the left at y = 40, then down at 45° through (60, 0) and out below
        val chain = c.chain(-10.0 to 40.0, 20.0 to 40.0, 70.0 to -10.0)
        val above = c.splitSolid(block, chain, c.planeXY(), 1)
        val below = c.splitSolid(block, chain, c.planeXY(), -1)

        val ev = Evaluator()
        // 200 mm² of full-height strip, 1200 mm² under the slope, 1000 mm² beyond where it leaves the block
        assertClose(volumeOf(ev, above, "the piece above the chain"), 2400.0 * 20.0, tol = 1e-6)
        assertClose(volumeOf(ev, below, "the piece below it"), 80000.0 - 2400.0 * 20.0, tol = 1e-6)
    }

    // ---- 3. the closed case, through the same operator ----

    /**
     * **A closed chain is a through-cut**, and it is the *same* node: a closed loop separates its plane too
     * (a bounded inside, an unbounded outside), so the through-bore is the ordinary cut with the outside
     * kept, and the plug is the ordinary cut with the inside kept. No special case anywhere.
     */
    @Test
    fun aClosedChainIsAThroughCutWithNoSpecialCase() {
        val c = Construction()
        val block = c.block()
        val bore = c.disc(40.0, 25.0, 10.0, "bore")
        val chain = c.closedChain(bore)
        val plate = c.splitSolid(block, chain, c.planeXY(), -1)
        val plug = c.splitSolid(block, chain, c.planeXY(), 1)

        val ev = Evaluator()
        val discArea = Geom3.tessArea(Geom3.tessellateRegion(ev.region(bore)).first!!)
        assertClose(volumeOf(ev, plug, "the plug"), discArea * 20.0, tol = 1e-6)
        assertClose(volumeOf(ev, plate, "the bored plate"), 80000.0 - discArea * 20.0, tol = 1e-6)
        assertEquals(
            1,
            ev.solid(plate).feature.footprint.single().holes.size,
            "the bore is a hole in the plate's own plan, which is what a through-cut is",
        )
    }

    // ---- 4. the cross-axis case: the chain on a vertical plane ----

    /**
     * **The same operator with the chain drawn on a vertical plane** — which is how a draft face or a
     * chamfer is actually stated — and it takes the *general* boolean path (OP-9) for the ordinary reason a
     * cross-axis pair does. That the tool is unbounded changes nothing about which engine runs: the bound is
     * derived, and what the engine sees is two solids.
     */
    @Test
    fun aChainOnAVerticalPlaneChamfersTheBlockThroughTheGeneralPath() {
        if (!MeshBool.available) return
        val c = Construction()
        val block = c.block()
        // on the XZ plane, so (u, v) is (x, z): a 45° line v = u - 55
        val chain = c.chain(50.0 to -5.0, 90.0 to 35.0)
        val kept = c.splitSolid(block, chain, c.planeXZ(), 1)

        val ev = Evaluator()
        val v = volumeOf(ev, kept, "the chamfered block")
        // the wedge below the line is 300 mm² over the block's 50 mm depth
        assertTrue(abs(v - (80000.0 - 300.0 * 50.0)) / 65000.0 < 1e-4, "the chamfer removes a 15 000 mm³ wedge, but removed $v")
        assertTrue(
            ev.solid(kept).feature is Feature3.MeshBoolean,
            "a cross-axis cut is a general boolean, and says so in its type (OP-9)",
        )
    }

    // ---- 5. the refusals, by name, and each of them heals ----

    /** A tool that misses leaves the solid unchanged — and that silence is what a wrong side looks like. */
    @Test
    fun aChainThatMissesRefusesRatherThanSayingNothing() {
        val c = Construction()
        val block = c.block()
        val y0 = c.freePoint("y0", (-10.0).mm, 100.mm)
        val y1 = c.freePoint("y1", 90.mm, 100.mm)
        val chain = c.chainThrough(listOf(y0, y1))
        val keptSide = c.splitSolid(block, chain, c.planeXY(), -1)
        val emptySide = c.splitSolid(block, chain, c.planeXY(), 1)

        assertTrue(reasonOf(Evaluator(), keptSide).contains("leaves the solid untouched"), reasonOf(Evaluator(), keptSide))
        assertTrue(reasonOf(Evaluator(), emptySide).contains("removes the whole solid"), reasonOf(Evaluator(), emptySide))

        // …and both heal the moment the chain crosses the material (OP-3)
        c.set(y0, (-10.0).mm, 25.mm)
        c.set(y1, 90.mm, 25.mm)
        val ev = Evaluator()
        assertClose(volumeOf(ev, keptSide, "the healed cut"), 80.0 * 25.0 * 20.0, tol = 1e-6)
        assertClose(volumeOf(ev, emptySide, "its complement"), 80.0 * 25.0 * 20.0, tol = 1e-6)
    }

    /** A chain that crosses itself does not separate the plane, so it is refused — and it heals. */
    @Test
    fun aSelfIntersectingChainIsRefusedAndHeals() {
        val c = Construction()
        val block = c.block()
        val last = c.freePoint("last", 20.mm, (-10.0).mm)
        val chain =
            c.chainThrough(
                listOf(
                    c.freePoint("k0", 0.mm, 0.mm),
                    c.freePoint("k1", 40.mm, 0.mm),
                    c.freePoint("k2", 40.mm, 30.mm),
                    last,
                ),
            )
        val cut = c.splitSolid(block, chain, c.planeXY(), 1)

        assertTrue(reasonOfChain(Evaluator(), chain).contains("meets itself"), reasonOfChain(Evaluator(), chain))
        assertTrue(
            Evaluator().resultOf(cut) is EvalResult.Invalid,
            "and invalidity propagates: nothing cut with a chain that has no sides has a value either (OP-3)",
        )

        c.set(last, 60.mm, 30.mm)
        assertNull(Chains.defect((Evaluator().valueOf(chain) as ChainValue).chain), "the chain is embedded again")
        assertTrue(Evaluator().resultOf(cut) is EvalResult.Ok, "so the cut comes back with no repair: ${reasonOf(Evaluator(), cut)}")
    }

    /** Two points in the same place have no direction, so there is no ray to continue — refused, and heals. */
    @Test
    fun aChainWithACoincidentPairIsRefusedAndHeals() {
        val c = Construction()
        val p = c.freePoint("q1", 10.mm, 10.mm)
        val chain = c.chainThrough(listOf(c.freePoint("q0", 10.mm, 10.mm), p))
        assertTrue(reasonOfChain(Evaluator(), chain).contains("same place"), reasonOfChain(Evaluator(), chain))
        c.set(p, 40.mm, 10.mm)
        assertTrue(Evaluator().resultOf(chain) is EvalResult.Ok)
    }

    private fun reasonOfChain(
        ev: Evaluator,
        r: ChainRef,
    ): String = (ev.resultOf(r) as? EvalResult.Invalid)?.reason ?: "«valid»"

    // ---- 6. the degeneracy the margin exists to prevent ----

    /**
     * **The test that proves the margin argument.** The chain's own extent lies *inside* the target's, so a
     * bound taken naively — the target's bounding box, exactly — would put four side faces and both caps of
     * the tool exactly coplanar with faces of the target: the degenerate class the exact path refuses and the
     * general one resolves by epsilon, and precisely the failure the big-box-by-eye workaround runs into.
     *
     * The derived bound cannot produce it, and the reason is checked directly rather than inferred from the
     * cut succeeding: every face of the tool is at least one whole margin clear of the target.
     */
    @Test
    fun theDerivedBoundIsStrictlyOutsideTheTargetSoNoFaceIsEverCoplanar() {
        val c = Construction()
        val block = c.block()
        val chain = c.chain(40.0 to 10.0, 40.0 to 40.0)
        val left = c.splitSolid(block, chain, c.planeXY(), 1)
        val right = c.splitSolid(block, chain, c.planeXY(), -1)

        val ev = Evaluator()
        assertClose(volumeOf(ev, left, "the left half"), 40.0 * 50.0 * 20.0, tol = 1e-6)
        assertClose(volumeOf(ev, right, "the right half"), 40.0 * 50.0 * 20.0, tol = 1e-6)

        // the claim itself: the tool's own box strictly contains the target's, by at least the margin
        val plane = Plane3(Vec3.ZERO, Vec3.X, Vec3.Y)
        val target = ev.solid(block).mesh
        val (tools, why) = Chains.tools((ev.valueOf(chain) as ChainValue).chain, plane, target)
        assertNotNull(tools, why?.render())
        val m = Chains.margin(80.0)
        // Every face plane of the block, as the coordinate it stands at on its own axis. No vertex of either
        // tool comes within a margin of any of them — so the closure cannot be coplanar with a face, and the
        // faces that *are* close to the material (the chain's own) are the user's statement, not a bound.
        val faces = listOf(listOf(0.0, 80.0), listOf(0.0, 50.0), listOf(0.0, 20.0))
        for ((what, tool) in listOf("the left tool" to tools.first, "the right tool" to tools.second)) {
            val (lo, hi) = Geom3.bounds(tool.mesh)!!
            assertTrue(lo.z <= -m + 1e-9 && hi.z >= 20.0 + m - 1e-9, "$what overhangs the block's caps by a margin")
            assertTrue(lo.y <= -m + 1e-9 && hi.y >= 50.0 + m - 1e-9, "$what reaches clear of the block in y")
            for (v in tool.mesh.vertices) {
                for ((axis, at) in listOf(v.x, v.y, v.z).zip(faces)) {
                    for (face in at) {
                        assertTrue(
                            abs(axis - face) >= m - 1e-9,
                            "$what has a vertex $axis mm from the block's face at $face — that is the coplanarity the margin exists to make unreachable",
                        )
                    }
                }
            }
        }
    }

    /** The bound is **derived**, so a target that grows is bounded larger — nothing was stored. */
    @Test
    fun theBoundGrowsWithTheTargetBecauseNothingAboutItIsStored() {
        val c = Construction()
        val h = c.parameter("h", 20.mm)
        val a = c.freePoint("g.a", 0.mm, 0.mm)
        val b = c.freePoint("g.b", 80.mm, 0.mm)
        val d = c.freePoint("g.c", 80.mm, 50.mm)
        val e = c.freePoint("g.d", 0.mm, 50.mm)
        val r = c.region(c.loop(c.segment(a, b), c.segment(b, d), c.segment(d, e), c.segment(e, a)))
        val block = c.extrude(c.sketchOn(c.planeXY(), r), h)
        val cut = c.splitSolid(block, c.chain(40.0 to 10.0, 40.0 to 40.0), c.planeXY(), 1)

        assertClose(volumeOf(Evaluator(), cut, "the cut"), 40.0 * 50.0 * 20.0, tol = 1e-6)
        c.set(h, 500.mm)
        assertClose(
            volumeOf(Evaluator(), cut, "the taller cut"),
            40.0 * 50.0 * 500.0,
            tol = 1e-6,
            msg = "the tool is re-bounded from the target's current extent, so a 25× taller block is cut through",
        )
    }

    // ---- the two halves, as geometry, before any solid is involved ----

    /** The ordering rule, on the value alone: side `+1` is the left of the chain's own direction of travel. */
    @Test
    fun theFirstHalfIsAlwaysTheLeftOfTheRun() {
        val chain = Chains.through(listOf(Vec2(-1.0, 0.0), Vec2(1.0, 0.0))).first!!
        val (sides, why) = Chains.halves(chain, Vec2(-10.0, -10.0), Vec2(10.0, 10.0))
        assertNotNull(sides, why?.render())
        val left = sides.first.single()
        val right = sides.second.single()
        assertTrue(left.outer.elements.all { GeomMath.startOf(it).y >= -1e-9 }, "the left half is the +y one")
        assertTrue(right.outer.elements.all { GeomMath.startOf(it).y <= 1e-9 }, "and the right half the −y one")
        assertClose(GeomMath.signedArea(left.outer), 200.0, tol = 1e-9)
        assertClose(GeomMath.signedArea(right.outer), 200.0, tol = 1e-9, msg = "both come out counter-clockwise")

        // …and the same rule, read by a click: a point above the run is on side +1
        assertEquals(1, Chains.sideAt(chain, Vec2(0.0, 5.0)))
        assertEquals(-1, Chains.sideAt(chain, Vec2(0.0, -5.0)))
        // …including out along the rays, where the travel direction reverses on the first one
        assertEquals(1, Chains.sideAt(chain, Vec2(-50.0, 5.0)))
        assertEquals(-1, Chains.sideAt(chain, Vec2(50.0, -5.0)))
    }

    /** A closed chain's `+1` is its inside, which is the same "left of travel" rule on a CCW boundary. */
    @Test
    fun aClosedChainsFirstHalfIsItsInside() {
        val c = Construction()
        val chain = c.closedChain(c.disc(0.0, 0.0, 5.0, "d"))
        val v = (Evaluator().valueOf(chain) as ChainValue).chain
        assertTrue(v is Chain.Closed)
        assertEquals(1, Chains.sideAt(v, Vec2(0.0, 0.0)))
        assertEquals(-1, Chains.sideAt(v, Vec2(50.0, 0.0)))
    }

    /** The composition claim: a cut is a solid like any other, and is a legal operand of the next boolean. */
    @Test
    fun aCutSolidIsAnOrdinaryOperandOfTheNextBoolean() {
        val c = Construction()
        val block = c.block()
        val half = c.splitSolid(block, c.chain(-10.0 to 20.0, 90.0 to 20.0), c.planeXY(), 1)
        val bore = c.extrude(c.sketchOn(c.planeXY(), c.disc(40.0, 35.0, 6.0, "b2")), c.parameter("t", 20.mm))
        val part = c.subtract(half, bore)

        val ev = Evaluator()
        val discArea = Geom3.tessArea(Geom3.tessellateRegion(ev.region(c.disc(40.0, 35.0, 6.0, "b3"))).first!!)
        assertClose(volumeOf(ev, part, "the cut and bored half"), 80.0 * 30.0 * 20.0 - discArea * 20.0, tol = 1e-6)
        assertClose(ev.scalar(c.measureVolume(part)).base, Geom3.volume(ev.solid(part).mesh), tol = 1e-6)
    }
}
