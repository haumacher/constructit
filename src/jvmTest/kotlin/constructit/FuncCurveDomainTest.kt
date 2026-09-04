package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.FuncCurveValue
import constructit.dsl.valueOf
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.geom.FuncCurves
import constructit.geom.Vec2
import constructit.units.Quantity
import constructit.units.mm
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **An expression-valued domain** for function curves (the session-76 entry, item b; the curve half's cut 4,
 * closed): `[t0, t1]` is two *expressions* over named scalars, of which two plain numbers are the degenerate
 * case.
 *
 * The mechanism is the scalar half's own, one argument on: the end's source node is **bound** to an
 * `ExprNode` (`boundTo` generalized to a function), so a gear flank's length follows a teeth-count parameter
 * by plain recompute, the inspector's field goes read-only because the value is derived, the text is the
 * record and is re-stamped on rename, and the stored `from=`/`to=` argument simply gained an alternative form
 * — so no stored literal changed meaning and no version bump is owed (OP-18).
 */
class FuncCurveDomainTest {
    /** The true involute of a circle of radius [r] at [t] — computed here, not by the engine. */
    private fun involute(
        r: Double,
        t: Double,
    ) = Vec2(r * (cos(t) + t * sin(t)), r * (sin(t) - t * cos(t)))

    private fun roundTrip(ed: Editor): String {
        val once = DocumentFormat.save(ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "save -> load -> save must be byte-equal")
        return once
    }

    private fun curveOf(el: Element) =
        assertNotNull((Evaluator().valueOf(el.ref) as? FuncCurveValue)?.curve, "the curve must have a value")

    /** The involute of `r`, its domain running from 0 to whatever the text [to] says. */
    private fun flank(to: String): Pair<Editor, Element> {
        val ed = Editor()
        ed.doc.newParameter("r", 20.0.mm)
        ed.doc.newParameter("T", Quantity.number(1.6))
        val el =
            assertNotNull(
                ed.addFunctionCurve("r * (cos(t) + t * sin(t))", "r * (sin(t) - t * cos(t))", "0", to),
                "the flank must build: ${ed.statusHint}",
            )
        return ed to el
    }

    // ---- the acceptance: a flank whose length follows a parameter ----

    @Test
    fun theFlankRunsToWhatTheParameterSaysAndFollowsIt() {
        val (ed, el) = flank("T")
        assertClose(curveOf(el).t1, 1.6, 1e-12, "the domain is the parameter's value")
        val was = FuncCurves.arcLength(curveOf(el))
        val end = assertNotNull(FuncCurves.pointAt(curveOf(el), curveOf(el).t1))
        val want = involute(20.0, 1.6)
        assertClose(end.x, want.x, 1e-9, "and the flank ends on the true involute there")
        assertClose(end.y, want.y, 1e-9, "likewise y")

        // editing the parameter **extends the flank** — one recompute, nothing rebuilt
        ed.doc.setParameter(ed.doc.scalars.first { it.name == "T" }, Quantity.number(2.4))
        assertClose(curveOf(el).t1, 2.4, 1e-12, "the domain followed T")
        val longer = assertNotNull(FuncCurves.pointAt(curveOf(el), curveOf(el).t1))
        val wantLonger = involute(20.0, 2.4)
        assertClose(longer.x, wantLonger.x, 1e-9, "the new end is the involute at 2.4")
        assertClose(longer.y, wantLonger.y, 1e-9, "likewise y")
        assertTrue(FuncCurves.arcLength(curveOf(el)) > was, "and the flank is longer than it was")
    }

    /** A domain end may be an arithmetic expression, not merely a bare reference. */
    @Test
    fun aDomainEndIsAnyExpressionOverTheNames() {
        val (_, el) = flank("T * 2 - 0.6")
        assertClose(curveOf(el).t1, 2.6, 1e-12, "1.6 * 2 - 0.6")
    }

    /** What is derived is read-only: the inspector's own field says so, exactly as a wired value's does. */
    @Test
    fun aDerivedDomainEndIsNotWritableAndAPlainOneIs() {
        val (_, el) = flank("T")
        val fields = assertNotNull(el.handle).fields()
        val from = assertNotNull(fields.firstOrNull { it.label == "from" })
        val to = assertNotNull(fields.firstOrNull { it.label == "to" })
        assertTrue(from.writable, "the plain end is still typed and dragged")
        assertTrue(!to.writable, "the derived end reads but cannot be written")
        assertClose(assertNotNull(to.read(Evaluator())).base, 1.6, 1e-12, "and it reads what drives it")
    }

    // ---- the file ----

    @Test
    fun theDomainTextIsStoredVerbatimAndRoundTrips() {
        val (ed, _) = flank("T * 2")
        val text = roundTrip(ed)
        assertTrue(text.contains("from=0"), "a plain end is still a plain number:\n$text")
        assertTrue(text.contains("to=\"T * 2\""), "and a formula is the text itself, quoted:\n$text")
    }

