package constructit

import constructit.core.CircleValue
import constructit.core.Evaluator
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.dsl.valueOf
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.Geom3
import constructit.geom.MeshBool
import constructit.geom.Vec2
import constructit.units.mm
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Probes over the fix wave: a rider's full lifecycle across carriers, the naming authority under a
 * mid-journal delete, and boss + drill from one face view (issue #1's fix composed with Cut).
 */
class FixWaveProbeTest {
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

    private fun Editor.type(v: String) {
        v.forEach { key(it.toString()) }
        key("Enter")
    }

    /** Born on one curve, freed, attached to another — a circle centred on it follows the whole way. */
    @Test
    fun aRiderMigratesBetweenCarriersThroughFreedom() {
        val ed = Editor()
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(-80.0, 0.0))
        ed.click(Vec2(80.0, 0.0)) // carrier 1
        ed.click(Vec2(-80.0, 40.0))
        ed.click(Vec2(80.0, 40.0)) // carrier 2
        ed.setTool(Tools.POINT_ON_LINE)
        ed.click(Vec2(20.0, 0.0)) // born on carrier 1
        ed.setTool(Tools.CIRCLE_R)
        ed.type("6")
        ed.click(Vec2(20.0, 0.0)) // a consumer centred on the rider

        fun centre(): Vec2 {
            val c = ed.doc.elements.first { it.kind == ElementKind.CIRCLE }
            return (Evaluator().valueOf(c.ref) as CircleValue).circle.center
        }
        assertClose(centre().y, 0.0)

        ed.setTool(Tools.MAKE_ABSOLUTE)
        ed.click(Vec2(20.0, 0.0))
        assertClose(centre().y, 0.0, msg = "freeing moves nothing")
        // now drag the freed point onto carrier 2: the magnet attaches it there
        ed.drag(Vec2(20.0, 0.0), Vec2(20.0, 40.0))
        assertClose(centre().y, 40.0, msg = "the consumer followed onto the second carrier")
        // and it slides along carrier 2 now
        ed.drag(Vec2(20.0, 40.0), Vec2(45.0, 40.0))
        assertClose(centre().x, 45.0, msg = "sliding on the new carrier")
        assertClose(centre().y, 40.0)

        val once = DocumentFormat.save(ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "the whole lifecycle replays")
    }

    /** After a mid-journal delete renumbers the file, visible labels still match a fresh save. */
    @Test
    fun labelsMatchTheFileEvenAfterAMidJournalDelete() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(40.0, 0.0))
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(40.0, 0.0))
        ed.setTool(Tools.CIRCLE_R)
        ed.type("5")
        ed.click(Vec2(40.0, 0.0))
        // delete the FIRST point's... no — delete the segment (mid-journal), circle survives
        val seg = ed.doc.elements.first { it.kind == ElementKind.SEGMENT }
        ed.selectElement(seg)
        assertTrue(ed.deleteSelection())
        // now every visible label must equal the fresh save's name for that element
        val saved = DocumentFormat.save(ed.doc)
        val circle = ed.doc.elements.first { it.kind == ElementKind.CIRCLE }
        val label = ed.doc.nameOf(circle)
        assertTrue(saved.contains("-> $label"), "label '$label' must appear as a created name in:\n$saved")
        ed.selectElement(circle)
        assertTrue(ed.selectionLabel().contains(label), "the selection header says '$label': ${ed.selectionLabel()}")
    }

    /** One face view, both directions: an outward boss and an inward drill from the same sketch space. */
    @Test
    fun bossOutAndDrillInFromOneFace() {
        requireEngine()
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(80.0, 50.0))
        ed.activeScalar = ed.doc.newParameter("t", 20.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(40.0, 0.0))
        ed.setTool(Tools.SKETCH_ON_FACE)
        ed.click(Vec2(40.0, 0.0)) // the front face (y=0)
        // the boss: a small circle extruded OUTWARD (issue #1 semantics)
        ed.setTool(Tools.CIRCLE_R)
        ed.type("5")
        ed.click(Vec2(20.0, 10.0))
        ed.setTool(Tools.EXTRUDE)
        ed.type("8")
        ed.click(Vec2(25.0, 10.0))
        val boss = ed.doc.elements.last { it.kind == ElementKind.SOLID }

        @Suppress("UNCHECKED_CAST")
        val bb = Geom3.bounds(Evaluator().solid(boss.ref as SolidRef).mesh)!!
        assertTrue(bb.first.y < 0.0 && bb.second.y <= 0.0 + 1e-9, "the boss grows OUTWARD (y<0), got $bb")

        // the drill: another circle, Cut — inward as ever
        ed.setTool(Tools.CIRCLE_R)
        ed.type("3")
        ed.click(Vec2(60.0, 10.0))
        ed.setTool(Tools.CUT)
        ed.type("12")
        ed.click(Vec2(63.0, 10.0))
        val cut = ed.doc.elements.last { it.kind == ElementKind.SOLID }

        @Suppress("UNCHECKED_CAST")
        val cm = Evaluator().solid(cut.ref as SolidRef).mesh
        assertManifold(cm, "plate with a drill, boss alongside")
        assertClose(Geom3.volume(cm), 80.0 * 50.0 * 20.0 - Math.PI * 9.0 * 12.0, tol = 30.0, msg = "the drill went IN")
        val once = DocumentFormat.save(ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)))
    }

    private fun requireEngine() = assumeTrue(MeshBool.available, "mesh boolean engine unavailable")
}
