package constructit

import constructit.core.Evaluator
import constructit.core.PointValue
import constructit.dsl.valueOf
import constructit.editor.Appearance
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.Scene3
import constructit.editor.Tools
import constructit.exchange.ExportFormat
import constructit.exchange.Exports
import constructit.geom.Vec2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Probes on the issues-8/10 package, composing both with neighbours they never met: a **JT-imported body
 * wearing its file's colour into the construction 3D view** (the import's Tier-1 hand-off meeting the new
 * `Scene3` colour rule), and the **unlink lifecycle under drags and replay** — welded, moved together,
 * freed, moved apart, reloaded, undone.
 */
class UnlinkColorProbeTest {
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

    private fun Editor.drag(
        from: Vec2,
        to: Vec2,
    ) {
        setTool(Tools.SELECT)
        val a = camera.worldToScreen(from)
        val b = camera.worldToScreen(to)
        pointerDown(a)
        pointerMove(b)
        pointerUp(b)
    }

    private fun Editor.at(el: Element): Vec2 = assertNotNull((Evaluator().valueOf(el.ref) as? PointValue)?.p, "a point value")

    /** An imported body wears its file's colour in the construction 3D view; an undressed neighbour keeps the palette. */
    @Test
    fun anImportedDressedBodyCarriesItsColourIntoTheConstructionView() {
        val src = Editor()
        src.setTool(Tools.RECTANGLE)
        src.click(Vec2(0.0, 0.0))
        src.click(Vec2(40.0, 40.0))
        src.setTool(Tools.EXTRUDE)
        src.type("20")
        src.click(Vec2(20.0, 0.0))
        val part = src.doc.elements.filter { it.kind == ElementKind.SOLID }.last()
        src.setMaterial(part, Appearance("#2266aa", roughness = 0.5, metallic = 0.0))
        val jt = Exports.export(src.doc, "blau", ExportFormat.JT)
        assertTrue(jt.ok, jt.message)

        val ed = Editor()
        assertTrue(ed.importFile(jt.bytes!!, "blau.jt").ok, ed.statusHint)
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(100.0, 0.0))
        ed.click(Vec2(140.0, 40.0))
        ed.setTool(Tools.EXTRUDE)
        ed.type("20")
        ed.click(Vec2(120.0, 0.0))

        val solids = ed.doc.elements.filter { it.kind == ElementKind.SOLID && it.visible }
        val dressed = solids.first { ed.doc.assignedMaterial(it) != null }
        val plain = solids.last { ed.doc.assignedMaterial(it) == null }
        val scene = Scene3.extract(ed.doc)
        val dressedItem = assertNotNull(scene.solids.find { it.elementId == dressed.id }, "the imported body is in the scene")
        val plainItem = assertNotNull(scene.solids.find { it.elementId == plain.id }, "the constructed one too")

        // the colour that crossed two formats is the colour the view shades with — not the palette's pick
        assertEquals(assertNotNull(ed.doc.assignedMaterial(dressed)).color, dressedItem.color, "the file's colour, worn in the view")
        assertEquals(Scene3.colorFor(plain.id), plainItem.color, "the undressed body keeps its palette identity")
        assertTrue(dressedItem.color != plainItem.color, "the two are tellable apart")
    }

    /** Welded, moved together, freed, moved apart, reloaded, undone — the whole unlink lifecycle. */
    @Test
    fun theUnlinkLifecycleSurvivesDragsReplayAndUndo() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(30.0, 0.0))
        val pts = ed.doc.elements.filter { it.isPoint }
        val (a, b) = pts[0] to pts[1]
        ed.setTool(Tools.JOIN)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(30.0, 0.0))
        assertTrue(ed.doc.isWelded(b), ed.statusHint)

        // one dot, one DOF: dragging the merged point moves both
        ed.drag(Vec2(0.0, 0.0), Vec2(10.0, 10.0))
        assertClose(ed.at(a).x, 10.0, msg = "the master moved")
        assertClose(ed.at(b).x, 10.0, msg = "the alias followed — sharing the node is the join")

        // the selected member leaves the weld, right where it stands
        ed.selectElement(b)
        ed.setTool(Tools.UNLINK)
        assertFalse(ed.doc.isWelded(b), ed.statusHint)
        assertClose(ed.at(b).x, 10.0, msg = "nothing jumped on unlink")
        assertTrue(b.visible, "the freed point is back on screen")

        // and freedom is real: both stand at (10,10), the drag grabs whichever tops the pile — the point
        // is that exactly ONE of them moves and the other stays, which only a broken weld allows
        ed.drag(Vec2(10.0, 10.0), Vec2(25.0, 20.0))
        val ax = ed.at(a).x
        val bx = ed.at(b).x
        assertTrue(
            (ax == 25.0 && bx == 10.0) || (ax == 10.0 && bx == 25.0),
            "one moved, one stayed — they are two DOFs again: a=$ax b=$bx",
        )

        // the whole story replays byte-equal, and the reloaded drawing has the same two positions
        val once = DocumentFormat.save(ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "weld + drag + unlink + drag replays byte-equal")
        val re = DocumentFormat.load(once)
        val rePts = re.elements.filter { it.isPoint }
        val xs = rePts.map { assertNotNull((Evaluator().valueOf(it.ref) as? PointValue)?.p).x }.sorted()
        assertClose(xs[0], 10.0, msg = "the freed point, where it was left")
        assertClose(xs[1], 25.0, msg = "the master, where it went")

        // undo takes back the second drag, then the unlink itself: the weld is whole again
        ed.undo()
        ed.undo()
        assertTrue(ed.doc.isWelded(ed.doc.elements.filter { it.isPoint }[1]), "undo restores the weld")
    }
}
