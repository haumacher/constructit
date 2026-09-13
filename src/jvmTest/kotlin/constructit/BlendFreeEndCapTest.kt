package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.Construction
import constructit.dsl.RegionRef
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.editor.Document
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.Blend3
import constructit.geom.BlendKind
import constructit.geom.BlendSection
import constructit.geom.FaceName
import constructit.geom.GeomMath
import constructit.geom.Plane3
import constructit.geom.Region
import constructit.geom.Section3
import constructit.geom.Solid3
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.units.mm
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **A band's free end takes a cap wherever no face is square to it** (OP-31, slice 5p).
 *
 * *The sentence this retires, and it is session 81's own.* A band that ends without a corner closes on a flat
 * cap standing in the plane square to its crease, and *"at a free end of a straight edge that frame lies in
 * the end face's plane — the cap is square to the edge and so is the face"*. That holds at a **right angle**
 * and nowhere else. The neighbouring wall of a regular **pentagonal** prism stands at the polygon's own
 * exterior angle; a **loft's** side face leans by the slant. In both the cap is a face of the body like any
 * other, and until this slice a straight crease owned no flat-end slot to state it in — so a level section
 * through the band region could not close, on a shape as ordinary as a prism with one rounded top edge.
 *
 * *Two things were missing and the loop needed both.* The **cap** itself, which is what
 * [constructit.editor.DocumentFormat.CAP_SLOT_VERSION] is spent on; and the **triangle of the neighbouring
 * face** that survives past that cap, which the band's own strip of constant width had trimmed off the
 * face's whole boundary piece. The second is [Blend3]'s `capStep`, and it is `bandToItsCorners`' own rule
 * one dimension over: a band's strip runs only over the band's own run.
 *
 * *The figure.* In a level plane at height `h` below the cap the band shows as one straight ruling, so what
 * the rounding takes off the plain section there is a **rectangle**: the crease's own length by the depth the
 * band bites into the neighbouring wall. That depth is the 2D fillet of radius `r` in the crease's own
 * dihedral, and for a prism — cap horizontal, wall vertical, so the dihedral is a right angle — it is
 * `r − √(2rh − h²)`, which is nothing at all once `h ≥ r`.
 */
class BlendFreeEndCapTest {
    private var ids = 0

    private val height = 20.0
    private val radius = 30.0

    private fun polygon(
        cx: Construction,
        pts: List<Vec2>,
    ): RegionRef {
        val ps = pts.map { cx.freePoint("C${ids++}", it.x.mm, it.y.mm) }
        return cx.region(cx.loop(*ps.indices.map { cx.segment(ps[it], ps[(it + 1) % ps.size]) }.toTypedArray()))
    }

    /** A regular [n]-gon prism of circumradius 30 and height 20 — the most ordinary extrusion there is. */
    private fun prism(
        cx: Construction,
        n: Int,
    ): SolidRef {
        val pts = (0 until n).map { Vec2(radius * cos(2 * PI * it / n), radius * sin(2 * PI * it / n)) }
        return cx.extrude(cx.sketchOn(cx.planeXY(), polygon(cx, pts)), cx.const(height.mm))
    }

    /** The edges of [s] that lie wholly in the top cap's own plane, in the edge list's order. */
    private fun topEdges(s: Solid3): List<Int> {
        val es = assertNotNull(Section3.edges(s.feature).first, "it names its edges")
        return es.indices.filter { i ->
            val p = Blend3.edgePath(es[i]).first ?: return@filter false
            val a = p.start ?: return@filter false
            val b = p.end ?: return@filter false
            abs(a.z - height) < 1e-9 && abs(b.z - height) < 1e-9
        }
    }

    private fun round(
        cx: Construction,
        on: SolidRef,
        address: List<Int>,
        size: Double,
    ): Pair<SolidRef?, String?> {
        val body = Evaluator().solid(on)
        val (choices, why) = Blend3.choicesFor(body, address, BlendSection(BlendKind.FILLET, size))
        if (choices == null) return null to (why?.render() ?: "no choice")
        val ref = cx.blendAll(on, cx.planeXY(), listOf(Construction.BlendRun(BlendKind.FILLET, cx.const(size.mm), null, address, choices)))
        val r = Evaluator().eval(ref.node)
        if (r is EvalResult.Invalid) return null to r.why.render()
        return ref to null
    }

