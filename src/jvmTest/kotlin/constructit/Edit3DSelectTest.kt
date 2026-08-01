package constructit

import constructit.core.Evaluator
import constructit.core.PointValue
import constructit.dsl.valueOf
import constructit.editor.Camera3
import constructit.editor.DrawTarget
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.PointerButton
import constructit.editor.Style
import constructit.editor.TextAnchor
import constructit.editor.Tools
import constructit.editor.Viewport3
import constructit.geom.Vec2
import constructit.geom.Vec3
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **The reversal**: with a working plane under the 3D view, a plain primary drag belongs to the *editor* —
 * SELECT included — and the camera keeps the modifier, the middle button and Space.
 *
 * Slice 1 decided the other way and said so: *"SELECT's own gestures — the marquee, dragging a point — are
 * deliberately still the 2D canvas', because the one gesture they would compete with is the orbit, and an
 * orbit is this view's habit."* The user, with the working plane in hand, overruled it: *"Construction in the
 * 3D display works pretty well, however, it is not possible to move free points there. The mouse gesture
 * click and drag is bound to rotate the scene, not move points. I'd vote for using a modifier to rotate the
 * scene, instead."*
 *
 * What must **not** move with it is the read-only viewport: with no plane, a drag still orbits.
 */
class Edit3DSelectTest {
    private val wPx = 800.0
    private val hPx = 600.0

    /** An editor with two free points on the plan, and a 3D view over it with SELECT armed. */
    private fun viewOverTwoPoints(): Pair<Editor, Viewport3> {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        for (p in listOf(Vec2(0.0, 0.0), Vec2(80.0, 60.0))) {
            val s = ed.camera.worldToScreen(p)
            ed.pointerDown(s)
            ed.pointerUp(s)
        }
        ed.setTool(Tools.SELECT)
        val vp = Viewport3(camera = Camera3(target = Vec3(40.0, 30.0, 0.0), distance = 300.0), widthPx = wPx, heightPx = hPx)
        vp.editor = ed
        vp.shown = true
        return ed to vp
    }

    private fun Viewport3.at(plane: Vec2): Vec2 = assertNotNull(assertNotNull(projection()).toScreen(plane))

    private fun Viewport3.dragFrom(
        a: Vec2,
        b: Vec2,
        button: PointerButton = PointerButton.PRIMARY,
    ) {
        pointerDown(a, button)
        pointerMove(b)
        pointerUp(b)
    }

    private fun posOf(
        el: Element,
        ev: Evaluator = Evaluator(),
    ): Vec2 = assertNotNull((ev.valueOf(el.ref) as? PointValue)?.p)

    private fun freePoints(ed: Editor) = ed.doc.elements.filter { it.kind == ElementKind.POINT }

    /** (a) A plain drag on a free point moves **that point**, through the ray seam, and leaves the camera. */
    @Test
    fun aPlainDragMovesAFreePointAndNotTheCamera() {
        val (ed, vp) = viewOverTwoPoints()
        val pt = freePoints(ed)[0]
        val before = vp.camera
        vp.dragFrom(vp.at(Vec2(0.0, 0.0)), vp.at(Vec2(25.0, 10.0)))
        val now = posOf(pt)
        assertClose(now.x, 25.0, tol = 1e-6, msg = "the point took the drag: ${ed.statusHint}")
        assertClose(now.y, 10.0, tol = 1e-6)
        assertEquals(before, vp.camera, "and the camera did not move at all")
        // the other point stayed where it was: a drag moves what it grabbed, exactly as on the canvas
        assertClose(posOf(freePoints(ed)[1]).x, 80.0, tol = 1e-6)
    }

