package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.Construction
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.geom.Blend3
import constructit.geom.BlendKind
import constructit.geom.BlendSection
import constructit.geom.EdgeName
import constructit.geom.FaceName
import constructit.geom.Geom3
import constructit.geom.Section3
import constructit.units.mm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **Orchestrator's probe of OP-31 slice 5b** (the ball along a curved crease) on what the delivery never saw: the
 * rounded notch when the rim it ends comes off the dressing, when the rim's radius changes, and the new cap face
 * as a sketch space.
 */
class BlendCurvedCreaseProbeTest {
    /** A 60 × 40 × 20 plate with one top rim edge rounded at `r` (and, for [twoRims], the opposite rim too). */
    private fun plate(
        r: String,
        twoRims: Boolean = false,
    ) =
        """constructit 7
orthostart 0,0 -> e1
orthovertex 60,0 -> e2,e3
orthovertex 60,40 -> e4,e5
orthovertex 0,40 -> e6,e7
orthoclose -> e8
param "h" = 20mm
tool extrude els=e7 clicks=30,20 scalar="h" -> e9
param "r" = ${r}mm
tool filletedge els=e9 clicks=30,0 scalar="r" signs=8;-1;1;0;1 -> e10,e11
""" + (if (twoRims) "tool filletedge els=e10 clicks=30,40 scalar=\"r\" signs=10;-1;1;0;1 -> e12\n" else "")

    private fun load(text: String): Editor {
        val ed = Editor()
        ed.replaceDocument(DocumentFormat.load(text))
        return ed
    }

    private fun body(ed: Editor): Element = ed.doc.elements.last { it.kind == ElementKind.SOLID }

    @Suppress("UNCHECKED_CAST")
    private fun refOf(el: Element) = el.ref as SolidRef

    private fun volumeOf(
        ref: SolidRef,
        what: String,
    ): Double {
        val r = Evaluator().eval(ref.node)
        assertTrue(r !is EvalResult.Invalid, "$what is valid: ${(r as? EvalResult.Invalid)?.reason}")
        val mesh = Evaluator().solid(ref).mesh
        assertManifold(mesh, what)
        return Geom3.volume(mesh)
    }

    private fun notches(ref: SolidRef): List<Int> {
        val edges = assertNotNull(Section3.edges(Evaluator().solid(ref).feature).first)
        return edges.indices.filter { edges[it].name is EdgeName.BlendNotch && edges[it].reason == null }
    }

    private fun Construction.roundNotch(
        on: SolidRef,
        edge: Int,
        r: Double,
    ): SolidRef {
        val (choices, why) = Blend3.choicesFor(Evaluator().solid(on), listOf(edge), BlendSection(BlendKind.FILLET, r))
        assertNotNull(choices, "the notch curve is roundable: ${why?.render()}")
        return blendAll(on, planeXY(), listOf(Construction.BlendRun(BlendKind.FILLET, const(r.mm), null, listOf(edge), choices)))
    }

    @Test
    fun theRoundedNotchGoesWithItsRimAndComesBackWithUndo() {
        val ed = load(plate("4", twoRims = true))
        val rim = body(ed)
        val n = notches(refOf(rim))
        assertEquals(4, n.size, "two bands ending free at both ends leave four notch curves")
        val rounded = ed.doc.cx.roundNotch(refOf(rim), n[0], 1.0)
        val v = volumeOf(rounded, "notch rounded")
        assertTrue(volumeOf(refOf(rim), "rims") - v > 1.0, "a convex notch rounded takes the ball's material")
        // the first rim's entry comes off (the second stays, so the dressing stays); the notch it left is gone with it
        val entry = ed.doc.elements.filter { it.kind == ElementKind.DRESSING }[0]
        ed.selectElement(entry)
        assertTrue(ed.deleteSelection(), "the first rim's rounding comes off: ${ed.statusHint}")
        val r = Evaluator().eval(rounded.node)
        val after = Section3.edges(Evaluator().solid(refOf(body(ed))).feature).first
        val slot = after?.getOrNull(n[0])
        assertTrue(
            r is EvalResult.Invalid && !r.reason.isNullOrBlank(),
            "with the rim gone the notch rounding says why — instead it is valid at ${if (r is EvalResult.Invalid) "?" else volumeOf(rounded, "slid")} " +
                "and slot ${n[0]} now names ${slot?.name} (reason ${slot?.reason?.render()}), list size ${after?.size} (was ${n.size} notches among ${Section3.edges(Evaluator().solid(refOf(rim)).feature).first?.size})",
        )
        assertTrue(ed.undo(), "undo")
        // **a handle into the graph an undo replaced is not the body it names.** Removing an entry is an
        // *in-place* re-stamp of the dressed body (OP-23), which is why the rounding above followed it off;
        // an undo restores by **reloading** the document, so it builds a fresh graph that the old handle
        // cannot see. The notch rounding is therefore asked of the body as it now stands — which is what a
        // user's own drawing is, every step of it being a step of that document.
        val restored = ed.doc.cx.roundNotch(refOf(body(ed)), notches(refOf(body(ed)))[0], 1.0)
        assertClose(v, volumeOf(restored, "restored"), 1e-9, "undo brings the rounded notch back exactly")
    }

