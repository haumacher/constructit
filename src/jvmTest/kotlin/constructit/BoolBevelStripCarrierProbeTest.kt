package constructit

import constructit.core.Evaluator
import constructit.dsl.Construction
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
import kotlin.math.abs
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

    @Test
    fun aBevelledBodyBoredTwiceStillNamesItsStrip() = boredTwice(BlendKind.CHAMFER, 1.0, "the bevelled mitre")

    @Test
    fun aCanalBodyBoredTwiceStillNamesItsBand() = boredTwice(BlendKind.FILLET, 1.0, "the canal")
}
