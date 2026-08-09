package constructit

import constructit.core.Evaluator
import constructit.dsl.Construction
import constructit.dsl.DirectionRef
import constructit.dsl.PointRef
import constructit.dsl.RegionRef
import constructit.dsl.SolidRef
import constructit.dsl.scalar
import constructit.dsl.solid
import constructit.dsl.valueOf
import constructit.editor.DocumentFormat
import constructit.editor.ElementKind
import constructit.geom.GeomMath
import constructit.geom.Vec3
import constructit.units.deg
import constructit.units.mm
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * GitHub #13 — "Mesh too fine-grained for display". The tessellation tolerance was a single **absolute**
 * sagitta (0.02 mm), so `chordStepAngle` demanded ever more chords as the physical radius grew: the
 * user's 200 mm revolve meshed to ~97k triangles while its ×0.1 twin needed ~10k, for a display where
 * both look identical. The fix makes the tolerance **relative** to the arc's own radius, floored by the
 * old absolute value, so the chord *count* for a given arc is invariant under uniformly scaling the model
 * (see `GeomMath.effectiveTol` / `GeomMath.REL_TOL`).
 *
 * The invariant the one-number doctrine (OP-9) protected — two solids sharing a face must tessellate it
 * identically — survives because `effectiveTol` is a pure function of the radius: coincident faces have
 * the same radius, hence the same count. The shared-face boolean below is that proof.
 */
class Issue13TessScaleTest {
    private fun Construction.cyl(
        tag: String,
        r: Double,
        z0: Double,
        z1: Double,
    ): SolidRef {
        val c = freePoint("c_$tag", 0.mm, 0.mm)
        val circ = circleCR(c, parameter("r_$tag", r.mm))
        val reg = region(loop(circ))
        val base = plane(Vec3(0.0, 0.0, z0), Vec3.X, Vec3.Y)
        return extrude(sketchOn(base, reg), parameter("h_$tag", (z1 - z0).mm))
    }

    /** A washer profile offset [r0]..[r1] from the axis, [h] tall, revolved a full turn about +y. */
    private fun Construction.washer(
        r0: Double,
        r1: Double,
        h: Double,
    ): SolidRef {
        val a = freePoint("wa", r0.mm, 0.mm)
        val b = freePoint("wb", r1.mm, 0.mm)
        val d = freePoint("wc", r1.mm, h.mm)
        val e = freePoint("wd", r0.mm, h.mm)
        val reg: RegionRef = region(loop(segment(a, b), segment(b, d), segment(d, e), segment(e, a)))
        // Sketch on the XZ plane so the in-plane v axis is world +Z: revolving about it gives a washer whose
        // axis is vertical, so a horizontal section (sectionAt cuts at constant world z) is one connected annulus.
        val axisO: PointRef = freePoint("axisO", 0.mm, 0.mm)
        val axisDir: DirectionRef = direction(freePoint("axisA", 0.mm, 0.mm), freePoint("axisB", 0.mm, 10.mm))
        return revolve(sketchOn(planeXZ(), reg), axisO, axisDir, parameter("turn", 360.0.deg))
    }

    /** The user's model (issue #13): a 200 mm revolve. Was 96,672 triangles; must now sit in the ~10k band. */
    @Test
    fun theUsersRevolveIsNoLongerOverFine() {
        val doc = DocumentFormat.load(USERS_MODEL)
        val ev = Evaluator()
        val solids = doc.elements.filter { it.kind == ElementKind.SOLID }
        assertEquals(1, solids.size, "the script builds one revolve")

        @Suppress("UNCHECKED_CAST")
        val mesh = ev.solid(solids.single().ref as SolidRef).mesh
        val n = mesh.triangles.size
        val before = 96_672
        assertTrue(n < before / 5, "the mesh must drop sharply from $before; got $n")
        assertTrue(
            n in 8_000..12_000,
            "it must land in the ~10k band its ×0.1 twin (~9,936) sits in; got $n",
        )
        // A ~10× drop, the ratio between the user's 200 mm model and its 20 mm twin under the old rule.
        assertTrue(before.toDouble() / n in 8.0..12.0, "roughly a ×10 drop; got ${before.toDouble() / n}")
        assertManifold(mesh, "the user's revolve")
    }

    /** The heart of the fix: the same shape at ×10 the size meshes to the *same* count (was ~3.2× more). */
    @Test
    fun tessellationCountIsScaleInvariant() {
        val small = Evaluator().solid(Construction().cyl("s", 20.0, 0.0, 40.0)).mesh
        val large = Evaluator().solid(Construction().cyl("l", 200.0, 0.0, 40.0)).mesh
        assertManifold(small, "r=20 cylinder")
        assertManifold(large, "r=200 cylinder")

        // Under the old absolute rule the large one was 888 vs 280 (~3.17×). Now they match to the triangle.
        assertTrue(
            large.triangles.size <= small.triangles.size + 1 &&
                small.triangles.size <= large.triangles.size + 1,
            "scaling the whole model must not change the triangle count: " +
                "r=20 -> ${small.triangles.size}, r=200 -> ${large.triangles.size}",
        )
        assertTrue(large.triangles.size < 888, "and it must be far below the old absolute-rule 888")
    }

