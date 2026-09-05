package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.Construction
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.geom.Blend3
import constructit.geom.BlendKind
import constructit.geom.BlendSection
import constructit.geom.FaceName
import constructit.geom.Geom3
import constructit.geom.GeomMath
import constructit.geom.Mesh3
import constructit.geom.Plane3
import constructit.geom.ProfileElement
import constructit.geom.Revolve3
import constructit.geom.Section3
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.units.mm
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **The catalogue's fourth corner: the three-concave vertex** (session 81).
 *
 * Session 80 built the two places the rolling ball **stands still** — the pivot at an inside corner of a
 * shared face, and the ball at a convex trihedral vertex — and named a cut: *"a concave vertex … is not
 * built: the patch is the ball's octant **added** rather than taken, and the sign of the fill is the one
 * thing the construction guesses rather than derives"*. This is that corner, and the sign is now argued
 * rather than guessed.
 *
 * **It is the same ball.** Three fills meet at a **room's own corner** — a shelled box's inside corner, or
 * an L-shaped box hollowed out — and the ball sits against the three faces from the *air* side, touching all
 * three at once. Its centre stands at `(r, r, r)` from the vertex **along** the three outward normals rather
 * than against them, and the stations solve identically: each pair of bands shares a face, on which their
 * two tangency lines cross at one point, and the three answers agreeing is the statement that a ball of this
 * size sits there. What turns is one sign and one growth direction, and both come out of the fill's own
 * wedge rather than out of a trial:
 *
 * - `Blend3.outwardAt` steps each leg **out of the wedge**, which at a convex crease is out of the material
 *   and at a concave one is *into* it — so the grown leg stands a micron on the far side of its face from
 *   the tool either way, and the vertex's three flat quads follow it.
 * - The tool's own **outside** at such a quad is the side its interior is not on: the face's own normal
 *   where the tool is subtracted, its negative where the tool is united.
 * - The **fill does not turn at all.** The corner cell keeps the ball's own octant — of material at a
 *   convex vertex, of air at a concave one — so the tool is outside the ball in both readings and its
 *   surface there faces the ball's centre, which is exactly what session 80 already wrote.
 *
 * **The figure, derived.** Put the vertex at the origin with the void in the octant `x, y, z ≥ 0`. Each of
 * the three fills adds its wedge — the corner square less the quarter disc, `(1 − π/4) r²` — along its own
 * run, so the three of them would add `(1 − π/4) r² · ΣL` if nothing overlapped. In the corner cell
 * `[0, r]³` they do overlap, and what the finished body keeps there is exactly the **ball's own octant of
 * air**: the fill adds `r³ − (π/6) r³`. The three bands' own sum over that same cell is `3(1 − π/4) r³`, so
 * the corner takes back
 *
 * ```
 * 3(1 − π/4) r³ − (1 − π/6) r³ = (2 − 7π/12) r³
 * ```
 *
 * — the very figure the *convex* vertex reads the other way round, which is what says it is one ball. And
 * the discriminator is not the number but the surface: without the patch the three fills leave the
 * **tricylinder**'s corner, `(2 − √2) r³` of air rather than `(π/6) r³`, with a spike of air poking at the
 * vertex that no ball of radius `r` can reach. So every direction inside the patch, walked out from the
 * ball's centre, must meet the body at exactly `r`.
 */
class BlendConcaveVertexTest {
    private val plan = listOf(Vec2(0.0, 0.0), Vec2(60.0, 0.0), Vec2(60.0, 40.0), Vec2(0.0, 40.0))
    private val deep = 30.0
    private val wall = 6.0

    /** The cavity's own corner, and the three creases that meet there. */
    private val corner = Vec3(wall, wall, wall)
    private val runs =
        listOf(
            corner to Vec3(60.0 - wall, wall, wall),
            corner to Vec3(wall, 40.0 - wall, wall),
            corner to Vec3(wall, wall, deep),
        )

    private var ids = 0

    private fun prism(
        cx: Construction,
        xy: List<Vec2>,
        h: Double,
    ): SolidRef {
        val pts = xy.map { cx.freePoint("p${ids++}", it.x.mm, it.y.mm) }
        val segs = xy.indices.map { cx.segment(pts[it], pts[(it + 1) % xy.size]) }
        return cx.extrude(cx.sketchOn(cx.planeXY(), cx.region(cx.loop(*segs.toTypedArray()))), cx.const(h.mm))
    }

