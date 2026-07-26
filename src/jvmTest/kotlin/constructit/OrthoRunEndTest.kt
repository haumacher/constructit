package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.PointValue
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.SvgDrawTarget
import constructit.editor.Tools
import constructit.geom.Vec2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * **The end of an ortho run has to be unmissable, and the next click unambiguous.**
 *
 * Reported as "the ending did not snap and did not finish the path" while drawing a T-web (a closed
 * rectangle plus a middle run across it). Three faults, all on the same gesture:
 *
 * 1. the second end of the middle run could not attach at all — its x already belonged to the junction at
 *    its *first* end, so a second junction was one DOF too many and the attach was refused **in silence**:
 *    the run neither joined nor finished, which is exactly what the report says. It is now a *determined*
 *    meeting point (OP-20, `bindCornerToDeterminedMeeting`);
 * 2. a run that does finish by reaching geometry said so in one quiet status line, so an intermediate click
 *    that landed on geometry stopped the drawing unnoticed, and the next click read as "nothing happened".
 *    The terminus is now **marked on the canvas** and the words say the run is over;
 * 3. clicking that end afterwards reported "extending this path" — the message for continuing a *dangling*
 *    end. The handler was right about the graph and the graph was wrong: the end was still dangling because
 *    of (1). With the attach made, the same click starts a **branch**, as the design says it must.
 */
class OrthoRunEndTest {
    /** The reported drawing: a closed rectangle with a middle run attached at both ends. */
    private val tWeb =
        """
constructit 1
orthostart -63.5,13.75 -> e1
orthovertex -63.5,73 -> e2,e3
orthovertex 53.5,73 -> e4,e5
orthovertex 53.5,13.75 -> e6,e7
orthoclose -> e8
orthostart 15.75,73 -> e9
attachortho e9 e5
orthovertex 15.75,13.75 -> e10,e11
attachortho e10 e8
""".trimStart()

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

    private fun Editor.svg(): String = SvgDrawTarget().also { render(it) }.svg()

    /** Hover, which is what puts the rubber band back while a run is still being drawn. */
    private fun Editor.hover(world: Vec2) = pointerMove(camera.worldToScreen(world))

    private fun endOf(
        ed: Editor,
        path: Int,
    ): Vec2 =
        ed.doc.orthoPaths[path].vertices.last().let { v ->
            ((Evaluator().eval(v.ref.node) as EvalResult.Ok).value as PointValue).p
        }

    /** The terminus mark — the cue has to reach the drawing, not only the status line. */
    private val terminalMark = "stroke=\"#17becf\""

    /**
     * The rubber band: a *stroked* polyline in the preview colour (an ordinary point is a filled dot in the
     * same colour, hence the attribute). Its presence means "still drawing", so a terminal click leaves none.
     */
    private val rubberBand = "stroke=\"#ff7f0e\""

