package constructit

import constructit.editor.BrowserCanvasDrawTarget
import constructit.editor.Camera
import constructit.editor.Editor
import constructit.editor.Tool
import constructit.geom.Vec2
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.MouseEvent
import org.w3c.dom.events.WheelEvent

private val TOOL_BUTTONS = listOf(
    "tool-select" to Tool.SELECT,
    "tool-point" to Tool.POINT,
    "tool-line" to Tool.LINE,
    "tool-circle" to Tool.CIRCLE,
    "tool-intersect" to Tool.INTERSECT,
)

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

    fun repaint() {
        editor.render(target)
        renderTree(editor)
    }
    editor.onChange = { repaint() }

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

    TOOL_BUTTONS.forEach { (id, tool) ->
        (document.getElementById(id) as HTMLElement).addEventListener("click", {
            editor.setTool(tool)
            TOOL_BUTTONS.forEach { (bid, _) ->
                (document.getElementById(bid) as HTMLElement).className = if (bid == id) "tool active" else "tool"
            }
        })
    }

    window.addEventListener("resize", {
        fitCanvas()
        editor.canvasW = canvas.width.toDouble()
        editor.canvasH = canvas.height.toDouble()
        repaint()
    })

    repaint()
}

private fun renderTree(editor: Editor) {
    val tree = document.getElementById("tree") ?: return
    tree.innerHTML = editor.doc.elements.joinToString("") {
        "<div class=\"item\"><span class=\"kind\">${it.kind}</span> <span class=\"eid\">${it.id}</span></div>"
    }
}
