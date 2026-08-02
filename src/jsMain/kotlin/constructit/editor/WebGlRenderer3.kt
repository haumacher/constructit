package constructit.editor

import org.khronos.webgl.Float32Array
import org.khronos.webgl.WebGLBuffer
import org.khronos.webgl.WebGLProgram
import org.khronos.webgl.WebGLRenderingContext
import org.khronos.webgl.WebGLUniformLocation
import org.khronos.webgl.set
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

    /** How many vertices of the buffers are triangles; the rest (to [lineVertexCount]) are line endpoints. */
    private var triVertexCount = 0
    private var lineVertexCount = 0

    /** The MVP uniform's storage, allocated once — see [draw]. */
    private val mvpBuf = Float32Array(16)

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
        // **Counted first, then written straight into the typed arrays the GPU takes.** What was here built
        // an `ArrayList<Float>` per attribute, converted it to a `FloatArray`, then boxed *that* into an
        // `Array<Float>` for the `Float32Array` constructor — three copies of the mesh, one of them a
        // million-odd boxed objects for an imported assembly, on every upload. The vertex count is known
        // exactly from the scene (three per triangle, two per line), so one pass sizes the buffers and a
        // second fills them, with no intermediate collection at all.
        var triVerts = 0
        for (solid in scene.solids) triVerts += solid.mesh.triangles.size * 3
        var lineVerts = 0
        for (solid in scene.solids) lineVerts += solid.edges.size * 2
        for (curve in scene.curves) lineVerts += maxOf(curve.points.size - 1, 0) * 2
        lineVerts += scene.lines.size * 2
        val total = triVerts + lineVerts
        val pos = Float32Array(total * 3)
        val norm = Float32Array(total * 3)
        val col = Float32Array(total * 3)
        var at = 0

        fun vertex(
            px: Double,
            py: Double,
            pz: Double,
            nx: Float,
            ny: Float,
            nz: Float,
            rgb: FloatArray,
        ) {
            val i = at * 3
            pos[i] = px.toFloat()
            pos[i + 1] = py.toFloat()
            pos[i + 2] = pz.toFloat()
            norm[i] = nx
            norm[i + 1] = ny
            norm[i + 2] = nz
            col[i] = rgb[0]
            col[i + 1] = rgb[1]
            col[i + 2] = rgb[2]
            at++
        }
        for (solid in scene.solids) {
            val rgb = rgbOf(solid.color)
            val v = solid.mesh.vertices
            for (t in solid.mesh.triangles) {
                val a = v[t.a]
                val b = v[t.b]
                val c = v[t.c]
                val n = (b - a).cross(c - a).normalized()
                val nx = n.x.toFloat()
                val ny = n.y.toFloat()
                val nz = n.z.toFloat()
                vertex(a.x, a.y, a.z, nx, ny, nz, rgb)
                vertex(b.x, b.y, b.z, nx, ny, nz, rgb)
                vertex(c.x, c.y, c.z, nx, ny, nz, rgb)
            }
        }
        triVertexCount = at
        // Feature edges (GitHub issue #3) ride the *same* line section as the furniture: they are unlit lines
        // in a colour of their own, which is exactly what the grid already is, so one more draw call would buy
        // nothing. Their normal is never read (uLit = 0), so any unit vector will do.
        for (solid in scene.solids) {
            val rgb = rgbOf(solid.edgeColor)
            for (e in solid.edges) {
                vertex(e.a.x, e.a.y, e.a.z, 0f, 0f, 1f, rgb)
                vertex(e.b.x, e.b.y, e.b.z, 0f, 0f, 1f, rgb)
            }
        }
        // Curves in space (OP-26) ride the same section for the same reason the feature edges do: they are
        // unlit lines in a colour of their own. Emitted as GL_LINES pairs rather than a strip, because one
        // buffer carries every curve and a strip would join the end of one to the start of the next.
        for (curve in scene.curves) {
            val rgb = rgbOf(curve.color)
            for ((p, q) in curve.points.zipWithNext()) {
                vertex(p.x, p.y, p.z, 0f, 0f, 1f, rgb)
                vertex(q.x, q.y, q.z, 0f, 0f, 1f, rgb)
            }
        }
        for (line in scene.lines) {
            val rgb = rgbOf(line.color)
            vertex(line.a.x, line.a.y, line.a.z, 0f, 0f, 1f, rgb)
            vertex(line.b.x, line.b.y, line.b.z, 0f, 0f, 1f, rgb)
        }
        lineVertexCount = at - triVertexCount
        bind(gl, posBuffer, pos)
        bind(gl, normBuffer, norm)
        bind(gl, colorBuffer, col)
    }

    private fun bind(
        gl: WebGLRenderingContext,
        buffer: WebGLBuffer?,
        data: Float32Array,
    ) {
        gl.bindBuffer(WebGLRenderingContext.ARRAY_BUFFER, buffer)
        gl.bufferData(WebGLRenderingContext.ARRAY_BUFFER, data, WebGLRenderingContext.STATIC_DRAW)
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
        // straight into a typed array that is allocated once, for [upload]'s reason one frame at a time:
        // `Float32Array(FloatArray.toTypedArray())` would box sixteen numbers on every frame of an orbit
        val mvp = cam.viewProjection(w.toDouble(), h.toDouble()).m
        for (i in 0..15) mvpBuf[i] = mvp[i].toFloat()
        gl.uniformMatrix4fv(uMvp, false, mvpBuf)
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
        fun rgbOf(hex: String): FloatArray {
            if (hex.length != 7 || hex[0] != '#') return floatArrayOf(0.5f, 0.5f, 0.5f)
            return FloatArray(3) { (hex.substring(1 + it * 2, 3 + it * 2).toIntOrNull(16) ?: 128) / 255.0f }
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
