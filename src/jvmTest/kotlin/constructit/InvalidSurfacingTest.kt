package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.PointValue
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.dsl.valueOf
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.HitTest
import constructit.editor.Tools
import constructit.geom.Vec2
import constructit.units.mm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **A node that goes invalid under a live edit says so** (OP-3 × *refusals speak*, session 60).
 *
 * The defect, in the reporter's own words: *"if I then modify the swept outline in some way, the 3D solid
 * vanishes and re-occurs, if I change parameters further. However, I do not understand, why a solid cannot be
 * drawn in this situation."* The reason existed the whole time — the sweep's node was `Invalid` with a
 * sentence naming the stations, the clearance and the two cures — but nothing on the **live-edit** path spoke
 * it: the describe routes read it on demand, while a drag or a panel edit that made a body invalid simply
 * stopped drawing it.
 *
 * What is asserted here is the whole episode on the user's own drawing, and the shape of the surfacing:
 *
 * - the edit is **never refused** (OP-3: the gesture is legal, the *value* is what is wrong) and nothing is
 *   modal — the drag completes, the document holds the new position, undo can take it back;
 * - the transition is **spoken live**, on the pointer frame it happens, in the node's own words;
 * - the fact **stands** while it is true, so a user who edits three more things still finds it, and each
 *   element is listed with its reason ([Editor.invalidElements]) for the panel to show;
 * - healing speaks too, and a *load* states rather than announces — a file that arrives unbuildable did not
 *   just turn that way under the user's hand.
 */
class InvalidSurfacingTest {
    private fun named(
        ed: Editor,
        name: String,
    ): Element = assertNotNull(ed.doc.elements.firstOrNull { ed.doc.nameOf(it) == name }, "element $name")

    private fun posOf(el: Element): Vec2 = (Evaluator().valueOf(el.ref) as PointValue).p

    private fun whyInvalid(el: Element): String? = (Evaluator().eval(el.ref.node) as? EvalResult.Invalid)?.reason

    private fun Editor.dragTo(
        from: Vec2,
        to: Vec2,
    ) {
        pointerDown(camera.worldToScreen(from))
        pointerMove(camera.worldToScreen(to))
        pointerUp(camera.worldToScreen(to))
    }

    /** The pillar, loaded and ready: the sweep builds, and nothing is being said about validity. */
    private fun pillar(): Editor {
        val ed = Editor()
        ed.replaceDocument(DocumentFormat.load(TALLER_CIT))
        assertNull(whyInvalid(named(ed, "e32")), "the drawing arrives as a body")
        assertNull(ed.validityNote, "…so there is nothing to say about validity")
        assertTrue(ed.invalidElements.isEmpty(), "…and nothing is listed as unbuildable")
        return ed
    }

    /** Widen the foundation profile **inward**, which is the edit that folds the ring (see the class note). */
    private fun foldIt(ed: Editor) {
        val e18 = named(ed, "e18")
        ed.dragTo(posOf(e18), posOf(e18) - Vec2(65.0, 0.0))
    }

    // ---- 1. the user's edit: the body goes, and the reason arrives with it ----

