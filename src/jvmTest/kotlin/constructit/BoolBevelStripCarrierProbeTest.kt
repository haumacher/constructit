package constructit

import constructit.core.Evaluator
import constructit.dsl.Construction
import constructit.dsl.LoftPart
import constructit.dsl.RegionRef
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.geom.Blend3
import constructit.geom.BlendKind
import constructit.geom.BlendSection
import constructit.geom.EdgeGeom
import constructit.geom.EdgeName
import constructit.geom.FacePatch
import constructit.geom.Geom3
import constructit.geom.GeomMath
import constructit.geom.MeshBool
import constructit.geom.Plane3
import constructit.geom.Region
import constructit.geom.Section3
import constructit.geom.Solid3
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.units.mm
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **Adversarial probe of slice 5r** (OP-31), written after the delivery and never seen by it: **booleans
 * chain.** A face a boolean's result keeps of its operand is an ordinary operand face of the *next* boolean —
 * that is what lets a second bore be drilled into a bored body without any address moving (slice 5c's chaining
 * rule). So a bevelled body bored **twice** — once through the strip, once clear of it — must still name every
 * face with the strip's pieces among them, and so must a canal body bored twice. The two bodies are read
 * exactly the same way, which is the probe's point: a carrier that survives one boolean must survive two.
 */
class BoolBevelStripCarrierProbeTest {
    private var ids = 0
    private val width = 40.0
    private val depth = 30.0
    private val height = 20.0

    private fun requireEngine() = assumeTrue(MeshBool.available, "no general boolean engine: ${MeshBool.status}")

    private fun edgesOf(s: Solid3) = assertNotNull(Section3.edges(s.feature).first, "it names its edges")

    private fun prism(cx: Construction): SolidRef {
        val xy = listOf(Vec2(0.0, 0.0), Vec2(width, 0.0), Vec2(width, depth), Vec2(0.0, depth))
        val pts = xy.map { cx.freePoint("p${ids++}", it.x.mm, it.y.mm) }
        val segs = xy.indices.map { cx.segment(pts[it], pts[(it + 1) % xy.size]) }
        return cx.extrude(cx.sketchOn(cx.planeXY(), cx.region(cx.loop(*segs.toTypedArray()))), cx.const(height.mm))
    }

    private fun twoRounds(cx: Construction): SolidRef {
        val box = prism(cx)
        val es = edgesOf(Evaluator().solid(box))
        val corner = Vec3(width, depth, height)
        val tops =
            es.indices.filter { i ->
                val path = Blend3.edgePath(es[i]).first ?: return@filter false
                val a = path.start ?: return@filter false
                val b = path.end ?: return@filter false
                abs(a.z - height) < 1e-9 && abs(b.z - height) < 1e-9 && ((a - corner).length() < 1e-6 || (b - corner).length() < 1e-6)
            }
        assertEquals(2, tops.size, "two top edges share the far corner")
        val choices = assertNotNull(Blend3.choicesFor(Evaluator().solid(box), tops, BlendSection(BlendKind.FILLET, 4.0)).first, "scored")
        return cx.blendAll(box, cx.planeXY(), listOf(Construction.BlendRun(BlendKind.FILLET, cx.const(4.0.mm), null, tops, choices)))
    }

    /** The mitre dressed with [kind] at [size]: a canal band (round) or a ruled strip (chamfer). */
    private fun dressedMitre(
        cx: Construction,
        kind: BlendKind,
        size: Double,
    ): SolidRef {
        val two = twoRounds(cx)
        val es = edgesOf(Evaluator().solid(two))
        val mitre = assertNotNull(es.indices.firstOrNull { es[it].name is EdgeName.BlendMitre && es[it].reason == null && es[it].geom is EdgeGeom.OnPlane }, "the mitre")
        val (choices, why) = Blend3.choicesFor(Evaluator().solid(two), listOf(mitre), BlendSection(kind, size))
        return cx.blendAll(two, cx.planeXY(), listOf(Construction.BlendRun(kind, cx.const(size.mm), null, listOf(mitre), assertNotNull(choices, "the mitre is scored for $kind: ${why?.render()}"))))
    }

    private fun drill(
        cx: Construction,
        at: Vec2,
        r: Double,
    ): SolidRef {
        val c = cx.freePoint("d${ids++}", at.x.mm, at.y.mm)
        val circle = cx.region(cx.loop(cx.circleCR(c, cx.const(r.mm))))
        return cx.extrude(cx.sketchOn(cx.plane(Vec3(0.0, 0.0, -5.0), Vec3.X, Vec3.Y), circle), cx.const((height + 10.0).mm))
    }