    /** The box hollowed out with its top left open — a room, whose floor corners are the fixture. */
    private fun room(cx: Construction): SolidRef {
        val box = prism(cx, plan, deep)
        val faces = assertNotNull(Section3.faces(Evaluator().solid(box).feature).first, "the box names its faces")
        val top =
            assertNotNull(
                faces.indices.firstOrNull { i ->
                    val p = faces[i].plane ?: return@firstOrNull false
                    abs(p.normal.normalized().z - 1.0) < 1e-9 && abs(p.origin.z - deep) < 1e-9
                },
                "a top face at z = $deep",
            )
        return cx.shell(box, cx.const(wall.mm), listOf(top))
    }

    private fun edgeAt(
        ref: SolidRef,
        a: Vec3,
        b: Vec3,
    ): Int {
        val edges = assertNotNull(Section3.edges(Evaluator().solid(ref).feature).first, "the body names its edges")
        return assertNotNull(
            edges.indices.firstOrNull { i ->
                if (edges[i].reason != null) return@firstOrNull false
                val el = Blend3.edgePath(edges[i]).first?.elements?.singleOrNull() ?: return@firstOrNull false
                val ends = listOf(el.start, el.end)
                listOf(a, b).all { q -> ends.any { (it - q).length() < 1e-6 } }
            },
            "an edge from $a to $b",
        )
    }

    /** One gesture, scored the way a live click scores it and then handed over verbatim (OP-1/OP-18). */
    private fun fill(
        cx: Construction,
        on: SolidRef,
        size: Double,
        address: Int,
    ): SolidRef {
        val body = Evaluator().solid(on)
        val (choices, why) = Blend3.choicesFor(body, listOf(address), BlendSection(BlendKind.FILLET, size))
        assertNotNull(choices, why?.render())
        assertTrue(choices.all { !it.convex }, "the crease at the cavity's corner is concave, so the rounding is a fill")
        return cx.blend(on, on, cx.planeXY(), cx.const(size.mm), BlendKind.FILLET, whole = false, address = address, choices = choices)
    }

    /** The room with the three creases of its own corner filled, in the order [order] gives them. */
    private fun filled(
        r: Double,
        order: List<Int>,
        sizes: List<Double> = List(3) { r },
    ): Pair<SolidRef, Double> {
        val cx = Construction()
        val hollow = room(cx)
        val before = Geom3.volume(Evaluator().solid(hollow).mesh)
        var on = hollow
        for (k in order) on = fill(cx, on, sizes[k], edgeAt(on, runs[k].first, runs[k].second))
        return on to before
    }

    private fun volumeOf(
        ref: SolidRef,
        what: String,
    ): Double {
        val ev = Evaluator()
        val res = ev.eval(ref.node)
        assertTrue(res is EvalResult.Ok, "$what: ${(res as? EvalResult.Invalid)?.reason}")
        val mesh = ev.solid(ref).mesh
        assertManifold(mesh, what)
        return Geom3.volume(mesh)
    }

    private fun meshOf(ref: SolidRef): Mesh3 = Evaluator().solid(ref).mesh

    private fun length(k: Int): Double = (runs[k].second - runs[k].first).length()

    /** The chord surplus an inscribed arc leaves over [length] mm of run — [EdgeBlendTest]'s own model. */
    private fun chords(
        r: Double,
        length: Double,
    ): Double = PI * r * GeomMath.TESS_TOL_MM * length / 3.0

    // ---- the corner itself ----

    /**
     * **Three fills at a room's corner are one body, and the corner takes back `(2 − 7π/12) r³`.**
     * Two-sided, because the arcs reach the boolean as inscribed chord polygons and an inscribed chord makes
     * a fill's wedge *larger*: never less than the exact figure, never more than it by the chords.
     */
    @Test
    fun threeFillsAtARoomsCornerLeaveTheBallsOctantOfAir() {
        val r = 3.0
        val (body, before) = filled(r, listOf(0, 1, 2))
        val added = volumeOf(body, "the room with its own corner filled") - before

        val exact = (1.0 - PI / 4.0) * r * r * (0..2).sumOf { length(it) } - (2.0 - 7.0 * PI / 12.0) * r * r * r
        assertTrue(added >= exact - 1e-6, "the three fills add at least the exact $exact mm^3 — they added $added")
        assertTrue(added <= exact + chords(r, (0..2).sumOf { length(it) }), "…and at most that plus the chords: $added")

        // …and the corner is the ball's own surface, named as the sphere it is
        val faces = assertNotNull(Section3.faces(Evaluator().solid(body).feature).first, "the body names its faces")
        val balls =
            faces.filter { it.name is FaceName.BlendCorner }
                .mapNotNull { it.surface?.band as? Revolve3.Band.Sphere }
        assertTrue(balls.size == 1, "one ball at the corner: ${faces.map { it.name.label.render() }}")
        assertClose(balls[0].radius, r, 1e-12, "…of the fill's own radius")
    }