    private fun areaOf(regions: List<Region>): Double =
        regions.sumOf { r -> abs(GeomMath.signedArea(r.outer)) - r.holes.sumOf { abs(GeomMath.signedArea(it)) } }

    private fun levelArea(
        s: Solid3,
        z: Double,
        what: String,
    ): List<Region> {
        val (regions, why) = Section3.regionsOf(s.feature, Plane3(Vec3(0.0, 0.0, z), Vec3.X, Vec3.Y))
        return assertNotNull(regions, "$what: the level section at z = $z closes: ${why?.render()}")
    }

    /** The area of the plain [n]-gon of circumradius 30. */
    private fun planArea(n: Int): Double = n * 0.5 * radius * radius * sin(2 * PI / n)

    /** One edge of that [n]-gon. */
    private fun edgeLength(n: Int): Double = 2.0 * radius * sin(PI / n)

    /**
     * What one rounded top edge leaves of the prism's own level section at [z] — the plain polygon less the
     * rectangle the band takes: its crease's own length by `r − √(2rh − h²)`, nothing once `h ≥ r`.
     */
    private fun prismFigure(
        n: Int,
        z: Double,
        r: Double,
    ): Double {
        val h = height - z
        val strip = if (h <= 0.0 || h >= r) 0.0 else r - sqrt(2 * r * h - h * h)
        return planArea(n) - edgeLength(n) * strip
    }

    /**
     * **The same figure for the gestured frustum**, which is the shape the whole slice is about: a square of
     * side 100 at `z = 0` lofted to one of side 60 at `z = 60`, so every wall leans by `tan β = 20/60` and
     * the crease at the top rim stands at the dihedral `α = 90° + β` rather than at a right angle.
     *
     * The level section at [z] is the plain frustum's own square, `100 − 2z/3` on a side, less the rectangle
     * the band takes: the crease's own 60 mm by the depth it bites into the wall. That depth is the 2D fillet
     * of radius [r] in that dihedral — the setback `t = r·tan(45° − β/2) = r / tan(α/2)` along each face, the
     * band's trace standing `√(2rh − h²)` in from it and the wall's own trace having moved out by `h·tan β` —
     * and it is nothing at all below `t·cos β`, where the band's tangency on the wall is.
     */
    private fun frustumFigure(
        z: Double,
        r: Double,
    ): Double {
        val tanB = 20.0 / 60.0
        val cosB = 1.0 / sqrt(1.0 + tanB * tanB)
        val t = r / kotlin.math.tan((PI / 2 + kotlin.math.atan(tanB)) / 2)
        val h = 60.0 - z
        val strip = if (h <= 0.0 || h >= t * cosB) 0.0 else h * tanB + t - sqrt(2 * r * h - h * h)
        val side = 100.0 - 2.0 * z / 3.0
        return side * side - 60.0 * strip
    }

    /**
     * **The headline: a pentagonal prism with one rounded top edge.** Only the **square** ever worked —
     * there the neighbouring wall is square to the crease, the cap stands in it and is that face's own notch.
     * At every other plan angle the cap is a face of its own, and the section is asserted here against the
     * figure at seven heights: above the band, through it, at its own tangency and below.
     */
    @Test
    fun aPrismsRoundedTopEdgeClosesItsLevelSectionAtEveryHeight() {
        for (n in listOf(4, 5, 6, 7, 8)) {
            val cx = Construction()
            val base = prism(cx, n)
            val plain = Evaluator().solid(base)
            val what = "the $n-gon prism"
            val (ref, why) = round(cx, base, listOf(topEdges(plain).first()), 3.0)
            val body = Evaluator().solid(assertNotNull(ref, "$what: one top edge rounds: $why"))
            assertManifold(body.mesh, "$what with one rounded top edge")
            for (z in listOf(19.9, 19.5, 19.0, 18.0, 17.5, 17.0, 16.0)) {
                val r = levelArea(body, z, what)
                assertEquals(1, r.size, "$what: one region at z = $z")
                assertClose(areaOf(r), prismFigure(n, z, 3.0), 1e-9, "$what: the figure at z = $z")
            }
            // …and the plain prism's own section is what the figure reduces to below the band
            assertClose(areaOf(levelArea(plain, 16.0, what)), planArea(n), 1e-9, "$what: the plain prism's section")
        }
    }

