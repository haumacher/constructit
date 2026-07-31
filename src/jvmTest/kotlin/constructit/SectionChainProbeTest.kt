package constructit

import constructit.core.Evaluator
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.editor.Document
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.Feature3
import constructit.geom.Geom3
import constructit.geom.Vec2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Probe: **two solids chained through a section input.** A pyramid is cut by a datum at half height; one
 * corner of that section becomes the APEX of a second solid built on the datum — solid → section → corner →
 * solid. Dragging the first pyramid's apex must re-derive the corner and carry the second solid with it,
 * exactly and without rebuilding anything, and the whole chain must replay byte-equal.
 */
class SectionChainProbeTest {
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

    private fun Editor.type(digits: String) {
        for (c in digits) key(c.toString())
        key("Enter")
    }

    private fun Editor.solids(): List<Element> = doc.elements.filter { it.kind == ElementKind.SOLID }

    @Suppress("UNCHECKED_CAST")
    private fun solidOf(el: Element) = Evaluator().solid(el.ref as SolidRef)

    private fun apexOf(el: Element) =
        assertNotNull(
            (solidOf(el).feature as Feature3.Loft).sections.filterIsInstance<constructit.geom.LoftSection.Apex>().single().at,
            "an apex",
        )

    @Test
    fun aSectionCornerCarriesASecondSolidAndFollowsTheFirstApex() {
        // the acceptance pyramid
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 100.0))
        ed.setTool(Tools.EXTRUDE_TO_POINT)
        ed.type("90")
        ed.click(Vec2(30.0, 0.0))
        ed.click(Vec2(50.0, 50.0))
        val pyramid = ed.solids().single()

        // the datum at half height: the section is the exact 50x50 square with a corner at (25,25)
        ed.setTool(Tools.SKETCH_PLANE)
        ed.type("0")
        ed.type("45")
        ed.click(Vec2(30.0, 0.0))
        assertTrue(ed.activeSpace.isDatum, "on the datum: ${ed.statusHint}")

        // a second solid drawn on the datum, its apex CLICKED ON the section corner — the chain
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(30.0, 30.0))
        ed.click(Vec2(70.0, 70.0))
        ed.setTool(Tools.EXTRUDE_TO_POINT)
        ed.type("20")
        ed.click(Vec2(50.0, 30.0))
        ed.click(Vec2(25.0, 25.0))
        assertEquals(2, ed.solids().size, "the second solid was built: ${ed.statusHint}")
        val tip = ed.solids().last { it !== pyramid }
        assertManifold(solidOf(tip).mesh, "the chained solid")
        // 40 x 40 base, apex 20 above the datum: 1600 * 20 / 3
        assertClose(Geom3.volume(solidOf(tip).mesh), 32000.0 / 3.0, tol = 1e-6, msg = "volume: ${ed.statusHint}")
        val a0 = apexOf(tip)
        assertClose(a0.x, 25.0, tol = 1e-6, msg = "apex over the section corner")
        assertClose(a0.y, 25.0, tol = 1e-6, msg = "apex over the section corner")
        assertClose(a0.z, 65.0, tol = 1e-6, msg = "the datum at 45 plus the height 20")

        val once = DocumentFormat.save(ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "the chain replays byte-equal")

        // drag the FIRST pyramid's apex: the oblique section corner (0,0)->(60,50,90) at z=45 is (30,25)
        assertTrue(ed.setActiveSpace(Document.PLAN_SPACE))
        val elementsBefore = ed.doc.elements.size
        ed.drag(Vec2(50.0, 50.0), Vec2(60.0, 50.0))
        assertEquals(elementsBefore, ed.doc.elements.size, "one literal edit, nothing rebuilt (OP-21)")
        val a1 = apexOf(tip)
        assertClose(a1.x, 30.0, tol = 1e-6, msg = "the chained apex followed the oblique section")
        assertClose(a1.y, 25.0, tol = 1e-6, msg = "the chained apex followed the oblique section")
        assertClose(a1.z, 65.0, tol = 1e-6, msg = "still on the datum plus its own height")
        // Cavalieri: an oblique apex leaves the second solid's volume alone
        assertClose(Geom3.volume(solidOf(tip).mesh), 32000.0 / 3.0, tol = 1e-6, msg = "volume is apex-position-blind")
        assertManifold(solidOf(tip).mesh, "the chained solid, oblique")

        val moved = DocumentFormat.save(ed.doc)
        assertEquals(moved, DocumentFormat.save(DocumentFormat.load(moved)), "the moved chain replays byte-equal")

        // one undo unwinds the drag and the chain returns exactly
        assertTrue(ed.undo(), "undo the drag")
        val back = apexOf(ed.doc.elements.filter { it.kind == ElementKind.SOLID }.last())
        assertClose(back.x, 25.0, tol = 1e-6, msg = "the chain is back")
        assertClose(back.y, 25.0, tol = 1e-6, msg = "the chain is back")
    }
}
