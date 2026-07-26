package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.Construction
import constructit.dsl.RegionRef
import constructit.dsl.ScalarRef
import constructit.dsl.plane
import constructit.dsl.region
import constructit.dsl.resultOf
import constructit.dsl.scalar
import constructit.dsl.solid
import constructit.geom.Feature3
import constructit.geom.Geom3
import constructit.geom.MeshBool
import constructit.geom.Vec3
import constructit.units.mm
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **The general boolean path** (OP-9): what happens when two solids have no common axis and OP-22's
 * exact algebra therefore has no answer at all.
 *
 * The showcase is the one the exact path cannot do and every real part needs: a **vertical prism with a
 * horizontal hole through its side** — a circle sketched on a vertical plane and extruded along *that*
 * plane's normal, i.e. perpendicular to the prism's own axis.
 *
 * Three things are asserted of every result, because a mesh boolean that only *looks* right is exactly
 * the failure mode OP-22 was written to avoid: it is **watertight** ([assertManifold], unchanged — the
 * engine's output is canonicalised into shared vertices, so the same edge-pairing check applies), its
 * **volume** is the analytic figure to within the tessellation, and it is **deterministic** across two
 * evaluations. And the exact path is checked to still be the one taken where it applies: a general
 * boolean produces a [Feature3.MeshBoolean], a same-axis one a [Feature3.Prism], so which engine ran is
 * visible in the value and cannot silently change.
 */
class MeshBooleanTest {
    /**
     * Loudly, and as a *skip* rather than a pass: a platform with no engine must show up in the report as
     * an untested one, never as a green tick. `assumeTrue` is what makes the test SKIPPED with the reason
     * attached; returning early would have looked identical to success.
     */
    private fun requireEngine() =
        assumeTrue(
            MeshBool.available,
            "the general boolean engine (Manifold, OP-9) is not available here: ${MeshBool.status}",
        )

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

    private fun Construction.disc(
        cx: Double,
        cy: Double,
        r: ScalarRef,
        tag: String,
    ): RegionRef = region(loop(circleCR(freePoint("$tag.c", cx.mm, cy.mm), r)))

    /**
     * A plate 80 × 50 × 20 mm standing on the XY plane, with a **horizontal** ⌀12 hole drilled 60 mm
     * through it from the front face — the cross-axis boolean, so the general engine (OP-9).
     *
     * The bore's sketch plane is the vertical `y = -25` plane, and its own normal points into the
     * material, so the operand is an ordinary extrude in every respect *except* that its axis is
     * perpendicular to the plate's. That is the whole difference between this test and
     * `PrismBooleanTest`, and it is the difference between the two engines.
     */
    @Test
    fun aVerticalPlateMinusAHorizontalBoreIsWatertightAndItsVolumeIsRight() {
        requireEngine()
        val c = Construction()
        val thickness = c.parameter("thickness", 20.mm)
        val boreR = c.parameter("boreR", 6.mm)
        val boreDepth = c.parameter("boreDepth", 60.mm)

        val plate = c.extrude(c.sketchOn(c.planeXY(), c.rect(-40.0, -25.0, 40.0, 25.0, "plate")), thickness)
        // The plate's front face, as a sketch plane: through y = -25 with u = +Z and v = +X, so its normal
        // is u x v = +Y and an extrude along it runs *into* the plate — a horizontal drill. The order of
        // the two axes is the whole content of that sentence: (X, Z) would have pointed the sketch (and the
        // drill) the other way, which is exactly the mistake a right-handed frame is there to make visible.
        val front = c.plane(Vec3(0.0, -25.0, 0.0), Vec3.Z, Vec3.X)
        // in that frame a sketch point is (z, x), so (10, 0) is mid-height and centred across the plate
        val bore = c.extrude(c.sketchOn(front, c.disc(10.0, 0.0, boreR, "bore")), boreDepth)
        val part = c.subtract(plate, bore)
        val vol = c.measureVolume(part)

        val ev = Evaluator()
        assertTrue(ev.resultOf(part) is EvalResult.Ok, "the cross-axis cut should build: ${ev.resultOf(part)}")
        val mesh = ev.solid(part).mesh
        assertManifold(mesh, "plate with a horizontal bore")

        // it went through the general engine, and says so in the value (OP-9's type boundary)
        assertTrue(
            ev.solid(part).feature is Feature3.MeshBoolean,
            "a cross-axis boolean has no prismatic form; its feature was ${ev.solid(part).feature::class.simpleName}",
        )

        // plate minus a full-depth cylinder: the bore spans the plate's whole 50 mm in y (60 mm long,
        // starting on the front face), and its ⌀12 circle sits clear of both faces of the 20 mm plate
        val analytic = 80.0 * 50.0 * 20.0 - PI * 36.0 * 50.0
        val measured = ev.scalar(vol).base
        val err = (measured - analytic) / analytic
        assertTrue(err > 0.0, "an inscribed bore removes slightly too little, so the part comes out heavy ($err)")
        assertTrue(err < 1e-3, "volume is $err off the analytic part, more than the tessellation explains")
        // and the mesh's own volume agrees with the measurement node, which reads the same mesh
        assertClose(Geom3.volume(mesh), measured, tol = 1e-6)

        // deterministic: a second, independent evaluation produces the same triangles in the same order
        assertEquals(mesh, Evaluator().solid(part).mesh, "a general boolean must be a pure function of its inputs")

        // the bounding box is the plate's — the hole is interior, so nothing moved
        assertClose(ev.scalar(c.measureBBoxExtent(part, constructit.geom.Axis3.X)).base, 80.0, tol = 1e-3)
        assertClose(ev.scalar(c.measureBBoxExtent(part, constructit.geom.Axis3.Z)).base, 20.0, tol = 1e-3)
    }

    /**
     * The same drill, but through a **polygon** prism — a regular hexagon — as a *blind* pocket: the
     * cylinder starts on a vertical plane outside the solid and stops inside it, so what is removed is
     * `π r² ·` the part of it that was in the material.
     *
     * Worth its own test because the entry face is **not** coplanar with anything (a hexagon's flat is at
     * 30° to the world axes), which is where a BSP-based CSG would start needing an epsilon.
     */
    @Test
    fun aHexagonalPrismWithASidePocketIsWatertight() {
        requireEngine()
        val c = Construction()
        val h = c.parameter("h", 30.mm)
        val r = 25.0
        val corners =
            (0 until 6).map { i ->
                val a = PI / 3.0 * i
                c.freePoint("hex$i", (r * kotlin.math.cos(a)).mm, (r * kotlin.math.sin(a)).mm)
            }
        val hex =
            c.region(
                c.loop(*(0 until 6).map { c.segment(corners[it], corners[(it + 1) % 6]) }.toTypedArray()),
            )
        val prism = c.extrude(c.sketchOn(c.planeXY(), hex), h)

        // The hexagon's flat at x = +12.5 (its apothem, r·cos 60° — the corners are at 0° and 60°, so the
        // edge between them is the vertical plane x = 12.5). u = +Z, v = +Y makes the normal -X, i.e. into
        // the material, and a sketch point is then (z, y).
        val flat = c.plane(Vec3(12.5, 0.0, 0.0), Vec3.Z, Vec3.Y)
        val pocketR = c.parameter("pocketR", 5.mm)
        val depth = c.parameter("pocketDepth", 8.mm)
        val pocket = c.extrude(c.sketchOn(flat, c.disc(15.0, 0.0, pocketR, "pocket")), depth)
        val part = c.subtract(prism, pocket)

        val ev = Evaluator()
        assertTrue(ev.resultOf(part) is EvalResult.Ok, "the pocket should build: ${ev.resultOf(part)}")
        assertManifold(ev.solid(part).mesh, "hexagonal prism with a side pocket")

        // hexagon area (3√3/2)r² times the height, less the pocket cylinder that lay inside it
        val hexArea = 1.5 * kotlin.math.sqrt(3.0) * r * r
        val analytic = hexArea * 30.0 - PI * 25.0 * 8.0
        val err = (ev.scalar(c.measureVolume(part)).base - analytic) / analytic
        assertTrue(err > 0.0, "an inscribed pocket removes slightly too little ($err)")
        assertTrue(err < 1e-3, "volume is $err off the analytic part")
    }

    /** Cross-axis **union**: an upright post and a beam laid across it fuse into one watertight shell. */
    @Test
    fun aCrossAxisUnionIsOneWatertightSolid() {
        requireEngine()
        val c = Construction()
        val post = c.extrude(c.sketchOn(c.planeXY(), c.rect(-10.0, -10.0, 10.0, 10.0, "post")), c.parameter("postH", 100.mm))
        // a beam extruded along +Y from the vertical plane y = -50, crossing the post near its top; the
        // frame is (u = +Z, v = +X), so the sketch rectangle is (z from 70 to 86) x (x from -8 to 8)
        val side = c.plane(Vec3(0.0, -50.0, 0.0), Vec3.Z, Vec3.X)
        val beam = c.extrude(c.sketchOn(side, c.rect(70.0, -8.0, 86.0, 8.0, "beam")), c.parameter("beamLen", 100.mm))
        val fused = c.union(post, beam)

        val ev = Evaluator()
        assertTrue(ev.resultOf(fused) is EvalResult.Ok, "the union should build: ${ev.resultOf(fused)}")
        assertManifold(ev.solid(fused).mesh, "post and beam")

        // the two volumes less the box where they overlap: 16 × 16 × 20 mm of shared material
        val analytic = 20.0 * 20.0 * 100.0 + 16.0 * 16.0 * 100.0 - 16.0 * 16.0 * 20.0
        assertClose(ev.scalar(c.measureVolume(fused)).base, analytic, tol = 0.5, msg = "a union counts the overlap once")
        assertEquals(ev.solid(fused).mesh, Evaluator().solid(fused).mesh, "and it is deterministic")
    }

    /** Cross-axis **intersection**: what is in both is exactly the box where the two prisms cross. */
    @Test
    fun aCrossAxisIntersectionIsTheOverlapBox() {
        requireEngine()
        val c = Construction()
        val post = c.extrude(c.sketchOn(c.planeXY(), c.rect(-10.0, -10.0, 10.0, 10.0, "post")), c.parameter("postH", 100.mm))
        val side = c.plane(Vec3(0.0, -50.0, 0.0), Vec3.Z, Vec3.X)
        val beam = c.extrude(c.sketchOn(side, c.rect(70.0, -8.0, 86.0, 8.0, "beam")), c.parameter("beamLen", 100.mm))
        val meet = c.intersect(post, beam)

        val ev = Evaluator()
        assertTrue(ev.resultOf(meet) is EvalResult.Ok, "the intersection should build: ${ev.resultOf(meet)}")
        assertManifold(ev.solid(meet).mesh, "where post and beam cross")
        assertClose(ev.scalar(c.measureVolume(meet)).base, 16.0 * 16.0 * 20.0, tol = 0.5)
        val (lo, hi) = Geom3.bounds(ev.solid(meet).mesh)!!
        assertClose(lo.z, 70.0, tol = 1e-3)
        assertClose(hi.z, 86.0, tol = 1e-3)
        assertClose(lo.y, -10.0, tol = 1e-3)
        assertClose(hi.y, 10.0, tol = 1e-3)
    }

    /**
     * A bore **tangent** to both faces of the plate — radius exactly half the thickness, at mid-height — is
     * **refused with a reason**, and that is the honest answer rather than a defect.
     *
     * Such a solid touches itself along two lines, so it has no watertight triangle mesh in which a
     * position is a vertex: Manifold represents it with coincident-but-distinct vertices, and the canonical
     * form ([MeshCanon]) necessarily welds those into one, which turns the contact line into an edge used
     * twice. Rather than hand back a shell with that in it, the seam checks its own output and refuses —
     * an ordinary invalid node that heals the moment the radius or the thickness moves off the tangency,
     * which the second half of this test walks through.
     */
    @Test
    fun aBoreTangentToBothFacesIsRefusedAndHeals() {
        requireEngine()
        val c = Construction()
        val thickness = c.parameter("thickness", 12.mm)
        val boreR = c.parameter("boreR", 6.mm)
        val plate = c.extrude(c.sketchOn(c.planeXY(), c.rect(-40.0, -25.0, 40.0, 25.0, "plate")), thickness)
        val front = c.plane(Vec3(0.0, -25.0, 0.0), Vec3.Z, Vec3.X)
        val bore = c.extrude(c.sketchOn(front, c.disc(6.0, 0.0, boreR, "bore")), c.parameter("boreDepth", 60.mm))
        val part = c.subtract(plate, bore)

        val r = Evaluator().resultOf(part)
        assertTrue(r is EvalResult.Invalid, "a doubly tangent bore has no watertight mesh, so this must refuse")
        assertTrue(r.reason.contains("closed shell"), "and the reason should say what is wrong: ${r.reason}")

        // ...and it heals: a hair narrower and the contact is gone (OP-3)
        c.set(boreR, 5.9.mm)
        val healed = Evaluator()
        assertTrue(healed.resultOf(part) is EvalResult.Ok, "a narrower bore is an ordinary cut: ${healed.resultOf(part)}")
        assertManifold(healed.solid(part).mesh, "healed bore")
    }

    /**
     * **The exact path must not degrade.** A same-axis pair takes OP-22's algebra even with the general
     * engine present — asserted through the *value*: the result is a [Feature3.Prism] (so it can be
     * sectioned, sketched on and combined exactly again), its volume is the tessellated cap area times
     * the height to 1e-6 mm³, and its bore is a hole in a slab rather than an emergent mesh boundary.
     */
    @Test
    fun aSameAxisBooleanStillTakesTheExactPath() {
        val c = Construction()
        val h = c.parameter("h", 12.mm)
        val plateRegion = c.rect(-40.0, -25.0, 40.0, 25.0, "plate")
        val boreRegion = c.disc(0.0, 0.0, c.parameter("boreR", 5.mm), "bore")
        val plate = c.extrude(c.sketchOn(c.planeXY(), plateRegion), h)
        val bore = c.extrude(c.sketchOn(c.planeXY(), boreRegion), h)
        val part = c.subtract(plate, bore)

        val ev = Evaluator()
        val prism = ev.solid(part).feature
        assertTrue(prism is Feature3.Prism, "a same-axis boolean is exact and stays prismatic, not ${prism::class.simpleName}")
        assertEquals(1, prism.slabs.single().regions.single().holes.size, "the bore is a hole in the slab")

        val plateArea = Geom3.tessArea(Geom3.tessellateRegion(ev.region(plateRegion)).first!!)
        val boreArea = Geom3.tessArea(Geom3.tessellateRegion(ev.region(boreRegion)).first!!)
        assertClose(
            ev.scalar(c.measureVolume(part)).base,
            (plateArea - boreArea) * 12.0,
            tol = 1e-6,
            msg = "the exact path's volume is cap area times height, exactly — a mesh boolean would not be",
        )
    }

    /**
     * The **canonical form** is what makes the engine's output a value (OP-4): welded, sorted, and
     * invariant under any relabelling of the same geometry. Asserted directly, on a mesh whose vertices
     * are shuffled and duplicated, because the property has to hold *before* the engine is trusted.
     */
    @Test
    fun canonicalisationMakesAMeshIndependentOfItsLabelling() {
        val c = Construction()
        val box = c.extrude(c.sketchOn(c.planeXY(), c.rect(0.0, 0.0, 10.0, 20.0, "box")), c.parameter("t", 5.mm))
        val mesh = Evaluator().solid(box).mesh
        val canonical = constructit.geom.MeshCanon.canonical(mesh)
        assertManifold(canonical, "canonicalised box")
        assertClose(Geom3.volume(canonical), Geom3.volume(mesh), tol = 1e-9)

        // the same solid, its vertices reversed and every one of them duplicated: a different labelling
        // of one geometry, which must canonicalise to the identical value
        val n = mesh.vertices.size
        val relabelled =
            constructit.geom.Mesh3(
                mesh.vertices.reversed() + mesh.vertices,
                mesh.triangles.map { constructit.geom.Tri(n - 1 - it.a, n - 1 - it.b, n - 1 - it.c) },
            )
        assertEquals(canonical, constructit.geom.MeshCanon.canonical(relabelled), "canonical form is a function of the geometry")
    }
}