    /**
     * **A whole cap chain** — every edge of the top face rounded in one gesture, so every band has two ends
     * and every one of them is a corner rather than a free end. The section closes at every height and takes
     * strictly more than one band does, which is the claim that the corners and the caps compose.
     */
    @Test
    fun aWholeCapChainOnAPrismClosesItsLevelSection() {
        for (n in listOf(5, 6)) {
            val cx = Construction()
            val base = prism(cx, n)
            val plain = Evaluator().solid(base)
            val what = "the $n-gon prism's whole cap"
            val all = topEdges(plain)
            assertEquals(n, all.size, "$what: one edge per side")
            val (ref, why) = round(cx, base, all, 3.0)
            val body = Evaluator().solid(assertNotNull(ref, "$what rounds: $why"))
            assertManifold(body.mesh, what)
            for (z in listOf(19.5, 19.0, 17.5, 16.0)) {
                val r = levelArea(body, z, what)
                assertEquals(1, r.size, "$what: one region at z = $z")
                val one = prismFigure(n, z, 3.0)
                assertTrue(areaOf(r) <= one + 1e-9, "$what at z = $z takes at least what one band takes: ${areaOf(r)} vs $one")
                assertTrue(areaOf(r) > 0.0, "$what at z = $z encloses something")
            }
            assertClose(areaOf(levelArea(body, 16.0, what)), planArea(n), 1e-9, "$what: below the band it is the plain prism again")
        }
    }

    /**
     * **And where a section still does not close, it names the face it broke at** (OP-3, OP-31 slice 5p).
     *
     * The reading that found this whole defect was a level section that refused, and the refusal said only
     * *"one of the faces it crosses"* — a whole session went into learning which. It says which now. The
     * case kept here is the one plane that can never be read as an area whatever the drawing does: the
     * prism's own cap plane `z = 20`, which the section lies *in* rather than crosses, so the pieces there
     * are degenerate and the chain runs out. The face it runs out at is slice 5p's own — the band's flat
     * end — and every plane a hair below it closes.
     */
    @Test
    fun aSectionThatDoesNotCloseNamesTheFaceItBrokeAt() {
        val cx = Construction()
        val base = prism(cx, 5)
        val (ref, why) = round(cx, base, listOf(topEdges(Evaluator().solid(base)).first()), 3.0)
        val body = Evaluator().solid(assertNotNull(ref, "the pentagon's top edge rounds: $why"))
        val (regions, refusal) = Section3.regionsOf(body.feature, Plane3(Vec3(0.0, 0.0, height), Vec3.X, Vec3.Y))
        assertEquals(null, regions, "the cap's own plane is no section of the body")
        val words = assertNotNull(refusal, "…and it says so").render()
        assertTrue("does not close into an area" in words, "in the drawing's own sentence: $words")
        assertTrue("it breaks at the flat end of the rounded band" in words, "…naming the face it ran out at: $words")
        // …and a hair below it the very same body closes, which is what makes the name the answer
        assertEquals(1, levelArea(body, height - 1e-9, "the pentagon a hair below its cap").size, "one region just below")
    }

