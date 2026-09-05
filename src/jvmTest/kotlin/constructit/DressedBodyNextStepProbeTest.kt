package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.editor.Document
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.Geom3
import constructit.geom.Vec2
import constructit.units.mm
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **Orchestrator's probe of OP-30's next step** on what the delivery never saw: the reporter's migrated file with
 * two entries removed (the second and the concave fifth) against the file written without those two steps, a
 * tombstoned **first** rounding of three sizes through the file and a later re-add, and a fresh drawing whose
 * three roundings of three kinds and sizes go to one pass and one body whatever their order.
 */
class DressedBodyNextStepProbeTest {
    private val head = """constructit 5
orthostart -26.875,-32.375 -> e1
orthovertex -26.875,15.375 -> e2,e3
orthovertex 41.999800864975384,15.375 -> e4,e5
orthovertex 41.999800864975384,-11.775083491926196 -> e6,e7
orthovertex -5.521648428788623,-11.775083491926196 -> e8,e9
orthovertex -5.521648428788623,-32.375 -> e10,e11
orthoclose -> e12
param "h" = 20mm
tool extrude els=e11 clicks=-48.125,37.875 scalar="h" -> e13
param "r" = 5mm
"""
    private val steps =
        listOf(
            Triple(12, "-1;1;0;1", "-42.670739764447546,-4.867038301721209"),
            Triple(13, "-1;1;0;1", "-31.533048614089623,14.504582242265968"),
            Triple(1, "-1;1;0;1", "-15.209120508301623,-22.09480593584297"),
            Triple(14, "-1;1;0;1", "-1.6336097588108203,35.97358839564461"),
            Triple(2, "-1;1;0;-1", "-11.301657028615722,8.858099327956722"),
            Triple(3, "-1;1;0;1", "56.88568755568988,21.122250050431774"),
            Triple(15, "-1;1;0;1", "52.78762484641989,32.49678119098172"),
        )

    private fun script(which: List<Int>): String {
        val sb = StringBuilder(head)
        var prev = "e13"
        var next = 14
        for (i in which) {
            val (edge, tail, click) = steps[i]
            sb.append("tool filletedge els=$prev clicks=$click scalar=\"r\" signs=$edge;$tail -> e$next\n")
            prev = "e$next"
            next++
        }
        return sb.toString()
    }

    private fun solids(doc: Document) = doc.elements.filter { it.kind == ElementKind.SOLID }

    private fun entries(doc: Document) = doc.elements.filter { it.kind == ElementKind.DRESSING }

