package constructit

import constructit.core.Evaluator
import constructit.dsl.scalar
import constructit.editor.BrowserCanvasDrawTarget
import constructit.editor.Camera
import constructit.editor.CreateDialog
import constructit.editor.CreateMode
import constructit.editor.DocumentFormat
import constructit.editor.DocumentName
import constructit.editor.Editor
import constructit.editor.Format
import constructit.editor.PointerButton
import constructit.editor.Scene3
import constructit.editor.Tools
import constructit.editor.Viewport3
import constructit.editor.WebGlRenderer3
import constructit.geom.Justification
import constructit.geom.MeshBool
import constructit.geom.Vec2
import constructit.units.Dimension
import constructit.units.Quantity
import kotlinx.browser.document
import kotlinx.browser.window
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
    editor.camera = Camera.centered(canvas.width.toDouble(), canvas.height.toDouble(), scale = 4.0)
    val target = BrowserCanvasDrawTarget(ctx)

    buildPalette(editor)

    // ---- the 3D view (OP-12: Canvas2D for 2D, WebGL for 3D) ----
    //
    // A second canvas over the same area, and a pure [Viewport3] driving it — the shell contributes only
    // event plumbing and the GL calls, so orbit/zoom/pan are the headless-tested gestures of the
    // controller and nothing about *what a drag means* lives here.
    val viewport = Viewport3()
    val gl = WebGlRenderer3(canvas3)
    var view3d = false
    // The document's own "version counter" is [Editor.onChange]: geometry is rebuilt exactly when the
    // editor reports a change, and an orbit — which does not go through the editor at all — only
    // re-issues the draw call with a new matrix.
    var glDirty = true

    fun draw3d() {
        if (fit(canvas3)) glDirty = true
        viewport.widthPx = canvas3.width.toDouble()
        viewport.heightPx = canvas3.height.toDouble()
        if (glDirty) {
            gl.upload(Scene3.extract(editor.doc))
            glDirty = false
        }
        gl.draw(viewport.camera)
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
            glDirty = true
            draw3d()
        } else {
            editor.render(target)
        }
        renderPanel(editor, view3d, viewport)
    }
    editor.onChange = { repaint() }
    viewport.onChange = {
        draw3d()
        renderPanel(editor, view3d, viewport)
    }

    fun setView3d(on: Boolean) {
        view3d = on
        canvas.hidden = on
        canvas3.hidden = !on
        (document.getElementById("v-2d") as HTMLElement).className = if (on) "" else "active"
        (document.getElementById("v-3d") as HTMLElement).className = if (on) "active" else ""
        if (on) {
            glDirty = true
            // frame the solids the first time there is something to look at, so switching over does not
            // land on an empty view with the part behind the camera
            viewport.widthPx = canvas3.width.toDouble()
            viewport.heightPx = canvas3.height.toDouble()
            val scene = Scene3.extract(editor.doc)
            if (!scene.isEmpty) viewport.frame(scene)
            editor.note(
                if (scene.isEmpty) {
                    "Nothing solid yet — trace an Outline (or a Wall), then use Extrude or Revolve in the 2D view."
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
    (document.getElementById("v-2d") as HTMLElement).addEventListener("click", { setView3d(false) })
    (document.getElementById("v-3d") as HTMLElement).addEventListener("click", { setView3d(true) })

    // Which 2D sketch space the canvas shows (OP-17): the view indicator *and* the way back to the plan.
    // A `<select>` because that is what "one of these, and here is which" is; the Editor owns the switch
    // (its own camera per space, its own selection reset), so this only routes.
    (document.getElementById("v-space") as HTMLElement).addEventListener("change", {
        // read what was picked *first*: a repaint restates the select from the editor, so anything that
        // paints in between (the view switch below) would put the old value back under us
        val picked = (document.getElementById("v-space") as HTMLSelectElement).value
        // the 2D view first: a space is a 2D thing, so asking for one means looking at it — and switching
        // views has a note of its own, which must not talk over the space's
        if (view3d) setView3d(false)
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

    // ---- 3D canvas input: the same three gestures, routed to the pure Viewport3 ----
    fun pos3(e: MouseEvent): Vec2 {
        val r = canvas3.getBoundingClientRect()
        return Vec2(e.clientX - r.left, e.clientY - r.top)
    }
    canvas3.addEventListener("mousedown", {
        val e = it as MouseEvent
        if (e.button.toInt() == 1) e.preventDefault()
        viewport.panMode = spaceDown
        viewport.pointerDown(pos3(e), if (e.button.toInt() == 1 || spaceDown) PointerButton.MIDDLE else PointerButton.PRIMARY)
    })
    canvas3.addEventListener("mousemove", { viewport.pointerMove(pos3(it as MouseEvent)) })
    canvas3.addEventListener("mouseup", { viewport.pointerUp(pos3(it as MouseEvent)) })
    canvas3.addEventListener("mouseleave", { viewport.pointerUp(pos3(it as MouseEvent)) })
    canvas3.addEventListener("wheel", {
        val e = it as WheelEvent
        e.preventDefault()
        viewport.wheel(pos3(e), e.deltaY)
    })
    // double-click reframes: the cheap way back when an orbit has wandered off the part
    canvas3.addEventListener("dblclick", { viewport.frame(Scene3.extract(editor.doc)) })
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
    (document.getElementById("groups-list") as HTMLElement).addEventListener("click", {
        val t = it.target as? HTMLElement ?: return@addEventListener
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
    (document.getElementById("palette") as HTMLElement).addEventListener("click", {
        val btn = (it.target as? HTMLElement)?.closest("button") ?: return@addEventListener
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
        editor.activeScalar = editor.doc.scalars.firstOrNull { s -> s.id == row.getAttribute("data-sid") }
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
    (document.getElementById("inspector") as HTMLElement).addEventListener("change", {
        val t = it.target as? HTMLInputElement ?: return@addEventListener
        val idx = t.getAttribute("data-fidx")?.toIntOrNull() ?: return@addEventListener
        val v = t.value.toDoubleOrNull() ?: return@addEventListener
        if (!editor.writeSelectionField(idx, v)) editor.note("That value is determined by the construction and can't be set here")
        repaint()
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
        val text = DocumentFormat.save(editor.doc)
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
    (document.getElementById("f-copy") as HTMLElement).addEventListener("click", {
        val text = DocumentFormat.save(editor.doc)
        window.navigator.clipboard.writeText(text).then(
            { note("Copied ${text.lines().size - 1} step(s) to the clipboard") },
            { note("Clipboard refused; use Save instead", error = true) },
        )
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
        val el = editor.doc.elements.firstOrNull { e -> e.id == row.getAttribute("data-eid") } ?: return@addEventListener
        editor.selectElement(el)
    })

    // a measurement can drive a new construction: click it to make it the active scalar (OP-4)
    (document.getElementById("measure-list") as HTMLElement).addEventListener("click", {
        val row = (it.target as? HTMLElement)?.closest(".mrow") ?: return@addEventListener
        editor.activeScalar = editor.doc.scalars.firstOrNull { s -> s.id == row.getAttribute("data-sid") }
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

    // the tool's key, on the button and in its tooltip — a shortcut nobody can see is a shortcut nobody uses
    fun keyTag(key: Char?): String = if (key == null) "" else "<span class=\"tkey\">$key</span>"
    sb.append(
        "<button class=\"tool active\" id=\"tool-select\" data-tool=\"select\" title=\"Select / drag (${Tools.SELECT_KEY})\">" +
            "Select / Drag${keyTag(Tools.SELECT_KEY)}</button>",
    )
    for (cat in constructit.editor.ToolCategory.values()) {
        val inCat = tools.filter { it.category == cat }
        // the custom category exists only once the document defines a macro
        if (inCat.isEmpty()) continue
        sb.append("<div class=\"cat\">${cat.name.lowercase()}</div>")
        for (t in inCat) {
            val hint = if (t.shortcut == null) t.help else "${t.help} (shortcut ${t.shortcut})"
            sb.append("<button class=\"tool\" id=\"tool-${t.id}\" data-tool=\"${t.id}\" title=\"$hint\">${t.label}${keyTag(t.shortcut)}</button>")
        }
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
    host.innerHTML =
        "<div class=\"cdtitle\">${d.title} — ${d.members.size} element(s)</div>" +
        "<div class=\"cdhelp\">${d.help}</div>" +
        "<input id=\"cd-name\" type=\"text\" placeholder=\"name\" value=\"${d.name}\">" +
        rows +
        "<div class=\"cdbuttons\"><button id=\"cd-ok\">Create</button><button id=\"cd-cancel\">Cancel</button></div>"
}

private fun renderPanel(
    editor: Editor,
    view3d: Boolean,
    viewport: Viewport3,
) {
    // In the 3D view the drawing tools are inert (there is no 3D picking in this slice), so the status
    // line says what the view *does* rather than describing clicks that will not happen.
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
            val label = if (s.isPlan) "plan" else "${s.name} (face of ${s.anchor?.id})"
            "<option value=\"${s.name}\"${if (s.name == editor.activeSpace.name) " selected" else ""}>$label</option>"
        }
    if (spaceSel.innerHTML != spaceOptions) spaceSel.innerHTML = spaceOptions
    spaceSel.value = editor.activeSpace.name

    // edit buttons mirror the editor's stacks and selection
    (document.getElementById("e-undo") as org.w3c.dom.HTMLButtonElement).disabled = !editor.canUndo
    (document.getElementById("e-redo") as org.w3c.dom.HTMLButtonElement).disabled = !editor.canRedo
    (document.getElementById("e-delete") as org.w3c.dom.HTMLButtonElement).disabled = editor.selection == null

    // the palette carries the document's own macros beside the built-in tools (OP-6), so it follows the
    // document rather than being built once at startup
    buildPalette(editor)
    renderCreateDialog(editor)

    // active tool highlight
    val toolNodes = document.querySelectorAll(".tool")
    for (i in 0 until toolNodes.length) {
        val el = toolNodes.item(i) as HTMLElement
        el.className = if (el.getAttribute("data-tool") == editor.toolId) "tool active" else "tool"
    }

    val ev = Evaluator()

    // selection inspector: one row per handle field — the numeric form of the selection's drag
    val insp = document.getElementById("inspector") as HTMLElement
    val fields = editor.selectionFields()
    insp.innerHTML =
        if (editor.selection == null) {
            "<div class=\"hint\">Click a corner or a leg to read and set its values.</div>"
        } else if (editor.selectionCount > 1 && fields.isEmpty()) {
            // a *placed* group is the exception: several elements are selected, but they have one handle
            // between them — the frame (OP-16 step 2) — so its fields are shown rather than nothing
            // fields address one handle (OP-13), so a multi-selection shows none — but it is what
            // delete, hide and Group act on, hence the count
            "<div class=\"selname\">${editor.selectionLabel()}</div>" +
                "<div class=\"hint\">Delete, Hide and Group act on all of them. Click one element for its values.</div>"
        } else {
            "<div class=\"selname\">${editor.selectionLabel()}</div>" +
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
                if (fields.isEmpty()) "<div class=\"hint\">No editable values — this element is fully derived.</div>" else ""
        }

    // groups: a named set (OP-16) — click to select its members, ◉ to hide/show, ⌖ to place/unplace
    // (a placed group gets its own frame: dragging it then moves the frame), × to dissolve
    val glist = document.getElementById("groups-list") as HTMLElement
    glist.innerHTML =
        editor.doc.groups.joinToString("") { g ->
            "<div class=\"grow\" data-gid=\"${g.id}\">" +
                "<span class=\"gname\">${g.name}</span>" +
                "<span class=\"gcount\">${editor.doc.groupMembers(g).size}</span>" +
                "<button class=\"gplace\" title=\"${if (g.placed) "Unplace — its points become free again where they are" else "Place — give it a frame; dragging then moves the whole group"}\">${if (g.placed) "⊗" else "⌖"}</button>" +
                "<button class=\"gvis\" title=\"Hide or show every member\">${if (editor.isGroupVisible(g)) "◉" else "○"}</button>" +
                "<button class=\"gdrop\" title=\"Dissolve the group — its elements stay\">×</button>" +
                "</div>"
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
        editor.doc.elements.joinToString("") {
            val active = if (editor.isSelected(it)) " active" else ""
            // the tree lists the whole document, so a row the canvas is not drawing says where it lives
            // (OP-17): one canvas shows one sketch space
            val where = if (it.space == editor.activeSpace.name) "" else " · ${it.space}"
            "<div class=\"item$active\" data-eid=\"${it.id}\">${it.kind.name.lowercase()}$where<span class=\"eid\">${it.id}</span></div>"
        }
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
