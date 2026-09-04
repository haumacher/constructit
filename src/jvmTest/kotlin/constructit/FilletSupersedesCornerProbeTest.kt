package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.PointValue
import constructit.core.ScalarValue
import constructit.core.SolidValue
import constructit.dsl.valueOf
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.Geom3
import constructit.geom.Vec2
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **Orchestrator's probe of the fillet that supersedes its corner (GitHub #25/#29)**, composed with what the
 * delivery was not written against: the session's own ortho re-anchoring (#23), the version-3 file's load notes
 * and its version-4 re-save, a single 3D pick running *round a rounded ortho corner*, and the undo stack.
 */
class FilletSupersedesCornerProbeTest {
    private val issue25 =
        """
constructit 3
orthostart -73.625,28.875 -> e1
orthovertex -73.625,84.125 -> e2,e3
orthovertex 67.125,84.125 -> e4,e5
orthovertex 67.125,56.125 -> e6,e7
orthovertex -37.375,56.125 -> e8,e9
orthovertex -37.375,28.875 -> e10,e11
orthoclose -> e12
param "r" = 5mm
tool fillet els=e3,e5 clicks=-73.625,71.125;-64.875,84.125 scalar="r" signs=-1;1 -> e13
tool fillet els=e9,e11 clicks=-28.875,56.125;-37.625,46.625 scalar="r" signs=-1;1 -> e14
param "h" = 18mm
tool extrude els=e12 clicks=-57.375,28.625 scalar="h" -> e15
""".trimStart()

    private fun load(text: String): Editor = Editor().also { it.replaceDocument(DocumentFormat.load(text)) }

    private fun el(
        ed: Editor,
        name: String,
    ): Element = assertNotNull(ed.doc.elements.firstOrNull { ed.doc.nameOf(it) == name }, "$name is in the drawing")

    private fun pos(el: Element): Vec2 = assertNotNull((Evaluator().valueOf(el.ref) as? PointValue)?.p, "the point has a value")

    private fun loopArea(ed: Editor): Double {
        val loop = ed.doc.orthoLoopOf(ed.doc.orthoPaths.single())
        val v = Evaluator().eval(ed.doc.cx.loopArea(loop).node)
        assertTrue(v is EvalResult.Ok, "the rounded loop closes: ${(v as? EvalResult.Invalid)?.reason}")
        return ((v as EvalResult.Ok).value as ScalarValue).q.base
    }

    private fun polygonArea(ed: Editor): Double {
        val c = listOf("e1", "e2", "e4", "e6", "e8", "e10").map { pos(el(ed, it)) }
        var twice = 0.0
        for (i in c.indices) {
            val a = c[i]
            val b = c[(i + 1) % c.size]
            twice += a.x * b.y - b.x * a.y
        }
        return abs(twice) / 2
    }

    private fun solid(ed: Editor): Element = ed.doc.elements.last { it.kind == ElementKind.SOLID }

