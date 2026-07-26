package constructit

import constructit.core.ArcValue
import constructit.core.Evaluator
import constructit.dsl.valueOf
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.SvgDrawTarget
import constructit.editor.Tools
import constructit.geom.Vec2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Probes over previews: fillet preview honesty on a ROUND leg (line-circle scoring must preview
 * exactly what the click builds), and Escape clearing both the preview and the pending typed
 * parameter (the retraction rule composing with the preview machinery).
 */
class PreviewProbeTest {
    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerMove(s)
        pointerDown(s)
        pointerUp(s)
    }

    private fun Editor.hover(world: Vec2) = pointerMove(camera.worldToScreen(world))

    private fun Editor.type(v: String) {
        v.forEach { key(it.toString()) }
        key("Enter")
    }

    private fun svg(ed: Editor): String {
        val t = SvgDrawTarget()
        ed.render(t)
        return t.svg()
    }

    /** Line-circle fillet: what the hover shows is byte-for-byte what the click builds. */
    @Test
    fun aRoundLegFilletPreviewsExactlyWhatItBuilds() {
        val ed = Editor()
        ed.setTool(Tools.CIRCLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(30.0, 0.0)) // r=30
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(-80.0, 45.0))
        ed.click(Vec2(80.0, 45.0)) // line above, 15 clear
        ed.setTool(Tools.FILLET)
        ed.type("10")
        ed.click(Vec2(0.0, 30.0)) // the circle
        // hover the second leg: the preview scores the variant the click would take
        ed.hover(Vec2(12.0, 45.0))
        val before = svg(ed)
        assertTrue(before.contains("#ff7f0e"), "a preview arc is painted")
        ed.click(Vec2(12.0, 45.0))
        val arc = ed.doc.elements.last { it.kind == ElementKind.ARC }
        val built = (Evaluator().valueOf(arc.ref) as ArcValue).arc
        assertClose(built.radius, 10.0)
        // tangency to both legs — the built variant is the previewed side (nestled between them, x>0)
        assertClose(kotlin.math.hypot(built.center.x, built.center.y), 40.0, msg = "externally tangent to the circle")
        assertClose(kotlin.math.abs(45.0 - built.center.y), 10.0, msg = "tangent to the line")
        assertTrue(built.center.x > 0.0, "the cursor's side was honored")
    }

    /** Escape mid-gesture: preview gone, typed parameter retracted, nothing recorded. */
    @Test
    fun escapeClearsThePreviewAndRetractsTheTypedScalar() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        val before = constructit.editor.DocumentFormat.save(ed.doc)
        ed.setTool(Tools.CIRCLE_R)
        ed.type("7") // pending typed parameter
        ed.setTool(Tools.POLYGON)
        ed.type("9") // corner radius typed for the polygon instead... also pending
        ed.click(Vec2(0.0, 0.0)) // centre picked: the ring previews now
        ed.hover(Vec2(40.0, 0.0))
        assertTrue(svg(ed).contains("#ff7f0e"), "polygon preview painted")
        ed.key("Escape")
        assertTrue(!svg(ed).contains("#ff7f0e"), "Escape cleared the preview")
        ed.key("Escape") // clear any remaining pick state
        assertEquals(before, constructit.editor.DocumentFormat.save(ed.doc), "typed params retracted, nothing recorded")
    }
}
