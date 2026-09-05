package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.PointRef
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.editor.Document
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.Picks
import constructit.editor.SlotKind
import constructit.editor.SvgDrawTarget
import constructit.editor.ToolDef
import constructit.editor.Tools
import constructit.geom.Vec2
import constructit.units.Dimension
import constructit.units.Quantity
import constructit.units.mm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **Every element was created by exactly one journal step** (OP-27) — the invariant, and the three places
 * it is now enforced.
 *
 * The defect this comes from: a user built tubes on a helix, and the tubes appeared but could not be
 * deleted ("e20 has no construction step to remove"), were in no saved file, and drew nothing in the 3D
 * view. The radius they had picked in the panel was a **dimensionless** parameter, so the tube tool's own
 * status message — a `Quantity.mm` read (OP-7) — threw *after* the solid had been added and *before*
 * [constructit.editor.Document] could write the step that would have owned it. What was left was an element
 * the journal did not know about: unsaveable, undeletable, invisible to undo.
 *
 * The fix is not the message. A status note that can destroy the construction it describes is one bug; an
 * element that can exist without a step is the *class* of bug, and it is what the user asked to be made
 * impossible. So: a gesture is a transaction (`Editor.transacted`), the tool table is asserted as a whole
 * (below), and a file save refuses by name rather than writing a document without it.
 */
