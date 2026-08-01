package constructit

import constructit.core.ArcValue
import constructit.core.Evaluator
import constructit.dsl.ArcRef
import constructit.dsl.valueOf
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.PreviewShape
import constructit.editor.SlotKind
import constructit.editor.SvgDrawTarget
import constructit.editor.Tools
import constructit.geom.Vec2
import constructit.units.deg
import constructit.units.mm
import kotlin.math.PI
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **Live tool previews** (`ToolDef.preview`): what the next click will build, drawn under the cursor.
 *
 * Three things are asserted here, and they are the three claims the mechanism makes.
 *
 * 1. **Honesty** — the previewed geometry *is* the geometry the click produces. Wherever the click's result
 *    can be compared directly (the fillet's scored variant, a dimension's graphic) the test builds it and
 *    compares, instead of restating the formula a second time.
 * 2. **The values in effect are in the picture** — a typed radius, a defaulted corner radius, the current
 *    structural count.
 * 3. **Hovering never touches the graph** — asserted *generically*, over every tool that has a preview at
 *    all, rather than tool by tool: no node, no element, no step, and a byte-identical saved script across a
 *    sweep of hovers. That is the rule the ortho band already followed (DESIGN: "hover-time intersections are
 *    computed with GeomMath, so previewing never touches the graph"), now a property of the whole mechanism.
 */
class PreviewTest {
    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerDown(s)
        pointerUp(s)
    }

    private fun Editor.hover(world: Vec2) = pointerMove(camera.worldToScreen(world))

    private fun Editor.segmentAt(
        a: Vec2,
        b: Vec2,
    ) {
        setTool(Tools.SEGMENT)
        click(a)
        click(b)
    }

    private fun Editor.circleAt(
        centre: Vec2,
        r: Double,
        name: String,
    ) {
        activeScalar = doc.newParameter(name, r.mm)
        setTool(Tools.CIRCLE_R)
        click(centre)
    }

    private fun Editor.previewSegs() = previewShapes.filterIsInstance<PreviewShape.Seg>().map { it.seg }

    private fun Editor.previewCircles() = previewShapes.filterIsInstance<PreviewShape.Circ>().map { it.circle }

    private fun Editor.previewArcs() = previewShapes.filterIsInstance<PreviewShape.ArcS>().map { it.arc }

    private fun Editor.previewDots() = previewShapes.filterIsInstance<PreviewShape.Dot>().map { it.at }

    // ---- the band, in the shape the click will actually make ----

    @Test
    fun theSegmentBandIsTheSegmentTheClickWillMake() {
        val ed = Editor()
        ed.setTool(Tools.SEGMENT)
        assertTrue(ed.previewShapes.isEmpty(), "an armed tool with nothing picked draws nothing")
        ed.click(Vec2(0.0, 0.0))
        ed.hover(Vec2(20.0, 10.0))
        val seg = ed.previewSegs().single()
        assertClose(seg.a.x, 0.0)
        assertClose(seg.a.y, 0.0)
        assertClose(seg.b.x, 20.0)
        assertClose(seg.b.y, 10.0)
    }

    /** A line and a ray preview in their *own* forms — infinite and half-infinite, as the renderer draws them. */
    @Test
    fun aLinePreviewsAsALineAndARayAsARay() {
        val ed = Editor()
        ed.setTool(Tools.LINE)
        ed.click(Vec2(0.0, 0.0))
        ed.hover(Vec2(30.0, 0.0))
        val line = ed.previewShapes.filterIsInstance<PreviewShape.Ln>().single().line
        assertClose(line.dir.x, 1.0)
        assertClose(line.dir.y, 0.0)

        val ed2 = Editor()
        ed2.setTool(Tools.RAY)
        ed2.click(Vec2(5.0, 5.0))
        ed2.hover(Vec2(5.0, 25.0))
        val ray = ed2.previewShapes.filterIsInstance<PreviewShape.Ry>().single().ray
        assertClose(ray.origin.y, 5.0)
        assertClose(ray.dir.y, 1.0)
    }

    @Test
    fun theCircleGrowsWithTheCursor() {
        val ed = Editor()
        ed.setTool(Tools.CIRCLE)
        ed.click(Vec2(0.0, 0.0))
        ed.hover(Vec2(30.0, 40.0))
        val c = ed.previewCircles().single()
        assertClose(c.center.x, 0.0)
        assertClose(c.radius, 50.0, msg = "radius is the cursor's distance from the clicked centre")
    }

    /** The circumcircle through the two picks and the cursor — the third point, live. */
    @Test
    fun theThreePointCirclePreviewsThroughAllThree() {
        val ed = Editor()
        ed.setTool(Tools.CIRCLE_3)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(20.0, 0.0))
        ed.hover(Vec2(0.0, 20.0))
        val c = ed.previewCircles().single()
        assertClose(c.center.x, 10.0)
        assertClose(c.center.y, 10.0)
        for (p in listOf(Vec2(0.0, 0.0), Vec2(20.0, 0.0), Vec2(0.0, 20.0))) {
            assertClose((p - c.center).length(), c.radius, msg = "the previewed circle passes through $p")
        }
        assertClose(c.radius, sqrt(200.0))
    }

    /** …and the same for the three-point arc, which must also get its *sweep* right. */
    @Test
    fun theThreePointArcPreviewsThroughTheCursor() {
        val ed = Editor()
        ed.setTool(Tools.ARC_3)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(10.0, 10.0))
        ed.hover(Vec2(20.0, 0.0))
        val arc = ed.previewArcs().single()
        assertClose(arc.center.x, 10.0)
        assertClose(arc.center.y, 0.0)
        assertClose(arc.radius, 10.0)
        assertClose(arc.startAngle, PI)
        assertClose(arc.endAngle, 0.0)
        assertTrue(!arc.ccw, "start, through, end wind clockwise here, so the previewed arc must too")
    }

    // ---- closed shapes, with the values in effect ----

    @Test
    fun theRectanglePreviewsItsClosedOutline() {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.hover(Vec2(30.0, 20.0))
        val path = ed.previewShapes.filterIsInstance<PreviewShape.Path>().single().points
        assertEquals(5, path.size, "a closed outline repeats its first corner")
        assertEquals(listOf(0.0, 30.0, 30.0, 0.0, 0.0), path.map { round(it.x) })
        assertEquals(listOf(0.0, 0.0, 20.0, 20.0, 0.0), path.map { round(it.y) })
    }

    /** The rounded rectangle previews **with the radius in effect** — four sides and four quarter arcs. */
    @Test
    fun theRoundedRectanglePreviewsTheRadiusInEffect() {
        val ed = Editor()
        ed.activeScalar = ed.doc.newParameter("radius", 5.0.mm)
        ed.setTool(Tools.ROUNDED_RECT)
        ed.click(Vec2(0.0, 0.0))
        ed.hover(Vec2(40.0, 30.0))
        assertEquals(4, ed.previewSegs().size)
        val arcs = ed.previewArcs()
        assertEquals(4, arcs.size)
        assertTrue(arcs.all { kotlin.math.abs(it.radius - 5.0) < 1e-9 }, "each corner arc is the radius in effect")
        // the corner centres are inset by the radius from the span's corners
        // centre (20, 15), inset by the radius: (20±15, 15±10)
        assertEquals(setOf(35.0 to 25.0, 5.0 to 25.0, 5.0 to 5.0, 35.0 to 5.0), arcs.map { round(it.center.x) to round(it.center.y) }.toSet())
    }

    /** The polygon previews the **current count**, and gains its corner arcs when a radius is in effect. */
    @Test
    fun thePolygonPreviewsTheCountAndTheCornerRadius() {
        val ed = Editor()
        ed.count = 5
        ed.setTool(Tools.POLYGON)
        ed.click(Vec2(0.0, 0.0))
        ed.hover(Vec2(20.0, 0.0))
        val path = ed.previewShapes.filterIsInstance<PreviewShape.Path>().single().points
        assertEquals(6, path.size, "five vertices, closed")
        assertTrue(path.all { kotlin.math.abs(it.length() - 20.0) < 1e-9 }, "every vertex is the clicked one, rotated")
        assertTrue(ed.previewArcs().isEmpty(), "with the radius at its default (0 = don't round) there is nothing to round")

        ed.activeScalar = ed.doc.newParameter("corner radius", 3.0.mm)
        ed.hover(Vec2(20.0, 0.0))
        assertEquals(5, ed.previewArcs().size, "a non-zero radius rounds every corner, as the gesture does")
        assertTrue(ed.previewArcs().all { kotlin.math.abs(it.radius - 3.0) < 1e-9 })
    }

    /** A count change redraws the preview where the cursor last was — it is one of the values in effect. */
    @Test
    fun changingTheCountRedrawsThePreview() {
        val ed = Editor()
        ed.count = 4
        ed.setTool(Tools.POLYGON)
        ed.click(Vec2(0.0, 0.0))
        ed.hover(Vec2(20.0, 0.0))
        assertEquals(5, ed.previewShapes.filterIsInstance<PreviewShape.Path>().single().points.size)
        ed.count = 8
        assertEquals(9, ed.previewShapes.filterIsInstance<PreviewShape.Path>().single().points.size)
    }

    // ---- ghosts: the copies a transform would make ----

    @Test
    fun rotatePreviewsAGhostAtTheAngleInEffect() {
        val ed = Editor()
        ed.segmentAt(Vec2(10.0, 0.0), Vec2(30.0, 0.0))
        ed.activeScalar = ed.doc.newParameter("angle", 90.0.deg)
        ed.setTool(Tools.ROTATE)
        ed.click(Vec2(20.0, 0.0)) // the segment, away from its endpoints
        ed.hover(Vec2(0.0, 0.0)) // the centre this click would place
        val ghost = ed.previewSegs().single()
        assertClose(ghost.a.x, 0.0)
        assertClose(ghost.a.y, 10.0)
        assertClose(ghost.b.x, 0.0)
        assertClose(ghost.b.y, 30.0)
    }

    @Test
    fun mirrorPreviewsAcrossTheAxisUnderTheCursor() {
        val ed = Editor()
        ed.circleAt(Vec2(20.0, 20.0), 5.0, "r")
        ed.segmentAt(Vec2(0.0, -40.0), Vec2(0.0, 40.0))
        ed.setTool(Tools.MIRROR)
        ed.click(Vec2(25.0, 20.0)) // on the circle
        assertTrue(ed.previewShapes.isEmpty(), "with the cursor over no line there is no axis, so nothing is promised")
        ed.hover(Vec2(0.0, 10.0)) // over the vertical segment: that is the axis
        val ghost = ed.previewCircles().single()
        assertClose(ghost.center.x, -20.0)
        assertClose(ghost.center.y, 20.0)
        assertClose(ghost.radius, 5.0)
    }

    @Test
    fun aLinearArrayPreviewsEveryCopy() {
        val ed = Editor()
        ed.circleAt(Vec2(0.0, 0.0), 4.0, "r")
        ed.count = 3
        ed.setTool(Tools.ARRAY_LINEAR)
        ed.click(Vec2(4.0, 0.0)) // the circle
        ed.click(Vec2(0.0, 0.0)) // the step vector's start
        ed.hover(Vec2(20.0, 0.0))
        val ghosts = ed.previewCircles()
        assertEquals(2, ghosts.size, "three instances means the original plus two copies")
        assertEquals(listOf(20.0, 40.0), ghosts.map { it.center.x })
    }

    /** A circular pattern previews **the ring under the cursor** — the members it would stamp (OP-23). */
    @Test
    fun aCircularPatternPreviewsItsRing() {
        val ed = Editor()
        ed.count = 4
        ed.setTool(Tools.PATTERN_CIRCULAR)
        ed.click(Vec2(0.0, 0.0)) // the centre
        ed.hover(Vec2(10.0, 0.0)) // the reference member
        val dots = ed.previewDots()
        assertEquals(4, dots.size)
        assertEquals(setOf(10.0 to 0.0, 0.0 to 10.0, -10.0 to 0.0, 0.0 to -10.0), dots.map { round(it.x) to round(it.y) }.toSet())
    }

    /** Rounded for comparison — and `+ 0.0`, so a screen round trip's -0.0 is not a different number. */
    private fun round(v: Double) = kotlin.math.round(v * 1e6) / 1e6 + 0.0

    // ---- the discrete choices, made visible before they are committed ----

    /**
     * **The fillet's variant flips with the cursor side, and the arc previewed is the arc built.**
     *
     * This is the case the mechanism exists for: which of the variants a pair of clicks means (OP-1) used to
     * be invisible until the click had already stored it. The preview runs the same scoring, so the two
     * hovers below promise different roundings — and the click confirms the promise byte for byte.
     */
    @Test
    fun theFilletPreviewFlipsWithTheCursorSideAndIsWhatTheClickBuilds() {
        val ed = Editor()
        ed.segmentAt(Vec2(-40.0, 0.0), Vec2(40.0, 0.0))
        ed.segmentAt(Vec2(0.0, -40.0), Vec2(0.0, 40.0))
        ed.activeScalar = ed.doc.newParameter("radius", 5.0.mm)
        ed.setTool(Tools.FILLET)
        ed.click(Vec2(20.0, 0.0)) // the first leg, on its right-hand half

        ed.hover(Vec2(0.0, 20.0))
        val above = ed.previewArcs().single()
        assertClose(above.center.x, 5.0)
        assertClose(above.center.y, 5.0)
        assertClose(above.radius, 5.0)

        ed.hover(Vec2(0.0, -20.0))
        val below = ed.previewArcs().single()
        assertClose(below.center.x, 5.0)
        assertClose(below.center.y, -5.0, msg = "the other side of the second leg is the other quadrant")

        // and the click keeps the promise the hover made
        ed.click(Vec2(0.0, -20.0))
        val built = builtArc(ed)
        assertClose(built.center.x, below.center.x)
        assertClose(built.center.y, below.center.y)
        assertClose(built.radius, below.radius)
        assertClose(built.startAngle, below.startAngle)
        assertClose(built.endAngle, below.endAngle)
        assertEquals(below.ccw, built.ccw)
    }

    /** The chamfer's quadrant is the same story, one shape simpler. */
    @Test
    fun theChamferPreviewIsTheBevelTheClickCuts() {
        val ed = Editor()
        ed.segmentAt(Vec2(-40.0, 0.0), Vec2(40.0, 0.0))
        ed.segmentAt(Vec2(0.0, -40.0), Vec2(0.0, 40.0))
        ed.activeScalar = ed.doc.newParameter("distance", 8.0.mm)
        ed.setTool(Tools.CHAMFER)
        ed.click(Vec2(20.0, 0.0))
        ed.hover(Vec2(0.0, 20.0))
        val bevel = ed.previewSegs().single()
        assertEquals(setOf(8.0 to 0.0, 0.0 to 8.0), setOf(round(bevel.a.x) to round(bevel.a.y), round(bevel.b.x) to round(bevel.b.y)))

        ed.click(Vec2(0.0, 20.0))
        val made = Evaluator().valueOf(ed.doc.elements.last { it.kind == ElementKind.SEGMENT }.ref)
        val seg = (made as constructit.core.SegmentValue).seg
        assertEquals(
            setOf(round(bevel.a.x) to round(bevel.a.y), round(bevel.b.x) to round(bevel.b.y)),
            setOf(round(seg.a.x) to round(seg.a.y), round(seg.b.x) to round(seg.b.y)),
            "the bevel built is the bevel previewed",
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun builtArc(ed: Editor) =
        (Evaluator().valueOf(ed.doc.elements.last { it.kind == ElementKind.ARC }.ref as ArcRef) as ArcValue).arc

    // ---- annotations: the graphic riding the cursor ----

    /** The dimension previewed is the dimension placed — the same graphic, from the same cursor position. */
    @Test
    fun aDimensionPreviewIsTheGraphicThePlacingClickLeaves() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(40.0, 0.0))
        ed.setTool(Tools.DIM_LINEAR)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(40.0, 0.0))
        ed.hover(Vec2(20.0, 15.0))
        val g = ed.previewShapes.filterIsInstance<PreviewShape.Dim>().single().graphic
        assertEquals(3, g.lines.size, "two extension lines and the dimension line")
        assertClose(g.lines[2].a.y, 15.0, msg = "the dimension line sits where the cursor is")
        assertEquals("40 mm", g.text)

        ed.click(Vec2(20.0, 15.0))
        val placed = ed.doc.elements.last { it.annotation != null }.annotation!!.graphic(Evaluator())
        assertNotNull(placed)
        assertEquals(g.lines.map { round(it.a.x) to round(it.a.y) }, placed.lines.map { round(it.a.x) to round(it.a.y) })
        assertEquals(g.lines.map { round(it.b.x) to round(it.b.y) }, placed.lines.map { round(it.b.x) to round(it.b.y) })
        assertEquals(g.text, placed.text)
    }

    /** A radial dimension's leader rides the cursor too, on the circle it names. */
    @Test
    fun aRadialDimensionPreviewsItsLeader() {
        val ed = Editor()
        ed.circleAt(Vec2(0.0, 0.0), 10.0, "r")
        ed.setTool(Tools.DIM_RADIAL)
        ed.click(Vec2(10.0, 0.0))
        ed.hover(Vec2(25.0, 0.0))
        val g = ed.previewShapes.filterIsInstance<PreviewShape.Dim>().single().graphic
        assertEquals(1, g.lines.size)
        assertClose(g.lines[0].a.x, 10.0, msg = "the leader starts on the circle")
        assertClose(g.lines[0].b.x, 25.0, msg = "…and reaches the cursor")
        assertEquals("R 10 mm", g.text)
    }

    // ---- the rendering half ----

    /** Mid-gesture the SVG carries the preview, exactly as it carries the ortho band. */
    @Test
    fun theSvgCarriesThePreviewMidGesture() {
        val ed = Editor()
        ed.setTool(Tools.CIRCLE)
        ed.click(Vec2(0.0, 0.0))
        val before = SvgDrawTarget().also { ed.render(it) }.svg()
        assertTrue(!before.contains("stroke=\"#ff7f0e\""), "nothing is previewed before the cursor moves")
        ed.hover(Vec2(10.0, 0.0))
        val svg = SvgDrawTarget().also { ed.render(it) }.svg()
        // the camera is 4 px/mm and centred, so a 10 mm circle at the origin is r=40 at (400, 300)
        assertTrue(
            svg.contains("<circle cx=\"400.000\" cy=\"300.000\" r=\"40.000\" fill=\"none\" stroke=\"#ff7f0e\""),
            "the growing circle should be in the drawing: $svg",
        )
    }

    // ---- the invariant, asserted once for every previewing tool ----

    /**
     * **No hover ever touches the graph.** One sweep over *every* tool that declares a preview: fill all but
     * its last slot from a scene that has something for each slot kind, then hover a grid of positions and
     * assert that nothing about the document moved — the node counter, the element list, the journal and the
     * saved script are all unchanged.
     *
     * Generic on purpose. A per-tool assertion would be a list somebody has to remember to extend; this one
     * covers the next preview to be written without being touched. It also asserts the sweep is not vacuous:
     * most of these tools must actually have drawn something, or the invariant would be about nothing.
     */
    @Test
    fun noHoverTouchesTheGraph() {
        val previewing = Tools.all.filter { it.preview != null }
        assertTrue(previewing.size >= 20, "the first wave of previews is ~20 tools, found ${previewing.size}")
        val drew = ArrayList<String>()
        val silent = ArrayList<String>()
        for (tool in previewing) {
            val ed = scene()
            // whatever scalars it wants, in effect — so the preview has no reason to decline
            for (slot in tool.scalars) ed.activeScalar = ed.doc.newParameter(slot.name, quantityFor(slot.dim))
            ed.count = 3
            ed.setTool(tool.id)
            // every slot but the last: the last one is what the cursor is standing in for
            val used = HashMap<SlotKind, Int>()
            for (kind in tool.slots.dropLast(1)) {
                val n = used.getOrElse(kind) { 0 }
                used[kind] = n + 1
                // a *different* spot per repeat of the same kind: two picks of one line (or one point) are a
                // degenerate input, and a preview that declines it would make the sweep prove nothing
                ed.click(spotFor(kind, n))
            }
            val nodes = ed.doc.cx.nodesCreated
            val elements = ed.doc.elements.size
            val steps = ed.doc.journal.size
            val saved = DocumentFormat.save(ed.doc)
            var any = false
            for (x in -30..30 step 10) {
                for (y in -30..30 step 10) {
                    ed.hover(Vec2(x.toDouble(), y.toDouble()))
                    if (ed.previewShapes.isNotEmpty()) any = true
                }
            }
            if (any) drew.add(tool.id) else silent.add(tool.id)
            assertEquals(nodes, ed.doc.cx.nodesCreated, "${tool.id}: hovering created nodes")
            assertEquals(elements, ed.doc.elements.size, "${tool.id}: hovering created elements")
            assertEquals(steps, ed.doc.journal.size, "${tool.id}: hovering recorded a step")
            assertEquals(saved, DocumentFormat.save(ed.doc), "${tool.id}: hovering changed the saved script")
        }
        assertTrue(drew.size >= previewing.size - 2, "the sweep drew nothing for $silent, so it proves little")
    }

    /** A scene with something for every slot kind a previewing tool can ask for. */
    private fun scene(): Editor {
        val ed = Editor()
        ed.segmentAt(Vec2(-40.0, 0.0), Vec2(40.0, 0.0)) // a LINE / CARRIER, through the origin
        ed.segmentAt(Vec2(0.0, -40.0), Vec2(0.0, 40.0)) // a second one, crossing it
        ed.segmentAt(Vec2(-40.0, 30.0), Vec2(40.0, 34.0)) // a third, all but parallel, for the tangent circle
        ed.circleAt(Vec2(-20.0, -20.0), 6.0, "sceneR") // a CIRCLE / CENTRIC / GEOMETRY
        ed.setTool(Tools.POINT)
        ed.click(Vec2(25.0, 25.0)) // two EXISTING_POINTs, clear of everything else
        ed.click(Vec2(35.0, 25.0))
        ed.setTool(Tools.SELECT)
        return ed
    }

    /** Where the [n]-th click filling [kind] lands in [scene] — each repeat somewhere else. */
    private fun spotFor(
        kind: SlotKind,
        n: Int,
    ): Vec2 {
        val spots =
            when (kind) {
                // the three segments, each picked well clear of the other two
                SlotKind.LINE, SlotKind.CARRIER, SlotKind.CURVE, SlotKind.SEGMENT ->
                    listOf(Vec2(20.0, 0.0), Vec2(0.0, 20.0), Vec2(-30.0, 30.5))
                // CENTERED and MEASURABLE take the circle too — a circle has a centre and a length (OP-24
                // widened both slots so an ellipse can fill them); CONIC has no previewing tool
                SlotKind.CIRCLE, SlotKind.CENTRIC, SlotKind.GEOMETRY, SlotKind.CENTERED, SlotKind.MEASURABLE, SlotKind.CONIC ->
                    listOf(Vec2(-14.0, -20.0))
                // the circle is an area too (a closed curve bounds one, OP-17), which is what an AREA or a
                // loft-part slot picks here — the scene needs no second area for it
                SlotKind.AREA, SlotKind.LOFT_PART -> listOf(Vec2(-14.0, -20.0))
                SlotKind.EXISTING_POINT -> listOf(Vec2(25.0, 25.0), Vec2(35.0, 25.0))
                else -> listOf(Vec2(10.0, 10.0), Vec2(-12.0, 12.0), Vec2(12.0, -14.0))
            }
        return spots[n % spots.size]
    }

    private fun quantityFor(dim: constructit.units.Dimension) =
        when (dim) {
            constructit.units.Dimension.ANGLE -> 30.0.deg
            constructit.units.Dimension.LENGTH -> 4.0.mm
            else -> constructit.units.Quantity.number(2.0)
        }
}
