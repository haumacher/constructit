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
import constructit.geom.Geom3
import constructit.geom.ProfileElement
import constructit.geom.Section3
import constructit.units.mm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **Orchestrator's probe of OP-31 item 2** (the mixed-sign pair) on what the delivery never saw: the dressing
 * with one entry removed and put back by undo, the file round trip, a radius change, and level sections
 * through the new corner.
 */
class BlendMixedPairProbeTest {
    private val script1 = """constructit 6
orthostart -26.875,-32.375 -> e1
orthovertex -26.875,15.375 -> e2,e3
orthovertex 61.875,15.375 -> e4,e5
orthovertex 61.875,0.375 -> e6,e7
orthovertex -5.521648428788623,0.375 -> e8,e9
orthovertex -5.521648428788623,-32.375 -> e10,e11
orthoclose -> e12
param "h" = 20mm
tool extrude els=e11 clicks=-48.125,37.875 scalar="h" -> e13
hide els=e1,e2,e3,e4,e5,e6,e7,e8,e9,e10,e11,e12,e13
show els=e1,e2,e3,e4,e5,e6,e7,e8,e9,e10,e11,e12,e13
param "r" = 4mm
tool filletedge els=e13 clicks=-31.252365457721638,11.31587462139538 scalar="r" signs=13;-1;1;0;1 -> e14,e15
tool filletedge els=e14 clicks=-15.659687663642714,14.554138077719443 scalar="r" signs=2;-1;1;0;-1 -> e16
"""
    private val naive = 40566.658467
    private val base = 40611.44527914345

    private fun body(ed: Editor): Element = ed.doc.elements.last { it.kind == ElementKind.SOLID }

    private fun entries(ed: Editor) = ed.doc.elements.filter { it.kind == ElementKind.DRESSING }

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

    private fun load(): Editor {
        val ed = Editor()
        ed.replaceDocument(DocumentFormat.load(script1))
        return ed
    }

    @Test
    fun removingEitherEntryLeavesTheOtherAloneAndUndoBringsTheCornerBack() {
        val ed = load()
        val both = volumeOf(body(ed), "both roundings")
        assertTrue(kotlin.math.abs(both - naive) > 1.0, "the corner is built: $both is not the naive $naive")
        val w = raspWedgeArea(4.0)
        val wc = raspWedgeAreaByChords(4.0)
        // the general engine's float32 noise on a body of this size
        val noise = 1e-6 * base
        // the fill comes off: what is left is the band alone over its whole 32.75 mm
        ed.selectElement(entries(ed)[1])
        assertTrue(ed.deleteSelection(), "the fill's entry comes off: ${ed.statusHint}")
        val bandOnly = volumeOf(body(ed), "band alone")
        assertTrue(bandOnly <= base - w * 32.75 + noise && bandOnly >= base - wc * 32.75 - noise, "the band alone takes its wedge: $bandOnly")
        assertTrue(ed.undo(), "undo")
        assertClose(both, volumeOf(body(ed), "both again"), 1e-9, "undo restores the corner exactly")
        // the band comes off: what is left is the fill alone over the whole upright
        ed.selectElement(entries(ed)[0])
        assertTrue(ed.deleteSelection(), "the band's entry comes off: ${ed.statusHint}")
        val fillOnly = volumeOf(body(ed), "fill alone")
        assertTrue(fillOnly >= base + w * 20.0 - noise && fillOnly <= base + wc * 20.0 + noise, "the fill alone adds its wedge: $fillOnly")
        assertTrue(ed.undo(), "undo")
        assertClose(both, volumeOf(body(ed), "both once more"), 1e-9, "undo restores the corner exactly")
    }

    @Test
    fun theFileIsAFixedPointAndTheReloadedBodyIsTheLiveOne() {
        val ed = load()
        val live = volumeOf(body(ed), "live")
        val saved = DocumentFormat.save(ed.doc)
        val ed2 = Editor()
        ed2.replaceDocument(DocumentFormat.load(saved))
        assertEquals(saved, DocumentFormat.save(ed2.doc), "save → load → save is a fixed point")
        assertClose(live, volumeOf(body(ed2), "reloaded"), 1e-9, "the reloaded body is the live one")
    }

    @Test
    fun aSmallerRadiusRebuildsTheCornerAndALargerOneStillBuilds() {
        val ed = load()
        val r = ed.doc.scalars.first { it.name == "r" }
        ed.doc.setParameter(r, 3.0.mm)
        val v3 = volumeOf(body(ed), "r = 3")
        val naive3 = base - raspWedgeArea(3.0) * 32.75 + raspWedgeArea(3.0) * 20.0
        assertTrue(kotlin.math.abs(v3 - naive3) > 0.5, "at r = 3 the corner is still built: $v3 vs naive $naive3")
        ed.doc.setParameter(r, 6.0.mm)
        volumeOf(body(ed), "r = 6")
    }

    @Test
    fun levelSectionsThroughTheCornerCloseAndTheTopFaceStatesItsFittedBoundary() {
        val ed = load()
        val el = body(ed)
        val cx = ed.doc.cx

        @Suppress("UNCHECKED_CAST")
        val ref = el.ref as SolidRef
        for (z in listOf(10.0, 17.0, 18.5, 19.9)) {
            val area = Evaluator().scalar(cx.regionArea(cx.sectionAt(ref, cx.const(z.mm)))).base
            assertTrue(area > 0.0, "the level section at z = $z closes into an area: $area")
            // below the band the section is the plan plus the fill's wedge; the fill is concave so it adds
            if (z < 16.0) assertClose(area, planArea() + raspWedgeArea(4.0), 0.05, msg = "z = $z: plan plus the fill's wedge")
        }
        val faces = assertNotNull(Section3.faces(Evaluator().solid(ref).feature).first, "the dressed body names its faces")
        val top = faces.filter { f -> f.plane != null && kotlin.math.abs(f.plane!!.normal.z - 1.0) < 1e-9 && f.plane!!.distanceTo(constructit.geom.Vec3(0.0, 0.0, 20.0)).let { kotlin.math.abs(it) < 1e-9 } }
        assertTrue(top.isNotEmpty(), "the top face is stated")
        assertTrue(top.any { f -> f.outline.any { it !is ProfileElement.Seg } }, "the top face's outline carries the cap's fitted boundary")
    }

    private fun planArea(): Double {
        val p =
            listOf(
                -26.875 to -32.375,
                -26.875 to 15.375,
                61.875 to 15.375,
                61.875 to 0.375,
                -5.521648428788623 to 0.375,
                -5.521648428788623 to -32.375,
            )
        var a = 0.0
        for (i in p.indices) {
            val (x1, y1) = p[i]
            val (x2, y2) = p[(i + 1) % p.size]
            a += x1 * y2 - x2 * y1
        }
        return kotlin.math.abs(a) / 2.0
    }
}