class RecordedElementTest {
    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerMove(s)
        pointerDown(s)
        pointerUp(s)
    }

    private fun Editor.type(digits: String) {
        for (c in digits) key(c.toString())
        key("Enter")
    }

    /** The user's own script, verbatim, as they pasted it — a permanent load test (OP-18's fixture rule). */
    private val usersScript =
        """
        constructit 2
        param "r" = 50mm
        param "pitch" = 20mm
        param "cnt" = 10.5
        point -0.125,14.25 -> e1
        tool helix els=e1 clicks=-0.125,14.25 scalar="r","pitch","cnt" -> e2
        orthostart -0.125,14.25 -> e3
        weldortho e3 e1
        orthovertex 43.375,14.25 -> e4,e5
        wall "cnt" center -> e6
        param "r2" = 5
        """.trimIndent() + "\n"

    // ---- 1. the user's flow, through the real gestures ----

    /**
     * **The gesture that lost the tube now records its step** — helix, a parameter picked in the panel, one
     * click on the coil. The parameter is the one the user actually made: dimensionless, which is what set
     * the whole thing off.
     *
     * What is asserted is the whole of what they lost: a step, a file that round-trips byte for byte, and a
     * delete that reaches it. Plus the two halves of OP-3 that should have happened instead of the crash —
     * the solid is invalid *with the dimension named*, and it **heals** the moment the parameter is given a
     * unit, without the step changing at all.
     */
    @Test
    fun aTubeBuiltWithAParameterOfTheWrongDimensionIsStillRecordedDeletableAndHeals() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.setTool(Tools.HELIX)
        ed.type("50")
        ed.type("20")
        ed.type("10.5")
        ed.click(Vec2(0.0, 0.0))
        assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.SPACE_CURVE }, "the coil: ${ed.statusHint}")

        // the panel's own route into a scalar slot (OP-13), with the dimensionless value the user typed there
        val radius = ed.doc.newParameter("r", Quantity.number(5.0))
        ed.activeScalar = radius
        ed.checkpoint()

        ed.setTool(Tools.TUBE)
        ed.click(Vec2(50.0, 0.0)) // the coil's plan projection is a circle of radius 50 about the axis point
        val tube = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.SOLID }, "the tube: ${ed.statusHint}")

        // the step exists, and it is the tube's
        val step = assertNotNull(ed.doc.creatingStep(tube), "the tube has a construction step")
        assertTrue(step.creates.any { it === tube }, "and that step is the one that made it")
        assertTrue(ed.doc.steplessElements().isEmpty(), "and nothing in the drawing is stepless")

        // the file has it, and says what it was built from and with
        val once = DocumentFormat.save(ed.doc)
        assertTrue(once.lines().any { it.startsWith("tool tube") }, "the tube is in the script: $once")
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "and save -> load -> save is byte-equal")

        // the node's own refusal, which is where a dimension error belongs (OP-7) — named, and not thrown
        val bad = Evaluator().eval(tube.ref.node)
        assertTrue(bad is EvalResult.Invalid, "a dimensionless radius makes the solid invalid, not the editor: $bad")
        assertTrue((bad as EvalResult.Invalid).reason.contains("requires L"), "and it names the dimension: ${bad.reason}")
        assertTrue(ed.statusHint.contains(bad.reason), "and the tool said so rather than claiming a 3D view: ${ed.statusHint}")

        // ...and it heals when the parameter is given a unit — the step is untouched
        ed.doc.setParameter(radius, 5.0.mm)
        assertTrue(Evaluator().eval(tube.ref.node) is EvalResult.Ok, "a radius in millimetres is a tube")
        assertManifold(Evaluator().solid(tube.ref as SolidRef).mesh, "the healed tube")

        // and Delete reaches it, which is the sentence the user could not get
        ed.setTool(Tools.SELECT)
        ed.selectElement(tube) // the elements panel's own route, the one the user was using
        assertEquals(tube, ed.selection, "the tube is selected: ${ed.statusHint}")
        assertTrue(ed.deleteSelection(), "the tube is deletable: ${ed.statusHint}")
        assertEquals(0, ed.doc.elements.count { it.kind == ElementKind.SOLID }, "and it is gone")
        // ...and the coil it rode stays: a delete replays the script into a fresh document, so it is asked
        // of the drawing rather than of the handle from before
        assertEquals(1, ed.doc.elements.count { it.kind == ElementKind.SPACE_CURVE }, "while the coil it rode stays")
    }

    /**
     * **The typed radius is consumed by the step, not left dangling.** In the file the user sent, the
     * parameter they typed sat at the end with nothing reading it — the signature of a scalar that outlived
     * the gesture it was made for.
     */
    @Test
    fun theRadiusPickedForTheTubeIsConsumedByTheStepThatBuiltIt() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.setTool(Tools.HELIX)
        ed.type("50")
        ed.type("20")
        ed.type("10.5")
        ed.click(Vec2(0.0, 0.0))
        ed.setTool(Tools.TUBE)
        ed.type("5")
        ed.click(Vec2(50.0, 0.0))

        val script = DocumentFormat.save(ed.doc)
        val tubeLine = assertNotNull(script.lines().firstOrNull { it.startsWith("tool tube") }, script)
        val radius = assertNotNull(ed.doc.scalars.lastOrNull { it.name.startsWith("radius") }, "the typed radius is a panel row")
        assertTrue(tubeLine.contains("scalar=\"${radius.name}\""), "the step consumes it: $tubeLine")
        // ...and it is not a `param` line the script forgot about: every parameter is read by some later step
        for (entry in ed.doc.scalars) {
            assertTrue(
                script.lines().any { it.contains("scalar=") && it.contains("\"${entry.name}\"") },
                "${entry.name} is an orphan parameter — nothing in the script consumes it:\n$script",
            )
        }
    }

    // ---- 2. the user's own file ----

    /** **Their script loads, draws and behaves as their drawing did** — helix, welded ortho run, wall. */
    @Test
    fun theUsersScriptLoadsDrawsAndRoundTrips() {
        val doc = DocumentFormat.load(usersScript)
        assertEquals(emptyList(), doc.loadNotes, "it needs no migration")
        assertEquals(
            listOf(
                ElementKind.POINT,
                ElementKind.SPACE_CURVE,
                ElementKind.ON_CURVE,
                ElementKind.ON_CURVE,
                ElementKind.SEGMENT,
                ElementKind.AREA,
            ),
            doc.elements.map { it.kind },
            "the coil, the welded ortho run and the wall it carries",
        )
        assertEquals(atThisVersion(usersScript), DocumentFormat.save(doc), "and it is written back exactly as it was sent")

        // the wall's thickness is `cnt`, a *dimensionless* parameter — drawing it must not throw either
        val ed = Editor()
        ed.replaceDocument(doc)
        ed.draw(SvgDrawTarget(), 800.0, 600.0)
        assertTrue(ed.doc.steplessElements().isEmpty(), "nothing in their drawing is stepless")
    }

    /** **A tube on their coil is an ordinary recorded solid**, which is what they were trying to do. */
    @Test
    fun aTubeBuiltOnTheUsersOwnCoilIsRecordedAndSurvivesSaveAndLoad() {
        val ed = Editor()
        ed.replaceDocument(DocumentFormat.load(usersScript))
        val coil = ed.doc.elements.first { it.kind == ElementKind.SPACE_CURVE }

        ed.setTool(Tools.TUBE)
        ed.type("5")
        // the coil's plan projection is a circle of radius 50 about the axis point at (-0.125, 14.25)
        ed.click(Vec2(49.875, 14.25))
        val tube = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.SOLID }, "the tube: ${ed.statusHint}")
        assertNotNull(ed.doc.creatingStep(tube), "it has a step")
        assertTrue(ed.statusHint.contains("tube along ${ed.doc.nameOf(coil)}"), "and it says what it rode: ${ed.statusHint}")
        assertManifold(Evaluator().solid(tube.ref as SolidRef).mesh, "a tube on the user's coil")

        val once = DocumentFormat.save(ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "save -> load -> save is byte-equal")
        assertTrue(ed.undo(), "and one undo takes the tube back")
        assertEquals(0, ed.doc.elements.count { it.kind == ElementKind.SOLID })
    }

    // ---- 3. the audit: the whole tool table, so a future row cannot reintroduce it ----

    /**
     * **No row of the tool table can build an element without recording a step** — driven through the real
     * gesture runner, one fresh drawing per row, over a fixture with something of every kind a slot asks for.
     *
     * This is the test that makes the fix general rather than shaped like the bug report. A row that builds
     * nothing here is no proof of anything, so the count of rows that *did* build is asserted too: the audit
     * cannot quietly become vacuous by the fixture drifting out from under it.
     */
    @Test
    fun everyToolRowThatBuildsAnythingRecordsAStepForIt() {
        var built = 0
        val silent = ArrayList<String>()
        for (def in Tools.all) {
            val ed = fixture()
            val before = Triple(ed.doc.elements.size, ed.doc.scalars.size, ed.doc.journal.size)
            runGenerically(ed, def)
            val stepless = ed.doc.steplessElements()
            assertTrue(
                stepless.isEmpty(),
                "tool ${def.id} left ${stepless.map { ed.doc.nameOf(it) }} with no construction step — " +
                    "OP-27's invariant: an element the journal does not know about is in no file and reachable " +
                    "by no delete. Status: ${ed.statusHint}",
            )
            // "did something" counts a scalar (a measurement is a panel row, not an element) and a step (a
            // plane creates a sketch space and no geometry) — otherwise the floor below would be measuring
            // the fixture's reach rather than the audit's
            val after = Triple(ed.doc.elements.size, ed.doc.scalars.size, ed.doc.journal.size)
            if (after != before) built++ else silent.add(def.id)
        }
        assertTrue(
            built >= 100,
            "only $built of ${Tools.all.size} rows did anything, so this audit proves little — " +
                "the fixture has drifted out from under it. Silent rows: $silent",
        )
    }

    /**
     * **A drawing driven through many gestures never gains a stepless element** — the same invariant asked of
     * a session rather than of a single row, since a tool's inputs being another tool's outputs is where a
     * table-driven check is thinnest.
     */
    @Test
    fun aLongSessionOfComposedGesturesLeavesNothingStepless() {
        val ed = fixture()
        // trace the rectangle into an outline, extrude it, cut it with a chain, thicken a run into a wall
        ed.setTool(Tools.EXTRUDE)
        ed.type("12")
        ed.click(RECT_EDGE)
        ed.setTool(Tools.THICKEN)
        ed.click(SEG_MID)
        ed.key("Enter")
        ed.setTool(Tools.TUBE)
        ed.type("4")
        ed.click(CURVE3_MID)
        ed.setTool(Tools.MIDPOINT)
        ed.click(SEG_MID)
        assertTrue(ed.doc.steplessElements().isEmpty(), "every element of the session has a step: ${ed.statusHint}")
        val once = DocumentFormat.save(ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "and the session round-trips byte for byte")
    }

    // ---- 4. the transaction, at the seam that owns it ----

    /**
     * **A build that fails part-way leaves nothing behind** — the structural half of the fix, asked of
     * [Document.recordingTool] directly, which is the seam every gesture's build goes through.
     *
     * The step can only be written after the body has run (it has to know what was created), so the one
     * route out that is not a return has to take the creations with it. Before this, that route left them.
     */
    @Test
    fun aBuildThatThrowsPartWayLeavesNoElementAndNoStep() {
        val doc = Document()
        val anchor = doc.freePoint(0.0.mm, 0.0.mm).let { ref -> doc.elements.first { it.ref === ref } }
        val elementsBefore = doc.elements.size
        val scalarsBefore = doc.scalars.size
        val stepsBefore = doc.journal.size

        val picks = Picks(emptyList(), emptyList(), Vec2(0.0, 0.0), emptyList())
        val thrown =
            try {
                doc.recordingTool(Tools.TUBE, picks, emptyList()) {
                    doc.newParameter("half", 3.0.mm)
                    doc.freePoint(10.0.mm, 10.0.mm)
                    throw IllegalStateException("the build gave up here")
                }
                null
            } catch (e: IllegalStateException) {
                e
            }
        assertNotNull(thrown, "the failure is not swallowed — a silent no-op would be its own defect")
        assertEquals(elementsBefore, doc.elements.size, "what the failed body created went with it")
        assertEquals(scalarsBefore, doc.scalars.size, "and so did the parameter it made")
        assertEquals(stepsBefore, doc.journal.size, "and no step was written")
        assertTrue(doc.steplessElements().isEmpty(), "so the invariant holds")
        assertTrue(doc.elements.any { it === anchor }, "and what was there before is untouched")
    }

    // ---- 5. the backstop ----

    /**
     * **A document holding an element no step created refuses to save, by name.**
     *
     * The state is built the one way the code still allows: a direct [Document] builder call, which is the
     * internal path replay and the tests use and which no route in the shell can reach. That is the honest
     * boundary — public element creation is always recorded, and this is what stands behind it if a future
     * one is not.
     */
    @Test
    fun aDocumentWithAnElementNoStepCreatedRefusesToSaveAndNamesIt() {
        val doc = Document()
        val a: PointRef = doc.freePoint(0.0.mm, 0.0.mm)
        val b: PointRef = doc.freePoint(40.0.mm, 0.0.mm)
        assertNotNull(DocumentFormat.saveFile(doc).text, "an ordinary drawing saves")

        // the internal path: a builder called outside any recording, which is how a test makes geometry
        val orphan = doc.segment(a, b)
        assertEquals(listOf(orphan), doc.steplessElements(), "the segment belongs to no step")

        val outcome = DocumentFormat.saveFile(doc)
        assertEquals(null, outcome.text, "the file is refused rather than written without it")
        val refusal = assertNotNull(outcome.refusal)
        assertTrue(refusal.contains(doc.nameOf(orphan)), "and the refusal names it: $refusal")
        assertTrue(refusal.contains("construction step"), "and says why: $refusal")

        // ...while the plain save is untouched, because it is also the undo substrate (OP-18) and a snapshot
        // that could refuse would break editing rather than one edit
        assertTrue(DocumentFormat.save(doc).startsWith(DocumentFormat.HEADER), "the snapshot still works")
    }

    // ---- the fixture, and the generic driver the audit runs every row through ----

    private val POINT_A = Vec2(0.0, 0.0)
    private val POINT_B = Vec2(60.0, 0.0)
    private val POINT_C = Vec2(60.0, 45.0)
    private val SEG_MID = Vec2(30.0, 0.0)
    private val SEG2_MID = Vec2(60.0, 22.5)
    private val CIRCLE_CENTRE = Vec2(-70.0, 60.0)
    private val CIRCLE_EDGE = Vec2(-50.0, 60.0)
    private val ELLIPSE_CENTRE = Vec2(-70.0, -60.0)
    private val ELLIPSE_EDGE = Vec2(-40.0, -60.0)
    private val RECT_EDGE = Vec2(150.0, 0.0)
    private val RECT_EDGE2 = Vec2(180.0, 40.0)
    private val RECT2_EDGE = Vec2(230.0, -60.0)
    private val CURVE3_MID = Vec2(30.0, 0.0)

    // the rims of the three sphere loci (OP-28), 40 mm out from POINT_A / POINT_B / POINT_C, each chosen so
    // that the nearest other locus's rim is at least 30 mm further off — a pick that cannot be ambiguous
    private val LOCUS_A_RIM = Vec2(0.0, -40.0)
    private val LOCUS_B_RIM = Vec2(60.0, -40.0)
    private val LOCUS_C_RIM = Vec2(60.0, 85.0)
    private val FREE_SPOT = Vec2(240.0, 120.0)
    private val FREE_SPOT2 = Vec2(270.0, 150.0)
    private val FREE_SPOT3 = Vec2(300.0, 110.0)

    /**
     * A drawing with one of everything a slot kind can ask for, built **by gestures** so every element in it
     * already has a step — the audit is about what a tool adds, never about what it was handed.
     */
    private fun fixture(): Editor {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        for (p in listOf(POINT_A, POINT_B, POINT_C, CIRCLE_CENTRE, CIRCLE_EDGE, ELLIPSE_CENTRE, ELLIPSE_EDGE)) ed.click(p)
        // a two-piece run: enough for the repeating tools to have something to follow
        ed.setTool(Tools.SEGMENT)
        ed.click(POINT_A)
        ed.click(POINT_B)
        ed.click(POINT_B)
        ed.click(POINT_C)
        ed.setTool(Tools.CIRCLE)
        ed.click(CIRCLE_CENTRE)
        ed.click(CIRCLE_EDGE)
        ed.setTool(Tools.ELLIPSE_AB)
        ed.type("15")
        ed.click(ELLIPSE_CENTRE)
        ed.click(ELLIPSE_EDGE)
        ed.setTool(Tools.POINT_ON_CIRCLE)
        ed.click(CIRCLE_EDGE)
        // a closed area, and a solid raised from it
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(120.0, 0.0))
        ed.click(Vec2(180.0, 40.0))
        ed.setTool(Tools.EXTRUDE)
        ed.type("20")
        ed.click(RECT_EDGE)
        // ...and a second one overlapping it, so a boolean row has two distinct operands to work on
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(200.0, -60.0))
        ed.click(Vec2(260.0, -20.0))
        ed.setTool(Tools.EXTRUDE)
        ed.type("20")
        ed.click(RECT2_EDGE)
        // a curve in space through the plan points
        ed.setTool(Tools.CURVE3)
        ed.click(POINT_A)
        ed.click(POINT_B)
        ed.click(POINT_C)
        ed.key("Enter")
        // three **sphere loci** (OP-28) on the three plan points, overlapping so that two of them meet in a
        // circle, three of them meet at a pair of points, and the curve in space above runs out through the
        // first one — which is what lets the audit drive every row of the composition table generically
        for (centre in listOf(POINT_A, POINT_B, POINT_C)) {
            ed.setTool(Tools.SPHERE_LOCUS)
            ed.type("40")
            ed.click(centre)
        }
        ed.setTool(Tools.SELECT)
        ed.checkpoint()
        assertTrue(ed.doc.steplessElements().isEmpty(), "the fixture itself is fully recorded")
        return ed
    }

    /** Where a click has to land to fill a slot of [kind] in [fixture], in the order a repeat wants them. */
    private fun spotsFor(kind: SlotKind): List<Vec2>? =
        when (kind) {
            SlotKind.PLACE_POINT, SlotKind.POINT, SlotKind.SIDE -> listOf(FREE_SPOT, FREE_SPOT2, FREE_SPOT3)
            SlotKind.EXISTING_POINT -> listOf(POINT_A, POINT_B, POINT_C)
            // an **optional** point slot gets an existing point too, so the audit drives the row with its
            // option *taken* (GitHub #15): the anchored sweep has to record its anchor like every other pick
            SlotKind.OPTIONAL_POINT -> listOf(POINT_A, POINT_B, POINT_C)
            // …and the placing point slots get **empty** spots, which is the whole of session 50's change:
            // the sweep then audits that a slot which states a new point records it like everything else
            SlotKind.INPUT_POINT, SlotKind.POINT3 -> listOf(FREE_SPOT, FREE_SPOT2, FREE_SPOT3)
            SlotKind.SEGMENT, SlotKind.LINE -> listOf(SEG_MID, SEG2_MID, SEG_MID)
            SlotKind.CURVE, SlotKind.CARRIER, SlotKind.MEASURABLE, SlotKind.EXTRACTABLE, SlotKind.GEOMETRY ->
                listOf(SEG_MID, SEG2_MID, CIRCLE_EDGE)
            SlotKind.CIRCLE, SlotKind.CENTRIC, SlotKind.CENTERED -> listOf(CIRCLE_EDGE, CIRCLE_EDGE, CIRCLE_EDGE)
            SlotKind.CONIC -> listOf(ELLIPSE_EDGE, ELLIPSE_EDGE, ELLIPSE_EDGE)
            SlotKind.ON_CIRCLE_POINT -> listOf(CIRCLE_EDGE)
            SlotKind.AREA, SlotKind.CHAIN, SlotKind.LOFT_PART -> listOf(RECT_EDGE, RECT_EDGE2, RECT_EDGE)
            SlotKind.SOLID -> listOf(RECT_EDGE, RECT2_EDGE)
            SlotKind.PATH3 -> listOf(CURVE3_MID, CURVE3_MID)
            // the three sphere loci, each clicked on its own outline circle well away from the other two
            SlotKind.SPHERE -> listOf(LOCUS_A_RIM, LOCUS_B_RIM, LOCUS_C_RIM)
            // the lift's slot: an ordinary drawn curve, and a second one so a repeat has somewhere to go
            SlotKind.DRAWN_RUN -> listOf(SEG_MID, SEG2_MID, CIRCLE_EDGE)
            // a blend's drawn profile: an ordinary two-ended curve of the fixture (whether it *fits* the
            // corner is the node's business — what this audit is about is that the pick is recorded)
            SlotKind.BLEND_PROFILE -> listOf(SEG_MID, SEG2_MID, SEG_MID)
            // a section curve needs a working plane cutting a solid, which is a gesture of its own — the
            // rows that take one are covered by their own tests (IntersectionCurveToolTest)
            SlotKind.SECTION_CURVE -> null
        }

    /**
     * Run [def] over the fixture the way the palette would: the scalars it declares picked from the panel in
     * slot order, then a click per slot (or a handful and Enter, for a repeating one).
     */
    private fun runGenerically(
        ed: Editor,
        def: ToolDef,
    ) {
        // the scalars first: a pick made *after* the last slot click would complete the tool mid-sequence
        for (slot in def.scalars) {
            ed.activeScalar =
                ed.doc.newParameter(
                    "a${ed.doc.scalars.size}",
                    when (slot.dim) {
                        Dimension.ANGLE -> Quantity.deg(45.0)
                        Dimension.NONE -> Quantity.number(3.0)
                        else -> Quantity.mm(10.0)
                    },
                )
        }
        if (def.minCount > 0) ed.count = maxOf(def.minCount, 3)
        ed.setTool(def.id)
        if (def.slots.isEmpty()) {
            ed.click(FREE_SPOT)
            return
        }
        if (def.repeating) {
            val spots = spotsFor(def.slots.last()) ?: return
            for (p in spots.take(maxOf(def.minPicks, 2))) ed.click(p)
            ed.key("Enter")
            return
        }
        for ((i, kind) in def.slots.withIndex()) {
            val spots = spotsFor(kind) ?: return
            ed.click(spots[minOf(i, spots.size - 1)])
        }
    }
}
