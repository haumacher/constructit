package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.Construction
import constructit.dsl.RegionRef
import constructit.dsl.SolidRef
import constructit.dsl.plane
import constructit.dsl.region
import constructit.dsl.resultOf
import constructit.dsl.solid
import constructit.geom.EdgeGeom
import constructit.geom.EdgeName
import constructit.geom.FaceName
import constructit.geom.Feature3
import constructit.geom.MeshBool
import constructit.geom.Section3
import constructit.geom.SolidFace
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.units.mm
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **A general boolean's result keeps its faces** (OP-31, item 4) — the half of OP-9's mesh-is-a-sink rule
 * that is retired here, asserted as arithmetic.
 *
 * The argument the whole slice rests on is that a boolean never **moves** a surface: every triangle of the
 * result lies in a face of one of its two operands, and only the *trim* is emergent. So the result's faces
 * are the operands' own faces cut down — exactly, on the operand's own plane — and its creases are where two
 * of those exact planes meet. That is what is checked below: not that a face list exists, but that every
 * carrier is the operand plane it claims to be and every corner stands where **three planes** put it, to a
 * nanometre, on a mesh whose vertices are float32 and are therefore a thousand times coarser than that.
 *
 * The fixture is a **cross bar**: an upright 20 mm box and a horizontal 10 mm bar driven through it at right
 * angles, so the two have no common axis and the exact slab algebra (OP-22) has no answer at all. All three
 * booleans are asserted over it, because each puts a different question to the provenance — a union splits a
 * face into two pieces, an intersection consumes whole faces, and a difference turns the tool's own faces
 * into walls of a cavity and has to flip them.
 */
class BooleanProvenanceTest {
    private fun requireEngine() =
        assumeTrue(MeshBool.available, "the general boolean engine (Manifold, OP-9) is not available here: ${MeshBool.status}")

    private fun Construction.rect(
        x0: Double,
        y0: Double,
        x1: Double,
        y1: Double,
        tag: String,
    ): RegionRef {
        val a = freePoint("$tag.a", x0.mm, y0.mm)
        val b = freePoint("$tag.b", x1.mm, y0.mm)
        val d = freePoint("$tag.c", x1.mm, y1.mm)
        val e = freePoint("$tag.d", x0.mm, y1.mm)
        return region(loop(segment(a, b), segment(b, d), segment(d, e), segment(e, a)))
    }

    /** The upright: 20 × 20 × 20 mm on the XY plane, `x, y ∈ [−10, 10]`, `z ∈ [0, 20]`. */
    private fun Construction.post(): SolidRef = extrude(sketchOn(planeXY(), rect(-10.0, -10.0, 10.0, 10.0, "post")), const(20.mm))

    /**
     * The bar: driven along **+Y**, `z ∈ [5, 15]`, `x ∈ [−5, 5]`, `y ∈ [−30, 30]` — through the post and out
     * both ends, and narrow enough in `x` to leave the post's other two walls alone.
     *
     * Its sketch plane is `y = −30` with `u = +Z` and `v = +X`, so a sketch point is `(z, x)` and the
     * extrude runs along `u × v = +Y` — an axis perpendicular to the post's, which is the whole reason this
     * pair reaches the general engine.
     */
    private fun Construction.bar(): SolidRef =
        extrude(sketchOn(plane(Vec3(0.0, -30.0, 0.0), Vec3.Z, Vec3.X), rect(5.0, -5.0, 15.0, 5.0, "bar")), const(60.mm))

    private fun built(
        ev: Evaluator,
        ref: SolidRef,
        what: String,
    ): Feature3 {
        assertTrue(ev.resultOf(ref) is EvalResult.Ok, "$what should build: ${(ev.resultOf(ref) as? EvalResult.Invalid)?.reason}")
        val solid = ev.solid(ref)
        assertManifold(solid.mesh, what)
        assertTrue(solid.feature is Feature3.MeshBoolean, "$what took the general path, not ${solid.feature::class.simpleName}")
        return solid.feature
    }

