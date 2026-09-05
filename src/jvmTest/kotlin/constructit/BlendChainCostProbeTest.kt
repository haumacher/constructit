package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.editor.DocumentFormat
import constructit.editor.ElementKind
import constructit.geom.Blend3
import constructit.geom.Geom3
import constructit.units.mm
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **Orchestrator's probe of GitHub #35, part 1**, on what the delivery never saw: the reporter's seven roundings
 * applied in **another order** (the uprights first, then the top edges) must be the same body and cost no more
 * booleans than levels plus the one rebuild, and a drag of ten radii must not accumulate anything.
 */
class BlendChainCostProbeTest {
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

    /** The reporter's seven steps, as (edge address, signs tail, click). */
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

    private fun script(order: List<Int>): String {
        val sb = StringBuilder(head)
        var prev = "e13"
        var next = 14
        for (i in order) {
            val (edge, tail, click) = steps[i]
            sb.append("tool filletedge els=$prev clicks=$click scalar=\"r\" signs=$edge;$tail -> e$next\n")
            prev = "e$next"
            next++
        }
        return sb.toString()
    }

    private fun tipVolume(text: String): Pair<Double, Int> {
        val doc = DocumentFormat.load(text)
        val solids = doc.elements.filter { it.kind == ElementKind.SOLID }
        val r = doc.scalars.first { it.name == "r" }
        doc.setParameter(r, 6.0.mm)
        Geom3.resetCombines()
        val ev = Evaluator()
        for (el in solids) {
            val res = ev.eval(el.ref.node)
            assertTrue(res !is EvalResult.Invalid, "valid: ${(res as? EvalResult.Invalid)?.reason}")
            ev.solid(el.ref as SolidRef).mesh
        }
        val mesh = ev.solid(solids.last().ref as SolidRef).mesh
        assertManifold(mesh, "the tip")
        return Geom3.volume(mesh) to Geom3.combines
    }

    @Test
    fun theUprightsFirstThenTheTopEdgesIsTheSameBodyForNoMoreBooleans() {
        val (asReported, nReported) = tipVolume(script(listOf(0, 1, 2, 3, 4, 5, 6)))
        // the uprights (convex, convex, concave) first, then the four top edges
        val (reordered, nReordered) = tipVolume(script(listOf(2, 5, 4, 0, 1, 3, 6)))
        assertTrue(abs(asReported - reordered) < 1e-3, "one body whatever the order: $asReported vs $reordered")
        // seven levels: one boolean each, and a rebuild in two groups where the corner about the fill is fresh
        assertTrue(nReported <= 9, "the reported order costs $nReported booleans")
        assertTrue(nReordered <= 9, "the reordered chain costs $nReordered booleans")
    }

    @Test
    fun aDragOfTenRadiiDerivesTheSameEveryTimeAndKeepsNothing() {
        val doc = DocumentFormat.load(script(listOf(0, 1, 2, 3, 4, 5, 6)))
        val solids = doc.elements.filter { it.kind == ElementKind.SOLID }
        val r = doc.scalars.first { it.name == "r" }
        var first = -1
        for (k in 1..10) {
            doc.setParameter(r, (4.0 + k * 0.1).mm)
            Blend3.resetDerivations()
            Geom3.resetCombines()
            val ev = Evaluator()
            for (el in solids) ev.solid(el.ref as SolidRef).mesh
            if (first < 0) first = Blend3.derivations
            assertEquals(first, Blend3.derivations, "frame $k derives what the first did")
            assertTrue(Geom3.combines <= 9, "frame $k: ${Geom3.combines} booleans")
        }
    }
}