    /**
     * **The report, cured.** Dragging the profile's free corner inward past its neighbour widens the section
     * across the run until the closed border cannot carry it — the very refusal OP-26's directional criterion
     * states — and the status line now names the solid and repeats the node's sentence, cures included.
     *
     * The edit itself stands: the point is where the drag left it, the solid is still an element of the
     * drawing (its definition is retained, OP-3), and the drag's own note is untouched in [Editor.statusHint]
     * — the two are separate channels precisely so neither can silence the other.
     */
    @Test
    fun theUsersEditThatHidesTheBodyNamesItAndTheReason() {
        val ed = pillar()
        val before = posOf(named(ed, "e18"))
        val elementsBefore = ed.doc.elements.size
        foldIt(ed)

        val note = assertNotNull(ed.validityNote, "the body went — so something must say why")
        assertTrue(note.startsWith("e32 can't be built right now: "), "it names the solid, as the file names it: $note")
        assertTrue(note.contains("cut into itself"), "…and repeats the node's own reason: $note")
        assertTrue(note.contains("thin the section, or open the run out"), "…the way out included: $note")
        assertEquals(whyInvalid(named(ed, "e32")), note.substringAfter("right now: "), "verbatim, not paraphrased")

        // the same fact, per element, for whoever lists it (the panel's rows)
        val listed = assertNotNull(ed.invalidElements.singleOrNull(), "one element cannot be built: ${ed.invalidElements.map { it.name }}")
        assertEquals("e32", listed.name)
        assertTrue(listed.own, "and the sweep is where it failed, not a cascade from something else")
        assertEquals(ElementKind.SOLID, listed.element.kind)

        // …and the edit was legal all along: nothing refused, nothing taken back, nothing modal
        assertTrue((posOf(named(ed, "e18")) - before).length() > 60.0, "the point went where it was dragged")
        assertEquals(elementsBefore, ed.doc.elements.size, "the solid keeps its definition (OP-3), so nothing was removed")
        assertFalse(ed.statusHint.contains("can't be built"), "the drag's own note is untouched: ${ed.statusHint}")
        assertTrue(ed.statusLine.contains(ed.statusHint) && ed.statusLine.contains(note), "the line says both: ${ed.statusLine}")
        assertTrue(ed.canUndo, "the edit is an ordinary operation and can be taken back")
    }

    // ---- 2. the fact stands while the user edits on ----

    /**
     * **Three edits later it is still there.** The reporter's complaint was not only that the body vanished
     * but that they could not find out *why* — so the note is not a flash: it is the standing state of the
     * drawing, and it stays word for word while further edits leave the sweep unbuildable.
     */
    @Test
    fun theFactStaysFindableWhileTheUserEditsOnward() {
        val ed = pillar()
        foldIt(ed)
        val note = assertNotNull(ed.validityNote)

        // two more edits that have nothing to do with the sweep's clearance
        val e22 = named(ed, "e22")
        ed.dragTo(posOf(e22), posOf(e22) + Vec2(0.0, 4.0))
        val e20 = named(ed, "e20")
        ed.dragTo(posOf(e20), posOf(e20) + Vec2(0.0, 3.0))

        val still = assertNotNull(ed.validityNote, "the reason still stands")
        assertTrue(still.startsWith("e32 can't be built right now: "), "…about the same solid, in the same voice: $still")
        assertTrue(still.contains("cut into itself"), still)
        // its *numbers* are live — the second edit moved the profile, so the clearance it quotes moved with
        // it, which is the truth and not a new event (see [Editor.speakValidity]'s "newly" rule)
        assertEquals(note.substringBefore("the profile's reach"), still.substringBefore("the profile's reach"))
        val listed = assertNotNull(ed.invalidElements.singleOrNull(), "and it is still listed on its own")
        assertEquals("e32", listed.name)
        assertTrue(listed.reason.contains("cut into itself"), listed.reason)
    }

    // ---- 3. healing speaks too ----

    /**
     * **Dragging it back says the body is back** — OP-3's healing half, in the same voice. The sentence is a
     * transient (there is nothing standing to show once everything builds), and it outlives the pointer frame
     * it happened on: it is still there after the release, and it goes when the next operation speaks.
     */
    @Test
    fun editingBackHealsAndSaysSo() {
        val ed = pillar()
        val home = posOf(named(ed, "e18"))
        foldIt(ed)
        assertNotNull(ed.validityNote)

        ed.dragTo(posOf(named(ed, "e18")), home)
        assertEquals("e32 is a solid again", ed.validityNote, "the heal is spoken in the house's own words")
        assertTrue(ed.invalidElements.isEmpty(), "…and nothing is listed as unbuildable any more")

        val solid = named(ed, "e32")
        assertNull(whyInvalid(solid), "the node really is valid again")
        @Suppress("UNCHECKED_CAST")
        assertManifold(Evaluator().solid(solid.ref as SolidRef).mesh, "the healed pillar")

        // it survives until something else has something to say, and then it goes
        ed.setTool(Tools.POINT)
        assertNull(ed.validityNote, "a new operation's own words replace it")
    }