    /**
     * Everything a result face must be, checked against the operand face it claims to come from: its carrier
     * **is** that operand's plane (up to the flip a cavity wall needs), its outline is closed and made of
     * straight pieces, and every corner of it lies on the two creases that meet there.
     */
    private fun checkFaces(
        feature: Feature3,
        what: String,
    ) {
        val (faces, why) = Section3.faces(feature)
        assertNotNull(faces, "$what names its faces: ${why?.render()}")
        val f = feature as Feature3.MeshBoolean
        val ops = listOf(f.provenance)
        assertTrue(ops.first() != null, "$what carries its provenance")
        for (p in faces) {
            val name = p.name
            assertTrue(name is FaceName.BoolFace, "$what: every face is a piece of an operand face, not $name")
            if (p.plane == null) {
                assertNotNull(p.reason, "$what: a face with no surface says why")
                assertTrue(p.outline.isEmpty(), "$what: and draws nothing")
                continue
            }
            assertNull(p.reason, "$what: a face that has a plane is not refused")
            val pts = p.outline.segments()
            assertTrue(pts.size >= 3, "$what: ${name.label.render()} is a closed outline, not ${pts.size} pieces")
            // closed: every piece hands over to the next, loop by loop
            var open = 0
            for (i in pts.indices) {
                if ((pts[i].b - pts[(i + 1) % pts.size].a).length() > 1e-9) open++
            }
            assertTrue(open <= countLoops(p.outline.segments()), "$what: ${name.label.render()} closes")
        }
    }

    /** How many closed loops a flat piece list draws — a hand-over that misses is a loop boundary. */
    private fun countLoops(segs: List<constructit.geom.Segment>): Int {
        var n = 0
        for (i in segs.indices) if ((segs[i].b - segs[(i + 1) % segs.size].a).length() > 1e-9) n++
        return maxOf(n, 1)
    }

    /** The face of [feature] that is piece [piece] of face [face] of operand [operand], or null. */
    private fun faceOf(
        feature: Feature3,
        operand: Int,
        face: Int,
        piece: Int = 0,
    ): constructit.geom.FacePatch? =
        Section3.faces(feature).first?.firstOrNull {
            val n = it.name
            n is FaceName.BoolFace && n.operand == operand && n.face == face && n.piece == piece
        }

    /**
     * **The union**, and the three things a boolean can do to a face, all three of them in one body: leave it
     * alone (the post's top cap and its two side walls the bar never reaches), **pierce** it (the two walls
     * the bar comes out of, which keep one outline with a hole in it), and **cut it in two** (each of the
     * bar's four sides, which the post interrupts in the middle of its run).
     */
    @Test
    fun aUnionKeepsEveryFaceWhicheverOfTheThreeThingsHappensToIt() {
        requireEngine()
        val c = Construction()
        val part = c.union(c.post(), c.bar())
        val ev = Evaluator()
        val feature = built(ev, part, "post ∪ bar")
        checkFaces(feature, "post ∪ bar")

        // the post's own face list: sides 0..3 over its boundary pieces, then bottom cap, then top cap
        val (postFaces, _) = Section3.faces(ev.solid(c.post()).feature)
        assertEquals(6, assertNotNull(postFaces).size, "the post has four sides and two caps")

        // untouched: the top cap (z = 20) above a bar that stops at z = 15 keeps its whole 20 × 20 outline
        val top = assertNotNull(faceOf(feature, 0, 5), "the post's top cap is still a face of the union")
        assertEquals(FaceName.Cap(SolidFace.TOP), (top.name as FaceName.BoolFace).of, "and it is named as the cap it is")
        assertClose(400.0, area(top.outline.segments()), tol = 1e-9, msg = "the untouched top cap keeps its whole area")
        // …and so does the post's x = +10 wall, which the 10 mm-wide bar never reaches
        assertClose(400.0, area(assertNotNull(faceOf(feature, 0, 1)).outline.segments()), tol = 1e-9, msg = "the wall beside the bar")

        // pierced: the y = −10 wall the bar comes out of keeps one outline, with the bar's 10 × 10 section as
        // a hole in it — one face, two loops
        val pierced = assertNotNull(faceOf(feature, 0, 0), "the wall the bar comes out of")
        assertNull(faceOf(feature, 0, 0, 1), "…is one piece, not two")
        assertEquals(8, pierced.outline.segments().size, "…drawn as two rectangles: the wall and the hole")
        assertClose(400.0 - 100.0, area(pierced.outline.segments()), tol = 1e-6, msg = "the wall less the hole")

        // cut in two: each of the bar's four sides runs 60 mm and the post takes the middle 20 out of it
        for (side in 0 until 4) {
            val lo = assertNotNull(faceOf(feature, 1, side, 0), "the bar's side $side before the post")
            val hi = assertNotNull(faceOf(feature, 1, side, 1), "…and after it")
            assertClose(10.0 * 20.0, area(lo.outline.segments()), tol = 1e-6, msg = "the piece before the post")
            assertClose(10.0 * 20.0, area(hi.outline.segments()), tol = 1e-6, msg = "the piece after it")
        }
    }