    /**
     * **The discriminator: the air at the corner is the ball and not the tricylinder's spike.** Every
     * direction inside the patch, walked out from the ball's own centre, leaves the body at exactly `r`.
     * Without the patch the three fills leave the intersection of three cylinders, whose corner pokes
     * `(1 − 1/√2) r` further toward the vertex — `1.22 r` from the centre along the diagonal.
     */
    @Test
    fun theCornersAirIsTheBallsOwn() {
        val r = 3.0
        val (body, _) = filled(r, listOf(0, 1, 2))
        val mesh = meshOf(body)
        assertManifold(mesh, "the room with its own corner filled")

        val centre = corner + Vec3(r, r, r)
        assertTrue(!Geom3.encloses(mesh, centre), "the ball's centre is air")
        for (u in patchDirections()) {
            var lo = 0.0
            var hi = 3.0 * r
            repeat(40) {
                val mid = (lo + hi) / 2.0
                if (!Geom3.encloses(mesh, centre + u * mid)) lo = mid else hi = mid
            }
            val reach = (lo + hi) / 2.0
            assertClose(reach, r, 0.05, "the air stands at the ball's own radius along $u — it stands at $reach")
        }
    }

    /** A spread of directions inside the patch: the diagonal, the three corners' own, and between them. */
    private fun patchDirections(): List<Vec3> {
        val corners = listOf(Vec3(-1.0, 0.0, 0.0), Vec3(0.0, -1.0, 0.0), Vec3(0.0, 0.0, -1.0))
        val out = ArrayList<Vec3>()
        out.add((corners[0] + corners[1] + corners[2]).normalized())
        for (i in 0 until 3) {
            for (t in listOf(0.25, 0.5, 0.75)) {
                out.add((corners[i] * t + corners[(i + 1) % 3] * (1.0 - t)).normalized())
            }
        }
        return out
    }

    /**
     * **One body whichever gesture arrives last** (OP-30). The three fills are three gestures on the dressed
     * body, and all six orders come out the same volume to the boolean's own float32 noise.
     */
    @Test
    fun theThreeGesturesAgreeInEveryOrder() {
        val r = 3.0
        val orders =
            listOf(
                listOf(0, 1, 2),
                listOf(0, 2, 1),
                listOf(1, 0, 2),
                listOf(1, 2, 0),
                listOf(2, 0, 1),
                listOf(2, 1, 0),
            )
        val first = filled(r, orders[0]).let { volumeOf(it.first, "order ${orders[0]}") }
        for (order in orders.drop(1)) {
            val v = volumeOf(filled(r, order).first, "order $order")
            assertClose(v, first, abs(first) * 1e-5, "order $order is the same body")
        }
    }

    /**
     * **A fill meeting two fills of another radius is left as it was** — the non-congruent rule, unchanged.
     * Two sections that do not land on one ring make no corner of any kind, so no ball is named there and
     * the boolean trims the three bands against each other exactly as it did before this session.
     */
    @Test
    fun aFillOfAnotherRadiusMakesNoBallAtAll() {
        val (body, _) = filled(3.0, listOf(0, 1, 2), sizes = listOf(3.0, 3.0, 4.0))
        volumeOf(body, "the room with two 3 mm fills and one 4 mm one")
        val faces = assertNotNull(Section3.faces(Evaluator().solid(body).feature).first, "the body names its faces")
        assertTrue(
            faces.none { it.name is FaceName.BlendCorner },
            "no corner is built between sections that are not congruent: ${faces.map { it.name.label.render() }}",
        )
    }

