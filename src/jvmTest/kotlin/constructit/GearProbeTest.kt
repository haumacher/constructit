package constructit

import constructit.core.Evaluator
import constructit.dsl.Construction
import constructit.dsl.SpurGearArgs
import constructit.dsl.instance
import constructit.dsl.isValid
import constructit.dsl.scalar
import constructit.dsl.solid
import constructit.dsl.spurGear
import constructit.geom.Geom3
import constructit.units.deg
import constructit.units.mm
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Probes at the gear macro's domain edges, which its spec tests never visited: an odd, small tooth
 * count (no bbox symmetry to lean on), and a bore that swallows the root circle (the macro must say
 * no, and heal — OP-3).
 */
class GearProbeTest {
    @Test
    fun anOddSmallToothCountStillExtrudesManifold() {
        val cx = Construction()
        val g =
            cx.instance(
                spurGear,
                "g13",
                SpurGearArgs(cx.freePoint("c", 0.mm, 0.mm), cx.parameter("m", 3.mm), 13, cx.parameter("pa", 20.deg), cx.parameter("bore", 8.mm)),
            )
        val solidRef = cx.extrude(cx.sketchOn(cx.planeXY(), g.region), cx.parameter("d", 12.mm))
        val mesh = Evaluator().solid(solidRef).mesh
        assertManifold(mesh, "13-tooth m3 gear")
        // volume brackets: more than the root disc minus bore, less than the tip disc minus bore
        val rTip = Evaluator().scalar(g.tipRadius).mm
        val rRoot = Evaluator().scalar(g.rootRadius).mm
        val v = Geom3.volume(mesh)
        assertTrue(v > (Math.PI * rRoot * rRoot - Math.PI * 64.0) * 12.0, "volume above the root disc")
        assertTrue(v < (Math.PI * rTip * rTip - Math.PI * 64.0) * 12.0, "volume below the tip disc")
    }

    @Test
    fun aBoreSwallowingTheRootIsRefusedAndHeals() {
        val cx = Construction()
        val bore = cx.parameter("bore", 8.mm)
        val g =
            cx.instance(
                spurGear,
                "g",
                SpurGearArgs(cx.freePoint("c", 0.mm, 0.mm), cx.parameter("m", 2.mm), 20, cx.parameter("pa", 20.deg), bore),
            )
        assertTrue(Evaluator().isValid(g.region))

        cx.set(bore, 30.mm) // root radius for m2/z20 is 17.5 — the bore leaves no material
        assertFalse(Evaluator().isValid(g.region), "a bore beyond the root circle cannot be a gear")

        cx.set(bore, 8.mm)
        assertTrue(Evaluator().isValid(g.region), "and it heals (OP-3)")
    }
}
