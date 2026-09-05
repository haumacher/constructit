package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.Construction
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.editor.Camera3
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.editor.Viewport3
import constructit.geom.Blend3
import constructit.geom.BlendKind
import constructit.geom.BlendSection
import constructit.geom.Geom3
import constructit.geom.Mesh3
import constructit.geom.Section3
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.units.mm
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **Orchestrator's probe of the pivot about a band** (the corner where a concave upright meets two convex
 * bands), on fixtures the delivery never saw: the two roundings of **different** sizes, one upright carrying
 * **two** such corners (both caps rounded), a body with **two** inside corners, and the whole thing driven
 * through the editor's own 3D picks and the file.
 */
class BlendMixedVertexProbeTest {
    private val h = 20.0

    /** The reporter's L, as plan points. */
    private val plan =
        listOf(
            Vec2(-26.875, -32.375),
            Vec2(-26.875, 15.375),
            Vec2(61.875, 15.375),
            Vec2(61.875, 0.375),
            Vec2(-5.521648428788623, 0.375),
            Vec2(-5.521648428788623, -32.375),
        )
    private val inside = plan[4]

    // ---- closed forms ----

    /** A fillet wedge's section area. */
    private fun wedge(r: Double): Double = r * r * (1 - PI / 4)

    /** Two equal fillet bands crossing at a right angle take this much less than their sum. */
    private fun joint(r: Double): Double = r * r * r * (5.0 / 3.0 - PI / 2.0)

    /**
     * A fillet band of radius [r] turned a quarter about an upright band of radius [ru] — Pappus over the wedge
     * standing [ru] out from the axis: the square minus the quarter disc, first moments and all.
     */
    private fun pivot(
        r: Double,
        ru: Double,
    ): Double {
        val square = r * r
        val squareAt = ru + r / 2
        val disc = PI * r * r / 4
        val discAt = ru + r - 4 * r / (3 * PI)
        val area = square - disc
        val at = (square * squareAt - disc * discAt) / area
        return area * (PI / 2) * at
    }

    private fun area(pts: List<Vec2>): Double = abs(pts.indices.sumOf { pts[it].cross(pts[(it + 1) % pts.size]) }) / 2

    private fun perimeter(pts: List<Vec2>): Double = pts.indices.sumOf { (pts[(it + 1) % pts.size] - pts[it]).length() }

    /** The L's exact volume with one cap chain of radius [r] and the inside upright filled with [ru]. */
    private fun exactL(
        r: Double,
        ru: Double,
        caps: Int,
    ): Double {
        val base = area(plan) * h
        val fill = wedge(ru) * h
        val chain = (perimeter(plan) - 2 * ru) * wedge(r) - 5 * joint(r) + pivot(r, ru)
        return base + fill - caps * chain
    }

    private fun assertBracket(
        v: Double,
        exact: Double,
        what: String,
    ) {
        assertTrue(v <= exact + 1e-6, "$what: mesh $v never above the exact body $exact")
        assertTrue(v >= exact * 0.998, "$what: mesh $v within the chords of the exact body $exact")
    }

    // ---- DSL fixtures ----

    private fun prism(
        cx: Construction,
        xy: List<Vec2>,
        depth: Double,
    ): SolidRef {
        val pts = xy.mapIndexed { i, p -> cx.freePoint("p$i", p.x.mm, p.y.mm) }
        val segs = xy.indices.map { cx.segment(pts[it], pts[(it + 1) % xy.size]) }
        return cx.extrude(cx.sketchOn(cx.planeXY(), cx.region(cx.loop(*segs.toTypedArray()))), cx.const(depth.mm))
    }

    private fun blendOn(
        cx: Construction,
        on: SolidRef,
        size: Double,
        whole: Boolean,
        address: Int,
        kind: BlendKind = BlendKind.FILLET,
    ): SolidRef {
        val body = Evaluator().solid(on)
        val (targets, whyTargets) = Blend3.targets(body.feature, whole, address)
        assertNotNull(targets, whyTargets)
        val (choices, why) = Blend3.choicesFor(body, targets, BlendSection(kind, size))
        assertNotNull(choices, why)
        return cx.blend(on, on, cx.planeXY(), cx.const(size.mm), kind, whole, address, choices)
    }

    private fun capFace(
        ref: SolidRef,
        z: Double,
    ): Int {
        val faces = assertNotNull(Section3.faces(Evaluator().solid(ref).feature).first)
        val nz = if (z > 0) 1.0 else -1.0
        return assertNotNull(
            faces.indices.firstOrNull { i ->
                val p = faces[i].plane ?: return@firstOrNull false
                abs(p.normal.normalized().z - nz) < 1e-9 && abs(p.origin.z - z) < 1e-9
            },
            "a cap at z = $z",
        )
    }

