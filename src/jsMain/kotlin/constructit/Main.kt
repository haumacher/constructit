package constructit

import constructit.core.Evaluator
import constructit.dsl.scalar
import constructit.editor.Appearance
import constructit.editor.BrowserCanvasDrawTarget
import constructit.editor.Camera
import constructit.editor.CreateDialog
import constructit.editor.CreateMode
import constructit.editor.DocumentFormat
import constructit.editor.DocumentName
import constructit.editor.Editor
import constructit.editor.Format
import constructit.editor.Icons
import constructit.editor.PointerButton
import constructit.editor.Preview3
import constructit.editor.Scene3
import constructit.editor.Scene3Sync
import constructit.editor.Tools
import constructit.editor.Viewport3
import constructit.editor.WebGlRenderer3
import constructit.exchange.ExportFormat
import constructit.exchange.ExportScene
import constructit.exchange.Exports
import constructit.geom.Justification
import constructit.geom.MeshBool
import constructit.geom.Vec2
import constructit.units.Dimension
import constructit.units.Quantity
import kotlinx.browser.document
import kotlinx.browser.window
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8Array
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.events.MouseEvent
import org.w3c.dom.events.WheelEvent
import org.w3c.dom.url.URL
import org.w3c.files.Blob
import org.w3c.files.BlobPropertyBag
import org.w3c.files.FileReader

fun main() {
    window.addEventListener("load", { setupApp() })
}

