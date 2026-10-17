package constructit

import constructit.ProvenanceAddressTest.Companion.ISSUE36
import constructit.ProvenanceAddressTest.Companion.named
import constructit.ProvenanceAddressTest.Companion.roundedKeys
import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.SolidValue
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.Vec2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **Refactoring the construction in the editor** (OP-32, slice 3b and 3c) — replace an input of a feature, delete a
 * feature and reroute what read it onto its input. Each is an edit of the graph the file is, followed by its load:
 * one undo step, and a pick that addressed the old input is resolved **by name** on the new one.
 */
class RefactorTest {
    /** Every solid of [ed]'s drawing builds manifold or is invalid with a reason; the file round-trips; one undo is [before]. */
    private fun assertOneSoundStep(
        ed: Editor,
        before: String,
        what: String,
    ) {
        val ev = Evaluator()
        for (el in ed.doc.elements.filter { it.kind == ElementKind.SOLID }) {
            when (val r = ev.eval(el.ref.node)) {
                is EvalResult.Ok -> assertManifold((r.value as SolidValue).solid.mesh, "$what: ${ed.doc.nameOf(el)}")
                is EvalResult.Invalid -> assertTrue(r.why.render().isNotBlank(), "$what: ${ed.doc.nameOf(el)} is invalid with a reason")
            }
        }
        val text = DocumentFormat.save(ed.doc)
        assertSameFile(DocumentFormat.save(DocumentFormat.load(text)), text, "$what: save -> load -> save")
        assertTrue(ed.undo(), "$what: undo")
        assertEquals(before, DocumentFormat.save(ed.doc), "$what: one undo restores the previous file exactly")
        assertTrue(ed.redo(), "$what: redo")
        assertEquals(text, DocumentFormat.save(ed.doc), "$what: and redo is the edit again")
    }

    /** GitHub #36's first script: insert a vertex, delete the first fillet keeping the second, move the second onto the extrusion. */
    @Test
    fun issue36RefactoredThreeWays() {
        val ed = Editor(DocumentFormat.load(ISSUE36))
        ed.checkpoint()

        // 1. a vertex inserted into the outline upstream of both roundings
        var before = DocumentFormat.save(ed.doc)
        val path = ed.doc.orthoPaths.single()
        assertTrue(ed.doc.breakOrthoLeg(path, ed.doc.legOf(named(ed.doc, "e5"))!!.second, Vec2(20.0, 15.375), Vec2(20.0, 20.0)))
        ed.checkpoint()
        assertEquals(setOf("e11|top", "e11|e9"), roundedKeys(ed.doc, "e14"), "both roundings stay on their edges")
        assertOneSoundStep(ed, before, "insert")

        // 2. delete the first fillet, keeping the second: it now rounds the extruded body alone, its edge by name
        before = DocumentFormat.save(ed.doc)
        ed.selectElement(named(ed.doc, "e15"))
        assertTrue(ed.deleteSelectionRerouting(), ed.statusHint)
        assertEquals(setOf("e11|e9"), roundedKeys(ed.doc, "e14"), "the second fillet sits on the extrusion, on its own edge")
        assertOneSoundStep(ed, before, "delete the first fillet")

        // 3. replace the second fillet's body input with the extrusion itself
        before = DocumentFormat.save(ed.doc)
        val entry = named(ed.doc, "e16")
        ed.selectElement(entry)
        val inputs = ed.replaceableInputs()
        assertEquals(listOf("e14"), inputs.map { it.element }, "the second fillet reads the dressed body")
        assertTrue(ed.replaceInput(entry, 0, named(ed.doc, "e13")), ed.statusHint)
        val f12 = saved(ed.doc).features.first { it.str("id") == "f12" }
        assertEquals(listOf("e13"), f12.ids("els"), "it reads the extrusion now: $f12")
        assertEquals("e11|e9", f12.addressKey(), "…and still names its edge")
        val body = ed.doc.elements.first { it.kind == ElementKind.SOLID && ed.doc.creatingStep(it)?.fid == 12 }
        assertEquals(setOf("e11|e9"), roundedKeys(ed.doc, ed.doc.nameOf(body)), "its own body, rounded on the edge it named")
        assertOneSoundStep(ed, before, "replace the input")
    }