    /**
     * **A revolve's cap carries the same step** (the carrier check). A band along one straight profile edge
     * of a partial revolve ends on the turn's own cap, and that cap's boundary at the crease's end is a
     * **circle** — tangent to the band's flat cap, which grazes it. So nothing is owed there and nothing is
     * spliced, which is as much a part of the rule as the loft's triangle is: the step is asked of every
     * face a band's run ends short of, and answered only by the faces that really carry material past it.
     */
    @Test
    fun aRevolvesCapIsAskedForTheStepAndAnswersNothing() {
        val cx = Construction()
        val prof = listOf(Vec2(0.0, 10.0), Vec2(0.0, 30.0), Vec2(10.0, 30.0), Vec2(10.0, 10.0))
        val o = cx.freePoint("Ro", 0.mm, 0.mm)
        val axis = cx.direction(o, cx.freePoint("Rx", 1.mm, 0.mm))
        val base = cx.revolve(cx.sketchOn(cx.planeXY(), polygon(cx, prof)), o, axis, cx.const(constructit.units.Quantity(PI / 2, constructit.units.Dimension.ANGLE)))
        val plain = Evaluator().solid(base)
        val es = assertNotNull(Section3.edges(plain.feature).first, "it names its edges")
        // the radial edge of the start cap at the outer rim: a straight run between two planes, both of whose
        // ends stand on a *cylinder* rather than on a plane
        val radial =
            assertNotNull(
                es.indices.firstOrNull { i ->
                    val p = Blend3.edgePath(es[i]).first
                    val a = p?.start
                    val b = p?.end
                    a != null && b != null && abs(a.z) < 1e-9 && abs(b.z) < 1e-9 && abs(a.x - b.x) < 1e-9 && abs(a.x - 10.0) < 1e-9
                },
                "the start cap's own outer radial edge: ${es.map { it.name }}",
            )
        val (ref, why) = round(cx, base, listOf(radial), 2.0)
        val body = Evaluator().solid(assertNotNull(ref, "the cap edge rounds: $why"))
        assertManifold(body.mesh, "a revolve's cap edge rounded")
        val caps = assertNotNull(Section3.faces(body.feature).first, "it names its faces").filter { it.name is FaceName.BlendCap }
        assertEquals(2, caps.size, "the entry owns its two flat-end slots")
        for (c in caps) assertTrue(c.reason == null && c.plane != null, "…and each of them is a face: ${c.name.label.render()} ${c.reason?.render()}")
        // the section through the band region closes, on a plane along the axis and on one across it
        for (x in listOf(1.0, 2.0)) {
            val (r, w) = Section3.regionsOf(body.feature, Plane3(Vec3(x, 0.0, 0.0), Vec3.Y, Vec3.Z))
            assertNotNull(r, "the section across the axis at x = $x closes: ${w?.render()}")
        }
    }