private fun setupApp() {
    val canvas = document.getElementById("canvas") as HTMLCanvasElement
    val canvas3 = document.getElementById("canvas3") as HTMLCanvasElement
    val ctx = canvas.getContext("2d") as CanvasRenderingContext2D

    fun fit(c: HTMLCanvasElement): Boolean {
        val area = c.parentElement as HTMLElement
        if (c.width == area.clientWidth && c.height == area.clientHeight) return false
        c.width = area.clientWidth
        c.height = area.clientHeight
        return true
    }
    fit(canvas)

    val editor = Editor(canvasW = canvas.width.toDouble(), canvasH = canvas.height.toDouble())
    editor.showGrid = true
    // the corner ruler, on in the shell exactly as the grid is (both are view state the Editor defaults off,
    // so a headless render stays a render of the geometry alone)
    editor.showScaleBar = true
    editor.camera = Camera.centered(canvas.width.toDouble(), canvas.height.toDouble(), scale = 4.0)
    val target = BrowserCanvasDrawTarget(ctx)

    buildPalette(editor)

    // ---- the 3D view (OP-12: Canvas2D for 2D, WebGL for 3D) ----
    //
    // A second canvas over the same area, and a pure [Viewport3] driving it — the shell contributes only
    // event plumbing and the GL calls, so orbit/zoom/pan are the headless-tested gestures of the
    // controller and nothing about *what a drag means* lives here.
    val viewport = Viewport3()
    // ...and since edit-in-3D slice 1, the same view *edits*: the controller is handed the editor once, and
    // decides for itself whether a gesture is the camera's or the armed tool's (its `shown` flag is what the
    // view switch below writes). The shell still contributes only plumbing — which canvas got the event, and
    // whether the modifier is down.
    viewport.editor = editor
    val gl = WebGlRenderer3(canvas3)
    var view3d = false
    // What the GPU is holding, and whether it is still what the document says. [Editor.onChange] is only
    // the *prompt* to ask — it fires on every hover too, since a previewing tool refreshes its preview on
    // each pointer move — and [Scene3Sync] gives the exact answer from mesh identity (OP-5), so plain mouse
    // motion over the 3D canvas no longer re-creases and re-uploads half a million triangles. An orbit does
    // not go through the editor at all and therefore does not even ask: it re-issues the draw call with a
    // new matrix, as it always did.
    val glSync = Scene3Sync()
    var glCheck = true

    fun draw3d() {
        // Resizing the drawing buffer deliberately does *not* invalidate the geometry: a vertex buffer has
        // nothing to do with how many pixels it is rasterized into, and only the viewport and the matrix —
        // both read per frame in `gl.draw` — depend on the size.
        fit(canvas3)
        viewport.widthPx = canvas3.width.toDouble()
        viewport.heightPx = canvas3.height.toDouble()
        if (glCheck) {
            glSync.update(Scene3.extract(editor.doc)) { gl.upload(it) }
            glCheck = false
        }
        gl.draw(viewport.camera)
        // The working plane's sketch, over the shaded solids: the *same* renderer and the same document as
        // the 2D view, through a perspective projection instead of the canvas camera (edit-in-3D slice 1).
        // Two canvases rather than one because the platform makes it cheap — GL below, Canvas2D above — and
        // `Viewport3.render` composes exactly the same two layers onto one target for the headless goldens.
        viewport.renderSketch(target)
        canvas3.style.cursor = if (viewport.editing()) "crosshair" else "grab"
    }

    // ---- the realistic preview: a *third* view, display-only (three.js, loaded on first open) ----
    //
    // Its own canvas over the same area, so the two editing views keep working exactly as they did: nothing
    // here touches them, and turning the preview off puts the pointer back where it was. What crosses into it
    // is the neutral `ExportScene` — the very scene the GLB writer consumes — so what this shows is what an
    // exported file shows, by construction.
    val canvasPreview = document.getElementById("canvas-preview") as HTMLCanvasElement
    val preview = Preview3(canvasPreview)
    var previewOn = false

    fun drawPreview() {
        // The scene's *name* is the exported file's root-node name and nothing else, so the preview does not
        // need the drawing's name at all — it needs the bodies and their materials.
        val scene = ExportScene.extract(editor.doc)
        preview.update(scene)
        preview.draw()
    }

    fun repaint() {
        // the drawing buffer must match the element's CSS size on *every* paint, not only on window
        // resize: panel content or a wrapping status line changes the canvas box too, and a stale buffer
        // is silently scaled by CSS — which offsets every hit test from what the user sees
        if (fit(canvas)) {
            editor.canvasW = canvas.width.toDouble()
            editor.canvasH = canvas.height.toDouble()
        }
        if (view3d) {
            glCheck = true
            draw3d()
        } else {
            editor.render(target)
        }
        // the preview follows every document change like the other two views — and costs a *buffer upload*
        // only for the bodies whose mesh actually changed (OP-5, see Preview3.update)
        if (previewOn && preview.ready) drawPreview()
        renderPanel(editor, view3d, viewport)
    }

    /**
     * **One paint per animation frame.** Pointer events arrive faster than the display refreshes — a fast
     * orbit delivers several `mousemove`s between two frames — and painting each of them synchronously
     * meant the view drew work nobody would ever see, then fell behind the cursor doing it.
     *
     * Deliberately in the shell and nowhere else. `requestAnimationFrame` is a platform API, so it cannot
     * live in `commonMain` (OP-12); more to the point [Viewport3] must stay the pure controller the headless
     * suite drives synchronously — a gesture there still means exactly one `onChange`, and it is the *shell*
     * that decides how many of those become pixels. Everything else (`repaint`, the view switch) still
     * paints straight through, so a state change is on screen before the next line of code runs.
     */
    var framePending = false

    fun draw3dSoon() {
        if (framePending) return
        framePending = true
        window.requestAnimationFrame {
            framePending = false
            draw3d()
        }
    }
    editor.onChange = { repaint() }
    // **A camera move is not a document change.** An orbit writes one of the camera's four numbers and
    // nothing else: no element, no parameter, no selection, no status line (the panel's only 3D-dependent
    // text is `Viewport3.help`, which reads the tool and the plane, never the eye). So the side panel is
    // left entirely alone here — rebuilding it per `mousemove` rewrote six lists and allocated an evaluator
    // for a picture that had not changed. The document-change path above keeps owning the panel.
    viewport.onChange = { draw3dSoon() }

    fun setView3d(on: Boolean) {
        view3d = on
        // the 2D canvas stays *shown* in the 3D view — as the transparent sketch layer over the GL canvas —
        // but stops taking the pointer, since the 3D canvas is the one that routes gestures there
        viewport.shown = on
        canvas.className = if (on) "overlay" else ""
        canvas3.hidden = !on
        (document.getElementById("v-2d") as HTMLElement).className = if (on) "" else "active"
        (document.getElementById("v-3d") as HTMLElement).className = if (on) "active" else ""
        if (on) {
            glCheck = true
            // frame the solids the first time there is something to look at, so switching over does not
            // land on an empty view with the part behind the camera
            viewport.widthPx = canvas3.width.toDouble()
            viewport.heightPx = canvas3.height.toDouble()
            val scene = Scene3.extract(editor.doc)
            if (!scene.isEmpty) viewport.frame(scene)
            editor.note(
                if (scene.isEmpty && !viewport.editing()) {
                    "Nothing solid yet — trace an Outline (or a Wall), then use Extrude or Revolve."
                } else {
                    viewport.help()
                },
            )
            if (!gl.available) editor.note("This browser gave no WebGL context, so the 3D view cannot draw.")
        } else {
            editor.note("")
        }
        repaint()
    }

    /** What the status line says while the preview is up: what is in it, and what it deliberately is not. */
    fun previewNote(scene: ExportScene): String =
        scene.refusal
            ?: "Preview: ${scene.nodes.size} solid(s), ${scene.triangleCount} triangles — display only " +
            "(drag to orbit, wheel to zoom, Space+drag to move, double-click to reframe)" +
            if (scene.notes.isEmpty()) "" else " · ${scene.notes.joinToString("; ")}"

    /**
     * Show or hide the preview. **The editing views are untouched by its presence**: it is a canvas above
     * them, and turning it off gives the pointer straight back to whichever of them was in front.
     *
     * three.js is fetched here, on the first open — not in the main bundle (see [Preview3.load]) — so this is
     * the one place in the shell that has to cope with a view that is not ready yet. It copes the way the
     * WASM engine's arrival is coped with (OP-3): the app carries on, and the panel says what happened.
     */
    fun setPreview(on: Boolean) {
        previewOn = on
        canvasPreview.hidden = !on
        (document.getElementById("v-prev") as HTMLElement).className = if (on) "active" else ""
        if (!on) {
            editor.note("")
            repaint()
            return
        }
        preview.load { ok ->
            val scene = ExportScene.extract(editor.doc)
            // `update` is what starts the renderer, so `ready` is only meaningful after it
            if (ok) preview.update(scene)
            if (ok && preview.ready) {
                if (!scene.isEmpty) preview.frame(scene)
                preview.draw()
                editor.note(previewNote(scene))
                console.log("[Preview] ready — ${scene.nodes.size} solid(s), ${scene.triangleCount} triangles")
            } else {
                editor.note(preview.problem ?: "the preview could not start")
            }
            renderPanel(editor, view3d, viewport)
        }
        repaint()
    }
    (document.getElementById("v-2d") as HTMLElement).addEventListener("click", {
        setPreview(false)
        setView3d(false)
    })
    (document.getElementById("v-3d") as HTMLElement).addEventListener("click", {
        setPreview(false)
        setView3d(true)
    })
    (document.getElementById("v-prev") as HTMLElement).addEventListener("click", { setPreview(!previewOn) })

    // Which sketch space is active (OP-17): the view indicator *and* the way back to the plan.
    // A `<select>` because that is what "one of these, and here is which" is; the Editor owns the switch
    // (its own camera per space, its own selection reset), so this only routes.
    //
    // It no longer switches to the 2D view. That was right while the 3D view was read-only ("a space is a
    // 2D thing, so asking for one means looking at it"), and it is wrong now: the active space *is* the
    // working plane the 3D view edits on, so choosing one from here is how a plane is chosen without
    // leaving that view (edit-in-3D slice 1 — the plane is chosen the existing way).
    (document.getElementById("v-space") as HTMLElement).addEventListener("change", {
        val picked = (document.getElementById("v-space") as HTMLSelectElement).value
        editor.setActiveSpace(picked)
        repaint()
    })

    // ---- canvas pointer input ----
    fun pos(e: MouseEvent): Vec2 {
        val r = canvas.getBoundingClientRect()
        return Vec2(e.clientX - r.left, e.clientY - r.top)
    }
    // Space held turns a primary drag into a pan, which the controller sees as the middle button —
    // so the shell keeps the key mapping and the pure controller keeps knowing only buttons (OP-16)
    var spaceDown = false

    fun button(e: MouseEvent): PointerButton =
        if (e.button.toInt() == 1 || spaceDown) PointerButton.MIDDLE else PointerButton.PRIMARY
    canvas.addEventListener("mousedown", {
        val e = it as MouseEvent
        if (e.button.toInt() == 1) e.preventDefault() // no middle-click autoscroll
        editor.pointerDown(pos(e), button(e), additive = e.shiftKey)
    })
    canvas.addEventListener("mousemove", { editor.pointerMove(pos(it as MouseEvent)) })
    canvas.addEventListener("mouseup", { editor.pointerUp(pos(it as MouseEvent)) })
    canvas.addEventListener("mouseleave", { editor.pointerUp(pos(it as MouseEvent)) })
    canvas.addEventListener("wheel", {
        val e = it as WheelEvent
        e.preventDefault()
        editor.wheel(pos(e), e.deltaY)
    })
    canvas.addEventListener("dblclick", { editor.finishPath() })

    // ---- 3D canvas input: every gesture routed to the pure Viewport3, which decides whose it is ----
    //
    // The shell's whole contribution to edit-in-3D: which canvas the event came from, and whether the camera
    // modifier is down. Where the ray lands, whether the tool or the camera takes the drag, and what a
    // release means are all [Viewport3]'s, in commonMain, where the headless suite drives them.
    fun pos3(e: MouseEvent): Vec2 {
        val r = canvas3.getBoundingClientRect()
        return Vec2(e.clientX - r.left, e.clientY - r.top)
    }

    /** Ctrl (Cmd on a Mac) held: the camera takes the drag even while a tool is armed. */
    fun modifier(e: MouseEvent): Boolean = e.ctrlKey || e.metaKey
    canvas3.addEventListener("mousedown", {
        val e = it as MouseEvent
        if (e.button.toInt() == 1) e.preventDefault()
        viewport.panMode = spaceDown
        viewport.cameraModifier = modifier(e)
        viewport.pointerDown(pos3(e), if (e.button.toInt() == 1 || spaceDown) PointerButton.MIDDLE else PointerButton.PRIMARY)
    })
    canvas3.addEventListener("mousemove", {
        val e = it as MouseEvent
        viewport.cameraModifier = modifier(e)
        viewport.pointerMove(pos3(e))
    })
    canvas3.addEventListener("mouseup", { viewport.pointerUp(pos3(it as MouseEvent)) })
    canvas3.addEventListener("mouseleave", { viewport.pointerUp(pos3(it as MouseEvent)) })
    canvas3.addEventListener("wheel", {
        val e = it as WheelEvent
        e.preventDefault()
        viewport.wheel(pos3(e), e.deltaY)
    })
    // double-click reframes — unless a tool is drawing here, where it means what it means on the canvas:
    // finish the run. The decision is the controller's, so it is asserted headlessly like the rest.
    canvas3.addEventListener("dblclick", { viewport.doubleClick(Scene3.extract(editor.doc)) })

    // ---- preview input: the camera, and nothing else ----
    //
    // No routing decision to make here, and that is the point of a display-only view: there is no tool to
    // give a drag to, no ray to cast, no pick to resolve. So the shell maps the gesture straight onto the
    // *same* orbit camera the working 3D view uses ([Camera3], whose arithmetic is headlessly tested) —
    // Space or the middle button meaning "move what the view looks at", exactly as it does over there.
    var previewDrag: Vec2? = null
    var previewPan = false
    canvasPreview.addEventListener("mousedown", {
        val e = it as MouseEvent
        if (e.button.toInt() == 1) e.preventDefault()
        previewPan = e.button.toInt() == 1 || spaceDown
        previewDrag = Vec2(e.clientX.toDouble(), e.clientY.toDouble())
    })
    canvasPreview.addEventListener("mousemove", {
        val e = it as MouseEvent
        val from = previewDrag ?: return@addEventListener
        val dx = e.clientX - from.x
        val dy = e.clientY - from.y
        previewDrag = Vec2(e.clientX.toDouble(), e.clientY.toDouble())
        if (previewPan) preview.pan(dx, dy) else preview.orbit(dx, dy)
        preview.draw()
    })
    canvasPreview.addEventListener("mouseup", { previewDrag = null })
    canvasPreview.addEventListener("mouseleave", { previewDrag = null })
    canvasPreview.addEventListener("wheel", {
        val e = it as WheelEvent
        e.preventDefault()
        preview.zoom(e.deltaY)
        preview.draw()
    })
    canvasPreview.addEventListener("dblclick", {
        val scene = ExportScene.extract(editor.doc)
        if (!scene.isEmpty) preview.frame(scene)
        preview.draw()
    })
    document.addEventListener("keydown", {
        val e = it as org.w3c.dom.events.KeyboardEvent
        val key = e.key
        // don't steal typing from the panel's own inputs
        val inField = (e.target as? HTMLElement)?.tagName?.lowercase() in setOf("input", "select", "textarea")
        val ctrl = e.ctrlKey || e.metaKey
        if (!inField) {
            when {
                ctrl && (key == "z" || key == "Z") -> {
                    if (e.shiftKey) editor.redo() else editor.undo()
                    e.preventDefault()
                }
                ctrl && (key == "y" || key == "Y") -> {
                    editor.redo()
                    e.preventDefault()
                }
                // The controller first: it owns digits/Enter/Esc/Backspace (a leg's length, or the scalar a
                // tool wants) and the single letters that arm a tool. Only without a modifier — Ctrl+S and
                // friends belong to the browser, and one of them would otherwise switch tools.
                !ctrl && editor.key(key) -> e.preventDefault()
                key == "Delete" || key == "Backspace" -> {
                    if (editor.deleteSelection()) e.preventDefault()
                }
            }
        }
        if (key == "Shift" && !editor.axisLock) {
            editor.axisLock = true
            editor.note("Axis lock: the drag is restricted to one axis (release Shift to free it)")
            repaint()
        }
        if (key == " " && !spaceDown) {
            spaceDown = true
            viewport.panMode = true // the same key means the same thing in both views
            e.preventDefault() // Space would otherwise scroll the page
            editor.note(if (view3d) "Space: drag to move what the view looks at" else "Space: drag to pan (release to resume selecting)")
            repaint()
        }
        if (key == "Alt" && editor.snapEnabled) {
            e.preventDefault() // Alt alone would otherwise reach the browser menu bar
            editor.snapEnabled = false
            editor.note("Alt: clicks place at the cursor and flattened corners are kept (release to resume)")
            repaint()
        }
        // the camera modifier, while the 3D view is drawing: it only *says* so here — the routing decision is
        // read off the key state at the press ([Viewport3.cameraModifier])
        if ((key == "Control" || key == "Meta") && !viewport.cameraModifier) {
            viewport.cameraModifier = true
            // what the modifier gives back on release depends on what the plain drag now does — since the
            // reversal that is selecting and moving geometry under SELECT, not only drawing under a tool
            if (viewport.editing()) {
                editor.note(
                    if (viewport.drawing()) {
                        "Ctrl: drag to orbit the view without leaving the tool (release to draw again)"
                    } else {
                        "Ctrl: drag to orbit the view (release to select and drag on the plane again)"
                    },
                )
            }
            repaint()
        }
    })
    document.addEventListener("keyup", {
        val key = (it as org.w3c.dom.events.KeyboardEvent).key
        if (key == "Shift" && editor.axisLock) {
            editor.axisLock = false
            editor.note("")
            repaint()
        }
        if (key == " " && spaceDown) {
            spaceDown = false
            viewport.panMode = false
            editor.note(if (view3d) viewport.help() else "")
            repaint()
        }
        if (key == "Alt" && !editor.snapEnabled) {
            editor.snapEnabled = true
            editor.note("")
            repaint()
        }
        if ((key == "Control" || key == "Meta") && viewport.cameraModifier) {
            viewport.cameraModifier = false
            // back to the tool, mid-session: an orbit already under way keeps the camera to its release
            if (view3d) editor.note(viewport.help())
            repaint()
        }
    })

    // ---- edit actions: thin adapters over the Editor's own undo/redo/delete ----
    (document.getElementById("e-undo") as HTMLElement).addEventListener("click", { editor.undo() })
    (document.getElementById("e-redo") as HTMLElement).addEventListener("click", { editor.redo() })
    (document.getElementById("e-delete") as HTMLElement).addEventListener("click", { editor.deleteSelection() })

    // ---- selection: bulk visibility and flat groups (OP-16). The DOM only routes; the Editor decides ----
    (document.getElementById("s-hide") as HTMLElement).addEventListener("click", { editor.setSelectionVisible(false) })
    (document.getElementById("s-show") as HTMLElement).addEventListener("click", { editor.setSelectionVisible(true) })
    // Group and Make-tool open the *same* dialog with different defaults (OP-16): the shell only routes
    // clicks into it — the candidates, the defaults and the validation are all [CreateDialog]'s, in
    // commonMain, so they are headlessly tested rather than living in the DOM.
    (document.getElementById("g-add") as HTMLElement).addEventListener("click", { editor.beginCreate(CreateMode.GROUP) })
    (document.getElementById("g-tool") as HTMLElement).addEventListener("click", { editor.beginCreate(CreateMode.TOOL) })
    val createDialog = document.getElementById("create-dialog") as HTMLElement
    createDialog.addEventListener("click", {
        val t = it.target as? HTMLElement ?: return@addEventListener
        val d = editor.createDialog ?: return@addEventListener
        // the typed name lives in the input until something is done with it — read it back first, since
        // confirming is the only moment it matters and the field is not re-rendered while the dialog is up
        (document.getElementById("cd-name") as? HTMLInputElement)?.let { f -> d.name = f.value }
        when {
            t.id == "cd-ok" -> editor.confirmCreate()
            t.id == "cd-cancel" -> editor.cancelCreate()
            // "movable (with frame)" — ticked by default, so confirming creates *and* places (OP-16 step 2)
            t.id == "cd-framed" -> d.framed = (t as HTMLInputElement).checked
            // the checkbox has already flipped itself in the DOM; keep the model in step with it
            t is HTMLInputElement -> t.getAttribute("data-cidx")?.toIntOrNull()?.let { i -> d.toggle(i) }
        }
    })
    // a custom tool's row: click to select it, × to retire it (refused while instances exist — OP-6)
    (document.getElementById("macros-list") as HTMLElement).addEventListener("click", {
        val t = it.target as? HTMLElement ?: return@addEventListener
        val row = t.closest(".trow") ?: return@addEventListener
        val def = editor.doc.macros.firstOrNull { m -> m.id == row.getAttribute("data-mid") } ?: return@addEventListener
        if (t.className.contains("tdrop")) editor.deleteMacro(def) else editor.setTool(def.toolId)
    })
    val groupsList = document.getElementById("groups-list") as HTMLElement
    // Focusing the name field **also picks the group** — the parameter panel's own rule (focusing a value
    // field makes that parameter active without rebuilding the row). The name is the widest part of the row,
    // so without this, making it editable would have taken away the row's main click target: the one that
    // selects a group, and the one that feeds a whole group into an armed geometry slot (OP-16).
    groupsList.addEventListener("focusin", {
        val gid = (it.target as? HTMLInputElement)?.getAttribute("data-gid") ?: return@addEventListener
        editor.doc.groups.firstOrNull { g -> g.id == gid }?.let { g -> editor.clickGroup(g) }
    })
    // a group's name is editable in place (OP-16 × OP-7), and commits like a parameter's: on Enter or blur
    groupsList.addEventListener("change", {
        val t = it.target as? HTMLInputElement ?: return@addEventListener
        if (!t.className.contains("gname")) return@addEventListener
        val g = editor.doc.groups.firstOrNull { g -> g.id == t.getAttribute("data-gid") } ?: return@addEventListener
        t.value = editor.renameGroup(g, t.value) ?: g.name
        repaint()
    })
    groupsList.addEventListener("click", {
        val t = it.target as? HTMLElement ?: return@addEventListener
        // clicking into the name field is on the way to typing, not a pick of the group
        if (t is HTMLInputElement) return@addEventListener
        val row = t.closest(".grow") ?: return@addEventListener
        val g = editor.doc.groups.firstOrNull { g -> g.id == row.getAttribute("data-gid") } ?: return@addEventListener
        when {
            t.className.contains("gvis") -> editor.setGroupVisible(g, !editor.isGroupVisible(g))
            // place / unplace (OP-16 step 2): a placed group carries its own frame, and moving it edits
            // that frame rather than its points
            t.className.contains("gplace") -> if (g.placed) editor.unplaceGroup(g) else editor.placeGroup(g)
            t.className.contains("gdrop") -> editor.ungroup(g)
            // the row selects the group — or **feeds** it to a tool waiting for a geometry slot (OP-16),
            // which is the Editor's decision, not the DOM's
            else -> editor.clickGroup(g)
        }
    })

    // ---- palette (tool selection via delegation) ----
    //
    // The target is an `Element`, not an `HTMLElement`: an icon button's contents are **SVG**, and an
    // `SVGElement` is not an `HTMLElement` — so casting to the latter silently dropped every click that
    // landed on a glyph. (The CSS also makes the glyph transparent to the pointer, which is the belt to
    // this brace; both are cheap and the failure mode was the whole palette going dead.)
    (document.getElementById("palette") as HTMLElement).addEventListener("click", {
        val btn = (it.target as? org.w3c.dom.Element)?.closest("button") ?: return@addEventListener
        btn.getAttribute("data-tool")?.let { id -> editor.setTool(id) }
    })

    // ---- parameters panel ----
    (document.getElementById("p-add") as HTMLElement).addEventListener("click", {
        val name = (document.getElementById("p-name") as HTMLInputElement).value.ifBlank { "p" }
        val v = (document.getElementById("p-value") as HTMLInputElement).value.toDoubleOrNull() ?: 0.0
        val unit = (document.getElementById("p-unit") as HTMLSelectElement).value
        val q =
            when (unit) {
                "deg" -> Quantity.deg(v)
                "num" -> Quantity.number(v)
                else -> Quantity.mm(v)
            }
        editor.activeScalar = editor.doc.newParameter(name, q)
        editor.checkpoint() // panel edits commit through the same seam as canvas gestures
        repaint()
    })
    val paramsList = document.getElementById("params-list") as HTMLElement
    // select active parameter by clicking a row — but NOT when clicking the value field
    // (that would repaint and destroy the input, stealing focus)
    paramsList.addEventListener("click", {
        val target = it.target as? HTMLElement ?: return@addEventListener
        if (target is HTMLInputElement) return@addEventListener
        val row = target.closest(".prow") ?: return@addEventListener
        // one entry point: clicking the *active* row again switches the pick off, which is the Editor's
        // decision (a defaulted scalar slot would otherwise be shadowed forever) — see Editor.clickScalar
        editor.clickScalar(editor.doc.scalars.firstOrNull { s -> s.id == row.getAttribute("data-sid") })
        repaint()
    })
    // focusing a value field selects that parameter without rebuilding (keeps the caret)
    paramsList.addEventListener("focusin", {
        val sid = (it.target as? HTMLInputElement)?.getAttribute("data-sid") ?: return@addEventListener
        editor.activeScalar = editor.doc.scalars.firstOrNull { s -> s.id == sid }
        val rows = paramsList.querySelectorAll(".prow")
        for (i in 0 until rows.length) {
            val r = rows.item(i) as HTMLElement
            r.className = if (r.getAttribute("data-sid") == sid) "prow active" else "prow"
        }
    })
    // A spinner tick (or a keystroke) in a value field writes the value **live and uncommitted**: the
    // geometry follows every tick, and the undo step is taken where the browser says the value changed
    // (see [Editor.setParameter]). Nothing else is routed here — a name is renamed on commit only, since
    // renaming per keystroke would uniquify half-typed words and fill the undo stack with them.
    paramsList.addEventListener("input", {
        val t = it.target as? HTMLInputElement ?: return@addEventListener
        if (!t.className.contains("pval")) return@addEventListener
        val entry = editor.doc.scalars.firstOrNull { s -> s.id == t.getAttribute("data-sid") } ?: return@addEventListener
        // an empty or half-typed field ("-", "1e") writes nothing and waits
        val v = t.value.toDoubleOrNull() ?: return@addEventListener
        editor.setParameter(entry, v, commit = false)
    })
    // edit a parameter value (pval), rename it (pname) or wire it to another scalar (pbind), on commit
    paramsList.addEventListener("change", {
        val t = it.target as? HTMLElement ?: return@addEventListener
        val entry = editor.doc.scalars.firstOrNull { s -> s.id == t.getAttribute("data-sid") } ?: return@addEventListener
        when {
            t is HTMLInputElement && t.className.contains("pval") -> {
                val v = t.value.toDoubleOrNull() ?: return@addEventListener
                editor.setParameter(entry, v) // commits: one undo step per committed change
                repaint()
            }
            // renaming is the panel's own operation (OP-7): the field shows the name it actually took,
            // which a clash or a blank field makes different from what was typed
            t is HTMLInputElement && t.className.contains("pname") -> {
                t.value = editor.renameParameter(entry, t.value) ?: entry.name
                repaint()
            }
            t is HTMLSelectElement && t.className.contains("pbind") -> {
                val target = editor.doc.scalars.firstOrNull { s -> s.id == t.value }
                if (target == null) {
                    editor.doc.unwireParameter(entry)
                } else if (!editor.doc.wireParameter(entry, target)) {
                    editor.note("Can't wire ${entry.name}: type mismatch or would create a cycle")
                }
                editor.checkpoint()
                repaint()
            }
        }
    })

    // inspector: typing a value writes exactly what dragging the selected handle writes (OP-13)
    val inspector = document.getElementById("inspector") as HTMLElement
    inspector.addEventListener("change", {
        val t = it.target as? HTMLInputElement ?: return@addEventListener
        // the element's own name (OP-7 one level up) — the field shows the name it actually took, which a
        // clash or a blank field makes different from what was typed, exactly as a parameter's does
        if (t.id == "insp-name") {
            editor.selection?.let { el -> t.value = editor.nameElement(el, t.value) ?: editor.doc.userNameOf(el) ?: "" }
            repaint()
            return@addEventListener
        }
        // the solid's **material** (appearance Tier 1): three fields, one record, one recorded step. Read
        // together rather than one at a time, because they are one value — and the Editor is what decides
        // what it took, exactly as it does for a name.
        if (t.id == "insp-color" || t.id == "insp-rough" || t.id == "insp-metal") {
            val el = editor.selection ?: return@addEventListener
            val was = editor.doc.materialOf(el)

            fun field(
                id: String,
                fallback: Double,
            ): Double = (document.getElementById(id) as? HTMLInputElement)?.value?.toDoubleOrNull() ?: fallback
            editor.setMaterial(
                el,
                Appearance(
                    color = (document.getElementById("insp-color") as? HTMLInputElement)?.value ?: was.color,
                    roughness = field("insp-rough", was.roughness),
                    metallic = field("insp-metal", was.metallic),
                ),
            )
            repaint()
            return@addEventListener
        }
        val idx = t.getAttribute("data-fidx")?.toIntOrNull() ?: return@addEventListener
        val v = t.value.toDoubleOrNull() ?: return@addEventListener
        if (!editor.writeSelectionField(idx, v)) editor.note("That value is determined by the construction and can't be set here")
        repaint()
    })
    // Hovering a name in *built from* / *used by* points the canvas at that element, and clicking it goes
    // there. The set is on `mouseover` per chip and the clear is on `mouseleave` of the whole panel — never
    // per chip — because a repaint replaces these nodes under the pointer, and a per-chip clear would then
    // fight the very highlight it had just asked for.
    inspector.addEventListener("mouseover", {
        val chip = (it.target as? HTMLElement)?.closest(".dep")
        val eid = chip?.getAttribute("data-eid")
        editor.setSpotlight(editor.doc.elements.firstOrNull { e -> e.id == eid })
    })
    inspector.addEventListener("mouseleave", { editor.setSpotlight(null) })
    inspector.addEventListener("click", {
        val eid = (it.target as? HTMLElement)?.closest(".dep")?.getAttribute("data-eid") ?: return@addEventListener
        editor.doc.elements.firstOrNull { e -> e.id == eid }?.let { el -> editor.selectElement(el) }
    })

    // ---- drawing file: the document as a construction script (DocumentFormat) ----
    val fileNote = document.getElementById("file-note") as HTMLElement

    fun note(
        message: String,
        error: Boolean = false,
    ) {
        fileNote.textContent = message
        fileNote.className = if (error) "err" else ""
    }

    fun loadScript(text: String): Boolean {
        var ok = false
        try {
            val fresh = DocumentFormat.load(text)
            editor.replaceDocument(fresh)
            note("Loaded ${fresh.elements.size} element(s)")
            ok = true
        } catch (e: Throwable) {
            note(e.message ?: "could not load that file", error = true)
        }
        repaint()
        return ok
    }

    // ---- the drawing's name, and Save (OP-18: the name is shell state, never part of the file) ----
    //
    // The field *is* the state: every save reads it, so nothing has to be kept in step with it and a value
    // typed without committing still saves under the name the user can see. [DocumentName] (commonMain,
    // unit-tested) owns the arithmetic — the default, what a typed name means, what a picked file is called.
    val nameField = document.getElementById("f-name") as HTMLInputElement
    nameField.value = DocumentName.DEFAULT

    fun docName(): String = DocumentName.normalize(nameField.value)

    fun setDocName(raw: String) {
        nameField.value = DocumentName.normalize(raw)
    }
    // normalise what was typed as soon as it is committed, so the field never shows a name Save would change
    nameField.addEventListener("change", { setDocName(nameField.value) })

    // The **File System Access API**, where it exists: the first Save asks for a file, later Saves write back
    // to that same handle — a real Save rather than a pile of numbered downloads. Feature-detected, no
    // library, and every failure path ends in the download that always worked.
    //
    // Excluded over `file:`, deliberately: a page with an opaque origin has nothing to remember a handle
    // *for*, and Chrome refuses the picker there — which is also the E2E's environment, so the test exercises
    // the fallback rather than hanging on a native dialog.
    val fsApi: Boolean =
        js("typeof window.showSaveFilePicker === 'function' && window.location.protocol !== 'file:'") as Boolean

    /** The file a real Save writes back to, once there is one. Null means "ask". */
    var fileHandle: dynamic = null
    val anchor = document.getElementById("f-anchor") as org.w3c.dom.HTMLAnchorElement

    /** The fallback that always works: hand the script to the browser as a download. */
    fun downloadScript(
        text: String,
        why: String = "",
    ) {
        val blob = Blob(arrayOf(text), BlobPropertyBag(type = "text/plain"))
        val url = URL.createObjectURL(blob)
        anchor.href = url
        anchor.download = DocumentName.fileName(docName())
        anchor.click()
        URL.revokeObjectURL(url)
        note("Saved ${anchor.download}$why")
    }

    /** Write [text] through [handle]; any refusal (a revoked permission, a vanished file) falls back. */
    fun saveViaHandle(
        handle: dynamic,
        text: String,
    ) {
        fun failed() {
            // the handle is no longer good for anything, so stop pretending it is
            fileHandle = null
            downloadScript(text, " (the file could not be written, so it was downloaded)")
        }
        try {
            val created: dynamic = handle.createWritable()
            created.then(
                { w: dynamic ->
                    val written: dynamic = w.write(text)
                    written.then(
                        { _: dynamic ->
                            val closed: dynamic = w.close()
                            closed.then(
                                { _: dynamic ->
                                    // the file the bytes went into names the drawing from now on
                                    setDocName(DocumentName.fromFileName(handle.name as String))
                                    note("Saved ${DocumentName.fileName(docName())}")
                                },
                                { _: dynamic -> failed() },
                            )
                        },
                        { _: dynamic -> failed() },
                    )
                },
                { _: dynamic -> failed() },
            )
        } catch (e: Throwable) {
            failed()
        }
    }

    /** The `.cit` file type, as both pickers want it described. */
    fun citTypes(): dynamic {
        val accept: dynamic = js("({})")
        accept["text/plain"] = arrayOf(DocumentName.EXTENSION)
        val type: dynamic = js("({})")
        type.description = "ConstructIt drawing"
        type.accept = accept
        return arrayOf(type)
    }

    /**
     * Save. [askForFile] is *Save as…*: it always asks for a new handle, while a plain Save reuses the one
     * it has. Without the API — or with the prompt refused — this is the download flow, unchanged.
     */
    fun saveDrawing(askForFile: Boolean) {
        // OP-27's backstop: a document holding an element no step created is refused by name rather than
        // written without it — the refusal is the format's, said here and nowhere else
        val outcome = DocumentFormat.saveFile(editor.doc)
        val text =
            outcome.text ?: run {
                note(outcome.refusal ?: "Not saved", error = true)
                return
            }
        val handle = fileHandle
        if (!fsApi) {
            downloadScript(text)
            return
        }
        // the handle is reused only while it still *is* the drawing's name: the field says what the drawing is
        // called, so renaming it and pressing Save must produce a file of that name rather than quietly
        // overwriting the old one — which is Save-as by another route, and asks
        val sameName = handle != null && DocumentName.fromFileName(handle.name as String) == docName()
        if (handle != null && sameName && !askForFile) {
            saveViaHandle(handle, text)
            return
        }
        try {
            val opts: dynamic = js("({})")
            opts.suggestedName = DocumentName.fileName(docName())
            opts.types = citTypes()
            val asked: dynamic = window.asDynamic().showSaveFilePicker(opts)
            asked.then(
                { h: dynamic ->
                    fileHandle = h
                    saveViaHandle(h, text)
                },
                { e: dynamic ->
                    // A *cancelled* picker is not a failure, and downloading behind the user's back would be
                    // the wrong answer to "not that name". Anything else — a refused prompt, no gesture —
                    // is the API being unavailable in practice, so it falls back.
                    if ((e.name as? String) == "AbortError") note("Save cancelled") else downloadScript(text, " (the file picker was refused)")
                },
            )
        } catch (e: Throwable) {
            downloadScript(text, " (the file picker was refused)")
        }
    }

    /** Open through the API, keeping the handle so later Saves write back to it. False when unavailable. */
    fun openWithPicker(fallback: () -> Unit): Boolean {
        if (!fsApi || js("typeof window.showOpenFilePicker !== 'function'") as Boolean) return false
        try {
            val opts: dynamic = js("({})")
            opts.types = citTypes()
            opts.multiple = false
            val asked: dynamic = window.asDynamic().showOpenFilePicker(opts)
            asked.then(
                { handles: dynamic ->
                    val h: dynamic = handles[0]
                    val file: dynamic = h.getFile()
                    file.then(
                        { f: dynamic ->
                            val read: dynamic = f.text()
                            read.then(
                                { t: dynamic ->
                                    if (loadScript(t as String)) {
                                        // it came from a handle, so Save goes straight back into it
                                        fileHandle = h
                                        setDocName(DocumentName.fromFileName(h.name as String))
                                    }
                                },
                                { _: dynamic -> note("could not read that file", error = true) },
                            )
                        },
                        { _: dynamic -> note("could not read that file", error = true) },
                    )
                },
                { e: dynamic -> if ((e.name as? String) == "AbortError") note("Open cancelled") else fallback() },
            )
        } catch (e: Throwable) {
            return false
        }
        return true
    }

    (document.getElementById("v-dim") as HTMLInputElement).addEventListener("change", { e ->
        editor.dimScaffolding = (e.target as HTMLInputElement).checked
        repaint()
    })
    // which side of the centerline a new wall's thickness sits on — a thick path's justification (OP-21)
    (document.getElementById("v-just") as HTMLSelectElement).addEventListener("change", { e ->
        val picked = (e.target as HTMLSelectElement).value
        editor.justification = Justification.entries.first { it.name.lowercase() == picked }
        repaint()
    })
    // the structural count a polygon / array tool builds with (see Editor.count). A tool *option*, like
    // the wall justification and the active parameter — there is no slot to click it into.
    val countField = document.getElementById("t-count") as HTMLInputElement
    countField.addEventListener("change", {
        editor.count = countField.value.toIntOrNull() ?: editor.count
        countField.value = editor.count.toString() // the editor clamps it; show what it actually took
        repaint()
    })
    // ...and the one place that count is *not* only a tool option: with a pattern member selected it
    // re-stamps that pattern (OP-23) — the count of a pattern is editable after the fact, because the
    // pattern stores the rule and every gesture riding it can be re-run.
    (document.getElementById("t-restamp") as HTMLElement).addEventListener("click", {
        editor.selectedPattern()?.let { editor.setPatternCount(it, countField.value.toIntOrNull() ?: it.count) }
        repaint()
    })
    (document.getElementById("f-copy") as HTMLElement).addEventListener("click", {
        val copied = DocumentFormat.saveFile(editor.doc)
        val text = copied.text
        if (text == null) {
            note(copied.refusal ?: "Not copied", error = true)
            return@addEventListener
        }
        window.navigator.clipboard.writeText(text).then(
            { note("Copied ${text.lines().size - 1} step(s) to the clipboard") },
            { note("Clipboard refused; use Save instead", error = true) },
        )
    })

    /**
     * **Export**: bytes out, named after the drawing, downloaded.
     *
     * The shell's entire contribution is the last five lines — a blob and a click. *Which* bodies go into the
     * file, what it is called, what the status line says and every refusal are [Exports]'s, in `commonMain`,
     * which is why the whole flow is covered headlessly and this cannot drift from what the tests assert.
     *
     * Deliberately the download route and not the File System Access API that Save uses: an export is a
     * *derived artefact*, not the drawing — there is nothing to write back to, and no handle worth keeping.
     */
    fun exportBytes(format: ExportFormat) {
        val result = Exports.export(editor.doc, docName(), format)
        val bytes = result.bytes
        if (bytes == null) {
            note(result.message, error = true)
            return
        }
        // Kotlin/JS holds a ByteArray as an `Int8Array`, so the bytes are handed to the Blob as a *view* over
        // the same buffer — a copy of a 50 MB mesh here would be a stall nobody could explain.
        val signed = bytes.unsafeCast<Int8Array>()
        val view = Uint8Array(signed.buffer, signed.byteOffset, signed.length)
        val blob = Blob(arrayOf(view), BlobPropertyBag(type = format.mimeType))
        val url = URL.createObjectURL(blob)
        anchor.href = url
        anchor.download = result.fileName
        anchor.click()
        URL.revokeObjectURL(url)
        note(result.message)
    }
    (document.getElementById("x-glb") as HTMLElement).addEventListener("click", { exportBytes(ExportFormat.GLB) })
    (document.getElementById("x-3mf") as HTMLElement).addEventListener("click", { exportBytes(ExportFormat.THREE_MF) })
    (document.getElementById("x-stl") as HTMLElement).addEventListener("click", { exportBytes(ExportFormat.STL) })
    (document.getElementById("x-jt") as HTMLElement).addEventListener("click", { exportBytes(ExportFormat.JT) })
    /**
     * **Import**: bytes in, reference bodies in the drawing — the mirror of [exportBytes], and just as thin.
     *
     * The shell reads a file and hands over the bytes; which bodies come in, what they are called, what the
     * status line says and every refusal are `Imports`', in `commonMain`, so the whole flow is covered
     * headlessly and this cannot drift from what the tests assert. One call is one checkpoint
     * (`Editor.importFile`), so one undo removes everything a file brought.
     *
     * Deliberately the plain file input rather than the File System Access API that *Open* uses: an import
     * is read once and becomes part of the drawing, so there is no handle worth keeping — the same reasoning
     * that puts an export on the download route.
     */
    val importPicker = document.getElementById("x-import-file") as HTMLInputElement
    (document.getElementById("x-import") as HTMLElement).addEventListener("click", { importPicker.click() })
    importPicker.addEventListener("change", {
        val file = importPicker.files?.item(0)
        if (file != null) {
            val reader = FileReader()
            reader.onload = { _ ->
                val buffer = reader.result.unsafeCast<ArrayBuffer>()
                val result = editor.importFile(Int8Array(buffer).unsafeCast<ByteArray>(), file.name)
                note(result.message, error = !result.ok)
                repaint()
            }
            reader.onerror = { _ -> note("could not read that file", error = true) }
            reader.readAsArrayBuffer(file)
            // so picking the same file twice in a row fires `change` the second time too
            importPicker.value = ""
        }
    })

    (document.getElementById("f-download") as HTMLElement).addEventListener("click", { saveDrawing(askForFile = false) })
    (document.getElementById("f-saveas") as HTMLElement).addEventListener("click", { saveDrawing(askForFile = true) })
    val filePicker = document.getElementById("f-file") as HTMLInputElement
    (document.getElementById("f-load") as HTMLElement).addEventListener("click", {
        // the API's Open when it is there (its handle is what makes the *next* Save a real one), else the
        // ordinary file input, which has no handle to give
        if (!openWithPicker { filePicker.click() }) filePicker.click()
    })
    filePicker.addEventListener("change", {
        val file = filePicker.files?.item(0)
        if (file != null) {
            val reader = FileReader()
            reader.onload = { _ ->
                if (loadScript(reader.result as String)) {
                    // a file input yields no writable handle, so the next Save has to ask for one — and the
                    // drawing takes the picked file's name either way
                    fileHandle = null
                    setDocName(DocumentName.fromFileName(file.name))
                }
            }
            reader.readAsText(file)
        }
    })

    // the element tree selects by name — the way to reach an element a click cannot, such as the area
    // under a solid's footprint hint (they occupy exactly the same place; see Editor.selectElement)
    (document.getElementById("tree") as HTMLElement).addEventListener("click", {
        val row = (it.target as? HTMLElement)?.closest(".item") ?: return@addEventListener
        val el = editor.doc.listedElements().firstOrNull { e -> e.id == row.getAttribute("data-eid") } ?: return@addEventListener
        editor.selectElement(el)
    })

    // a measurement can drive a new construction: click it to make it the active scalar (OP-4), and click it
    // again to switch that pick off — the same one route as a parameter row
    (document.getElementById("measure-list") as HTMLElement).addEventListener("click", {
        val row = (it.target as? HTMLElement)?.closest(".mrow") ?: return@addEventListener
        editor.clickScalar(editor.doc.scalars.firstOrNull { s -> s.id == row.getAttribute("data-sid") })
        repaint()
    })

    window.addEventListener("resize", { repaint() })

    repaint()

    // The general boolean engine (OP-9) is WASM, so it becomes usable *after* the first paint while node
    // evaluation stays synchronous. Nothing waits for it: until it is up, a boolean between solids with no
    // common axis is an ordinary invalid node carrying that as its reason, and this repaint is the whole
    // auto-heal (OP-3) — the graph is re-evaluated, and those solids appear.
    MeshBool.initialize { ready ->
        console.log("[MeshBool] ${if (ready) "ready" else "unavailable"} — ${MeshBool.status}")
        repaint()
    }
}

