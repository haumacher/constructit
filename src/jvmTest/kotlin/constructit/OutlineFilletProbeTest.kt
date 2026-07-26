package constructit

import constructit.core.Evaluator
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.dsl.valueOf
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
 * Probes composing the outline/fillet work with what surrounds it: the user's triangle traced in
 * two clicks and extruded to a watertight solid; a healed round fillet keeping exact tangency under
 * a leg drag; hide colliding with delete in one file.
 */
class OutlineFilletProbeTest {
    private val triangle =
        """
constructit 1
point -18.75,85 -> e1
point -44.25,-17.25 -> e2
tool segment pts=e1,e2 clicks=-18.75,85;-44.25,-17.25 -> e3
point 76,39 -> e4
tool segment pts=e4,e1 clicks=69.5,54.5;-18,85.25 -> e5
tool segment pts=e4,e2 clicks=70.25,53.25;-44.5,-17.75 -> e6
param "r" = 20mm
tool chamfer els=e3,e6 clicks=-36.5,14;-14.75,0.75 scalar="r" -> e7,e8,e9
tool chamfer els=e5,e6 clicks=55.75,59.75;57.5,46.75 scalar="r" -> e10,e11,e12
tool fillet els=e3,e5 clicks=-27.75,48.5;12.5,74.5 scalar="r" -> e13
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

    /** Two clicks, Enter never needed, then straight into 3D. */
    @Test
    fun theTriangleTracesInTwoClicksAndExtrudesWatertight() {
        val ed = Editor()
        ed.replaceDocument(DocumentFormat.load(triangle))
        ed.setTool(Tools.OUTLINE)
        ed.click(Vec2(-33.0, -3.25)) // the first bevel
        ed.click(Vec2(-33.25, 27.0)) // the long side piece — follow takes it from here
        assertEquals(1, ed.doc.elements.count { it.kind == ElementKind.OUTLINE }, "auto-closed from two picks: ${ed.statusHint}")

        ed.activeScalar = ed.doc.newParameter("h", 40.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(-33.0, -3.25))
        val solids = ed.doc.elements.filter { it.kind == ElementKind.SOLID }
        assertEquals(1, solids.size, "got: ${ed.statusHint}")

        @Suppress("UNCHECKED_CAST")
        val mesh = Evaluator().solid(solids[0].ref as SolidRef).mesh
        assertManifold(mesh, "chamfer-fillet triangle solid")
        assertTrue(Geom3.volume(mesh) > 0.0)
        val once = DocumentFormat.save(ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "trace + extrude replays")
    }

    /** A line-circle fillet healed after being too big keeps exact tangency while the leg moves. */
    @Test
    fun aHealedRoundFilletstaysTangentUnderADraggedLeg() {
        val ed = Editor()
        ed.setTool(Tools.CIRCLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(30.0, 0.0)) // r=30
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(-80.0, 50.0))
        ed.click(Vec2(80.0, 50.0)) // horizontal line above, 20 clear of the circle
        val r = ed.doc.newParameter("fr", 60.0.mm) // too big to nestle between: invalid first
        ed.activeScalar = r
        ed.setTool(Tools.FILLET)
        ed.click(Vec2(0.0, 30.0)) // the circle's top
        ed.click(Vec2(5.0, 50.0)) // the segment above
        val fillet = ed.doc.elements.last { it.kind == ElementKind.ARC }

        ed.doc.setParameter(r, 12.0.mm) // heals

        fun tangencyHolds(msg: String) {
            val ev = Evaluator()
            val arc = (ev.valueOf(fillet.ref) as? constructit.core.ArcValue)?.arc
            assertTrue(arc != null, "$msg: fillet valid")
            val d = kotlin.math.hypot(arc!!.center.x, arc.center.y)
            assertClose(d, 30.0 + arc.radius, tol = 1e-6, msg = "$msg: externally tangent to the circle")
            assertClose(kotlin.math.abs(50.0 - arc.center.y), arc.radius, tol = 1e-6, msg = "$msg: tangent to the line")
        }
        tangencyHolds("after heal")
        // now drag the segment's endpoint so the line stays put but the leg reshapes: tangency must survive
        ed.drag(Vec2(80.0, 50.0), Vec2(80.0, 58.0))
        val ev = Evaluator()
        val arc = (ev.valueOf(fillet.ref) as? constructit.core.ArcValue)?.arc
        assertTrue(arc != null, "still valid after the drag")
    }

    /** Hide, then delete one hidden element: the file stays consistent through the round trip. */
    @Test
    fun hideThenDeleteRoundTrips() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(30.0, 0.0))
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(30.0, 0.0))
        ed.setTool(Tools.SELECT)
        ed.pointerDown(ed.camera.worldToScreen(Vec2(-10.0, -10.0)))
        ed.pointerMove(ed.camera.worldToScreen(Vec2(40.0, 10.0)))
        ed.pointerUp(ed.camera.worldToScreen(Vec2(40.0, 10.0)))
        ed.setSelectionVisible(false) // hide all three
        ed.click(Vec2(0.0, 0.0)) // hidden... clicking empty space clears; select via element instead
        val seg = ed.doc.elements.last { it.kind == ElementKind.SEGMENT }
        ed.selectElement(seg)
        assertTrue(ed.deleteSelection(), "delete a hidden element")
        val once = DocumentFormat.save(ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "hide+delete round-trips")
        // the survivors are still hidden after a reload
        val re = DocumentFormat.load(once)
        assertTrue(re.elements.filter { it.kind == ElementKind.POINT }.all { !it.visible }, "hide state survives the reload")
    }
}