    // ---- through the editor and the file ----

    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerMove(s)
        pointerDown(s)
        pointerUp(s)
    }

    private fun Editor.type(digits: String) {
        for (c in digits) key(c.toString())
        key("Enter")
    }

    @Suppress("UNCHECKED_CAST")
    private fun bodyOf(ed: Editor): Solid3 = Evaluator().solid(ed.doc.elements.last { it.kind == ElementKind.SOLID }.ref as SolidRef)

    /**
     * **The whole way round, by gestures and through the file: a pentagonal prism.** The plan is a regular
     * five-sided polygon, the body its extrusion, the rounding an ordinary `filletedge` click — and the file
     * it saves is a fixed point whose reloaded body encloses the same area at the same height, to the last
     * bit. Nothing about slice 5p is recorded in the file: no slot name, no `signs=` of a different length,
     * and the step is the one it always was.
     */
    @Test
    fun aPentagonalPrismRoundsThroughTheEditorAndTheFileIsAFixedPoint() {
        val ed = Editor()
        ed.count = 5
        ed.setTool(Tools.POLYGON)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(radius, 0.0))
        ed.setTool(Tools.OUTLINE)
        for (i in 0 until 5) {
            val a = 2 * PI * i / 5
            val b = 2 * PI * (i + 1) / 5
            ed.click(Vec2(radius * (cos(a) + cos(b)) / 2.0, radius * (sin(a) + sin(b)) / 2.0))
        }
        ed.key("Enter")
        ed.setTool(Tools.EXTRUDE)
        ed.type("20")
        ed.click(Vec2(radius * (cos(0.0) + cos(2 * PI / 5)) / 2.0, radius * (sin(0.0) + sin(2 * PI / 5)) / 2.0))
        assertTrue(ed.doc.elements.any { it.kind == ElementKind.SOLID }, "the pentagon extrudes: ${ed.statusHint}")
        val plain = bodyOf(ed)
        assertClose(areaOf(levelArea(plain, 16.0, "the gestured pentagon")), planArea(5), 1e-9, "the plain prism")
        ed.setTool(Tools.BLEND_EDGE)
        ed.type("3")
        // the mid-point of the first top edge, in the plan — a click on the body's own rim
        ed.click(Vec2(radius * (cos(0.0) + cos(2 * PI / 5)) / 2.0, radius * (sin(0.0) + sin(2 * PI / 5)) / 2.0))
        val rounded = bodyOf(ed)
        assertManifold(rounded.mesh, "the gestured pentagon's rounded top edge")
        val area = areaOf(levelArea(rounded, 19.5, "the gestured pentagon, rounded"))
        assertClose(area, prismFigure(5, 19.5, 3.0), 1e-9, "…and the gesture's own body is the figure's")

        val once = DocumentFormat.save(ed.doc)
        val back = DocumentFormat.load(once)
        assertEquals(once, DocumentFormat.save(back), "save → load → save is byte-equal:\n$once")
        assertTrue("constructit ${DocumentFormat.VERSION}" in once, "written at this version")
        // **nothing about a flat-end slot is in the file**: no new argument, no slot name, and the rounding's
        // own `signs=` is the address and one choice per edge, exactly as before (OP-21, OP-18)
        assertTrue("cap" !in once && "slots=" !in once, "the file says nothing about flat ends:\n$once")
        val ed2 = Editor()
        ed2.replaceDocument(back)
        assertEquals(emptyList(), ed2.doc.loadNotes.map { it.toString() }, "a file at this version says nothing on load")
        assertClose(areaOf(levelArea(bodyOf(ed2), 19.5, "reloaded")), area, 1e-9, "…and the reloaded body encloses the same area")
    }

    /**
     * **The same, for a loft.** Two squares on two planes, the upper one smaller, so every side face *leans*
     * — which is the shape the whole slice is about. The top cap's own edge is rounded by an ordinary
     * `filletedge` click, and the reloaded body's section closes on the same area.
     */
    @Test
    fun aLoftRoundsThroughTheEditorAndTheFileIsAFixedPoint() {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 100.0))
        ed.setTool(Tools.SKETCH_PLANE)
        ed.type("0")
        ed.type("60")
        ed.click(Vec2(30.0, 0.0))
        assertTrue(!ed.activeSpace.isPlan, "the view switched to the datum plane: ${ed.statusHint}")
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(20.0, 20.0))
        ed.click(Vec2(80.0, 80.0))
        val datum = ed.activeSpace.name
        ed.setTool(Tools.LOFT)
        ed.click(Vec2(50.0, 20.0))
        assertTrue(ed.setActiveSpace(Document.PLAN_SPACE), "back to the plan for the other section")
        ed.click(Vec2(30.0, 0.0))
        ed.key("Enter")
        val plain = bodyOf(ed)
        assertManifold(plain.mesh, "the gestured frustum")
        val top = topEdgesAt(plain, 60.0)
        assertEquals(4, top.size, "the top cap has four edges: ${top.size}")
        // **a solid is clicked where the drawing draws it** — in the space it was sketched in, which for a
        // loft is its *first* section's (OP-17; `LoftToolTest.theLoftIsAtHomeInItsFirstSectionsSpace`). This
        // one's first section is the datum 60 mm up, so that is where its footprint — and the top cap's own
        // rim with it — is there to be clicked.
        assertTrue(ed.setActiveSpace(datum), "the loft is clicked in the space it was sketched in")
        ed.setTool(Tools.BLEND_EDGE)
        ed.type("4")
        ed.click(Vec2(50.0, 20.0))
        val rounded = bodyOf(ed)
        assertTrue(ed.doc.elements.any { it.kind == ElementKind.DRESSING }, "the click rounds the rim: ${ed.statusHint}")
        assertManifold(rounded.mesh, "the gestured frustum's rounded top edge")
        val z = 58.0
        val area = areaOf(levelArea(rounded, z, "the gestured frustum, rounded"))
        assertClose(area, frustumFigure(z, 4.0), 1e-9, "…and the gesture's own body is the figure's at z = $z")
        assertTrue(area < areaOf(levelArea(plain, z, "the plain frustum")), "the rounding takes material at z = $z")

        val once = DocumentFormat.save(ed.doc)
        val back = DocumentFormat.load(once)
        assertEquals(once, DocumentFormat.save(back), "save → load → save is byte-equal:\n$once")
        val ed2 = Editor()
        ed2.replaceDocument(back)
        assertClose(areaOf(levelArea(bodyOf(ed2), z, "reloaded")), area, 1e-9, "…and the reloaded body encloses the same area")
    }

    /** The edges of [s] lying wholly at height [h]. */
    private fun topEdgesAt(
        s: Solid3,
        h: Double,
    ): List<Int> {
        val es = assertNotNull(Section3.edges(s.feature).first, "it names its edges")
        return es.indices.filter { i ->
            val p = Blend3.edgePath(es[i]).first ?: return@filter false
            val a = p.start ?: return@filter false
            val b = p.end ?: return@filter false
            abs(a.z - h) < 1e-9 && abs(b.z - h) < 1e-9
        }
    }

    /**
     * **A loft whose side faces are not planes refuses by name, before the body is built** (OP-3).
     *
     * Turn the upper section by six degrees and every side face becomes a **ruled strip** between two edges
     * that are not parallel — no plane, so no crease this drawing can state a rigid section along and no
     * face for a flat end to step in. The rounding is refused in the face's own words, with how far out of
     * plane its corners stand, rather than built into a shell that does not close.
     */
    @Test
    fun aLoftWhoseSideFaceIsRuledRefusesTheRoundingByName() {
        val cx = Construction()
        val lo = listOf(Vec2(0.0, 0.0), Vec2(60.0, 0.0), Vec2(60.0, 30.0), Vec2(30.0, 30.0), Vec2(30.0, 60.0), Vec2(0.0, 60.0))
        val a = 6.0 * PI / 180.0
        val c = Vec2(25.0, 25.0)
        val hi = lo.map { p -> (p - c).let { Vec2(it.x * cos(a) - it.y * sin(a), it.x * sin(a) + it.y * cos(a)) } * 0.7 + c }
        val base =
            cx.loft(
                listOf(
                    constructit.dsl.LoftPart.Area(cx.sketchOn(cx.planeXY(), polygon(cx, lo))),
                    constructit.dsl.LoftPart.Area(cx.sketchOn(cx.planeOffset(cx.planeXY(), cx.const(height.mm)), polygon(cx, hi))),
                ),
            )
        val s = Evaluator().solid(base)
        assertManifold(s.mesh, "the twisted loft itself")
        val ruled = assertNotNull(Section3.faces(s.feature).first, "it names its faces").filter { it.name is FaceName.Band }
        assertEquals(6, ruled.size, "six side faces")
        for (f in ruled) {
            val why = assertNotNull(f.reason, "${f.name.label.render()} says it is no plane").render()
            assertTrue("ruled rather than flat" in why, "…in the drawing's own words: $why")
            assertTrue("mm out of plane" in why, "…with how far out of plane it stands: $why")
        }
        val top = topEdgesAt(s, height)
        assertTrue(top.isNotEmpty(), "the twisted loft still has a top cap with edges")
        val (ref, refusal) = round(cx, base, listOf(top.first()), 3.0)
        assertEquals(null, ref, "a rounding along a crease one of whose faces is no plane is not built")
        val words = assertNotNull(refusal, "…and it is refused in words")
        assertTrue("ruled rather than flat" in words, "the refusal names what the face is: $words")
        assertTrue("section 2" in words || "sections 1 and 2" in words, "…and which face it is: $words")
    }
}
