package constructit.editor

import constructit.core.Evaluator
import constructit.geom.Vec2

/**
 * The 3D view's interaction controller — [Editor]'s pointer API, one dimension up, and since edit-in-3D
 * slice 1 also **the router** that decides whether a gesture belongs to the view or to the armed tool.
 *
 * Same shape and same reason as before: it is **pure**, so orbiting, zooming, panning *and* drawing on the
 * working plane are simulated gestures in the headless test suite rather than something only a browser can
 * exercise (OP-12). The browser shell contributes nothing but event plumbing, the modifier's key state and
 * the GL calls; every decision about what a drag *means* lives here, where it can be asserted.
 *
 * **What makes this view an editing view is one thing: an active working plane** (OP-17's `activeSpace`).
 * Its plane and this view's camera make a [PlanePerspective], which is handed to the editor as
 * [Editor.pointing] — and from there the editor is unchanged: it receives plane coordinates, as the test
 * suite has always driven it. Nothing here knows what a tool is beyond whether one is armed.
 *
 * **The bodies are pickable too, and by name** — the ray seam's other half. A `SOLID` slot and a plain
 * selection resolve by ray ∩ mesh (session 63), and since edit-in-3D **slice 2** *Sketch on face* resolves the
 * hit to a **face** of the body's own face list and records that face's address, so a working plane is chosen
 * by clicking it here. Nothing in this class knows about either: it routes the gesture, and the editor's
 * projection ([Editor.pointing]) is what carries the ray.
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

    /**
     * The editor this view edits, or null for a purely read-only viewport (which is what every existing
     * `Viewport3Test` gesture drives, unchanged).
     */
    var editor: Editor? = null

    /**
     * Whether this view is the one on screen. The shell's view switch, and *also* what hands the editor its
     * projection and takes it back again — so a 2D canvas can never be drawn or clicked through a
     * perspective the user is no longer looking at.
     */
    var shown: Boolean = false
        set(value) {
            field = value
            syncPointing()
        }

    /**
     * **The camera modifier**, held: the view takes the drag even while a tool is armed.
     *
     * Ctrl in the shell, and the choice is made by elimination — the other modifiers already mean something
     * *inside* a drawing gesture and have to go on meaning it here: Shift is the axis lock, Alt declines a
     * snap and a pick cycle's join, Space is the pan (in both views, unchanged). Ctrl appears only in key
     * chords (Ctrl+Z / Ctrl+Y), never during a pointer gesture, so it is the one modifier that can be given
     * to the view without taking anything away from the tool.
     */
    var cameraModifier: Boolean = false

    /** Which consumer a drag belongs to. Decided at the press and held until the release — see [pointerUp]. */
    private enum class Owner { CAMERA, TOOL }

    private var lastScreen: Vec2? = null
    private var panning = false
    private var dragOwner: Owner? = null

    /** True while a drag is in progress (whether it orbits or pans). */
    val dragging: Boolean get() = lastScreen != null

    /**
     * The working plane seen through this camera, or null when there is nothing to edit on: no editor, this
     * view is not the one shown, or the active space's plane does not currently evaluate (OP-3).
     */
    fun projection(): PlanePerspective? {
        val e = editor ?: return null
        if (!shown) return null
        val plane = e.doc.activePlane3(Evaluator()) ?: return null
        return PlanePerspective(plane, camera, widthPx, heightPx)
    }

    /**
     * Whether a **plain** drag belongs to the editor rather than to the camera: **whenever there is a working
     * plane under this view**, for every tool including SELECT.
     *
     * This reverses slice 1's cut, which read *"SELECT's own gestures — the marquee, dragging a point — are
     * deliberately still the 2D canvas', because the one gesture they would compete with is the orbit, and an
     * orbit is this view's habit"*. That was decided before there was a working plane to point at; with one in
     * hand the user's answer is the other way round — *"it is not possible to move free points there … I'd vote
     * for using a modifier to rotate the scene, instead"* — and it is the better rule, because a view that can
     * draw a point it cannot then move is a view that owns half a gesture. The camera keeps the middle button,
     * Space ([panMode]) and the modifier ([cameraModifier]); the wheel is still always its own.
     *
     * With no plane — no editor, or a view nobody is looking at — the answer is no, and this is the read-only
     * viewport it has always been: a drag orbits and a click selects nothing.
     */
    fun editing(): Boolean = editor != null && projection() != null

    /**
     * Whether a **tool** is drawing here — [editing] minus SELECT, which is a different question from who owns
     * a drag and is asked in the two places that are about *building* geometry: what a double-click means
     * (finish the run, or reframe) and what the status line says.
     */
    fun drawing(): Boolean = editor?.let { it.toolId != Tools.SELECT } == true && projection() != null

    /**
     * Give the editor the projection it should be pointing and drawing through — or take it away.
     *
     * Called at the top of every entry point rather than cached, because the projection depends on the
     * camera, the viewport size *and* the active plane, and any of the three can have changed since the
     * last event. Cheap: the plan needs no evaluation at all ([Document.activePlane3]).
     */
    private fun syncPointing() {
        val e = editor ?: return
        e.pointing = projection()
    }

    private fun ownerNow(button: PointerButton): Owner =
        if (button == PointerButton.MIDDLE || panMode || cameraModifier || !editing()) Owner.CAMERA else Owner.TOOL

    fun pointerDown(
        screen: Vec2,
        button: PointerButton = PointerButton.PRIMARY,
    ) {
        syncPointing()
        val owner = ownerNow(button)
        dragOwner = owner
        if (owner == Owner.TOOL) {
            editor?.pointerDown(screen, button)
            return
        }
        lastScreen = screen
        panning = button == PointerButton.MIDDLE || panMode
    }

    fun pointerMove(screen: Vec2) {
        syncPointing()
        // A hover with no button down still belongs to the tool: that is what keeps a live preview and a
        // snap marker following the cursor in this view (`ToolDef.preview`), which is half of what makes
        // drawing here usable at all.
        if (dragOwner == Owner.TOOL || (dragOwner == null && editing())) {
            editor?.pointerMove(screen)
            return
        }
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
     * Release — and the place the modifier's **mid-gesture semantics** are stated: a drag goes to whoever
     * owned it at the press, whatever the modifier does in between.
     *
     * So letting Ctrl go halfway through an orbit finishes the orbit instead of teleporting geometry to the
     * cursor, and pressing it halfway through a tool's drag leaves that drag alone. What crossing the
     * modifier *does* change is the next gesture — and the tool is untouched by the detour: its collected
     * picks, its active path and its preview are all still there, now drawn through the camera the orbit
     * left behind (the projection is re-read per event, see [syncPointing]).
     */
    fun pointerUp(screen: Vec2) {
        syncPointing()
        val owner = dragOwner
        dragOwner = null
        if (owner == Owner.TOOL) {
            editor?.pointerUp(screen)
            return
        }
        if (lastScreen == null) return
        lastScreen = null
        panning = false
        onChange()
    }

    /**
     * Wheel zoom. Sign follows the 2D camera's: scrolling up (negative delta) moves closer.
     *
     * **Always the camera's**, modifier or not: no tool in this editor uses the wheel, so there is nothing
     * to take from one — and requiring a modifier to zoom while drawing would be a trap, since the 2D
     * camera the editor would otherwise zoom is not the one on screen ([Editor.wheel] refuses it for that
     * reason).
     *
     * Zoom is towards the **target**, not towards the cursor as the 2D camera's is: keeping a point
     * under the cursor fixed needs its depth, and the only depth this view knows is the working plane's —
     * which would make the zoom lurch as the cursor crossed on and off the plane.
     */
    @Suppress("UNUSED_PARAMETER")
    fun wheel(
        screen: Vec2,
        deltaY: Double,
    ) {
        syncPointing()
        camera = camera.zoom(if (deltaY < 0) 1.0 / ZOOM_STEP else ZOOM_STEP)
        onChange()
    }

    /**
     * A double-click: **finish the run** while a tool is drawing here (exactly what it means on the canvas),
     * and otherwise reframe — the cheap way back when an orbit has wandered off the part.
     *
     * One gesture, two meanings, decided by whether a tool is *drawing* here ([drawing]) rather than by who
     * owns a drag: reframing mid-path would throw the view away just as a wall was being closed, while under
     * SELECT — which now takes the plain drag too — a double-click has no run to finish and reframes as ever.
     */
    fun doubleClick(scene: Scene3) {
        if (drawing()) {
            syncPointing()
            editor?.finishPath()
        } else {
            frame(scene)
        }
    }

    /** Frame [scene]'s solids, keeping the current viewing direction. An empty scene is left alone. */
    fun frame(scene: Scene3) {
        val b = scene.bounds() ?: return
        camera = Camera3.framing(b.first, b.second, yaw = camera.yaw, pitch = camera.pitch)
        onChange()
    }

    /**
     * The whole view on one target: the shaded solids, with the working plane's sketch laid over them.
     *
     * What the headless suite renders, and what an SVG golden of an editing 3D view is. The browser splits
     * the same two layers across its two canvases (WebGL under, Canvas2D over — see [renderSketch]),
     * because that is what the platform makes cheap; the *content* is identical either way, since both go
     * through the one [SceneRenderer] and the one projection.
     */
    fun render(
        scene: Scene3,
        target: DrawTarget,
    ) {
        syncPointing()
        val e = editor
        val on = e != null && e.pointing != null
        Painter3.render(scene, camera, target, widthPx, heightPx) { t ->
            if (on) e!!.draw(t, widthPx, heightPx)
        }
    }

    /**
     * The sketch layer alone, on a target of its own — the browser's transparent 2D canvas over the GL one.
     * Nothing is drawn when there is no working plane to draw on, which is what leaves a read-only 3D view
     * looking exactly as it always did.
     */
    fun renderSketch(target: DrawTarget) {
        syncPointing()
        val e = editor ?: return
        target.begin(widthPx, heightPx)
        if (e.pointing != null) e.draw(target, widthPx, heightPx)
        target.end()
    }

    /** The status line while the 3D view is shown — including what it does *not* do. */
    fun help(): String {
        val e = editor
        val plane = projection()
        if (e != null && plane != null && e.toolId != Tools.SELECT) {
            val tool = e.doc.toolDef(e.toolId)?.label ?: e.toolId
            return "3D view: drawing $tool on ${e.doc.activeSpace.name} — click on the plane. " +
                "Hold Ctrl to orbit (wheel zooms, Space+drag pans), then carry on."
        }
        // with a plane under the view SELECT drags geometry here like it does on the canvas, so the line says
        // what the gestures now are rather than describing an orbit the modifier has taken over
        if (e != null && plane != null) {
            return "3D view: click to select and drag to move on ${e.doc.activeSpace.name}; a drag on empty " +
                "space boxes a selection. Ctrl+drag orbits (wheel zooms, Space+drag pans)."
        }
        return "3D view: drag to orbit, wheel to zoom, middle-drag or Space+drag to pan."
    }

    companion object {
        /** How far a pixel of drag turns the camera. */
        const val ORBIT_RAD_PER_PX = 0.008

        /** One wheel notch, matching the 2D camera's 1.1 per notch. */
        const val ZOOM_STEP = 1.1
    }
}
