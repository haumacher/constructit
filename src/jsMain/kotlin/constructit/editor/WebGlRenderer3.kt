package constructit.editor

import org.khronos.webgl.Float32Array
import org.khronos.webgl.WebGLBuffer
import org.khronos.webgl.WebGLProgram
import org.khronos.webgl.WebGLRenderingContext
import org.khronos.webgl.WebGLUniformLocation
import org.w3c.dom.HTMLCanvasElement

/**
 * The browser's 3D renderer: **one** shader program, drawing a [Scene3] with a headlight.
 *
 * No libraries, and no second projection pipeline — the MVP matrix comes from [Camera3.viewProjection],
 * exactly the numbers [Painter3] projects with on the CPU, so the SVG goldens of the headless suite are
 * evidence about this renderer too. That equality is the whole reason `Camera3` lives in `commonMain`
 * and not here (OP-12).
 *
 * Flat shading by **duplicated vertices**: every triangle contributes its own three vertices carrying
 * the face normal. It costs memory a preview does not care about and buys a shader with no derivative
 * extension, no smoothing groups and no vertex-normal averaging that would round off a machined edge —
 * a solid's facets are what it *is* (OP-9: the mesh is the sink, shown as it will be printed).
 *
 * Lines (grid, axes, and each solid's feature edges) go through the same program with a flag that skips the
 * lighting, so there is one program and one buffer discipline for the whole view.
 */
class WebGlRenderer3(private val canvas: HTMLCanvasElement) {
    /**
     * `preserveDrawingBuffer` is on deliberately. Without it the canvas is only readable inside the frame
     * that drew it, so `toDataURL` and `page.screenshot` come back blank — which would make the browser
     * E2E (and any later "export the view as an image") assert nothing at all. The cost is one buffer.
     */
    private fun contextAttributes(): dynamic {
        val o = js("({})")
        o.preserveDrawingBuffer = true
        o.antialias = true
        return o
    }

    private val gl: WebGLRenderingContext? =
        (
            canvas.getContext("webgl", contextAttributes())
                ?: canvas.getContext("experimental-webgl", contextAttributes())
        ) as? WebGLRenderingContext

    /** False when the browser gave us no context at all — the shell then says so instead of throwing. */
    val available: Boolean get() = gl != null

    private var program: WebGLProgram? = null
    private var posBuffer: WebGLBuffer? = null
    private var normBuffer: WebGLBuffer? = null
    private var colorBuffer: WebGLBuffer? = null
    private var uMvp: WebGLUniformLocation? = null
    private var uLight: WebGLUniformLocation? = null
    private var uLit: WebGLUniformLocation? = null
    private var aPos = -1
    private var aNorm = -1
    private var aColor = -1

    /** How many vertices of the buffers are triangles; the rest (to [lineCount]) are line endpoints. */
    private var triVertexCount = 0
    private var lineVertexCount = 0

    private fun init(gl: WebGLRenderingContext): WebGLProgram? {
        program?.let { return it }
        val vs = compile(gl, WebGLRenderingContext.VERTEX_SHADER, VERTEX_SRC) ?: return null
        val fs = compile(gl, WebGLRenderingContext.FRAGMENT_SHADER, FRAGMENT_SRC) ?: return null
        val p = gl.createProgram() ?: return null
        gl.attachShader(p, vs)
        gl.attachShader(p, fs)
        gl.linkProgram(p)
        if (gl.getProgramParameter(p, WebGLRenderingContext.LINK_STATUS) != true) return null
        program = p
        aPos = gl.getAttribLocation(p, "aPos")
        aNorm = gl.getAttribLocation(p, "aNorm")
        aColor = gl.getAttribLocation(p, "aColor")
        uMvp = gl.getUniformLocation(p, "uMvp")
        uLight = gl.getUniformLocation(p, "uLight")
        uLit = gl.getUniformLocation(p, "uLit")
        posBuffer = gl.createBuffer()
        normBuffer = gl.createBuffer()
        colorBuffer = gl.createBuffer()
        return p
    }

    private fun compile(
        gl: WebGLRenderingContext,
        type: Int,
        src: String,
    ): org.khronos.webgl.WebGLShader? {
        val s = gl.createShader(type) ?: return null
        gl.shaderSource(s, src)
        gl.compileShader(s)
        return if (gl.getShaderParameter(s, WebGLRenderingContext.COMPILE_STATUS) == true) s else null
    }

    /**
     * Rebuild the vertex buffers from [scene]. Called when the *document* changed, not when the camera
     * moved: an orbit is a uniform write, so dragging the view never touches the GPU's geometry.
     */
    fun upload(scene: Scene3) {
        val gl = gl ?: return
        init(gl) ?: return
        val pos = ArrayList<Float>()
        val norm = ArrayList<Float>()
        val col = ArrayList<Float>()
        for (solid in scene.solids) {
            val rgb = rgbOf(solid.color)
            val v = solid.mesh.vertices
            for (t in solid.mesh.triangles) {
                val a = v[t.a]
                val b = v[t.b]
                val c = v[t.c]
                val n = (b - a).cross(c - a).normalized()
                for (p in listOf(a, b, c)) {
                    pos.add(p.x.toFloat())
                    pos.add(p.y.toFloat())
                    pos.add(p.z.toFloat())
                    norm.add(n.x.toFloat())
                    norm.add(n.y.toFloat())
                    norm.add(n.z.toFloat())
                    col.addAll(rgb)
                }
            }
        }
        triVertexCount = pos.size / 3
        // Feature edges (GitHub issue #3) ride the *same* line section as the furniture: they are unlit lines
        // in a colour of their own, which is exactly what the grid already is, so one more draw call would buy
        // nothing. Their normal is never read (uLit = 0), so any unit vector will do.
        for (solid in scene.solids) {
            val rgb = rgbOf(solid.edgeColor)
            for (e in solid.edges) {
                for (p in listOf(e.a, e.b)) {
                    pos.add(p.x.toFloat())
                    pos.add(p.y.toFloat())
                    pos.add(p.z.toFloat())
                    norm.add(0f)
                    norm.add(0f)
                    norm.add(1f)
                    col.addAll(rgb)
                }
            }
        }
        for (line in scene.lines) {
            val rgb = rgbOf(line.color)
            for (p in listOf(line.a, line.b)) {
                pos.add(p.x.toFloat())
                pos.add(p.y.toFloat())
                pos.add(p.z.toFloat())
                norm.add(0f)
                norm.add(0f)
                norm.add(1f)
                col.addAll(rgb)
            }
        }
        lineVertexCount = pos.size / 3 - triVertexCount
        bind(gl, posBuffer, pos)
        bind(gl, normBuffer, norm)
        bind(gl, colorBuffer, col)
    }

