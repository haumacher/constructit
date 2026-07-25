package constructit

import constructit.core.Evaluator
import constructit.core.RegionValue
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.dsl.valueOf
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.Geom3
import constructit.geom.GeomMath
import constructit.geom.Vec2
import constructit.units.mm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Probes for the downward seam in compositions it was not written against: a scalar flowing from
 * one solid into another's feature, a section taken from a face-based storey, and a section area
 * fed to a transform array. Downward values are ordinary nodes; every consumer must take them.
 */
class SeamDownProbeTest {
    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerDown(s)
        pointerUp(s)
    }

    private fun Editor.wall(
        x: Double,
        len: Double,
    ) {
        activeScalar = doc.newParameter("t$x", 10.0.mm)
        setTool(Tools.WALL)
        click(Vec2(x, 0.0))
        click(Vec2(x + 1.0, len))
        finishPath()
    }

    @Suppress("UNCHECKED_CAST")
    private fun Editor.solids() = doc.elements.filter { it.kind == ElementKind.SOLID }.map { Evaluator().solid(it.ref as SolidRef).mesh }

    /** OP-4 across solids: A's measured height drives B's depth — forward, non-ancestor, legal. */
    @Test
    fun oneSolidsExtentDrivesAnothersDepth() {
        val ed = Editor()
        ed.wall(20.0, 60.0)
        val hA = ed.doc.newParameter("hA", 42.0.mm)
        ed.activeScalar = hA
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(15.0, 30.0))
        ed.setTool(Tools.EXTENT_Z)
        ed.click(Vec2(15.0, 30.0))
        val extent = ed.doc.scalars.last { !it.editable }

        ed.wall(120.0, 40.0)
        ed.activeScalar = extent // B's depth = A's measured height
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(115.0, 20.0))

        assertEquals(2, ed.solids().size)
        assertClose(Geom3.volume(ed.solids()[1]), 10.0 * 40.0 * 42.0, tol = 1e-6)

        ed.doc.setParameter(hA, 77.0.mm)
        assertClose(Geom3.volume(ed.solids()[1]), 10.0 * 40.0 * 77.0, tol = 1e-6, msg = "B follows A's height through the measurement")
        val once = DocumentFormat.save(ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)))
    }

    /** A section of a storey that was itself built on a face: plan coordinates all the way up. */
    @Test
    fun aSectionOfAFaceBasedStoreyReadsInPlanCoordinates() {
        val ed = Editor()
        ed.wall(20.0, 100.0)
        ed.activeScalar = ed.doc.newParameter("h1", 50.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(15.0, 50.0))
        // storey 2 = the ground floor's own section, extruded on its top face
        ed.activeScalar = ed.doc.newParameter("zc", 25.0.mm)
        ed.setTool(Tools.SECTION)
        ed.click(Vec2(15.0, 50.0))
        ed.activeScalar = ed.doc.newParameter("h2", 30.0.mm)
        ed.setTool(Tools.EXTRUDE_ON_FACE)
        ed.click(Vec2(15.0, 50.0)) // the base solid
        ed.click(Vec2(15.0, 50.0)) // the section area (footprint hint coincides; the slot filters kinds)

        // now section storey 2 at an absolute height inside it (50 + 15)
        ed.activeScalar = ed.doc.newParameter("z2", 65.0.mm)
        ed.setTool(Tools.SECTION)
        // pick storey 2: both solids' hints coincide in plan, so address it via the element tree
        val storey2 = ed.doc.elements.last { it.kind == ElementKind.SOLID }
        ed.selectElement(storey2)
        ed.setTool(Tools.SECTION)
        ed.click(Vec2(15.0, 50.0))

        val section2 = ed.doc.elements.last { it.kind == ElementKind.AREA }
        val region = (Evaluator().valueOf(section2.ref) as RegionValue).region
        // 10 x 100 rectangle in plan, regardless of how many faces up the chain it sits
        assertClose(kotlin.math.abs(loopAreaOf(region)), 10.0 * 100.0, tol = 1e-6, msg = "plan coordinates all the way up")
    }

    private fun loopAreaOf(region: constructit.geom.Region): Double = GeomMath.signedArea(region.outer)

    /** A section area is ordinary geometry: a linear array must copy it like anything else. */
    @Test
    fun aSectionAreaCanBeArrayed() {
        val ed = Editor()
        ed.wall(20.0, 60.0)
        ed.activeScalar = ed.doc.newParameter("h", 30.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(15.0, 30.0))
        ed.activeScalar = ed.doc.newParameter("zc", 15.0.mm)
        ed.setTool(Tools.SECTION)
        ed.click(Vec2(15.0, 30.0))

        ed.setTool(Tools.POINT)
        ed.click(Vec2(200.0, 0.0))
        ed.click(Vec2(260.0, 0.0))
        ed.count = 3
        ed.setTool(Tools.ARRAY_LINEAR)
        ed.click(Vec2(15.0, 30.0)) // the section area (the wall footprint is also here — see what wins)
        ed.click(Vec2(200.0, 0.0))
        ed.click(Vec2(260.0, 0.0))

        val areas = ed.doc.elements.filter { it.kind == ElementKind.AREA }
        assertTrue(areas.size >= 3, "an array of an area makes area copies, got ${areas.size} areas")
        val once = DocumentFormat.save(ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)))
    }
}
