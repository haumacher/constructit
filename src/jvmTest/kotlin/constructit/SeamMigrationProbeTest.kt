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
 * **The probe review of the seam migration** — the version bump composed with the machinery that replays.
 *
 * A migrated file does not just load: every undo **replays its journal**, and the journal a v2 file leaves in
 * memory is the *migrated* one, walked back through the undo baseline that was itself reworked two packages
 * ago. So the probe loads the user's own v2 drawing, watches it migrate to a v3 fixed point, then works on it
 * and walks the history both ways — the swept body must ride its recorded crossing identically at every stop,
 * pinned by its extent rather than by mere validity.
 */
class SeamMigrationProbeTest {
    private fun whyInvalid(el: Element): String? = (Evaluator().eval(el.ref.node) as? EvalResult.Invalid)?.reason

    @Suppress("UNCHECKED_CAST")
    private fun extentOf(el: Element): List<Double> {
        val m = Evaluator().solid(el.ref as SolidRef).mesh
        return listOf(
            m.vertices.minOf { it.x },
            m.vertices.maxOf { it.x },
            m.vertices.minOf { it.y },
            m.vertices.maxOf { it.y },
            m.vertices.minOf { it.z },
            m.vertices.maxOf { it.z },
        )
    }

    @Test
    fun theMigratedDrawingSurvivesItsOwnHistory() {
        assertTrue(EmbeddingDirectionProbeTest.TALLER_CIT.startsWith("constructit 2\n"), "the fixture is a v2 file")
        val ed = Editor(DocumentFormat.load(EmbeddingDirectionProbeTest.TALLER_CIT))
        val doc = ed.doc
        val sweep = doc.elements.last { it.kind == ElementKind.SOLID }
        assertNull(whyInvalid(sweep), "the v2 drawing arrives riding its crossing")
        val home = extentOf(sweep)

        // the migration is a fixed point from its first save, and it saves at the new version
        val v3 = DocumentFormat.save(doc)
        assertTrue(v3.startsWith("constructit 3\n"), "…and re-saves at the version that can say what it means")
        assertEquals(v3, DocumentFormat.save(DocumentFormat.load(v3)), "a migrated file is a fixed point")

        // work on it, then walk the history both ways — every stop rides the same crossing
        ed.setTool(Tools.POINT)
        val s = ed.camera.worldToScreen(Vec2(150.0, 150.0))
        ed.pointerMove(s)
        ed.pointerDown(s)
        ed.pointerUp(s)
        assertEquals(home, extentOf(ed.doc.elements.last { it.kind == ElementKind.SOLID }), "after the gesture")
        ed.undo()
        assertEquals(home, extentOf(ed.doc.elements.last { it.kind == ElementKind.SOLID }), "after the undo's replay")
        ed.redo()
        assertEquals(home, extentOf(ed.doc.elements.last { it.kind == ElementKind.SOLID }), "after the redo's replay")
        ed.undo()
        val back = ed.doc
        assertNotNull(back.elements.lastOrNull { it.kind == ElementKind.SOLID }, "the drawing is whole")
        assertEquals(home, extentOf(back.elements.last { it.kind == ElementKind.SOLID }), "…and still where it stood")

        // and what the walked-back document saves is the migrated fixed point again
        assertEquals(v3, DocumentFormat.save(back), "history walked both ways lands on the same file")
    }
}
