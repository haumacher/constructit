package constructit

import constructit.core.Evaluator
import constructit.dsl.scalar
import constructit.editor.BrowserCanvasDrawTarget
import constructit.editor.Camera
import constructit.editor.Editor
import constructit.editor.Format
import constructit.editor.ScalarEntry
import constructit.editor.Tools
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
        val q = when (unit) { "deg" -> Quantity.deg(v); "num" -> Quantity.number(v); else -> Quantity.mm(v) }
        editor.activeScalar = editor.doc.newParameter(name, q)
        repaint()
    })
    val paramsList = document.getElementById("params-list") as HTMLElement
    // select active parameter by clicking a row
    paramsList.addEventListener("click", {
        val row = (it.target as? HTMLElement)?.closest(".prow") ?: return@addEventListener
        val sid = row.getAttribute("data-sid")
        editor.activeScalar = editor.doc.scalars.firstOrNull { s -> s.id == sid }
        repaint()
    })
    // edit a parameter value (on commit)
    paramsList.addEventListener("change", {
        val input = it.target as? HTMLInputElement ?: return@addEventListener
        val sid = input.getAttribute("data-sid") ?: return@addEventListener
        val entry = editor.doc.scalars.firstOrNull { s -> s.id == sid } ?: return@addEventListener
        val v = input.value.toDoubleOrNull() ?: return@addEventListener
        editor.doc.setParameter(entry, quantityIn(entry, v))
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

    // active tool highlight
    val toolNodes = document.querySelectorAll(".tool")
    for (i in 0 until toolNodes.length) {
        val el = toolNodes.item(i) as HTMLElement
        el.className = if (el.getAttribute("data-tool") == editor.toolId) "tool active" else "tool"
    }

    val ev = Evaluator()

    // parameters (editable)
    val plist = document.getElementById("params-list") as HTMLElement
    plist.innerHTML = editor.doc.scalars.filter { it.editable }.joinToString("") { s ->
        val active = if (s === editor.activeScalar) " active" else ""
        val q = ev.scalar(s.ref)
        "<div class=\"prow$active\" data-sid=\"${s.id}\"><span class=\"pname\">${s.name}</span>" +
            "<input class=\"pval\" data-sid=\"${s.id}\" value=\"${displayValue(q)}\"><span class=\"punit\">${unitLabel(q.dim)}</span></div>"
    }

    // measurements (read-only)
    val mlist = document.getElementById("measure-list") as HTMLElement
    mlist.innerHTML = editor.doc.scalars.filter { !it.editable }.joinToString("") { s ->
        val value = (ev.eval(s.ref.node) as? constructit.core.EvalResult.Ok)?.let { Format.quantity((it.value as constructit.core.ScalarValue).q) } ?: "—"
        "<div class=\"mrow\"><span>${s.name}</span><span class=\"mval\">$value</span></div>"
    }

    // element tree
    val tree = document.getElementById("tree") as HTMLElement
    tree.innerHTML = editor.doc.elements.joinToString("") {
        "<div class=\"item\">${it.kind.name.lowercase()}<span class=\"eid\">${it.id}</span></div>"
    }
}

private fun unitLabel(dim: Dimension): String = when (dim) {
    Dimension.LENGTH -> "mm"; Dimension.ANGLE -> "°"; else -> ""
}

private fun displayValue(q: Quantity): String = when (q.dim) {
    Dimension.ANGLE -> Format.num(q.deg)
    Dimension.LENGTH -> Format.num(q.mm)
    else -> Format.num(q.value)
}

private fun quantityIn(entry: ScalarEntry, value: Double): Quantity {
    val dim = (Evaluator().eval(entry.ref.node) as? constructit.core.EvalResult.Ok)?.let { (it.value as constructit.core.ScalarValue).q.dim } ?: Dimension.LENGTH
    return when (dim) { Dimension.ANGLE -> Quantity.deg(value); Dimension.LENGTH -> Quantity.mm(value); else -> Quantity.number(value) }
}