    /**
     * The reported sequence, click for click: a branch off the middle run that reaches the bottom wall. It
     * finishes there, says so in words that cannot be read as "carry on", marks the terminus, and drops the
     * rubber band.
     */
    @Test
    fun reachingALegFinishesTheRunLoudlyAndMarksTheTerminus() {
        val ed = Editor()
        ed.replaceDocument(DocumentFormat.load(tWeb))
        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(15.75, 40.0)) // start on the middle run's leg
        ed.click(Vec2(-20.0, 40.0))
        ed.hover(Vec2(-20.0, 30.0))
        assertTrue(ed.svg().contains(rubberBand), "a run in progress shows its rubber band")
        ed.click(Vec2(-20.0, 13.75)) // reaches the bottom wall
        assertTrue(ed.statusHint.contains("ends on"), "got: ${ed.statusHint}")
        assertTrue(ed.statusHint.contains("the run is finished"), "the ending must not be readable as a continuation: ${ed.statusHint}")
        val svg = ed.svg()
        assertTrue(svg.contains(terminalMark), "the terminus is marked on the canvas: $svg")
        assertFalse(svg.contains(rubberBand), "and the rubber band is gone the instant the run ends: $svg")
        Golden.check("ortho_terminal_cue", svg)
        // the band stays gone while the pointer wanders, and the mark stays put: the status line is
        // transient by design (a hover hands it to the snap label), so the mark is the durable half
        ed.hover(Vec2(0.0, 30.0))
        val after = ed.svg()
        assertFalse(after.contains(rubberBand), "nothing is being drawn any more: $after")
        assertTrue(after.contains(terminalMark), "and a hover does not erase the cue: $after")
    }

    /** The very next click is a *new* run, and the terminus mark goes with the action that follows it. */
    @Test
    fun theClickAfterATerminalEndStartsANewRun() {
        val ed = Editor()
        ed.replaceDocument(DocumentFormat.load(tWeb))
        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(15.75, 40.0))
        ed.click(Vec2(-20.0, 40.0))
        ed.click(Vec2(-20.0, 13.75))
        val paths = ed.doc.orthoPaths.size
        ed.click(Vec2(15.75, 13.75)) // the connected end of the middle run: a terminus, so this branches
        assertFalse(ed.statusHint.contains("extending"), "a connected end is a terminus, not a loose thread: ${ed.statusHint}")
        assertTrue(ed.statusHint.contains("starts on"), "got: ${ed.statusHint}")
        assertEquals(paths + 1, ed.doc.orthoPaths.size, "the branch is a run of its own")
        assertFalse(ed.svg().contains(terminalMark), "the cue lasts until the next action, and this was one")
    }

    /** The other half of the distinction: a **dangling** end still continues its path. */
    @Test
    fun aDanglingEndStillExtendsItsPath() {
        val ed = Editor()
        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(80.0, 1.0))
        ed.key("Escape") // stopped in the air, so the end is loose
        assertFalse(ed.svg().contains(terminalMark), "nothing was reached, so there is no terminus to mark")
        val paths = ed.doc.orthoPaths.size
        ed.click(Vec2(80.0, 0.0))
        assertTrue(ed.statusHint.contains("extending"), "a dangling end continues its path: ${ed.statusHint}")
        assertEquals(paths, ed.doc.orthoPaths.size, "extending adds no path")
    }

    /**
     * Fault (1): the run whose *first* end is already attached must be able to attach its second end too —
     * and stay attached, which is the whole point of a connection rather than a coincidence of coordinates.
     */
    @Test
    fun theSecondEndOfARunAttachesAndRidesTheLegItReached() {
        val ed = Editor()
        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(-63.5, 13.75))
        ed.click(Vec2(-63.5, 73.0))
        ed.click(Vec2(53.5, 73.0))
        ed.click(Vec2(53.5, 13.75))
        ed.click(Vec2(-63.5, 13.75)) // close the rectangle
        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(15.75, 73.0)) // start on the top wall
        ed.click(Vec2(15.75, 13.75)) // and reach the bottom one
        assertTrue(ed.statusHint.contains("the run is finished"), "reaching the bottom wall ends the run: ${ed.statusHint}")
        assertEquals(2, ed.doc.orthoPaths.size)
        // the connection is real: drag the bottom wall and the middle run's end goes with it
        ed.drag(Vec2(-20.0, 13.75), Vec2(-20.0, -10.0))
        assertClose(endOf(ed, 1).y, -10.0, 1e-6, "the attached end must ride the leg it reached")
        assertClose(endOf(ed, 1).x, 15.75, 1e-6, "and keep its own leg vertical")
        // and it survives the file, which is the test that it is an ordinary construction
        val saved = DocumentFormat.save(ed.doc)
        val again = Editor()
        again.replaceDocument(DocumentFormat.load(saved))
        assertEquals(saved, DocumentFormat.save(again.doc), "the attach replays verbatim")
        assertClose(endOf(again, 1).y, -10.0, 1e-6, "and is still attached after a reload")
    }

    /**
     * A connection that genuinely cannot be made **says why**, on the drawing route as well: welding a
     * corner that has one coordinate left onto a *point* would have to pin both, and the honest advice is
     * to reach the leg through that point instead — which needs only the one coordinate.
     */
    @Test
    fun aRefusedConnectionWhileDrawingExplainsItself() {
        val ed = Editor()
        ed.replaceDocument(DocumentFormat.load(tWeb))
        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(30.0, 73.0)) // start on the top wall: both coordinates are the junction's
        ed.click(Vec2(30.0, 40.0)) // one leg down: its own y is the only freedom left
        ed.click(Vec2(53.5, 13.75)) // the rectangle's corner point — a weld that would pin both
        assertTrue(ed.statusHint.contains("can't join"), "got: ${ed.statusHint}")
        assertTrue(ed.statusHint.contains("already held by"), "the reason names what holds the coordinate: ${ed.statusHint}")
        assertTrue(ed.statusHint.contains("run continues"), "and says the run did not finish: ${ed.statusHint}")
        ed.hover(Vec2(45.0, 20.0))
        assertTrue(ed.svg().contains(rubberBand), "the band is the honest cue that drawing continues")
    }

    /**
     * The circular case (OP-20), on the drawing route: continuing a run *through* a junction that hangs off
     * it would weld the run onto a point derived from itself. Refused — and no longer in silence.
     */
    @Test
    fun aCircularConnectionWhileDrawingExplainsItself() {
        val ed = Editor()
        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(1.0, 100.0)) // run A, vertical, dangling at the top
        ed.key("Escape")
        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(0.0, 50.0)) // run B starts on A's leg — its start is derived from A
        ed.click(Vec2(60.0, 51.0))
        ed.key("Escape")
        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(0.0, 100.0)) // continue A from its dangling end
        assertTrue(ed.statusHint.contains("extending"), "got: ${ed.statusHint}")
        ed.click(Vec2(0.0, 50.0)) // straight through B's start: that point follows A
        assertTrue(ed.statusHint.contains("can't join"), "got: ${ed.statusHint}")
        assertTrue(ed.statusHint.contains("already follows"), "the reason is the cycle: ${ed.statusHint}")
    }

    /** A release whose magnet could not deliver says why too — the same words, from the same predicate. */
    @Test
    fun aRefusedDropExplainsItself() {
        val ed = Editor()
        ed.replaceDocument(DocumentFormat.load(tWeb))
        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(30.0, 73.0)) // a run starting on the top wall
        ed.click(Vec2(30.0, 40.0)) // whose dangling end has one coordinate of its own
        ed.key("Escape")
        ed.drag(Vec2(30.0, 40.0), Vec2(53.5, 13.75)) // dropped on a point, which would pin both
        assertTrue(ed.statusHint.contains("Can't join"), "a release must not be silent: ${ed.statusHint}")
        assertTrue(ed.statusHint.contains("already held by"), "and it names the reason: ${ed.statusHint}")
    }
}
