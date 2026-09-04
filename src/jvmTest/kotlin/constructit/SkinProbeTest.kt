package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.SketchSpace
import constructit.editor.Tools
import constructit.geom.BoolOp
import constructit.geom.Curves3
import constructit.geom.Geom3
import constructit.geom.Loop
import constructit.geom.Path3
import constructit.geom.Plane3
import constructit.geom.ProfileElement
import constructit.geom.Region
import constructit.geom.Segment
import constructit.geom.Sketch3
import constructit.geom.Skin3
import constructit.geom.SkinRow
import constructit.geom.SkinSection
import constructit.geom.SweepProfile
import constructit.geom.Vec2
import constructit.geom.Vec3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Probe review of the loft (OP-26, session 77) — compositions the package's own tests never try:
 *
 * 1. **A skin is an honest operand of the general boolean.** The ruled frustum between two squares
 *    is an exact prismatoid, and a constant tube is a prism of its own ring polygon — so a bore
 *    straight through both caps removes exactly its length-fraction of the tube, and the boolean's
 *    result is predictable to arithmetic, not to a band.
 *
 * 2. **The Match re-stamp layers under undo like one edit.** A Match rewrites the loft's own step;
 *    undoing it must return the body to the invalid-but-healing state that asked for the match, and
 *    redoing it must restore the identical body — with the file byte-equal round-trip and the
 *    reloaded body equal to the live one.
 */
class SkinProbeTest {
    // ---- 1. skin × boolean, exact ----

    private fun stationAt(z: Double) = Plane3(Vec3(0.0, 0.0, z), Vec3.X, Vec3.Y)

    private fun square(half: Double): Loop {
        val at = listOf(Vec2(-half, -half), Vec2(half, -half), Vec2(half, half), Vec2(-half, half))
        return Loop(at.indices.map { ProfileElement.Seg(Segment(at[it], at[(it + 1) % at.size])) })
    }

    private fun section(
        z: Double,
        loop: Loop,
    ) = SkinSection(Sketch3(stationAt(z), listOf(Region(loop, emptyList()))), z)

    @Test
    fun aBoreThroughASkinRemovesExactlyItsShareOfTheTube() {
        val (skin, whySkin) = Skin3.skin(listOf(section(0.0, square(20.0)), section(60.0, square(10.0))), SkinRow.RULED, emptyList())
        val frustum = assertNotNull(skin, "the frustum builds: $whySkin")
        // h/6 · (A0 + 4·A½ + A1) — exact, every corner linear in the run parameter
        assertClose(Geom3.volume(frustum.mesh), 10.0 * (1600.0 + 4.0 * 900.0 + 400.0), tol = 1e-9, msg = "the skin is its prismatoid")

        val run = Path3(Curves3.straightThrough(listOf(Vec3(0.0, 0.0, -10.0), Vec3(0.0, 0.0, 70.0))))
        val (tube, whyTube) = Geom3.sweep(run, Vec3.X, SweepProfile.Round(4.0))
        val bore = assertNotNull(tube, "the tube builds: $whyTube")
        assertManifold(bore.mesh, "the tube before the boolean")

        val (result, whyCut) = Geom3.combine(BoolOp.SUBTRACT, frustum, bore)
        val holed = assertNotNull(result, "the subtraction was refused: $whyCut")
        assertManifold(holed.mesh, "the skin with the bore through both caps")
        // the tube is a prism of its ring polygon, so the piece inside the skin is exactly its
        // length-fraction — 60 of 80 mm — and the polygon factor cancels without being known
        val removed = Geom3.volume(bore.mesh) * (60.0 / 80.0)
        assertClose(
            Geom3.volume(holed.mesh),
            Geom3.volume(frustum.mesh) - removed,
            tol = 1e-3,
            msg = "the bore removes exactly its share of the tube",
        )
    }

    // ---- 2. Match under undo/redo, and the reload ----

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

    private fun Editor.stationOn(
        mm: String,
        at: Vec2,
    ): SketchSpace {
        setActiveSpace("plan")
        setTool(Tools.STATION)
        type(mm)
        click(at)
        assertTrue(doc.activeSpace.isStation, "the station opened: $statusHint")
        return doc.activeSpace
    }

    private fun theSkin(ed: Editor): Element = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.SOLID }, "the skin exists")

    private fun invalidReason(ed: Editor): String? = (Evaluator().eval(theSkin(ed).ref.node) as? EvalResult.Invalid)?.reason

    private fun volume(ed: Editor): Double = Geom3.volume(Evaluator().solid(theSkin(ed).ref as SolidRef).mesh)

    @Test
    fun aMatchIsOneUndoStepBetweenTheHealingStateAndTheBody() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(300.0, 0.0))
        ed.setTool(Tools.CURVE3)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(300.0, 0.0))
        ed.key("Enter")
        ed.stationOn("60", Vec2(100.0, 0.0))
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(-20.0, -20.0))
        ed.click(Vec2(20.0, 20.0))
        ed.stationOn("240", Vec2(200.0, 0.0))
        ed.count = 3
        ed.setTool(Tools.POLYGON)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(12.0, 0.0))
        ed.setTool(Tools.LOFT_RULED)
        ed.click(Vec2(-6.0, 0.0))
        ed.setActiveSpace("station1")
        ed.click(Vec2(0.0, -20.0))
        ed.key("Enter")
        val healing = assertNotNull(invalidReason(ed), "4 against 3 pieces waits for a pair")
        assertTrue(
            healing.contains("Match") || healing.contains("Break"),
            "and the wait names its cures: $healing",
        )

        ed.setTool(Tools.MATCH_SECTIONS)
        ed.click(Vec2(0.0, -20.0))
        ed.setActiveSpace("station2")
        ed.click(Vec2(-6.0, 0.0))
        assertNull(invalidReason(ed), "the matched skin builds: ${ed.statusHint}")
        val built = volume(ed)

        assertTrue(ed.undo(), "the match undoes")
        assertEquals(
            healing,
            assertNotNull(invalidReason(ed), "one undo is back to the healing state, not past it"),
            "in the same words",
        )
        assertTrue(ed.redo(), "and redoes")
        assertNull(invalidReason(ed), "the redone match builds again")
        assertClose(volume(ed), built, tol = 1e-12, msg = "as the identical body")

        val text = DocumentFormat.save(ed.doc)
        assertEquals(text, DocumentFormat.save(DocumentFormat.load(text)), "save → load → save is byte-equal")
        val back = DocumentFormat.load(text)
        val reloaded = assertNotNull(back.elements.lastOrNull { it.kind == ElementKind.SOLID }, "the skin reloads")
        assertClose(
            Geom3.volume(Evaluator().solid(reloaded.ref as SolidRef).mesh),
            built,
            tol = 1e-12,
            msg = "and the reloaded body is the live one",
        )
    }
}