    private fun regionArea(regions: List<Region>): Double =
        regions.sumOf { r -> abs(GeomMath.signedArea(r.outer)) - r.holes.sumOf { abs(GeomMath.signedArea(it)) } }

    private fun facesNamed(
        s: Solid3,
        what: String,
    ): List<FacePatch> {
        val (fs, why) = Section3.faces(s.feature)
        return assertNotNull(fs, "$what names its faces: ${why?.render()}")
    }

    private fun boredTwice(
        kind: BlendKind,
        size: Double,
        what: String,
    ) {
        requireEngine()
        val cx = Construction()
        val dressed = dressedMitre(cx, kind, size)
        // the first bore crosses the dressed mitre near the far corner, the second stands clear of everything
        val once = cx.subtract(dressed, drill(cx, Vec2(38.0, 28.0), 1.5))
        val first = Evaluator().solid(once)
        assertManifold(first.mesh, "$what bored once")
        val fs1 = facesNamed(first, "$what bored once")
        val carriersOnce = fs1.count { (kind == BlendKind.CHAMFER && it.strip != null) || (kind == BlendKind.FILLET && it.pipe != null) }
        assertTrue(carriersOnce >= 1, "$what bored once keeps its mitre face: ${fs1.map { it.name.label.render() }}")
        val twice = Evaluator().solid(cx.subtract(once, drill(cx, Vec2(10.0, 10.0), 3.0)))
        assertManifold(twice.mesh, "$what bored twice")
        val v1 = Geom3.volume(first.mesh)
        val v2 = Geom3.volume(twice.mesh)
        val hole = Math.PI * 9.0 * height
        assertTrue(v1 - v2 > 0.98 * hole && v1 - v2 < hole + 1e-6, "$what: the second bore takes its own cylinder, less its chords: ${v1 - v2} vs $hole")
        // **the chain**: every face of the twice-bored body is named, the mitre's pieces among them
        val fs2 = facesNamed(twice, "$what bored twice")
        val carriersTwice = fs2.count { (kind == BlendKind.CHAMFER && it.strip != null) || (kind == BlendKind.FILLET && it.pipe != null) }
        val mitreFaces1 = fs1.filter { (kind == BlendKind.CHAMFER && it.strip != null) || (kind == BlendKind.FILLET && it.pipe != null) }.map { "${it.name.label.render()} [${it.outline.size}]" }
        val mitreFaces2 = fs2.filter { (kind == BlendKind.CHAMFER && it.strip != null) || (kind == BlendKind.FILLET && it.pipe != null) }.map { "${it.name.label.render()} [${it.outline.size}]" }
        assertEquals(carriersOnce, carriersTwice, "$what: the second bore, clear of the mitre, leaves its pieces as they were:\n  once: $mitreFaces1\n  twice: $mitreFaces2")
        // **no two faces of one body share a name** — a label is what a refusal and a panel row say, and two
        // faces answering to one sentence is a drawing that cannot tell them apart
        val labels2 = fs2.filter { it.outline.isNotEmpty() || it.plane != null }.map { it.name.label.render() }
        assertEquals(labels2.size, labels2.toSet().size, "$what bored twice: every face has a name of its own: ${labels2.groupBy { it }.filter { it.value.size > 1 }.keys}")
        // the second bore's own cylinder is a named face too, and the first bore's cylinder survives one boolean further
        assertTrue(fs2.count { it.surface != null && it.outline.isNotEmpty() } >= 2, "$what: both bores' cylinders are named faces")
        // and the sections: below the dressing, the block less the two bores' traces
        val (below, wb) = Section3.regionsOf(twice.feature, Plane3(Vec3(0.0, 0.0, 5.0), Vec3.X, Vec3.Y))
        val area = regionArea(assertNotNull(below, "$what bored twice: the level section at z = 5 closes: ${wb?.render()}"))
        val expected = width * depth - Math.PI * 9.0 - Math.PI * 2.25
        assertTrue(abs(area - expected) < 0.2, "$what: the block less two circles, to the bores' chords: $area vs $expected")
        val (through, wt) = Section3.regionsOf(twice.feature, Plane3(Vec3(0.0, 0.0, 17.5), Vec3.X, Vec3.Y))
        assertNotNull(through, "$what bored twice: the level section through the dressing closes: ${wt?.render()}")
    }