    private fun volume(el: Element): Double = Geom3.volume(((Evaluator().eval(el.ref.node) as EvalResult.Ok).value as SolidValue).solid.mesh)

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
        pointerMove(camera.worldToScreen(from + (to - from) * 0.5))
        pointerMove(camera.worldToScreen(to))
        pointerUp(camera.worldToScreen(to))
    }

    /** The reporter's version-3 file says what it now means, once; its version-4 re-save says nothing more and means the same. */
    @Test
    fun theOldFileIsToldWhatItNowMeansExactlyOnce() {
        val doc3 = DocumentFormat.load(issue25)
        val notes = doc3.loadNotes.joinToString("\n")
        assertTrue("e13" in notes && "e14" in notes, "both roundings are named on load: $notes")
        val saved = DocumentFormat.save(doc3)
        assertTrue(saved.lines().first().trim() == "constructit 4", "re-saved at the current version: ${saved.lines().first()}")
        val doc4 = DocumentFormat.load(saved)
        assertTrue(doc4.loadNotes.isEmpty(), "a current file has nothing to be told: ${doc4.loadNotes}")
        assertEquals(saved, DocumentFormat.save(doc4), "and is a fixed point")
        val a = Editor().also { it.replaceDocument(doc3) }
        val b = Editor().also { it.replaceDocument(doc4) }
        assertClose(volume(solid(b)), volume(solid(a)), tol = 1e-9, msg = "the same body either way")
        assertClose(volume(solid(a)) / (loopArea(a) * 18.0), 1.0, tol = 2e-3, msg = "the prism over the rounded loop, within the arcs' chords")
        assertManifold(((Evaluator().eval(solid(a).ref.node) as EvalResult.Ok).value as SolidValue).solid.mesh, "the rounded prism")
    }

    /** Session 79's own re-anchoring on the rounded path: the closing leg holds, the corner stays round, the file round-trips. */
    @Test
    fun aRoundedPathCanStillBeReAnchoredAndDragged() {
        val ed = load(issue25)
        ed.setTool(Tools.MAKE_RELATIVE)
        ed.click(pos(el(ed, "e10")))
        ed.click(pos(el(ed, "e1")))
        assertTrue("now follows" in ed.statusHint, "e10 anchored to e1: ${ed.statusHint}")
        val closing = (pos(el(ed, "e10")) - pos(el(ed, "e1"))).length()
        // drag the rounded leg e3 (x = -73.625) sideways by 10: the bottom follows, the corner stays round
        ed.drag(Vec2(-73.625, 50.0), Vec2(-63.625, 50.0))
        assertClose(pos(el(ed, "e1")).x, -63.625, tol = 1e-6, msg = "the drag happened")
        assertClose((pos(el(ed, "e10")) - pos(el(ed, "e1"))).length(), closing, tol = 1e-6, msg = "the closing leg keeps its length")
        // one convex and one reflex corner rounded by the same r: the slivers cancel and the loop's area is its polygon's
        assertClose(loopArea(ed), polygonArea(ed), tol = 1e-9, msg = "the loop is still the rounded one, now over the moved polygon")
        assertClose(volume(solid(ed)) / (loopArea(ed) * 18.0), 1.0, tol = 2e-3, msg = "and the prism followed")
        val saved = DocumentFormat.save(ed.doc)
        assertEquals(saved, DocumentFormat.save(DocumentFormat.load(saved)), "rounding + anchoring round-trip together")
    }

    /** A single 3D pick on a leg's rim runs round the path's own rounded corner: leg, arc, leg — three edges. */
    @Test
    fun aSinglePickRunsRoundTheRoundedOrthoCorner() {
        val ed = load(issue25)
        ed.activeScalar = ed.doc.scalars.first { it.name == "r" }
        val before = volume(solid(ed))
        ed.setTool(Tools.BLEND_EDGE)
        // the top rim over leg e3 (x = -73.625), well clear of both of its ends
        ed.click(Vec2(-73.625, 50.0))
        assertEquals(2, ed.doc.elements.count { it.kind == ElementKind.SOLID }, "the blend was made: ${ed.statusHint}")
        assertTrue("(3 edges)" in ed.statusHint, "leg, the corner's own arc, leg: ${ed.statusHint}")
        val body = ((Evaluator().eval(solid(ed).ref.node) as EvalResult.Ok).value as SolidValue).solid
        assertManifold(body.mesh, "the rasped rounded corner")
        assertTrue(Geom3.volume(body.mesh) < before, "a convex run loses material")
        val saved = DocumentFormat.save(ed.doc)
        assertEquals(saved, DocumentFormat.save(DocumentFormat.load(saved)), "the run's step round-trips")
    }

    /** Undo peels the roundings back to the sharp loop; redo rounds them again. */
    @Test
    fun undoAndRedoOfTheRoundingsOnTheLoop() {
        val ed = load(issue25.lines().filter { !it.startsWith("param \"h\"") && !it.startsWith("tool extrude") }.joinToString("\n") + "\n")
        val rounded = loopArea(ed)
        // round a third corner by gesture, then undo it
        ed.activeScalar = ed.doc.scalars.first { it.name == "r" }
        ed.setTool(Tools.FILLET)
        ed.click(Vec2(-20.0, 84.125))
        ed.click(Vec2(67.125, 70.0))
        assertTrue(ed.doc.orthoPaths.single().corners.size >= 3 || "e15" in ed.statusHint, "a third corner rounded: ${ed.statusHint}")
        val threeRounded = loopArea(ed)
        assertClose(threeRounded, rounded - (1 - Math.PI / 4) * 25.0, tol = 1e-9, msg = "a convex corner loses its sliver")
        assertTrue(ed.undo(), "undo the third rounding")
        assertClose(loopArea(ed), rounded, tol = 1e-9, msg = "back to two")
        assertTrue(ed.redo(), "redo it")
        assertClose(loopArea(ed), threeRounded, tol = 1e-9, msg = "three again")
    }
}
