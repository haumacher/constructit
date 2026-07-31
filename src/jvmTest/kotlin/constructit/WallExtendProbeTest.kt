package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.RegionRef
import constructit.dsl.region
import constructit.dsl.scalar
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.ThickCarrier
import constructit.editor.Tools
import constructit.geom.Justification
import constructit.geom.Vec2
import constructit.units.mm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Probes on incremental walls (GitHub #7), composing the extension gesture with what already existed:
 * an opening cut on the *appended* leg, a carrier drag that slides both T-vertices along their hosts,
 * a second extension whose new curve lands a T on the previously appended one, and a thickness edit —
 * each stage still valid, still one wall, and byte-equal through save → load → save.
 */
class WallExtendProbeTest {
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

    private fun wall(ed: Editor) = ed.doc.thickNetworks.single()

    private fun regionOf(ed: Editor) = Evaluator().region(wall(ed).footprint.ref as RegionRef)

    private fun assertValid(
        ed: Editor,
        msg: String,
    ) = assertTrue(Evaluator().eval(wall(ed).footprint.ref.node) is EvalResult.Ok, "$msg — ${ed.statusHint}")

    private fun roundTrips(
        ed: Editor,
        msg: String,
    ) {
        val once = DocumentFormat.save(ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), msg)
    }

    @Test
    fun openingOnTheAppendedLegSurvivesDriftSecondExtensionAndThicknessEdit() {
        val ed = Editor()
        ed.replaceDocument(DocumentFormat.load(TAttachmentTest.HULL))

        // ---- extend the hull with the partition e11 (the issue's gesture) ----
        ed.setTool(Tools.THICKEN)
        ed.justification = Justification.CENTER
        ed.click(Vec2(-67.25, 9.0))
        assertNotNull(ed.extendingWall, "first pick was the wall: ${ed.statusHint}")
        ed.click(Vec2(-85.75, -20.0))
        ed.key("Enter")
        assertEquals(5, (wall(ed).carrier as ThickCarrier.Network).curves.size, ed.statusHint)
        assertValid(ed, "after the first extension")

        // ---- an opening on the APPENDED leg (leg index of e11 in the grown wall) ----
        ed.activeScalar = ed.doc.newParameter("w", 12.0.mm)
        ed.setTool(Tools.OPENING)
        ed.click(Vec2(-85.75, -20.0))
        assertEquals(1, wall(ed).intervals.size, "the opening landed: ${ed.statusHint}")
        val leg = wall(ed).intervals.single().legIndex
        assertEquals(4, leg, "the opening rides the appended carrier's leg")
        roundTrips(ed, "extension + opening on the appended leg replays")

        // ---- drift: drag the partition sideways; both T-vertices slide along their hosts ----
        val holesBefore = regionOf(ed).holes.size
        ed.drag(Vec2(-85.75, -30.0), Vec2(-70.0, -30.0))
        assertValid(ed, "after sliding the partition")
        assertEquals(holesBefore, regionOf(ed).holes.size, "still the same rooms after the slide")
        assertEquals(leg, wall(ed).intervals.single().legIndex, "the opening stayed on its leg")
        roundTrips(ed, "the slid plan replays")

        // ---- a second extension: a NEW segment drawn after the wall, landing a T on the appended curve ----
        // draw a fresh segment from the partition's interior to the hull's right leg
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(-70.0, -35.0))
        ed.click(Vec2(-11.75, -35.0))
        ed.setTool(Tools.THICKEN)
        ed.justification = Justification.CENTER
        ed.click(Vec2(-67.25, 9.0))
        assertNotNull(ed.extendingWall, "second extension arms: ${ed.statusHint}")
        ed.click(Vec2(-40.0, -35.0))
        ed.key("Enter")
        assertEquals(6, (wall(ed).carrier as ThickCarrier.Network).curves.size, ed.statusHint)
        assertValid(ed, "after the second extension (T on the appended curve)")
        assertEquals(1, ed.doc.thickNetworks.size, "still one wall")
        assertEquals(holesBefore + 1, regionOf(ed).holes.size, "the new partition split a room")
        assertEquals(leg, wall(ed).intervals.single().legIndex, "the opening still rides its leg")
        roundTrips(ed, "a curve drawn after the wall, then appended, replays (journal move)")

        // ---- thickness is one shared parameter: edit it, everything re-derives ----
        val d = ed.doc.scalars.first { it.name == "d" }
        ed.doc.setParameter(d, 8.0.mm)
        assertValid(ed, "after the thickness edit")
        roundTrips(ed, "the thicker plan replays")

        // ---- undo: the uncheckpointed live edit first, then exactly one step per extension ----
        assertTrue(ed.undo(), "undo #1")
        assertEquals(6, (wall(ed).carrier as ThickCarrier.Network).curves.size, "the live parameter edit reverts first")
        // re-fetch: undo reloads the document, so the old entry is stale
        val dNow = ed.doc.scalars.first { it.name == "d" }
        assertClose(Evaluator().scalar(dNow.ref).mm, 5.0, tol = 1e-9, msg = "d is back to 5mm")
        assertTrue(ed.undo(), "undo #2")
        assertEquals(5, (wall(ed).carrier as ThickCarrier.Network).curves.size, "one step back to the 5-curve wall")
        assertValid(ed, "the smaller wall is whole again")
    }
}
