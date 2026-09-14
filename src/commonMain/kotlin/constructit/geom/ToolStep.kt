package constructit.geom

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sqrt

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

    /**
     * **A step-off reads the body it is standing on, not the plane the drawing names** (OP-31, slice 5v) —
     * how far a tool must stand off the plane `(normal, offset)`, stepping along [normal], so that no facet
     * [body] really has there is left flush with it.
     *
     * *Why the stated plane is not enough.* A face of a dressed body is very often **not** where the drawing
     * names it: the tool that cut it stepped itself [MM] off the nominal plane, and what the boolean left
     * behind is a facet a micron away from the plane the face list still states — a micron-thick ledge along
     * a band's own leg, a cap the previous free end overshot. The next tool steps off the **nominal** plane
     * by the same micron and lands exactly on that facet, which is the very coplanar pair the whole rule
     * exists to abolish. Over the matrix's 288 two-edge cells that was sixteen difference tools and
     * thirty-two union tools, and it was the one residue slice 5q wrote down.
     *
     * *So the step composes, and it is stated rather than accumulated.* The body's own triangles say where
     * its facets are — a measurement the tool already has the triangles for, and the only honest reading of
     * a face the drawing does not name at all. Every facet parallel to [normal], standing within [window] of
     * the stated plane and overlapping the contact box `(lo, hi)`, is taken in order, and a step that would
     * land on one is carried past it by another [skin]. A chain of *n* tools therefore steps *n* skins, each
     * one stated by the body it stands on rather than assumed, and a tool standing off an undressed face
     * steps exactly the one skin it always did — which is why no body this drawing already builds moves.
     *
     * The contact box is what keeps a micron from being spent for nothing: two faces in one plane and
     * nowhere near each other are not a contact, and [parted]'s own overlap test is this one.
     */
    fun clear(
        body: Mesh3?,
        normal: Vec3,
        offset: Double,
        skin: Double,
        lo: Vec3,
        hi: Vec3,
        window: Double = WINDOW * skin,
    ): Double {
        if (body == null) return skin
        val ds = ArrayList<Double>()
        for (t in body.triangles) {
            val p0 = body.vertices[t.a]
            val p1 = body.vertices[t.b]
            val p2 = body.vertices[t.c]
            val n0 = (p1 - p0).cross(p2 - p0)
            val len = n0.length()
            if (len <= 0.0) continue
            val dot = n0.dot(normal) / len
            if (abs(abs(dot) - 1.0) > PARALLEL_TOL) continue
            val d = normal.dot(p0) - offset
            if (d < -window || d > window) continue
            if (!meets(p0, p1, p2, lo, hi)) continue
            ds.add(d)
        }
        if (ds.isEmpty()) return skin
        ds.sort()
        var step = skin
        for (d in ds) if (d >= step - Geom3.WELD_TOL && d <= step + Geom3.WELD_TOL) step = d + skin
        return step
    }

    /** How many skins out from the stated plane [clear] still looks for a facet — a few, never a feature. */
    private const val WINDOW = 64.0

    /** Whether the triangle's own box meets the contact box — [clear]'s *"is this a contact at all"*. */
    private fun meets(
        p0: Vec3,
        p1: Vec3,
        p2: Vec3,
        lo: Vec3,
        hi: Vec3,
    ): Boolean {
        val tlo = Vec3(min(min(p0.x, p1.x), p2.x), min(min(p0.y, p1.y), p2.y), min(min(p0.z, p1.z), p2.z))
        val thi = Vec3(max(max(p0.x, p1.x), p2.x), max(max(p0.y, p1.y), p2.y), max(max(p0.z, p1.z), p2.z))
        return tlo.x <= hi.x + MM && lo.x <= thi.x + MM &&
            tlo.y <= hi.y + MM && lo.y <= thi.y + MM &&
            tlo.z <= hi.z + MM && lo.z <= thi.z + MM
    }

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

    // ---- and the tangency two *named* surfaces decide (OP-31, slice 5w) ----

    /**
     * **A tangency between two named surfaces is decided by what they are, not by how near two
     * tessellations come** (OP-31, slice 5w) — the second side [parted] never had.
     *
     * *The argument.* [parted] asks the two operands' **meshes** whether they share a plane, which is an
     * exact question because a plane is stated exactly by a triangle. Along a **curve** there is no such
     * question to ask of triangles: two tangent curved faces are not even chorded the same way, and session
     * 86 measured what happens to a drawing that guesses — a skin stepped on a measurement took one corner
     * cell from 0 to 103 bad vertices. But the two faces are not triangles: each is a **named surface**, and
     * *a sphere of radius `r` centred on the axis of a cylinder of radius `r` touches that cylinder along
     * one circle* is a fact about the two statements, exact and decidable before any mesh is looked at. That
     * is what a tool's own provenance buys (slice 5w's first half): until it, every tool `Blend3` built
     * named no surface at all and this predicate had one side.
     *
     * *What is done about it.* The second operand's facets **on that face** are carried off by the face's
     * own skin ([offCurve], never the micron — the two surfaces are curved and their triangles stand a
     * tessellation tolerance inside their own truth), along the face's own gradient, on the side the
     * operation makes irrelevant: out of the first operand's material for a difference or an intersection,
     * into it for a union. Connectivity is untouched, so the operand stays exactly as watertight as it was.
     *
     * *And it is asked only where the kernel has already refused* (`Geom3.combine`), exactly as [parted] is,
     * which is what keeps it honest in both directions: no body this drawing builds today moves, because a
     * boolean that answers is never asked twice.
     *
     * Null where the two face lists name no such pair — and then nothing is moved and the engine's own
     * refusal stands, named.
     */
    fun untangled(
        kind: BoolOp,
        a: List<FacePatch>,
        b: List<FacePatch>,
        mesh: Mesh3,
    ): Mesh3? {
        for (fb in b) {
            val skin = curveSkin(fb) ?: continue
            for (fa in a) {
                val at = tangentAlongACurve(fa, fb) ?: continue
                // the gap opens where the second operand leaves the first's material, and a union wants the
                // other side: the two bodies then genuinely overlap by a skin, which is what a union of two
                // tangent bodies means
                val ga = gradientOf(fa, at) ?: continue
                val gb = gradientOf(fb, at) ?: continue
                val dot = ga.dot(gb)
                if (abs(dot) <= 0.5) continue
                val sense = if (kind == BoolOp.UNION) -1.0 else 1.0
                val step = sense * skin * (if (dot >= 0.0) 1.0 else -1.0)
                val lim = TOUCH_TOL + fb.slack
                var moved = 0
                val out =
                    mesh.vertices.map { v ->
                        val d = offSurface(fb, v)
                        if (d == null || abs(d) > lim) {
                            v
                        } else {
                            val g = gradientOf(fb, v)
                            if (g == null) {
                                v
                            } else {
                                moved++
                                v + g * step
                            }
                        }
                    }
                if (moved > 0) return Mesh3(out, mesh.triangles)
            }
        }
        return null
    }

    /** How near a vertex must come to a face's own surface to count as standing on it, in mm. */
    private const val TOUCH_TOL = 1e-6

    /**
     * How far a face standing tangent along a curve is carried off — the **body's own skin** at its own
     * radius, and null for a face this rule has nothing to say about (a plane, whose contact [parted]
     * decides exactly, and a surface with no radius to read).
     */
    private fun curveSkin(p: FacePatch): Double? {
        val band = p.surface?.band ?: return null
        val r =
            when (band) {
                is Revolve3.Band.Cylinder -> band.r
                is Revolve3.Band.Sphere -> band.radius
                is Revolve3.Band.Torus -> band.minor
                else -> return null
            }
        return if (r <= Geom3.WELD_TOL) null else offCurve(r)
    }

    /**
     * **Where two named surfaces touch along a curve**, as one point of that curve — or null where the two
     * of them, being what they are, do not.
     *
     * Three statements and no measurement, each of them an identity between the two surfaces' own
     * parameters: a **sphere in a cylinder** of the same radius, its centre on the axis (they touch along
     * the great circle square to the axis); a **sphere in a torus** of the same minor radius, its centre on
     * the torus' own centre circle; and two **parallel cylinders** standing exactly the sum or the
     * difference of their radii apart (they touch along a ruling). Everything else this drawing can say two
     * surfaces are is either a crossing, a coincidence or nothing at all, and says so by answering null.
     */
    private fun tangentAlongACurve(
        fa: FacePatch,
        fb: FacePatch,
    ): Vec3? {
        val sa = fa.surface ?: return null
        val sb = fb.surface ?: return null
        sphereInCylinder(sa, sb)?.let { return it }
        sphereInCylinder(sb, sa)?.let { return it }
        sphereInTorus(sa, sb)?.let { return it }
        sphereInTorus(sb, sa)?.let { return it }
        return parallelCylinders(sa, sb)
    }

    /** A sphere of radius `r` whose centre stands **on** the axis of a cylinder of the same radius. */
    private fun sphereInCylinder(
        s: Surface3,
        c: Surface3,
    ): Vec3? {
        val ball = s.band as? Revolve3.Band.Sphere ?: return null
        val cyl = c.band as? Revolve3.Band.Cylinder ?: return null
        if (abs(ball.radius - cyl.r) > SURFACE_TOL) return null
        val centre = s.origin + s.axis * ball.sc
        val rel = centre - c.origin
        val radial = rel - c.axis * rel.dot(c.axis)
        if (radial.length() > SURFACE_TOL) return null
        // one point of the circle they touch along: square to the cylinder's axis, a radius out
        return centre + c.ref.normalized() * cyl.r
    }

    /** A sphere whose centre stands on the **centre circle** of a torus of the same minor radius. */
    private fun sphereInTorus(
        s: Surface3,
        t: Surface3,
    ): Vec3? {
        val ball = s.band as? Revolve3.Band.Sphere ?: return null
        val ring = t.band as? Revolve3.Band.Torus ?: return null
        if (abs(ball.radius - ring.minor) > SURFACE_TOL) return null
        val centre = s.origin + s.axis * ball.sc
        val rel = centre - t.origin
        val axial = rel.dot(t.axis)
        val radial = rel - t.axis * axial
        if (abs(axial - ring.sc) > SURFACE_TOL) return null
        if (abs(radial.length() - ring.rc) > SURFACE_TOL) return null
        if (radial.length() <= Geom3.WELD_TOL) return null
        return centre + radial.normalized() * ball.radius
    }

    /** Two cylinders with parallel axes standing exactly the sum or the difference of their radii apart. */
    private fun parallelCylinders(
        a: Surface3,
        b: Surface3,
    ): Vec3? {
        val ca = a.band as? Revolve3.Band.Cylinder ?: return null
        val cb = b.band as? Revolve3.Band.Cylinder ?: return null
        if (abs(abs(a.axis.dot(b.axis)) - 1.0) > PARALLEL_TOL) return null
        val rel = b.origin - a.origin
        val off = rel - a.axis * rel.dot(a.axis)
        val d = off.length()
        if (d <= Geom3.WELD_TOL) return null
        if (abs(d - (ca.r + cb.r)) > SURFACE_TOL && abs(d - abs(ca.r - cb.r)) > SURFACE_TOL) return null
        return a.origin + a.axis * rel.dot(a.axis) + off.normalized() * ca.r
    }

    /** How nearly two radii or two centres must agree for the identity to hold — an equality, not a fit. */
    private const val SURFACE_TOL = 1e-7

    /** How far [p] stands off the surface [f] states, or null where it states none. */
    private fun offSurface(
        f: FacePatch,
        p: Vec3,
    ): Double? = if (f.surface == null) null else BoolFace3.offSurface(f, p)

    /** The outward unit normal of [f]'s own surface at [p], or null where it has none there. */
    private fun gradientOf(
        f: FacePatch,
        p: Vec3,
    ): Vec3? {
        val s = f.surface ?: return null
        val rel = p - s.origin
        val axial = rel.dot(s.axis)
        val radial = rel - s.axis * axial
        val r = radial.length()
        return when (val band = s.band) {
            is Revolve3.Band.Cylinder -> if (r <= Geom3.WELD_TOL) null else radial * (1.0 / r)
            is Revolve3.Band.Sphere -> {
                val d = p - (s.origin + s.axis * band.sc)
                if (d.length() <= Geom3.WELD_TOL) null else d.normalized()
            }
            is Revolve3.Band.Torus -> {
                val ds = axial - band.sc
                val dr = r - band.rc
                val h = sqrt(ds * ds + dr * dr)
                if (h <= Geom3.WELD_TOL || r <= Geom3.WELD_TOL) null else (s.axis * (ds / h) + radial * (1.0 / r) * (dr / h)).normalized()
            }
            else -> null
        }
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
