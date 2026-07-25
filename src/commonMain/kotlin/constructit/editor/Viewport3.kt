package constructit.editor

import constructit.geom.Vec2

/**
 * The 3D view's interaction controller — [Editor]'s pointer API, one dimension up.
 *
 * Same shape and same reason: it is **pure**, so orbiting, zooming and panning are simulated gestures
 * in the headless test suite rather than something only a browser can exercise (OP-12). The browser
 * shell contributes nothing but event plumbing and the GL calls; every decision about what a drag
 * *means* lives here, where it can be asserted.
 *
 * There is deliberately **no picking in this view** — a click selects nothing, and the 2D toolset is
 * inert while it is shown (see [help]). Picking in 3D needs a ray/mesh intersection and an answer to
 * "what does selecting a face mean for a construction", which is the sketch-on-face task; guessing at
 * it now would put a second, weaker selection model beside the 2D one.
 */
class Viewport3(
    var camera: Camera3 = Camera3(),
    var widthPx: Double = 800.0,
    var heightPx: Double = 600.0,
) {
    var onChange: () -> Unit = {}

    /**
     * While set, a primary drag pans instead of orbiting — the browser shell maps Space to it, exactly
     * as it does for the 2D pan, so one habit works in both views.
     */
    var panMode: Boolean = false

    private var lastScreen: Vec2? = null
    private var panning = false

    /** True while a drag is in progress (whether it orbits or pans). */
    val dragging: Boolean get() = lastScreen != null

    fun pointerDown(
        screen: Vec2,
        button: PointerButton = PointerButton.PRIMARY,
    ) {
        lastScreen = screen
        panning = button == PointerButton.MIDDLE || panMode
    }

    fun pointerMove(screen: Vec2) {
        val last = lastScreen ?: return
        val dx = screen.x - last.x
        val dy = screen.y - last.y
        lastScreen = screen
        camera =
            if (panning) {
                camera.panBy(dx, dy, heightPx)
            } else {
                // dragging right turns the model to the right, i.e. the eye goes the other way round;
                // dragging down tips the top of the model towards the viewer
                camera.orbit(-dx * ORBIT_RAD_PER_PX, dy * ORBIT_RAD_PER_PX)
            }
        onChange()
    }

    /**
     * Release. The position is taken for symmetry with [Editor.pointerUp] and deliberately unused: a
     * release here cannot mean "select what is under the cursor", because this view has no picking.
     */
    @Suppress("UNUSED_PARAMETER")
    fun pointerUp(screen: Vec2) {
        if (lastScreen == null) return
        lastScreen = null
        panning = false
        onChange()
    }

    /**
     * Wheel zoom. Sign follows the 2D camera's: scrolling up (negative delta) moves closer.
     *
     * Zoom is towards the **target**, not towards the cursor as the 2D camera's is: keeping a point
     * under the cursor fixed needs its depth, and reading a depth off the model is picking — which this
     * slice does not have. The position is taken anyway so the two viewports share one pointer API.
     */
    @Suppress("UNUSED_PARAMETER")
    fun wheel(
        screen: Vec2,
        deltaY: Double,
    ) {
        camera = camera.zoom(if (deltaY < 0) 1.0 / ZOOM_STEP else ZOOM_STEP)
        onChange()
    }

    /** Frame [scene]'s solids, keeping the current viewing direction. An empty scene is left alone. */
    fun frame(scene: Scene3) {
        val b = scene.bounds() ?: return
        camera = Camera3.framing(b.first, b.second, yaw = camera.yaw, pitch = camera.pitch)
        onChange()
    }

    fun render(
        scene: Scene3,
        target: DrawTarget,
    ) = Painter3.render(scene, camera, target, widthPx, heightPx)

    /** The status line while the 3D view is shown — including what it does *not* do. */
    fun help(): String =
        "3D view: drag to orbit, wheel to zoom, middle-drag or Space+drag to pan. " +
            "The drawing tools apply to the 2D view — switch back to draw."

    companion object {
        /** How far a pixel of drag turns the camera. */
        const val ORBIT_RAD_PER_PX = 0.008

        /** One wheel notch, matching the 2D camera's 1.1 per notch. */
        const val ZOOM_STEP = 1.1
    }
}