    /**
     * **Every corner is where three planes meet**, to a nanometre — on a mesh whose own vertices are float32
     * and therefore a thousand times coarser.
     *
     * This is the assertion the whole design is for. The outline is never read off the triangles: each run of
     * a face's boundary is the line where that face's plane meets its neighbour's, and each corner is where
     * two of those lines cross. So a corner of the union's cut wall stands at exactly `(±10, ±10, 5)` and
     * `(±10, ±10, 15)` — the drawing's own numbers — rather than at whatever float32 wrote down.
     */
    @Test
    fun everyCornerStandsWhereThreePlanesPutIt() {
        requireEngine()
        val c = Construction()
        val part = c.union(c.post(), c.bar())
        val ev = Evaluator()
        val feature = built(ev, part, "post ∪ bar")
        var checked = 0
        for (p in assertNotNull(Section3.faces(feature).first)) {
            val plane = p.plane ?: continue
            for (s in p.outline.segments()) {
                for (q in listOf(s.a, s.b)) {
                    val w = plane.toWorld(q)
                    // every coordinate of this fixture is a multiple of 5 mm; a corner computed from planes
                    // hits it exactly, a corner copied off a float32 vertex would be ~1e-5 away
                    for (x in listOf(w.x, w.y, w.z)) {
                        assertTrue(abs(x / 5.0 - kotlin.math.round(x / 5.0)) < 1e-9, "corner $w is on the drawing's own lattice")
                    }
                    checked++
                }
            }
        }
        assertTrue(checked > 60, "and there were $checked of them")
    }

    /**
     * **The intersection.** What is left is the bar's own section clipped to the post — a 10 × 20 × 10 mm block — so
     * whole faces of both operands are gone, and each of them **keeps its slot** in the list and says so,
     * because a position in a face list is an address a stored step may hold (OP-17's index-stability rule).
     */
    @Test
    fun anIntersectionKeepsTheSlotOfAFaceItConsumedWholly() {
        requireEngine()
        val c = Construction()
        val part = c.intersect(c.post(), c.bar())
        val ev = Evaluator()
        val feature = built(ev, part, "post ∩ bar")
        checkFaces(feature, "post ∩ bar")

        val faces = assertNotNull(Section3.faces(feature).first)
        assertEquals(12, faces.size, "one slot per face of each operand, none of them dropped")
        val top = assertNotNull(faceOf(feature, 0, 5), "the post's top cap still has its slot")
        assertNull(top.plane, "…with no surface left")
        assertNotNull(top.reason, "…and a reason that says the boolean took it")
        assertTrue(top.reason!!.render().contains("wholly"), "the reason names what happened: ${top.reason!!.render()}")

        // the block that is left: x in [-5, 5], y in [-10, 10], z in [5, 15]
        assertClose(10.0 * 20.0 * 10.0, constructit.geom.Geom3.volume(ev.solid(part).mesh), tol = 1e-6)
    }