    private fun uprightAt(
        ref: SolidRef,
        xy: Vec2,
    ): Int {
        val edges = assertNotNull(Section3.edges(Evaluator().solid(ref).feature).first)
        return assertNotNull(
            edges.indices.firstOrNull { i ->
                val path = Blend3.edgePath(edges[i]).first ?: return@firstOrNull false
                val el = path.elements.singleOrNull() ?: return@firstOrNull false
                val ends = listOf(el.start, el.end).sortedBy { it.z }
                (ends[0] - Vec3(xy.x, xy.y, 0.0)).length() < 1e-6 && (ends[1] - Vec3(xy.x, xy.y, h)).length() < 1e-6
            },
            "an upright at $xy",
        )
    }

    private fun meshOf(ref: SolidRef): Mesh3 {
        val res = Evaluator().eval(ref.node)
        assertTrue(res !is EvalResult.Invalid, "valid: ${(res as? EvalResult.Invalid)?.reason}")
        return Evaluator().solid(ref).mesh
    }

    private fun volume(ref: SolidRef): Double {
        val m = meshOf(ref)
        assertManifold(m, "the blended body")
        return Geom3.volume(m)
    }

    // ---- 1. two different sizes, both orders ----

    @Test
    fun aSmallerAndALargerUprightBothTurnTheCapBandAboutThemselves() {
        for (ru in listOf(3.0, 8.0)) {
            val r = 5.0
            // cap first, then the upright
            val a =
                Construction().let { cx ->
                    val body = prism(cx, plan, h)
                    val cap = blendOn(cx, body, r, true, capFace(body, h))
                    blendOn(cx, cap, ru, false, uprightAt(cap, inside))
                }
            // upright first, then the cap
            val b =
                Construction().let { cx ->
                    val body = prism(cx, plan, h)
                    val fill = blendOn(cx, body, ru, false, uprightAt(body, inside))
                    blendOn(cx, fill, r, true, capFace(fill, h))
                }
            val va = volume(a)
            val vb = volume(b)
            val exact = exactL(r, ru, caps = 1)
            assertBracket(va, exact, "ru = $ru, cap first")
            assertBracket(vb, exact, "ru = $ru, upright first")
            assertTrue(abs(va - vb) < 1e-3, "ru = $ru: the two orders agree: $va vs $vb")
            // nothing of the fill stands above the band: no vertex of the fill's own quadrant near the top
            val stray =
                meshOf(a).vertices.filter {
                    it.x > inside.x + 1e-6 && it.x < inside.x + ru - 1e-6 && it.y < inside.y - 1e-6 && it.y > inside.y - ru + 1e-6 && it.z > h - 1e-3
                }
            assertTrue(stray.isEmpty(), "ru = $ru: nothing of the fill reaches the top plane: $stray")
        }
    }

    // ---- 2. both caps rounded: one upright, two pivots; then the far convex upright: two ball patches ----

    @Test
    fun oneUprightCarriesTwoPivotsAndTheFarUprightTwoBalls() {
        val r = 5.0
        val cx = Construction()
        val body = prism(cx, plan, h)
        val top = blendOn(cx, body, r, true, capFace(body, h))
        val both = blendOn(cx, top, r, true, capFace(top, 0.0))
        val fill = blendOn(cx, both, r, false, uprightAt(both, inside))
        val vFill = volume(fill)
        val exactFill = exactL(r, r, caps = 2)
        assertBracket(vFill, exactFill, "both caps and the inside upright")
        val far = blendOn(cx, fill, r, false, uprightAt(fill, plan[0]))
        val vFar = volume(far)
        // the convex upright: its straight run between the two bands, and at each end the ball's octant in place
        // of two mitred quarter-cylinders
        val ball = r * r * r * (1 - PI / 6)
        val mitred = 2 * r * wedge(r) - joint(r)
        val exactFar = exactFill - ((h - 2 * r) * wedge(r) + 2 * (ball - mitred))
        assertBracket(vFar, exactFar, "the far upright between two rounded caps")
        // the faces both levels name: the two horn tori are superseded with a reason, two ring tori stand
        val faces = assertNotNull(Section3.faces(Evaluator().solid(fill).feature).first)
        val superseded = faces.count { it.reason != null && it.plane == null && "stands in its place" in (it.reason ?: "") }
        assertEquals(2, superseded, "two horn tori superseded: ${faces.filter { it.reason != null }.map { it.name.label + ": " + it.reason }}")
    }

    // ---- 3. a body with two inside corners ----