    private fun volumeOf(el: Element): Double {
        val res = Evaluator().eval(el.ref.node)
        assertTrue(res !is EvalResult.Invalid, "valid: ${(res as? EvalResult.Invalid)?.reason}")
        val m = Evaluator().solid(el.ref as SolidRef).mesh
        assertManifold(m, "the dressed body")
        return Geom3.volume(m)
    }

    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerMove(s)
        pointerDown(s)
        pointerUp(s)
    }

    @Test
    fun twoEntriesRemovedAreTheFileWrittenWithoutThemAndTheFileHoldsTheTombstones() {
        val ed = Editor()
        ed.replaceDocument(DocumentFormat.load(script((0..6).toList())))
        assertEquals(7, entries(ed.doc).size)
        // remove the fifth (concave) first, then the second: two tombstones, one of them the concave upright
        ed.selectElement(entries(ed.doc)[4])
        assertTrue(ed.deleteSelection(), ed.statusHint)
        ed.selectElement(entries(ed.doc)[1])
        assertTrue(ed.deleteSelection(), ed.statusHint)
        assertEquals(5, entries(ed.doc).size)
        val v = volumeOf(solids(ed.doc).last())
        val theirs = volumeOf(solids(DocumentFormat.load(script(listOf(0, 2, 3, 5, 6)))).last())
        assertTrue(abs(v - theirs) < 1e-6 * theirs, "two removals are the file without those steps: $v vs $theirs")
        val once = DocumentFormat.save(ed.doc)
        assertEquals(2, once.lines().count { "removed=" in it }, "two tombstoned steps in the file:\n$once")
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "a fixed point")
        val back = DocumentFormat.load(once)
        assertEquals(5, entries(back).size, "five live entries after a reload")
        assertTrue(abs(volumeOf(solids(back).last()) - v) < 1e-9, "the same body after a reload")
        // undo twice: both roundings return, and the file forgets both tombstones
        assertTrue(ed.undo() && ed.undo(), "two undos")
        assertEquals(7, entries(ed.doc).size)
        assertTrue(DocumentFormat.save(ed.doc).lines().none { "removed=" in it }, "no tombstone left after undo")
    }

    private fun plate(): Editor {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(60.0, 40.0))
        ed.activeScalar = ed.doc.newParameter("depth", 20.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(30.0, 0.0))
        return ed
    }

    /** A fillet, a chamfer and a fillet of three sizes on three rim edges, in the given order. */
    private fun dressed(order: List<Int>): Editor {
        val ed = plate()
        val g =
            listOf(
                Triple(Tools.BLEND_EDGE, ed.doc.newParameter("a", 4.0.mm), Vec2(30.0, 0.0)),
                Triple(Tools.CHAMFER_EDGE, ed.doc.newParameter("b", 3.0.mm), Vec2(60.0, 20.0)),
                Triple(Tools.BLEND_EDGE, ed.doc.newParameter("c", 2.0.mm), Vec2(30.0, 40.0)),
            )
        for (i in order) {
            val (tool, scalar, at) = g[i]
            ed.activeScalar = scalar
            ed.setTool(tool)
            ed.click(at)
        }
        assertEquals(3, entries(ed.doc).size, ed.statusHint)
        return ed
    }

    @Test
    fun threeKindsAndSizesAreOnePassAndOneBodyWhateverTheOrder() {
        val a = dressed(listOf(0, 1, 2))
        val b = dressed(listOf(2, 1, 0))
        Geom3.resetCombines()
        val va = volumeOf(solids(a.doc).last())
        val vb = volumeOf(solids(b.doc).last())
        assertTrue(abs(va - vb) < 1e-6 * va, "one body whatever the order: $va vs $vb")
        // one pass: the dressed body's feature carries three targets, not a chain of three features
        val f = Evaluator().solid(solids(a.doc).last().ref as SolidRef).feature
        assertTrue(f is constructit.geom.Feature3.Blend && f.targets.size == 3 && f.base !is constructit.geom.Feature3.Blend, "one feature, three targets: $f")
    }

    @Test
    fun theFirstOfThreeSizesIsTombstonedAndALaterRoundingStillJoinsTheBody() {
        val ed = dressed(listOf(0, 1, 2))
        val all = volumeOf(solids(ed.doc).last())
        ed.selectElement(entries(ed.doc)[0])
        assertTrue(ed.deleteSelection(), "the first rounding comes off: ${ed.statusHint}")
        assertEquals(2, entries(ed.doc).size)
        val two = volumeOf(solids(ed.doc).last())
        assertTrue(two > all, "less taken off: $two vs $all")
        val once = DocumentFormat.save(ed.doc)
        assertTrue(once.lines().any { "removed=" in it }, "the first step stays as a tombstone:\n$once")
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "a fixed point")
        // a fourth rounding joins the same body, after the tombstone
        ed.activeScalar = ed.doc.scalars.first { it.name == "c" }
        ed.setTool(Tools.BLEND_EDGE)
        ed.click(Vec2(0.0, 20.0))
        assertEquals(3, entries(ed.doc).size, ed.statusHint)
        assertEquals(2, solids(ed.doc).size, "still one plate and one dressed body")
        val three = volumeOf(solids(ed.doc).last())
        assertTrue(three < two, "the new rounding takes material: $three vs $two")
        val again = DocumentFormat.save(ed.doc)
        assertEquals(again, DocumentFormat.save(DocumentFormat.load(again)), "still a fixed point")
        assertEquals(1, again.lines().count { "removed=" in it }, "one tombstone, three live entries")
    }
}