    /** A rename re-stamps the domain, in the file — the same claim the two coordinate texts carry. */
    @Test
    fun renamingTheParameterRestampsTheDomainInTheFile() {
        val (ed, el) = flank("T * 2")
        assertEquals("teeth", ed.doc.renameParameter(ed.doc.scalars.first { it.name == "T" }, "teeth"), "renamed")
        val text = roundTrip(ed)
        assertTrue(text.contains("to=\"teeth * 2\""), "the domain reads the new name:\n$text")
        assertTrue(!text.contains("\"T * 2\""), "and the old one is nowhere in it:\n$text")
        assertClose(curveOf(el).t1, 3.2, 1e-12, "nothing moved")
    }

    /**
     * **An old file loads unchanged.** The domain argument gained an alternative form and the old form kept
     * its meaning exactly (OP-18), which is why no version bump is owed — asserted on a hand-written script in
     * the pre-session-76 form rather than on one this build wrote.
     */
    @Test
    fun aPlainNumberDomainWrittenBeforeThisStillLoads() {
        val script =
            """
            constructit 3
            param "r" = 20mm
            funccurve "r * (cos(t) + t * sin(t))" "r * (sin(t) - t * cos(t))" from=0 to=1.6 -> e1
            """.trimIndent() + "\n"
        val doc = DocumentFormat.load(script)
        val el = assertNotNull(doc.elements.firstOrNull { it.kind == ElementKind.FUNC_CURVE })
        assertClose(curveOf(el).t1, 1.6, 1e-12, "the domain is the number it always was")
        assertEquals(atThisVersion(script), DocumentFormat.save(doc), "and it is written back byte for byte")
    }

    // ---- the values: dimensionless, and it says so ----

    /**
     * A **dimensioned domain** is the named invalidity that heals (OP-3) rather than a refusal, which is the
     * scalar half's own decision restated: a dimension violation is a property of the *values*, so the
     * expression is legal to write, the curve says why it has no value and quotes both ends' dimensions, and
     * the moment the value is a plain number the whole cone comes back.
     *
     * The status line says it at the gesture, so nothing is silent.
     */
    @Test
    fun aDimensionedDomainSaysSoAndHeals() {
        val ed = Editor()
        ed.doc.newParameter("r", 20.0.mm)
        val w = ed.doc.newParameter("w", 30.0.mm)
        val el =
            assertNotNull(
                ed.addFunctionCurve("r * (cos(t) + t * sin(t))", "r * (sin(t) - t * cos(t))", "0", "w"),
                "the curve is legal to *write*: ${ed.statusHint}",
            )
        val why = assertNotNull((Evaluator().eval(el.ref.node) as? EvalResult.Invalid)?.reason, "and it has no value")
        assertTrue(why.contains("domain") && why.contains("plain"), "naming what a domain is: $why")
        assertTrue(ed.statusHint.contains("domain"), "the status line says it too: ${ed.statusHint}")

        // ...and it heals the moment the value it reads is a plain number
        ed.doc.setParameter(w, Quantity.number(2.0))
        assertNull((Evaluator().eval(el.ref.node) as? EvalResult.Invalid), "the curve is back")
        assertClose(curveOf(el).t1, 2.0, 1e-12, "over the domain the number now states")
        roundTrip(ed)
    }

    /** A unit written straight into the field is the same violation, not a number with its unit dropped. */
    @Test
    fun aUnitTypedIntoTheDomainIsNotReadAsItsNumber() {
        val ed = Editor()
        ed.doc.newParameter("r", 20.0.mm)
        val el = assertNotNull(ed.addFunctionCurve("r * cos(t)", "r * sin(t)", "0", "2mm"), ed.statusHint)
        val why = assertNotNull((Evaluator().eval(el.ref.node) as? EvalResult.Invalid)?.reason, "it has no value")
        assertTrue(why.contains("domain"), "and says why: $why")
    }

    /** A name the drawing does not carry is refused **by name**, before anything is built. */
    @Test
    fun anUnknownNameInTheDomainIsRefusedByName() {
        val ed = Editor()
        ed.doc.newParameter("r", 20.0.mm)
        assertNull(ed.addFunctionCurve("r * cos(t)", "r * sin(t)", "0", "teeth"), "nothing is named teeth")
        assertTrue(ed.statusHint.contains("teeth"), "by name: ${ed.statusHint}")
        assertNull(ed.addFunctionCurve("r * cos(t)", "r * sin(t)", "0", "2 *"), "and a text that is no expression")
        assertTrue(ed.statusHint.contains("position"), "names the position: ${ed.statusHint}")
        assertEquals(0, ed.doc.journal.count { it.kind == "funccurve" }, "neither left a step behind")
    }

    /** The delete cascade follows a domain's reference like any other — a `funccurve` naming nothing cannot load. */
    @Test
    fun theDomainsReferenceCountsAsUse() {
        val (ed, _) = flank("T")
        assertTrue(!ed.doc.retractParameter(ed.doc.scalars.first { it.name == "T" }), "T is read by the domain, so it stays")
    }
}
