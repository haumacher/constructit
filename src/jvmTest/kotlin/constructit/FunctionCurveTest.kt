package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.FuncCurveValue
import constructit.core.PointValue
import constructit.dsl.valueOf
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.expr.Derive
import constructit.expr.DeriveError
import constructit.expr.ExprEval
import constructit.expr.ExprParser
import constructit.geom.FuncCurves
import constructit.geom.Vec2
import constructit.units.Quantity
import constructit.units.mm
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The **function curve**: the session-71 expressions entry's curve half — `x(t)`, `y(t)` over a stated
 * domain, the symbolic derivative, and the honesty classes OP-24 wrote down one curve family earlier.
 *
 * The acceptance is the **involute**, and it is asserted against the closed form computed here rather than
 * against anything the engine's AST produces: `x(t) = r(cos t + t sin t)`, `y(t) = r(sin t − t cos t)`.
 */
class FunctionCurveTest {
    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerDown(s)
        pointerUp(s)
    }

    private fun Editor.at(el: Element): Vec2 = assertNotNull((Evaluator().valueOf(el.ref) as? PointValue)?.p)

    private fun roundTrip(ed: Editor): String {
        val once = DocumentFormat.save(ed.doc)
        val back = DocumentFormat.load(once)
        assertEquals(once, DocumentFormat.save(back), "save -> load -> save must be byte-equal")
        return once
    }

    /** The true involute of a circle of radius [r] at parameter [t] — computed here, not by the engine. */
    private fun involute(
        r: Double,
        t: Double,
    ) = Vec2(r * (cos(t) + t * sin(t)), r * (sin(t) - t * cos(t)))

    private fun involuteEditor(
        r: Double = 20.0,
        to: Double = 1.6,
    ): Pair<Editor, Element> {
        val ed = Editor()
        ed.doc.newParameter("r", r.mm)
        val el =
            assertNotNull(
                ed.addFunctionCurve("r * (cos(t) + t * sin(t))", "r * (sin(t) - t * cos(t))", 0.0, to),
                "the involute must build",
            )
        return ed to el
    }

    private fun curveOf(
        ed: Editor,
        el: Element,
    ) = assertNotNull((Evaluator().valueOf(el.ref) as? FuncCurveValue)?.curve, "${ed.doc.nameOf(el)} must be a curve")

    // ---- the value: exact points, exact tangents ----

    @Test
    fun theDrawnInvoluteLiesOnTheTrueInvoluteToAPart() {
        val (ed, el) = involuteEditor(r = 20.0, to = 2.4)
        val c = curveOf(ed, el)
        for (i in 0..40) {
            val t = 2.4 * i / 40.0
            val p = assertNotNull(FuncCurves.pointAt(c, t))
            val want = involute(20.0, t)
            assertClose(p.x, want.x, 1e-9, "x at t=$t")
            assertClose(p.y, want.y, 1e-9, "y at t=$t")
        }
    }

    @Test
    fun theTangentIsTheSymbolicDerivativeAndNotADifference() {
        val (ed, el) = involuteEditor(r = 20.0, to = 2.4)
        val c = curveOf(ed, el)
        for (i in 1..20) {
            val t = 2.4 * i / 20.0
            val d = assertNotNull(FuncCurves.tangentAt(c, t))
            // d/dt of the involute is exactly (r t cos t, r t sin t)
            assertClose(d.x, 20.0 * t * cos(t), 1e-9, "dx at t=$t")
            assertClose(d.y, 20.0 * t * sin(t), 1e-9, "dy at t=$t")
        }
    }

    @Test
    fun aRiderSitsAtTheParameterAndItsTangentIsTheDerivative() {
        val (ed, el) = involuteEditor(r = 20.0, to = 2.4)
        val c = curveOf(ed, el)
        val want = involute(20.0, 1.2)
        ed.setTool(Tools.POINT)
        ed.click(want)
        val rider = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.ON_CURVE }, "a rider must be made")
        val param = assertNotNull(ed.doc.riderParam(rider))
        param.value = constructit.core.ScalarValue(Quantity.number(1.2))
        val p = ed.at(rider)
        assertClose(p.x, want.x, 1e-9, "the rider sits at the closed form")
        assertClose(p.y, want.y, 1e-9)
        val d = assertNotNull(FuncCurves.tangentAt(c, 1.2))
        assertClose(d.x, 20.0 * 1.2 * cos(1.2), 1e-9)
        assertClose(d.y, 20.0 * 1.2 * sin(1.2), 1e-9)
    }

    @Test
    fun draggingTheRiderMovesAlongTheCurveAndRestatesItsParameter() {
        val (ed, el) = involuteEditor(r = 20.0, to = 2.4)
        val c = curveOf(ed, el)
        ed.setTool(Tools.POINT)
        ed.click(involute(20.0, 0.6))
        val rider = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.ON_CURVE })
        val before = assertNotNull(ed.doc.riderParam(rider)).value as constructit.core.ScalarValue
        assertClose(before.q.base, 0.6, 1e-6, "the click states the parameter it landed on")
        ed.setTool(Tools.SELECT)
        val target = involute(20.0, 1.8)
        val from = ed.camera.worldToScreen(ed.at(rider))
        ed.pointerDown(from)
        ed.pointerMove(ed.camera.worldToScreen(target))
        ed.pointerUp(ed.camera.worldToScreen(target))
        val after = (assertNotNull(ed.doc.riderParam(rider)).value as constructit.core.ScalarValue).q.base
        assertClose(after, 1.8, 1e-5, "the drag restates the parameter")
        val p = ed.at(rider)
        assertTrue((p - assertNotNull(FuncCurves.pointAt(c, after))).length() < 1e-9, "the rider stays on the curve")
    }

    @Test
    fun editingTheParameterMovesTheCurveAndEverythingDownstream() {
        val (ed, _) = involuteEditor(r = 20.0, to = 2.4)
        ed.setTool(Tools.POINT)
        ed.click(involute(20.0, 1.2))
        val rider = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.ON_CURVE })
        val r = assertNotNull(ed.doc.scalars.firstOrNull { it.name == "r" })
        ed.doc.setParameter(r, 30.0.mm)
        val t = (assertNotNull(ed.doc.riderParam(rider)).value as constructit.core.ScalarValue).q.base
        val want = involute(30.0, t)
        val p = ed.at(rider)
        assertClose(p.x, want.x, 1e-9, "a plain recompute moves the curve and its rider")
        assertClose(p.y, want.y, 1e-9)
    }

    // ---- dimensions: named invalidity that heals, both ways ----

    @Test
    fun aCoordinateThatIsNotALengthIsNamedInvalidityThatHeals() {
        val ed = Editor()
        ed.doc.newParameter("r", 20.0.mm)
        // x(t) comes out as an *angle*, which is not a coordinate
        val el = assertNotNull(ed.addFunctionCurve("t * 1deg", "r * t", 0.0, 1.0))
        val why = (Evaluator().eval(el.ref.node) as? EvalResult.Invalid)?.reason
        assertNotNull(why, "a curve whose x(t) is an angle must be invalid")
        assertTrue(why.contains("x(t)") && why.contains("length"), "the reason names the coordinate: $why")
        // …and it heals, with no repair and no deletion
        val fixed = assertNotNull(ed.addFunctionCurve("r * t", "r * t", 0.0, 1.0))
        assertTrue(Evaluator().eval(fixed.ref.node) is EvalResult.Ok, "the corrected curve is a curve")
    }

    @Test
    fun aDimensionedUseOfTheParameterIsNamedInvalidityToo() {
        val ed = Editor()
        ed.doc.newParameter("r", 20.0.mm)
        // the parameter is dimensionless, so adding a length to it is the ordinary dimension violation
        val el = assertNotNull(ed.addFunctionCurve("t + 1mm", "r * t", 0.0, 1.0))
        val why = (Evaluator().eval(el.ref.node) as? EvalResult.Invalid)?.reason
        assertNotNull(why, "t + 1mm must not be a coordinate")
        assertTrue(why.contains("add") || why.contains("dimension") || why.contains("L"), "the reason says why: $why")
    }

    @Test
    fun aBackwardsDomainRefusesByName() {
        val ed = Editor()
        ed.doc.newParameter("r", 20.0.mm)
        val el = assertNotNull(ed.addFunctionCurve("r * t", "r * t", 2.0, 1.0))
        val why = assertNotNull((Evaluator().eval(el.ref.node) as? EvalResult.Invalid)?.reason)
        assertTrue(why.contains("forwards"), "a backwards domain says so: $why")
    }

    @Test
    fun anUnknownNameRefusesWithTheNameAndBuildsNothing() {
        val ed = Editor()
        val before = ed.doc.elements.size
        assertNull(ed.addFunctionCurve("q * cos(t)", "q * sin(t)", 0.0, 1.0))
        assertEquals(before, ed.doc.elements.size, "a refusal builds nothing")
        assertTrue(assertNotNull(ed.statusHint).contains("'q'"), "the refusal names the name: ${ed.statusHint}")
    }

    @Test
    fun anUnreadableTextRefusesWithThePosition() {
        val ed = Editor()
        assertNull(ed.addFunctionCurve("2 * ", "3", 0.0, 1.0))
        assertTrue(assertNotNull(ed.statusHint).contains("position"), "the refusal names the position: ${ed.statusHint}")
    }

    // ---- the differentiator: coverage, and the refusal set ----

    @Test
    fun theDifferentiatorCoversTheVocabularyItClaims() {
        val cases =
            listOf(
                "t * t" to { t: Double -> 2 * t },
                "t ^ 3" to { t: Double -> 3 * t * t },
                "sqrt(t)" to { t: Double -> 0.5 / kotlin.math.sqrt(t) },
                "cbrt(t)" to { t: Double -> 1.0 / (3.0 * kotlin.math.cbrt(t) * kotlin.math.cbrt(t)) },
                "sin(t)" to { t: Double -> cos(t) },
                "cos(t)" to { t: Double -> -sin(t) },
                "tan(t)" to { t: Double -> 1.0 / (cos(t) * cos(t)) },
                "exp(t)" to { t: Double -> kotlin.math.exp(t) },
                "log(t)" to { t: Double -> 1.0 / t },
                "log10(t)" to { t: Double -> 1.0 / (t * kotlin.math.ln(10.0)) },
                "atan(t)" to { t: Double -> 1.0 / (1.0 + t * t) },
                "asin(t)" to { t: Double -> 1.0 / kotlin.math.sqrt(1.0 - t * t) },
                "acos(t)" to { t: Double -> -1.0 / kotlin.math.sqrt(1.0 - t * t) },
                "atan2(t, 2)" to { t: Double -> 2.0 / (t * t + 4.0) },
                "hypot(t, 2)" to { t: Double -> t / kotlin.math.hypot(t, 2.0) },
                "abs(t)" to { _: Double -> 1.0 },
                "1 / t" to { t: Double -> -1.0 / (t * t) },
                "2 ^ t" to { t: Double -> kotlin.math.ln(2.0) * 2.0.pow(t) },
            )
        for ((text, truth) in cases) {
            val d = Derive.d(ExprParser.parse(text), "t")
            val t = 0.37
            // the inverse trigonometric ones come back as an **angle**, which is the dimension rule working
            val got = ExprEval.eval(d) { n -> if (n == "t") Quantity.number(t) else null }
            assertClose(got.base, truth(t), 1e-9, "d/dt $text")
        }
    }

    @Test
    fun theRefusalSetIsNamedAndTheCurveStillDraws() {
        for (op in listOf("floor", "ceil", "round", "sign")) {
            val e = ExprParser.parse("$op(t)")
            val why =
                try {
                    Derive.d(e, "t")
                    null
                } catch (err: DeriveError) {
                    err.message
                }
            assertNotNull(why, "$op must refuse a derivative")
            assertTrue(why.contains("'$op'"), "the refusal names the function: $why")
        }
        for (op in listOf("min(t, 2)", "max(t, 2)", "mod(t, 2)")) {
            var refused = false
            try {
                Derive.d(ExprParser.parse(op), "t")
            } catch (err: DeriveError) {
                refused = true
            }
            assertTrue(refused, "$op must refuse a derivative")
        }
    }

    @Test
    fun aCurveWithNoStatableDerivativeDrawsAndRidesAndRefusesTheTangent() {
        val ed = Editor()
        ed.doc.newParameter("r", 20.0.mm)
        val el = assertNotNull(ed.addFunctionCurve("r * t", "r * floor(t * 4) / 4", 0.0, 2.0))
        val c = curveOf(ed, el)
        assertNull(c.dx, "there is no derivative")
        assertNotNull(c.noTangent, "and the curve says which function stopped it")
        // it still has points, and a rider still rides it
        assertNotNull(FuncCurves.pointAt(c, 1.0))
        assertNull(FuncCurves.tangentAt(c, 1.0), "…but no tangent")
        ed.setTool(Tools.POINT)
        ed.click(assertNotNull(FuncCurves.pointAt(c, 1.0)))
        assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.ON_CURVE }, "a rider needs no derivative")
        val why = assertNotNull(ed.doc.funcTangentRefusal(el))
        assertTrue(why.contains("'floor'"), "the refusal names the function: $why")
    }

    // ---- persistence: the text is the record ----

    @Test
    fun theTwoTextsSurviveTheFileVerbatim() {
        val (ed, _) = involuteEditor(r = 20.0, to = 1.6)
        val text = roundTrip(ed)
        assertTrue(text.contains("\"r * (cos(t) + t * sin(t))\""), "x(t) is stored verbatim, spacing included:\n$text")
        assertTrue(text.contains("\"r * (sin(t) - t * cos(t))\""), "y(t) is stored verbatim:\n$text")
        assertTrue(text.contains("from=0") && text.contains("to=1.6"), "the domain is restated:\n$text")
    }

    @Test
    fun aRenameReStampsTheStoredTextAndTheFileStillLoads() {
        val (ed, _) = involuteEditor(r = 20.0, to = 1.6)
        val r = assertNotNull(ed.doc.scalars.firstOrNull { it.name == "r" })
        assertEquals("module", ed.doc.renameParameter(r, "module"))
        val text = roundTrip(ed)
        assertTrue(text.contains("\"module * (cos(t) + t * sin(t))\""), "the rename re-stamps the curve's text:\n$text")
        val back = DocumentFormat.load(text)
        val el = assertNotNull(back.elements.firstOrNull { it.kind == ElementKind.FUNC_CURVE })
        val c = assertNotNull((Evaluator().valueOf(el.ref) as? FuncCurveValue)?.curve)
        val p = assertNotNull(FuncCurves.pointAt(c, 1.0))
        assertClose(p.x, involute(20.0, 1.0).x, 1e-9, "and the reloaded curve is the same curve")
    }

    @Test
    fun aRenameThatWouldCaptureTheParameterIsRefusedByName() {
        val (ed, _) = involuteEditor()
        val r = assertNotNull(ed.doc.scalars.firstOrNull { it.name == "r" })
        assertNull(ed.doc.renameParameter(r, "t"), "renaming a referenced scalar to the binder is refused")
        assertEquals("r", r.name, "and the name is left alone")
        assertTrue(assertNotNull(ed.doc.note).contains("parameter"), "the refusal explains: ${ed.doc.note}")
    }

    @Test
    fun theDomainIsStateAndIsRestatedByItsOwnStep() {
        val (ed, el) = involuteEditor(r = 20.0, to = 1.6)
        val to = assertNotNull(el.handle?.fields()?.firstOrNull { it.label == "to" })
        to.write(Quantity.number(2.5))
        val text = roundTrip(ed)
        assertTrue(text.contains("to=2.5"), "the domain a field wrote comes back:\n$text")
    }

    // ---- intersections: numeric, deterministic, ordered along the curve ----

    @Test
    fun aLineMeetsTheCurveInAnOrderedSetThatSurvivesTheFile() {
        val (ed, el) = involuteEditor(r = 20.0, to = 3.0)
        val c = curveOf(ed, el)
        // a horizontal line through the middle of the involute's reach
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(-60.0, 12.0))
        ed.click(Vec2(80.0, 12.0))
        val seg = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.SEGMENT })
        val hit = assertNotNull(ed.doc.intersectNear(el, seg, Vec2(30.0, 12.0)), "the two must cross")
        val p = assertNotNull((Evaluator().valueOf(hit) as? PointValue)?.p)
        assertClose(p.y, 12.0, 1e-6, "the point is on the line")
        val t = FuncCurves.nearestParam(c, p)
        assertTrue((assertNotNull(FuncCurves.pointAt(c, t)) - p).length() < 1e-6, "…and on the curve")
        val text = roundTrip(ed)
        val back = DocumentFormat.load(text)
        val el2 = assertNotNull(back.elements.lastOrNull { it.kind == ElementKind.DERIVED_POINT })
        val q = assertNotNull((Evaluator().valueOf(el2.ref) as? PointValue)?.p)
        assertClose(q.x, p.x, 1e-12, "the branch is persisted, never re-scored")
        assertClose(q.y, p.y, 1e-12)
    }

    @Test
    fun twoFunctionCurvesRefuseByNameAndSayWhatDoesWork() {
        val (ed, a) = involuteEditor(r = 20.0, to = 2.0)
        val b = assertNotNull(ed.addFunctionCurve("r * t / 2", "r * t / 2", 0.0, 2.0))
        assertNull(ed.doc.intersectNear(a, b, Vec2(10.0, 10.0)))
        val why = assertNotNull(ed.doc.note)
        assertTrue(why.contains("function curves"), "the refusal names the case: $why")
        assertTrue(why.contains("line") && why.contains("circle"), "…and what does work: $why")
    }

    // ---- a citizen of the drawing ----

    @Test
    fun anAffineTransformComposesWithTheFunctionRatherThanFittingIt() {
        val (ed, el) = involuteEditor(r = 20.0, to = 2.0)
        val c = curveOf(ed, el)
        val mirrored = FuncCurves.transform(c, constructit.geom.Affine.reflection(constructit.geom.Line(Vec2(0.0, 0.0), Vec2(0.0, 1.0))))
        for (i in 0..10) {
            val t = 2.0 * i / 10.0
            val p = assertNotNull(FuncCurves.pointAt(mirrored, t))
            val want = involute(20.0, t)
            assertClose(p.x, -want.x, 1e-12, "the mirror is exact at t=$t")
            assertClose(p.y, want.y, 1e-12)
        }
        // …and so is the tangent, through the map's linear part
        val d = assertNotNull(FuncCurves.tangentAt(mirrored, 1.3))
        assertClose(d.x, -20.0 * 1.3 * cos(1.3), 1e-9)
        assertClose(d.y, 20.0 * 1.3 * sin(1.3), 1e-9)
    }

    @Test
    fun theCurveKnowsItsEndsAndIsHitWhereItIsDrawn() {
        val (ed, el) = involuteEditor(r = 20.0, to = 2.0)
        val keys = ed.doc.extractPoints(el)
        assertEquals(2, keys.size, "a function curve's key points are its two ends")
        val ev = Evaluator()
        val first = assertNotNull((ev.valueOf(keys[0]) as? PointValue)?.p)
        assertClose(first.x, 20.0, 1e-9, "the start is the point at t0")
        val mid = assertNotNull(FuncCurves.pointAt(curveOf(ed, el), 1.0))
        val d = assertNotNull(constructit.editor.HitTest.distanceTo(ev, el, mid))
        assertTrue(d < 0.5, "a point of the curve is on the curve for the hit test (was \$d)")
    }

    @Test
    fun theMeasuredLengthIsFlaggedApproximate() {
        val (ed, el) = involuteEditor(r = 20.0, to = 2.0)
        val len = assertNotNull(ed.doc.measureLength(el))
        val got = assertNotNull((Evaluator().eval(len.ref.node) as? EvalResult.Ok)?.value as? constructit.core.ScalarValue).q.mm
        // ∫|P'| dt = ∫ r t dt = r T²/2, exactly — which is what the numeric integral must reproduce
        assertClose(got, 20.0 * 2.0 * 2.0 / 2.0, 1e-6, "the involute's arc length")
        assertTrue(assertNotNull(ed.doc.note).contains("numerically"), "and it says it is numeric: ${ed.doc.note}")
    }

    @Test
    fun aBreakRefusesAndNamesTheDomainInstead() {
        val (ed, el) = involuteEditor(r = 20.0, to = 2.0)
        assertNull(ed.doc.breakCurve(el, assertNotNull(FuncCurves.pointAt(curveOf(ed, el), 1.0))))
        assertTrue(assertNotNull(ed.doc.note).contains("domain"), "the refusal names the way forward: ${ed.doc.note}")
    }

    // ---- what the 3D layer says about it, wholly and by name (the session-69 predicate rule) ----

    @Test
    fun anExtrudedFunctionCurveSweepsAFaceThisDrawingCannotName() {
        val (ed, el) = involuteEditor(r = 20.0, to = 2.0)
        val c = curveOf(ed, el)
        val plan = constructit.geom.Plane3(constructit.geom.Vec3(0.0, 0.0, 0.0), constructit.geom.Vec3(1.0, 0.0, 0.0), constructit.geom.Vec3(0.0, 1.0, 0.0))
        val loop =
            constructit.geom.Loop(
                listOf(
                    constructit.geom.ProfileElement.FuncE(c),
                    constructit.geom.ProfileElement.Seg(
                        constructit.geom.Segment(
                            assertNotNull(FuncCurves.end(c)),
                            assertNotNull(FuncCurves.start(c)),
                        ),
                    ),
                ),
            )
        val f =
            constructit.geom.Feature3.Extrusion(
                constructit.geom.Sketch3(plan, listOf(constructit.geom.Region(loop, emptyList()))),
                10.0,
            )
        val patch = assertNotNull(constructit.geom.Section3.faces(f).first)[0]
        assertNull(patch.plane, "the swept face is not a plane")
        assertNull(patch.surface, "and no name means no surface, not an approximate one")
        val why = assertNotNull(patch.reason)
        assertTrue(why.contains("function curve"), "it refuses by name: $why")
        // …and the body itself is still built, watertight, out of the tessellation under it
        val solid = assertNotNull(constructit.geom.Geom3.extrude(f.sketch, f.depth).first, "the body itself is built")
        assertManifold(solid.mesh, "an extruded function curve")
    }

    @Test
    fun aRevolvedFunctionCurveRefusesTheBandByName() {
        val (ed, el) = involuteEditor(r = 20.0, to = 1.2)
        val c = curveOf(ed, el)
        val plan = constructit.geom.Plane3(constructit.geom.Vec3(0.0, 0.0, 0.0), constructit.geom.Vec3(1.0, 0.0, 0.0), constructit.geom.Vec3(0.0, 1.0, 0.0))
        val band =
            constructit.geom.Revolve3.bandOf(
                assertNotNull(
                    constructit.geom.Revolve3.frameOf(
                        constructit.geom.Feature3.Revolution(
                            constructit.geom.Sketch3(plan, emptyList()),
                            Vec2(0.0, -60.0),
                            Vec2(1.0, 0.0),
                            constructit.geom.Turn3.Full,
                        ),
                    ),
                ),
                constructit.geom.ProfileElement.FuncE(c),
            )
        val why = assertNotNull((band as? constructit.geom.Revolve3.Band.Unnamed)?.label)
        assertTrue(why.contains("function curve"), "the band refuses by the name of the curve that swept it: $why")
    }

    @Test
    fun anOffsetOfAFunctionCurveIsApproximatedAndSaysSo() {
        val (ed, el) = involuteEditor(r = 20.0, to = 2.0)
        // away from the involute's own cusp at t = 0, where the curve stands still and has no normal at all
        val c = curveOf(ed, el).copy(t0 = 0.3)
        val poly = constructit.geom.offsetFuncCurve(c, 2.0)
        assertTrue(poly.size > 8, "the offset is a polyline, not a function curve")
        // exact at every sample, chords between — OP-15's spline bargain verbatim
        for (k in poly.indices) {
            val t = c.t0 + c.span * k / (poly.size - 1)
            val p = assertNotNull(FuncCurves.pointAt(c, t))
            val n = assertNotNull(FuncCurves.normalAt(c, t))
            assertClose((poly[k] - (p + n * 2.0)).length(), 0.0, 1e-9, "sample $k sits on the true normal")
        }
        // …and where the curve genuinely has no normal — the involute's cusp at t = 0 — there is no offset
        // to hand back at all, which the thick path turns into its own refusal rather than a fitted curve
        assertTrue(constructit.geom.offsetFuncCurve(curveOf(ed, el), 2.0).isEmpty(), "a cusp has no offset")
    }

    @Test
    fun theTessellationIsAdaptiveAndScaleRelative() {
        val (ed, small) = involuteEditor(r = 20.0, to = 3.0)
        val big = assertNotNull(ed.addFunctionCurve("10 * r * (cos(t) + t * sin(t))", "10 * r * (sin(t) - t * cos(t))", 0.0, 3.0))
        val a = FuncCurves.chordSteps(curveOf(ed, small))
        val b = FuncCurves.chordSteps(curveOf(ed, big))
        assertTrue(a > 4, "a curved piece needs real chords ($a)")
        // the tolerance is a fraction of the curve's own size above the crossover, so ten times the size
        // does not cost ten times the chords — that is GitHub #13's rule, honoured here too
        assertTrue(b < a * 4, "the count is scale-relative, not proportional to size ($a vs $b)")
    }
}