    /** …and the grab uses the view's own px→mm tolerance, not a millimetre constant. */
    @Test
    fun theGrabFollowsTheLocalPickTolerance() {
        val (ed, vp) = viewOverTwoPoints()
        val pt = freePoints(ed)[0]
        val tol = ed.pickToleranceAt(Vec2(0.0, 0.0))
        assertTrue(tol > 0.0 && tol.isFinite(), "the local tolerance is a number: $tol mm")

        // six of the ten pixels away, in this view's own millimetres at that spot: still a grab
        val sixPx = 6.0 / ed.tolPx * tol
        vp.dragFrom(vp.at(Vec2(0.0, sixPx)), vp.at(Vec2(0.0, sixPx + 20.0)))
        assertClose(posOf(pt).y, 20.0, tol = 1e-6, msg = "6 px off ($sixPx mm here) grabbed it: ${ed.statusHint}")

        // and well outside it, nothing is grabbed — the press starts a box instead
        val far = 4.0 * tol
        val (ed2, vp2) = viewOverTwoPoints()
        vp2.dragFrom(vp2.at(Vec2(0.0, -far)), vp2.at(Vec2(0.0, -far - 5.0)))
        assertClose(posOf(freePoints(ed2)[0]).y, 0.0, tol = 1e-9, msg = "$far mm off grabs nothing")
    }

    /** (b) The modifier still orbits, and moves nothing. */
    @Test
    fun theModifierOrbitsAndLeavesTheDrawingAlone() {
        val (ed, vp) = viewOverTwoPoints()
        val pt = freePoints(ed)[0]
        vp.cameraModifier = true
        val yaw = vp.camera.yaw
        vp.dragFrom(vp.at(Vec2(0.0, 0.0)), Vec2(vp.at(Vec2(0.0, 0.0)).x + 60.0, vp.at(Vec2(0.0, 0.0)).y))
        assertTrue(abs(vp.camera.yaw - yaw) > 1e-9, "the modifier gave the drag to the camera")
        assertClose(posOf(pt).x, 0.0, tol = 1e-9, msg = "and the point stayed where it was")
        assertClose(posOf(pt).y, 0.0, tol = 1e-9)
    }

    /** (c) The middle button still pans — a button, not a mode, in this view as in the other. */
    @Test
    fun theMiddleButtonStillPans() {
        val (ed, vp) = viewOverTwoPoints()
        val pt = freePoints(ed)[0]
        val target = vp.camera.target
        val a = vp.at(Vec2(0.0, 0.0))
        vp.dragFrom(a, Vec2(a.x + 40.0, a.y + 25.0), PointerButton.MIDDLE)
        assertTrue((vp.camera.target - target).length() > 1e-6, "the middle drag panned the view")
        assertClose(posOf(pt).x, 0.0, tol = 1e-9, msg = "and moved nothing in the drawing")
        // Space is the same pan, through [Viewport3.panMode]
        val (ed2, vp2) = viewOverTwoPoints()
        vp2.panMode = true
        val t2 = vp2.camera.target
        val b = vp2.at(Vec2(0.0, 0.0))
        vp2.dragFrom(b, Vec2(b.x + 40.0, b.y))
        assertTrue((vp2.camera.target - t2).length() > 1e-6, "Space+drag pans too")
        assertClose(posOf(freePoints(ed2)[0]).x, 0.0, tol = 1e-9)
    }

    /** (d) A view with no working plane under it is the read-only one it always was: a plain drag orbits. */
    @Test
    fun withNoPlaneAPlainDragStillOrbits() {
        val (ed, vp) = viewOverTwoPoints()
        vp.shown = false // nobody is looking through it, so it has no projection to point with
        assertTrue(!vp.editing())
        val yaw = vp.camera.yaw
        vp.dragFrom(Vec2(400.0, 300.0), Vec2(460.0, 300.0))
        assertClose(vp.camera.yaw, yaw - 60.0 * Viewport3.ORBIT_RAD_PER_PX, 1e-12, "it orbits, exactly as before")
        assertClose(posOf(freePoints(ed)[0]).x, 0.0, tol = 1e-9, msg = "and nothing was dragged")
    }

