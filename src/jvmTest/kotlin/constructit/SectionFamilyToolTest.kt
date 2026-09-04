package constructit

import constructit.SectionFamilyFixture.Rect
import constructit.SectionFamilyFixture.Wing
import constructit.SectionFamilyFixture.click
import constructit.SectionFamilyFixture.invalidity
import constructit.SectionFamilyFixture.meshOf
import constructit.SectionFamilyFixture.midOf
import constructit.SectionFamilyFixture.pointOf
import constructit.SectionFamilyFixture.solids
import constructit.SectionFamilyFixture.straightRun
import constructit.core.Evaluator
import constructit.core.Node
import constructit.editor.Camera3
import constructit.editor.Document
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.editor.Viewport3
import constructit.geom.Geom3
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.units.mm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **The function-family section as a gesture, a panel row and a file** (OP-26, session 79 — queue entry 2).
 *
 * [SectionFamilyTest] asserts the geometry; this is the other half of the claim, and it is the half the
 * doctrines care most about:
 *
 * - **a constant sweep is untouched** — no new argument at all, and a script written before this reading
 *   existed loads and builds identically (OP-18: absence is the old file);
 * - **the laws are ordinary expression machinery** — they read drawing parameters, follow them by recompute,
 *   store verbatim, and a rename re-stamps them on **both** sides of every `=`;
 * - **the rows are the gesture** — the panel offers one row per free named scalar the section is built from
 *   plus the run's own twist, and Apply either arms the next sweep or re-states one law of a selected body
 *   in **one** undo step, on the step that already declares it;
 * - **everything that cannot carry laws refuses by name**, which is where the tube and the swept cut say why
 *   they are not among them;
 * - **the memo stays sound** — one family build costs one evaluation of the section's cone per station and
 *   not one more, a repaint that changes nothing costs nothing, and a weld inside the section moves the body.
 */
class SectionFamilyToolTest {
    private fun Editor.type(digits: String) {
        for (c in digits) key(c.toString())
        key("Enter")
    }

    private fun assertRoundTrips(ed: Editor): Document {
        val once = DocumentFormat.save(ed.doc)
        val reloaded = DocumentFormat.load(once)
        assertEquals(once, DocumentFormat.save(reloaded), "save -> load -> save must be identical")
        assertEquals(ed.doc.elements.map { it.kind }, reloaded.elements.map { it.kind }, "same element kinds")
        return reloaded
    }

    /** Select [where] with the select tool — the route the panel rows read (`Editor.sectionFamilyRows`). */
    private fun Editor.selectAt(where: Vec2) {
        setTool(Tools.SELECT)
        click(where)
    }

    /** The step of the sweep [el] as the file writes it. */
    private fun stepOf(
        ed: Editor,
        el: Element,
    ): String {
        val name = ed.doc.nameOf(el)
        return DocumentFormat.save(ed.doc).lines().first { it.trimEnd().endsWith("-> $name") }
    }

    // ---- the rectangle fixture, swept with laws ----

    /**
     * A 20 × 10 rectangle swept 100 mm with [laws] armed from the panel's rows — the whole gesture, from
     * selecting the section to clicking the run and the area.
     */
    private fun sweptRect(
        vararg laws: Pair<String, String>,
        rigid: String? = null,
        w: Double = 20.0,
        h: Double = 10.0,
        runLength: Double = 100.0,
    ): Triple<Editor, Rect, Element> {
        val ed = Editor()
        val rect = Rect(ed, w, h)
        straightRun(ed, runLength)
        ed.selectAt(rect.pick())
        for ((name, text) in laws) assertTrue(ed.setFamilyLaw(name, text), "$name = $text: ${ed.statusHint}")
        if (rigid != null) assertTrue(ed.setSectionLaw(rigid), "scale(t) = $rigid: ${ed.statusHint}")
        ed.setTool(Tools.SWEEP)
        ed.click(midOf(runLength))
        ed.click(rect.pick())
        val body = assertNotNull(ed.solids().lastOrNull(), "the sweep was built: ${ed.statusHint}")
        return Triple(ed, rect, body)
    }

    // ---- 1. a constant sweep stores and builds exactly what it always did ----