/**
 * Which tools the palette currently shows. The registry is no longer static — a document's macros are
 * tools too (OP-6) — so the palette is rebuilt when *that set* changes and only then: rebuilding it on
 * every repaint would throw away the DOM under a click for no reason.
 */
private var paletteShows: String? = null

private fun buildPalette(editor: Editor) {
    val tools = editor.doc.toolDefs
    val signature = tools.joinToString(",") { it.id }
    if (paletteShows == signature) return
    paletteShows = signature
    val palette = document.getElementById("palette") as HTMLElement
    val sb = StringBuilder()

    // the tool's key, on the button and in its tooltip — a shortcut nobody can see is a shortcut nobody uses.
    // It rides the icon buttons too (a corner badge), because discoverability was never the label's job.
    fun keyTag(key: Char?): String = if (key == null) "" else "<span class=\"tkey\">$key</span>"

    /**
     * One palette button. A tool with a glyph ([ToolDef.icon]) gets an icon button whose *tooltip* carries
     * the words — label, help and key — and a tool without one keeps the text row it always had. Both keep
     * `id="tool-<id>"` and `data-tool`, which is what every flow and every E2E selector addresses.
     */
    fun button(
        id: String,
        label: String,
        help: String,
        key: Char?,
        active: Boolean = false,
    ): String {
        val hint = (if (help.isEmpty()) label else "$label — $help") + if (key == null) "" else " (shortcut $key)"
        val cls = "tool" + (if (active) " active" else "") + if (Tools.iconOf(id) != null) " icon" else ""
        val body = Tools.iconOf(id)?.let { Icons.wrap(it) } ?: label
        return "<button class=\"$cls\" id=\"tool-$id\" data-tool=\"$id\" title=\"$hint\">$body${keyTag(key)}</button>"
    }
    sb.append("<div class=\"icons\">")
    sb.append(button(Tools.SELECT, "Select / Drag", "Drag a point to reshape the construction", Tools.SELECT_KEY, active = true))
    sb.append("</div>")
    for (cat in constructit.editor.ToolCategory.values()) {
        val inCat = tools.filter { it.category == cat }
        // the custom category exists only once the document defines a macro
        if (inCat.isEmpty()) continue
        sb.append("<div class=\"cat\">${cat.name.lowercase()}</div>")
        // icons first, wrapped into a grid; then whatever has no glyph, as full-width text rows. The order
        // within each half is the table's, so a tool never moves about between builds.
        val withIcon = inCat.filter { it.icon != null }
        if (withIcon.isNotEmpty()) {
            sb.append("<div class=\"icons\">")
            for (t in withIcon) sb.append(button(t.id, t.label, t.help, t.shortcut))
            sb.append("</div>")
        }
        for (t in inCat.filter { it.icon == null }) sb.append(button(t.id, t.label, t.help, t.shortcut))
    }
    palette.innerHTML = sb.toString()
}

