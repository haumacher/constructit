package constructit.editor

import constructit.geom.Vec2
import constructit.geom.Vec3

/**
 * A **painter's-algorithm projector**: the 3D scene drawn through the ordinary [DrawTarget] seam.
 *
 * Its job is not to be the fast renderer — the browser's WebGL path is that — but to make the
 * projection *testable*. It uses the very same [Camera3] matrices the GPU is handed, so an SVG golden
 * of a 3D scene is evidence about what the browser draws, and the whole of the 3D view except the GL
 * calls themselves is exercised headlessly (OP-12's testability rule, which is why the [DrawTarget]
 * seam exists at all).
 *
 * Deliberately simple, and honest about it:
 * - **No depth buffer.** Triangles and furniture lines are sorted back-to-front by centroid depth and
 *   painted in that order. Exact for a convex solid; a concave one can show a wrong overlap where two
 *   triangles interleave in depth. The GPU path has a real depth test, so this affects goldens only.
 * - **Back faces are culled**, which for a watertight mesh (OP-2 guarantees one) leaves exactly the
 *   silhouette and halves the work.
 * - **One headlight.** Diffuse shading from a light at the eye, plus an ambient floor, which is all it
 *   takes to read a shape. No specular, no shadows: this is a preview of a *sink* (OP-9).
 */
object Painter3 {
    /** How much of a face's colour survives where it faces fully away from the light. */
    const val AMBIENT = 0.35

    /**
     * A triangle is stroked in its own fill colour so the seams between neighbours close up. Without
     * it, anti-aliasing leaves hairlines *through* a solid surface, which reads as cracks in the part.
     */
    private const val FACE_STROKE_PX = 1.0

    private const val LINE_PX = 1.0

    /** One thing to paint, with the depth it is sorted by. */
    private class Item(val depth: Double, val draw: (DrawTarget) -> Unit)

    fun render(
        scene: Scene3,
        cam: Camera3,
        target: DrawTarget,
        wPx: Double,
        hPx: Double,
    ) {
        target.begin(wPx, hPx)
        val vp = cam.viewProjection(wPx, hPx)
        val eye = cam.eye
        val items = ArrayList<Item>()

        for (line in scene.lines) {
            val a = cam.projectWith(vp, line.a, wPx, hPx) ?: continue
            val b = cam.projectWith(vp, line.b, wPx, hPx) ?: continue
            val depth = cam.viewDepth((line.a + line.b) * 0.5)
            if (depth <= 0.0) continue
            val style = Style(line.color, LINE_PX)
            items.add(Item(depth) { it.polyline(listOf(a, b), style) })
        }

        for (solid in scene.solids) {
            val verts = solid.mesh.vertices
            for (t in solid.mesh.triangles) {
                val a = verts[t.a]
                val b = verts[t.b]
                val c = verts[t.c]
                val n = (b - a).cross(c - a)
                val nLen = n.length()
                if (nLen <= Vec3.EPS) continue
                val centroid = (a + b + c) * (1.0 / 3.0)
                val toEye = (eye - centroid).normalized()
                val facing = n.dot(toEye) / nLen
                if (facing <= 0.0) continue // back face of a closed shell: never visible
                val pa = cam.projectWith(vp, a, wPx, hPx) ?: continue
                val pb = cam.projectWith(vp, b, wPx, hPx) ?: continue
                val pc = cam.projectWith(vp, c, wPx, hPx) ?: continue
                val shade = shade(solid.color, AMBIENT + (1.0 - AMBIENT) * facing)
                val style = Style(shade, FACE_STROKE_PX, fill = shade)
                val poly = listOf(pa, pb, pc)
                items.add(Item(cam.viewDepth(centroid)) { it.polygon(poly, style) })
            }
        }

        // far first: the near ones paint over them. Ties broken by insertion order, so the output is a
        // deterministic function of the scene — a golden's whole reason for existing.
        for (item in items.sortedWith(compareByDescending { it.depth })) item.draw(target)
        target.end()
    }

    /**
     * [hex] (`#rrggbb`) scaled by [k], clamped to the channel range, in a fixed lowercase two-digit
     * format so a shade is byte-reproducible.
     *
     * Rounded by `+0.5` and truncation rather than by `round`, which resolves ties differently on the
     * two platforms (half-to-even on the JVM, half-up in the browser) — a golden containing colours must
     * not depend on which one produced it.
     */
    fun shade(
        hex: String,
        k: Double,
    ): String {
        val rgb = parseHex(hex) ?: return hex
        val f = k.coerceIn(0.0, 1.0)
        return "#" + rgb.joinToString("") { two((it * f + 0.5).toInt().coerceIn(0, 255)) }
    }

    private fun parseHex(hex: String): List<Double>? {
        if (hex.length != 7 || hex[0] != '#') return null
        val out = ArrayList<Double>(3)
        for (i in 0..2) {
            val v = hex.substring(1 + i * 2, 3 + i * 2).toIntOrNull(16) ?: return null
            out.add(v.toDouble())
        }
        return out
    }

    private fun two(v: Int): String {
        val s = v.toString(16)
        return if (s.length == 1) "0$s" else s
    }

    /** A screen-space bounding box of everything projected — what a "did it draw anything" test asks. */
    fun projectedBounds(
        scene: Scene3,
        cam: Camera3,
        wPx: Double,
        hPx: Double,
    ): Pair<Vec2, Vec2>? {
        val vp = cam.viewProjection(wPx, hPx)
        var lo: Vec2? = null
        var hi: Vec2? = null
        for (solid in scene.solids) {
            for (v in solid.mesh.vertices) {
                val p = cam.projectWith(vp, v, wPx, hPx) ?: continue
                val l = lo
                val h = hi
                lo = if (l == null) p else Vec2(kotlin.math.min(l.x, p.x), kotlin.math.min(l.y, p.y))
                hi = if (h == null) p else Vec2(kotlin.math.max(h.x, p.x), kotlin.math.max(h.y, p.y))
            }
        }
        val l = lo ?: return null
        return l to (hi ?: l)
    }
}
