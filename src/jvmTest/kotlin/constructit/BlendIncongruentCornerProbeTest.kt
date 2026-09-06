package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.SolidRef
import constructit.dsl.scalar
import constructit.dsl.solid
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.geom.FaceName
import constructit.geom.Geom3
import constructit.geom.Section3
import constructit.units.mm
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **Orchestrator's probe of OP-31 slice 5a** (incongruent corners) on what the delivery never saw: the step face
 * between the two unlike sections taken as a sketch space, the smaller size raised until the pair is congruent
 * and past it, an entry removed and undone, and the file round trip.
 */
class BlendIncongruentCornerProbeTest {
    private fun script(r3: String) = """constructit 7
orthostart -26.875,-32.375 -> e1
orthovertex -26.875,15.375 -> e2,e3
orthovertex 61.875,15.375 -> e4,e5
orthovertex 61.875,0.375 -> e6,e7
orthovertex -5.521648428788623,0.375 -> e8,e9
orthovertex -5.521648428788623,-32.375 -> e10,e11
orthoclose -> e12
param "h" = 20mm
tool extrude els=e11 clicks=-48.125,37.875 scalar="h" -> e13
param "r" = 4mm
param "r3" = ${r3}mm
tool filletedge els=e13 clicks=-31.252365457721638,11.31587462139538 scalar="r" signs=13;-1;1;0;1 -> e14,e15
tool filletedge els=e14 clicks=20.0,2.0 scalar="r3" signs=14;-1;1;0;1 -> e16
"""

    private fun load(text: String): Editor {
        val ed = Editor()
        ed.replaceDocument(DocumentFormat.load(text))
        return ed
    }

    private fun body(ed: Editor): Element = ed.doc.elements.last { it.kind == ElementKind.SOLID }

    @Suppress("UNCHECKED_CAST")
    private fun volumeOf(
        el: Element,
        what: String,
    ): Double {
        val r = Evaluator().eval(el.ref.node)
        assertTrue(r !is EvalResult.Invalid, "$what is valid: ${(r as? EvalResult.Invalid)?.reason}")
        val mesh = Evaluator().solid(el.ref as SolidRef).mesh
        assertManifold(mesh, what)
        return Geom3.volume(mesh)
    }

    @Test
    fun theStepFaceIsAPlaneASketchSpaceOpensOnAndSectionsThroughTheCornerClose() {
        val ed = load(script("3"))
        val el = body(ed)
        volumeOf(el, "4 + 3")
        @Suppress("UNCHECKED_CAST")
        val ref = el.ref as SolidRef
        val faces = assertNotNull(Section3.faces(Evaluator().solid(ref).feature).first)
        // the corner's own faces: the deeper ball's pivot (a torus) and the step between the two sections (a plane)
        val corner = faces.indices.filter { faces[it].name is FaceName.BlendCorner && faces[it].reason == null }
        assertTrue(corner.isNotEmpty(), "the incongruent corner states faces of its own: ${faces.map { it.name }}")
        val step = corner.firstOrNull { faces[it].plane != null }
        assertNotNull(step, "one of them is the planar step between the 4 mm and the 3 mm section")
        // open a sketch space on the step and take its outline as a section input
        val e = load(script("3") + "sketchspace \"step\" el=e14 piece=$step\nsectioninput \"step\" edge=0 -> e17\n")
        val last = e.doc.elements.last()
        val res = Evaluator().eval(last.ref.node)
        assertTrue(res !is EvalResult.Invalid, "a space opens on the step and its outline is an input: ${(res as? EvalResult.Invalid)?.reason}")
        val saved = DocumentFormat.save(e.doc)
        assertEquals(saved, DocumentFormat.save(load(saved).doc), "the file with the space on the step is a fixed point")
        val cx = ed.doc.cx
        for (z in listOf(16.5, 17.5, 18.5, 19.5)) {
            val section = cx.sectionAt(ref, cx.const(z.mm))
            val r = Evaluator().eval(section.node)
            assertTrue(r !is EvalResult.Invalid, "the section at z = $z through the incongruent corner is stated: ${(r as? EvalResult.Invalid)?.reason}")
            assertTrue(Evaluator().scalar(cx.regionArea(section)).base > 0.0, "…and closes")
        }
    }

    @Test
    fun raisingTheSmallerSizeToTheLargerGivesTheCongruentBodyAndPastItTheOtherBallTravels() {
        val ed = load(script("3"))
        val v43 = volumeOf(body(ed), "4 + 3")
        val congruent = volumeOf(body(load(script("4"))), "4 + 4 as two entries")
        val onePass = volumeOf(body(load(script("4").replace("scalar=\"r3\"", "scalar=\"r\""))), "4 + 4 as one pass")
        assertClose(congruent, onePass, 1e-6 * onePass, "two entries of equal size are session 80's own corner")
        val r3 = ed.doc.scalars.first { it.name == "r3" }
        ed.doc.setParameter(r3, 4.0.mm)
        assertClose(congruent, volumeOf(body(ed), "r3 raised to 4"), 1e-9, "the live edit reaches the congruent body")
        ed.doc.setParameter(r3, 5.0.mm)
        val v45 = volumeOf(body(ed), "r3 raised to 5: the other ball travels")
        assertTrue(v45 < congruent && congruent < v43, "more rounding, less body: $v45 < $congruent < $v43")
        ed.doc.setParameter(r3, 3.0.mm)
        assertClose(v43, volumeOf(body(ed), "back to 3"), 1e-9, "and back")
    }

    @Test
    fun removingEitherEntryAndUndoingItIsExact() {
        val ed = load(script("3"))
        val both = volumeOf(body(ed), "both")
        val entries = ed.doc.elements.filter { it.kind == ElementKind.DRESSING }
        assertEquals(2, entries.size)
        for (i in 0..1) {
            ed.selectElement(ed.doc.elements.filter { it.kind == ElementKind.DRESSING }[i])
            assertTrue(ed.deleteSelection(), "entry $i comes off: ${ed.statusHint}")
            val one = volumeOf(body(ed), "entry $i removed")
            assertTrue(abs(one - both) > 1.0, "the body changed: $one vs $both")
            assertTrue(ed.undo(), "undo")
            assertClose(both, volumeOf(body(ed), "restored"), 1e-9, "undo restores the corner exactly")
        }
        val saved = DocumentFormat.save(ed.doc)
        assertEquals(saved, DocumentFormat.save(load(saved).doc), "fixed point")
        assertClose(both, volumeOf(body(load(saved)), "reloaded"), 1e-9, "the reloaded body is the live one")
    }
}