    // ---- 4. live, on the frame it happens, and once ----

    /**
     * **The transition is visible during the drag, not only on release.** The note appears on the pointer
     * frame the ring folds and then stays put for every later frame — it is not re-announced per move, and it
     * never leaks into [Editor.statusHint], which is what a drag's own note occupies.
     */
    @Test
    fun theReasonAppearsOnTheFrameTheDragCrossesIt() {
        val ed = pillar()
        val from = posOf(named(ed, "e18"))
        ed.pointerDown(ed.camera.worldToScreen(from))
        val notes = ArrayList<String?>()
        for (step in 1..13) {
            ed.pointerMove(ed.camera.worldToScreen(from - Vec2(step * 5.0, 0.0)))
            notes.add(ed.validityNote)
            assertFalse(ed.statusHint.contains("can't be built"), "the channels stay apart mid-drag: ${ed.statusHint}")
        }
        ed.pointerUp(ed.camera.worldToScreen(from - Vec2(65.0, 0.0)))

        val spoken = notes.filterNotNull()
        assertTrue(spoken.isNotEmpty(), "the fold was reached during the drag")
        assertTrue(notes.first() == null, "…and not at the first millimetre: ${notes.first()}")
        assertTrue(notes.dropWhile { it == null }.all { it != null }, "once it folds it stays folded: $notes")
        for (n in spoken) {
            assertEquals(1, n.lines().size, "one line, every frame: $n")
            assertTrue(n.startsWith("e32 can't be built right now: "), "one situation, one subject: $n")
        }
        // the numbers *do* move with the pointer, which is the drawing telling the truth as it is dragged —
        // what must not move is the situation being reported, and it does not
        assertEquals(spoken.last(), ed.validityNote, "and the release leaves the final situation stated")
        assertEquals(whyInvalid(named(ed, "e32")), ed.validityNote?.substringAfter("right now: "), "verbatim at the end too")
    }

    // ---- 5. the panel's parameter route says the same thing ----

    /**
     * **A number typed into the parameters panel speaks exactly as a drag does** (OP-7 × OP-13). Two circles
     * sharing one radius parameter — sharing a node *is* equality — meet while it is large enough and stop
     * meeting when it is not, which is OP-3's oldest example of a value going away.
     */
    @Test
    fun aParameterEditSpeaksTheSameWay() {
        val ed = meetingCircles(1)
        val meeting = ed.doc.elements.filter { it.kind == ElementKind.DERIVED_POINT }
        val r = ed.doc.scalars.single { it.name == "r" }

        assertTrue(ed.setParameter(r, 30.0), "the panel edit is taken")
        val note = assertNotNull(ed.validityNote, "the points went — so it must say why")
        val first = ed.invalidElements.first()
        assertTrue(note.startsWith("${first.name} can't be built right now: "), "it names the point: $note")
        assertEquals(whyInvalid(first.element), note.substringAfter("right now: ").substringBefore(" — and "), "in the node's own words")
        assertEquals(meeting.map { ed.doc.nameOf(it) }, ed.invalidElements.map { it.name }, "and every one is listed for the panel")

        assertTrue(ed.setParameter(r, 60.0), "…and typing it back is just as legal")
        assertEquals(
            "${ed.doc.nameOf(meeting.first())} is a derived point again — and ${meeting.size - 1} more",
            ed.validityNote,
            "the heal speaks, and counts the rest exactly as the refusal does",
        )
    }

    // ---- 6. several at once: one line, and a count ----