    /** Deleting the first fillet of the untouched #36 drawing the graph way: its body goes, the second fillet is rerouted. */
    @Test
    fun deletingADressedBodyReroutesWhatReadItOntoTheExtrusion() {
        val ed = Editor(DocumentFormat.load(DocumentFormat.save(DocumentFormat.load(ISSUE36))))
        // a box beside the L that subtracts from the dressed body: a reader of e14 that is no rounding of it
        val withBox =
            withEntries(
                DocumentFormat.save(ed.doc),
                features =
                    listOf(
                        """{"id":"f20","kind":"path","at":[50,-10],"creates":["e20"],"vertices":[{"id":"f21","at":[70,-10],"creates":["e21","e22"]},{"id":"f22","at":[70,10],"creates":["e23","e24"]},{"id":"f23","at":[50,10],"creates":["e25","e26"]}],"close":{"id":"f24","creates":["e27"]}}""",
                        """{"id":"f25","kind":"tool","tool":"extrude","els":["e22"],"params":["f8"],"creates":["e28"]}""",
                        """{"id":"f26","kind":"tool","tool":"union","els":["e14","e28"],"creates":["e29"]}""",
                    ),
            )
        ed.replaceDocument(DocumentFormat.load(withBox))
        val before = DocumentFormat.save(ed.doc)
        ed.selectElement(named(ed.doc, "e14"))
        assertTrue(ed.deleteSelectionRerouting(), ed.statusHint)
        val s = saved(ed.doc)
        assertTrue(s.features.none { it.str("id") == "f11" || it.str("id") == "f12" }, "the dressed body goes with its roundings: ${s.text}")
        assertEquals(listOf("e13", "e28"), s.features.first { it.str("id") == "f26" }.ids("els"), "the union reads the extrusion now")
        assertOneSoundStep(ed, before, "delete the dressed body")
    }

    /** A replacement that would close a circle, and one of a kind the slot does not take, are refused by name. */
    @Test
    fun aCycleAndAWrongKindAreRefusedAndChangeNothing() {
        val ed = Editor(DocumentFormat.load(ISSUE36))
        ed.checkpoint()
        val before = DocumentFormat.save(ed.doc)
        // the first fillet's input replaced by the body it makes itself
        val first = named(ed.doc, "e15")
        assertFalse(ed.replaceInput(first, 0, named(ed.doc, "e14")))
        assertTrue("circle" in ed.statusHint && "f11" in ed.statusHint, ed.statusHint)
        // …and by a curve, which its solid slot does not take
        assertFalse(ed.replaceInput(first, 0, named(ed.doc, "e3")))
        assertTrue("e3" in ed.statusHint && "f11" in ed.statusHint, ed.statusHint)
        assertEquals(before, DocumentFormat.save(ed.doc), "nothing changed")
        assertFalse(ed.canRedo)
    }

    /** The replacement is picked the way a slot picks: armed from the panel, one click in the drawing, Esc to cancel. */
    @Test
    fun theReplacementIsPickedByAClickInTheDrawing() {
        val ed = Editor(DocumentFormat.load(ISSUE36))
        ed.checkpoint()
        ed.selectElement(named(ed.doc, "e16"))
        assertTrue(ed.beginReplaceInput(0))
        assertTrue(ed.replacingInput)
        assertTrue(ed.key("Escape"))
        assertFalse(ed.replacingInput, "Esc disarms")
        ed.selectElement(named(ed.doc, "e16"))
        assertTrue(ed.beginReplaceInput(0))
        // a click on nothing the solid slot takes says so and stays armed
        val s = ed.camera.worldToScreen(Vec2(500.0, 500.0))
        ed.pointerDown(s)
        ed.pointerUp(s)
        assertTrue(ed.replacingInput, ed.statusHint)
        // the L's footprint picks the extrusion — the slot's own predicate, nearest first
        ed.doc.elements.filter { it.kind == ElementKind.SOLID && ed.doc.nameOf(it) != "e13" }.forEach { it.visible = false }
        val at = ed.camera.worldToScreen(Vec2(-26.875, 0.0))
        ed.pointerDown(at)
        ed.pointerUp(at)
        assertFalse(ed.replacingInput, ed.statusHint)
        assertEquals(listOf("e13"), saved(ed.doc).features.first { it.str("id") == "f12" }.ids("els"), ed.statusHint)
    }

    /** Deleting with reroute is refused by name where a reader has nothing of its kind to take instead. */
    @Test
    fun aRerouteWithNothingToTakeInsteadIsRefusedByName() {
        val ed = Editor(DocumentFormat.load(ISSUE36))
        ed.checkpoint()
        val before = DocumentFormat.save(ed.doc)
        // the extrusion reads a curve; the fillet reads the extrusion as a solid — no input of the extrusion is one
        ed.selectElement(named(ed.doc, "e13"))
        assertFalse(ed.deleteSelectionRerouting())
        assertTrue("f11" in ed.statusHint && "e13" in ed.statusHint, ed.statusHint)
        assertEquals(before, DocumentFormat.save(ed.doc))
        assertNotNull(Tools.byId(Tools.BLEND_EDGE))
    }
}
