package constructit

import constructit.core.Evaluator
import constructit.core.PointValue
import constructit.dsl.valueOf
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.Vec2
import kotlin.test.Test

/**
 * Probe on absolute anchoring: a point-on-line rider on a wall leg must ignore the host's EXTENT
 * (dragging the far corner) yet ride the host's own perpendicular moves — the two halves of "the
 * anchor corner is transparent".
 */
class JunctionAnchorProbeTest {
    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerMove(s)
        pointerDown(s)
        pointerUp(s)
    }

    private fun Editor.drag(
        from: Vec2,
        to: Vec2,
    ) {
        setTool(Tools.SELECT)
        pointerDown(camera.worldToScreen(from))
        pointerMove(camera.worldToScreen(to))
        pointerUp(camera.worldToScreen(to))
    }

    @Test
    fun aRiderIgnoresHostExtentButRidesHostMoves() {
        val ed = Editor()
        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 2.0)) // one horizontal leg
        ed.finishPath()
        ed.setTool(Tools.POINT_ON_LINE)
        ed.click(Vec2(30.0, 0.0)) // a slider at x=30 on the leg

        fun rider(): Vec2 {
            val el = ed.doc.elements.last { it.kind == ElementKind.ON_CURVE }
            return (Evaluator().valueOf(el.ref) as PointValue).p
        }
        assertClose(rider().x, 30.0)

        // extend the host: drag its far endpoint from x=100 to x=160 — the rider must not budge
        ed.drag(Vec2(100.0, 0.0), Vec2(160.0, 0.0))
        assertClose(rider().x, 30.0, msg = "host extent must be transparent to the rider")
        assertClose(rider().y, 0.0)

        // move the host itself: drag the leg perpendicular — the rider rides
        ed.drag(Vec2(80.0, 0.0), Vec2(80.0, -25.0))
        assertClose(rider().y, -25.0, msg = "the rider follows the leg's own move")
        assertClose(rider().x, 30.0, msg = "without sliding along it")
    }
}
