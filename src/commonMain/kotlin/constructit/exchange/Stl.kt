package constructit.exchange

import constructit.geom.Vec3

/**
 * **Binary STL** — the universal fallback, riding along off the same triangles.
 *
 * The format is fifty bytes per facet and nothing else: no units (millimetres by universal convention, which
 * is what every slicer assumes and what this engine's numbers already are), no indices, no names, no
 * materials, no structure. Every body in the scene lands in **one** triangle soup, because that is all the
 * format can hold — which is exactly why 3MF is the printing recommendation and this is the fallback.
 *
 * Little-endian throughout, an 80-byte header, a facet count, then per facet: a normal, three corners, and a
 * two-byte attribute field that stays zero (the "colour" conventions layered onto it are mutually
 * incompatible, so writing anything there would be guessing at a reader).
 */
object Stl {
    /** Header, count, and 50 bytes per facet: the whole size of the file, computable in advance. */
    fun sizeOf(triangles: Int): Int = 84 + 50 * triangles

    fun write(scene: ExportScene): ByteArray {
        val tris = scene.triangleCount
        val out = ByteSink(sizeOf(tris))
        // The 80-byte header is free-form text by convention. It names the app and the drawing, because a
        // nameless STL on a print-farm machine is a mystery — the one piece of provenance the format allows.
        // Truncated rather than wrapped, and never starting with "solid": a binary file whose first five bytes
        // spell that word is read as ASCII STL by some tools.
        val header = "ConstructIt ${scene.name} (mm)"
        val bytes = ByteSink.ascii(header)
        for (i in 0 until 80) out.u8(if (i < bytes.size) bytes[i].toInt() else 0)
        out.u32(tris)
        for (n in scene.nodes) {
            val v = n.mesh.vertices
            for (t in n.mesh.triangles) {
                val a = v[t.a]
                val b = v[t.b]
                val c = v[t.c]
                // one normal per facet, from the facet's own winding — the mesh is outward-wound (OP-9), so
                // this is the outward normal and no orientation decision is taken here
                val cross = (b - a).cross(c - a)
                val nrm = if (cross.length() <= Vec3.EPS) Vec3.ZERO else cross.normalized()
                out.f32(nrm.x).f32(nrm.y).f32(nrm.z)
                out.f32(a.x).f32(a.y).f32(a.z)
                out.f32(b.x).f32(b.y).f32(b.z)
                out.f32(c.x).f32(c.y).f32(c.z)
                out.u16(0) // attribute byte count: no colour convention is written
            }
        }
        return out.toByteArray()
    }
}
