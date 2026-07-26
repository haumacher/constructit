package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.PointValue
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.Geom3
import constructit.geom.Vec2
import constructit.units.mm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Probes over the issue-2/3/4 wave: a rectangle (now an ortho path) broken, jogged, and still
 * extruded; and the double-junction bend dragged diagonally with undo parity.
 */
class Issue24ProbeTest {
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

    /** A rectangle is a real ortho path now: break a side, pull the jog, and it still extrudes. */
    @Test
    fun aBrokenJoggedRectangleStillBoundsAnExtrudableArea() {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(60.0, 40.0))
        ed.setTool(Tools.BREAK_LEG)
        ed.click(Vec2(30.0, 40.0)) // break the top side
        ed.drag(Vec2(45.0, 40.0), Vec2(45.0, 52.0)) // pull the jog up: an L-bump on the roof
        assertTrue(ed.doc.orthoPaths.single().legs.size > 4, "the jog opened: ${ed.doc.orthoPaths.single().legs.size} legs")

        ed.activeScalar = ed.doc.newParameter("h", 10.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(10.0, 0.0)) // any leg of the (still closed) path
        val solid = ed.doc.elements.last { it.kind == ElementKind.SOLID }

        @Suppress("UNCHECKED_CAST")
        val mesh = Evaluator().solid(solid.ref as SolidRef).mesh
        assertManifold(mesh, "jogged rectangle extrusion")
        assertClose(Geom3.volume(mesh), (60.0 * 40.0 + 30.0 * 12.0) * 10.0, tol = 1.0, msg = "the 30-wide jogged half adds its area")
        val once = DocumentFormat.save(ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)))
    }

    /** The T-web bend moves BOTH axes in one diagonal drag now, and undo restores exactly. */
    @Test
    fun theDoubleJunctionBendTakesADiagonalDragAndUndoes() {
        val file =
            """
constructit 2
orthostart -36.5,77 -> e1
orthovertex 13.5,77 -> e2,e3
orthovertex 13.5,27 -> e4,e5
orthovertex -36.5,27 -> e6,e7
orthoclose -> e8
orthostart -36.5,46.75 -> e9
attachortho e9 e8
orthovertex -16.5,46.75 -> e10,e11
orthovertex -16.5,27 -> e12,e13
attachortho e12 e7
""".trimStart()
        val ed = Editor()
        ed.replaceDocument(DocumentFormat.load(file))
        val before = DocumentFormat.save(ed.doc)

        fun bend(): Vec2 =
            ed.doc.orthoPaths[1].vertices[1].let {
                ((Evaluator().eval(it.ref.node) as EvalResult.Ok).value as PointValue).p
            }
        assertClose(bend().x, -16.5)
        assertClose(bend().y, 46.75)

        // one diagonal drag: both coordinates must follow (each through its own junction)
        ed.drag(Vec2(-16.5, 46.75), Vec2(-6.5, 56.75))
        assertClose(bend().x, -6.5, msg = "x wrote through its junction")
        assertClose(bend().y, 56.75, msg = "y wrote through the other junction")

        assertTrue(ed.undo())
        assertEquals(before, DocumentFormat.save(ed.doc), "one drag, one undo, exact restore")
        assertClose(bend().x, -16.5)
        assertClose(bend().y, 46.75)
    }
}
