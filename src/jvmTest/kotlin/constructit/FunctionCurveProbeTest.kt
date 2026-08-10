package constructit

import constructit.core.Evaluator
import constructit.core.FuncCurveValue
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.dsl.valueOf
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.FuncCurves
import constructit.geom.Geom3
import constructit.geom.Vec2
import constructit.units.mm
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Probe review of the expressions curve half — compositions the delivery never saw.
 *
 * The two questions: do the **two expression layers re-stamp through one rename together** — a function
 * curve reading a scalar that is itself expression-bound, renamed at both levels, judged through the file
 * (the scalar half's own lesson: live evaluation proves nothing); and does a **blend face-chain speak**
 * when the cap it breaks carries a function-curve piece — the one face this vocabulary deliberately cannot
 * name a surface for, meeting slice 2 of the blends.
 */
class FunctionCurveProbeTest {
    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerMove(s)
        pointerDown(s)
        pointerUp(s)
    }

    private fun curveOf(
        ev: Evaluator,
        el: Element,
    ) = assertNotNull((ev.valueOf(el.ref) as? FuncCurveValue)?.curve, "a function curve value")

    private fun sample(
        el: Element,
        t: Double,
    ): Vec2 = assertNotNull(FuncCurves.pointAt(curveOf(Evaluator(), el), t))

    private fun roundTrips(
        ed: Editor,
        msg: String,
    ): String {
        val once = DocumentFormat.save(ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), msg)
        return once
    }

    /**
     * **A curve over a derived scalar, renamed at both layers.** `r` is expression-bound to `m*10`, the
     * involute reads `r` — editing `m` moves the curve through both bindings, renaming `m` re-stamps the
     * scalar's text, renaming `r` re-stamps both of the curve's texts, and the file that results loads to
     * the same curve, byte-equal on the second save.
     */
    @Test
    fun aCurveOverADerivedScalarRenamesThroughBothLayers() {
        val ed = Editor()
        val m = ed.doc.newParameter("m", 2.0.mm)
        val r = ed.doc.newParameter("r", 5.0.mm)
        assertTrue(ed.doc.bindParameter(r, "m*10"), "r derives from m: ${ed.doc.note}")

        val flank =
            assertNotNull(
                ed.addFunctionCurve("r * (cos(t) + t * sin(t))", "r * (sin(t) - t * cos(t))", 0.0, 1.5),
                "the involute over the derived radius builds: ${ed.statusHint}",
            )
        val p = sample(flank, 1.0)
        assertClose(p.x, 20.0 * (cos(1.0) + sin(1.0)), tol = 1e-9, msg = "x on the true involute, r = m*10 = 20")
        assertClose(p.y, 20.0 * (sin(1.0) - cos(1.0)), tol = 1e-9, msg = "y on the true involute")

        ed.doc.setParameter(m, 3.0.mm)
        val p2 = sample(flank, 1.0)
        assertClose(p2.x, 30.0 * (cos(1.0) + sin(1.0)), tol = 1e-9, msg = "one edit of m moved the curve through both layers")

        assertEquals("module", ed.doc.renameParameter(m, "module"), "the lower layer renames")
        assertEquals("module*10", ed.doc.expressionOf(r), "and the scalar's text is re-stamped")
        assertEquals("base", ed.doc.renameParameter(r, "base"), "the upper layer renames")

        val text = roundTrips(ed, "both re-stamped layers round-trip byte-equal")
        assertTrue("base * (cos(t) + t * sin(t))" in text, "the curve's stored text carries the new name, spacing intact")
        val loaded = DocumentFormat.load(text)
        val flankLoaded = loaded.elements.single { it.kind == ElementKind.FUNC_CURVE }
        val q = assertNotNull(FuncCurves.pointAt(assertNotNull((Evaluator().valueOf(flankLoaded.ref) as? FuncCurveValue)?.curve), 1.0))
        assertClose(q.x, p2.x, tol = 1e-9, msg = "the reloaded curve is the same curve")
        assertClose(q.y, p2.y, tol = 1e-9, msg = "in both coordinates")
    }

    /**
     * **A blend face-chain meeting a function-curve piece must speak.** A bulge plate — one sine arch and
     * one closing segment, traced and extruded — gets *Fillet the edges of a face* on its cap: the segment's
     * edge is blendable, the function piece's face is the one this vocabulary cannot name, and whatever the
     * gesture does, it says so; every body stands watertight and the drawing survives its file.
     */
    @Test
    fun aBlendFaceChainOverAFunctionPieceSaysWhatItWouldNotTake() {
        val ed = Editor()
        val arch =
            assertNotNull(
                ed.addFunctionCurve("40mm * t", "10mm * sin(PI * t)", 0.0, 1.0),
                "the sine arch builds: ${ed.statusHint}",
            )
        // everything through recorded gestures, so the file can say all of it (OP-18)
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(40.0, 0.0))
        ed.click(Vec2(0.0, 0.0))
        ed.setTool(Tools.OUTLINE)
        ed.click(sample(arch, 0.5))
        ed.click(Vec2(20.0, 0.0))
        ed.key("Enter")
        val outline =
            assertNotNull(
                ed.doc.elements.lastOrNull { it.kind == ElementKind.OUTLINE },
                "the trace crosses the function piece: ${ed.statusHint}",
            )
        assertEquals(ElementKind.OUTLINE, outline.kind)

        ed.activeScalar = ed.doc.newParameter("depth", 8.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(20.0, 0.0))
        val plate = ed.doc.elements.single { it.kind == ElementKind.SOLID }

        @Suppress("UNCHECKED_CAST")
        fun meshOf(el: Element) = Evaluator().solid(el.ref as SolidRef).mesh
        val before = Geom3.volume(meshOf(plate))
        assertManifold(meshOf(plate), "the bulge plate")

        ed.activeScalar = ed.doc.newParameter("r", 1.5.mm)
        ed.setTool(Tools.BLEND_FACE)
        ed.click(Vec2(20.0, 0.0))

        val said = assertNotNull(ed.statusHint, "the gesture spoke")
        assertTrue(said.isNotBlank(), "the gesture spoke words")
        assertTrue(
            listOf("function", "cannot", "vocabulary", "curved", "name").any { it in said },
            "and it names what it would not take: $said",
        )
        for (s in ed.doc.elements.filter { it.kind == ElementKind.SOLID }) {
            assertManifold(meshOf(s), "every body stands watertight after the gesture")
        }
        val after = Geom3.volume(meshOf(ed.doc.elements.last { it.kind == ElementKind.SOLID }))
        assertTrue(after <= before + 1e-6, "whatever was taken was taken, never added: $after vs $before")

        roundTrips(ed, "the drawing survives its file, function piece and all")
    }
}
