package constructit.exchange

import constructit.geom.Geom3
import constructit.geom.Mesh3

/**
 * **3MF — the printing half, done honestly.** Core spec only: an OPC (ZIP) container with three parts, one
 * mesh object per solid, and a build that places each of them once.
 *
 * Why this format rather than STL for printing, in one line: **the unit is stated** (`unit="millimeter"`,
 * which is the engine's own canonical base unit, so nothing is converted) and **the spec requires the mesh to
 * be manifold with consistent orientation** — which is OP-9's watertight-or-refused doctrine written down as a
 * file format. That doctrine did the hard part long before this writer existed, so there is no repair pass
 * here and there never should be one. What there *is* is an assertion: [check] re-verifies every mesh at
 * export time and **refuses by name**. A guarantee that is never checked at the boundary is a guarantee that
 * quietly stops holding.
 *
 * Deliberately out: the materials, colours, slice and beam-lattice extensions. Tier 1's five numbers per solid
 * have a consumer that renders them (the GLB export and the preview); a slicer does not render, and a
 * colour in a print file that no slicer acts on is noise dressed as information.
 */
object ThreeMf {
    const val CORE_NAMESPACE = "http://schemas.microsoft.com/3dmanufacturing/core/2015/02"
    const val MODEL_RELATIONSHIP = "http://schemas.microsoft.com/3dmanufacturing/2013/01/3dmodel"
    const val MODEL_PART = "3D/3dmodel.model"
    const val MODEL_CONTENT_TYPE = "application/vnd.ms-package.3dmanufacturing-3dmodel+xml"
    const val RELS_CONTENT_TYPE = "application/vnd.openxmlformats-package.relationships+xml"

    /**
     * Why [scene] cannot be written as a 3MF, naming the body at fault — or null when it can.
     *
     * The check is `assertManifold`'s, structurally: every directed edge used exactly once with its reverse
     * used exactly once (closed *and* consistently oriented, in one statement) and a positive enclosed volume
     * (so the consistent orientation is the outward one, which is the direction 3MF defines).
     */
    fun check(scene: ExportScene): String? {
        for (n in scene.nodes) {
            val why = defect(n.mesh) ?: continue
            return "${n.name} cannot be printed: $why"
        }
        return null
    }

    private fun defect(mesh: Mesh3): String? {
        if (mesh.triangles.isEmpty()) return "it has no triangles"
        val used = HashMap<Long, Int>(mesh.triangles.size * 3)
        for (t in mesh.triangles) {
            if (t.a == t.b || t.b == t.c || t.a == t.c) return "a triangle repeats a corner"
            for (e in listOf(t.a to t.b, t.b to t.c, t.c to t.a)) {
                val key = (e.first.toLong() shl 32) or (e.second.toLong() and 0xffffffffL)
                used[key] = (used[key] ?: 0) + 1
            }
        }
        // Iterate the triangles, not the map: the checks are order-free but the message must not be.
        for (t in mesh.triangles) {
            for (e in listOf(t.a to t.b, t.b to t.c, t.c to t.a)) {
                val fwd = (e.first.toLong() shl 32) or (e.second.toLong() and 0xffffffffL)
                val back = (e.second.toLong() shl 32) or (e.first.toLong() and 0xffffffffL)
                if (used[fwd] != 1 || used[back] != 1) {
                    return "the surface is not closed and consistently wound at the edge ${e.first}-${e.second}"
                }
            }
        }
        if (Geom3.volume(mesh) <= 0.0) return "it encloses no positive volume — the surface is inside out"
        return null
    }

    fun write(scene: ExportScene): ByteArray {
        require(scene.nodes.isNotEmpty()) { "an empty scene is a refusal, not a file (see ExportScene.refusal)" }
        check(scene)?.let { throw IllegalArgumentException(it) }
        val zip = Zip()
        // The three parts an OPC package needs, in the order a reader looks for them. `[Content_Types].xml`
        // first is a convention rather than a rule, and a helpful one: a reader can stop as soon as it knows
        // what the package holds.
        zip.add(
            "[Content_Types].xml",
            """<?xml version="1.0" encoding="UTF-8"?>""" + "\n" +
                """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""" +
                """<Default Extension="rels" ContentType="$RELS_CONTENT_TYPE"/>""" +
                """<Default Extension="model" ContentType="$MODEL_CONTENT_TYPE"/>""" +
                "</Types>\n",
        )
        zip.add(
            "_rels/.rels",
            """<?xml version="1.0" encoding="UTF-8"?>""" + "\n" +
                """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
                """<Relationship Id="rel0" Target="/$MODEL_PART" Type="$MODEL_RELATIONSHIP"/>""" +
                "</Relationships>\n",
        )
        zip.add(MODEL_PART, model(scene))
        return zip.toByteArray()
    }

    /** The `3dmodel.model` part: the resources (one object per body) and the build that places them. */
    internal fun model(scene: ExportScene): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        // the unit, stated — the whole reason this format is worth writing rather than an STL
        sb.append("""<model unit="${scene.unit.threeMfName}" xml:lang="en-US" xmlns="$CORE_NAMESPACE">""").append('\n')
        sb.append("""<metadata name="Application">${esc(Glb.GENERATOR)}</metadata>""").append('\n')
        sb.append("""<metadata name="Title">${esc(scene.name)}</metadata>""").append('\n')
        sb.append("<resources>").append('\n')
        for ((i, n) in scene.nodes.withIndex()) {
            // object ids are positive integers and unique in the package; the *name* is the drawing's own
            // (OP-18), so a slicer's object list reads like the element tree
            sb.append("""<object id="${i + 1}" type="model" name="${esc(n.name)}">""").append('\n')
            sb.append("<mesh>").append('\n').append("<vertices>").append('\n')
            for (p in n.mesh.vertices) {
                sb.append("""<vertex x="${dec(p.x)}" y="${dec(p.y)}" z="${dec(p.z)}"/>""").append('\n')
            }
            sb.append("</vertices>").append('\n').append("<triangles>").append('\n')
            for (t in n.mesh.triangles) {
                sb.append("""<triangle v1="${t.a}" v2="${t.b}" v3="${t.c}"/>""").append('\n')
            }
            sb.append("</triangles>").append('\n').append("</mesh>").append('\n').append("</object>").append('\n')
        }
        sb.append("</resources>").append('\n').append("<build>").append('\n')
        for (i in scene.nodes.indices) sb.append("""<item objectid="${i + 1}"/>""").append('\n')
        sb.append("</build>").append('\n').append("</model>").append('\n')
        return sb.toString()
    }

    /**
     * A coordinate as 3MF wants it: a plain decimal, never scientific notation (the spec's number type is
     * XML `double`, which permits an exponent, but slicers in the wild have choked on it and a canonical
     * spelling is worth more than the two bytes). [Glb.num]'s rule, reused — one number format for this
     * package.
     */
    private fun dec(v: Double): String = Glb.num(v)

    /** XML text escaping, for a name the user chose. */
    private fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