    /**
     * **Two things flipping in one edit are one line.** The first is named with its reason and the rest are
     * counted — a status line that grew with the drawing would be a wall of text nobody reads, and the panel
     * is where every one of them is listed.
     */
    @Test
    fun twoElementsFlippingInOneEditAreOneLineAndACount() {
        val ed = meetingCircles(2)
        val r = ed.doc.scalars.single { it.name == "r" }
        assertTrue(ed.setParameter(r, 30.0))

        val gone = ed.invalidElements
        assertTrue(gone.size >= 3, "several elements went at once: ${gone.map { it.name }}")
        val note = assertNotNull(ed.validityNote)
        assertEquals(1, note.lines().size, "one line, however many went: $note")
        assertTrue(note.endsWith(" — and ${gone.size - 1} more"), "the first by name, the rest counted: $note")
        assertTrue(note.startsWith("${gone.first().name} can't be built right now: "), note)
        assertTrue(note.length < 200, "…and it stays a line, not a wall of text: ${note.length} characters")
    }

    // ---- 7. a load states, it does not announce ----

    /**
     * **A file that arrives unbuildable is stated, never announced.** Nothing turned invalid under the user's
     * hand, so the transition voice would be a lie — and it would also talk over what the *load* itself had to
     * say ([Document.loadNotes], OP-18). The standing note is there from the first frame, the element is
     * listed with its reason, and [Editor.statusHint] belongs entirely to the load.
     */
    @Test
    fun aDrawingThatArrivesUnbuildableIsStatedNotAnnounced() {
        val folded = pillar().also { foldIt(it) }
        val text = DocumentFormat.save(folded.doc)

        val ed = Editor()
        ed.replaceDocument(DocumentFormat.load(text))
        assertEquals(text, DocumentFormat.save(ed.doc), "save -> load -> save is byte-equal, unbuildable and all")
        assertEquals("", ed.statusHint, "the load's own channel is untouched (this file has no migration note)")
        val note = assertNotNull(ed.validityNote, "…and the fact is on screen from the first frame")
        assertTrue(note.startsWith("e32 can't be built right now: "), note)
        assertEquals("e32", assertNotNull(ed.invalidElements.singleOrNull()).name)

        // an unrelated edit afterwards must not re-announce what the file already said
        val e22 = named(ed, "e22")
        ed.dragTo(posOf(e22), posOf(e22) + Vec2(0.0, 4.0))
        val after = assertNotNull(ed.validityNote, "still stated")
        assertEquals(note.substringBefore("the profile's reach"), after.substringBefore("the profile's reach"), "and said once, about the same body")
    }

    // ---- 8. undo, which is an edit like any other ----

    /**
     * **An undo that brings the body back says so.** The diff is keyed on the drawing's *name* for an element
     * rather than on object identity, precisely because undo replays the saved script into a fresh document
     * (OP-18) — identities do not survive that and names do. The alternative, staying silent because "undo
     * already says Undone", was rejected: *what* came back is exactly the question the reporter asked.
     */
    @Test
    fun undoOfTheEditThatHidItSaysItIsBack() {
        val ed = pillar()
        foldIt(ed)
        assertNotNull(ed.validityNote)

        assertTrue(ed.undo(), "the drag was one operation and undo takes it back")
        assertEquals("Undone", ed.statusHint, "undo keeps its own word")
        assertEquals("e32 is a solid again", ed.validityNote, "…and the validity channel says what came back")
        assertEquals("Undone · e32 is a solid again", ed.statusLine, "the status bar shows both")
        assertTrue(ed.invalidElements.isEmpty())
        assertNull(whyInvalid(named(ed, "e32")))
    }

    // ---- 9. why the panel has to be the answer ----