    /** The 35° loft whose leaning inside corner is the pivot's home (`BlendCornerCanalTest.lofted`). */
    private fun lofted(cx: Construction): Pair<SolidRef, Vec3> {
        val lo = listOf(Vec2(0.0, 0.0), Vec2(60.0, 0.0), Vec2(60.0, 30.0), Vec2(30.0, 30.0), Vec2(30.0, 60.0), Vec2(0.0, 60.0))
        val shift = height * tan(35.0 * PI / 180.0) / sqrt(2.0)
        val scale = 1.0 - shift / 30.0

        fun area(pts: List<Vec2>): RegionRef {
            val ps = pts.map { q -> cx.freePoint("F${ids++}", q.x.mm, q.y.mm) }
            return cx.region(cx.loop(*pts.indices.map { cx.segment(ps[it], ps[(it + 1) % pts.size]) }.toTypedArray()))
        }
        val loft =
            cx.loft(
                listOf(
                    LoftPart.Area(cx.sketchOn(cx.planeXY(), area(lo))),
                    LoftPart.Area(cx.sketchOn(cx.planeOffset(cx.planeXY(), cx.const(height.mm)), area(lo.map { it * scale }))),
                ),
            )
        return loft to Vec3(30.0 * scale, 30.0 * scale, height)
    }

    /** …its two cap edges at the reflex corner rounded in one gesture: the pivot, a canal corner face. */
    private fun pivot(cx: Construction): Pair<SolidRef, Vec3> {
        val (base, v) = lofted(cx)
        val es = edgesOf(Evaluator().solid(base))
        val pair =
            es.indices.filter { i ->
                val e = es[i]
                if (!e.between.a.label.render().contains("section 2's own face") && !e.between.b.label.render().contains("section 2's own face")) return@filter false
                val path = Blend3.edgePath(e).first ?: return@filter false
                val a = path.start ?: return@filter false
                val b = path.end ?: return@filter false
                (a - v).length() < 1e-6 || (b - v).length() < 1e-6
            }
        assertEquals(2, pair.size, "the cap turns a reflex corner between two of its own edges")
        val (choices, why) = Blend3.choicesFor(Evaluator().solid(base), pair, BlendSection(BlendKind.FILLET, 3.0))
        return cx.blendAll(base, cx.planeXY(), listOf(Construction.BlendRun(BlendKind.FILLET, cx.const(3.0.mm), null, pair, assertNotNull(choices, "the pair rounds: ${why?.render()}")))) to v
    }

    /**
     * **The pivot bored twice** — through its own corner, then clear of everything — is the same chain as the
     * canal's and the strip's: every face named with the corner's pieces among them, no two faces one name,
     * the second bore leaving the corner's pieces exactly as they were, and the level section closing.
     */
    @Test
    fun aPivotBoredThroughItsCornerAndAgainClearOfItStillNamesItsCorner() {
        requireEngine()
        val cx = Construction()
        val (body, v) = pivot(cx)
        val plain = Evaluator().solid(body)
        assertManifold(plain.mesh, "the loft's inside corner rounded")

        fun drillAt(
            at: Vec2,
            r: Double,
        ): SolidRef {
            val c = cx.freePoint("d${ids++}", at.x.mm, at.y.mm)
            return cx.extrude(cx.sketchOn(cx.plane(Vec3(0.0, 0.0, -5.0), Vec3.X, Vec3.Y), cx.region(cx.loop(cx.circleCR(c, cx.const(r.mm))))), cx.const((height + 10.0).mm))
        }
        val once = cx.subtract(body, drillAt(Vec2(v.x, v.y), 2.0))
        val first = Evaluator().solid(once)
        assertManifold(first.mesh, "the pivot bored through its corner")
        val fs1 = facesNamed(first, "the pivot bored through its corner")
        val corner1 = fs1.filter { it.pipe != null && it.outline.isNotEmpty() }
        assertTrue(corner1.size >= 2, "the bore down the corner leaves the pivot's face in pieces: ${corner1.size}")
        val twice = Evaluator().solid(cx.subtract(once, drillAt(Vec2(10.0, 45.0), 3.0)))
        assertManifold(twice.mesh, "the pivot bored twice")
        val fs2 = facesNamed(twice, "the pivot bored twice")
        val corner2 = fs2.filter { it.pipe != null && it.outline.isNotEmpty() }
        assertEquals(corner1.size, corner2.size, "the second bore, clear of the corner, leaves its pieces as they were:\n  once: ${corner1.map { it.name.label.render() }}\n  twice: ${corner2.map { it.name.label.render() }}")
        val labels = fs2.filter { it.outline.isNotEmpty() || it.plane != null }.map { it.name.label.render() }
        assertEquals(labels.size, labels.toSet().size, "every face has a name of its own: ${labels.groupBy { it }.filter { it.value.size > 1 }.keys}")
        val hole = Math.PI * 9.0 * height
        val taken = Geom3.volume(first.mesh) - Geom3.volume(twice.mesh)
        // …and it takes **less** than its own cylinder, because the loft leans: the second bore stands at
        // `(10, 45)`, which is inside the L at the bottom and outside it at the top — the outer wall passes
        // the bore's own centre at `z ≈ 15.2` — so the drill leaves the body through that leaning face and
        // what it removes is the part of its cylinder that is still inside. The figure below it is the full
        // disc up to where the wall first reaches the bore (`z ≈ 13.3`), and the whole of it is gone by
        // `z ≈ 17`; what is asserted is that the bore is a real removal of that size and no more than its
        // own cylinder.
        assertTrue(taken > 0.70 * hole && taken < hole + 1e-6, "the second bore takes its cylinder less what the leaning wall cuts off it: $taken vs $hole")
        val (below, wb) = Section3.regionsOf(twice.feature, Plane3(Vec3(0.0, 0.0, 5.0), Vec3.X, Vec3.Y))
        assertNotNull(below, "the level section at z = 5 closes: ${wb?.render()}")
        val (through, wt) = Section3.regionsOf(twice.feature, Plane3(Vec3(0.0, 0.0, height - 1.0), Vec3.X, Vec3.Y))
        assertNotNull(through, "the level section through the bored corner closes: ${wt?.render()}")
    }