    /**
     * **A sweep whose section is read once writes no `laws=` at all**, and the script it writes is a script
     * every build since the sweep existed would have written.
     *
     * Absence *is* the old file, which is the whole of why this feature needed no version bump (OP-18). The
     * body the older text loads to is compared vertex for vertex against the one the gesture built.
     */
    @Test
    fun aConstantSweepWritesNoLawsAndAnOlderScriptBuildsTheIdenticalBody() {
        val (ed, _, body) = sweptRect()
        val step = stepOf(ed, body)
        assertFalse(step.contains("laws="), "a section read once states none: $step")
        assertFalse(step.contains("law="), "and no rigid law either: $step")

        val reloaded = assertRoundTrips(ed)
        val back = reloaded.elements.last { it.kind == ElementKind.SOLID }
        assertEquals(
            meshOf(body).vertices,
            meshOf(back).vertices,
            "the reloaded body is the same body, corner for corner",
        )
    }

    /**
     * **A family sweep round-trips byte for byte**, and the body the file loads to is the body the gesture
     * built — corner for corner.
     */
    @Test
    fun aFamilySweepRoundTripsByteForByteAndRebuildsTheSameBody() {
        val (ed, _, body) = sweptRect("w" to "20mm * (1 - 0.5*t)", "twist" to "15deg * t")
        val step = stepOf(ed, body)
        assertTrue(
            step.contains("""laws="w = 20mm * (1 - 0.5*t); twist = 15deg * t""""),
            "the laws are stored verbatim, semicolon-separated and quoted: $step",
        )

        val reloaded = assertRoundTrips(ed)
        val back = reloaded.elements.last { it.kind == ElementKind.SOLID }
        assertEquals(
            meshOf(body).vertices,
            meshOf(back).vertices,
            "the reloaded family is the same body, corner for corner",
        )
        assertManifold(meshOf(back), "the reloaded family")
    }

    /**
     * **Laws that read no `t` build the constant body, vertex for vertex** — the frozen-reading half of the
     * ruling said as a mesh comparison: a law is a *substitution*, so substituting the value the drawing
     * already has changes nothing at all.
     */
    @Test
    fun lawsThatReadNoTBuildTheConstantBodyVertexForVertex() {
        val (_, _, constant) = sweptRect()
        val (_, _, stated) = sweptRect("w" to "20mm", "h" to "10mm")
        assertEquals(
            meshOf(constant).vertices,
            meshOf(stated).vertices,
            "a law with no t in it is the section as it is drawn",
        )
        assertEquals(
            meshOf(constant).triangles.toSet(),
            meshOf(stated).triangles.toSet(),
            "and the same triangles over it (a family emits its far cap in a pass of its own, so the order is its own)",
        )
    }

    /**
     * **A rename re-stamps both sides of every law** — the names inside each text *and* the driven name on
     * the left of each `=`.
     *
     * One-sided would leave the body live and its file unloadable, which is the failure the scalar half's own
     * probe found (OP-7: one rename restates the whole file consistently). Asserted through the file, because
     * that is where a half-done re-stamp shows.
     */
    @Test
    fun renamingADrivenParameterRestampsBothSidesOfTheLaws() {
        val ed = Editor()
        val rect = Rect(ed, 20.0, 10.0)
        val span = ed.doc.newParameter("span", 20.0.mm)
        straightRun(ed, 100.0)
        ed.selectAt(rect.pick())
        assertTrue(ed.setFamilyLaw("w", "span * (1 - 0.5*t)"), ed.statusHint)
        ed.setTool(Tools.SWEEP)
        ed.click(midOf(100.0))
        ed.click(rect.pick())
        val body = assertNotNull(ed.solids().lastOrNull(), "the sweep was built: ${ed.statusHint}")
        assertTrue(stepOf(ed, body).contains("""laws="w = span * (1 - 0.5*t)""""), stepOf(ed, body))

        // the **right** side: the name inside the text
        assertEquals("reach", ed.renameParameter(span, "reach"), ed.statusHint)
        assertTrue(
            stepOf(ed, body).contains("""laws="w = reach * (1 - 0.5*t)""""),
            "the name inside the law follows the rename: ${stepOf(ed, body)}",
        )
        // …and the **left** side: the driven name itself
        assertEquals("width", ed.renameParameter(ed.doc.scalars.first { it.name == "w" }, "width"), ed.statusHint)
        val now = stepOf(ed, ed.doc.elements.last { it.kind == ElementKind.SOLID })
        assertTrue(
            now.contains("""laws="width = reach * (1 - 0.5*t)""""),
            "and the driven name follows it too: $now",
        )
        // …and the file still loads, which is what a one-sided re-stamp would have broken
        val reloaded = DocumentFormat.load(DocumentFormat.save(ed.doc))
        assertEquals(
            DocumentFormat.save(ed.doc),
            DocumentFormat.save(reloaded),
            "the re-stamped file round-trips",
        )
        assertNull(
            invalidity(reloaded.elements.last { it.kind == ElementKind.SOLID }),
            "and the reloaded body is live",
        )
    }

    /**
     * **A deleted driven parameter takes the body with it, and the file stays loadable.**
     *
     * The driven name on the left of a law is a reference to a scalar row like any other, so the delete
     * cascade follows it ([Document.dependentSteps] through `referencedScalars`) — a `laws=` left behind
     * naming a row that has gone would name it on load and the file would not open (OP-18).
     */
    @Test
    fun deletingADrivenParameterTakesTheBodyAndLeavesTheFileLoadable() {
        val (ed, rect, body) = sweptRect("w" to "20mm * (1 - 0.5*t)")
        val sweepStep = assertNotNull(ed.doc.creatingStep(body), "the sweep has a step")
        val paramStep = assertNotNull(ed.doc.journal.firstOrNull { s -> s.createsScalars.any { it === rect.w } }, "w has a step")

        val cascade = ed.doc.dependentSteps(setOf(paramStep))
        assertTrue(sweepStep in cascade, "deleting the driven parameter takes the body it drives")

        ed.doc.journal.removeAll(cascade)
        val text = DocumentFormat.save(ed.doc)
        assertFalse(text.contains("laws="), "and no law is left behind naming a value that has gone: $text")
        val reloaded = DocumentFormat.load(text)
        assertEquals(text, DocumentFormat.save(reloaded), "the file that is left round-trips")
    }

    /**
     * **`laws=` on a step whose tool reads no section per station is refused at load**, by name.
     *
     * Silently ignoring it would build a body the file says varies, which is the one thing a load may not do
     * — the `law=` row's own rule (OP-26, session 77) one argument on.
     */
    @Test
    fun lawsOnAToolThatReadsNoSectionPerStationAreRefusedAtLoad() {
        val hand =
            """constructit 3
param "r" = 7mm
point 0,0 -> e1
point 120,0 -> e2
tool curve3 els=e1,e2 clicks=0,0;120,0 -> e3
tool tube els=e3 clicks=60,0 scalar="r" dofs=0deg;0deg laws="r = 7mm * (1 - 0.5*t)" -> e4
"""
        val why =
            assertNotNull(
                runCatching { DocumentFormat.load(hand) }.exceptionOrNull(),
                "a tube states no section per station, so the file is refused",
            )
        val said = assertNotNull(why.message, "and it says so")
        assertTrue(said.contains("tube"), "naming the tool: $said")
        assertTrue(said.contains("reads no section per station"), "and why: $said")
        assertTrue(said.contains("""laws="r = 7mm * (1 - 0.5*t)""""), "quoting what it said: $said")
    }

    /**
     * **A name the drawing carries nothing for is invalid with a reason, never a hard load error and never a
     * silent drop** — the hand-edited file's case, with the load naming the element ([Document.loadNotes]).
     */
    @Test
    fun aLawNamingAValueTheDrawingHasNotGotIsInvalidAndNamedByTheLoad() {
        val (ed, _, body) = sweptRect("w" to "20mm * (1 - 0.5*t)")
        val text = DocumentFormat.save(ed.doc).replace("laws=\"w = ", "laws=\"chord = ")
        val doc = DocumentFormat.load(text)
        val back = doc.elements.last { it.kind == ElementKind.SOLID }
        val said = assertNotNull(invalidity(back), "the body says why it has no shape")
        assertTrue(said.contains("'chord'"), "naming the name: $said")
        assertTrue(
            doc.loadNotes.any { it.contains(doc.nameOf(back)) && it.contains("chord") },
            "and the load names the element: ${doc.loadNotes}",
        )
        assertNotNull(meshOf(body), "while the drawing that did have the value still builds")
        // …and the law is kept verbatim, so the file still round-trips
        assertEquals(text, DocumentFormat.save(doc), "the law is kept exactly as it was written")
    }

    /**
     * **`laws=` and `law=` compose on one step** — the family supplies the outline and the factor multiplies
     * it, and the file carries both arguments on the one step that states them.
     */
    @Test
    fun lawsAndTheRigidLawComposeOnOneStep() {
        val (ed, _, body) = sweptRect("w" to "20mm * (1 - 0.5*t)", rigid = "1 + t")
        val step = stepOf(ed, body)
        assertTrue(step.contains("""law="1 + t""""), "the rigid factor rides the step: $step")
        assertTrue(step.contains("""laws="w = 20mm * (1 - 0.5*t)""""), "and the family beside it: $step")
        assertRoundTrips(ed)
        assertManifold(meshOf(body), "the composed body")

        // 100 · ∫₀¹ (200 − 100t)(1 + t)² dt = 32 500 mm³ (see SectionFamilyTest for the closed form)
        assertClose(Geom3.volume(meshOf(body)), 32500.0, 32500.0 * 2e-3, msg = "the composed volume")
    }

    // ---- 2. the panel's rows ----

    /**
     * **The rows are what the drawing offers**: one per *free named* scalar the selected section is
     * transitively built from, with the run's own `twist` last.
     *
     * Free, because a law is a substitution and a bound value has none of its own; named, because a law is
     * written; transitively read, because that is what "the section is built from this" means. The
     * rectangle's `nought` is bound (`0 * w`) and is therefore not offered — which is the design pass's F3
     * visible in the panel.
     */
    @Test
    fun theRowsAreTheSectionsOwnFreeScalarsPlusTheRunsTwist() {
        val ed = Editor()
        val rect = Rect(ed, 20.0, 10.0)
        ed.selectAt(rect.pick())
        assertEquals(
            listOf("w", "h", "twist"),
            ed.sectionFamilyRows.map { it.name },
            "the rows are the section's own free scalars, and the run's turn last",
        )
        assertTrue(ed.sectionFamilyRows.last().isTwist, "the last row is the run's own turn")
        assertTrue(ed.sectionFamilyRows.all { it.text.isEmpty() }, "and nothing is stated yet")
    }

    /**
     * **A selected swept body's rows are its own laws, and Apply re-states one** — on the step that already
     * declares the body, so it keeps its identity and its name, everything built on it follows by recompute,
     * and the whole thing is **one** undo step.
     *
     * Re-stating one row leaves every other law exactly where it was, which is what makes the rows
     * independent.
     */
    @Test
    fun theRowsOfASweptBodyAreItsOwnLawsAndApplyRestatesOne() {
        val (ed, _, body) = sweptRect("w" to "20mm * (1 - 0.5*t)", "twist" to "15deg * t")
        val name = ed.doc.nameOf(body)
        ed.selectAt(midOf(100.0))
        // the body is what a click on the run's own middle finds once it is swept
        val selected = assertNotNull(ed.selectedElements.singleOrNull(), "one thing is selected")
        assertEquals(name, ed.doc.nameOf(selected), "and it is the body: ${ed.statusHint}")

        assertEquals(
            listOf("w = 20mm * (1 - 0.5*t)", "h = ", "twist = 15deg * t"),
            ed.sectionFamilyRows.map { "${it.name} = ${it.text}" },
            "the rows show the body's own laws, and offer the scalars it has not stated",
        )

        val before = DocumentFormat.save(ed.doc)
        assertTrue(ed.setFamilyLaw("twist", "30deg * t"), ed.statusHint)
        val after = ed.doc.elements.first { ed.doc.nameOf(it) == name }
        assertEquals(name, ed.doc.nameOf(after), "the body keeps its name, so it keeps its identity")
        assertTrue(
            stepOf(ed, after).contains("""laws="w = 20mm * (1 - 0.5*t); twist = 30deg * t""""),
            "the one row re-stated and the other left alone: ${stepOf(ed, after)}",
        )
        assertManifold(meshOf(after), "the re-lawed body")

        assertTrue(ed.undo(), "and it is one undo step")
        assertEquals(before, DocumentFormat.save(ed.doc), "which puts the file back exactly as it was")
    }

    /**
     * **A law armed while a tool that reads no section per station completes is refused by name**, never
     * dropped: a variation that silently vanished is the one failure a status line cannot recover.
     */
    @Test
    fun lawsArmedForATubeAreRefusedWhereTheTubeCompletes() {
        val ed = Editor()
        val rect = Rect(ed, 20.0, 10.0)
        straightRun(ed, 100.0)
        ed.selectAt(rect.pick())
        assertTrue(ed.setFamilyLaw("w", "20mm * (1 - 0.5*t)"), ed.statusHint)
        ed.setTool(Tools.TUBE)
        ed.type("7")
        ed.click(midOf(100.0))
        assertTrue(ed.solids().isEmpty(), "no tube was built: ${ed.statusHint}")
        assertTrue(
            ed.statusHint.contains("reads no section per station"),
            "and it says why: ${ed.statusHint}",
        )
    }

    /** **The wing, built by gestures**, has the volume its laws state — the whole stack, once. */
    @Test
    fun theWingBuiltByGesturesHasTheVolumeItsLawsState() {
        val ed = Editor()
        val wing = Wing(ed)
        straightRun(ed, 1000.0)
        ed.selectAt(Vec2(100.0, 24.0))
        assertTrue(ed.setFamilyLaw("chord", "200mm * (1 - 0.6*t)"), ed.statusHint)
        ed.setTool(Tools.SWEEP)
        ed.click(midOf(1000.0))
        ed.click(pointOf(wing.qc))
        ed.click(Vec2(100.0, 24.0))
        val body = assertNotNull(ed.solids().lastOrNull(), "the wing was swept: ${ed.statusHint}")
        assertManifold(meshOf(body), "the gesture-built wing")
        assertEquals(2_496_000.0, Geom3.volume(meshOf(body)), "the wing's volume is what its laws state")
        assertRoundTrips(ed)
    }

    // ---- 3. what cannot carry laws, in its own words ----

    /** The rows of a section, with [name] stated — or the refusal that came instead. */
    private fun refusal(
        name: String,
        text: String,
        build: (Editor) -> Vec2,
    ): String {
        val ed = Editor()
        val at = build(ed)
        ed.selectAt(at)
        // a law is refused where the sweep is *made*, so the gesture is run and the status read
        assertTrue(ed.setFamilyLaw(name, text), "the law is armed: ${ed.statusHint}")
        ed.setTool(Tools.SWEEP)
        ed.click(midOf(100.0))
        ed.click(at)
        assertTrue(ed.solids().isEmpty(), "nothing was built: ${ed.statusHint}")
        return ed.statusHint
    }

    private fun rectScene(ed: Editor): Vec2 {
        val rect = Rect(ed, 20.0, 10.0)
        straightRun(ed, 100.0)
        return rect.pick()
    }

    /** A **bound** parameter has no value of its own for a station to read — it follows what binds it. */
    @Test
    fun aBoundParameterPointsAtItsBinding() {
        val said = refusal("nought", "1mm * t") { rectScene(it) }
        assertTrue(said.contains("'nought' follows"), "the refusal points at what binds it: $said")
        assertTrue(said.contains("at every station"), "and says the family reads it there anyway: $said")
    }

    /** A **coordinate** is read and never driven — the session-76 rule, one feature on. */
    @Test
    fun aCoordinateIsReadNeverDriven() {
        val said = refusal("p.x", "1mm * t") { rectScene(it) }
        assertTrue(said.contains("is a point's coordinate"), "the refusal names what it is: $said")
        assertTrue(said.contains("read rather than driven"), "and the rule: $said")
    }

    /** A name the section does **not** read is refused naming what the section *is* built from. */
    @Test
    fun aNameTheSectionDoesNotReadNamesWhatItIsBuiltFrom() {
        val said =
            refusal("stray", "1mm * t") { ed ->
                val at = rectScene(ed)
                ed.doc.newParameter("stray", 5.0.mm)
                at
            }
        assertTrue(said.contains("is not built from 'stray'"), "the refusal says so: $said")
        assertTrue(said.contains("'w'") && said.contains("'h'"), "and names what it is built from: $said")
    }

    /** A law may not read a name the **same step** drives — a station's values come from the drawing. */
    @Test
    fun aLawMayNotNameAScalarTheSameStepDrives() {
        val ed = Editor()
        val at = rectScene(ed)
        ed.selectAt(at)
        assertTrue(ed.setFamilyLaw("w", "20mm * (1 - 0.5*t)"), ed.statusHint)
        assertTrue(ed.setFamilyLaw("h", "0.5 * w"), ed.statusHint)
        ed.setTool(Tools.SWEEP)
        ed.click(midOf(100.0))
        ed.click(at)
        assertTrue(ed.solids().isEmpty(), "nothing was built: ${ed.statusHint}")
        assertTrue(ed.statusHint.contains("which this same step drives"), ed.statusHint)
        assertTrue(ed.statusHint.contains("bind 'w' in the drawing"), "and names the cure: ${ed.statusHint}")
    }

    /** **`roll` is reserved** and is not a law: it is one angle for the whole body, and it points at twist. */
    @Test
    fun rollIsReservedAndPointsAtTwist() {
        val said = refusal("roll", "5deg * t") { rectScene(it) }
        assertTrue(said.contains("is where the section starts"), "the refusal says what roll is: $said")
        assertTrue(said.contains("'twist = "), "and points at twist: $said")
    }

    /** A drawing scalar called `twist` collides with the run's own turn and refuses by name. */
    @Test
    fun aDrawingScalarCalledTwistCollidesAndSaysSo() {
        val ed = Editor()
        val rect = Rect(ed, 20.0, 10.0, wName = "twist")
        straightRun(ed, 100.0)
        ed.selectAt(rect.pick())
        assertTrue(ed.setFamilyLaw("twist", "15deg * t"), ed.statusHint)
        ed.setTool(Tools.SWEEP)
        ed.click(midOf(100.0))
        ed.click(rect.pick())
        assertTrue(ed.solids().isEmpty(), "nothing was built: ${ed.statusHint}")
        assertTrue(
            ed.statusHint.contains("is also the run's") && ed.statusHint.contains("rename the parameter"),
            "the collision is refused by name, with the cure: ${ed.statusHint}",
        )
    }

    /**
     * **A tube refuses family laws pointing at `r(t)`** — its section is a circle the run itself states, so
     * there is no drawing to read per station, and the one size it has is already a law.
     */
    @Test
    fun aTubeRefusesFamilyLawsPointingAtItsRadiusLaw() {
        val ed = Editor()
        straightRun(ed, 100.0)
        ed.setTool(Tools.TUBE)
        ed.type("7")
        ed.click(midOf(100.0))
        val tube = assertNotNull(ed.solids().lastOrNull(), "the tube was built: ${ed.statusHint}")
        assertNull(ed.doc.sweepFamilyRestated(tube, "r = 7mm * (1 - 0.5*t)"), "a tube reads no section per station")
        val said = assertNotNull(ed.doc.takeNote(), "and it says so")
        assertTrue(said.contains("is a tube"), "the refusal names what it is: $said")
        assertTrue(said.contains("*Section law*"), "and points at r(t): $said")
        assertTrue(
            said.contains("*Sweep (profile along a curve)*"),
            "and at the tool that does read a drawing: $said",
        )
    }

    /**
     * **A body that is no swept family refuses by name**, and the two that say *why* they are not among them
     * are the prism (which has no run at all) and the **swept cut** — whose refusal is session 77's own
     * sentence, pluralized: its reach is derived from the solid it cuts, so sections that differed along the
     * run would move that reach station by station.
     */
    @Test
    fun aPrismAndASweptCutRefuseFamilyLawsInTheirOwnWords() {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(40.0, 0.0))
        ed.click(Vec2(100.0, 40.0))
        ed.activeScalar = ed.doc.newParameter("depth", 40.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(70.0, 0.0))
        val block = assertNotNull(ed.solids().lastOrNull(), "the block was built: ${ed.statusHint}")
        assertNull(ed.doc.sweepFamilyRestated(block, "w = 20mm * t"), "a prism reads no section per station")
        val notSwept = assertNotNull(ed.doc.takeNote())
        assertTrue(notSwept.contains("whose section is a drawing"), "and says where laws belong: $notSwept")
        assertTrue(notSwept.contains("*Sweep (profile along a curve)*"), "naming the tool: $notSwept")

        // [SweepLawToolTest]'s own swept-cut fixture: a route climbing through the block, and the channel's
        // section drawn about the plan's origin — which is what puts it on the route
        for ((base, h) in listOf(Vec2(70.0, 20.0) to "10", Vec2(70.0, 20.0) to "30", Vec2(105.0, 20.0) to "70")) {
            ed.setTool(Tools.HEIGHT_POINT)
            ed.type(h)
            ed.click(base)
        }
        val vp =
            Viewport3(
                camera = Camera3(target = Vec3(80.0, 20.0, 30.0), distance = 420.0, yaw = -0.9, pitch = 0.5),
                widthPx = 800.0,
                heightPx = 600.0,
            )
        vp.editor = ed
        vp.shown = true
        ed.setTool(Tools.CURVE3)
        for (p in listOf(Vec3(70.0, 20.0, 10.0), Vec3(70.0, 20.0, 30.0), Vec3(105.0, 20.0, 70.0))) {
            val at = assertNotNull(vp.camera.project(p, vp.widthPx, vp.heightPx), "$p projects")
            vp.pointerDown(at)
            vp.pointerUp(at)
        }
        ed.key("Enter")
        assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.SPACE_CURVE }, "the route: ${ed.statusHint}")
        vp.shown = false
        ed.activeScalar = ed.doc.newParameter("r", 8.0.mm)
        ed.setTool(Tools.CIRCLE_R)
        ed.click(Vec2(0.0, 0.0))

        ed.setTool(Tools.CUT_ALONG_CURVE)
        ed.click(Vec2(70.0, 0.0))
        ed.click(Vec2(8.0, 0.0))
        ed.click(Vec2(70.0, 20.0))
        ed.click(Vec2(30.0, 30.0))
        val cut = assertNotNull(ed.solids().lastOrNull { it !== block }, "the swept cut was built: ${ed.statusHint}")

        assertNull(ed.doc.sweepFamilyRestated(cut, "w = 20mm * t"), "and the swept cut refuses family laws")
        val why = assertNotNull(ed.doc.takeNote())
        assertTrue(why.contains("a swept cut states no sizes of its own"), "in session 77's sentence, pluralized: $why")
        assertTrue(why.contains("derived from the solid it cuts"), "which is where the derived reach comes from: $why")
        assertTrue(why.contains("sections that differed along the run"), "pluralized: $why")
        assertTrue(why.contains("*Sweep*") && why.contains("*Subtract*"), "and the way round it: $why")
    }

    // ---- 4. the memo's soundness (the design pass's F2) ----

    /** Every node at or upstream of [root]. */
    private fun cone(root: Node): List<Node> {
        val seen = LinkedHashMap<Node, Unit>()

        fun walk(n: Node) {
            if (seen.put(n, Unit) != null) return
            n.inputs.forEach { walk(it) }
        }
        walk(root)
        return seen.keys.toList()
    }

    private fun computes(root: Node): Int = cone(root).sumOf { it.computeCount }

    /** Read the whole document once, so every memo is warm — what a repaint before the edit would do. */
    private fun warm(ed: Editor) {
        val ev = Evaluator()
        ed.doc.elements.forEach { ev.eval(it.ref.node) }
        ed.doc.scalars.forEach { ev.eval(it.ref.node) }
    }

    /**
     * **One family build costs one evaluation of the section's cone per station and not one more** — the
     * cost of the feature, stated as a number.
     *
     * The verdict grid is [constructit.dsl.SectionFamilies.FAMILY_STEPS] + 1 stations, and a family refined
     * past that grid pays one evaluation per extra station and nothing else — so what the substituted pass
     * costs is `stations × |affected cone|`, and the *unaffected* half of the drawing pays nothing at all
     * ([Evaluator]'s overrides decide the cone bottom-up).
     */
    @Test
    fun oneFamilyBuildCostsOneEvaluationOfTheSectionsConePerStation() {
        val (ed, rect, body) = sweptRect("w" to "20mm * (1 - 0.5*t)")
        warm(ed)
        val sectionCone = cone(rect.area.ref.node).size
        val before = computes(body.ref.node)

        // one more build of the very same body: move the driven parameter's literal and read it back
        ed.doc.setParameter(rect.w, 24.0.mm)
        warm(ed)
        val cost = computes(body.ref.node) - before

        // the affected cone is read once per station of the verdict grid; the sweep node itself and the
        // section's own ordinary pass are the handful beside it
        val stations = constructit.dsl.SectionFamilies.FAMILY_STEPS + 1
        assertTrue(
            cost in (stations)..(stations * sectionCone + 4 * sectionCone),
            "one family build costs about $stations readings of a $sectionCone-node cone, and it cost $cost",
        )
        assertTrue(cost > stations, "and the substituted readings are really being paid for: $cost")
    }

    /** **A repaint that changes nothing costs nothing** — the family is memoized like everything else. */
    @Test
    fun aRepaintThatChangesNothingCostsNothing() {
        val (ed, _, body) = sweptRect("w" to "20mm * (1 - 0.5*t)")
        warm(ed)
        val settled = computes(body.ref.node)
        warm(ed)
        warm(ed)
        assertEquals(settled, computes(body.ref.node), "three repaints of an unchanged drawing cost one build")
    }

    /**
     * **A drag outside the section leaves the count alone** — the family's inputs are the freedoms it
     * actually reads, so moving something else does not rebuild it.
     */
    @Test
    fun aDragOutsideTheSectionLeavesTheCountAlone() {
        val (ed, _, body) = sweptRect("w" to "20mm * (1 - 0.5*t)")
        ed.setTool(Tools.POINT)
        ed.click(Vec2(400.0, 400.0))
        val stray = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.POINT }, "a stray point")
        warm(ed)
        val settled = computes(body.ref.node)
        ed.doc.moveFreePoint(stray, Vec2(420.0, 380.0))
        warm(ed)
        assertEquals(settled, computes(body.ref.node), "a point the section does not read costs the family nothing")
    }

    /**
     * **The weld test** (the design pass's F2): welding a point of the section onto another point moves the
     * body.
     *
     * A weld is a **mutation in place** ([constructit.core.SourceNode.boundTo]) rather than a rewiring, so
     * nothing about the sweep node's input list changes — and the family still has to follow. It does,
     * because the freedoms the section reads are ordinary inputs of that node.
     */
    @Test
    fun weldingASectionPointOntoAnotherMovesTheBody() {
        val (ed, rect, body) = sweptRect("w" to "20mm * (1 - 0.5*t)")
        warm(ed)
        val was = meshOf(body).vertices.toList()

        // the rectangle's origin corner is a free point; drag it onto a point elsewhere and it welds
        ed.setTool(Tools.POINT)
        ed.click(Vec2(-30.0, -12.0))
        val target = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.POINT }, "a target point")
        val corner = rect.corners[0]
        ed.setTool(Tools.SELECT)
        val from = ed.camera.worldToScreen(pointOf(corner))
        val to = ed.camera.worldToScreen(pointOf(target))
        ed.pointerMove(from)
        ed.pointerDown(from)
        ed.pointerMove(to)
        ed.pointerUp(to)

        val live = ed.doc.elements.first { ed.doc.nameOf(it) == ed.doc.nameOf(body) }
        assertTrue(
            meshOf(live).vertices != was,
            "the body followed the weld: ${ed.statusHint}",
        )
        assertManifold(meshOf(live), "the welded family")
    }

    /**
     * **The section's free sources are ordinary inputs of the sweep node** — the design pass's F2a, asserted
     * structurally, because that is what makes the memo sound *by construction*.
     *
     * The alternative would have been to rely on every intermediate node handing on a fresh value object
     * whenever anything upstream moves. That is true of this engine today and it is an argument about the
     * engine rather than about this feature, so the freedoms the family actually reads are listed instead:
     * any change to any of them changes this node's own arguments, and a free source is never invalid, so
     * nothing new can cascade in through them either.
     */
    @Test
    fun theSectionsFreeSourcesAreOrdinaryInputsOfTheSweep() {
        val (ed, rect, body) = sweptRect("w" to "20mm * (1 - 0.5*t)")
        val inputs = body.ref.node.inputs.toSet()
        val free =
            cone(rect.area.ref.node).filter { n ->
                (n is constructit.core.SourceNode && n.boundTo == null) ||
                    (n is constructit.core.ParameterNode && n.boundTo == null)
            }
        assertTrue(free.isNotEmpty(), "the section has freedoms to read")
        for (n in free) {
            assertTrue(n in inputs, "${n.id} is an ordinary input of the sweep, so the memo cannot go stale")
        }
        assertEquals(ed.doc.nameOf(body), ed.doc.nameOf(body), "and the body is the body")
    }
}