    /**
     * **An invalid solid has nothing left to click.** Its footprint is a value it no longer has, so no canvas
     * pick can reach it in either view — which is exactly why the element list must keep its row rather than
     * drop it, and why the row carries the reason. Drawing a phantom footprint for a value that does not exist
     * was rejected as the opposite of OP-3: what is hidden is hidden, and what is flagged is the *element*.
     */
    @Test
    fun anInvalidSolidIsUnpickableAndThereforeKeepsItsPanelRow() {
        val ed = pillar()
        foldIt(ed)
        val solid = named(ed, "e32")

        val everywhere = HitTest.within(ed.doc, Evaluator(), Vec2(-1000.0, -1000.0), Vec2(1000.0, 1000.0))
        assertFalse(everywhere.any { it === solid }, "the canvas has nothing of it left to pick")
        assertTrue(ed.doc.listedElements().any { it === solid }, "…so the panel keeps its row — the one thing left to ask")
        assertEquals("e32", assertNotNull(ed.invalidElements.singleOrNull()).name, "…with the reason attached to it")
    }

    // ---- 10. gone is not healed ----

    /**
     * **Deleting an unbuildable element is not it healing.** Both take its name off the list, and only one of
     * them means the drawing has it back — so the heal is spoken about an element that is *there*, and a
     * delete is left to say what a delete says (OP-18: the step and its dependents).
     */
    @Test
    fun deletingAnUnbuildableElementIsNotAHeal() {
        val ed = pillar()
        foldIt(ed)
        val solid = named(ed, "e32")
        assertNotNull(ed.validityNote)

        // selected from the panel's own row, which is the only route left to it (see the test above)
        ed.selectElement(solid)
        assertTrue(ed.deleteSelection(), "the sweep can be deleted like anything else: ${ed.statusHint}")
        assertTrue(ed.statusHint.startsWith("Deleted"), "the delete says what it did: ${ed.statusHint}")
        assertNull(ed.validityNote, "…and nothing claims the body came back")
        assertTrue(ed.invalidElements.isEmpty())
    }

    // ---- 11. a heal nobody asked for ----

    /**
     * **Something outside the graph can heal a node too, and the line must follow.** The standing case is the
     * general boolean engine (OP-9): it is WASM, it arrives after the first paint, and until it does a
     * boolean is an ordinary invalid node carrying that as its reason. No gesture heals it, so no change seam
     * runs — `Editor.revalidate` is the seam for exactly that, and the shell calls it where the engine reports
     * itself. Simulated here by mending the drawing behind the controller's back, which is the same shape.
     */
    @Test
    fun aHealFromOutsideTheGesturesStillSpeaks() {
        val ed = meetingCircles(1)
        val r = ed.doc.scalars.single { it.name == "r" }
        assertTrue(ed.setParameter(r, 30.0))
        assertNotNull(ed.validityNote, "they no longer meet")

        // the document mended with no gesture at all — no change seam runs, so nothing has spoken yet
        ed.doc.setParameter(r, 60.0.mm)
        assertTrue(assertNotNull(ed.validityNote).contains("can't be built"), "the note is still the old truth")

        ed.revalidate()
        assertTrue(assertNotNull(ed.validityNote).contains("again"), "…and the seam catches up: ${ed.validityNote}")
        assertTrue(ed.invalidElements.isEmpty())
    }

    /**
     * [pairs] pairs of circles, each pair meeting in one recorded intersection, **all four circles driven by
     * one radius parameter `r`** — so a single panel edit takes every intersection away at once.
     */
    private fun meetingCircles(pairs: Int): Editor {
        val ed = Editor()
        val r = ed.doc.newParameter("r", 60.0.mm)
        for (i in 0 until pairs) {
            val y = i * 400.0
            for (x in listOf(0.0, 100.0)) {
                ed.activeScalar = r
                ed.setTool(Tools.CIRCLE_R)
                ed.click(Vec2(x, y))
            }
            val two = ed.doc.elements.filter { it.kind == ElementKind.CIRCLE }.takeLast(2)
            ed.setTool(Tools.INTERSECT)
            for (c in two) ed.click(pointOn(c))
        }
        ed.checkpoint()
        assertTrue(ed.doc.elements.count { it.kind == ElementKind.DERIVED_POINT } >= pairs, "the intersections were built")
        assertTrue(ed.invalidElements.isEmpty(), "they all meet at 60 mm")
        return ed
    }

