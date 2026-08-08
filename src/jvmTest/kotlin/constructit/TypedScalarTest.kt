package constructit

import constructit.core.CircleValue
import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.editor.Camera
import constructit.editor.DocumentFormat
import constructit.editor.DrawTarget
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.HandleField
import constructit.editor.Style
import constructit.editor.TextAnchor
import constructit.editor.Tools
import constructit.geom.Curve3Element
import constructit.geom.Mesh3
import constructit.geom.Vec2
import constructit.units.mm
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **A number typed for an armed tool is part of the click that follows** — OP-13's typing contract, amended
 * on a user's report, and the freedom an untyped optional value keeps.
 *
 * Three claims, one seam:
 *
 * 1. *"Arm Circle (centre, radius), type 20, click — and nothing happens at all."* It did nothing because the
 *    number needed an Enter nobody was told about: the press published its pick, the tool was still waiting
 *    for the very value in the buffer, and the status line asked for it again. A click now **commits** the
 *    digits, through the same code Enter runs, so it stays one parameter and one undo step.
 * 2. The pending value is visible **where the user is looking**: echoed at the cursor, and folded into the
 *    live preview, so the picture promises what the click will build rather than what it would have built.
 * 3. A defaulted scalar the user never typed is no longer baked in as an anonymous constant — it is a
 *    **degree of freedom the step owns**, standing at the default, restated on save (`dofs=`) and editable
 *    for ever as an ordinary field. A coil could not be given a second turn before this.
 */