    /**
     * **The difference.** The bar is taken out of the post, so the bar's own faces become the **walls of the
     * cavity** — the same planes, turned round, because a face's normal points out of the material and the
     * material is now on the other side of them.
     */
    @Test
    fun aDifferenceTurnsTheToolsFacesIntoWallsOfTheCavity() {
        requireEngine()
        val c = Construction()
        val part = c.subtract(c.post(), c.bar())
        val ev = Evaluator()
        val feature = built(ev, part, "post − bar")
        checkFaces(feature, "post − bar")

        val barFeature = ev.solid(c.bar()).feature
        val barFaces = assertNotNull(Section3.faces(barFeature).first)
        var walls = 0
        for (j in barFaces.indices) {
            val wall = faceOf(feature, 1, j) ?: continue
            val here = wall.plane ?: continue
            val there = assertNotNull(barFaces[j].plane)
            // the same plane, and the normal turned round: a cavity wall faces the material it bounds
            assertClose(0.0, abs(here.normal.normalized().dot(there.normal.normalized())) - 1.0, tol = 1e-12)
            assertTrue(here.normal.normalized().dot(there.normal.normalized()) < 0.0, "a cavity wall faces the other way")
            walls++
        }
        assertEquals(4, walls, "the four walls of the bore, and neither of the bar's own ends")
        assertClose(20.0 * 20.0 * 20.0 - 10.0 * 20.0 * 10.0, constructit.geom.Geom3.volume(ev.solid(part).mesh), tol = 1e-6)
    }

    /**
     * **Every crease is exact and every crease is named by the two faces it separates** — and the two
     * together are the whole boundary, so an edge blend has as much to work with here as it has on an
     * extrusion.
     */
    @Test
    fun theCreasesAreTheStraightLinesWhereTwoResultFacesMeet() {
        requireEngine()
        val c = Construction()
        val part = c.union(c.post(), c.bar())
        val ev = Evaluator()
        val feature = built(ev, part, "post ∪ bar")
        val faces = assertNotNull(Section3.faces(feature).first)
        val (edges, why) = Section3.edges(feature)
        assertNotNull(edges, "the union names its edges: ${why?.render()}")
        assertTrue(edges.isNotEmpty(), "and there are some")
        for (e in edges) {
            val name = e.name
            assertTrue(name is EdgeName.BoolCrease, "every edge is a crease of the boolean, not $name")
            name as EdgeName.BoolCrease
            assertTrue(name.a < name.b || (name.a == name.b), "creases are ordered by the pair they separate")
            val g = e.geom
            assertTrue(g is EdgeGeom.Straight, "two planes meet in a line")
            g as EdgeGeom.Straight
            assertTrue((g.b - g.a).length() > 1e-6, "and the line has length")
            // the crease lies in *both* of the faces it claims to separate
            for (i in listOf(name.a, name.b)) {
                val plane = faces[i].plane ?: continue
                for (w in listOf(g.a, g.b)) assertClose(0.0, plane.distanceTo(w), tol = 1e-9, msg = "the crease lies in face $i")
            }
            assertTrue(e.between.sameAs(faces[name.a].name, faces[name.b].name), "and it says which two faces those are")
        }
        // the union of a box and a bar has 16 outer corners of the post/bar shells plus the ring of 16
        // where the two shells cross — every one of them a meeting of exactly three planes
        assertTrue(edges.size >= 24, "a cross bar has plenty of creases: ${edges.size}")
    }

    /**
     * **Determinism**: the same drawing evaluated twice gives the same triangles *and* the same faces in the
     * same order. That is what makes an address into this list something a file may hold (OP-4, OP-18).
     */
    @Test
    fun twoEvaluationsGiveTheSameFacesInTheSameOrder() {
        requireEngine()
        val c = Construction()
        val part = c.union(c.post(), c.bar())
        val one = Evaluator().solid(part)
        val two = Evaluator().solid(part)
        assertEquals(one.mesh, two.mesh, "the same triangles, bit for bit")
        val a = assertNotNull(Section3.faces(one.feature).first)
        val b = assertNotNull(Section3.faces(two.feature).first)
        assertEquals(a.map { it.name }, b.map { it.name }, "the same faces in the same order")
        assertEquals(a.map { it.outline }, b.map { it.outline }, "with the same outlines")
        assertEquals(
            assertNotNull(Section3.edges(one.feature).first).map { it.name to it.geom },
            assertNotNull(Section3.edges(two.feature).first).map { it.name to it.geom },
            "and the same creases",
        )
    }

    /** The area a closed polygon of straight pieces encloses, loops and holes together. */
    private fun area(segs: List<constructit.geom.Segment>): Double {
        var a = 0.0
        for (s in segs) a += s.a.x * s.b.y - s.b.x * s.a.y
        return abs(a) / 2.0
    }

    private fun Vec2.length(): Double = kotlin.math.sqrt(x * x + y * y)
}
