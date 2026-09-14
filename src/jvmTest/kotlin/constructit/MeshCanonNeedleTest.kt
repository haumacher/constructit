package constructit

import constructit.geom.Geom3
import constructit.geom.Mesh3
import constructit.geom.MeshCanon
import constructit.geom.Tri
import constructit.geom.Vec3
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * **The needle a kernel leaves at a T-junction, and the repair that takes it out** (OP-31, slice 5u).
 *
 * *What the defect is.* A general boolean may put a vertex exactly **on an edge** of a triangle it does not
 * split, and close the surface with the degenerate ear between the two: three vertices of the body, hundreds
 * of weld cells apart, collinear to a fraction of a nanometre. Nothing the drawing had could see it —
 * `MeshCanon.canonicalWith` welds vertices onto vertices and these three are genuinely distinct,
 * `MeshCanon.fault` finds every directed edge used once each way, and the volume integral is blind to a
 * triangle of no area — and nothing could repair it, because a weld is the wrong move: the middle vertex is
 * a real vertex of the body and moving it onto either end would move real surface by micrometres.
 *
 * *What the repair is.* The ear is dropped and the triangle **across its long edge** is split at the middle
 * vertex, which is the only move that leaves every directed edge used once each way and moves the surface by
 * the needle's own height and by nothing else. It runs inside `MeshCanon.finish`, so both engines' output
 * passes through it.
 *
 * *What a needle is, as opposed to a sliver.* Measured over this suite, the two families stand two decades
 * apart and the measurement is what makes the repair possible: a needle's corners are collinear to the
 * arithmetic that placed them (5.15e-11 mm on a 40 mm body, one part in 10^12), while a **sliver** the
 * tessellation honestly has — two nearly parallel chords — is collinear only to one part in 10^8, and
 * fifty-nine of those stand between 1.5e-6 and 5.7e-6 mm of height against a 4.8e-6 mm weld lattice. So the
 * weld lattice is emphatically **not** the bar for either the repair or `assertManifold`'s own statement of
 * *degenerate*; `MeshCanon.straightTol` is, and this class states both sides of that.
 */
class MeshCanonNeedleTest {
    private val w = 40.0
    private val d = 30.0
    private val h = 20.0

    /** The block as eight corners and twelve triangles, wound outward. */
    private fun box(): Mesh3 {
        val vs =
            listOf(
                Vec3(0.0, 0.0, 0.0),
                Vec3(w, 0.0, 0.0),
                Vec3(w, d, 0.0),
                Vec3(0.0, d, 0.0),
                Vec3(0.0, 0.0, h),
                Vec3(w, 0.0, h),
                Vec3(w, d, h),
                Vec3(0.0, d, h),
            )
        val ts =
            listOf(
                Tri(0, 2, 1), Tri(0, 3, 2),
                Tri(4, 5, 6), Tri(4, 6, 7),
                Tri(0, 1, 5), Tri(0, 5, 4),
                Tri(3, 7, 6), Tri(3, 6, 2),
                Tri(0, 4, 7), Tri(0, 7, 3),
                Tri(1, 2, 6), Tri(1, 6, 5),
            )
        return Mesh3(vs, ts)
    }

    /**
     * The same block with a **T-junction** put into its bottom-front edge: a ninth vertex `m` standing on
     * the segment from `(0, 0, 0)` to `(40, 0, 0)`, off its line by [off] mm; the bottom triangle that used
     * that edge split at it, and the degenerate ear closing what is left.
     */
    private fun withNeedle(off: Double): Mesh3 {
        val base = box()
        val vs = base.vertices + Vec3(12.0, off, 0.0)
        val m = vs.size - 1
        val ts = ArrayList<Tri>()
        for (t in base.triangles) {
            // the bottom triangle using the edge 1 -> 0, split at m: (1, 0, 2) becomes (1, m, 2), (m, 0, 2)
            if (t == Tri(0, 2, 1)) {
                ts.add(Tri(1, m, 2))
                ts.add(Tri(m, 0, 2))
            } else {
                ts.add(t)
            }
        }
        // …and the ear the kernel leaves behind, 0 -> m -> 1, whose long edge is the unsplit one
        ts.add(Tri(0, m, 1))
        return Mesh3(vs, ts)
    }

    /** Every directed edge used once, with exactly one opposite use — the closedness half, stated here too. */
    private fun assertClosed(
        mesh: Mesh3,
        what: String,
    ) {
        val uses = HashMap<Pair<Int, Int>, Int>()
        for (t in mesh.triangles) {
            for (e in listOf(t.a to t.b, t.b to t.c, t.c to t.a)) uses[e] = (uses[e] ?: 0) + 1
        }
        for ((e, n) in uses) {
            assertEquals(1, n, "$what: edge ${e.first}->${e.second} is used $n times")
            assertEquals(1, uses[e.second to e.first] ?: 0, "$what: edge ${e.first}->${e.second} has no single opposite")
        }
    }

