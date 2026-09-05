package constructit

import constructit.core.Evaluator
import constructit.dsl.SolidRef
import constructit.dsl.plane
import constructit.dsl.solid
import constructit.dsl.valueOf
import constructit.editor.Camera3
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.PlanePerspective
import constructit.editor.Tools
import constructit.geom.BoolOp
import constructit.geom.Geom3
import constructit.geom.Mesh3
import constructit.geom.MeshBool
import constructit.geom.MeshCanon
import constructit.geom.Solid3
import constructit.geom.Tri
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.units.mm
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **The flap check as a production gate** (session 82; GitHub #33's own by-product).
 *
 * Session 81 named the defect — two triangles sharing an edge whose unit normals are back-to-back, a surface
 * of zero thickness that every edge-use count and the volume integral are blind to — and shipped it
 * *test-side only*, because sixteen bodies the build made then carried one. Four families, each recorded at
 * its own assertion. This class is the two of them that had no home of their own, plus the gate itself:
 *
 * - **family 1**, a drill through a pyramid's slanted face, cured by stepping the *Cut* tool off the face it
 *   is sketched on ([Geom3.cutTool]) — the same rule the blend's tool follows;
 * - **family 2**, the near-tangent fuse, which is measured here and found **sound**: a real dihedral with
 *   real thickness, three decades outside the band, and therefore let through on purpose;
 * - **the gate**, which is [MeshCanon.fault] answering with the flap's own words so that a boolean that
 *   folds is an ordinary invalid node with a reason (OP-3) rather than a body nobody can slice.
 *
 * Families 3 and 4 keep their regression tests where their reporters' drawings are — `BlendRunTangencyTest`
 * and `FilletSupersedesCornerProbeTest` for the band that closes on an arc's own centre, `LoftTest`,
 * `SkinTest`, `SkinToolTest` and `LoftToolTest` for the correspondence that folds a band.
 */
class FlapGateTest {
    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerDown(s)
        pointerUp(s)
    }

    private fun Editor.type(digits: String) {
        for (c in digits) key(c.toString())
        key("Enter")
    }

    private fun Editor.solids(): List<Element> = doc.elements.filter { it.kind == ElementKind.SOLID }

    @Suppress("UNCHECKED_CAST")
    private fun Editor.meshOf(el: Element) = Evaluator().solid(el.ref as SolidRef).mesh

    // ---- family 1: a drill through a slanted face ----

    /** The acceptance pyramid: a 100 × 100 plan square with its apex 90 mm over the centre. */
    private fun pyramid(): Editor {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 100.0))
        ed.setTool(Tools.EXTRUDE_TO_POINT)
        ed.type("90")
        ed.click(Vec2(30.0, 0.0))
        ed.click(Vec2(50.0, 50.0))
        return ed
    }

    /**
     * **A drill through a pyramid's slanted face leaves no fold** — family 1, at its cause.
     *
     * The tool used to start exactly *on* the face it was sketched on, and the argument for that was
     * exactness. Two prisms along one axis never noticed, because the slab algebra answers them without a
     * mesh; a **slanted** face has no common axis, so the pair goes to Manifold — and a coplanar pair of
     * faces is a cancellation a float32 engine adjudicates by coin toss. Nine zero-thickness flaps came back
     * in the re-triangulation of that one face. Stepped a micron into the air the same drill comes back with
     * none, and the only surface the tool still shares with the body is its wall, which crosses the face
     * transversally.
     *
     * What is asserted: the body is flap-free, the tool stands exactly [Geom3.TOOL_STEP_MM] proud of the
     * face, the bore really took material, and the **volume is the volume the flush tool removed** — the
     * micron is air above the face, so it removes nothing (1e-6 relative, which is four decades above the
     * 7e-9 the change actually costs).
     */
    @Test
    fun aDrillThroughASlantedFaceLeavesNoFold() {
        val ed = pyramid()
        ed.setTool(Tools.SKETCH_ON_FACE)
        ed.click(Vec2(30.0, 0.0))
        val slant = sqrt(50.0 * 50.0 + 90.0 * 90.0)
        ed.setTool(Tools.CIRCLE_R)
        ed.type("6")
        ed.click(Vec2(0.0, slant * 0.6))
        ed.setTool(Tools.CUT)
        ed.type("40")
        ed.click(Vec2(6.0, slant * 0.6))
        val all = ed.solids()
        val mesh = ed.meshOf(all.last())
        assertManifold(mesh, "a pyramid drilled through its slanted face")
        assertNull(MeshCanon.fault(mesh), "and the production gate is silent on it")
        // the volume the flush tool removed, to 1e-6 relative: 295495.6507 with the fold, 295495.6528 without
        assertClose(Geom3.volume(mesh) / 295495.6507194861, 1.0, 1e-6, "the micron of air removes nothing")
        assertTrue(Geom3.volume(mesh) < 300000.0 - 100.0, "…and the bore did take material")

        // the tool itself: a micron off the face, measured along the face's own normal
        val face = Evaluator().plane(assertNotNull(ed.activeSpace.plane, "the face space has a plane"))
        val tool = ed.meshOf(all[all.size - 2])
        val out = tool.vertices.maxOf { (it - face.origin).dot(face.normal) }
        assertClose(out, Geom3.TOOL_STEP_MM, tol = 1e-9, msg = "the tool stands exactly one micron proud of the face")
        assertTrue(
            tool.vertices.minOf { (it - face.origin).dot(face.normal) } < -39.0,
            "…and still reaches the depth that was typed",
        )
    }

    /**
     * **A datum that leans through the material keeps its flush cap**, and this is the measurement that drew
     * the line: the same step-off applied there is not a micron of air but a micron-thick slab off the whole
     * cut cross-section — 2.26 mm³ on the 45° mitre below, a ten-thousandth of the body.
     *
     * So the step is asked of a **face** space and of nothing else ([Geom3.cutTool]), which is a fact about
     * the drawing (`SketchSpace.piece >= 0`) rather than a measurement, and a replay reaches the same answer
     * the click did.
     */
    @Test
    fun aDatumThatLeansThroughTheMaterialKeepsItsFlushCap() {
        assumeEngine()
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(80.0, 50.0))
        ed.activeScalar = ed.doc.newParameter("thickness", 20.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(40.0, 0.0))
        // a datum hinged on the plate's front bottom edge, leaning into the material at 45°: its plane is z = y
        ed.setTool(Tools.SKETCH_PLANE)
        ed.type("45")
        ed.click(Vec2(40.0, 0.0))
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(-10.0, -10.0))
        ed.click(Vec2(90.0, 60.0))
        ed.setTool(Tools.CUT)
        ed.type("40")
        ed.click(Vec2(40.0, -10.0))
        val mesh = ed.meshOf(ed.solids().last())
        assertManifold(mesh, "the mitred plate")
        // exactly the triangular prism {z > y}: ½·20·20·80, and not a micron less
        assertClose(Geom3.volume(mesh), 16000.0, tol = 0.05, msg = "no slab was taken off the cut cross-section")
    }

    // ---- family 2: the near-tangent fuse, measured ----

    /**
     * **The knife edge the band lets through is a real wedge, and this is the measurement that says so** —
     * family 2, closed as *sound* rather than cured.
     *
     * The user's drawing (session 63's plate and handle, `ChainCutFixture`) trims the handle by two mirrored
     * chains and fuses the result to its plate. Where the twice-trimmed handle's outline crosses the plate's
     * rim it does so at a very shallow angle, and the fuse leaves five pairs of wall triangles meeting at
     * dihedrals between 0.04° and 0.25°. Those are **knife edges**: two faces at a real, if thin, angle, with
     * material between them — not flaps, which have no thickness at all. The band is `FLAP_COS`, a dihedral
     * of 0.003°, and the sharpest of these stands more than a decade outside it.
     *
     * This is asserted rather than described because the alternative reading — a near-coincident contact the
     * construction should refuse or step off — would have taken the user's own body away. The numbers say it
     * is a wedge: every pair has a positive dihedral, and the material between the two faces grows linearly
     * away from the edge, which is what a wedge is and what a fold is not.
     */
    @Test
    fun theNearTangentFuseLeavesAKnifeEdgeAndNotAFlap() {
        assumeEngine()
        val fused = fusedPlateAndHandle()
        assertManifold(fused, "plate and twice-trimmed handle, fused")
        assertNull(MeshCanon.fault(fused), "the production gate passes it")
        val knives = knifeEdges(fused, cos = -1.0 + 1e-5)
        assertTrue(knives.size >= 5, "the drawing really does carry them: ${knives.size}")
        val sharpest = knives.min()
        assertTrue(sharpest > 0.02 && sharpest < 0.5, "the sharpest is a real dihedral of $sharpest°, not a fold")
        assertTrue(
            sharpest > 10.0 * flapBandDegrees(),
            "…and it stands more than a decade outside the flap band (${flapBandDegrees()}°)",
        )
    }

    /** The flap band `FLAP_COS`, as the dihedral it admits, in degrees. */
    private fun flapBandDegrees(): Double = 180.0 - acos(MeshCanon.FLAP_COS.coerceIn(-1.0, 1.0)) * 180.0 / PI

    /** Every pair of triangles sharing an edge whose normals are within [cos] of back-to-back, as dihedrals in degrees. */
    private fun knifeEdges(
        mesh: Mesh3,
        cos: Double,
    ): List<Double> {
        val owner = HashMap<Long, Int>(mesh.triangles.size * 4)

        fun key(
            a: Int,
            b: Int,
        ) = (a.toLong() shl 32) or (b.toLong() and 0xffffffffL)
        for ((i, t) in mesh.triangles.withIndex()) {
            for (e in listOf(t.a to t.b, t.b to t.c, t.c to t.a)) owner[key(e.first, e.second)] = i
        }

        fun normal(i: Int): Vec3? {
            val t = mesh.triangles[i]
            val a = mesh.vertices[t.a]
            val n = (mesh.vertices[t.b] - a).cross(mesh.vertices[t.c] - a)
            return if (n.length() <= Vec3.EPS) null else n.normalized()
        }
        val out = ArrayList<Double>()
        for ((i, t) in mesh.triangles.withIndex()) {
            val n = normal(i) ?: continue
            for ((from, to) in listOf(t.a to t.b, t.b to t.c, t.c to t.a)) {
                val j = owner[key(to, from)] ?: continue
                if (j <= i) continue
                val m = normal(j) ?: continue
                if (n.dot(m) > cos) continue
                out.add(180.0 - acos(n.dot(m).coerceIn(-1.0, 1.0)) * 180.0 / PI)
            }
        }
        return out
    }

    /** The user's own flow: trim the handle by both chains, then fuse it to its plate. */
    private fun fusedPlateAndHandle(): Mesh3 {
        val ed = Editor(DocumentFormat.load(ChainCutFixture.CIT))
        val doc = ed.doc

        fun named(name: String): Element =
            assertNotNull(
                doc.elements.firstOrNull { doc.userNameOf(it) == name } ?: doc.elements.firstOrNull { doc.nameOf(it) == name },
                "the drawing has $name",
            )

        fun solidsOf(): List<Element> = doc.elements.filter { it.kind == ElementKind.SOLID }

        fun centreOf(el: Element): Vec3 {
            val b = assertNotNull(Geom3.bounds(ed.meshOf(el)), "the solid has bounds")
            return (b.first + b.second) * 0.5
        }

        fun look(at: Vec3) {
            val plane = assertNotNull(doc.activePlane3(Evaluator()), "the active space has a plane")
            ed.pointing = PlanePerspective(plane, Camera3(target = at, distance = 300.0, yaw = 0.6, pitch = 0.5), 800.0, 600.0)
        }

        fun aimAt(el: Element): Vec2 {
            val plane = assertNotNull(doc.activePlane3(Evaluator()), "the active space has a plane")
            return plane.toLocal(centreOf(el))
        }

        fun pick(world: Vec2) {
            val s = assertNotNull(ed.pointing?.toScreen(world) ?: ed.camera.worldToScreen(world), "the point has an image")
            ed.pointerMove(s)
            ed.pointerDown(s)
            ed.pointerUp(s)
        }

        fun along(
            chain: Element,
            t: Double,
            d: Double,
        ): Vec2 {
            val line = (Evaluator().valueOf(chain.ref) as constructit.core.LineValue).line
            val dir = line.dir.normalized()
            val p = line.origin + dir * t
            return Vec2(p.x - dir.y * d, p.y + dir.x * d)
        }

        val handle = named("e30")
        val plate = solidsOf().first()
        look(centreOf(handle))
        ed.setTool(Tools.CUT_BY_CHAIN)
        pick(aimAt(handle))
        ed.pointing = null
        val chain2 = named("chain2")
        pick(along(chain2, 30.0, 0.0))
        pick(along(chain2, 30.0, 20.0))
        val cut1 = solidsOf().last()

        look(centreOf(cut1))
        ed.setTool(Tools.CUT_BY_CHAIN)
        pick(aimAt(cut1))
        ed.pointing = null
        val chain1 = named("chain1")
        pick(along(chain1, 30.0, 0.0))
        pick(along(chain1, 30.0, -20.0))
        val cut2 = solidsOf().last()

        look(centreOf(cut2))
        ed.setTool(Tools.UNION)
        pick(aimAt(cut2))
        pick(aimAt(plate))
        return ed.meshOf(solidsOf().last())
    }

    // ---- the gate itself ----

    /**
     * **A result that folds comes back from the general boolean as an invalid node with the flap's own
     * words** — the production gate, at the seam every boolean result passes through.
     *
     * [MeshCanon.finish] is that seam: both actuals of [MeshBool] canonicalize the engine's mesh and hand it
     * to it, and what it says is what becomes the node's reason (OP-3). The mesh here is built by hand — two
     * coplanar triangles wound against each other — because that is the one shape no count can see: every
     * directed edge is used exactly once with exactly one opposite use, so [MeshCanon.notClosed] is silent,
     * and the volume integral is silent too because the pair cancels. Session 81 had these words test-side
     * only; from session 82 they are the engine's.
     */
    @Test
    fun aResultThatFoldsComesBackFromTheGeneralBooleanInvalid() {
        val folded = flapSheet()
        assertNull(MeshCanon.notClosed(folded), "closed and consistently wound, which is why counts cannot see it")
        assertClose(abs(Geom3.volume(folded)), 0.0, tol = 1e-12, msg = "and enclosing nothing, which is why the integral cannot")
        val said = assertNotNull(MeshCanon.fault(folded), "the gate names it")
        assertTrue("zero-thickness flap" in said, "in the flap's own words: $said")
        assertTrue("folds back on itself" in said, "…and says what that means: $said")

        // the seam itself: what `MeshBool.boolean` hands its result to, on both platforms
        val (out, why) = MeshCanon.finish(folded)
        assertNull(out, "a boolean result that folds is not a body")
        assertTrue("zero-thickness flap" in assertNotNull(why, "and it says why"), "the seam speaks the gate's words: $why")

        // …and a sound body still goes through it untouched (the general engine's own results are asserted
        // gate-clean by `aDrillThroughASlantedFaceLeavesNoFold` above)
        val (cut, whyCut) = Geom3.combine(BoolOp.SUBTRACT, cube(20.0), cube(10.0))
        assertNull(whyCut, "an ordinary boolean is unaffected by the gate: $whyCut")
        assertManifold(assertNotNull(cut, "…and is a body").mesh, "a notched cube")
    }

    /**
     * **The second degenerate closed shell: one that encloses nothing** (session 82, the orchestrator's
     * probe of the gate).
     *
     * A [MeshCanon.flap] is a surface with no *thickness*. This is a surface with no *inside*: the shell the
     * probe found is the one a twisted triangular skin used to hand back — three congruent triangles turned
     * one corner round, so every ruling joins a corner to the vertical of its neighbour and the three quads
     * sweep through the axis. Every directed edge is used once with exactly one opposite use, **nothing is
     * coplanar**, so neither `notClosed` nor `flap` sees anything — and the divergence integral comes out at
     * exactly zero. It is now named by [MeshCanon.hollow] and refused at the same seam every boolean result
     * passes through.
     *
     * The correspondence that asks for it is refused one level up, before a triangle exists
     * (`SkinTest.aQuarterTurnOfAFourPieceCorrespondenceIsRefusedAsAFold` and its triangular twin), so this
     * body is built here by hand — the emission's own rule, written out — which is the only way to reach the
     * gate with it now.
     */
    @Test
    fun aClosedShellThatEnclosesNothingIsRefusedByTheGate() {
        val hollow = twistedTriangleShell()
        assertNull(MeshCanon.notClosed(hollow), "closed and consistently wound, which is why counts cannot see it")
        assertNull(MeshCanon.flap(hollow), "and nothing in it is coplanar, which is why the flap check cannot")
        assertClose(Geom3.volume(hollow), 0.0, tol = 1e-9, msg = "…and it encloses exactly nothing")
        val said = assertNotNull(MeshCanon.hollow(hollow), "the gate names it")
        assertTrue("encloses no volume" in said, "in its own words: $said")
        assertTrue("passes through itself" in said, "…and says what that means: $said")
        assertEquals(said, MeshCanon.fault(hollow), "and `fault` is the two degenerate shells in one question")
        val (out, why) = MeshCanon.finish(hollow)
        assertNull(out, "a boolean result with no inside is not a body")
        assertTrue("encloses no volume" in assertNotNull(why, "and it says why"), "the seam speaks the gate's words: $why")

        // …and a body that *does* enclose something is untouched, however small: the bar is the mesh's own
        // resolution over its own footprint, so a tenth-millimetre cube clears it by six orders
        val tiny = cubeMesh(0.1)
        assertNull(MeshCanon.hollow(tiny), "a 0.1 mm cube has an inside: ${Geom3.volume(tiny)} mm³")
        assertNull(MeshCanon.fault(tiny), "and passes the gate whole")
        // a shell wound inside out is the same defect read the other way
        val inverted = Mesh3(tiny.vertices, tiny.triangles.map { Tri(it.a, it.c, it.b) })
        assertNull(MeshCanon.notClosed(inverted), "still closed and consistently wound")
        assertNotNull(MeshCanon.hollow(inverted), "and still refused: it bounds the complement of a body")
    }

    /**
     * The shell a twisted triangular skin used to emit: two congruent triangles a third of a turn apart,
     * ruled corner to corner, split from each strip's own lower rail — `Skin3`'s emission rule written out.
     */
    private fun twistedTriangleShell(): Mesh3 {
        val r = 20.0
        val a = (0 until 3).map { Vec3(r * kotlin.math.cos(2 * PI * it / 3), r * kotlin.math.sin(2 * PI * it / 3), 0.0) }
        val b = a.map { Vec3(it.x, it.y, 40.0) }
        val mb = ArrayList<Triple<Vec3, Vec3, Vec3>>()
        for (j in 0 until 3) {
            // rail j joins corner j to the corner *one round* above it, which is the stated pair
            val lo0 = a[j]
            val hi0 = b[(j + 1) % 3]
            val lo1 = a[(j + 1) % 3]
            val hi1 = b[(j + 2) % 3]
            mb.add(Triple(lo0, lo1, hi1))
            mb.add(Triple(lo0, hi1, hi0))
        }
        mb.add(Triple(a[0], a[2], a[1]))
        mb.add(Triple(b[1], b[2], b[0]))
        val verts = ArrayList<Vec3>()

        fun index(p: Vec3): Int {
            val at = verts.indexOfFirst { (it - p).length() < 1e-9 }
            if (at >= 0) return at
            verts.add(p)
            return verts.size - 1
        }
        val tris = mb.map { (x, y, z) -> Tri(index(x), index(y), index(z)) }
        return Mesh3(verts, tris)
    }

    /** A cube of side [side] at the origin, as a mesh — the smallest honest body a gate may not refuse. */
    private fun cubeMesh(side: Double): Mesh3 {
        val v =
            listOf(
                Vec3(0.0, 0.0, 0.0),
                Vec3(side, 0.0, 0.0),
                Vec3(side, side, 0.0),
                Vec3(0.0, side, 0.0),
                Vec3(0.0, 0.0, side),
                Vec3(side, 0.0, side),
                Vec3(side, side, side),
                Vec3(0.0, side, side),
            )
        val faces =
            listOf(
                listOf(0, 3, 2, 1),
                listOf(4, 5, 6, 7),
                listOf(0, 1, 5, 4),
                listOf(1, 2, 6, 5),
                listOf(2, 3, 7, 6),
                listOf(3, 0, 4, 7),
            )
        return Mesh3(v, faces.flatMap { f -> listOf(Tri(f[0], f[1], f[2]), Tri(f[0], f[2], f[3])) })
    }

    /** Two coplanar triangles wound against each other: a closed shell of zero thickness. */
    private fun flapSheet(): Mesh3 =
        Mesh3(
            listOf(Vec3(2.0, 2.0, 5.0), Vec3(8.0, 2.0, 5.0), Vec3(2.0, 8.0, 5.0)),
            listOf(Tri(0, 1, 2), Tri(0, 2, 1)),
        )

    private fun cube(side: Double): Solid3 {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(side, side))
        ed.activeScalar = ed.doc.newParameter("h", side.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(side / 2.0, 0.0))
        @Suppress("UNCHECKED_CAST")
        return Evaluator().solid(ed.solids().single().ref as SolidRef)
    }

    private fun assumeEngine() {
        assumeTrue(MeshBool.available, "the general engine is needed here (OP-9): ${MeshBool.status}")
    }
}