/** The dialog currently rendered, so typing in its name field is never interrupted by a repaint. */
private var dialogShown: CreateDialog? = null

/**
 * Render the shared create dialog (OP-16). Only when it *changes*: while it is open the DOM is the live
 * copy of the checkbox state and of the half-typed name, and re-rendering would discard both.
 */
private fun renderCreateDialog(editor: Editor) {
    val host = document.getElementById("create-dialog") as HTMLElement
    val d = editor.createDialog
    if (d === dialogShown) return
    dialogShown = d
    if (d == null) {
        host.innerHTML = ""
        return
    }
    val rows =
        d.candidates.withIndex().joinToString("") { (i, c) ->
            val checked = if (c.checked) " checked" else ""
            val what = if (c.isPoint) "point" else "parameter"
            "<label class=\"cdrow\"><input type=\"checkbox\" data-cidx=\"$i\"$checked>" +
                "<span>${c.label}</span><span class=\"tports\">$what</span></label>"
        }
    // the frame tick (OP-16 step 2), ticked by default: a group is nearly always a movable *part*, and
    // unticking is the other intent — a named set, e.g. the original an array copies frame-free
    val framed =
        if (d.mode != CreateMode.GROUP) {
            ""
        } else {
            "<label class=\"cdrow\" title=\"${d.flatMeaning}\"><input type=\"checkbox\" id=\"cd-framed\"" +
                "${if (d.framed) " checked" else ""}><span>${d.framedLabel}</span>" +
                "<span class=\"tports\">${d.framedMeaning}</span></label>"
        }
    host.innerHTML =
        "<div class=\"cdtitle\">${d.title} — ${d.members.size} element(s)</div>" +
        "<div class=\"cdhelp\">${d.help}</div>" +
        "<input id=\"cd-name\" type=\"text\" placeholder=\"name\" value=\"${d.name}\">" +
        framed +
        rows +
        "<div class=\"cdbuttons\"><button id=\"cd-ok\">Create</button><button id=\"cd-cancel\">Cancel</button></div>"
}