    /**
     * **The needle is a fault the drawing can see, and the repair takes it out and nothing else.** The mesh
     * before is closed, positively wound and carries a triangle of millimetre sides whose height is a
     * fraction of a nanometre; after it is closed, carries no such triangle, and encloses the same volume to
     * the needle's own height.
     */
    @Test
    fun aTJunctionsNeedleIsRepairedAndTheShellStaysClosed() {
        val off = 5.0e-11
        val bad = withNeedle(off)
        assertClosed(bad, "the block with a needle in its bottom edge")
        assertEquals(14, bad.triangles.size, "twelve triangles, one split and one ear")
        // …and it is exactly the shape the honest statement names: sides of millimetres, no height
        val ear = bad.triangles.last()
        val a = bad.vertices[ear.a]
        val b = bad.vertices[ear.b]
        val c = bad.vertices[ear.c]
        val sides = listOf((b - a).length(), (c - b).length(), (a - c).length())
        val height = (b - a).cross(c - a).length() / sides.max()
        assertTrue(sides.min() > 1.0, "the needle's own sides are millimetres: $sides")
        assertClose(height, off, 1e-13, "…and its height is the arithmetic's own residue")
        assertTrue(height < MeshCanon.straightTol(bad.vertices), "…which is below what arithmetic places a point to")
        assertTrue(height < MeshCanon.weldTol(bad.vertices), "…and below the weld lattice as well")
        // the check is proved against the defect: `assertManifold` refuses this mesh, naming the shape
        val why =
            try {
                assertManifold(bad, "the block with a needle")
                null
            } catch (e: AssertionError) {
                e.message
            }
        val words = assertNotNull(why, "a needle is a fault this suite states")
        for (part in listOf("degenerate", "sides=", "height=", "straight=", "lattice=")) {
            assertTrue(part in words, "…naming $part: $words")
        }

        val (good, tags) = MeshCanon.repairNeedles(bad, IntArray(bad.triangles.size) { it })
        assertEquals(14, good.triangles.size, "the ear goes and the triangle across its long edge is split")
        assertEquals(good.triangles.size, tags.size, "…and every triangle still says where it came from")
        assertClosed(good, "the repaired block")
        assertManifold(good, "the repaired block")
        // **the surface moves by the needle's own height and by nothing else**, and the bound is stated
        // rather than guessed: the repair slides the split face's own middle corner by [off], so what the
        // body may gain or lose is that height over the face it splits — 4e-8 mm^3 of 24,000 here, and the
        // measured move is 6.7e-9.
        assertClose(
            Geom3.volume(good),
            w * d * h,
            off * w * h,
            "the repair moves the surface by the needle's own height and by nothing else",
        )
        // …and it is a fixed point: a mesh with no needle comes back unchanged, by identity
        val (again, _) = MeshCanon.repairNeedles(good, tags)
        assertTrue(again === good, "a mesh with no needle is not rewritten at all")
    }

    /**
     * **A sliver is not a needle, and the repair leaves it alone.** A triangle the tessellation honestly has
     * — thinner than the weld lattice and two decades above the arithmetic — is what a chord of a curved
     * face looks like near a tangency, and repairing it would re-triangulate a face for nothing.
     */
    @Test
    fun anHonestSliverIsNotRepairedAndIsNoFault() {
        val off = 2.0e-6
        val mesh = withNeedle(off)
        assertTrue(off < MeshCanon.weldTol(mesh.vertices), "the sliver stands below the weld lattice")
        assertTrue(off > MeshCanon.straightTol(mesh.vertices), "…and two decades above the arithmetic")
        assertManifold(mesh, "the block with a sliver in its bottom edge")
        val (out, _) = MeshCanon.repairNeedles(mesh, IntArray(mesh.triangles.size) { it })
        assertTrue(out === mesh, "the repair leaves an honest thin triangle exactly where it is")
    }

    /**
     * **Every boolean's output passes through the repair**, which is what makes this a statement about the
     * drawing and not about one caller: `MeshCanon.finish` is the one seam both engines' shims end in.
     */
    @Test
    fun finishHandsBackAMeshWithNoNeedleInIt() {
        val bad = withNeedle(5.0e-11)
        val (out, why) = MeshCanon.finish(bad, IntArray(bad.triangles.size) { it })
        assertNull(why, "the block with a needle is a body, not a refusal: $why")
        val mesh = assertNotNull(out, "…and it comes back as one").mesh
        assertManifold(mesh, "the finished block")
        assertClose(Geom3.volume(mesh), w * d * h, 5.0e-11 * w * h, "…enclosing the block it is")
        for (t in mesh.triangles) {
            val a = mesh.vertices[t.a]
            val b = mesh.vertices[t.b]
            val c = mesh.vertices[t.c]
            val sides = listOf((b - a).length(), (c - b).length(), (a - c).length())
            val height = (b - a).cross(c - a).length() / sides.max()
            if (height <= MeshCanon.straightTol(mesh.vertices)) fail("the finished mesh still carries a needle: sides=$sides height=$height")
        }
        assertTrue(abs(Geom3.volume(mesh) - w * d * h) < 5.0e-11 * w * h, "and the volume is the block's")
    }
}