    /** (e) A tool sequence still survives an orbit detour — and the reversal lets the result be moved after. */
    @Test
    fun aToolFlowSurvivesAnOrbitAndItsResultCanThenBeDragged() {
        val (ed, vp) = viewOverTwoPoints()
        ed.setTool(Tools.SEGMENT)
        vp.pointerDown(vp.at(Vec2(0.0, 0.0)))
        vp.pointerUp(vp.at(Vec2(0.0, 0.0)))

        vp.cameraModifier = true
        vp.dragFrom(Vec2(700.0, 120.0), Vec2(760.0, 150.0))
        vp.cameraModifier = false

        vp.pointerDown(vp.at(Vec2(80.0, 60.0)))
        vp.pointerUp(vp.at(Vec2(80.0, 60.0)))
        assertEquals(1, ed.doc.elements.count { it.kind == ElementKind.SEGMENT }, "the segment: ${ed.statusHint}")

        // …and now the reversal: grab the far end in the 3D view and move it, through the orbited camera
        ed.setTool(Tools.SELECT)
        val end = freePoints(ed)[1]
        val cam = vp.camera
        vp.dragFrom(vp.at(Vec2(80.0, 60.0)), vp.at(Vec2(60.0, 90.0)))
        val moved = posOf(end)
        assertClose(moved.x, 60.0, tol = 1e-6, msg = "the endpoint moved in 3D: ${ed.statusHint}")
        assertClose(moved.y, 90.0, tol = 1e-6)
        assertEquals(cam, vp.camera, "without the camera taking any of it")
    }

    /**
     * The box selection is **the rectangle on the plane**, both in what it takes and in what it draws.
     *
     * A screen rectangle is not a plane rectangle under perspective, so the band is drawn from the plane
     * rect's own four corners, projected. That is the one way the drawing and the selection cannot disagree.
     */
    @Test
    fun theBoxSelectionIsAPlaneRectangleAndIsDrawnAsOne() {
        val (ed, vp) = viewOverTwoPoints()
        val a = Vec2(-20.0, -20.0)
        val b = Vec2(40.0, 30.0) // covers (0,0) but not (80,60)
        vp.pointerDown(vp.at(a))
        vp.pointerMove(vp.at(b))

        val cap = Capture()
        vp.renderSketch(cap)
        val corners = listOf(a, Vec2(b.x, a.y), b, Vec2(a.x, b.y), a).map { vp.at(it) }
        val band = cap.polylines.firstOrNull { it.size == 5 && (it[0] - corners[0]).length() < 1e-9 }
        assertNotNull(band, "the rubber band is drawn: ${cap.polylines.size} polylines")
        for (i in corners.indices) {
            assertClose(band[i].x, corners[i].x, tol = 1e-9, msg = "band corner $i is the plane corner projected")
            assertClose(band[i].y, corners[i].y, tol = 1e-9)
        }

        vp.pointerUp(vp.at(b))
        assertEquals(1, ed.selectedElements.size, "one point in the box: ${ed.statusHint}")
        assertClose(posOf(ed.selectedElements[0]).x, 0.0, tol = 1e-9, msg = "and it is the one inside it")
    }

    /** The status line says what the gestures now are, rather than describing the orbit it gave away. */
    @Test
    fun theStatusLineNamesTheNewBinding() {
        val (_, vp) = viewOverTwoPoints()
        val help = vp.help()
        assertTrue(help.contains("drag to move"), "it says a drag moves: $help")
        assertTrue(help.contains("Ctrl+drag orbits"), "…and where the orbit went: $help")
    }

    /** Captures what a render call actually asked for — the polylines are enough for the band. */
    private class Capture : DrawTarget {
        val polylines = ArrayList<List<Vec2>>()

        override fun begin(
            width: Double,
            height: Double,
        ) = Unit

        override fun polyline(
            points: List<Vec2>,
            style: Style,
        ) {
            polylines.add(points)
        }

        override fun polygon(
            points: List<Vec2>,
            style: Style,
        ) = Unit

        override fun circle(
            center: Vec2,
            radius: Double,
            style: Style,
        ) = Unit

        override fun dot(
            center: Vec2,
            radiusPx: Double,
            color: String,
        ) = Unit

        override fun text(
            at: Vec2,
            s: String,
            style: Style,
            anchor: TextAnchor,
        ) = Unit

        override fun end() = Unit
    }
}
