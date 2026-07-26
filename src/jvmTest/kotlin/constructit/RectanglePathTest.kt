package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.PointValue
import constructit.core.SegmentValue
import constructit.editor.Document
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.Vec2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **The rectangle is a closed ortho path** (GitHub issue #4).
 *
 * The report: *"the rectangle produced by the rectangle tool is almost non-editable — when dragging its free
 * points, they move only along one axis. Maybe a better approach is to produce the same result as the
 * ortho-path tool would create but more easily by just setting two points. This would also allow setting the
 * width and height precisely."*
 *
 * So the tool's *gesture* is unchanged — two diagonally opposite clicks — and what it records is exactly what
 * the ortho tool records: `orthostart`, three `orthovertex`, `orthoclose`. The file needs no new step kind and
 * cannot tell the two gestures apart, which is the point: everything a path can do arrives with it, and none
 * of it is written twice.
 *
 * The old build stays reachable for **replay only** (`Tools.RECTANGLE_V1`, still the id `rect`): a step already
 * written down means what it meant (OP-18), and the loader checks that it creates the six elements the script
 * declares.
 */
class RectanglePathTest {
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

    private fun rect(
        a: Vec2 = Vec2(-20.0, -10.0),
        c: Vec2 = Vec2(30.0, 25.0),
    ): Editor {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(a)
        ed.click(c)
        return ed
    }

    private fun corners(doc: Document): List<Vec2> =
        doc.orthoPaths.single().vertices.map {
            ((Evaluator().eval(it.ref.node) as EvalResult.Ok).value as PointValue).p
        }

    private fun assertRectangle(
        doc: Document,
        x0: Double,
        y0: Double,
        x1: Double,
        y1: Double,
    ) {
        val got = corners(doc)
        assertEquals(4, got.size, "four corners; got $got")
        // sorted and compared with a tolerance: a click travels through the camera, so an exact 0 comes back
        // as -0.0 often enough that set equality on Double would be testing the wrong thing
        val xs = got.map { it.x }.sorted()
        val ys = got.map { it.y }.sorted()
        for ((i, want) in listOf(x0, x0, x1, x1).withIndex()) assertClose(xs[i], want, 1e-9, "x $i of $got")
        for ((i, want) in listOf(y0, y0, y1, y1).withIndex()) assertClose(ys[i], want, 1e-9, "y $i of $got")
        val ev = Evaluator()
        for (leg in doc.orthoPaths.single().legs) {
            val s = ((ev.eval(leg.ref.node) as EvalResult.Ok).value as SegmentValue).seg
            assertTrue(
                kotlin.math.abs(s.a.x - s.b.x) < 1e-9 || kotlin.math.abs(s.a.y - s.b.y) < 1e-9,
                "side $s is neither horizontal nor vertical",
            )
        }
    }

    /** Two clicks, a closed four-leg path — and the steps of one drawn by hand. */
    @Test
    fun twoClicksMakeAClosedFourLegOrthoPath() {
        val ed = rect()
        val path = ed.doc.orthoPaths.single()
        assertTrue(path.closed)
        assertEquals(4, path.vertices.size)
        assertEquals(4, path.legs.size)
        assertEquals(listOf(0, 1, 0, 1), path.legAxes.toList(), "the legs alternate, starting horizontal")
        assertRectangle(ed.doc, -20.0, -10.0, 30.0, 25.0)
        assertEquals(0, ed.doc.freePoints.size, "no stray points beside the path's own corners")

        // the file reads as an ortho path, with no `tool` step and no new kind
        val saved = DocumentFormat.save(ed.doc)
        assertEquals(
            listOf("orthostart", "orthovertex", "orthovertex", "orthovertex", "orthoclose"),
            saved.lineSequence().drop(1).filter { it.isNotBlank() }.map { it.substringBefore(' ') }.toList(),
            saved,
        )
        assertFalse(saved.contains("tool rect"), saved)
    }

    /** A degenerate second click builds nothing rather than half a rectangle. */
    @Test
    fun aSecondClickOnTheSameRowOrColumnBuildsNothing() {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(40.0, 0.0))
        assertEquals(0, ed.doc.orthoPaths.size, "no rectangle there")
        assertEquals("", DocumentFormat.save(ed.doc).lineSequence().drop(1).joinToString("").trim(), "and no steps")
    }

    /**
     * Width and height, typed — the second half of the report. Each side's length is a field of its own leg
     * (OP-13), so this needed no code: the leg handle already had it.
     */
    @Test
    fun theWidthAndHeightAreTypedOnTheSides() {
        val ed = rect(Vec2(0.0, 0.0), Vec2(40.0, 20.0))
        val path = ed.doc.orthoPaths.single()

        fun write(
            leg: Int,
            label: String,
            mm: Double,
        ) {
            ed.selectElement(path.legs[leg])
            val i = ed.selectionFields().indexOfFirst { it.label == label }
            assertTrue(i >= 0, "$label on leg $leg; got ${ed.selectionFields().map { it.label }}")
            assertTrue(ed.writeSelectionField(i, mm), "writing $label")
        }

        write(0, "length (move end)", 64.0) // the bottom side: width
        write(1, "length (move end)", 33.0) // the right side: height
        assertRectangle(ed.doc, 0.0, 0.0, 64.0, 33.0)
    }

    /** A whole side drags across itself — the editing the old rectangle had no way to offer at all. */
    @Test
    fun aSideDragsAcrossItselfAndACornerDragsBothWays() {
        val ed = rect(Vec2(0.0, 0.0), Vec2(40.0, 20.0))
        ed.drag(Vec2(20.0, 20.0), Vec2(20.0, 30.0)) // the top side up
        assertRectangle(ed.doc, 0.0, 0.0, 40.0, 30.0)
        ed.drag(Vec2(0.0, 0.0), Vec2(-7.0, -5.0)) // a corner, diagonally — both axes at once
        assertRectangle(ed.doc, -7.0, -5.0, 40.0, 30.0)
    }

    /** A corner clicked on existing geometry joins it, exactly as an ortho-path click does. */
    @Test
    fun aCornerClickedOnAPointJoinsIt() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(50.0, 30.0))
        val anchor = ed.doc.elements.single { it.kind == ElementKind.POINT }
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(50.0, 30.0))
        assertTrue(DocumentFormat.save(ed.doc).contains("weldortho"), DocumentFormat.save(ed.doc))

        // dragging the anchor now reshapes the rectangle, which is what "joined" means
        ed.drag(Vec2(50.0, 30.0), Vec2(70.0, 45.0))
        assertRectangle(ed.doc, 0.0, 0.0, 70.0, 45.0)
        assertTrue(anchor.draggable, "and the anchor is still the free point it was")
    }

    /** Save, load, save — the corner positions are restated, so every later edit survives (OP-18). */
    @Test
    fun aDraggedRectangleRoundTrips() {
        val ed = rect(Vec2(-15.0, -5.0), Vec2(25.0, 35.0))
        ed.drag(Vec2(5.0, 35.0), Vec2(5.0, 41.0)) // a side
        ed.drag(Vec2(-15.0, -5.0), Vec2(-18.0, -9.0)) // a corner
        val once = DocumentFormat.save(ed.doc)
        val reloaded = DocumentFormat.load(once)
        assertEquals(once, DocumentFormat.save(reloaded), "save -> load -> save")
        assertRectangle(reloaded, -18.0, -9.0, 25.0, 41.0)
        assertTrue(reloaded.orthoPaths.single().closed, "and it comes back closed")
    }

    /** It bounds an area, so it still extrudes with one pick (OP-14/OP-17). */
    @Test
    fun aRectangleBoundsAnAreaWithoutTracingAnOutline() {
        val ed = rect()
        val side = ed.doc.orthoPaths.single().legs[0]
        assertEquals(4, ed.doc.boundaryPiecesOf(side)?.size)
        assertTrue(ed.doc.areaPickFilter(Evaluator())(side))
    }

    /**
     * **An old file still replays.** The v1 rectangle's `tool rect` step names two picked points and creates
     * six elements — two derived corners and four sides — and the loader's count check vouches for every one
     * of them. Nothing about it changed: the build is still registered, only not in the palette.
     */
    @Test
    fun anOldFormatRectangleFileStillLoads() {
        val old =
            """
constructit 1
point 0,0 -> e1
point 80,40 -> e2
tool rect pts=e1,e2 clicks=0,0;80,40 -> e3,e4,e5,e6,e7,e8
""".trimStart()
        val doc = DocumentFormat.load(old)
        assertEquals(0, doc.orthoPaths.size, "a v1 rectangle is four segments, not a path")
        assertEquals(4, doc.elements.count { it.kind == ElementKind.SEGMENT }, "four sides")
        assertEquals(2, doc.elements.count { it.kind == ElementKind.DERIVED_POINT }, "two derived corners")
        val ev = Evaluator()
        val ys =
            doc.elements.filter { it.kind == ElementKind.SEGMENT }
                .map { ((ev.eval(it.ref.node) as EvalResult.Ok).value as SegmentValue).seg }
        assertEquals(listOf(0.0, 40.0), ys.flatMap { listOf(it.a.y, it.b.y) }.distinct().sorted())
        assertEquals(listOf(0.0, 80.0), ys.flatMap { listOf(it.a.x, it.b.x) }.distinct().sorted())

        // and it round-trips as itself: the step is rewritten with the same tool id, so it never becomes a path
        val saved = DocumentFormat.save(doc)
        assertTrue(saved.contains("tool rect "), saved)
        assertEquals(saved, DocumentFormat.save(DocumentFormat.load(saved)), "save -> load -> save")

        // dragging a clicked corner still reshapes it, exactly as it always did
        val ed = Editor()
        ed.replaceDocument(doc)
        ed.drag(Vec2(80.0, 40.0), Vec2(100.0, 60.0))
        val after =
            doc.elements.filter { it.kind == ElementKind.SEGMENT }
                .map { ((Evaluator().eval(it.ref.node) as EvalResult.Ok).value as SegmentValue).seg }
        assertEquals(listOf(0.0, 100.0), after.flatMap { listOf(it.a.x, it.b.x) }.distinct().sorted())
    }

    /** The replay-only build is registered but never offered. */
    @Test
    fun theOldRectangleIsReachableForReplayAndNowhereElse() {
        assertNotNull(Tools.byId(Tools.RECTANGLE_V1), "replay must find it")
        assertTrue(Tools.all.none { it.id == Tools.RECTANGLE_V1 }, "the palette must not")
        assertEquals(Tools.RECTANGLE, Tools.byShortcut('R'), "R arms the rectangle that draws a path")
    }
}