    /** The floor: a tiny feature keeps exactly the fineness it had — its count must not drop. */
    @Test
    fun theFloorKeepsSmallFeaturesAtLeastAsFine() {
        val tiny = Evaluator().solid(Construction().cyl("t", 1.0, 0.0, 40.0)).mesh
        assertManifold(tiny, "r=1 cylinder")
        assertEquals(60, tiny.triangles.size, "the r=1 mesh must be unchanged from the pre-#13 absolute rule")

        // The floor asserted directly: at or below the crossover radius, the effective tolerance is still
        // the old absolute one; above it, it scales with the radius.
        assertClose(GeomMath.effectiveTol(1.0), GeomMath.TESS_TOL_MM, 1e-15, "r=1 is floored to the old tol")
        assertClose(GeomMath.effectiveTol(20.0), GeomMath.TESS_TOL_MM, 1e-15, "r=20 is exactly the crossover")
        assertClose(GeomMath.effectiveTol(200.0), 0.2, 1e-15, "r=200 scales with the radius")
        assertTrue(GeomMath.REL_TOL == GeomMath.TESS_TOL_MM / 20.0, "REL_TOL is pinned to the 20 mm crossover")
    }

    /**
     * The invariant proof (OP-9): a boolean between a large (r=200) and a small (r=20) coaxial cylinder
     * that share a face must be watertight, and its volume right. `effectiveTol` being a pure function of
     * the radius is what keeps the shared circle sampled identically from both operands.
     */
    @Test
    fun aSharedFaceBooleanIsWatertight() {
        // A tube: r=200 outer with an r=20 bore straight through — operands 10× apart in radius.
        run {
            val c = Construction()
            val tube = c.subtract(c.cyl("to", 200.0, 0.0, 40.0), c.cyl("ti", 20.0, -10.0, 50.0))
            val mesh = Evaluator().solid(tube).mesh
            assertManifold(mesh, "tube (large minus small bore)")
            val exact = PI * (200.0 * 200.0 - 20.0 * 20.0) * 40.0
            val vol = constructit.geom.Geom3.volume(mesh)
            assertTrue(abs(vol - exact) / exact < 0.01, "tube volume $vol within 1% of $exact (inscribed chords undershoot)")
        }
        // A stacked union: an r=20 cylinder crossing the top cap of an r=200 one — a real shared boundary
        // circle at the interface, which both meshes must agree on for the result to close.
        run {
            val c = Construction()
            val u = c.union(c.cyl("ub", 200.0, 0.0, 40.0), c.cyl("us", 20.0, 20.0, 60.0))
            val mesh = Evaluator().solid(u).mesh
            assertManifold(mesh, "large ∪ small stacked cylinder")
            val exact = PI * 200.0 * 200.0 * 40.0 + PI * 20.0 * 20.0 * 20.0
            val vol = constructit.geom.Geom3.volume(mesh)
            assertTrue(abs(vol - exact) / exact < 0.01, "union volume $vol within 1% of $exact")
        }
    }

    /**
     * OP-15 unchanged. A perpendicular cut of a plain extrude is still an **exact** circle regardless of
     * radius — it is the algebraic slab (OP-22), not a tessellation, so the relative-tolerance change
     * cannot leak into it. That is the honesty line, asserted at the large radius the #13 model uses.
     *
     * A large *revolve* is still **drawn** from its mesh — that is the path that reads TESS_TOL_MM, so #13
     * touches it — but it exposes no analytic construction inputs (a surface-of-revolution section is its
     * own unbuilt slice, DESIGN.md): `pieces` is populated and flagged approximated, while `edges` is empty
     * and `inputsRefusal` says why. This change alters only how *many* chords the drawing uses, never that
     * distinction, so it is asserted here as the unchanged behaviour.
     */
    @Test
    fun sectionsAreUnaffected() {
        // Exact circle at a large radius — no tessellation leaked into the section path.
        val c = Construction()
        val plate = c.cyl("p", 200.0, 0.0, 40.0)
        val cut = c.sectionAt(plate, c.parameter("hc", 20.mm))
        val area = Evaluator().scalar(c.regionArea(cut)).base
        assertClose(area, PI * 200.0 * 200.0, 1e-9, "the perpendicular cut of a round prism is πr² to the last bit")

        // A large revolve: built, watertight, and its section still drawn from the mesh (reads TESS_TOL_MM).
        val c2 = Construction()
        val ring = c2.washer(80.0, 200.0, 40.0)
        assertManifold(Evaluator().solid(ring).mesh, "the large washer revolve")
        val rsec = c2.section(ring, c2.planeOffset(c2.planeXY(), c2.parameter("hr", 20.mm)))
        val s =
            Evaluator().valueOf(rsec) as? constructit.core.SectionValue
                ?: error("the section node evaluates to a compound section value")
        assertTrue(s.section.pieces.isNotEmpty(), "the revolve's section is drawn from its mesh (${s.section.pieces.size} pieces)")
        assertTrue(s.section.approximated, "and flagged approximated — it is sampled at the mesh tolerance")
        assertTrue(s.section.edges.isEmpty(), "but it exposes no analytic inputs (a revolve section is unbuilt)")
        assertTrue(
            s.section.inputsRefusal?.contains("revolved") == true,
            "and it says why: ${s.section.inputsRefusal}",
        )
    }

