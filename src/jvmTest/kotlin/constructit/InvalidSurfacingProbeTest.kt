package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **The probe review of the validity surfacing** — the one claim the delivery's own suite exercises only one
 * level deep: that a failure is announced at its **root**, not at whichever dependent happens to hide with it.
 *
 * The user's pillar is a three-storey dependency: the extrude carries the section inputs, the section inputs
 * carry the foundation profile, and the profile carries the sweep. Failing the *extrude* (its depth parameter
 * driven negative) takes the whole house down — and the announcement must name the extrude with the extrude's
 * own reason, count the rest, and flag every dependent as cascade rather than as its own failure; the heal must
 * speak the same shape. A surfacing that led with "e32 can't be built" here would be truthful and useless.
 */
class InvalidSurfacingProbeTest {
    private fun named(
        ed: Editor,
        name: String,
    ): Element = assertNotNull(ed.doc.elements.firstOrNull { ed.doc.nameOf(it) == name }, "the drawing has $name")

    private fun whyInvalid(el: Element): String? = (Evaluator().eval(el.ref.node) as? EvalResult.Invalid)?.reason

    @Test
    fun aFailureIsAnnouncedAtItsRootAndTheCascadeIsCounted() {
        val ed = Editor()
        ed.replaceDocument(DocumentFormat.load(EmbeddingDirectionProbeTest.TALLER_CIT))
        assertTrue(ed.invalidElements.isEmpty(), "the drawing arrives whole")

        // drive the pillar's own depth negative: the extrude fails, and everything standing on it hides
        val r = ed.doc.scalars.single { it.name == "r" }
        assertTrue(ed.setParameter(r, -50.0), "the edit itself is legal — the value is what's wrong (OP-3)")

        val listed = ed.invalidElements
        assertTrue(listed.size > 3, "the whole house came down, not one storey: ${listed.map { it.name }}")
        val root = listed.first()
        assertEquals("e9", root.name, "the extrude is announced first — the failure, not a casualty")
        assertTrue(root.own, "…and it is where the failure lives")
        for (other in listed.drop(1)) {
            assertTrue(!other.own, "${other.name} is hidden by the cascade, not broken itself")
        }
        assertTrue(listed.any { it.name == "e32" }, "the sweep, three storeys up, is among the hidden")

        val note = assertNotNull(ed.validityNote, "the house came down — so something must say why")
        // the line names the one element that FAILED and nothing else: counting the casualties would dress
        // one failure up as many, and pointing at a dependent would send the user to the wrong element —
        // the panel is where the hidden ones are listed, each flagged as cascade
        assertEquals(
            "e9 can't be built right now: ${whyInvalid(named(ed, "e9"))}",
            note,
            "the line is the root and its own reason, whole and alone",
        )

        // the heal speaks the same lead — and counts everything that came back, because that is true of all
        assertTrue(ed.setParameter(r, 200.0), "typing the depth back is just as legal")
        val healed = assertNotNull(ed.validityNote, "the house is back — so something must say so")
        assertTrue(healed.startsWith("e9 is a solid again"), "the heal leads with the root too: $healed")
        assertTrue(Regex(" — and \\d+ more$").containsMatchIn(healed), "…and counts what returned with it: $healed")
        assertTrue(ed.invalidElements.isEmpty(), "nothing is left unbuildable")
        assertNull(whyInvalid(named(ed, "e32")), "…the sweep included")
    }
}
