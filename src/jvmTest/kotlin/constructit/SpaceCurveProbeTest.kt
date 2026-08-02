package constructit

import constructit.core.Evaluator
import constructit.core.Path3Value
import constructit.dsl.valueOf
import constructit.editor.Document
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.Scene3
import constructit.editor.Scene3Sync
import constructit.editor.Tools
import constructit.exchange.ExportFormat
import constructit.exchange.ExportScene
import constructit.exchange.Exports
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.units.mm
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **The probe review of OP-26 step 1** — the curve composed with what was already in the drawing.
 *
 * The delivery's own suite proves the curve against itself: its geometry, its interpolation, its gesture,
 * its two views, its file, its refusals. These take it outside: to the **parenting rule**, which is the
 * headline claim of OP-26 and is only really tested when the *parent* moves rather than the point; to the
 * **export seam**, which nobody would think to point a curve at; to the two most recent packages it now has
 * to share a scene with; and to the one thing a gesture that closes on identity must not do — close on a
 * coincidence.
 */
class SpaceCurveProbeTest {
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

    private fun pathOf(ed: Editor): constructit.geom.Path3 {
        val el = ed.doc.elements.last { it.kind == ElementKind.SPACE_CURVE }
        return (Evaluator().valueOf(el.ref) as Path3Value).path
    }

    private fun corners(ed: Editor): List<Vec3> {
        val p = pathOf(ed)
        return p.elements.map { it.start } + listOfNotNull(p.elements.lastOrNull()?.end)
    }

    // ---- the parenting rule, tested by moving the parent ----

    /**
     * **A curve rides the space its points were drawn in.** This is what OP-26's parenting rule is *for*,
     * and it is not proved by dragging a point: a point dragged is a source moving, which any node follows.
     * The claim is that a curve routed against a **datum plane** follows when that plane itself moves —
     * here by dragging the segment the plane is hinged on, which is one construction removed from the curve
     * and two from the point. Nothing in the curve's own definition mentions the plane; it follows because
     * its input does.
     */
    @Test
    fun aCurveThroughAPointOnADatumFollowsWhenTheDatumsOwnCarrierMoves() {
        val ed = Editor()
        // a carrier segment in the plan, and a datum plane standing on it
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(0.0, 50.0))
        ed.click(Vec2(100.0, 50.0))
        ed.setTool(Tools.SKETCH_PLANE)
        ed.type("90")
        ed.click(Vec2(50.0, 50.0))
        assertTrue(ed.doc.activeSpace.isDatum, "a datum plane was opened")
        val datum = ed.doc.activeSpace.name

        // a point drawn *on the datum*, which is the one that will ride
        ed.setTool(Tools.POINT)
        ed.click(Vec2(20.0, 30.0))

        // ...and two ordinary plan points, so the curve genuinely spans two spaces
        assertTrue(ed.setActiveSpace(Document.PLAN_SPACE))
        ed.setTool(Tools.POINT)
        ed.click(Vec2(-40.0, 50.0))
        ed.click(Vec2(140.0, 50.0))

        // the curve: one pick on the datum, two in the plan — the picks survive the space switch
        ed.setTool(Tools.CURVE3)
        assertTrue(ed.setActiveSpace(datum))
        ed.click(Vec2(20.0, 30.0))
        assertTrue(ed.setActiveSpace(Document.PLAN_SPACE))
        ed.click(Vec2(-40.0, 50.0))
        ed.click(Vec2(140.0, 50.0))
        ed.key("Enter")

        assertEquals(1, ed.doc.elements.count { it.kind == ElementKind.SPACE_CURVE }, "one curve was built")
        val before = corners(ed)
        assertEquals(3, before.size, "through three points")
        val lifted = before.first { abs(it.z) > 1e-9 }
        assertTrue(lifted.z > 0.0, "the datum point is off the plan: $lifted")

        // now move the *plane's own carrier*. Nothing touches the curve or its points.
        ed.drag(Vec2(0.0, 50.0), Vec2(0.0, 90.0))
        val after = corners(ed)
        val movedTo = after.first { abs(it.z) > 1e-9 }
        assertTrue(
            (movedTo - lifted).length() > 1.0,
            "the curve's datum point followed the plane its space stands on: $lifted -> $movedTo",
        )
        // the two plan points did not move — only what was parented to the datum did
        val planBefore = before.filter { abs(it.z) <= 1e-9 }.sortedBy { it.x }
        val planAfter = after.filter { abs(it.z) <= 1e-9 }.sortedBy { it.x }
        assertEquals(planBefore.size, planAfter.size, "still the same number of plan points")
        for ((a, b) in planBefore.zip(planAfter)) {
            assertTrue((a - b).length() < 1e-9, "a plan point stayed where it was: $a vs $b")
        }

