package constructit

import constructit.core.Evaluator
import constructit.core.PointValue
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.dsl.valueOf
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.Format
import constructit.editor.ScalarEntry
import constructit.editor.Tools
import constructit.geom.Geom3
import constructit.geom.Vec2
import constructit.units.Dimension
import constructit.units.mm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **3D measurements as tools** (OP-4, forward): a solid's volume and its extent along each world axis,
 * landing in the panel as ordinary read-only scalars — which is the whole of the 3D→2D seam that needs no
 * geometry (OP-17). The papercraft flow is the test that matters: a measured extent *drives* a new 2D
 * construction, and the drawing follows the part.
 *
 * The axis of an extent is a **stored discrete choice**, and the tool id is where it is stored — three
 * tools, as there are three boolean tools for `BoolOp`. A single tool reading the axis off the placing
 * click (the way an angular dimension reads its sector) cannot work: the choice includes Z, which no click
 * in a plan view can name.
 */
class SolidMeasureToolTest {
    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerDown(s)
        pointerUp(s)
    }

    private fun Editor.solidElement(): Element = doc.elements.last { it.kind == ElementKind.SOLID }

    @Suppress("UNCHECKED_CAST")
    private fun Editor.meshOf(el: Element) = Evaluator().solid(el.ref as SolidRef).mesh

    private fun Editor.entry(name: String): ScalarEntry = doc.scalars.single { it.name == name }

    private fun value(e: ScalarEntry) = ((Evaluator().eval(e.ref.node) as constructit.core.EvalResult.Ok).value as constructit.core.ScalarValue).q

    /** A straight wall, 10 x 60 in plan (x in 15..25), extruded 12 deep. */
    private fun wallSolid(): Editor {
        val ed = Editor()
        ed.activeScalar = ed.doc.newParameter("t", 10.0.mm)
        ed.setTool(Tools.WALL)
        ed.click(Vec2(20.0, 0.0))
        ed.click(Vec2(21.0, 60.0))
        ed.finishPath()
        ed.activeScalar = ed.doc.newParameter("depth", 12.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(15.0, 30.0))
        return ed
    }

    // ---- the measurements themselves ----

    @Test
    fun theVolumeToolLandsAReadOnlyMeasurementThatFollowsTheSolid() {
        val ed = wallSolid()
        val before = ed.doc.elements.size
        ed.setTool(Tools.VOLUME)
        ed.click(Vec2(15.0, 30.0))

        assertEquals(before, ed.doc.elements.size, "a measurement is a panel value, not geometry")
        val vol = ed.entry("vol")
        assertFalse(vol.editable, "a measured value is the driven side of OP-4: it cannot be written")
        assertEquals(Dimension.VOLUME, value(vol).dim)
        assertClose(value(vol).base, 10.0 * 60.0 * 12.0, tol = 1e-6)
        assertTrue(Format.quantity(value(vol)).endsWith("mm³"), Format.quantity(value(vol)))

        // it is derived, so it follows the feature's own parameter
        ed.doc.setParameter(ed.entry("depth"), 30.0.mm)
        assertClose(value(vol).base, 10.0 * 60.0 * 30.0, tol = 1e-6)
        assertClose(value(vol).base, Geom3.volume(ed.meshOf(ed.solidElement())), tol = 1e-9)
    }

    @Test
    fun theThreeExtentToolsMeasureTheThreeAxes() {
        val ed = wallSolid()
        for (tool in listOf(Tools.EXTENT_X, Tools.EXTENT_Y, Tools.EXTENT_Z)) {
            ed.setTool(tool)
            ed.click(Vec2(15.0, 30.0))
        }
        assertClose(value(ed.entry("extx")).mm, 10.0)
        assertClose(value(ed.entry("exty")).mm, 60.0)
        assertClose(value(ed.entry("extz")).mm, 12.0)
        for (n in listOf("extx", "exty", "extz")) assertEquals(Dimension.LENGTH, value(ed.entry(n)).dim)

        // thicken the wall and raise the extrusion: both numbers follow, each its own axis
        ed.doc.setParameter(ed.entry("t"), 25.0.mm)
        ed.doc.setParameter(ed.entry("depth"), 40.0.mm)
        assertClose(value(ed.entry("extx")).mm, 25.0)
        assertClose(value(ed.entry("exty")).mm, 60.0)
        assertClose(value(ed.entry("extz")).mm, 40.0)
    }

    /** Picking something that is not a solid builds nothing, and says nothing untrue about it. */
    @Test
    fun theMeasureToolsNeedASolid() {
        val ed = wallSolid()
        val scalars = ed.doc.scalars.size
        ed.setTool(Tools.EXTENT_Z)
        ed.click(Vec2(500.0, 500.0)) // empty space
        assertEquals(scalars, ed.doc.scalars.size, "a miss creates nothing")
    }

    // ---- forward flow: the measured part drives the new drawing (the papercraft net) ----

    /**
     * The flow a papercraft net is built with: two extents of the solid become the two coordinates of a
     * point, so a 2D rectangle *is* the part's footprint — and re-typing the part's own parameters moves
     * the drawing. This goes through the ordinary scalar slot: a tool takes measurements and parameters
     * alike from the panel, which is what "measurements feed forward" means concretely (OP-4).
     */
    @Test
    fun measuredExtentsDriveA2dConstruction() {
        val ed = wallSolid()
        ed.setTool(Tools.EXTENT_Y)
        ed.click(Vec2(15.0, 30.0))
        ed.setTool(Tools.EXTENT_Z)
        ed.click(Vec2(15.0, 30.0))

        // the unfolded panel: a rectangle from the origin to (length, height) of the part
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, -100.0))
        val origin = ed.doc.elements.last { it.kind == ElementKind.POINT }
        ed.activeScalar = ed.entry("exty")
        ed.activeScalar = ed.entry("extz")
        ed.setTool(Tools.POINT_XY)
        ed.click(Vec2(0.0, 0.0)) // no slots: the click only says "now"
        val corner = ed.doc.elements.last { it.kind == ElementKind.POINT || it.kind == ElementKind.DERIVED_POINT }
        val ev = Evaluator()
        assertEquals(Vec2(60.0, 12.0), (ev.valueOf(corner.ref) as PointValue).p)

        val segmentsBefore = ed.doc.elements.filter { it.kind == ElementKind.SEGMENT }
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, -100.0)) // the free origin point
        ed.click(Vec2(60.0, 12.0)) // the driven corner
        val net = ed.doc.elements.filter { it.kind == ElementKind.SEGMENT && it !in segmentsBefore }
        assertEquals(4, net.size, "the net panel is a rectangle: ${ed.statusHint}")

        // now change the *part* and watch the *drawing* move: forward flow, one direction only
        ed.doc.setParameter(ed.entry("depth"), 33.0.mm)
        assertEquals(Vec2(60.0, 33.0), (Evaluator().valueOf(corner.ref) as PointValue).p)
        val ev2 = Evaluator()
        val top = net.mapNotNull { (ev2.valueOf(it.ref) as? constructit.core.SegmentValue)?.seg }.maxOf { maxOf(it.a.y, it.b.y) }
        assertClose(top, 33.0, msg = "the net's far edge is the part's height")
        assertTrue(origin.draggable, "the net's own corner is still a free point")
    }

    /** The other route to the same thing: wire a parameter to a measurement (OP-4's driving XOR driven). */
    @Test
    fun aParameterCanBeWiredToASolidsMeasuredExtent() {
        val ed = wallSolid()
        ed.setTool(Tools.EXTENT_Z)
        ed.click(Vec2(15.0, 30.0))
        val gauge = ed.doc.newParameter("gauge", 1.0.mm)
        assertTrue(ed.doc.wireParameter(gauge, ed.entry("extz")))
        assertClose(value(gauge).mm, 12.0)
        ed.doc.setParameter(ed.entry("depth"), 21.0.mm)
        assertClose(value(gauge).mm, 21.0, msg = "the wired parameter tracks the measurement")
    }

    /**
     * The honest refusal at this seam: a measurement may drive **new** geometry, never an ancestor of the
     * solid it was measured from — that would be a cycle, and a cyclic DAG is a dead drawing, not a wrong
     * one. The existing wiring check covers it, and this states it for the 3D case (OP-4).
     */
    @Test
    fun aMeasurementCannotDriveItsOwnAncestor() {
        val ed = wallSolid()
        ed.setTool(Tools.EXTENT_Z)
        ed.click(Vec2(15.0, 30.0))
        ed.setTool(Tools.EXTENT_X)
        ed.click(Vec2(15.0, 30.0))
        ed.setTool(Tools.VOLUME)
        ed.click(Vec2(15.0, 30.0))

        assertFalse(
            ed.doc.wireParameter(ed.entry("depth"), ed.entry("extz")),
            "the extrusion's own depth cannot be driven by a measurement of the extrusion",
        )
        assertFalse(
            ed.doc.wireParameter(ed.entry("t"), ed.entry("extx")),
            "nor can the wall's thickness, two steps upstream",
        )
        // ...and the drawing is untouched: the refusal is a refusal, not a half-applied edit
        assertClose(value(ed.entry("depth")).mm, 12.0)
        assertClose(value(ed.entry("extz")).mm, 12.0)
        assertClose(Geom3.volume(ed.meshOf(ed.solidElement())), 10.0 * 60.0 * 12.0, tol = 1e-6)
    }

    // ---- the file ----

    @Test
    fun aDocumentWithSolidMeasurementsRoundTrips() {
        val ed = wallSolid()
        ed.setTool(Tools.VOLUME)
        ed.click(Vec2(15.0, 30.0))
        ed.setTool(Tools.EXTENT_Y)
        ed.click(Vec2(15.0, 30.0))
        ed.setTool(Tools.EXTENT_Z)
        ed.click(Vec2(15.0, 30.0))
        ed.activeScalar = ed.entry("exty")
        ed.activeScalar = ed.entry("extz")
        ed.setTool(Tools.POINT_XY)
        ed.click(Vec2(0.0, 0.0))

        val text = DocumentFormat.save(ed.doc)
        assertTrue(text.contains("tool mvolume"), "a measurement rides the generic tool step (OP-18):\n$text")
        assertTrue(text.contains("tool mextentz"), text)
        val reloaded = DocumentFormat.load(text)
        assertEquals(text, DocumentFormat.save(reloaded), "save -> load -> save must be byte-equal")

        val corner = reloaded.elements.last()
        assertEquals(Vec2(60.0, 12.0), (Evaluator().valueOf(corner.ref) as PointValue).p, "the driven point came back driven")
        val vol = assertNotNull(reloaded.scalars.firstOrNull { it.name == "vol" })
        assertFalse(vol.editable, "and the measurement came back read-only")
    }

    /** Deleting the solid takes its measurements with it — the step that made them owns them (OP-18). */
    @Test
    fun deletingTheSolidTakesItsMeasurements() {
        val ed = wallSolid()
        ed.setTool(Tools.VOLUME)
        ed.click(Vec2(15.0, 30.0))
        ed.setTool(Tools.SELECT)
        ed.selectElement(ed.solidElement())
        assertTrue(ed.deleteSelection(), ed.statusHint)
        assertEquals(0, ed.doc.elements.count { it.kind == ElementKind.SOLID })
        assertTrue(ed.doc.scalars.none { it.name == "vol" }, "the measurement of a deleted solid is gone too")
        assertTrue(ed.undo())
        assertClose(value(ed.entry("vol")).base, 10.0 * 60.0 * 12.0, tol = 1e-6, msg = "undo restores it measuring")
    }
}