private fun renderPanel(
    editor: Editor,
    view3d: Boolean,
    viewport: Viewport3,
) {
    // In the 3D view the gestures are the viewport's to describe — which tool is drawing on which plane, or
    // that a plain drag now selects and moves there while Ctrl orbits — so the status line asks it.
    (document.getElementById("status") as HTMLElement).textContent =
        when {
            editor.statusHint.isNotEmpty() -> editor.statusHint
            view3d -> viewport.help()
            else -> editor.currentHelp()
        }

    // the sketch-space indicator (OP-17): every space, the active one selected. Rebuilt from the document,
    // so a space created by a click — or removed by an undo — shows up here without a second notification.
    val spaceSel = document.getElementById("v-space") as HTMLSelectElement
    val spaceOptions =
        editor.doc.spaces.joinToString("") { s ->
            // the label is the document's answer (a face of what, a datum at what angle on what line) — the
            // shell only renders it, the same discipline the elements list follows
            val label = editor.doc.spaceLabel(s)
            "<option value=\"${s.name}\"${if (s.name == editor.activeSpace.name) " selected" else ""}>$label</option>"
        }
    if (spaceSel.innerHTML != spaceOptions) spaceSel.innerHTML = spaceOptions
    spaceSel.value = editor.activeSpace.name

    // edit buttons mirror the editor's stacks and selection
    (document.getElementById("e-undo") as org.w3c.dom.HTMLButtonElement).disabled = !editor.canUndo
    (document.getElementById("e-redo") as org.w3c.dom.HTMLButtonElement).disabled = !editor.canRedo
    (document.getElementById("e-delete") as org.w3c.dom.HTMLButtonElement).disabled = editor.selection == null

    // the pattern the selection addresses (OP-23), and whether the count field can re-stamp it
    val pattern = editor.selectedPattern()
    (document.getElementById("t-restamp") as org.w3c.dom.HTMLButtonElement).disabled = pattern == null
    (document.getElementById("t-pattern") as HTMLElement).textContent =
        pattern?.let { "Pattern ${it.name}: ${it.count} instances, ${it.gestures.size} gesture(s) riding it" } ?: ""

    // the palette carries the document's own macros beside the built-in tools (OP-6), so it follows the
    // document rather than being built once at startup
    buildPalette(editor)
    renderCreateDialog(editor)

    // active tool highlight — the icon class is part of what the button *is*, so it is preserved
    val toolNodes = document.querySelectorAll(".tool")
    for (i in 0 until toolNodes.length) {
        val el = toolNodes.item(i) as HTMLElement
        val icon = if (el.className.contains("icon")) " icon" else ""
        el.className = (if (el.getAttribute("data-tool") == editor.toolId) "tool active" else "tool") + icon
    }

    val ev = Evaluator()

    // selection inspector: one row per handle field — the numeric form of the selection's drag. Left alone
    // while its *name* field has the keyboard, for the reason the parameter list is: replacing the DOM under
    // a half-typed name discards it (the numeric fields keep their old behaviour, committed on change).
    val insp = document.getElementById("inspector") as HTMLElement
    val fields = editor.selectionFields()
    val namingElement = (document.activeElement as? HTMLElement)?.id == "insp-name"
    if (!namingElement) {
        insp.innerHTML =
            // an opening's jamb (OP-21) is a selection that owns no element, so it is asked about separately —
            // its fields are the interval's own parameters
            if (editor.selection == null && editor.selectedJamb == null) {
                "<div class=\"hint\">Click a corner, a leg or an opening's jamb to read and set its values.</div>"
            } else if (editor.selectionCount > 1 && fields.isEmpty()) {
                // a *placed* group is the exception: several elements are selected, but they have one handle
                // between them — the frame (OP-16 step 2) — so its fields are shown rather than nothing
                // fields address one handle (OP-13), so a multi-selection shows none — but it is what
                // delete, hide and Group act on, hence the count
                "<div class=\"selname\">${editor.selectionLabel()}</div>" +
                    "<div class=\"hint\">Delete, Hide and Group act on all of them. Click one element for its values.</div>"
            } else {
                "<div class=\"selname\">${editor.selectionLabel()}</div>" +
                    elementNameRow(editor) +
                    materialRow(editor) +
                    fields.withIndex().joinToString("") { (i, f) ->
                        val q = f.read(ev)
                        val shown = q?.let { displayValue(it) } ?: ""
                        val disabled = if (f.writable) "" else " disabled"
                        "<div class=\"frow\">" +
                            "<span class=\"flabel\">${f.label}</span>" +
                            "<input class=\"fval\" data-fidx=\"$i\" value=\"$shown\"$disabled>" +
                            "<span class=\"funit\">${unitLabel(f.dim)}</span>" +
                            "</div>"
                    } +
                    (if (fields.isEmpty()) "<div class=\"hint\">No editable values — this element is fully derived.</div>" else "") +
                    dependencyRows(editor)
            }
    }

    // groups: a named set (OP-16) — click to select its members, ◉ to hide/show, ⌖ to place/unplace
    // (a placed group gets its own frame: dragging it then moves the frame), × to dissolve
    val glist = document.getElementById("groups-list") as HTMLElement
    val editingGroups = (document.activeElement as? HTMLElement)?.closest("#groups-list") != null
    if (!editingGroups) {
        glist.innerHTML =
            editor.doc.groups.joinToString("") { g ->
                // the name is editable exactly where the file can carry it (OP-16 × OP-7), as a parameter's is
                val name =
                    if (editor.doc.canRenameGroup(g)) {
                        "<input class=\"gname\" data-gid=\"${g.id}\" value=\"${g.name}\" title=\"Rename — Enter to commit\">"
                    } else {
                        "<span class=\"gname\">${g.name}</span>"
                    }
                "<div class=\"grow\" data-gid=\"${g.id}\">" +
                    name +
                    "<span class=\"gcount\">${editor.doc.groupMembers(g).size}</span>" +
                    "<button class=\"gplace\" title=\"${if (g.placed) "Unplace — its points become free again where they are" else "Place — give it a frame; dragging then moves the whole group"}\">${if (g.placed) "⊗" else "⌖"}</button>" +
                    "<button class=\"gvis\" title=\"Hide or show every member\">${if (editor.isGroupVisible(g)) "◉" else "○"}</button>" +
                    "<button class=\"gdrop\" title=\"Dissolve the group — its elements stay\">×</button>" +
                    "</div>"
            }
    }

    // custom tools (OP-6): what the document itself defines — click a row to arm the tool, × to retire it
    val mlistTools = document.getElementById("macros-list") as HTMLElement
    mlistTools.innerHTML =
        editor.doc.macros.joinToString("") { m ->
            val ports =
                "${m.pointInputs.size} pt" +
                    if (m.scalarInputs.isEmpty()) "" else " + ${m.scalarInputs.joinToString(", ") { it.name }}"
            val instances = editor.doc.instancesOf(m).size
            "<div class=\"trow\" data-mid=\"${m.id}\" title=\"Editing the original updates all $instances instance(s)\">" +
                "<span class=\"tname\">${m.name}</span>" +
                "<span class=\"tports\">$ports · $instances×</span>" +
                "<button class=\"tdrop\" title=\"Retire this tool — the construction it was made from stays\">×</button>" +
                "</div>"
        }

    // parameters (editable). While a row of this list has the keyboard the DOM is left exactly as it is:
    // replacing it under a live spinner or a half-typed name would destroy the focus — and with it the
    // next tick. Everything else still repaints, so the canvas follows every tick (Editor.setParameter).
    val plist = document.getElementById("params-list") as HTMLElement
    val editingParams = (document.activeElement as? HTMLElement)?.closest("#params-list") != null
    if (!editingParams) {
        plist.innerHTML =
            editor.doc.scalars.filter { it.editable }.joinToString("") { s ->
                val active = if (s === editor.activeScalar) " active" else ""
                val q = ev.scalar(s.ref)
                val boundId = editor.doc.boundEntry(s)?.id ?: ""
                // wire options: other scalars of the same dimension
                val opts = StringBuilder("<option value=\"\">free</option>")
                for (t in editor.doc.scalars) {
                    if (t === s) continue
                    val td = (ev.eval(t.ref.node) as? constructit.core.EvalResult.Ok)?.let { (it.value as? constructit.core.ScalarValue)?.q?.dim }
                    if (td == null || td != q.dim) continue
                    opts.append("<option value=\"${t.id}\"${if (t.id == boundId) " selected" else ""}>=${t.name}</option>")
                }
                val disabled = if (editor.doc.isBound(s)) " disabled" else ""
                // the name is editable exactly where the file can carry it (OP-7); the others say why not
                val name =
                    if (editor.doc.canRenameParameter(s)) {
                        "<input class=\"pname\" data-sid=\"${s.id}\" value=\"${s.name}\" title=\"Rename — Enter to commit\">"
                    } else {
                        "<span class=\"pname\" title=\"Named by the step that created it, so it cannot be renamed\">${s.name}</span>"
                    }
                "<div class=\"prow$active\" data-sid=\"${s.id}\">" +
                    name +
                    // a native number field: the browser's own up/down arrows and arrow keys nudge it, and
                    // every tick is a live write (OP-13 — typing and nudging are the same operation)
                    "<input class=\"pval\" type=\"number\" step=\"${stepFor(q.dim)}\" data-sid=\"${s.id}\" value=\"${displayValue(q)}\"$disabled>" +
                    "<span class=\"punit\">${unitLabel(q.dim)}</span>" +
                    "<select class=\"pbind\" data-sid=\"${s.id}\">$opts</select>" +
                    "</div>"
            }
    }

    // measurements (read-only)
    val mlist = document.getElementById("measure-list") as HTMLElement
    mlist.innerHTML =
        editor.doc.scalars.filter { !it.editable }.joinToString("") { s ->
            val active = if (s === editor.activeScalar) " active" else ""
            val value = (ev.eval(s.ref.node) as? constructit.core.EvalResult.Ok)?.let { Format.quantity((it.value as constructit.core.ScalarValue).q) } ?: "—"
            "<div class=\"mrow$active\" data-sid=\"${s.id}\"><span>${s.name}</span><span class=\"mval\">$value</span></div>"
        }

    // element tree
    val tree = document.getElementById("tree") as HTMLElement
    tree.innerHTML =
        // the *active space's* elements plus the solids, which live in none — the rule is
        // [Document.listedIn]'s, so the shell only renders it (OP-17, GitHub issue #2)
        editor.doc.listedElements().joinToString("") {
            val active = if (editor.isSelected(it)) " active" else ""
            // a listed row the canvas is not drawing says where it lives (OP-17): that is a solid, shown in
            // the 3D viewport rather than in this space's plan
            val where = if (it.space == editor.activeSpace.name) "" else " · ${it.space}"
            // `data-eid` stays the internal id — it is how a click finds the element again — while what the
            // row *shows* is the drawing's one name for it, the file's (OP-18, [Document.nameOf]), plus the
            // user's own label in front of it where there is one ([Document.displayName])
            "<div class=\"item$active\" data-eid=\"${it.id}\">${it.kind.name.lowercase()}$where<span class=\"eid\">${editor.doc.displayName(it)}</span></div>"
        }
}