    /**
     * **A mixed vertex is still the pivot about a band**, and this is the boundary the vertex rule stops at:
     * three bands of one sign make a ball, and a trio that is not of one sign is the pair turning about the
     * band between them ([Blend3] `Turn`). The fixture is the reflex plan corner of an L-shaped room, whose
     * upright is **convex** where its two floor creases are fills.
     */
    @Test
    fun aMixedVertexIsStillThePivotAboutItsBand() {
        val r = 3.0
        val ell = listOf(Vec2(0.0, 0.0), Vec2(60.0, 0.0), Vec2(60.0, 20.0), Vec2(25.0, 20.0), Vec2(25.0, 50.0), Vec2(0.0, 50.0))
        val cx = Construction()
        val box = prism(cx, ell, deep)
        val faces = assertNotNull(Section3.faces(Evaluator().solid(box).feature).first, "the box names its faces")
        val top =
            assertNotNull(
                faces.indices.firstOrNull { i ->
                    val p = faces[i].plane ?: return@firstOrNull false
                    abs(p.normal.normalized().z - 1.0) < 1e-9 && abs(p.origin.z - deep) < 1e-9
                },
                "a top face",
            )
        var on = cx.shell(box, cx.const(wall.mm), listOf(top))
        // the cavity's own reflex plan corner, and the three creases that meet at it — the two floor ones
        // are fills and the upright between them is a cut ([BlendMixedVertexTest]'s own fixture)
        val floor = Vec3(19.0, 14.0, wall)
        val trio =
            listOf(
                floor to Vec3(54.0, 14.0, wall),
                floor to Vec3(19.0, 44.0, wall),
                floor to Vec3(19.0, 14.0, deep),
            )
        for ((a, b) in trio) on = fillOrCut(cx, on, r, edgeAt(on, a, b))
        volumeOf(on, "the L-shaped room's reflex corner")

        val named = assertNotNull(Section3.faces(Evaluator().solid(on).feature).first, "the body names its faces")
        val tori = named.filter { it.name is FaceName.BlendCorner }.mapNotNull { it.surface?.band as? Revolve3.Band.Torus }
        assertTrue(tori.any { abs(it.rc - 2 * r) < 1e-9 && abs(it.minor - r) < 1e-9 }, "the ring torus, not a ball: ${named.map { it.name.label.render() }}")
        assertTrue(
            named.filter { it.name is FaceName.BlendCorner }.none { it.surface?.band is Revolve3.Band.Sphere },
            "…and no ball, because the trio is not of one sign",
        )
    }

    /** A rounding whichever sign the crease is — the mixed fixture's upright is a cut where its floor is a fill. */
    private fun fillOrCut(
        cx: Construction,
        on: SolidRef,
        size: Double,
        address: Int,
    ): SolidRef {
        val body = Evaluator().solid(on)
        val (choices, why) = Blend3.choicesFor(body, listOf(address), BlendSection(BlendKind.FILLET, size))
        assertNotNull(choices, why?.render())
        return cx.blend(on, on, cx.planeXY(), cx.const(size.mm), BlendKind.FILLET, whole = false, address = address, choices = choices)
    }

    /**
     * **A working plane through the added patch is cut exactly.** A plane cuts a ball in a circle whatever
     * its attitude, and the patch is that circle clipped to its own spherical triangle by three great
     * circles — three half-spaces through the centre, so an angular interval. At `1.5 mm` below the ball's
     * centre the body's surface there is the circle of radius `√(r² − 1.5²)` about the centre's own foot,
     * drawn as a real arc with no chord in it.
     */
    @Test
    fun theSectionThroughTheAddedPatchIsExact() {
        val r = 3.0
        val (body, _) = filled(r, listOf(0, 1, 2))
        val solid = Evaluator().solid(body)
        val centre = corner + Vec3(r, r, r)
        val off = 1.5
        val at = centre.z - off
        val cut = Plane3(Vec3(0.0, 0.0, at), Vec3.X, Vec3.Y)
        val drawn = Section3.sectionOf(solid, cut).drawn
        val want = sqrt(r * r - off * off)
        val arcs =
            drawn.filterIsInstance<ProfileElement.ArcE>()
                .filter { abs(it.arc.radius - want) <= 1e-9 && (it.arc.center - Vec2(centre.x, centre.y)).length() <= 1e-9 }
        assertTrue(arcs.isNotEmpty(), "the ball's own circle at that height: $drawn")
        // …and it is the spherical triangle's own share of that circle, not the whole of it
        val swept = arcs.sumOf { abs(GeomMath.sweep(it.arc)) }
        assertTrue(swept > 0.0 && swept < 2.0 * PI - 1e-9, "clipped to the patch's three great circles: $swept rad")
    }
}
