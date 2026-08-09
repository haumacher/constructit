package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.Vec2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **The probe review of the ghost layer** — the toggle composed with a live tool flow on the user's own
 * rounded-pillar drawing, whose file carries recorded `hide` steps (the construction circle `e25`, hidden the
 * moment it had served its purpose, passes right beside the foundation profile).
 *
 * The delivery proves the ghost in isolation; this asks whether it stays out of the way of real work. With
 * the toggle on, the in-place sweep must complete exactly as it does with the toggle off — a ghost under the
 * cursor must not steal an area click — while a click that finds *only* a ghost refuses by name without
 * consuming the slot; and Show/undo must round-trip through the journal with the toggle a pure spectator.
 */
class ShowHiddenProbeTest {
    private fun Editor.click(at: Vec2) {
        val s = camera.worldToScreen(at)
        pointerMove(s)
        pointerDown(s)
        pointerUp(s)
    }

    private fun whyInvalid(el: Element): String? = (Evaluator().eval(el.ref.node) as? EvalResult.Invalid)?.reason

    @Test
    fun theGhostLayerStaysOutOfTheWayOfRealWork() {
        val ed = Editor()
        ed.replaceDocument(DocumentFormat.load(LiftedRunTest.ROUND_PILLAR_CIT))
        ed.showHidden = true
        val ghosted = ed.ghostElements()
        assertTrue(ghosted.any { it.kind == ElementKind.CIRCLE }, "the file's hidden construction circle ghosts")
        val journalBefore = ed.doc.journal.size
        val savedBefore = DocumentFormat.save(ed.doc)

        // the user's sweep, with the toggle on: the ghost circle passes beside the profile, and must not
        // steal the area click — visible wins
        val solidsBefore = ed.doc.elements.count { it.kind == ElementKind.SOLID }
        ed.setTool(Tools.SWEEP)
        // the rounded border, on its top edge — the bottom carries the fixture's own keypoints, whose
        // magnet would win the click (the probe-trap list's oldest entry)
        ed.click(Vec2(-17.5, 33.0))
        ed.setActiveSpace("plane1")
        ed.setTool(Tools.SWEEP)

        // …but first: a click that finds ONLY the ghost refuses by name and consumes nothing. The circle's
        // left rim (centre (33, 8.86), radius 18.14) stands 18 mm from everything visible.
        ed.click(Vec2(33.0 - 18.143586883130496, 8.860472688863375))
        assertTrue(ed.statusLine.contains("hidden"), "the ghost-only click says what it found: ${ed.statusLine}")
        assertEquals(
            solidsBefore,
            ed.doc.elements.count { it.kind == ElementKind.SOLID },
            "…and built nothing",
        )

        // the area click on the profile's fillet arc, mid-arc — 13.9 mm from the nearest point, and right
        // on the ghost circle's own carrier (the arc was drawn ON the hidden circle): the visible arc wins
        ed.click(Vec2(45.83, 21.69))
        val solid = ed.doc.elements.last { it.kind == ElementKind.SOLID }
        assertTrue(
            ed.doc.elements.count { it.kind == ElementKind.SOLID } == solidsBefore + 1,
            "the sweep completed with the toggle on: ${ed.statusLine}",
        )
        assertNull(whyInvalid(solid), "…and built the foundation: ${ed.statusLine}")
        assertManifold(Evaluator().solid(solid.ref as SolidRef).mesh, "the foundation swept past a ghost")

        // The toggle itself touched nothing — asked of the **file**, against the identical gesture made with
        // the toggle off, rather than of a step count. A cross-space gesture legitimately records two steps
        // (the `space "plane1"` the sweep runs in, and the `tool sweep` itself), so a count is a statement
        // about the sweep and not about the toggle; byte-equality is the statement that was wanted.
        val control = Editor()
        control.replaceDocument(DocumentFormat.load(LiftedRunTest.ROUND_PILLAR_CIT))
        control.setTool(Tools.SWEEP)
        control.click(Vec2(-17.5, 33.0))
        control.setActiveSpace("plane1")
        control.click(Vec2(45.83, 21.69))
        assertEquals(journalBefore + 2, control.doc.journal.size, "the space it runs in, and the sweep")
        assertEquals(
            DocumentFormat.save(control.doc),
            DocumentFormat.save(ed.doc),
            "a ghosting session with a refused ghost click writes the very same file as one without",
        )

        // Show the ghost, undo it back: the journal breathes one step each way, the ghost set follows
        val circle = ghosted.first { it.kind == ElementKind.CIRCLE }
        assertEquals(1, ed.doc.setElementsVisible(listOf(circle), true), "Show reaches what the toggle found")
        assertTrue(ed.ghostElements().none { it.kind == ElementKind.CIRCLE }, "shown, so no longer a ghost")
        ed.undo()
        assertTrue(
            ed.ghostElements().any { it.kind == ElementKind.CIRCLE },
            "undo hides it again, and the toggle — pure view state — still shows the ghost",
        )
        assertTrue(ed.showHidden, "…because the toggle survives the undo's document rebuild")

        // and the file never learned the toggle existed
        val savedAfterUndo = DocumentFormat.save(ed.doc)
        assertTrue(savedAfterUndo.contains("tool sweep"), "the sweep's step is in the file")
        assertNotNull(savedBefore, "…")
        assertEquals(
            savedAfterUndo,
            DocumentFormat.save(DocumentFormat.load(savedAfterUndo)),
            "byte-equal round trip with hidden elements and a ghosting session",
        )
    }
}