/**
 * The selected element's **own name** (OP-7 one level up): a text field, blank until it has one, and the
 * script name beside it as a reminder of what it is really called ([Document.displayName]).
 */
private fun elementNameRow(editor: Editor): String {
    val el = editor.selection?.takeIf { editor.selectionCount == 1 } ?: return ""
    if (!editor.doc.canNameElement(el)) return ""
    val given = editor.doc.userNameOf(el) ?: ""
    return "<div class=\"frow\"><span class=\"flabel\">name</span>" +
        "<input id=\"insp-name\" class=\"fname\" value=\"$given\" placeholder=\"${editor.doc.nameOf(el)}\" " +
        "title=\"A name of your own for this element. The script name stays what the file and every message call it; " +
        "clear the field to drop the name.\"></div>"
}

/**
 * The selected solid's **material** — appearance Tier 1, in one row: a colour, a roughness and a metalness.
 *
 * Only for a solid, and only where the file can carry it (`Document.canSetMaterial`), which is the rule the
 * name row above follows for the same reason. Three inputs rather than a dialog because that is the whole of
 * Tier 1: five numbers is what makes a GLB render honestly in any viewer, and anything more — a material
 * library, a texture, a per-face assignment — is a later tier with its own mechanism.
 */
private fun materialRow(editor: Editor): String {
    val el = editor.selection?.takeIf { editor.selectionCount == 1 } ?: return ""
    if (!editor.doc.canSetMaterial(el)) return ""
    val m = editor.doc.materialOf(el)
    val assigned = if (editor.doc.assignedMaterial(el) == null) " (default)" else ""
    return "<div class=\"frow\"><span class=\"flabel\">material$assigned</span>" +
        "<input id=\"insp-color\" class=\"fcolor\" type=\"color\" value=\"${m.color}\" " +
        "title=\"Base colour. What an exported GLB and the realistic preview both render — one material, two consumers.\">" +
        "<input id=\"insp-rough\" class=\"fmat\" type=\"number\" min=\"0\" max=\"1\" step=\"0.05\" value=\"${Format.num(m.roughness)}\" " +
        "title=\"Roughness: 0 is mirror-smooth, 1 is fully diffuse.\">" +
        "<input id=\"insp-metal\" class=\"fmat\" type=\"number\" min=\"0\" max=\"1\" step=\"0.05\" value=\"${Format.num(m.metallic)}\" " +
        "title=\"Metalness: 0 is a dielectric (plastic, wood), 1 is bare metal.\">" +
        "</div>"
}