        // and the whole thing survives the file with its two parents intact
        val text = DocumentFormat.save(ed.doc)
        assertEquals(text, DocumentFormat.save(DocumentFormat.load(text)), "save → load → save is byte-equal")
    }

    // ---- closing is identity, never coincidence ----

    /**
     * **A curve whose ends are dragged together is still an open curve.** `Path3.closed` is structure, and
     * the sharpest way to test that is not a coincidence but a **weld**: drag the last point onto the first
     * and the two become *one node*, so the chain's last piece now ends exactly where its first begins —
     * bit-identically, not nearly. If closure were read off the geometry this would silently become a
     * different object, and dragging them apart again could not undo it.
     *
     * The gesture's own rule is the same one from the other side: a repeating tool closes when the *first
     * pick* is clicked again, which is an identity test against the element picked, never a proximity test
     * against a position.
     */
    @Test
    fun weldingACurvesLastPointOntoItsFirstDoesNotMakeItAClosedCurve() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(60.0, 0.0))
        ed.click(Vec2(60.0, 40.0))
        ed.setTool(Tools.CURVE3)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(60.0, 0.0))
        ed.click(Vec2(60.0, 40.0))
        ed.key("Enter")
        assertFalse(pathOf(ed).closed, "finished with Enter, so it is open")
        assertEquals(2, pathOf(ed).elements.size, "two pieces through three points")

        // drag the last point onto the first — close enough to weld, which makes them the same node
        ed.drag(Vec2(60.0, 40.0), Vec2(0.0, 0.0))
        val welded = pathOf(ed)
        assertEquals(welded.start, welded.end, "the chain now ends exactly where it began")
        assertFalse(welded.closed, "...and is still an OPEN curve: closure is what was said, not what is true")
        assertEquals(2, welded.elements.size, "still two pieces — no closing piece was invented")

        val text = DocumentFormat.save(ed.doc)
        val reloaded = DocumentFormat.load(text)
        val back =
            (Evaluator().valueOf(reloaded.elements.last { it.kind == ElementKind.SPACE_CURVE }.ref) as Path3Value).path
        assertFalse(back.closed, "and the file says open too — closure came off the step, not off the coordinates")
        assertEquals(2, back.elements.size)
        assertEquals(text, DocumentFormat.save(reloaded), "save → load → save is byte-equal")
    }

    // ---- the export seam has never seen a curve ----

    /**
     * **A curve is not a body, and every writer has to agree without being told.** The export seam extracts
     * *solids*; a curve is a first-class element that is not one, so the risk is not that it exports wrongly
     * but that it exports at all — as an empty body, or as an "invalid — not exported" note about something
     * that was never a candidate. All four writers are asked, because the note text is composed once and
     * consumed four times.
     */
    @Test
    fun aCurveIsInvisibleToEveryExportWriterWithoutBeingCalledInvalid() {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(60.0, 40.0))
        ed.activeScalar = ed.doc.newParameter("h", 20.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(30.0, 0.0))

        ed.setTool(Tools.POINT)
        ed.click(Vec2(-30.0, 10.0))
        ed.click(Vec2(-30.0, 60.0))
        ed.click(Vec2(90.0, 60.0))
        ed.setTool(Tools.CURVE3)
        ed.click(Vec2(-30.0, 10.0))
        ed.click(Vec2(-30.0, 60.0))
        ed.click(Vec2(90.0, 60.0))
        ed.key("Enter")
        assertEquals(1, ed.doc.elements.count { it.kind == ElementKind.SPACE_CURVE })

        val scene = ExportScene.extract(ed.doc, "probe")
        assertEquals(1, scene.nodes.size, "the solid is the only body")
        assertTrue(
            scene.notes.none { it.contains("invalid") },
            "and the curve is not reported as a body that failed: ${scene.notes}",
        )
        for (format in ExportFormat.entries) {
            val out = Exports.export(ed.doc, "probe", format)
            assertTrue(out.ok, "${format.label}: ${out.message}")
            assertTrue(out.bytes!!.isNotEmpty(), "${format.label} wrote something")
            assertTrue(out.message.contains("1 solid"), "${format.label}: ${out.message}")
        }
    }

    // ---- three packages in one scene ----

    /**
     * **A curve, a constructed solid and an imported open shell share one view and one upload gate.**
     * Sessions 34, 35 and 37 meeting: the gate now compares two lists rather than one, and the way that
     * goes wrong is asymmetric — a change to the kind that *was not* touched by a given edit going unnoticed.
     * So each is moved in turn and the gate is asked after each.
     */
    @Test
    fun aCurveASolidAndAnImportedShellShareTheGateWithoutLosingTrackOfAnyOfThem() {
        val ed = Editor()
        assertTrue(ed.importFile(crackedBoxBytes(), "gehaeuse.jt").ok)
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(200.0, 0.0))
        ed.click(Vec2(240.0, 40.0))
        ed.activeScalar = ed.doc.newParameter("h", 15.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(220.0, 0.0))
        ed.setTool(Tools.POINT)
        ed.click(Vec2(-50.0, 0.0))
        ed.click(Vec2(-50.0, 80.0))
        ed.click(Vec2(120.0, 80.0))
        ed.setTool(Tools.CURVE3)
        ed.click(Vec2(-50.0, 0.0))
        ed.click(Vec2(-50.0, 80.0))
        ed.click(Vec2(120.0, 80.0))
        ed.key("Enter")

        val scene = Scene3.extract(ed.doc)
        assertEquals(2, scene.solids.size, "the imported shell and the constructed block")
        assertEquals(1, scene.curves.size, "and the curve beside them")

        val sync = Scene3Sync()
        sync.update(Scene3.extract(ed.doc)) { }
        assertEquals(1, sync.uploads, "the first look uploads")
        repeat(10) { sync.update(Scene3.extract(ed.doc)) { } }
        assertEquals(1, sync.uploads, "and an unchanged drawing never again")

        // move the CURVE — the solids are untouched, and the gate must still see it
        ed.drag(Vec2(-50.0, 0.0), Vec2(-70.0, -10.0))
        sync.update(Scene3.extract(ed.doc)) { }
        assertEquals(2, sync.uploads, "a moved curve is new vertex data")

        // move the SOLID's parameter — the curve is untouched this time
        ed.doc.setParameter(ed.doc.scalars.first { it.name == "h" }, 25.0.mm)
        sync.update(Scene3.extract(ed.doc)) { }
        assertEquals(3, sync.uploads, "a changed solid is too")

        // hide the curve — it leaves the scene, which is a change
        val curve = ed.doc.elements.last { it.kind == ElementKind.SPACE_CURVE }
        assertEquals(1, ed.doc.setElementsVisible(listOf(curve), false))
        sync.update(Scene3.extract(ed.doc)) { }
        assertEquals(4, sync.uploads, "a hidden curve leaves the buffer")
        assertEquals(0, Scene3.extract(ed.doc).curves.size, "and really left")

        // ...while a rename reaches no vertex
        assertEquals(1, ed.doc.setElementsVisible(listOf(curve), true))
        sync.update(Scene3.extract(ed.doc)) { }
        val shown = sync.uploads
        ed.doc.nameElement(ed.doc.elements.last { it.kind == ElementKind.SPACE_CURVE }, "trasse")
        sync.update(Scene3.extract(ed.doc)) { }
        assertEquals(shown, sync.uploads, "a renamed curve is the same vertices in the same order")
        assertNotNull(pathOf(ed), "and it is still there")
        assertFalse(ed.doc.elements.none { ed.doc.userNameOf(it) == "trasse" }, "under its new name")
    }

    // ---- fixture ----

    /** A 60 × 40 × 20 box with one bottom facet removed, as JT bytes — the session-34 open shell. */
    private fun crackedBoxBytes(): ByteArray {
        val corners =
            listOf(
                Vec3(0.0, 0.0, 0.0),
                Vec3(60.0, 0.0, 0.0),
                Vec3(60.0, 40.0, 0.0),
                Vec3(0.0, 40.0, 0.0),
                Vec3(0.0, 0.0, 20.0),
                Vec3(60.0, 0.0, 20.0),
                Vec3(60.0, 40.0, 20.0),
                Vec3(0.0, 40.0, 20.0),
            )
        val tris =
            listOf(
                constructit.geom.Tri(0, 3, 2),
                constructit.geom.Tri(4, 5, 6),
                constructit.geom.Tri(4, 6, 7),
                constructit.geom.Tri(0, 1, 5),
                constructit.geom.Tri(0, 5, 4),
                constructit.geom.Tri(1, 2, 6),
                constructit.geom.Tri(1, 6, 5),
                constructit.geom.Tri(2, 3, 7),
                constructit.geom.Tri(2, 7, 6),
                constructit.geom.Tri(3, 0, 4),
                constructit.geom.Tri(3, 4, 7),
            )
        return constructit.exchange.Jt.write(
            constructit.exchange.ExportScene(
                "probe",
                listOf(
                    constructit.exchange.ExportNode(
                        "gehaeuse",
                        constructit.geom.Mesh3(corners, tris),
                        constructit.editor.Appearance.DEFAULT,
                    ),
                ),
            ),
        )
    }
}