    companion object {
        val USERS_MODEL =
            """
            constructit 2
            param "r" = 200mm
            param "d" = 40mm
            param "s" = 50mm
            point -17.25,-10.25 -> e1
            orthostart -17.25,-10.25 -> e2
            weldortho e2 e1
            orthovertex -17.25,55 -> e3,e4
            tool parallelat els=e4 clicks=-17,8;26.25,8.5 scalar="d" -> e5
            tool perp pts=e2 els=e5 clicks=22.539412499999983,-3.0633699999999893;-17.32071000000002,-10.31066499999999 -> e6
            tool parallelat els=e6 clicks=88.94275293749996,-10.948024332499983;119.48889347999996,54.96733157500002 scalar="s" -> e7
            tool line pts=e2,e3 clicks=-15.557201550000032,-13.091613142499984;-13.94950994250003,57.110920385000014 -> e8
            tool intersect els=e5,e7 clicks=23.027397029999968,49.60835955000002;55.71712638249997,19.062219007500016 -> e9
            tool circleR pts=e9 clicks=23.563294232499967,20.134013412500018 scalar="r" -> e10
            tool intersect els=e8,e10 clicks=-18.772584765000033,235.02879161500002;4.806892144999968,219.4877727425 -> e11,e12
            tool circleR pts=e12 clicks=-17.164893157500032,213.59290351500002 scalar="r" -> e13
            tool intersect els=e8,e13 clicks=-17.60968783557502,430.122168185125;-51.97677543190002,412.61440657945 -> e14,e15
            tool intersect els=e5,e6 clicks=23.241755910999977,0.8577910385750537;58.90571473737498,-10.814050031874945 -> e16
            param "s2" = 10mm
            tool concentric els=e13 clicks=106.24151463419997,371.11452721785;163.3038487564,379.54419021317506 scalar="s2" -> e17
            tool parallelat els=e5 clicks=22.453653208524944,-88.56429870247442;102.03438777977483,-80.31148178397443 scalar="s2" -> e18
            tool intersect els=e18,e6 clicks=24.037626963756434,-5.468285542611265;28.599840944609255,-10.682244377871633 -> e19
            tool intersect els=e17,e18 clicks=32.872390545725395,20.746340823558917;23.89279477388809,14.373724469351801 -> e20,e21
            tool intersect els=e17,e8 clicks=-22.182596666542665,417.49460574293346;-16.11800072381616,421.3432916296638 -> e22,e23
            hide els=e21
            hide els=e10
            tool arccs pts=e12,e9,e15 clicks=-17.523044034741428,237.7041419193154;24.812834962758394,39.95807419681622;-18.594838439741427,435.9861068443146 -> e24
            tool arccs pts=e12,e20,e23 clicks=-17.523044034741428,235.0246559068154;33.387190202758354,31.383718956816256;-18.058941237241427,443.4886676793145 -> e25
            tool segment pts=e9,e16 clicks=22.133348950258405,42.10166300681621;25.88462936775839,-9.88036563568357 -> e26
            tool segment pts=e20,e19 clicks=33.92308740525836,30.84782175431626;34.99488181025835,-9.88036563568357 -> e27
            tool segment pts=e19,e16 clicks=34.45898460775835,-12.023954445683561;25.34873216525839,-9.344468433183572 -> e28
            tool segment pts=e23,e15 clicks=-17.523044034741428,446.1681536918145;-17.523044034741428,437.59379845181456 -> e29
            tool outline els=e28,e27,e25,e29,e24,e26 clicks=29.100012582758374,-10.416262838183567;33.387190202758354,7.268344844316359;191.23472835459603,260.8908799790167;-17.249999999999993,440.70917942265424;181.7372306010248,255.8109718236959;22.750000000000014,14.75 -> e30,e31,e32,e33,e34,e35,e36
            param "a" = 360deg
            tool revolve els=e36,e8 clicks=85.07761038251773,51.99563831311234;-17.627458245676575,501.6539639878118 scalar="a" -> e37
            """.trimIndent()
    }
}