    private fun bind(
        gl: WebGLRenderingContext,
        buffer: WebGLBuffer?,
        data: List<Float>,
    ) {
        gl.bindBuffer(WebGLRenderingContext.ARRAY_BUFFER, buffer)
        gl.bufferData(WebGLRenderingContext.ARRAY_BUFFER, Float32Array(data.toFloatArray().toTypedArray()), WebGLRenderingContext.STATIC_DRAW)
    }

    /** Draw the uploaded scene through [cam]. Depth test on — this is the real one, not painter's. */
    fun draw(cam: Camera3) {
        val gl = gl ?: return
        val p = init(gl) ?: return
        val w = canvas.width
        val h = canvas.height
        gl.viewport(0, 0, w, h)
        gl.clearColor(1.0f, 1.0f, 1.0f, 1.0f)
        gl.enable(WebGLRenderingContext.DEPTH_TEST)
        gl.clear(WebGLRenderingContext.COLOR_BUFFER_BIT or WebGLRenderingContext.DEPTH_BUFFER_BIT)
        gl.useProgram(p)
        val mvp = cam.viewProjection(w.toDouble(), h.toDouble()).toFloatArray()
        gl.uniformMatrix4fv(uMvp, false, Float32Array(mvp.toTypedArray()))
        // the headlight points where the camera looks, so the lit side is always the side facing us
        val f = cam.forward()
        gl.uniform3f(uLight, f.x.toFloat(), f.y.toFloat(), f.z.toFloat())
        attrib(gl, aPos, posBuffer)
        attrib(gl, aNorm, normBuffer)
        attrib(gl, aColor, colorBuffer)
        if (triVertexCount > 0) {
            gl.uniform1f(uLit, 1.0f)
            // A feature edge lies exactly *on* the two faces that make it, so it z-fights them and comes out
            // stitched. The fix is depth bias, and the choice here is to offset the **faces away** from the
            // eye rather than the lines toward it: GL ES 2.0 has `POLYGON_OFFSET_FILL` only — there is no
            // `POLYGON_OFFSET_LINE` to enable — and pushing the fill back one depth-slope unit is the one
            // hardware-exact way to say "the coincident line wins". Doing it in the shader instead (scaling
            // `gl_Position.z`) would be a second projection pipeline and break the rule that the GPU
            // multiplies nothing the painter's projector does not (OP-12).
            gl.enable(WebGLRenderingContext.POLYGON_OFFSET_FILL)
            gl.polygonOffset(1.0f, 1.0f)
            gl.drawArrays(WebGLRenderingContext.TRIANGLES, 0, triVertexCount)
            gl.disable(WebGLRenderingContext.POLYGON_OFFSET_FILL)
        }
        if (lineVertexCount > 0) {
            gl.uniform1f(uLit, 0.0f)
            gl.drawArrays(WebGLRenderingContext.LINES, triVertexCount, lineVertexCount)
        }
    }

    private fun attrib(
        gl: WebGLRenderingContext,
        index: Int,
        buffer: WebGLBuffer?,
    ) {
        if (index < 0) return
        gl.bindBuffer(WebGLRenderingContext.ARRAY_BUFFER, buffer)
        gl.enableVertexAttribArray(index)
        gl.vertexAttribPointer(index, 3, WebGLRenderingContext.FLOAT, false, 0, 0)
    }

    private companion object {
        /** `#rrggbb` as three 0..1 floats — the same palette strings the painter's projector shades. */
        fun rgbOf(hex: String): List<Float> {
            if (hex.length != 7 || hex[0] != '#') return listOf(0.5f, 0.5f, 0.5f)
            return (0..2).map { (hex.substring(1 + it * 2, 3 + it * 2).toIntOrNull(16) ?: 128) / 255.0f }
        }

        val VERTEX_SRC = """
            attribute vec3 aPos;
            attribute vec3 aNorm;
            attribute vec3 aColor;
            uniform mat4 uMvp;
            varying vec3 vNorm;
            varying vec3 vColor;
            void main() {
                vNorm = aNorm;
                vColor = aColor;
                gl_Position = uMvp * vec4(aPos, 1.0);
            }
        """

        /**
         * Headlight diffuse plus an ambient floor — the same shading law as `Painter3.AMBIENT`, so the
         * two back ends read the same way. `uLit` is 0 for the grid and axes, which are their own colour.
         */
        val FRAGMENT_SRC = """
            precision mediump float;
            varying vec3 vNorm;
            varying vec3 vColor;
            uniform vec3 uLight;
            uniform float uLit;
            void main() {
                float diffuse = abs(dot(normalize(vNorm), normalize(uLight)));
                float k = mix(1.0, 0.35 + 0.65 * diffuse, uLit);
                gl_FragColor = vec4(vColor * k, 1.0);
            }
        """
    }
}
