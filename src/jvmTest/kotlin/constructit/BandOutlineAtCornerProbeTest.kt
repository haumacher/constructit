package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.SolidRef
import constructit.dsl.scalar
import constructit.dsl.solid
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.geom.FaceName
import constructit.geom.Section3
import constructit.units.mm
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * **Orchestrator's probe of OP-31 item 3b** (the band's face outline at a corner): a face space opened on the
 * corner's own faces and on a band face with the outline taken as a section input, and level sections through
 * the one-ended pivot of script 1.
 */
class BandOutlineAtCornerProbeTest {
    private val base = """constructit 7
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
"""
    private val threeBevels =
        base + """tool chamferedge els=e13 clicks=-12.581664043342087,5.018353790754986 scalar="r" signs=2;-1;1;0;-1 -> e14,e15
tool chamferedge els=e14 clicks=-36.10499303384047,0.8875707537249014 scalar="r" signs=13;-1;1;0;1 -> e16
tool chamferedge els=e14 clicks=-6.480180051294639,24.048959979858537 scalar="r" signs=14;-1;1;0;1 -> e17
"""
    private val script1 =
        base + """tool filletedge els=e13 clicks=-31.252365457721638,11.31587462139538 scalar="r" signs=13;-1;1;0;1 -> e14,e15
tool filletedge els=e14 clicks=-15.659687663642714,14.554138077719443 scalar="r" signs=2;-1;1;0;-1 -> e16
"""

    private fun load(text: String): Editor {
        val ed = Editor()
        ed.replaceDocument(DocumentFormat.load(text))
        return ed
    }

    @Suppress("UNCHECKED_CAST")
    private fun bodyRef(ed: Editor) = ed.doc.elements.last { it.kind == ElementKind.SOLID }.ref as SolidRef

    @Test
    fun aFaceSpaceOpensOnEveryPlanarFaceOfTheCornerAndItsOutlineIsASectionInput() {
        val ed = load(threeBevels)
        val faces = Section3.faces(Evaluator().solid(bodyRef(ed)).feature).first ?: error("faces")
        val corner = faces.indices.filter { faces[it].name is FaceName.BlendCorner }
        val bands = faces.indices.filter { faces[it].name is FaceName.BlendBand }
        assertTrue(corner.isNotEmpty(), "the bevelled pivot states corner faces")
        assertTrue(bands.size >= 3, "three bevel bands")
        var opened = 0
        var refused = 0
        for (i in corner + bands) {
            val f = faces[i]
            val text = threeBevels + "sketchspace \"f\" el=e14 piece=$i\nsectioninput \"f\" edge=0 -> e18\n"
            val e =
                try {
                    load(text)
                } catch (err: DocumentFormat.LoadError) {
                    // the file names a face no space can stand on: the loader says so by name
                    assertTrue(f.plane == null || f.reason != null, "face #$i (${f.name.label.render()}) is a plane but the file refuses it: ${err.message}")
                    assertTrue(!err.message.isNullOrBlank(), "the refusal speaks")
                    refused++
                    continue
                }
            val last = e.doc.elements.last()
            val res = Evaluator().eval(last.ref.node)
            val note = (e.doc.loadNotes.joinToString(" | ") { it.render() })
            if (f.plane != null && f.reason == null) {
                assertTrue(res !is EvalResult.Invalid, "face #$i (${f.name.label.render()}) is a plane: a space opens on it and its first edge is an input — ${(res as? EvalResult.Invalid)?.reason} $note")
                opened++
                val saved = DocumentFormat.save(e.doc)
                val again = load(saved)
                assertTrue(DocumentFormat.save(again.doc) == saved, "the file with a space on face #$i is a fixed point")
            } else {
                // a cone or a face with no surface: the refusal must speak, wherever it lands
                val words = ((res as? EvalResult.Invalid)?.reason ?: "") + " " + note + " " + (e.doc.note ?: "")
                assertTrue(words.isNotBlank(), "face #$i (${f.name.label.render()}) is not a plane: something says so")
                refused++
            }
        }
        println("face spaces on the bevelled pivot: $opened opened, $refused refused by name")
        assertTrue(opened >= 3, "at least the three planar bands take a space: $opened")
    }

    @Test
    fun levelSectionsThroughTheOneEndedPivotClose() {
        val ed = load(script1)
        val cx = ed.doc.cx
        val ref = bodyRef(ed)
        for (z in listOf(5.0, 15.9, 16.5, 17.0, 18.5, 19.5, 19.9)) {
            val section = cx.sectionAt(ref, cx.const(z.mm))
            val r = Evaluator().eval(section.node)
            assertTrue(r !is EvalResult.Invalid, "the level section at z = $z through the one-ended pivot is stated: ${(r as? EvalResult.Invalid)?.reason}")
            assertTrue(Evaluator().scalar(cx.regionArea(section)).base > 0.0, "…and closes")
        }
    }
}
