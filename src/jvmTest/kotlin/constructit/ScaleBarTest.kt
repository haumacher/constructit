package constructit

import constructit.editor.Camera
import constructit.editor.Editor
import constructit.editor.SceneRenderer
import constructit.editor.SvgDrawTarget
import constructit.editor.Tools
import constructit.geom.Vec2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **The corner scale bar** (queue #18 item 5): a round length, drawn at the size it really is.
 *
 * The assertable half is the arithmetic — the bar's whole job is that the number beside it is a number a
 * person recognises, and that is the grid's own 1/2/5 × 10^k rounding applied to a hundred pixels rather
 * than to forty. So the rule is checked at known zooms, and the drawing once, as a golden.
 */
class ScaleBarTest {
    @Test
    fun theBarPicksARoundLengthUnderAHundredPixels() {
        // scale is px per mm; 100 px is what the bar asks for and the rounding takes it down to 1/2/5 × 10^k
        assertEquals(100.0, SceneRenderer.scaleBarLength(1.0), "100 px at 1 px/mm is 100 mm exactly")
        assertEquals(50.0, SceneRenderer.scaleBarLength(2.0), "…and at 2 px/mm, 50 mm")
        assertEquals(20.0, SceneRenderer.scaleBarLength(4.0), "25 mm would fit, but 20 is the round one")
        assertEquals(20.0, SceneRenderer.scaleBarLength(3.0), "33.3 mm rounds down to 20 too")
        assertEquals(10.0, SceneRenderer.scaleBarLength(10.0))
        assertEquals(1.0, SceneRenderer.scaleBarLength(100.0), "zoomed right in, one millimetre")
        assertEquals(200.0, SceneRenderer.scaleBarLength(0.5), "and out again, two hundred")
        assertEquals("50 mm", SceneRenderer.scaleBarLabel(2.0), "labelled in the base unit the model is in")
        assertEquals("20 mm", SceneRenderer.scaleBarLabel(4.0))
    }

    @Test
    fun itFollowsTheZoomAndStaysRound() {
        // every step of the wheel, over four decades: the label is always a round number, and it never
        // claims more than a hundred pixels of screen
        var scale = 0.05
        while (scale < 500.0) {
            val mm = SceneRenderer.scaleBarLength(scale)
            val digits = mm / Math.pow(10.0, Math.floor(Math.log10(mm)))
            assertTrue(
                digits == 1.0 || digits == 2.0 || digits == 5.0,
                "at $scale px/mm the bar says $mm mm, which is not a 1/2/5 length",
            )
            assertTrue(mm * scale <= 100.0 + 1e-9, "at $scale px/mm the bar would be ${mm * scale} px, over the 100 px budget")
            assertTrue(mm * scale > 20.0, "…and it must not shrink to nothing either (${mm * scale} px)")
            scale *= 1.1
        }
    }

    @Test
    fun theBarIsAnOverlayOfTheRenderer() {
        val ed = Editor()
        ed.canvasW = 320.0
        ed.canvasH = 240.0
        ed.camera = Camera.centered(320.0, 240.0, scale = 2.0)
        ed.setTool(Tools.POINT)
        ed.click(Vec2(-20.0, 0.0))
        ed.click(Vec2(20.0, 0.0))
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(-20.0, 0.0))
        ed.click(Vec2(20.0, 0.0))

        // off by default, exactly as the grid is: a headless render stays a render of the geometry alone
        assertTrue(!ed.svg().contains("50 mm"), "no bar until the view asks for one")
        ed.showScaleBar = true
        val svg = ed.svg()
        assertTrue(svg.contains("50 mm"), "at 2 px/mm the bar is 50 mm; got:\n$svg")
        Golden.check("editor_scale_bar", svg)
    }

    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerDown(s)
        pointerUp(s)
    }

    private fun Editor.svg(): String {
        val t = SvgDrawTarget()
        render(t)
        return t.svg()
    }
}
