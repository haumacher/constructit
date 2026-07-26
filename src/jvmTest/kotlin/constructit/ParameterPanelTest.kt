package constructit

import constructit.core.CircleValue
import constructit.core.Evaluator
import constructit.dsl.scalar
import constructit.dsl.valueOf
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.PointerButton
import constructit.editor.ScalarEntry
import constructit.editor.Tools
import constructit.geom.Vec2
import constructit.units.mm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The parameters panel as an *operation*, headlessly (OP-7 named values + OP-13 typing-is-dragging): a
 * parameter can be **renamed in place**, and its value is written through one route whose undo
 * granularity is stated rather than implied.
 *
 * Both live in `commonMain`, so the browser shell only routes events into them — which is what makes
 * them testable here at all. The two properties worth pinning down:
 *
 * - **renaming needs nothing from the save format.** Every mention of a scalar in the script is written
 *   as its *current* name (the `param` step's own label, `scalar=`, `wire`, an opening's `width=`) and
 *   load resolves by that name, so one rename restates the whole file consistently and everything the
 *   parameter drove stays wired.
 * - **one undo step per committed change.** A spinner's ticks write the model live (the geometry has to
 *   follow the nudge) but are not operations; the committing event is.
 */
class ParameterPanelTest {
    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerDown(s, PointerButton.PRIMARY)
        pointerUp(s)
    }

    private fun value(
        ed: Editor,
        e: ScalarEntry,
    ): Double = Evaluator().scalar(e.ref).mm

    private fun names(ed: Editor): List<String> = ed.doc.scalars.map { it.name }

    /** A parameter, a circle of that radius, and the tool step wiring the two — the round-trip subject. */
    private fun circleOfR(): Pair<Editor, ScalarEntry> {
        val ed = Editor()
        val r = ed.doc.newParameter("r", 20.0.mm)
        ed.activeScalar = r
        ed.setTool(Tools.CIRCLE_R)
        ed.click(Vec2(0.0, 0.0))
        ed.setTool(Tools.SELECT)
        return ed to r
    }

    private fun radiusOf(ed: Editor): Double {
        val c = ed.doc.elements.first { it.kind == ElementKind.CIRCLE }
        return (Evaluator().valueOf(c.ref) as CircleValue).circle.radius
    }

    // ---- renaming ----

    @Test
    fun renamingUniquifiesExactlyLikeCreatingDoes() {
        val ed = Editor()
        val a = ed.doc.newParameter("r", 20.0.mm)
        val b = ed.doc.newParameter("r", 30.0.mm)
        assertEquals(listOf("r", "r2"), names(ed), "creation already uniquifies")

        assertEquals("width", ed.renameParameter(a, "width"))
        assertEquals("width", a.name)
        assertTrue(ed.statusHint.contains("Renamed r to width"), "got: ${ed.statusHint}")

        assertEquals("width2", ed.renameParameter(b, "width"), "a clash gets a suffix, as creation would")
        assertTrue(ed.statusHint.contains("was taken"), "and it says so: ${ed.statusHint}")
        assertEquals("width2", ed.renameParameter(b, "   "), "a blank field keeps the old name")
        assertEquals("width2", ed.renameParameter(b, "width2"), "and its own name is a no-op, not a fresh suffix")

        // a name is one word and carries no quote: a step's arguments are split on spaces, and a name is
        // written quoted, so either would come back as a different name (or not at all)
        assertEquals("wall-width", ed.renameParameter(b, "  wall width  "))
        assertEquals("it's", ed.renameParameter(b, "it\"s"))
    }

    @Test
    fun aRenamedParameterKeepsWhatItDrivesAndTheFileRoundTripsByteIdentically() {
        val (ed, r) = circleOfR()
        val before = DocumentFormat.save(ed.doc)
        assertTrue(before.contains("param \"r\" = 20mm"), "got:\n$before")
        assertTrue(before.contains("scalar=\"r\""), "the tool step names the parameter: got\n$before")

        assertEquals("radius", ed.renameParameter(r, "radius"))

        val once = DocumentFormat.save(ed.doc)
        assertTrue(once.contains("param \"radius\" = 20mm"), "got:\n$once")
        assertTrue(once.contains("scalar=\"radius\""), "and every reference is restated with it: got\n$once")
        assertFalse(once.contains("\"r\""), "nothing is left under the old name: got\n$once")
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "save -> load -> save must be identical")

        // still wired after the round trip: editing the reloaded parameter still moves the circle
        val reloaded = Editor(DocumentFormat.load(once))
        val entry = reloaded.doc.scalars.single { it.name == "radius" }
        assertTrue(reloaded.setParameter(entry, 35.0))
        assertClose(radiusOf(reloaded), 35.0, msg = "the reloaded tool step consumed the renamed parameter")
    }

    @Test
    fun renamingIsOneUndoStep() {
        val (ed, r) = circleOfR()
        val before = DocumentFormat.save(ed.doc)
        assertEquals("radius", ed.renameParameter(r, "radius"))
        val after = DocumentFormat.save(ed.doc)
        assertTrue(after != before)

        assertTrue(ed.undo())
        assertEquals(before, DocumentFormat.save(ed.doc))
        assertEquals(listOf("r"), names(ed), "one undo puts the name back")
        assertTrue(ed.redo())
        assertEquals(after, DocumentFormat.save(ed.doc))
        assertEquals(listOf("radius"), names(ed))
    }

    /**
     * A scalar can be renamed **exactly when the file names it**. The two kinds it does not name would lose
     * the new name on reload — or, worse, be referred to under a name replay never declares — so they are
     * refused with the reason, not renamed in memory only.
     */
    @Test
    fun onlyAScalarTheFileNamesCanBeRenamed() {
        val ed = Editor()
        val p = ed.doc.newParameter("r", 20.0.mm)
        assertTrue(ed.doc.canRenameParameter(p), "a panel parameter rides its own param step")

        // a measurement (OP-4): its name comes from the step that measures it
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(40.0, 0.0))
        ed.setTool(Tools.DISTANCE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(40.0, 0.0))
        val m = ed.doc.scalars.single { !it.editable }
        assertFalse(ed.doc.canRenameParameter(m))
        assertNull(ed.renameParameter(m, "span"))
        assertEquals("dist", m.name, "the name is untouched")
        assertTrue(ed.statusHint.contains("measurement"), "and says why: ${ed.statusHint}")

        // an opening's own pos/sill/head (OP-21): created *inside* the step that owns them, which records
        // their values but has no place for their names
        ed.activeScalar = p
        ed.setTool(Tools.WALL)
        ed.click(Vec2(0.0, 60.0))
        ed.click(Vec2(200.0, 62.0))
        ed.finishPath()
        ed.activeScalar = ed.doc.newParameter("w", 30.0.mm)
        ed.setTool(Tools.OPENING)
        ed.click(Vec2(100.0, 60.0))
        val pos = ed.doc.scalars.single { it.name == "pos" }
        assertFalse(ed.doc.canRenameParameter(pos))
        assertNull(ed.renameParameter(pos, "door-x"))
        assertEquals("pos", pos.name)
        assertTrue(ed.statusHint.contains("could not be saved"), "got: ${ed.statusHint}")
    }

    /**
     * The restatement of an opening's values must not depend on what those parameters are *called* — it is
     * matched to the step's keys positionally, so the guard above is the only thing standing between a
     * rename and a silently frozen opening. Renaming such an entry directly (bypassing the refusal) still
     * leaves the file restating its position.
     */
    @Test
    fun anOpeningRestatesItsValuesWhateverItsParametersAreCalled() {
        val ed = Editor()
        ed.activeScalar = ed.doc.newParameter("t", 20.0.mm)
        ed.setTool(Tools.WALL)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(200.0, 2.0))
        ed.finishPath()
        ed.activeScalar = ed.doc.newParameter("w", 30.0.mm)
        ed.setTool(Tools.OPENING)
        ed.click(Vec2(100.0, 0.0))

        val pos = ed.doc.scalars.single { it.name == "pos" }
        pos.name = "sill" // the worst case: the name of *another* key of the same step
        ed.doc.setParameter(pos, 120.0.mm)
        val once = DocumentFormat.save(ed.doc)
        assertTrue(once.contains("pos=120mm"), "the position is restated by position, not by name: got\n$once")
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)))
    }

    // ---- writing values: the spinner route (OP-13) ----

    /**
     * The panel's value route, and its **undo granularity**: a native number field fires an event per
     * spinner tick, the geometry follows every tick, and only a *committed* change is an undo step. So the
     * ticks in between leave no trace in the history, exactly as the intermediate positions of a drag do
     * not (a drag checkpoints on release).
     */
    @Test
    fun geometryFollowsEveryTickButOnlyACommittedChangeIsAnUndoStep() {
        val (ed, r) = circleOfR()
        assertClose(radiusOf(ed), 20.0)

        // two ticks, uncommitted: the drawing moves…
        assertTrue(ed.setParameter(r, 21.0, commit = false))
        assertTrue(ed.setParameter(r, 22.0, commit = false))
        assertClose(radiusOf(ed), 22.0, msg = "the geometry follows the nudge live")
        // …and the committing event seals the run as one operation
        assertTrue(ed.setParameter(r, 23.0))
        assertClose(radiusOf(ed), 23.0)

        assertTrue(ed.setParameter(r, 24.0, commit = false))
        assertTrue(ed.setParameter(r, 25.0, commit = false))
        assertTrue(ed.setParameter(r, 26.0))
        assertClose(radiusOf(ed), 26.0)

        // three committed operations: the parameter+circle, then 23, then 26 — the six ticks are not steps
        assertTrue(ed.undo())
        assertClose(value(ed, ed.doc.scalars.single()), 23.0, msg = "one undo drops the whole second run")
        assertTrue(ed.undo())
        assertClose(value(ed, ed.doc.scalars.single()), 20.0)
        assertTrue(ed.undo())
        assertTrue(ed.doc.scalars.isEmpty(), "and the parameter itself was the first step")
        assertFalse(ed.undo())
    }

    /** A wired (bound) parameter is driven by another one, so the panel cannot write it — as it shows. */
    @Test
    fun aWiredParameterRefusesTheWrite() {
        val ed = Editor()
        val a = ed.doc.newParameter("a", 10.0.mm)
        val b = ed.doc.newParameter("b", 20.0.mm)
        assertTrue(ed.doc.wireParameter(b, a))
        assertFalse(ed.setParameter(b, 50.0), "b follows a; the field is disabled for exactly this reason")
        assertClose(value(ed, b), 10.0)
        assertTrue(ed.setParameter(a, 50.0))
        assertClose(value(ed, b), 50.0, msg = "and writing the master moves both")
    }
}
