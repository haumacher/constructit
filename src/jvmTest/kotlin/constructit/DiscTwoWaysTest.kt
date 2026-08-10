package constructit

import constructit.core.Evaluator
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.editor.DocumentFormat
import constructit.editor.ElementKind
import constructit.geom.Geom3
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * GitHub #17 — *two ways creating a disc*: an extruded circle and an outline revolved about **its own
 * side on the axis** must be the same 3D object, and the revolve must not carry a 0 mm hole where its
 * closing segment lies on the axis.
 *
 * The user's drawing, verbatim: a circle extruded to a disc (e4), then the same disc rebuilt by measuring
 * the first one — radius and depth read off its section — and revolving the resulting rectangle about the
 * side that lies on the axis (e28). A profile piece **on** the axis sweeps nothing by construction (its
 * band is `Band.Degenerate`, session 69), the ring at radius 0 collapses to a welded point, and
 * watertightness is what proves there is no hole — a crack at the axis would fail `assertManifold`, not
 * an eyeball.
 */
class DiscTwoWaysTest {
    private val script =
        """
constructit 2
point -35.33896388499252,-20.515316766172162 -> e1
point -11.862690931735507,-6.57460001576257 -> e2
tool circle pts=e1,e2 clicks=-77.5,20.5;-40.5,37 -> e3
tool makerel els=e2,e1 clicks=-40.5,36.25;-77.25,20.75 dofs=27.303460866545425mm;30.70275335387129deg
param "depth" = 5mm
tool extrude els=e3 clicks=-92.25,-17.5 scalar="depth" -> e4
tool line pts=e1,e2 clicks=-78.75,19.5;-40.75,37 -> e5
param "angle" = 90deg
sketchspace "plane1" line=e5 angle="angle"
sectioninput "plane1" el=e4 edge=1 -> e6
tool keypoints els=e6 clicks=-63.14517628135042,-0.20921502859541527 -> e7,e8
tool line pts=e8,e7 clicks=-102.89245610811433,-0.5091944989860862;-21.29804016185183,-0.5091944989860862 -> e9
pointoncurve e9 75.29534930394422,0.0000000000000000000000000000011239987439872907 dofs=42.26861643903972mm -> e10
tool perp pts=e10 els=e9 clicks=73.34548274640485,0.24075417699059115;75.2953493039442,-0.6591842341814217 -> e11
tool perpbis pts=e8,e7 clicks=-102.74246637291898,-0.5091944989860862;-22.04798883782851,-0.05922529340007979 -> e12
tool intersect els=e12,e9 clicks=-62.54521734056908,18.389512135626184;-54.595761375216306,0.09076444179525568 -> e13
tool mdist pts=e13,e7 clicks=-62.39522760537375,-0.05922529340007979;-20.848070956265822,-0.05922529340007979
tool ptatdist pts=e10 els=e9 clicks=75.44533903913955,0.24075417699059115;105.74326554859731,0.24075417699059115 scalar="dist" -> e14
tool perp pts=e14 els=e9 clicks=115.94256754188012,0.24075417699059115;115.94256754188012,0.24075417699059115 -> e15
tool ptatdist pts=e14 els=e15 clicks=115.94256754188012,-0.20921502859541527;115.94256754188012,9.390128023906055 scalar="depth" -> e16
tool perp pts=e16 els=e11 clicks=75.14535956874887,10.440056170273403;115.79257780668479,5.040425703241326 -> e17
tool intersect els=e17,e11 clicks=79.4950618894136,5.640384644022668;75.14535956874887,13.739830344570784 -> e18
tool segment pts=e10,e14 clicks=74.69539036316287,-0.3592047637907507;116.24254701227079,-0.05922529340007979 -> e19
tool segment pts=e14,e16 clicks=114.89263939551277,-0.3592047637907507;116.39253674746614,5.190415438436662 -> e20
tool segment pts=e16,e18 clicks=116.09255727707546,5.340405173631997;74.8453800983582,4.140487292069313 -> e21
tool segment pts=e18,e10 clicks=74.99536983355354,4.740446232850656;75.89530824472556,-0.05922529340007979 -> e22
tool outline els=e19,e20,e21,e22 clicks=93.4441072625798,-0.20921502859541527;116.09255727707546,1.740651528943946;95.55152120289887,5;75.29534930394422,2.5 -> e23,e24,e25,e26,e27
tool revolve els=e27,e11 clicks=97.04386090726786,0.09076444179525568;75.2953493039442,26.788937306564968 -> e28
""".trimStart()

    @Test
    fun theExtrudedDiscAndTheRevolvedDiscAreTheSameObject() {
        val doc = DocumentFormat.load(script)
        val solids = doc.elements.filter { it.kind == ElementKind.SOLID }
        assertEquals(2, solids.size, "the two discs")

        @Suppress("UNCHECKED_CAST")
        fun meshOf(i: Int) = Evaluator().solid(solids[i].ref as SolidRef).mesh
        val extruded = meshOf(0)
        val revolved = meshOf(1)
        assertManifold(extruded, "the extruded disc")
        assertManifold(revolved, "the revolved disc — a crack at the axis would fail here, so there is no 0 mm hole")

        val ve = Geom3.volume(extruded)
        val vr = Geom3.volume(revolved)
        assertTrue(abs(ve - vr) / ve < 1e-9, "the same object both ways: $ve vs $vr")

        // the drawing is a v2 file, so the migrated save is the fixed point, not the original bytes
        val once = DocumentFormat.save(doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "save ∘ load is a fixed point on the user's own drawing")
    }
}