    /** A point **on** circle [c]'s outline — clicking its centre would pick the centre point instead. */
    private fun pointOn(c: Element): Vec2 {
        val circle = (Evaluator().valueOf(c.ref) as constructit.core.CircleValue).circle
        return Vec2(circle.center.x + circle.radius, circle.center.y)
    }

    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerMove(s)
        pointerDown(s)
        pointerUp(s)
    }

    companion object {
        /**
         * **The user's own drawing** — session 59's second report, the pillar whose foundation profile was
         * edited taller (`EmbeddingDirectionProbeTest.TALLER_CIT`, verbatim). It builds; what it is here for
         * is the *edit* that folds it.
         */
        val TALLER_CIT =
            """
            constructit 2
            orthostart -36,21.680818540354267 -> e1
            orthovertex 15.395828291524595,21.680818540354267 -> e2,e3
            orthovertex 15.395828291524595,-23.5 -> e4,e5
            orthovertex -36,-23.5 -> e6,e7
            orthoclose -> e8
            param "r" = 200mm
            tool extrude els=e7 clicks=-6.75,-23.5 scalar="r" -> e9
            tool perpbis pts=e1,e2 clicks=-35,32.25;22.25,32.25 -> e10
            param "angle" = 90deg
            sketchspace "plane1" line=e10 angle="angle"
            sectioninput "plane1" el=e9 edge=3 -> e11
            tool keypoints els=e11 clicks=31.667210440456785,44.143556280587276 -> e12,e13
            sectioninput "plane1" el=e9 edge=4 -> e14
            tool keypoints els=e14 clicks=14.179445350734108,0.032626427406199025 -> e15,e16
            tool line pts=e16,e15 clicks=-24.189233278955943,-0.4893964110929853;30.623164763458416,0.2936378466557912 -> e17
            pointoncurve e17 54.37520391517131,0 dofs=33.16070346923052mm -> e18
            tool segment pts=e15,e18 clicks=31.928221859706376,-0.4893964110929853;54.375203915171305,0.5546492659053834 -> e19
            pointoncurve e11 31.5,24.30668841761826 dofs=-17.880689101491953mm -> e20
            tool perp pts=e18 els=e17 clicks=83.08646003262645,0.032626427406199025;54.636215334420896,0.5546492659053834 -> e21
            pointoncurve e21 54.37520391517131,14.38825448613377 dofs=22.138495364771998mm -> e22
            tool segment pts=e18,e22 clicks=55.68026101141926,-0.7504078303425775;53.592169657422524,14.38825448613377 -> e23
            tool segment pts=e22,e20 clicks=53.592169657422524,14.38825448613377;30.36215334420882,24.567699836867863 -> e24
            tool segment pts=e20,e15 clicks=32.45024469820556,22.479608482871125;32.45024469820556,0.032626427406199025 -> e25
            tool outline els=e25,e24,e23,e19 clicks=32.18923327895597,9.951060358890702;41.585644371941285,19.869494290375204;54.37520391517131,7.194127243066885;42.937601957585656,0 -> e26,e27,e28,e29,e30
            space "plan"
            tool curve3 els=e6,e4,e2,e1,e6 clicks=-35.75,-24.5;21.75,-23.75;22,31.5;-36.25,30.25;-36,-25 -> e31
            space "plane1"
            tool makerel els=e18,e15 clicks=43.18857981128731,0.2107641895467509;31.205913150809764,0.5235088309397611 dofs=11.479884928876253mm
            space "plan"
            name e31 "border"
            space "plane1"
            space "plan"
            space "plane1"
            tool sweep els=e31,e30 clicks=16.52403765212233,3.9251743660926763;28.373922071722976,13.443325346523295 signs=1 dofs=0deg;0deg -> e32
            """.trimIndent() + "\n"
    }
}
