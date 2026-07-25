package constructit

import constructit.core.Evaluator
import constructit.dsl.scalar
import constructit.editor.BrowserCanvasDrawTarget
import constructit.editor.Camera
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Format
import constructit.editor.ScalarEntry
import constructit.editor.Tools
import constructit.editor.quantityOf
import constructit.geom.Justification
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
    val ctx = canvas.getContext("2d") as CanvasRenderingContext2D

    fun fitCanvas() {
        val area = canvas.parentElement as HTMLElement
        canvas.width = area.clientWidth
        canvas.height = area.clientHeight
    }
    fitCanvas()

    val editor = Editor(canvasW = canvas.width.toDouble(), canvasH = canvas.height.toDouble())
    editor.showGrid = true
    editor.camera = Camera.centered(canvas.width.toDouble(), canvas.height.toDouble(), scale = 4.0)
    val target = BrowserCanvasDrawTarget(ctx)

    buildPalette()

    fun repaint() {
        editor.render(target)
        renderPanel(editor)
    }
    editor.onChange = { repaint() }

    // ---- canvas pointer input ----
    fun pos(e: MouseEvent): Vec2 {
        val r = canvas.getBoundingClientRect()
        return Vec2(e.clientX - r.left, e.clientY - r.top)
    }
    canvas.addEventListener("mousedown", { editor.pointerDown(pos(it as MouseEvent)) })
    canvas.addEventListener("mousemove", { editor.pointerMove(pos(it as MouseEvent)) })
    canvas.addEventListener("mouseup", { editor.pointerUp(pos(it as MouseEvent)) })
    canvas.addEventListener("mouseleave", { editor.pointerUp(pos(it as MouseEvent)) })
    canvas.addEventListener("wheel", {
        val e = it as WheelEvent
        e.preventDefault()
        editor.wheel(pos(e), e.deltaY)
    })
    canvas.addEventListener("dblclick", { editor.finishPath() })
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
                // the controller first: direct distance entry owns digits/Enter/Esc/Backspace while active
                editor.key(key) -> e.preventDefault()
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
    // edit a parameter value (pval) or wire it to another scalar (pbind), on commit
    paramsList.addEventListener("change", {
        val t = it.target as? HTMLElement ?: return@addEventListener
        val entry = editor.doc.scalars.firstOrNull { s -> s.id == t.getAttribute("data-sid") } ?: return@addEventListener
        when {
            t is HTMLInputElement && t.className.contains("pval") -> {
                val v = t.value.toDoubleOrNull() ?: return@addEventListener
                editor.doc.setParameter(entry, quantityIn(entry, v))
                editor.checkpoint()
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

    fun loadScript(text: String) {
        try {
            val fresh = DocumentFormat.load(text)
            editor.replaceDocument(fresh)
            note("Loaded ${fresh.elements.size} element(s)")
        } catch (e: Throwable) {
            note(e.message ?: "could not load that file", error = true)
        }
        repaint()
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
    (document.getElementById("f-copy") as HTMLElement).addEventListener("click", {
        val text = DocumentFormat.save(editor.doc)
        window.navigator.clipboard.writeText(text).then(
            { note("Copied ${text.lines().size - 1} step(s) to the clipboard") },
            { note("Clipboard refused; use Save instead", error = true) },
        )
    })
    (document.getElementById("f-download") as HTMLElement).addEventListener("click", {
        val blob = Blob(arrayOf(DocumentFormat.save(editor.doc)), BlobPropertyBag(type = "text/plain"))
        val a = document.createElement("a") as org.w3c.dom.HTMLAnchorElement
        a.href = URL.createObjectURL(blob)
        a.download = "drawing.cit"
        a.click()
        URL.revokeObjectURL(a.href)
        note("Saved drawing.cit")
    })
    val filePicker = document.getElementById("f-file") as HTMLInputElement
    (document.getElementById("f-load") as HTMLElement).addEventListener("click", { filePicker.click() })
    filePicker.addEventListener("change", {
        val file = filePicker.files?.item(0)
        if (file != null) {
            val reader = FileReader()
            reader.onload = { _ -> loadScript(reader.result as String) }
            reader.readAsText(file)
        }
    })

    // a measurement can drive a new construction: click it to make it the active scalar (OP-4)
    (document.getElementById("measure-list") as HTMLElement).addEventListener("click", {
        val row = (it.target as? HTMLElement)?.closest(".mrow") ?: return@addEventListener
        editor.activeScalar = editor.doc.scalars.firstOrNull { s -> s.id == row.getAttribute("data-sid") }
        repaint()
    })

    window.addEventListener("resize", {
        fitCanvas()
        editor.canvasW = canvas.width.toDouble()
        editor.canvasH = canvas.height.toDouble()
        repaint()
    })

    repaint()
}

private fun buildPalette() {
    val palette = document.getElementById("palette") as HTMLElement
    val sb = StringBuilder()
    sb.append("<button class=\"tool active\" id=\"tool-select\" data-tool=\"select\">Select / Drag</button>")
    for (cat in constructit.editor.ToolCategory.values()) {
        sb.append("<div class=\"cat\">${cat.name.lowercase()}</div>")
        for (t in Tools.all.filter { it.category == cat }) {
            sb.append("<button class=\"tool\" id=\"tool-${t.id}\" data-tool=\"${t.id}\">${t.label}</button>")
        }
    }
    palette.innerHTML = sb.toString()
}

private fun renderPanel(editor: Editor) {
    (document.getElementById("status") as HTMLElement).textContent =
        if (editor.statusHint.isNotEmpty()) editor.statusHint else editor.currentHelp()

    // edit buttons mirror the editor's stacks and selection
    (document.getElementById("e-undo") as org.w3c.dom.HTMLButtonElement).disabled = !editor.canUndo
    (document.getElementById("e-redo") as org.w3c.dom.HTMLButtonElement).disabled = !editor.canRedo
    (document.getElementById("e-delete") as org.w3c.dom.HTMLButtonElement).disabled = editor.selection == null

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

    // parameters (editable)
    val plist = document.getElementById("params-list") as HTMLElement
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
            "<div class=\"prow$active\" data-sid=\"${s.id}\">" +
                "<span class=\"pname\">${s.name}</span>" +
                "<input class=\"pval\" data-sid=\"${s.id}\" value=\"${displayValue(q)}\"$disabled>" +
                "<span class=\"punit\">${unitLabel(q.dim)}</span>" +
                "<select class=\"pbind\" data-sid=\"${s.id}\">$opts</select>" +
                "</div>"
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
            "<div class=\"item\">${it.kind.name.lowercase()}<span class=\"eid\">${it.id}</span></div>"
        }
}

private fun unitLabel(dim: Dimension): String =
    when (dim) {
        Dimension.LENGTH -> "mm"
        Dimension.ANGLE -> "°"
        else -> ""
    }

private fun displayValue(q: Quantity): String =
    when (q.dim) {
        Dimension.ANGLE -> Format.num(q.deg)
        Dimension.LENGTH -> Format.num(q.mm)
        else -> Format.num(q.value)
    }

private fun quantityIn(
    entry: ScalarEntry,
    value: Double,
): Quantity {
    val dim = (Evaluator().eval(entry.ref.node) as? constructit.core.EvalResult.Ok)?.let { (it.value as constructit.core.ScalarValue).q.dim } ?: Dimension.LENGTH
    return quantityOf(dim, value)
}
