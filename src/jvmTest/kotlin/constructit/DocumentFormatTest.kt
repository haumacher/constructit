package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.PointValue
import constructit.dsl.PointRef
import constructit.dsl.point
import constructit.dsl.scalar
import constructit.editor.Document
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.Vec2
import constructit.units.mm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The save format is a construction script: replaying it must rebuild the drawing, including
 * everything synthetic (handles, paths, walls) that is deliberately not stored.
 *
 * The load-bearing assertion is `save -> load -> save` being byte-identical: it catches a step that
 * fails to replay, a literal that is not restated to its current value, and any drift in element
 * naming, all at once.
 */
class DocumentFormatTest {
    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerDown(s)
        pointerUp(s)
    }

    private fun Editor.drag(
        from: Vec2,
        to: Vec2,
    ) {
        pointerDown(camera.worldToScreen(from))
        pointerMove(camera.worldToScreen(to))
        pointerUp(camera.worldToScreen(to))
    }

    /** Every point in the document, in order — the geometric fingerprint of a drawing. */
    private fun points(doc: Document): List<Vec2> {
        val ev = Evaluator()
        return doc.elements.filter { it.isPoint }.mapNotNull {
            ((ev.eval(it.ref.node) as? EvalResult.Ok)?.value as? PointValue)?.p
        }
    }

    /** -0.0 and 0.0 are the same coordinate; the file writes the tidier one. */
    private fun fingerprint(doc: Document) = points(doc).map { "${if (it.x == 0.0) 0.0 else it.x},${if (it.y == 0.0) 0.0 else it.y}" }

    private fun assertRoundTrips(ed: Editor): Document {
        val once = DocumentFormat.save(ed.doc)
        val reloaded = DocumentFormat.load(once)
        val twice = DocumentFormat.save(reloaded)
        assertEquals(once, twice, "save -> load -> save must be identical")
        assertEquals(fingerprint(ed.doc), fingerprint(reloaded), "the reloaded drawing must have the same geometry")
        assertEquals(ed.doc.elements.map { it.kind }, reloaded.elements.map { it.kind }, "same element kinds")
        return reloaded
    }

    /**
     * A traced outline and a spline round-trip with no per-tool support in the format: the `tool` step
     * already carries `els=` and `clicks=`, and a repeating tool is just more of both (OP-14, OP-15).
     */
    @Test
    fun outlinesAndSplinesRoundTrip() {
        val ed = Editor()
        ed.setTool(Tools.LINE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(60.0, 0.0))
        ed.setTool(Tools.BEZIER)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(0.0, 40.0))
        ed.click(Vec2(60.0, 40.0))
        ed.click(Vec2(60.0, 0.0))
        ed.setTool(Tools.OUTLINE)
        ed.click(Vec2(30.0, 0.0))
        ed.click(Vec2(30.0, 30.0))
        ed.key("Enter")
        assertEquals(1, ed.doc.elements.count { it.kind == ElementKind.OUTLINE })

        val reloaded = assertRoundTrips(ed)
        assertEquals(1, reloaded.elements.count { it.kind == ElementKind.OUTLINE }, "the outline must come back")
        assertEquals(1, reloaded.elements.count { it.kind == ElementKind.BEZIER }, "so must the spline")
        val ev = Evaluator()
        val loop = reloaded.elements.first { it.kind == ElementKind.OUTLINE }
        assertTrue(ev.eval(loop.ref.node) is EvalResult.Ok, "and it must still be a closed boundary")
    }

    @Test
    fun pointsAndCurvesRoundTrip() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(-40.0, 0.0))
        ed.click(Vec2(40.0, 10.0))
        ed.setTool(Tools.LINE)
        ed.click(Vec2(-40.0, 0.0))
        ed.click(Vec2(40.0, 10.0))
        ed.setTool(Tools.CIRCLE)
        ed.click(Vec2(0.0, -60.0))
        ed.click(Vec2(25.0, -60.0))
        ed.setTool(Tools.MIDPOINT)
        ed.click(Vec2(-40.0, 0.0))
        ed.click(Vec2(40.0, 10.0))

        val text = DocumentFormat.save(ed.doc)
        assertTrue(text.startsWith(DocumentFormat.HEADER), "the file names its format")
        assertTrue(text.contains("tool line"), "a tool application is one step; got:\n$text")
        assertRoundTrips(ed)
    }

    @Test
    fun aDragIsSavedAsTheCurrentValueNotTheOriginalClick() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.setTool(Tools.SELECT)
        ed.drag(Vec2(0.0, 0.0), Vec2(70.0, -25.0))

        val text = DocumentFormat.save(ed.doc)
        assertTrue(text.contains("point 70,-25"), "the script carries the position as it is now; got:\n$text")
        val reloaded = assertRoundTrips(ed)
        assertClose(points(reloaded)[0].x, 70.0)
        assertClose(points(reloaded)[0].y, -25.0)
    }

    @Test
    fun aParameterKeepsItsEditedValue() {
        val ed = Editor()
        val t = ed.doc.newParameter("t", 100.0.mm)
        ed.doc.setParameter(t, 250.0.mm)
        ed.activeScalar = t
        ed.setTool(Tools.CIRCLE_R)
        ed.click(Vec2(0.0, 0.0))

        assertTrue(DocumentFormat.save(ed.doc).contains("param \"t\" = 250mm"))
        val reloaded = assertRoundTrips(ed)
        assertClose(Evaluator().scalar(reloaded.scalars.first { it.name == "t" }.ref).mm, 250.0)
    }

    @Test
    fun anOrthoPathRoundTripsWithItsSharedCoordinateStructure() {
        val ed = Editor()
        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(60.0, 3.0))
        ed.click(Vec2(58.0, 40.0))
        ed.click(Vec2(2.0, 40.0))
        ed.click(Vec2(0.0, 0.0)) // close the loop

        val reloaded = assertRoundTrips(ed)
        val path = reloaded.orthoPaths.single()
        assertTrue(path.closed)
        assertEquals(4, path.vertices.size)
        assertEquals(4, path.legs.size)

        // the rebuilt path edits like the original: dragging a leg moves both its ends and nothing else
        val ed2 = Editor(reloaded)
        ed2.setTool(Tools.SELECT)
        ed2.drag(Vec2(30.0, 0.0), Vec2(30.0, -20.0))
        val ev = Evaluator()
        val ys = path.vertices.map { ev.point(it.ref).y }
        assertClose(ys[0], -20.0)
        assertClose(ys[1], -20.0)
        assertClose(ys[2], 40.0)
        assertClose(ys[3], 40.0)
    }

    @Test
    fun anExtendedLegRoundTripsAtItsExtendedLength() {
        val ed = Editor()
        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(40.0, 2.0)) // a horizontal leg
        ed.click(Vec2(90.0, -1.0)) // straight on: extends that leg rather than adding a corner
        ed.finishPath()

        val text = DocumentFormat.save(ed.doc)
        assertEquals(1, text.lines().count { it.startsWith("orthovertex") }, "an extension is a value, not a step; got:\n$text")
        val reloaded = assertRoundTrips(ed)
        assertClose(Evaluator().point(reloaded.orthoPaths.single().vertices[1].ref).x, 90.0)
    }

    @Test
    fun aBrokenLegRoundTripsWithItsJogWhereverItWasPulled() {
        val ed = Editor()
        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 2.0))
        ed.finishPath()
        ed.setTool(Tools.BREAK_LEG)
        ed.click(Vec2(60.0, 1.0))
        // pull the jog open, so the saved script must carry *both* the split position and the offset
        ed.setTool(Tools.SELECT)
        ed.drag(Vec2(80.0, 0.0), Vec2(80.0, -25.0))

        val text = DocumentFormat.save(ed.doc)
        assertTrue(text.contains("orthobreak"), "got:\n$text")
        val reloaded = assertRoundTrips(ed)
        val path = reloaded.orthoPaths.single()
        assertEquals(4, path.vertices.size)
        val ev = Evaluator()
        assertClose(ev.point(path.vertices[1].ref).x, 60.0)
        assertClose(ev.point(path.vertices[2].ref).y, -25.0)
    }

    @Test
    fun aJoinedRunRoundTripsAsTheJoinedRun() {
        val ed = Editor()
        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(50.0, 2.0))
        ed.click(Vec2(48.0, -30.0))
        ed.click(Vec2(110.0, -28.0))
        ed.finishPath()
        ed.setTool(Tools.SELECT)
        ed.drag(Vec2(80.0, -30.0), Vec2(80.0, 0.0)) // flatten the jog -> joins on release

        val text = DocumentFormat.save(ed.doc)
        assertTrue(text.contains("orthojoin"), "got:\n$text")
        val reloaded = assertRoundTrips(ed)
        val path = reloaded.orthoPaths.single()
        assertEquals(1, path.legCount, "the reload is the joined run, not the jog it came from")
        assertEquals(2, path.vertices.size)
    }

    /**
     * A thick path and its interval features round-trip as the **description** they are (OP-21): the
     * carrier's own steps, a thickness, and one line per interval carrying which leg, how far along, how
     * wide and the two heights. No face geometry is stored, because none of it is stored anywhere.
     */
    @Test
    fun aThickPathWithAnOpeningRoundTrips() {
        val ed = Editor()
        ed.activeScalar = ed.doc.newParameter("t", 10.0.mm)
        ed.setTool(Tools.WALL)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 2.0))
        ed.finishPath()
        ed.activeScalar = ed.doc.newParameter("w", 20.0.mm)
        ed.setTool(Tools.OPENING)
        ed.click(Vec2(50.0, 0.0))
        // a value the user then typed: it must come back, which is why the step records the position
        // rather than the click that resolved it
        ed.doc.setParameter(ed.doc.scalars.first { it.name == "pos" }, 25.0.mm)

        val text = DocumentFormat.save(ed.doc)
        assertTrue(text.contains("opening e4 leg=0 pos=25mm width=\"w\" sill=0mm head=2100mm"), "got:\n$text")
        val reloaded = assertRoundTrips(ed)
        val tp = reloaded.thickPaths.single()
        val interval = tp.intervals.single()
        assertEquals(0, interval.legIndex)
        assertClose(Evaluator().scalar(interval.position).mm, 25.0)
        assertTrue(interval.width.node === reloaded.scalars.first { it.name == "w" }.ref.node, "the width stays shared")
        assertTrue(tp.carrier === reloaded.orthoPaths.single(), "and so is the link to its carrier")
    }

    @Test
    fun connectionsRoundTripAsConnectionsNotCoincidences() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(30.0, -60.0))
        ed.click(Vec2(30.0, 60.0))
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(30.0, -60.0))
        ed.click(Vec2(30.0, 60.0))
        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(29.0, 10.0)) // attaches the start onto the segment
        ed.click(Vec2(90.0, 12.0))
        ed.finishPath()

        val reloaded = assertRoundTrips(ed)
        val path = reloaded.orthoPaths.single()
        val start = reloaded.elements.first { it.ref === path.vertices[0].ref }
        // the connection survives as a junction owning the shared freedom, so both coordinates are
        // derived from it — and both remain settable through it (OP-20)
        val h = start.handle as constructit.editor.OrthoCornerHandle
        assertTrue(reloaded.junctionOf(h.xNode) != null, "reloaded as a junction, not a coincidence")
        assertTrue(start.handle!!.fields().filter { it.label in setOf("x", "y") }.all { it.writable })

        // and moving the segment still carries the path with it
        val ed2 = Editor(reloaded)
        ed2.setTool(Tools.SELECT)
        ed2.drag(Vec2(30.0, -60.0), Vec2(0.0, -60.0))
        assertTrue(kotlin.math.abs(Evaluator().point(path.vertices[0].ref).x - 30.0) > 1.0)
    }

    @Test
    fun anOnCurvePointRoundTripsWhereItWasSlidTo() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(-50.0, 0.0))
        ed.click(Vec2(50.0, 0.0))
        ed.setTool(Tools.LINE)
        ed.click(Vec2(-50.0, 0.0))
        ed.click(Vec2(50.0, 0.0))
        ed.setTool(Tools.POINT_ON_LINE)
        ed.click(Vec2(10.0, 0.0))
        ed.setTool(Tools.SELECT)
        ed.drag(Vec2(10.0, 0.0), Vec2(-30.0, 5.0)) // slide it along

        val reloaded = assertRoundTrips(ed)
        val slider = reloaded.elements.last { it.kind == ElementKind.ON_CURVE }
        assertClose(Evaluator().point(slider.ref as PointRef).x, -30.0)
    }

    @Test
    fun aScriptFromAnotherVersionIsRejectedRatherThanMisread() {
        val bad = "constructit 1\npoint 0,0 -> e1,e2\n"
        val failure = runCatching { DocumentFormat.load(bad) }.exceptionOrNull()
        assertTrue(failure is DocumentFormat.LoadError, "got: $failure")
        assertTrue(failure.message!!.contains("different version"), "got: ${failure.message}")

        val wrongHeader = runCatching { DocumentFormat.load("constructit 99\n") }.exceptionOrNull()
        assertTrue(wrongHeader is DocumentFormat.LoadError)
    }
}
