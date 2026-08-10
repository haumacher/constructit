package constructit

import constructit.core.CircleValue
import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.Point3Value
import constructit.dsl.scalar
import constructit.dsl.valueOf
import constructit.editor.Camera3
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.ScalarEntry
import constructit.editor.Tools
import constructit.editor.Viewport3
import constructit.expr.Expr
import constructit.expr.ExprError
import constructit.expr.ExprEval
import constructit.expr.ExprParser
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.units.Dimension
import constructit.units.DimensionError
import constructit.units.Quantity
import constructit.units.deg
import constructit.units.mm
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **Expressions — `boundTo` generalized to a function** (OP-7, the session-71 queue entry, scalar half).
 *
 * The user's design, in his words: *"a value could be computed from other values applying an arbitrary
 * function — you should be able to use everything java.lang.Math has to offer in addition to plain
 * operators"*. What is asserted here is that this is the **binding**, not a constraint: `r = d/2 + 1mm`
 * *defines* `r` in one direction, the recompute is ordinary DAG recompute, and everything that already
 * referenced `r` follows without a single input list being rewired.
 *
 * Six claims, one mechanism:
 *
 * 1. a circle whose radius is derived follows the value it reads, and the undo layering is the panel's
 *    own (a live tick is not an operation, a committed change is);
 * 2. the **text is the record** — stored verbatim, parsed on load, `save → load → save` byte-equal, and a
 *    file written before expressions existed still loads and round-trips untouched;
 * 3. the dimension rules ride the units layer in **both** directions, and a violation is the named
 *    invalidity that heals (OP-3), never an exception and never a silent zero;
 * 4. a derived parameter refuses the typed number and the drag **in the wired height's own words**
 *    (OP-25), and the refusal names the expression;
 * 5. a rename of something an expression reads **re-stamps** the stored text (OP-18's naming authority);
 * 6. a cycle is refused **by name at bind time**, not discovered as a hang.
 */
class ExpressionTest {
    private val wPx = 800.0
    private val hPx = 600.0

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

    private fun scalarNamed(
        ed: Editor,
        name: String,
    ): ScalarEntry = assertNotNull(ed.doc.scalars.firstOrNull { it.name == name }, "the panel has a scalar named '$name'")

    private fun radiusOf(el: Element): Double =
        assertNotNull(Evaluator().valueOf(el.ref) as? CircleValue, "the circle is built").circle.radius

    private fun roundTrips(
        ed: Editor,
        msg: String,
    ): String {
        val once = DocumentFormat.save(ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), msg)
        return once
    }

    /** A circle at the origin whose radius is a named parameter, plus a second parameter to derive it from. */
    private fun circleDoc(): Editor {
        val ed = Editor()
        ed.doc.newParameter("d", 20.0.mm)
        ed.activeScalar = ed.doc.newParameter("r", 10.0.mm)
        ed.setTool(Tools.CIRCLE_R)
        ed.click(Vec2(0.0, 0.0))
        ed.checkpoint()
        return ed
    }

    // ---- 1. the everyday case: a radius derived from a diameter ----

    /**
     * *"Editing `d` moves the circle"* — and by nothing more than the recompute every other edit uses. The
     * expression node sits under the radius parameter's `boundTo`, so the circle, which references the
     * parameter and knows nothing about any of this, simply follows.
     */
    @Test
    fun aRadiusDerivedFromADiameterFollowsIt() {
        val ed = circleDoc()
        val circle = ed.doc.elements.single { it.kind == ElementKind.CIRCLE }
        val d = scalarNamed(ed, "d")
        val r = scalarNamed(ed, "r")

        assertTrue(ed.doc.bindParameter(r, "d/2 + 1mm"), "the binding is taken: ${ed.doc.note}")
        assertClose(radiusOf(circle), 11.0, tol = 1e-9, msg = "20/2 + 1")

        ed.doc.setParameter(d, 30.0.mm)
        assertClose(radiusOf(circle), 16.0, tol = 1e-9, msg = "30/2 + 1 — the circle followed d")

        // one direction only: r is *defined* by d, and nothing propagates back
        assertClose(Evaluator().scalar(d.ref).mm, 30.0, tol = 1e-9, msg = "d is untouched")
        assertEquals("d/2 + 1mm", ed.doc.expressionOf(r), "and the drawing says what it is")
    }

    /**
     * The **undo layering** the panel already promises (OP-7): a spinner tick writes the model live and is
     * no operation, the committed change is one — and the binding itself is one operation of its own.
     */
    @Test
    fun aLiveTickIsNoOperationAndACommittedOneIs() {
        val ed = circleDoc()
        val circle = ed.doc.elements.single { it.kind == ElementKind.CIRCLE }
        val r = scalarNamed(ed, "r")
        assertTrue(ed.bindParameter(r, "d/2 + 1mm"), "bound through the panel's own entry point: ${ed.statusHint}")
        assertClose(radiusOf(circle), 11.0, tol = 1e-9, msg = "bound")

        val d = scalarNamed(ed, "d")
        ed.setParameter(d, 40.0, commit = false)
        assertClose(radiusOf(circle), 21.0, tol = 1e-9, msg = "the drawing follows every tick")
        ed.setParameter(d, 50.0, commit = true)
        assertClose(radiusOf(circle), 26.0, tol = 1e-9, msg = "and the committed value")

        // undo replays the saved script into a fresh document (OP-18), so the drawing is asked again by name
        fun radiusNow(): Double = radiusOf(ed.doc.elements.single { it.kind == ElementKind.CIRCLE })
        ed.undo()
        assertClose(radiusNow(), 11.0, tol = 1e-9, msg = "one undo step for the whole nudge, back to the bound value")
        assertEquals("d/2 + 1mm", ed.doc.expressionOf(scalarNamed(ed, "r")), "the binding survived the replay")
        ed.undo()
        assertClose(radiusNow(), 10.0, tol = 1e-9, msg = "and one more takes the binding itself off")
        assertNull(ed.doc.expressionOf(scalarNamed(ed, "r")), "the parameter is its own value again")
    }

    // ---- 2. the text is the record ----

    /** Stored **verbatim**, parsed on load, and `save → load → save` byte-equal (OP-18's load-bearing test). */
    @Test
    fun theExpressionIsStoredVerbatimAndRoundTrips() {
        val ed = circleDoc()
        assertTrue(ed.doc.bindParameter(scalarNamed(ed, "r"), "d/2 + 1mm"))
        val text = roundTrips(ed, "save -> load -> save is byte-equal with the expression in it")
        assertTrue(text.contains("bind \"r\" = \"d/2 + 1mm\""), "the user's own text, spacing and all:\n$text")

        val reloaded = DocumentFormat.load(text)
        val r = assertNotNull(reloaded.scalars.firstOrNull { it.name == "r" }, "the parameter came back")
        assertEquals("d/2 + 1mm", reloaded.expressionOf(r), "and so did its formula")
        val circle = reloaded.elements.single { it.kind == ElementKind.CIRCLE }
        assertClose(radiusOf(circle), 11.0, tol = 1e-9, msg = "evaluated the same on the other side")
    }

    /** A **plain wire** — the degenerate expression — keeps exactly the storage and the meaning it had. */
    @Test
    fun aPlainWiredFileLoadsUnchanged() {
        val ed = circleDoc()
        assertTrue(ed.doc.wireParameter(scalarNamed(ed, "r"), scalarNamed(ed, "d")), "wired the old way")
        val text = roundTrips(ed, "a pre-existing wire round-trips byte-equal")
        assertTrue(text.contains("wire \"r\" = \"d\""), "written as the `wire` step it always was:\n$text")
        assertFalse(text.contains("bind "), "and nothing new is written into an old file")

        val reloaded = DocumentFormat.load(text)
        val r = assertNotNull(reloaded.scalars.firstOrNull { it.name == "r" }, "the parameter came back")
        assertTrue(reloaded.isBound(r), "still wired")
        assertNull(reloaded.expressionOf(r), "and a wire is not an expression binding")
    }

    /**
     * **Freeing a bound value is now in the file.** It never was: the `wire` step stayed in the journal and
     * nothing said the bond had been dropped, so a parameter freed in the panel came back wired on the next
     * load. The `unbind` step says it, and restates the literal the parameter carries from then on.
     */
    @Test
    fun freeingABoundValueSurvivesTheFile() {
        val ed = circleDoc()
        val r = scalarNamed(ed, "r")
        assertTrue(ed.doc.wireParameter(r, scalarNamed(ed, "d")), "wired")
        assertTrue(ed.doc.unwireParameter(r), "then freed")
        ed.doc.setParameter(r, 7.0.mm)

        val text = roundTrips(ed, "the freeing round-trips")
        val reloaded = DocumentFormat.load(text)
        val back = assertNotNull(reloaded.scalars.firstOrNull { it.name == "r" }, "the parameter came back")
        assertFalse(reloaded.isBound(back), "free, as the user left it")
        assertClose(Evaluator().scalar(back.ref).mm, 7.0, tol = 1e-9, msg = "with the number he typed after freeing it")
    }

    // ---- 3. the dimension rules, both directions, and the invalidity that heals ----

    /** `sin` over a **deg-displayed, rad-canonical** angle parameter — the panel's units, the engine's. */
    @Test
    fun sinAndCosReadTheAngleParameterTheEngineStores() {
        val ed = Editor()
        val a = ed.doc.newParameter("a", 30.0.deg)
        val k = ed.doc.newParameter("k", Quantity.number(0.0))
        assertTrue(ed.doc.bindParameter(k, "sin(a)"), "an angle in, a plain number out: ${ed.doc.note}")
        assertClose(Evaluator().scalar(k.ref).value, 0.5, tol = 1e-12, msg = "sin 30° — the panel says 30, the node holds rad")

        ed.doc.setParameter(a, 60.0.deg)
        assertClose(Evaluator().scalar(k.ref).value, 0.5 * kotlin.math.sqrt(3.0), tol = 1e-12, msg = "and it follows")

        // a literal in degrees is canonicalized where it is parsed, never later
        val c = ed.doc.newParameter("c", Quantity.number(0.0))
        assertTrue(ed.doc.bindParameter(c, "cos(a - 60deg)"))
        assertClose(Evaluator().scalar(c.ref).value, 1.0, tol = 1e-12, msg = "cos 0")
    }

    /**
     * **Both directions of the rule, each a named invalidity that heals** (OP-3): an angle where a plain
     * number is demanded, and a plain number where an angle is. Nothing throws, nothing silently answers 0,
     * and correcting the formula brings the value back.
     */
    @Test
    fun aDimensionViolationIsNamedInvalidityAndItHeals() {
        val ed = Editor()
        val a = ed.doc.newParameter("a", 30.0.deg)
        val n = ed.doc.newParameter("n", Quantity.number(0.5))

        // (a) an angle where a plain number is demanded
        val bad = ed.doc.newParameter("bad", Quantity.number(0.0))
        assertTrue(ed.doc.bindParameter(bad, "asin(a)"), "the binding is legal — it is the *values* that disagree")
        val why = assertNotNull((Evaluator().eval(bad.ref.node) as? EvalResult.Invalid)?.reason, "and the node is invalid")
        assertTrue(why.contains("asin") && why.contains("plain number"), "named, in the drawing's own words: $why")
        assertTrue(why.contains("asin(a)"), "and it quotes the expression: $why")

        assertTrue(ed.doc.bindParameter(bad, "asin(sin(a))"), "corrected")
        assertClose(Evaluator().scalar(bad.ref).deg, 30.0, tol = 1e-9, msg = "and it healed, with no repair anywhere else")

        // (b) a **length** where an angle or a plain number is demanded.
        // Amended in the session-71 curve half: `sin`/`cos`/`tan` now take an angle *or a plain number read
        // as radians* — `java.lang.Math`'s own reading, and the one a dimensionless curve parameter needs
        // (`cos(t)`). So `sin(n)` is legal from that build on, and the violation the other way is a length.
        val w = ed.doc.newParameter("w", 40.0.mm)
        val bad2 = ed.doc.newParameter("bad2", Quantity.number(0.0))
        assertTrue(ed.doc.bindParameter(bad2, "sin(w)"))
        val why2 = assertNotNull((Evaluator().eval(bad2.ref.node) as? EvalResult.Invalid)?.reason, "invalid the other way")
        assertTrue(why2.contains("sin") && why2.contains("angle"), "and says which way: $why2")
        assertTrue(ed.doc.bindParameter(bad2, "sin(n)"), "corrected — a plain number is read as radians")
        assertClose(Evaluator().scalar(bad2.ref).value, kotlin.math.sin(0.5), tol = 1e-12, msg = "healed")

        // a re-bound parameter keeps *both* steps, each stating the text it stated — and the file round-trips
        val text = roundTrips(ed, "two bindings of one value round-trip")
        assertEquals(2, text.lines().count { it.startsWith("bind \"bad\" ") }, "the correction is a second step:\n$text")
        assertClose(Evaluator().scalar(scalarNamed(ed, "bad").ref).deg, 30.0, tol = 1e-9, msg = "and the live value is the last one")
    }

    /**
     * A `DimensionError` mid-expression hides what is built on it rather than crashing: invalidity
     * propagates transitively (OP-3), the element is **flagged by name with the node's reason**, and the
     * whole cone comes back when the formula is corrected.
     */
    @Test
    fun aDimensionErrorHidesDependentsAndTheyComeBack() {
        val ed = circleDoc()
        val circle = ed.doc.elements.single { it.kind == ElementKind.CIRCLE }
        val r = scalarNamed(ed, "r")

        assertTrue(ed.doc.bindParameter(r, "d/2 + 1deg"), "adding a length to an angle is legal to *write*")
        val bad = assertNotNull(ed.doc.invalidElements().firstOrNull { it.element === circle }, "the circle cannot be built")
        assertTrue(bad.reason.contains("d/2 + 1deg"), "the reason quotes the expression: ${bad.reason}")
        assertEquals(ed.doc.nameOf(circle), bad.name, "and names the element as the drawing does")
        assertTrue(Evaluator().eval(circle.ref.node) is EvalResult.Invalid, "nothing threw; the value is simply not there")

        assertTrue(ed.doc.bindParameter(r, "d/2 + 1mm"), "corrected")
        assertTrue(ed.doc.invalidElements().isEmpty(), "and the drawing healed by itself")
        assertClose(radiusOf(circle), 11.0, tol = 1e-9, msg = "back, with no repair and no deletion")
    }

    // ---- 4. what is derived refuses the write, and names what drives it ----

    /** A typed number into a derived parameter is refused, and the refusal **is** the expression. */
    @Test
    fun aDerivedParameterRefusesTheTypedNumberByNamingItsFormula() {
        val ed = circleDoc()
        val r = scalarNamed(ed, "r")
        assertTrue(ed.bindParameter(r, "d/2 + 1mm"))

        assertFalse(ed.setParameter(r, 99.0), "the panel's value field writes nothing")
        assertTrue(ed.statusHint.contains("r = d/2 + 1mm"), "and says why, quoting the formula: ${ed.statusHint}")
        assertClose(radiusOf(ed.doc.elements.single { it.kind == ElementKind.CIRCLE }), 11.0, tol = 1e-9, msg = "unmoved")

        // ...and the formula field frees it again, where it stands
        assertTrue(ed.bindParameter(r, ""), "blank frees it: ${ed.statusHint}")
        assertTrue(ed.setParameter(r, 99.0), "and then it takes a number of its own")
        assertClose(radiusOf(ed.doc.elements.single { it.kind == ElementKind.CIRCLE }), 99.0, tol = 1e-9, msg = "moved")
    }

    /**
     * **The drag refuses in the wired height's own words** (OP-25) — the same rule a welded 2D point
     * follows, asked in the very same way ([constructit.editor.isFreeSource]) — with one sentence added
     * that "driven by the construction" could not say: *which formula* to go and change.
     */
    @Test
    fun anExpressionBoundHeightRefusesTheDragAndNamesTheFormula() {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 100.0))
        ed.setTool(Tools.EXTRUDE_TO_POINT)
        ed.type("90")
        ed.click(Vec2(30.0, 0.0))
        ed.click(Vec2(50.0, 50.0))
        val apex = ed.doc.elements.single { it.kind == ElementKind.HEIGHT_POINT }
        val span = ed.doc.newParameter("span", 100.0.mm)
        assertNotNull(span, "a value to derive from")
        assertTrue(ed.doc.bindParameter(scalarNamed(ed, "height"), "span * 1.2"), "the height is a formula now")

        fun at3(el: Element): Vec3 = assertNotNull(Evaluator().valueOf(el.ref) as? Point3Value, "a point in space").p
        assertEquals(Vec3(50.0, 50.0, 120.0), at3(apex), "standing where the formula says")
        assertFalse(apex.hasFreeDof, "with no freedom of its own left")

        ed.setTool(Tools.SELECT)
        val vp =
            Viewport3(
                camera = Camera3(target = Vec3(50.0, 50.0, 40.0), distance = 320.0, yaw = -0.9, pitch = 0.5),
                widthPx = wPx,
                heightPx = hPx,
            )
        vp.editor = ed
        vp.shown = true
        val from = assertNotNull(vp.camera.project(Vec3(50.0, 50.0, 120.0), wPx, hPx), "the apex is on screen")
        val to = assertNotNull(vp.camera.project(Vec3(50.0, 50.0, 60.0), wPx, hPx), "and so is where it was dragged")
        vp.pointerDown(from)
        vp.pointerMove(to)
        vp.pointerUp(to)
        assertEquals(Vec3(50.0, 50.0, 120.0), at3(apex), "the drag wrote nothing: ${ed.statusHint}")
        assertTrue(ed.statusHint.contains("height = span * 1.2"), "and the refusal names the formula: ${ed.statusHint}")

        // move what drives it instead — which is exactly what the refusal told the user to do
        ed.doc.setParameter(span, 50.0.mm)
        assertEquals(Vec3(50.0, 50.0, 60.0), at3(apex), "the apex followed span")
    }

    // ---- 5. a rename re-stamps, it never orphans ----

    /**
     * Renaming something an expression reads **re-stamps the stored text** (OP-18's naming authority, and
     * the move OP-23 makes for a count): the reference follows by identity, and every other character of
     * the user's text is left alone.
     */
    @Test
    fun renamingAReferencedParameterRestampsTheExpression() {
        val ed = circleDoc()
        val r = scalarNamed(ed, "r")
        assertTrue(ed.doc.bindParameter(r, "d/2 + 1mm"))

        assertEquals("bore", ed.doc.renameParameter(scalarNamed(ed, "d"), "bore"), "the rename is taken")
        assertEquals("bore/2 + 1mm", ed.doc.expressionOf(r), "and the formula reads the new name")
        assertClose(radiusOf(ed.doc.elements.single { it.kind == ElementKind.CIRCLE }), 11.0, tol = 1e-9, msg = "nothing moved")

        val text = roundTrips(ed, "the re-stamped file round-trips")
        assertTrue(text.contains("bind \"r\" = \"bore/2 + 1mm\""), "the file says it too:\n$text")
        assertFalse(text.contains("\"d\""), "and the old name is nowhere in it")

        // ...and the parameter carrying the formula may be renamed as well, with the wiring untouched
        assertEquals("rad", ed.doc.renameParameter(r, "rad"), "renamed")
        assertEquals("bore/2 + 1mm", ed.doc.expressionOf(r), "it still reads what it read")
        roundTrips(ed, "and still round-trips")
    }

    /**
     * A name an expression **cannot spell** is named as such. A hyphen in a scalar name reads as a
     * subtraction, so `wall-width` is one scalar and two tokens — and "there is no value named 'wall'"
     * would send the user hunting for a typo he did not make.
     */
    @Test
    fun aNameAnExpressionCannotSpellSaysSoAndOffersTheCure() {
        val ed = Editor()
        ed.doc.newParameter("wall width", 200.0.mm)
        val t = ed.doc.newParameter("t", 10.0.mm)
        assertEquals("wall-width", ed.doc.scalars.first().name, "spaces become hyphens (OP-7)")

        assertFalse(ed.doc.bindParameter(t, "wall-width/2"), "it cannot be referenced")
        val why = assertNotNull(ed.doc.note, "and the refusal says why")
        assertTrue(why.contains("wall-width") && why.contains("rename"), "naming it and the cure: $why")
    }

    // ---- 6. cycles, refused by name at bind time ----

    /** `a → b → a` is refused **when it is asked for**, by name — never discovered as a hang. */
    @Test
    fun aCycleIsRefusedByNameAtBindTime() {
        val ed = Editor()
        val a = ed.doc.newParameter("a", 10.0.mm)
        val b = ed.doc.newParameter("b", 20.0.mm)
        val c = ed.doc.newParameter("c", 30.0.mm)

        assertTrue(ed.doc.bindParameter(b, "a * 2"), "b follows a")
        assertTrue(ed.doc.bindParameter(c, "b + 1mm"), "and c follows b")

        assertFalse(ed.doc.bindParameter(a, "c / 3"), "so a may not follow c")
        val why = assertNotNull(ed.doc.note, "the refusal speaks")
        assertTrue(why.contains("c") && why.contains("a"), "naming both ends: $why")
        assertNull(ed.doc.expressionOf(a), "and nothing was rewired")
        assertClose(Evaluator().scalar(a.ref).mm, 10.0, tol = 1e-9, msg = "a is what it was")

        assertFalse(ed.doc.bindParameter(a, "a + 1mm"), "and the shortest cycle of all is refused too")
        assertEquals(0, ed.doc.journal.count { it.kind == "bind" && it.args.isEmpty() }, "a refusal records no step")
    }

    /**
     * A **measurement** (OP-4) is a scalar like any other, so an expression may read one — which is the
     * whole point of measurements and expressions living in the same graph. And the reference counts: a
     * value an expression reads is *in use*, so it cannot be quietly taken back out of the panel.
     */
    @Test
    fun anExpressionReadsAMeasurementAndTheReferenceCounts() {
        val ed = circleDoc()
        val r = scalarNamed(ed, "r")
        val d = scalarNamed(ed, "d")
        assertTrue(ed.doc.bindParameter(r, "d/2 + 1mm"), "bound")
        assertFalse(ed.doc.retractParameter(d), "d is read by a formula, so it stays")

        // a measured length drives a parameter, through the very same binding
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(30.0, 40.0))
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(30.0, 40.0))
        val seg = ed.doc.elements.last { it.kind == ElementKind.SEGMENT }
        val m = assertNotNull(ed.doc.measureLength(seg), "the segment is measured")
        assertTrue(ed.doc.bindParameter(d, "${m.name} / 5"), "and a parameter follows the measurement: ${ed.doc.note}")
        assertClose(Evaluator().scalar(d.ref).mm, 10.0, tol = 1e-9, msg = "50 / 5")
        assertClose(radiusOf(ed.doc.elements.single { it.kind == ElementKind.CIRCLE }), 6.0, tol = 1e-9, msg = "10/2 + 1")
    }

    // ---- the panel's entry rule: a number is a number ----

    /**
     * **What is typed into the formula field.** Blank frees the value; one number — with or without a unit,
     * negative or not — is *today's plain value edit* and no binding at all; anything else is an expression.
     */
    @Test
    fun aTypedNumberInTheFormulaFieldStaysANumber() {
        val ed = circleDoc()
        val r = scalarNamed(ed, "r")

        assertTrue(ed.bindParameter(r, "12"), "a bare number is read in the panel's display unit")
        assertNull(ed.doc.expressionOf(r), "and binds nothing")
        assertClose(Evaluator().scalar(r.ref).mm, 12.0, tol = 1e-9, msg = "12 mm")

        assertTrue(ed.bindParameter(r, "1.5cm"), "a unit is taken at its word")
        assertClose(Evaluator().scalar(r.ref).mm, 15.0, tol = 1e-9, msg = "canonicalized to mm")
        assertNull(ed.doc.expressionOf(r), "still no binding")

        assertFalse(ed.bindParameter(r, "15deg"), "and a unit of the wrong dimension is refused, not bound")
        assertTrue(ed.statusHint.contains("millimetres"), "saying what the field means: ${ed.statusHint}")

        assertTrue(ed.bindParameter(r, "d/2"), "anything else is an expression")
        assertEquals("d/2", ed.doc.expressionOf(r), "bound")
        assertEquals(0, ed.doc.journal.count { it.kind == "bind" && it.args.size != 3 }, "one well-formed step")
    }

    /** An unparseable expression names the **position** and what was expected; an unknown name says which. */
    @Test
    fun aRefusalNamesThePositionOrTheName() {
        val ed = circleDoc()
        val r = scalarNamed(ed, "r")

        assertFalse(ed.doc.bindParameter(r, "d/"), "half an expression")
        assertTrue(assertNotNull(ed.doc.note).contains("position 3"), "position named: ${ed.doc.note}")

        assertFalse(ed.doc.bindParameter(r, "d/2 + q"), "an unknown name")
        assertTrue(assertNotNull(ed.doc.note).contains("'q'"), "the name named: ${ed.doc.note}")

        assertFalse(ed.doc.bindParameter(r, "wobble(d)"), "an unknown function")
        val why = assertNotNull(ed.doc.note)
        assertTrue(why.contains("wobble") && why.contains("sqrt"), "and it says what there is: $why")

        assertNull(ed.doc.expressionOf(r), "nothing was bound by any of them")
    }

    // ---- the language itself ----

    /** Precedence, associativity, unary minus, parentheses — deterministic, because the text is the record. */
    @Test
    fun theGrammarIsTheOrdinaryOne() {
        fun v(s: String): Double = ExprEval.eval(ExprParser.parse(s)) { null }.value
        assertClose(v("1 + 2 * 3"), 7.0, tol = 0.0, msg = "* binds tighter")
        assertClose(v("(1 + 2) * 3"), 9.0, tol = 0.0, msg = "parentheses")
        assertClose(v("10 - 3 - 2"), 5.0, tol = 0.0, msg = "- is left-associative")
        assertClose(v("2 ^ 3 ^ 2"), 512.0, tol = 0.0, msg = "^ is right-associative")
        assertClose(v("-2 ^ 2"), -4.0, tol = 0.0, msg = "^ binds tighter than unary minus")
        assertClose(v("--3"), 3.0, tol = 0.0, msg = "unary minus nests")
        assertClose(v("PI"), PI, tol = 0.0, msg = "the constant")
        assertClose(v("pi"), PI, tol = 0.0, msg = "…under either spelling")
        assertClose(v("E"), kotlin.math.E, tol = 0.0, msg = "and its neighbour")
    }

    /** The `java.lang.Math` vocabulary the user asked for, under Math's own names. */
    @Test
    fun theVocabularyIsMathsOwn() {
        fun q(s: String): Quantity = ExprEval.eval(ExprParser.parse(s)) { null }
        assertClose(q("abs(0mm - 3mm)").mm, 3.0, tol = 0.0, msg = "abs")
        assertClose(q("min(3mm, 5mm)").mm, 3.0, tol = 0.0, msg = "min")
        assertClose(q("max(3mm, 5mm)").mm, 5.0, tol = 0.0, msg = "max")
        assertClose(q("sqrt(16mm * 16mm)").mm, 16.0, tol = 1e-12, msg = "sqrt halves the exponents")
        assertClose(q("cbrt(27)").value, 3.0, tol = 1e-12, msg = "cbrt")
        assertClose(q("hypot(3mm, 4mm)").mm, 5.0, tol = 1e-12, msg = "hypot")
        assertClose(q("atan2(1mm, 1mm)").deg, 45.0, tol = 1e-9, msg = "atan2 takes two lengths and yields an angle")
        assertClose(q("atan(1)").deg, 45.0, tol = 1e-9, msg = "atan")
        assertClose(q("acos(0)").deg, 90.0, tol = 1e-9, msg = "acos")
        assertClose(q("tan(45deg)").value, 1.0, tol = 1e-12, msg = "tan")
        assertClose(q("pow(2, 10)").value, 1024.0, tol = 0.0, msg = "pow")
        assertClose(q("exp(log(7))").value, 7.0, tol = 1e-12, msg = "exp and log")
        assertClose(q("log10(1000)").value, 3.0, tol = 1e-12, msg = "log10")
        assertClose(q("floor(2.7)").value, 2.0, tol = 0.0, msg = "floor")
        assertClose(q("ceil(2.1)").value, 3.0, tol = 0.0, msg = "ceil")
        assertClose(q("round(2.5)").value, 3.0, tol = 0.0, msg = "round")
        assertClose(q("sign(0 - 4)").value, -1.0, tol = 0.0, msg = "sign")
        assertClose(q("mod(7, 3)").value, 1.0, tol = 0.0, msg = "mod")

        // the dimensions the table promises
        assertEquals(Dimension.AREA, q("3mm * 4mm").dim, "× combines exponents")
        assertEquals(Dimension.AREA, q("3mm ^ 2").dim, "a whole-number power scales them")
        assertEquals(Dimension.NONE, q("3mm / 4mm").dim, "÷ combines them the other way")
        assertEquals(Dimension.ANGLE, q("asin(0.5)").dim, "a plain number in, an angle out")
        assertEquals(Dimension.NONE, q("sin(30deg)").dim, "and back again")
    }

    /** Every refusal the *values* can make, each one an error the node turns into invalidity — never a 0. */
    @Test
    fun theDimensionRulesRefuseByName() {
        fun bad(s: String): String =
            try {
                ExprEval.eval(ExprParser.parse(s)) { null }
                "no error at all"
            } catch (e: DimensionError) {
                e.message ?: ""
            } catch (e: ExprError) {
                e.message ?: ""
            }
        assertTrue(bad("1mm + 1deg").contains("add"), "+ demands equal dimension: ${bad("1mm + 1deg")}")
        assertTrue(bad("min(1mm, 1deg)").contains("same dimension"), "and so does min")
        assertTrue(bad("sqrt(1mm)").contains("divisible"), "sqrt refuses an odd exponent by name: ${bad("sqrt(1mm)")}")
        assertTrue(bad("cbrt(1mm)").contains("divisible"), "cbrt likewise")
        assertTrue(bad("sin(1mm)").contains("angle"), "sin takes an angle (or a plain number of radians)")
        assertEquals("no error at all", bad("sin(0.5)"), "…and a plain number is read as radians (session 71)")
        assertTrue(bad("asin(1deg)").contains("plain number"), "asin does not")
        assertTrue(bad("exp(1mm)").contains("plain number"), "exp does not either")
        assertTrue(bad("round(1mm)").contains(ExprEval.ROUNDING_NOTE), "and rounding says which unit it would round in")
        assertTrue(bad("2mm ^ 0.5").contains("whole-number"), "a fractional power of a length is refused")
        assertTrue(bad("2 ^ 1mm").contains("exponent"), "and a dimensioned exponent always is")
        assertTrue(bad("1 / 0").contains("zero"), "division by zero is an error, not an infinity")
        assertTrue(bad("sqrt(0 - 1)").contains("negative"), "and so is a root of a negative")
        assertTrue(bad("asin(2)").contains("defined between"), "…and a domain a value has left")
    }

    /** The parser refuses what it cannot read, at the position it could not read it. */
    @Test
    fun theParserRefusesWithAPosition() {
        assertTrue(assertFailsWith<ExprError> { ExprParser.parse("1 +") }.message!!.contains("position 4"))
        assertTrue(assertFailsWith<ExprError> { ExprParser.parse("(1 + 2") }.message!!.contains("')'"))
        assertTrue(assertFailsWith<ExprError> { ExprParser.parse("1 @ 2") }.message!!.contains("position 3"))
        assertTrue(assertFailsWith<ExprError> { ExprParser.parse("min(1)") }.message!!.contains("2 arguments"))
        assertTrue(assertFailsWith<ExprError> { ExprParser.parse("3furlong") }.message!!.contains("unknown unit"))
        assertTrue(assertFailsWith<ExprError> { ExprParser.parse("wobble(1)") }.message!!.contains("unknown function"))
        assertTrue(assertFailsWith<ExprError> { ExprParser.parse("  ") }.message!!.contains("blank"))
        // …and a function name written without its arguments is not a parse error at all: it is an
        // ordinary name (the parser reserves nothing), so the *binding* is what has something to say
        assertEquals("sqrt", (ExprParser.parse("sqrt") as Expr.Ref).name, "a bare 'sqrt' parses as a reference")
    }

    /**
     * **A name the editor allows must be a name the file can read** — the defect a probe found in this
     * package's first cut, and the reason the parser has no reserved words at all.
     *
     * Renaming a referenced parameter to `sin` was accepted, the re-stamp wrote `bind "r" = "sin/2 + 1mm"`,
     * and the **live** drawing went on working (the bound node keeps its parsed AST) — while the *file*
     * became unloadable, because the parser met `sin` with no argument list and called it a misplaced
     * function. A legal editor operation that writes a file the next session cannot open is worse than a
     * wrong number: it is loss at the very seam the format doctrine exists to protect. Hence the rule that
     * a `(` is the only thing that makes a word a function — and hence a regression that goes **through
     * the file**, since live evaluation is precisely what did not catch it.
     */
    @Test
    fun aParameterNamedForAFunctionSurvivesTheFile() {
        val ed = circleDoc()
        val r = scalarNamed(ed, "r")
        assertTrue(ed.doc.bindParameter(r, "d/2 + 1mm"))
        assertEquals("sin", ed.doc.renameParameter(scalarNamed(ed, "d"), "sin"), "a function's name is an ordinary name")
        assertEquals("sin/2 + 1mm", ed.doc.expressionOf(r), "re-stamped")

        val text = roundTrips(ed, "and the file this writes is a file this build can read")
        assertTrue(text.contains("bind \"r\" = \"sin/2 + 1mm\""), "the stored text:\n$text")
        val reloaded = DocumentFormat.load(text)
        assertClose(
            radiusOf(reloaded.elements.single { it.kind == ElementKind.CIRCLE }),
            11.0,
            tol = 1e-9,
            msg = "the reloaded drawing is the same drawing",
        )

        // both readings stand in one text, told apart by the '(' and nothing else
        val t = ed.doc.newParameter("t", 0.0.mm)
        assertTrue(ed.doc.bindParameter(t, "sin + sin(90deg)*1mm"), "${ed.doc.note}")
        assertClose(Evaluator().scalar(t.ref).mm, 21.0, tol = 1e-9, msg = "the reference plus the function")
        roundTrips(ed, "and that round-trips too")

        // the same holds one step further: a parameter may be named for a *constant* and still be itself
        val ed2 = circleDoc()
        assertTrue(ed2.doc.bindParameter(scalarNamed(ed2, "r"), "d/2 + 1mm"))
        assertEquals("PI", ed2.doc.renameParameter(scalarNamed(ed2, "d"), "PI"), "a constant's name too")
        assertClose(radiusOf(ed2.doc.elements.single { it.kind == ElementKind.CIRCLE }), 11.0, tol = 1e-9, msg = "20/2 + 1, not π/2 + 1")
        val text2 = roundTrips(ed2, "a constant-named reference round-trips")
        assertClose(
            radiusOf(DocumentFormat.load(text2).elements.single { it.kind == ElementKind.CIRCLE }),
            11.0,
            tol = 1e-9,
            msg = "and reloads as the drawing's own value, not as π",
        )
    }

    /** A function name nothing carries still refuses **helpfully**: it says it is a function, and how to call it. */
    @Test
    fun aFunctionWithoutItsArgumentsSaysSo() {
        val ed = circleDoc()
        assertFalse(ed.doc.bindParameter(scalarNamed(ed, "r"), "sqrt * 2"), "nothing is named 'sqrt' here")
        val why = assertNotNull(ed.doc.note, "the refusal speaks")
        assertTrue(why.contains("no value named 'sqrt'"), "naming what is missing: $why")
        assertTrue(why.contains("sqrt(…)"), "and pointing at the call form: $why")
    }
}