    @Test
    fun theRoundedNotchFollowsTheRimsRadius() {
        val ed = load(plate("4"))
        val rim = body(ed)
        val rounded = ed.doc.cx.roundNotch(refOf(rim), notches(refOf(rim))[1], 1.0)
        val v4 = volumeOf(rounded, "rim 4, notch 1")
        val r = ed.doc.scalars.first { it.name == "r" }
        ed.doc.setParameter(r, 3.0.mm)
        val v3 = volumeOf(rounded, "rim 3, notch 1")
        // a 4 mm rim takes 3.4336·60 off, a 3 mm one 1.9314·60: the difference dominates, the notch is a mm³
        assertTrue(v3 > v4 && v3 - v4 > 80.0 && v3 - v4 < 95.0, "the rounded notch follows the smaller rim: $v3 vs $v4")
        ed.doc.setParameter(r, 6.0.mm)
        volumeOf(rounded, "rim 6, notch 1")
    }

    @Test
    fun theCapFaceIsAPlaneASketchSpaceOpensOn() {
        val ed = load(plate("4"))
        val rim = body(ed)
        val rounded = ed.doc.cx.roundNotch(refOf(rim), notches(refOf(rim))[0], 1.0)
        val faces = assertNotNull(Section3.faces(Evaluator().solid(rounded).feature).first)
        val caps = faces.indices.filter { faces[it].name is FaceName.BlendCap }
        assertTrue(caps.isNotEmpty(), "the revolved band ends on a cap face: ${faces.map { it.name }}")
        for (c in caps) {
            val f = faces[c]
            assertTrue(f.plane != null && f.reason == null, "the cap is a plane with an outline: ${f.name.label.render()} ${f.reason?.render()}")
            assertTrue(f.outline.isNotEmpty(), "…stated")
        }
        // through the file: the dressed body's own face addresses reach the cap (item 3b's rule) once the notch rounding is a step
        val n0 = notches(refOf(rim))[0]
        val scored = assertNotNull(Blend3.choicesFor(Evaluator().solid(refOf(rim)), listOf(n0), BlendSection(BlendKind.FILLET, 1.0)).first)[0]
        val text = plate("4") + "param \"r1\" = 1mm\ntool filletedge els=e10 clicks=0,1 scalar=\"r1\" signs=$n0;${scored.signs().joinToString(";")} -> e12,e13\n"
        val e2 = load(text)
        val fromFile = body(e2)
        assertClose(volumeOf(rounded, "dsl"), volumeOf(refOf(fromFile), "file"), 1e-9, "the file's step builds the same body")
        val faces2 = assertNotNull(Section3.faces(Evaluator().solid(refOf(fromFile)).feature).first)
        val cap = faces2.indices.first { faces2[it].name is FaceName.BlendCap && faces2[it].plane != null }
        val e3 = load(text + "sketchspace \"cap\" el=e12 piece=$cap\nsectioninput \"cap\" edge=0 -> e14\n")
        val res = Evaluator().eval(e3.doc.elements.last().ref.node)
        assertTrue(res !is EvalResult.Invalid, "a sketch space opens on the cap and its outline is an input: ${(res as? EvalResult.Invalid)?.reason}")
        val saved = DocumentFormat.save(e3.doc)
        assertEquals(saved, DocumentFormat.save(load(saved).doc), "fixed point")
    }
}
