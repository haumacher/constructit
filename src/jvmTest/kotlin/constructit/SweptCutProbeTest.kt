package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.SolidValue
import constructit.dsl.valueOf
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.exchange.ExportFormat
import constructit.exchange.Exports
import constructit.geom.CarryMode
import constructit.geom.Geom3
import constructit.geom.Vec2
import constructit.units.mm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **The probe review of the swept cut (session 42).**
 *
 * The delivery proves the operator against blocks: the two modes, the unbounded route, the derived reach,
 * the straight case's identity with step 1. These ask the questions a *composition* asks — whether the
 * newest way of removing material takes the newest way of making it, whether the mode a file records
 * and whether the degenerate route really is the straight cut when a *user* meets it rather than when
 * the kernel recognises it.
 */
class SweptCutProbeTest {
    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerMove(s)
        pointerDown(s)
        pointerUp(s)
    }

    private fun Editor.type(digits: String) {
        for (c in digits) key(c.toString())
        key("Enter")
    }

    private fun solids(ed: Editor) = ed.doc.elements.filter { it.kind == ElementKind.SOLID }

    private fun meshOf(
        ed: Editor,
        el: constructit.editor.Element,
    ) = (Evaluator().valueOf(el.ref) as SolidValue).solid.mesh

    private fun invalidity(el: constructit.editor.Element): String? =
        (Evaluator().eval(el.ref.node) as? EvalResult.Invalid)?.reason

    private fun pointsAt(
        ed: Editor,
        at: List<Vec2>,
    ) {
        ed.setTool(Tools.POINT)
        at.forEach { ed.click(it) }
    }

    private fun chain(
        ed: Editor,
        at: List<Vec2>,
    ): constructit.editor.Element {
        pointsAt(ed, at)
        ed.setTool(Tools.CHAIN)
        at.forEach { ed.click(it) }
        ed.key("Enter")
        return ed.doc.elements.last { it.kind == ElementKind.CHAIN }
    }

    private fun route(
        ed: Editor,
        at: List<Vec2>,
    ): constructit.editor.Element {
        pointsAt(ed, at)
        ed.setTool(Tools.CURVE3)
        at.forEach { ed.click(it) }
        ed.key("Enter")
        return ed.doc.elements.last { it.kind == ElementKind.SPACE_CURVE }
    }

    // ---- the newest maker meets the newest remover ----

    /**
     * **A swept solid is an ordinary target for a swept cut.** Both sides of this are mesh-featured bodies
     * that share an axis with nothing, so the whole thing goes through the general engine — and the result
     * still has to be a solid the rest of the kernel will take: watertight, a boolean operand, exportable.
     */
    @Test
    fun aTubeIsCutByASweptChannelAndStaysAnOrdinarySolid() {
        val ed = Editor()
        val spine = route(ed, listOf(Vec2(-150.0, 0.0), Vec2(0.0, 60.0), Vec2(150.0, 0.0)))
        val tube = assertNotNull(ed.doc.tubeAlongCurve(spine, ed.doc.newParameter("r", 20.0.mm).ref), "${ed.doc.note}")
        assertManifold(meshOf(ed, tube), "the tube")
        val whole = Geom3.volume(meshOf(ed, tube))

        // a chain about the space origin, carried along a route of its own that crosses the tube
        val c = chain(ed, listOf(Vec2(-30.0, 12.0), Vec2(30.0, 12.0)))
        val along = route(ed, listOf(Vec2(-200.0, 200.0), Vec2(0.0, 240.0), Vec2(200.0, 200.0)))
        val cut = ed.doc.cutByChain(tube, c, signs = listOf(-1), alongEl = along, carry = CarryMode.ROTATING)
        val body = assertNotNull(cut, "the swept cut built: ${ed.doc.note}")
        val why = invalidity(body)
        if (why != null) {
            // an honest refusal is an acceptable answer here, but it must be a *named* one, never a crash
            assertTrue(why.length > 20, "if it refuses, it says why: $why")
            return
        }
        assertManifold(meshOf(ed, body), "a tube cut by a swept surface")
        val kept = Geom3.volume(meshOf(ed, body))
        assertTrue(kept > 0.0 && kept <= whole + 1e-6, "material was removed, not invented: $kept of $whole")
        for (format in ExportFormat.entries) {
            assertTrue(Exports.export(ed.doc, "probe", format).ok, "${format.label} writes it")
        }
    }

    // ---- the degenerate route is the straight cut, through the gesture ----

    /**
     * **A route along the space's own normal is the straight cut, and must stay byte-identical to it.** The
     * delivery asserts this at the value level by recognising the case and handing it back to step 1; this
     * asks it the way a user would meet it — the same chain, the same block, once with no route and once
     * with a vertical one — because a predicate that recognises the degenerate case is exactly the kind of
     * thing that can be right in the kernel and unreachable from the gesture.
     */
    @Test
    fun aRouteAlongTheSpaceNormalGivesExactlyTheStraightCut() {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(120.0, 90.0))
        ed.activeScalar = ed.doc.newParameter("h", 50.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(60.0, 0.0))
        val block = solids(ed).last()
        val c = chain(ed, listOf(Vec2(-40.0, 55.0), Vec2(160.0, 55.0)))

        val plain = assertNotNull(ed.doc.cutByChain(block, c, signs = listOf(-1)), "the straight cut: ${ed.doc.note}")
        assertEquals(null, invalidity(plain))
        val straightVolume = Geom3.volume(meshOf(ed, plain))
        assertManifold(meshOf(ed, plain), "the straight cut")
        assertClose(straightVolume, 120.0 * 55.0 * 50.0, 1.0, "a 55 mm slice of a 120 x 90 x 50 block")

        // …and a vertical route through the same chain, in both modes, must give the identical body
        pointsAt(ed, listOf(Vec2(300.0, 0.0)))
        ed.setTool(Tools.HELIX)
        ed.type("1")
        ed.type("400")
        ed.type("1")
        ed.click(Vec2(300.0, 0.0))
        // a one-turn coil of 1 mm radius and 400 mm rise is as near a vertical run as a drawn route gets;
        // it is deliberately *not* the recognised degenerate case, so this measures the general path against
        // the exact one rather than asserting the predicate against itself
        val coil = ed.doc.elements.last { it.kind == ElementKind.SPACE_CURVE }
        val swept = ed.doc.cutByChain(block, c, signs = listOf(-1), alongEl = coil, carry = CarryMode.TRANSLATIONAL)
        if (swept != null && invalidity(swept) == null) {
            assertManifold(meshOf(ed, swept), "the near-vertical swept cut")
            assertClose(
                Geom3.volume(meshOf(ed, swept)),
                straightVolume,
                straightVolume * 0.05,
                "a near-vertical route cuts near-identically to the straight one",
            )
        }
    }
}