class TypedScalarTest {
    // ---- driving the editor ----

    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerMove(s)
        pointerDown(s)
        pointerUp(s)
    }

    /** Digits **without** Enter — the gesture the report is about. */
    private fun Editor.keyIn(digits: String) {
        for (c in digits) key(c.toString())
    }

    /** …and the committed form every other test in the suite uses. */
    private fun Editor.type(digits: String) {
        keyIn(digits)
        key("Enter")
    }

    private fun circles(ed: Editor): List<Element> = ed.doc.elements.filter { it.kind == ElementKind.CIRCLE }

    private fun radiusOf(el: Element): Double =
        assertNotNull(((Evaluator().eval(el.ref.node) as? EvalResult.Ok)?.value as? CircleValue)?.circle?.radius, "it is a circle")

    @Suppress("UNCHECKED_CAST")
    private fun helixOf(el: Element): Curve3Element.Helix3 =
        assertNotNull(
            ((Evaluator().eval(el.ref.node) as EvalResult.Ok).value as constructit.core.Path3Value)
                .path.elements.singleOrNull() as? Curve3Element.Helix3,
            "the run is one analytic coil",
        )

    @Suppress("UNCHECKED_CAST")
    private fun meshOf(el: Element): Mesh3 = Evaluator().solid(el.ref as SolidRef).mesh

    private fun coil(ed: Editor): Element =
        assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.SPACE_CURVE }, "the coil: ${ed.statusHint}")

    private fun field(
        ed: Editor,
        el: Element,
        label: String,
    ): Pair<Int, HandleField> {
        ed.setTool(Tools.SELECT)
        ed.selectElement(el)
        val fields = ed.selectionFields()
        val i = fields.indexOfFirst { it.label == label }
        assertTrue(i >= 0, "$label is one of the fields of ${ed.doc.nameOf(el)}: ${fields.map { it.label }}")
        return i to fields[i]
    }

    /** A target that keeps every text run and where it was drawn — the echo is asserted against this. */
    private class Texts : DrawTarget {
        val runs = ArrayList<Pair<Vec2, String>>()

        override fun begin(
            widthPx: Double,
            heightPx: Double,
        ) = Unit

        override fun polyline(
            points: List<Vec2>,
            style: Style,
        ) = Unit

        override fun polygon(
            points: List<Vec2>,
            style: Style,
        ) = Unit

        override fun circle(
            center: Vec2,
            radiusPx: Double,
            style: Style,
        ) = Unit

        override fun dot(
            center: Vec2,
            radiusPx: Double,
            color: String,
        ) = Unit

        override fun text(
            at: Vec2,
            text: String,
            style: Style,
            anchor: TextAnchor,
        ) {
            runs.add(at to text)
        }

        override fun end() = Unit
    }

    // ---- 1. the user's trace, verbatim ----

    /**
     * **The report, keystroke for keystroke**: a point, *Circle (centre, radius)*, `2`, `0`, then a click on
     * that point — and a circle of radius exactly 20 mm. No Enter anywhere.
     */
    @Test
    fun typeTwentyThenClickBuildsTheCircleOfRadiusTwenty() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.setTool(Tools.CIRCLE_R)
        ed.keyIn("20")
        assertEquals("20", ed.numericEntry, "the digits are pending, with nothing committed yet")
        assertTrue(circles(ed).isEmpty(), "and nothing is built yet")

        ed.click(Vec2(0.0, 0.0))
        val circle = assertNotNull(circles(ed).singleOrNull(), "the click built the circle: ${ed.statusHint}")
        assertClose(radiusOf(circle), 20.0, 1e-12, "of exactly the radius that was typed")
        assertEquals("", ed.numericEntry, "and the entry is spent, not left standing")
    }

    /**
     * **The status line states the whole contract while the digits are pending** — the value, *and* that a
     * click uses it. Before this it said only "Enter to use it", which is why a click read as nothing.
     */
    @Test
    fun theStatusNamesThePendingValueAndSaysAClickTakesIt() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.setTool(Tools.CIRCLE_R)
        ed.keyIn("20")
        val hint = ed.statusHint
        assertTrue(hint.contains("radius = 20 mm"), "it names the pending value: $hint")
        assertTrue(hint.contains("click to use it"), "and the click path: $hint")
        assertTrue(hint.contains("Enter"), "with Enter still offered: $hint")
        assertTrue(hint.contains("Esc"), "and Esc to cancel: $hint")
    }

    /**
     * **One gesture, one undo.** The parameter the digits became is *half* of the operation they were typed
     * for, so the tool's own checkpoint seals both — one undo takes the circle and its `radius` row together,
     * and there is no orphan step in between.
     */
    @Test
    fun oneUndoTakesTheCircleAndTheParameterItWasTypedFor() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.setTool(Tools.CIRCLE_R)
        ed.keyIn("20")
        ed.click(Vec2(0.0, 0.0))
        assertEquals(1, circles(ed).size)
        assertEquals(1, ed.doc.scalars.count { it.name == "radius" }, "the typed value is an ordinary parameter")

        assertTrue(ed.undo(), "one undo")
        assertTrue(circles(ed).isEmpty(), "takes the circle")
        assertTrue(ed.doc.scalars.none { it.name == "radius" }, "and the parameter with it — nothing half-undone")
        assertEquals(1, ed.doc.elements.count { it.kind == ElementKind.POINT }, "and stops there: the point stands")
    }

    /** **Escape still cancels**, exactly as before: the digits go, nothing is built, no parameter is left. */
    @Test
    fun escapeStillCancelsThePendingEntry() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.setTool(Tools.CIRCLE_R)
        ed.keyIn("20")
        ed.key("Escape")
        assertEquals("", ed.numericEntry, "the entry is cancelled")
        assertTrue(ed.doc.scalars.none { it.name == "radius" }, "and became no parameter")
        ed.click(Vec2(0.0, 0.0))
        assertTrue(circles(ed).isEmpty(), "so the click has no radius to build with: ${ed.statusHint}")
    }

    /**
     * **A leg's length is deliberately untouched.** While a path is being drawn the click states the endpoint
     * itself, so "use the typed length" and "place it here" are two different places — the two readings
     * conflict rather than compose. Enter places the typed leg; a click places the leg at the cursor.
     */
    @Test
    fun aClickWhileDrawingAPathStillPlacesTheLegAtTheCursor() {
        val ed = Editor()
        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(0.0, 0.0))
        val s = ed.camera.worldToScreen(Vec2(50.0, 0.0))
        ed.pointerMove(s)
        ed.keyIn("350")
        assertEquals("350", ed.numericEntry, "the digits are the leg's length")
        ed.pointerDown(s)
        ed.pointerUp(s)
        val path = assertNotNull(ed.doc.orthoPaths.lastOrNull())
        val v = assertNotNull(path.vertices.lastOrNull())
        val at = assertNotNull(((Evaluator().eval(v.ref.node) as? EvalResult.Ok)?.value as? constructit.core.PointValue)?.p)
        assertClose(at.x, 50.0, 1e-9, "the click placed the corner where the cursor was, not 350 mm out")
    }

    /**
     * **The same rule reaches the tools that used to refuse instead** — *Wall* and *Opening* declined a click
     * outright while the thickness they wanted was sitting in the buffer (*"Wall: type a thickness first"*).
     * Nothing about them is special-cased: their first click commits the digits like every other tool's, and
     * only then does the path begin.
     */
    @Test
    fun aWallTakesTheThicknessTypedForItWithoutAnEnter() {
        val ed = Editor()
        ed.setTool(Tools.WALL)
        ed.keyIn("200")
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(1000.0, 0.0))
        ed.key("Escape")
        val path = assertNotNull(ed.doc.thickNetworks.lastOrNull(), "the wall was drawn: ${ed.statusHint}")
        assertClose(
            assertNotNull(((Evaluator().eval(path.thickness.node) as? EvalResult.Ok)?.value as? constructit.core.ScalarValue)?.q?.mm),
            200.0,
            1e-12,
            "at the thickness that was typed, with no Enter",
        )
    }

    // ---- 2. feedback where the user is looking ----

    /** **The pending digits are drawn at the cursor**, through the one text primitive the surface has. */
    @Test
    fun aPendingEntryIsEchoedBesideThePointer() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.setTool(Tools.CIRCLE_R)
        val at = Vec2(40.0, 25.0)
        ed.pointerMove(ed.camera.worldToScreen(at))
        ed.keyIn("20")

        val target = Texts()
        ed.render(target)
        val echo = assertNotNull(target.runs.firstOrNull { it.second.contains("20") }, "the entry was drawn: ${target.runs}")
        assertEquals("radius = 20 mm", echo.second, "in the slot's own words")
        val cursor = ed.camera.worldToScreen(at)
        assertTrue((echo.first - cursor).length() < 30.0, "beside the pointer at $cursor, not at ${echo.first}")

        // …and it goes when the value does
        ed.key("Escape")
        val after = Texts()
        ed.render(after)
        assertTrue(after.runs.none { it.second.contains("20") }, "a cancelled entry is drawn nowhere: ${after.runs}")
    }

    /**
     * **The preview is computed with the pending value.** A fillet has no preview at all until it has a
     * radius, and the radius being *typed* is a radius: the arc appears while the digits are still pending, so
     * what is promised is what the click will build (OP-13's "the preview matches the result").
     */
    @Test
    fun thePreviewIsDrawnWithTheValueStillBeingTyped() {
        val ed = Editor()
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 0.0))
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(0.0, 100.0))
        ed.setTool(Tools.FILLET)
        ed.click(Vec2(50.0, 0.0))
        ed.pointerMove(ed.camera.worldToScreen(Vec2(0.0, 50.0)))
        assertTrue(ed.previewShapes.isEmpty(), "with no radius there is nothing to promise: ${ed.previewShapes}")

        ed.keyIn("20")
        assertTrue(ed.previewShapes.isNotEmpty(), "the digits are the radius, so the arc is previewed")
        ed.key("Backspace")
        ed.key("Backspace")
        assertTrue(ed.previewShapes.isEmpty(), "and it follows the entry back down to nothing")
    }

    // ---- 3. an untyped optional value stays editable for ever ----

    /**
     * **A coil built without typing a turn count gains a writable `turns` field** — and writing 3 puts its end
     * exactly three pitches up, which is the whole of the point: the value used to be an anonymous constant
     * that nothing in the program could reach.
     */
    @Test
    fun anUntypedTurnCountBecomesAnEditableFreedomOfTheStep() {
        val ed = Editor()
        ed.setTool(Tools.HELIX)
        ed.type("20")
        ed.type("12")
        ed.click(Vec2(0.0, 0.0))
        val el = coil(ed)
        assertClose(helixOf(el).turns, 1.0, 1e-12, "one turn, because no count was typed")
        assertClose(helixOf(el).at(1.0).z, 12.0, 1e-12, "so the end stands one pitch up")
        assertTrue(ed.doc.scalars.none { it.name == "turns" }, "and no panel row was invented for a value nobody stated")

        val (i, f) = field(ed, el, "turns")
        assertTrue(f.writable, "the freedom is writable")
        assertClose(assertNotNull(f.read(Evaluator())).value, 1.0, 1e-12, "and reads the default it stands at")

        assertTrue(ed.writeSelectionField(i, 3.0), "writing it is an ordinary field write")
        val after = coil(ed)
        assertClose(helixOf(after).turns, 3.0, 1e-12, "the coil has three turns")
        assertClose(helixOf(after).at(1.0).z, 36.0, 1e-12, "so its end stands exactly three pitches up")
    }

    /** **One undo per write**, like every other typed field (OP-13): the coil goes back to one turn. */
    @Test
    fun oneUndoTakesBackOneWriteOfTheFreedom() {
        val ed = Editor()
        ed.setTool(Tools.HELIX)
        ed.type("20")
        ed.type("12")
        ed.click(Vec2(0.0, 0.0))
        val (i, _) = field(ed, coil(ed), "turns")
        ed.writeSelectionField(i, 3.0)
        ed.writeSelectionField(i, 5.0)
        assertClose(helixOf(coil(ed)).turns, 5.0, 1e-12)

        assertTrue(ed.undo())
        assertClose(helixOf(coil(ed)).turns, 3.0, 1e-12, "the first undo takes back the second write")
        assertTrue(ed.undo())
        assertClose(helixOf(coil(ed)).turns, 1.0, 1e-12, "and the second takes back the first")
    }

    /**
     * **The step restates it, so it survives a save** — the `dofs=` seam a rider's angle uses (session 53),
     * with `save → load → save` byte-equal and the reloaded coil still three turns high.
     */
    @Test
    fun theFreedomRidesTheStepAndRoundTripsByteForByte() {
        val ed = Editor()
        ed.setTool(Tools.HELIX)
        ed.type("20")
        ed.type("12")
        ed.click(Vec2(0.0, 0.0))
        val (i, _) = field(ed, coil(ed), "turns")
        ed.writeSelectionField(i, 3.0)

        val text = DocumentFormat.save(ed.doc)
        assertTrue(text.contains("dofs=3"), "the step restates the freedom it owns:\n$text")
        val again = DocumentFormat.load(text)
        assertTrue(again.loadNotes.isEmpty(), "nothing about it is ambiguous: ${again.loadNotes}")
        assertEquals(text, DocumentFormat.save(again), "save -> load -> save is byte-equal")
        val reloaded = assertNotNull(again.elements.lastOrNull { it.kind == ElementKind.SPACE_CURVE })
        assertClose(helixOf(reloaded).turns, 3.0, 1e-12, "and the reloaded coil is the one that was saved")
    }

    /**
     * **An old file gains the freedom and means what it always meant.** Hand-written in the shape a build
     * *before* this package wrote it — a `tool helix` step with no `dofs=` at all — because an in-build round
     * trip proves nothing across builds (OP-18). It loads with one turn, silently (a new `dofs=` reading is
     * not a changed literal, so no version bump is owed), and the coil is editable from that moment on.
     */
    @Test
    fun aFileWrittenBeforeTheFreedomExistedLoadsAtTheDefaultAndGainsIt() {
        val doc = DocumentFormat.load(OLD_HELIX_CIT)
        assertTrue(doc.loadNotes.isEmpty(), "no note, because nothing about it is ambiguous: ${doc.loadNotes}")
        assertEquals(2, DocumentFormat.VERSION, "and the format is the version it was: no bump is owed")
        val el = assertNotNull(doc.elements.lastOrNull { it.kind == ElementKind.SPACE_CURVE }, "the coil loaded")
        assertClose(helixOf(el).turns, 1.0, 1e-12, "at one turn, which is what that file always meant")

        val ed = Editor(doc)
        val (i, f) = field(ed, el, "turns")
        assertTrue(f.writable, "and it is editable from now on")
        ed.writeSelectionField(i, 4.0)
        val after = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.SPACE_CURVE })
        assertClose(helixOf(after).turns, 4.0, 1e-12)
        assertClose(helixOf(after).at(1.0).z, 48.0, 1e-12, "four pitches of 12 mm")
        val text = DocumentFormat.save(ed.doc)
        assertTrue(text.contains("dofs=4"), "and the step restates it from now on:\n$text")
        assertEquals(text, DocumentFormat.save(DocumentFormat.load(text)), "round trip, byte for byte")
    }

    /**
     * **A sweep's roll and twist are two freedoms of the same kind** — the anchored-sweep fixture of
     * `SweepAnchorTest`, unrolled and then rolled half a turn from the panel: the section that stood *outside*
     * the coil stands inside it, the solid stays valid and the shell stays watertight.
     *
     * The numbers are the fixture's own: a coil of radius 30, pitch 10, two turns, and a 2 × 2 mm square
     * anchored at its lower-left corner, so the body lies within `sqrt(8) = 2.828 mm` of the spine either way
     * and the flip is the side of it that is filled.
     */
    @Test
    fun aSweptSectionRollsHalfATurnFromThePanelAndStaysWatertight() {
        val ed = Editor()
        ed.camera = Camera(-800.0, 500.0, 40.0)
        ed.setTool(Tools.HELIX)
        ed.type("30")
        ed.type("10")
        ed.type("2")
        ed.click(Vec2(0.0, 0.0))
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(30.0, 5.0))
        ed.click(Vec2(32.0, 7.0))
        ed.setTool(Tools.SWEEP)
        ed.click(Vec2(30.0, 0.0))
        ed.click(Vec2(30.0, 5.0))
        ed.click(Vec2(31.0, 5.0))
        val solid = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.SOLID }, "the sweep: ${ed.statusHint}")
        assertNull((Evaluator().eval(solid.ref.node) as? EvalResult.Invalid)?.reason, "it is valid")
        assertManifold(meshOf(solid), "the unrolled sweep")

        // both freedoms are there, and neither was invented as a panel row
        val labels = field(ed, solid, "roll").let { ed.selectionFields().map { f -> f.label } }
        assertTrue(labels.containsAll(listOf("roll", "twist")), "roll and twist are both fields: $labels")
        assertTrue(ed.doc.scalars.none { it.name == "roll" || it.name == "twist" }, "and neither is a panel parameter")

        val before = radialSpan(meshOf(solid))
        assertClose(before.first, 28.767910160401883, 1e-9, "unrolled, the body reaches this far in")
        assertClose(before.second, 32.00254653672712, 1e-9, "and this far out — 2 mm past the coil's own 30 mm")

        val (i, f) = field(ed, solid, "roll")
        assertTrue(f.writable, "roll is writable")
        assertTrue(ed.writeSelectionField(i, 180.0), "a half turn of roll")
        val rolled = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.SOLID })
        assertNull((Evaluator().eval(rolled.ref.node) as? EvalResult.Invalid)?.reason, "still valid")
        val mesh = meshOf(rolled)
        assertManifold(mesh, "the rolled sweep")
        val after = radialSpan(mesh)
        // the same section on the other side of the spine: the whole span moves in, by the same amount at
        // both ends, and stays inside the sqrt(8) = 2.828 mm shell the anchored corner implies either way
        assertClose(after.first, 27.997490559653052, 1e-9, "rolled, the body reaches further in")
        assertClose(after.second, 31.232140253085767, 1e-9, "and no longer as far out")
        // …by the same amount at both ends, to the accuracy the *tessellation* can state it: the extremes are
        // read off triangle vertices, so the two shifts agree to 1.4e-5 mm rather than exactly
        assertClose(before.first - after.first, before.second - after.second, 1e-4, "one rigid flip, not a distortion")
        val reach = sqrt(8.0)
        assertTrue(after.first >= 30.0 - reach - 1e-6 && after.second <= 30.0 + reach + 1e-6, "inside the shell either way: $after")

        // …and a *whole* turn of roll is the frame it started with, which is what says the number is an angle
        // entering the moving frame rather than anything of the section's own
        assertTrue(ed.writeSelectionField(i, 360.0), "a full turn of roll")
        val full = radialSpan(meshOf(assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.SOLID })))
        assertClose(full.first, before.first, 1e-9, "360 deg of roll is 0 deg of roll")
        assertClose(full.second, before.second, 1e-9, "at both ends of the span")
        assertTrue(ed.writeSelectionField(i, 180.0), "back to the half turn the file below is written at")

        // and it saves like the coil's own freedom does
        val text = DocumentFormat.save(ed.doc)
        assertTrue(text.contains("dofs=180deg;0deg"), "roll restated, twist still at its default:\n$text")
        assertEquals(text, DocumentFormat.save(DocumentFormat.load(text)), "round trip, byte for byte")
    }

    /** How far the vertices of [mesh] stand from the coil's axis (the world z axis here): min and max. */
    private fun radialSpan(mesh: Mesh3): Pair<Double, Double> {
        val radii = mesh.vertices.map { sqrt(it.x * it.x + it.y * it.y) }
        return radii.min() to radii.max()
    }

    /**
     * **A default that names a *construction* keeps its constant** — *Midpoint* with no factor is
     * `cx.midpoint`, a derived point with no degree of freedom at all, and a factor makes it a draggable ratio
     * point instead. Those are two constructions, not one construction at two values, so the tool owns no
     * freedom here and the midpoint stays exactly derived (see `ScalarSlot.structural`).
     */
    @Test
    fun aStructuralDefaultOwnsNoFreedomAndTheMidpointStaysDerived() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 0.0))
        ed.setTool(Tools.MIDPOINT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 0.0))
        val mid = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.DERIVED_POINT }, "a derived midpoint: ${ed.statusHint}")
        assertTrue(ed.doc.ownFields(mid).isEmpty(), "no freedom: 0.5 is the name of this construction")
        val text = DocumentFormat.save(ed.doc)
        assertFalse(text.contains("dofs="), "so its step restates nothing:\n$text")
        assertEquals(text, DocumentFormat.save(DocumentFormat.load(text)), "round trip, byte for byte")
    }

    /**
     * **A value the user *did* type keeps today's behaviour exactly** — an ordinary named parameter in the
     * panel, riding `scalar=`, with no freedom invented beside it. The step owns a freedom for what was *not*
     * stated and for nothing else, which is what keeps the two halves of the typing contract apart.
     */
    @Test
    fun aTypedTurnCountStaysAnOrdinaryParameterAndOwnsNoFreedom() {
        val ed = Editor()
        ed.setTool(Tools.HELIX)
        ed.type("20")
        ed.type("12")
        ed.type("3")
        ed.click(Vec2(0.0, 0.0))
        val el = coil(ed)
        assertClose(helixOf(el).turns, 3.0, 1e-12, "the coil has the turns that were typed")
        assertEquals(1, ed.doc.scalars.count { it.name == "turns" }, "as an ordinary panel parameter")
        assertTrue(ed.doc.ownFields(el).isEmpty(), "and the step owns no freedom for a value it was given")
        val text = DocumentFormat.save(ed.doc)
        assertTrue(text.contains("scalar=\"radius\",\"pitch\",\"turns\""), "it rides scalar= as it always did:\n$text")
        assertFalse(text.contains("dofs="), "and the step restates nothing of its own:\n$text")
        assertEquals(text, DocumentFormat.save(DocumentFormat.load(text)), "round trip, byte for byte")
    }

    /** **Deleting the element takes the freedom with it** — it was the step's, and the step goes. */
    @Test
    fun deletingTheCoilTakesTheFreedomWithIt() {
        val ed = Editor()
        ed.setTool(Tools.HELIX)
        ed.type("20")
        ed.type("12")
        ed.click(Vec2(0.0, 0.0))
        val (i, _) = field(ed, coil(ed), "turns")
        ed.writeSelectionField(i, 2.0)
        ed.selectElement(coil(ed))
        ed.deleteSelection()
        assertTrue(ed.doc.elements.none { it.kind == ElementKind.SPACE_CURVE }, "the coil is gone: ${ed.statusHint}")
        val text = DocumentFormat.save(ed.doc)
        assertFalse(text.contains("dofs="), "and nothing restates a freedom nobody owns:\n$text")
        assertEquals(text, DocumentFormat.save(DocumentFormat.load(text)), "round trip, byte for byte")
    }

    /**
     * **And the refusal says so.** A coil cannot be dragged — its geometry is its parents' — but "fully
     * determined by the construction" became a lie the moment it owned an editable turn count. Nothing may
     * decline in words that hide the route that exists.
     */
    @Test
    fun grabbingACoilNamesTheFreedomItCanStillBeGiven() {
        val ed = Editor()
        ed.setTool(Tools.HELIX)
        ed.type("20")
        ed.type("12")
        ed.click(Vec2(0.0, 0.0))
        ed.setTool(Tools.SELECT)
        ed.click(Vec2(20.0, 0.0))
        assertTrue(ed.statusHint.contains("turns"), "the refusal names what can still be set: ${ed.statusHint}")
        assertFalse(ed.statusHint.contains("fully determined"), "and no longer says there is nothing: ${ed.statusHint}")
    }

    /**
     * **A replicated gesture owns *one* freedom for the whole fan** (OP-23): a coil on every member of a
     * pattern is one rule, so its turn count is one number — writing it through any copy raises them all, and
     * the `orbit` step restates it once.
     *
     * The alternative, a value per copy, would be geometry the rule does not state: the file records the
     * gesture, not four gestures that happen to agree.
     */
    @Test
    fun aReplicatedGestureOwnsOneFreedomForEveryCopy() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(20.0, 0.0))
        ed.count = 4
        ed.setTool(Tools.PATTERN_CIRCULAR)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(20.0, 0.0))
        ed.setTool(Tools.HELIX)
        ed.type("5")
        ed.type("8")
        ed.click(Vec2(20.0, 0.0))
        val coils = ed.doc.elements.filter { it.kind == ElementKind.SPACE_CURVE }
        assertEquals(4, coils.size, "one coil per member: ${ed.statusHint}")
        for (c in coils) assertClose(helixOf(c).turns, 1.0, 1e-12, "each at the default turn count")

        val (i, _) = field(ed, coils.first(), "turns")
        assertTrue(ed.writeSelectionField(i, 2.5))
        val after = ed.doc.elements.filter { it.kind == ElementKind.SPACE_CURVE }
        for (c in after) assertClose(helixOf(c).turns, 2.5, 1e-12, "and every copy follows the one number")

        val text = DocumentFormat.save(ed.doc)
        assertEquals(1, text.lines().count { it.contains("dofs=2.5") }, "restated once, by the orbit step:\n$text")
        val back = DocumentFormat.load(text)
        assertTrue(back.loadNotes.isEmpty(), "and nothing about it is ambiguous: ${back.loadNotes}")
        assertEquals(text, DocumentFormat.save(back), "round trip, byte for byte")
        for (c in back.elements.filter { it.kind == ElementKind.SPACE_CURVE }) {
            assertClose(helixOf(c).turns, 2.5, 1e-12, "the reloaded fan is the one that was saved")
        }
    }

    /**
     * **The audit, as a test rather than as a table in a document**: every defaulted scalar slot in the whole
     * registry either owns a freedom or is one of the two things that cannot own one — a default that names a
     * *construction* (`ScalarSlot.structural`) or a tool that writes its own steps and so has no `tool` step to
     * restate one on (`ToolDef.recordsSteps`). A row added later cannot quietly go back to baking a constant.
     */
    @Test
    fun everyDefaultedScalarInTheRegistryEitherOwnsAFreedomOrCannot() {
        val owned = ArrayList<String>()
        val excused = ArrayList<String>()
        for (t in Tools.all) {
            t.scalars.forEachIndexed { i, s ->
                if (s.default == null) return@forEachIndexed
                val what = "${t.id}.${s.name}"
                // with the slots before it stated, this slot is the next one — which is the case a step owns
                if (t.ownedSlots(i).contains(i)) {
                    owned.add(what)
                } else {
                    assertTrue(s.structural || t.recordsSteps, "$what owns no freedom and gives no reason")
                    excused.add(what)
                }
            }
        }
        assertEquals(
            listOf(
                "helixpt.turns", "helixptleft.turns", "helix.turns", "helixleft.turns",
                "connect.tension", "connect.far tension", "connectg2.tension", "connectg2.far tension",
                "tube.roll", "tube.twist", "sweep.roll", "sweep.twist",
                "placesolid.angle", "placecurve.angle", "spaceorigin.dx", "spaceorigin.dy",
            ),
            owned,
            "the freedoms a step owns",
        )
        assertEquals(
            listOf("midpoint.factor", "polygon.corner radius", "sketchplane.angle", "sketchplane.offset", "perpbis.factor"),
            excused,
            "and the ones that cannot, each for a stated reason",
        )
    }

    /**
     * **A step that creates nothing carries no freedom** — *Space origin* is the one tool in that position: it
     * re-points a plane's anchor and builds no element, so there is no handle to reach a `dx` through. The
     * drawing is exactly what it was: no `dofs=`, and the offsets are the space's own zero.
     */
    @Test
    fun aToolThatCreatesNoElementWritesNoFreedom() {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(80.0, 50.0))
        ed.activeScalar = ed.doc.newParameter("thickness", 20.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(40.0, 0.0))
        ed.setTool(Tools.SKETCH_ON_FACE)
        ed.click(Vec2(40.0, 0.0))
        ed.setTool(Tools.SPACE_ORIGIN)
        ed.click(Vec2(-40.0, 0.0))
        assertEquals(0, ed.activeSpace.originCorner, "the corner was taken: ${ed.statusHint}")

        val text = DocumentFormat.save(ed.doc)
        val step = assertNotNull(text.lines().firstOrNull { it.startsWith("tool spaceorigin") }, "the step is there:\n$text")
        assertFalse(step.contains("dofs="), "and restates no freedom, because nothing can edit one: $step")
        assertEquals(text, DocumentFormat.save(DocumentFormat.load(text)), "round trip, byte for byte")
    }

    companion object {
        /**
         * A drawing in the shape a build **before** this package wrote it: a `tool helix` step with the two
         * typed parameters and **no `dofs=`**. Kept as a permanent load test (OP-18).
         */
        val OLD_HELIX_CIT =
            """
            constructit 2
            param "radius" = 20mm
            param "pitch" = 12mm
            point 0,0 -> e1
            tool helix els=e1 clicks=0,0 scalar="radius","pitch" -> e2
            """.trimIndent() + "\n"
    }
}