    @Test
    fun aSlotHasTwoInsideCornersAndBothPivot() {
        val u =
            listOf(
                Vec2(0.0, 0.0),
                Vec2(60.0, 0.0),
                Vec2(60.0, 40.0),
                Vec2(40.0, 40.0),
                Vec2(40.0, 20.0),
                Vec2(20.0, 20.0),
                Vec2(20.0, 40.0),
                Vec2(0.0, 40.0),
            )
        val r = 5.0
        val cx = Construction()
        val body = prism(cx, u, h)
        val cap = blendOn(cx, body, r, true, capFace(body, h))
        val one = blendOn(cx, cap, r, false, uprightAt(cap, Vec2(40.0, 20.0)))
        val two = blendOn(cx, one, r, false, uprightAt(one, Vec2(20.0, 20.0)))
        val exact = area(u) * h + 2 * wedge(r) * h - ((perimeter(u) - 4 * r) * wedge(r) - 6 * joint(r) + 2 * pivot(r, r))
        assertBracket(volume(two), exact, "the slot with both inside uprights filled")
        // …and the reverse order lands on the same body
        val cy = Construction()
        val body2 = prism(cy, u, h)
        val f1 = blendOn(cy, body2, r, false, uprightAt(body2, Vec2(40.0, 20.0)))
        val f2 = blendOn(cy, f1, r, false, uprightAt(f1, Vec2(20.0, 20.0)))
        val cap2 = blendOn(cy, f2, r, true, capFace(f2, h))
        assertTrue(abs(volume(two) - volume(cap2)) < 1e-3, "orders agree on the slot")
    }

    // ---- 4. the editor's own picks, and the file ----

    private fun Editor.solids(): List<Element> = doc.elements.filter { it.kind == ElementKind.SOLID }

    private fun view(
        ed: Editor,
        cam: Camera3,
    ): Viewport3 {
        val vp = Viewport3(camera = cam, widthPx = 800.0, heightPx = 600.0)
        vp.editor = ed
        vp.shown = true
        return vp
    }

    private fun Viewport3.clickWorld(p: Vec3): Boolean {
        val s = camera.project(p, widthPx, heightPx) ?: return false
        pointerDown(s)
        pointerUp(s)
        return true
    }

    private val head = """constructit 4
orthostart -26.875,-32.375 -> e1
orthovertex -26.875,15.375 -> e2,e3
orthovertex 61.875,15.375 -> e4,e5
orthovertex 61.875,0.375 -> e6,e7
orthovertex -5.521648428788623,0.375 -> e8,e9
orthovertex -5.521648428788623,-32.375 -> e10,e11
orthoclose -> e12
param "h" = 20mm
tool extrude els=e11 clicks=-48.125,37.875 scalar="h" -> e13
param "r" = 5mm
"""

    /** A gesture aimed at [at] from the first of [cams] that produces a new solid; asserts one did. */
    private fun gesture(
        ed: Editor,
        tool: String,
        at: Vec3,
        cams: List<Camera3>,
    ) {
        ed.activeScalar = ed.doc.scalars.first { it.name == "r" }
        val before = ed.solids().size
        for (cam in cams) {
            ed.setTool(tool)
            if (!view(ed, cam).clickWorld(at)) continue
            if (ed.solids().size == before + 1) return
        }
        throw AssertionError("no camera made the $tool at $at: ${ed.statusHint}")
    }

    @Test
    fun theEditorRoundsBothCapsThenFillsTheInsideUprightAndTheFileHoldsIt() {
        val ed = Editor()
        ed.replaceDocument(DocumentFormat.load(head))
        val t = Vec3(17.5, -8.5, 10.0)
        gesture(ed, Tools.BLEND_FACE, Vec3(20.0, 8.0, h), listOf(Camera3(target = t, distance = 300.0, yaw = -1.0, pitch = 1.1)))
        gesture(ed, Tools.BLEND_FACE, Vec3(20.0, 8.0, 0.0), listOf(Camera3(target = t, distance = 300.0, yaw = -1.0, pitch = -1.1)))
        // the inside upright's midpoint, seen from inside the notch
        val mid = Vec3(inside.x, inside.y, h / 2)
        gesture(
            ed,
            Tools.BLEND_EDGE,
            mid,
            listOf(-0.8, -0.5, -1.2, 0.8, 2.4, 5.5, 4.0, 0.0, PI).map { Camera3(target = mid, distance = 150.0, yaw = it, pitch = 0.25) },
        )
        val tip = ed.solids().last()
        val v = volume(tip.ref as SolidRef)
        assertBracket(v, exactL(5.0, 5.0, caps = 2), "the editor's three gestures")
        // the file: a fixed point, and a reload builds the very same body
        val once = DocumentFormat.save(ed.doc)
        val again = DocumentFormat.save(DocumentFormat.load(once))
        assertEquals(once, again, "save → load → save is a fixed point")
        val reloaded = DocumentFormat.load(once)
        assertTrue(reloaded.loadNotes.isEmpty(), "no load notes: ${reloaded.loadNotes}")
        val back = reloaded.elements.last { it.kind == ElementKind.SOLID }.ref as SolidRef
        assertNull((Evaluator().eval(back.node) as? EvalResult.Invalid)?.reason, "the reloaded tip is valid")
        assertEquals(v, Geom3.volume(Evaluator().solid(back).mesh), 1e-9, "the reload is the same body")
        // undo takes the fill off and leaves the two rounded caps valid
        ed.undo()
        val caps = ed.solids().last().ref as SolidRef
        assertNull((Evaluator().eval(caps.node) as? EvalResult.Invalid)?.reason, "two rounded caps stand after undo")
    }
}