    /**
     * **The tight bend, bored through its band** — the canal whose cap step slice 5l learned to state, and a
     * bore across it: every face named, and the vertical plane at x = 38 that used to break at the canal's flat
     * end closes on the bored body too, as does the level plane through the bands.
     */
    @Test
    fun theTightBendBoredThroughItsBandIsNamedAndItsVerticalSectionCloses() {
        requireEngine()
        val cx = Construction()
        val tight = dressedMitre(cx, BlendKind.FILLET, 2.5)
        val plain = Evaluator().solid(tight)
        assertManifold(plain.mesh, "the tight bend")
        val (before, wb) = Section3.regionsOf(plain.feature, Plane3(Vec3(38.0, 0.0, 0.0), Vec3.Y, Vec3.Z))
        val areaBefore = regionArea(assertNotNull(before, "the tight bend's vertical section at x = 38 closes: ${wb?.render()}"))
        val bored = Evaluator().solid(cx.subtract(tight, drill(cx, Vec2(38.0, 27.0), 1.5)))
        assertManifold(bored.mesh, "the tight bend bored through its band")
        val fs = facesNamed(bored, "the tight bend bored through its band")
        assertTrue(fs.any { it.pipe != null && it.outline.isNotEmpty() }, "the canal band is still a named face")
        val labels = fs.filter { it.outline.isNotEmpty() || it.plane != null }.map { it.name.label.render() }
        assertEquals(labels.size, labels.toSet().size, "every face has a name of its own")
        val (after, wa) = Section3.regionsOf(bored.feature, Plane3(Vec3(38.0, 0.0, 0.0), Vec3.Y, Vec3.Z))
        val areaAfter = regionArea(assertNotNull(after, "the bored tight bend's vertical section at x = 38 closes: ${wa?.render()}"))
        // the bore's axis stands in this very plane, so its trace is the 3 mm wide strip down the body's height
        assertTrue(areaAfter < areaBefore - 3.0 * 15.0 && areaAfter > areaBefore - 3.0 * height - 1.0, "the bore takes its own strip out of the vertical section: $areaBefore -> $areaAfter")
        val (level, wl) = Section3.regionsOf(bored.feature, Plane3(Vec3(0.0, 0.0, 17.5), Vec3.X, Vec3.Y))
        assertNotNull(level, "the level section through the bands and the bore closes: ${wl?.render()}")
    }

    @Test
    fun aBevelledBodyBoredTwiceStillNamesItsStrip() = boredTwice(BlendKind.CHAMFER, 1.0, "the bevelled mitre")

    @Test
    fun aCanalBodyBoredTwiceStillNamesItsBand() = boredTwice(BlendKind.FILLET, 1.0, "the canal")
}
