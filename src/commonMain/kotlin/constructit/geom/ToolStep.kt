package constructit.geom

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * **A tangency the drawing knows about is decided by the drawing, never handed to the kernel** (OP-31,
 * slice 5q) — and the one number that decides it, said once.
 *
 * *The rule, and why it is one rule and not five.* Since GitHub #33 this drawing has kept its tools off the
 * bodies they work on — *a tool never shares a face with the body* — and it has said so five times: the
 * micron a cut's sketch is stood proud of the face it is drawn on (`Geom3.cutTool`), the micron a blend's
 * stitched section is stepped outside its own two faces ([Blend3]'s own `GROW_MM`), the *wall's own skin*
 * a canal's section is carried off a curved wall by (slice 5f), the same skin a cap is carried off an
 * upright by (slice 5o), and the step a free end's ring is carried past the last station by (slice 5p).
 * They are all the same sentence about the same thing, and from this slice they are all this object.
 *
 * *Why the number is not one number.* A tool face lying in a **planar** body face is parted by anything at
 * all: a plane is stated exactly by the mesh, so a micron of air between the two is a micron of air. A tool
 * face rolling on a **curved** one is not, because the body's triangles stand **inside** its true surface by
 * as much as [GeomMath.effectiveTol] — twenty times the micron at drawing sizes — so a tool a micron proud
 * of the true surface is still a fifth of a tessellation tolerance *behind the body's own skin*, and the two
 * surfaces cross each other in a band as wide as the chords are. So a curved face's step-off is the body's
 * own skin: twice the tessellation tolerance there, since either surface may stand a whole tolerance inside
 * its own truth, and never less than the micron.
 *
 * *And the last resort, at the seam.* Everything above is a step a **builder** takes, and a builder can only
 * step off what it knows it is standing on. Two operands the **user** drew against each other — a foundation
 * hugging the pillar it was swept around, a boss standing exactly on a face — are tangent by the drawing's
 * own intent, no builder is involved, and under a float64 engine that contact stays exactly tangent and has
 * no watertight answer (a zero-thickness flap, an edge used twice). [parted] is that case decided by the
 * drawing: where the two operands' meshes **share a plane** — an exact coincidence, not a measurement — the
 * contact is a statement rather than an accident, and the second operand is carried off that plane by [MM]
 * on the side the operation makes irrelevant (into the material a union keeps, into the void a difference
 * already removes). Where the drawing cannot say two surfaces are coincident — a tangency along a curve
 * between two curved faces, whose two tessellations are not even the same chords — nothing is moved and the
 * engine's own refusal stands, named ([MeshBool]'s *"a tangent or self-touching contact has no watertight
 * mesh"*).
 *
 * *And it is asked only where the kernel has already said it cannot close the result* (`Geom3.combine`),
 * which is what keeps it honest in both directions: no body this drawing builds today moves by so much as a
 * micron, because a boolean that answers is never asked twice; and a refusal the drawing can itself resolve
 * stops being one. Two bolder shapes were built first and both were discarded on their own evidence —
 * restating every shared-plane contact up front moved real figures **and** cost a fused body its face names
 * (the parted operand's triangles no longer lie on its own exact carriers, so `Section3.boolProvenance`
 * cannot trace them back), and taking the micron back out of the result afterwards left a ring of zero-area
 * slivers where the micron-thick slab had been.
 */
object ToolStep {
    /**
     * How far a tool stands off a **planar** face of the body it works on, in mm.
     *
     * A micron: six orders below any feature a drawing has and five above the general engine's own float32
     * noise, which is the window it was measured into (GitHub #33).
     */
    const val MM = 1e-3

    /**
     * How far a tool stands off a **curved** face of radius [radius] — the body's own skin, never less
     * than [MM]. See this object's own note for why it is twice the tessellation tolerance.
     */
    fun offCurve(
        radius: Double,
        floorMm: Double = GeomMath.TESS_TOL_MM,
    ): Double = max(MM, 2.0 * GeomMath.effectiveTol(max(abs(radius), Geom3.WELD_TOL), floorMm))

    /** [offCurve] where [radius] is given, [MM] where it is null — *"a plane, or a curve of this radius"*. */
    fun off(
        radius: Double?,
        floorMm: Double = GeomMath.TESS_TOL_MM,
    ): Double = if (radius == null) MM else offCurve(radius, floorMm)

    // ---- the seam's own last resort: two operands the drawing itself laid tangent ----

    /**
     * The second operand of a general boolean, **carried off the planes the drawing put the two of them in**
     * — or null where the drawing says they share no plane, which is the overwhelming majority of booleans
     * and costs two hash passes over the triangles.
     *
     * Where they do share one, every vertex of [b] that lies *in* that plane is carried [MM] along it:
     * **away** from [a]'s material for a difference or an intersection (the tool retreats into the void it
     * was removing anyway), **into** it for a union (the two bodies genuinely overlap by a micron, which is
     * what a union of two bodies that share a face means). Connectivity is untouched, so [b] stays exactly
     * as watertight as it was; what moves is a micron of the contact itself, and a micron is the number
     * every builder in this drawing already steps off by.
     */
    fun parted(
        kind: BoolOp,
        a: Mesh3,
        b: Mesh3,
    ): Mesh3? {
        val shared = sharedPlanes(a, b)
        if (shared.isEmpty()) return null
        // `+1` carries a vertex of [b] out along [a]'s outward normal — out of [a]'s material.
        val sense = if (kind == BoolOp.UNION) -1.0 else 1.0
        val moved =
            b.vertices.map { v ->
                var d = Vec3.ZERO
                for (p in shared) {
                    if (abs(p.normal.dot(v) - p.offset) <= MM) d += p.normal * (sense * MM)
                }
                if (d == Vec3.ZERO) v else v + d
            }
        return Mesh3(moved, b.triangles)
    }

    /**
     * One plane both operands have a face in, stated with **[a]'s own outward normal** — and whether the
     * second operand's face there points **against** it ([opposed]) or the same way.
     *
     * The difference is the whole of what a union has to know. Two coplanar caps that point the **same** way
     * are no degeneracy at all: each face is used once each way, the union keeps one of them, and this is
     * what a fill's own cap standing in the wall it fills against has always relied on ([Blend3]'s butt
     * ends). Two that point **against** each other back onto one another and enclose nothing between them,
     * which is the self-touching contact no watertight mesh carries.
     */
    internal class Shared(val normal: Vec3, val offset: Double, val opposed: Boolean)

    /**
     * The planes both meshes have triangles in, found by hashing each mesh's triangle planes onto a coarse
     * lattice and probing the neighbourhood — so a plane the two arrived at by different arithmetic is still
     * found, while the test that decides it is **exact** (parallel to 1e-9, coincident to a nanometre).
     *
     * A pair is kept only where the two faces also **overlap in the plane**, by their own projected boxes:
     * two faces that lie in one infinite plane and nowhere near each other are not a contact, and moving one
     * of them would be a micron taken off a body for nothing.
     */
    internal fun sharedPlanes(
        a: Mesh3,
        b: Mesh3,
        opposedOnly: Boolean = false,
    ): List<Shared> {
        val pa = planesOf(a)
        if (pa.isEmpty()) return emptyList()
        val pb = planesOf(b)
        if (pb.isEmpty()) return emptyList()
        val byCell = HashMap<Long, ArrayList<Face>>()
        for (f in pa) byCell.getOrPut(cell(f.normal, f.offset)) { ArrayList() }.add(f)
        val out = ArrayList<Shared>()
        for (f in pb) {
            // **both orientations**: a face of [b] that lies *against* a face of [a] carries the opposite
            // normal, and the hash is on the oriented plane, so the flipped cell has to be probed too.
            for (c in cellsAround(f.normal, f.offset) + cellsAround(-f.normal, -f.offset)) {
                for (g in byCell[c] ?: continue) {
                    if (!samePlane(g, f)) continue
                    if (!overlap(g, f)) continue
                    val opposed = g.normal.dot(f.normal) < 0.0
                    val had = out.firstOrNull { abs(it.normal.dot(g.normal) - 1.0) <= PARALLEL_TOL && abs(it.offset - g.offset) <= Geom3.WELD_TOL }
                    if (had == null) {
                        out.add(Shared(g.normal, g.offset, opposed))
                    } else if (opposed && !had.opposed) {
                        out[out.indexOf(had)] = Shared(had.normal, had.offset, true)
                    }
                }
            }
        }
        return if (opposedOnly) out.filter { it.opposed } else out
    }

    /** Whether [g] (of the first mesh) and [f] (of the second) are the **same** plane, either way round. */
    private fun samePlane(
        g: Face,
        f: Face,
    ): Boolean {
        val dot = g.normal.dot(f.normal)
        if (abs(abs(dot) - 1.0) > PARALLEL_TOL) return false
        val off = if (dot > 0.0) f.offset else -f.offset
        return abs(g.offset - off) <= Geom3.WELD_TOL
    }

    /** Whether the two faces' own boxes meet at all — a cheap *"is this a contact"* before a micron is spent. */
    private fun overlap(
        g: Face,
        f: Face,
    ): Boolean =
        g.lo.x <= f.hi.x + MM && f.lo.x <= g.hi.x + MM &&
            g.lo.y <= f.hi.y + MM && f.lo.y <= g.hi.y + MM &&
            g.lo.z <= f.hi.z + MM && f.lo.z <= g.hi.z + MM

    /** One plane of one mesh, with the box its triangles occupy. */
    private class Face(val normal: Vec3, val offset: Double) {
        var lo = Vec3(Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE)
        var hi = Vec3(-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE)

        fun grow(p: Vec3) {
            lo = Vec3(min(lo.x, p.x), min(lo.y, p.y), min(lo.z, p.z))
            hi = Vec3(max(hi.x, p.x), max(hi.y, p.y), max(hi.z, p.z))
        }
    }

    /** How nearly two normals must agree to be the same direction — floating-point noise, not a tolerance. */
    private const val PARALLEL_TOL = 1e-9

    /** The lattice the plane hash is taken on: coarse enough that one plane lands within one cell of itself. */
    private const val CELL = 1e-6

    private fun planesOf(m: Mesh3): List<Face> {
        val byCell = HashMap<Long, ArrayList<Face>>()
        val out = ArrayList<Face>()
        for (t in m.triangles) {
            val p0 = m.vertices[t.a]
            val p1 = m.vertices[t.b]
            val p2 = m.vertices[t.c]
            val n0 = (p1 - p0).cross(p2 - p0)
            val len = n0.length()
            if (len <= 0.0) continue
            val n = n0 * (1.0 / len)
            val d = n.dot(p0)
            var found: Face? = null
            for (c in cellsAround(n, d)) {
                found = (byCell[c] ?: continue).firstOrNull { abs(it.normal.dot(n) - 1.0) <= PARALLEL_TOL && abs(it.offset - d) <= Geom3.WELD_TOL }
                if (found != null) break
            }
            val face =
                found ?: Face(n, d).also {
                    byCell.getOrPut(cell(n, d)) { ArrayList() }.add(it)
                    out.add(it)
                }
            face.grow(p0)
            face.grow(p1)
            face.grow(p2)
        }
        return out
    }

    private fun cell(
        n: Vec3,
        d: Double,
    ): Long = key(q(n.x), q(n.y), q(n.z), q(d))

    /** The cell and its immediate neighbours in all four coordinates — 81 of them, probed on lookup only. */
    private fun cellsAround(
        n: Vec3,
        d: Double,
    ): List<Long> {
        val out = ArrayList<Long>(81)
        val qx = q(n.x)
        val qy = q(n.y)
        val qz = q(n.z)
        val qd = q(d)
        for (i in -1..1) {
            for (j in -1..1) {
                for (k in -1..1) {
                    for (l in -1..1) out.add(key(qx + i, qy + j, qz + k, qd + l))
                }
            }
        }
        return out
    }

    private fun q(x: Double): Long = (x / CELL).roundToLong()

    private fun key(
        a: Long,
        b: Long,
        c: Long,
        d: Long,
    ): Long = a * 0x9E3779B97F4A7C15uL.toLong() xor (b * 0xC2B2AE3D27D4EB4FuL.toLong()) xor (c * 0x165667B19E3779F9uL.toLong()) xor (d * 0x27D4EB2F165667C5uL.toLong())
}