/**
 * **built from** / **used by** — the selection's inputs and dependents, by name (see `Dependencies`).
 *
 * Each name is a chip that highlights its element on the canvas when hovered and selects it when clicked,
 * which is what turns "which point is this circle's centre?" into one gesture rather than a hunt.
 */
private fun dependencyRows(editor: Editor): String {
    fun chip(
        el: constructit.editor.Element,
        role: String?,
    ): String {
        val label = if (role == null) editor.doc.displayName(el) else "$role ${editor.doc.displayName(el)}"
        return "<span class=\"dep\" data-eid=\"${el.id}\">$label</span>"
    }

    val inputs = editor.selectionInputs()
    val dependents = editor.selectionDependents()
    if (inputs.isEmpty() && dependents.isEmpty()) return ""
    val from =
        if (inputs.isEmpty()) {
            ""
        } else {
            "<div class=\"drow\"><span class=\"dlabel\">built from</span>" +
                inputs.joinToString("") { chip(it.element, it.role) } + "</div>"
        }
    val by =
        if (dependents.isEmpty()) {
            ""
        } else {
            "<div class=\"drow used\"><span class=\"dlabel\">used by</span>" +
                dependents.joinToString("") { chip(it, null) } + "</div>"
        }
    return from + by
}

private fun unitLabel(dim: Dimension): String =
    when (dim) {
        Dimension.LENGTH -> "mm"
        Dimension.ANGLE -> "°"
        else -> ""
    }

/**
 * The step a value field's spinner nudges by: 1 mm / 1° for the dimensions a drawing is measured in, 0.1
 * for a dimensionless factor, where 1 would jump past every useful value. Uniform on purpose — a
 * per-parameter step is a preference nobody asked for.
 */
private fun stepFor(dim: Dimension): String =
    when (dim) {
        Dimension.LENGTH, Dimension.ANGLE -> "1"
        else -> "0.1"
    }

private fun displayValue(q: Quantity): String =
    when (q.dim) {
        Dimension.ANGLE -> Format.num(q.deg)
        Dimension.LENGTH -> Format.num(q.mm)
        else -> Format.num(q.value)
    }
