package constructit

import constructit.core.Evaluator
import constructit.core.PointValue
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.dsl.valueOf
import constructit.editor.Document
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.Geom3
import constructit.geom.Vec2
import constructit.units.mm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The reported defect (OP-14): *"free points are normally rendered blue to distinguish them from derived
 * points. However, if you e.g. have a polygon extruded, then all points on the corners are rendered in green
 * making the free one undistinguishable from the others"* — diagnosed by the user as *"the outline points are
 * on top of the original points"*.
 *
 * A traced boundary hands over at the point the pieces already share, and must therefore publish **no second
 * marker** there. What it must go on publishing is a joint that is genuinely new geometry — a fillet's
 * tangency — which is the other half of every test here.
 */
class OutlineMarkerTest {
    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerMove(s)
        pointerDown(s)
        pointerUp(s)
    }

    private fun Editor.drag(
        from: Vec2,
        to: Vec2,
    ) {
        setTool(Tools.SELECT)
        pointerDown(camera.worldToScreen(from))
        pointerMove(camera.worldToScreen(to))
        pointerUp(camera.worldToScreen(to))
    }

    /** The user's drawing: four segments whose corner clicks reused the endpoints, then traced. */
    private fun tracedRectangle(): Editor {
        val ed = Editor()
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(40.0, 0.0))
        ed.click(Vec2(40.0, 0.0))
        ed.click(Vec2(40.0, 30.0))
        ed.click(Vec2(40.0, 30.0))
        ed.click(Vec2(0.0, 30.0))
        ed.click(Vec2(0.0, 30.0))
        ed.click(Vec2(0.0, 0.0))
        ed.setTool(Tools.OUTLINE)
        ed.click(Vec2(20.0, 0.0))
        ed.click(Vec2(40.0, 15.0))
        ed.click(Vec2(20.0, 30.0))
        ed.click(Vec2(0.0, 15.0))
        ed.key("Enter")
        return ed
    }

    private fun posOf(
        el: Element,
        ev: Evaluator,
    ): Vec2? = (ev.valueOf(el.ref) as? PointValue)?.p

    /** Every visible point marker of [kind], as positions. */
    private fun visible(
        doc: Document,
        kind: ElementKind,
    ): List<Vec2> {
        val ev = Evaluator()
        return doc.elements.filter { it.kind == kind && it.visible }.mapNotNull { posOf(it, ev) }
    }

    /** (a) No green marker stands on a blue one — and the four free corners are still there. */
    @Test
    fun aTracedCornerCarriesNoSecondMarker() {
        val ed = tracedRectangle()
        val free = visible(ed.doc, ElementKind.POINT)
        assertEquals(4, free.size, "the four free corners are still drawn: ${ed.statusHint}")
        val derived = visible(ed.doc, ElementKind.DERIVED_POINT)
        for (p in derived) {
            assertTrue(
                free.none { (it - p).length() < 1e-6 },
                "a visible derived point sits on a free point at $p — the defect: free=$free derived=$derived",
            )
        }
        // the markers were *published* and then hidden by construction: the element list — and therefore
        // every `-> eN` name the file gives — is exactly what it was before the fix
        val markers = ed.doc.elements.filter { it.kind == ElementKind.DERIVED_POINT }
        assertEquals(8, markers.size, "both traced loops still publish their four joints")
        assertTrue(markers.none { it.visible }, "…and every one of them is hidden, being a duplicate")
        // and they cannot be shown back into the defect
        assertEquals(0, ed.doc.setElementsVisible(markers, true), "a duplicate marker refuses to be shown")
    }

    /** (b) The free corner is still the drawing's degree of freedom: dragging it moves the construction. */
    @Test
    fun theFreeCornerStillDragsTheGeometry() {
        val ed = tracedRectangle()
        val ev0 = Evaluator()
        val corner = ed.doc.elements.first { it.kind == ElementKind.POINT && posOf(it, ev0) == Vec2(40.0, 30.0) }
        assertTrue(corner.visible, "the corner is grabbable because it is drawn")
        ed.drag(Vec2(40.0, 30.0), Vec2(55.0, 42.0))
        val ev = Evaluator()
        val moved = posOf(corner, ev)
        assertClose(moved!!.x, 55.0, tol = 1e-6, msg = "the free point took the drag")
        assertClose(moved.y, 42.0, tol = 1e-6)
        // the hidden marker rides along with it — it is an accessor on the segment, not a frozen literal
        val marker = ed.doc.elements.first { it.kind == ElementKind.DERIVED_POINT && posOf(it, ev)!!.x > 50.0 }
        assertClose(posOf(marker, ev)!!.y, 42.0, tol = 1e-6, msg = "the joint followed the corner")
    }

    /** (c) The traced boundary is still a boundary: it extrudes to a watertight solid. */
    @Test
    fun theTracedOutlineStillExtrudes() {
        val ed = tracedRectangle()
        ed.activeScalar = ed.doc.newParameter("h", 25.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(20.0, 0.0))
        val solid = ed.doc.elements.last { it.kind == ElementKind.SOLID }

        @Suppress("UNCHECKED_CAST")
        val mesh = Evaluator().solid(solid.ref as SolidRef).mesh
        assertManifold(mesh, "traced rectangle extruded")
        assertClose(Geom3.volume(mesh), 40.0 * 30.0 * 25.0, tol = 1e-6, msg = "the whole rectangle, 25 deep")
    }

    /** (d) Nothing about the file moved: same bytes, and the reload hides the same markers. */
    @Test
    fun theTraceRoundTripsByteEqual() {
        val ed = tracedRectangle()
        val once = DocumentFormat.save(ed.doc)
        val twice = DocumentFormat.save(DocumentFormat.load(once))
        assertEquals(once, twice, "save -> load -> save is byte-equal")
        val re = DocumentFormat.load(once)
        assertEquals(
            ed.doc.elements.size,
            re.elements.size,
            "the replay creates exactly the elements the script declares",
        )
        assertTrue(
            re.elements.filter { it.kind == ElementKind.DERIVED_POINT }.none { it.visible },
            "replay hides them again — the fact is synthetic, not stored",
        )
        assertEquals(4, visible(re, ElementKind.POINT).size, "and the free corners come back blue")
    }

    /** (e) A fillet's tangency is new geometry, so it keeps its visible marker — and only it does. */
    @Test
    fun aFilletsTangencyKeepsItsVisiblePoint() {
        val ed = Editor()
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(40.0, 0.0))
        ed.click(Vec2(40.0, 0.0))
        ed.click(Vec2(40.0, 30.0))
        ed.click(Vec2(40.0, 30.0))
        ed.click(Vec2(0.0, 30.0))
        ed.click(Vec2(0.0, 30.0))
        ed.click(Vec2(0.0, 0.0))
        ed.activeScalar = ed.doc.newParameter("r", 10.0.mm)
        ed.setTool(Tools.FILLET)
        ed.click(Vec2(20.0, 0.0)) // the bottom leg
        ed.click(Vec2(40.0, 15.0)) // the right leg — the corner at (40,0) is rounded
        assertEquals(1, ed.doc.elements.count { it.kind == ElementKind.ARC }, "the fillet: ${ed.statusHint}")

        ed.setTool(Tools.OUTLINE)
        ed.click(Vec2(10.0, 0.0)) // the bottom, short of the fillet
        ed.click(Vec2(37.071, 2.929)) // the fillet arc — the follow goes round the remaining three sides
        assertEquals(1, ed.doc.elements.count { it.kind == ElementKind.OUTLINE }, "traced: ${ed.statusHint}")

        val ev = Evaluator()
        val shown = visible(ed.doc, ElementKind.DERIVED_POINT)
        // the two tangencies, where nothing was marked before: (30,0) on the bottom and (40,10) on the right
        assertTrue(shown.any { (it - Vec2(30.0, 0.0)).length() < 1e-6 }, "the bottom tangency is drawn: $shown")
        assertTrue(shown.any { (it - Vec2(40.0, 10.0)).length() < 1e-6 }, "the right tangency is drawn: $shown")
        // …and the three plain corners, where a free point already stands, are not marked twice
        val free = visible(ed.doc, ElementKind.POINT)
        for (p in shown) {
            assertTrue(free.none { (it - p).length() < 1e-6 }, "no marker doubles a free corner at $p")
        }
        assertTrue(free.any { (it - Vec2(40.0, 0.0)).length() < 1e-6 }, "the rounded corner's own free point stays: $free")
        val once = DocumentFormat.save(ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "the filleted trace round-trips")
        val re = DocumentFormat.load(once)
        assertEquals(
            2,
            visible(re, ElementKind.DERIVED_POINT).size,
            "and the reload draws the two tangencies, no more and no fewer",
        )
    }
}
