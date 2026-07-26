package constructit

import constructit.core.Evaluator
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.SvgDrawTarget
import constructit.editor.Tools
import constructit.geom.Geom3
import constructit.geom.Vec2
import constructit.units.mm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Probes composing placed ortho paths with consumers their implementation never saw: a dimension
 * measuring a turned path, and a solid extruded AFTER the wall was placed and turned. A captured
 * vertex is an ordinary point value behind an indirection, so everything downstream of it must
 * follow the frame with no rule of its own.
 */
class PlacedPathProbeTest {
    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerDown(s)
        pointerUp(s)
    }

    private fun Editor.marqueeAll() {
        setTool(Tools.SELECT)
        pointerDown(camera.worldToScreen(Vec2(-200.0, -200.0)))
        pointerMove(camera.worldToScreen(Vec2(300.0, 300.0)))
        pointerUp(camera.worldToScreen(Vec2(300.0, 300.0)))
    }

    private fun svg(ed: Editor): String {
        val t = SvgDrawTarget()
        ed.render(t)
        return t.svg()
    }

    /** A dimension across a placed path's vertices: the number survives any turn of the frame. */
    @Test
    fun aDimensionOnAPlacedPathIsRotationInvariant() {
        val ed = Editor()
        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 3.0))
        ed.finishPath()
        ed.setTool(Tools.DIM_LINEAR)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 0.0))
        ed.click(Vec2(50.0, 25.0)) // where the dimension line sits
        assertTrue(svg(ed).contains(">100 mm<"), "the leg measures 100 before placing")

        ed.marqueeAll()
        val g = assertNotNull(ed.groupSelection("dim"))
        assertTrue(ed.placeGroup(g), "got: ${ed.statusHint}")
        // reach the frame and turn it by typing the angle (OP-13)
        ed.setTool(Tools.SELECT)
        ed.click(Vec2(0.0, 0.0))
        val angleIdx = ed.selectionFields().indexOfFirst { it.label == "angle" }
        assertTrue(angleIdx >= 0, "frame fields: ${ed.selectionFields().map { it.label }}")
        assertTrue(ed.writeSelectionField(angleIdx, 30.0))

        assertTrue(svg(ed).contains(">100 mm<"), "a turn changes the drawing, never the measured length")
        val once = DocumentFormat.save(ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "dimension + placed path round-trips")
    }

    /** Extruding a wall AFTER it was placed and turned: the solid is the turned footprint, watertight. */
    @Test
    fun extrudingATurnedWallGivesTheTurnedSolid() {
        val ed = Editor()
        ed.activeScalar = ed.doc.newParameter("t", 10.0.mm)
        ed.setTool(Tools.WALL)
        ed.click(Vec2(20.0, 0.0))
        ed.click(Vec2(21.0, 100.0))
        ed.finishPath()
        ed.marqueeAll()
        val g = assertNotNull(ed.groupSelection("wall"))
        assertTrue(ed.placeGroup(g), "got: ${ed.statusHint}")
        ed.setTool(Tools.SELECT)
        ed.click(Vec2(20.0, 50.0))
        val angleIdx = ed.selectionFields().indexOfFirst { it.label == "angle" }
        assertTrue(angleIdx >= 0, "frame fields: ${ed.selectionFields().map { it.label }}")
        assertTrue(ed.writeSelectionField(angleIdx, 90.0))

        // the footprint is now horizontal in world; extrude it where it lies
        ed.activeScalar = ed.doc.newParameter("h", 30.0.mm)
        ed.setTool(Tools.EXTRUDE)
        // the wall ran +Y from (20,0); turned 90° it runs along X and its upper FACE line sits at y≈55 —
        // the AREA pick hit-tests the footprint's boundary, not its interior
        ed.click(Vec2(-9.5, 55.0))
        val solids = ed.doc.elements.filter { it.kind == ElementKind.SOLID }
        assertEquals(1, solids.size, "got: ${ed.statusHint}")

        @Suppress("UNCHECKED_CAST")
        val mesh = Evaluator().solid(solids[0].ref as SolidRef).mesh
        assertManifold(mesh, "turned wall extrusion")
        assertClose(Geom3.volume(mesh), 10.0 * 100.0 * 30.0, tol = 1e-3, msg = "same wall, turned: same volume")
        val b = assertNotNull(Geom3.bounds(mesh))
        assertTrue(b.second.x - b.first.x > 90.0, "the long side now runs along X, got $b")
        assertTrue(b.second.y - b.first.y < 20.0, "and the thickness along Y, got $b")
    }
}
