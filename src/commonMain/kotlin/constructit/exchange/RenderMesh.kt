package constructit.exchange

import constructit.editor.Scene3
import constructit.geom.Mesh3
import constructit.geom.Vec3
import kotlin.math.cos

/**
 * A mesh with **normals**, ready for a renderer: flat arrays of positions and normals plus the index list —
 * the shape both `THREE.BufferGeometry` and a glTF primitive want, field for field.
 *
 * This is the one place normals are computed, and it is shared on purpose: the GLB writer and the in-app
 * preview must shade the same solid the same way, or "what the preview shows is what the exported GLB shows"
 * is not true by construction but by coincidence. 3MF and STL do not come through here — 3MF stores no
 * normals at all and STL stores one per *facet*, computed from the facet's own corners.
 */
class RenderMesh(
    /** `x, y, z` per vertex, in millimetres — the scene's unit, unconverted. */
    val positions: DoubleArray,
    /** A unit normal per vertex, same order. */
    val normals: DoubleArray,
    val indices: IntArray,
) {
    val vertexCount: Int get() = positions.size / 3
    val triangleCount: Int get() = indices.size / 3

    /** Component-wise minimum and maximum of [positions] — what a glTF POSITION accessor must state. */
    fun bounds(): Pair<DoubleArray, DoubleArray> {
        if (positions.isEmpty()) return DoubleArray(3) to DoubleArray(3)
        val lo = doubleArrayOf(positions[0], positions[1], positions[2])
        val hi = doubleArrayOf(positions[0], positions[1], positions[2])
        var i = 0
        while (i < positions.size) {
            for (k in 0..2) {
                val v = positions[i + k]
                if (v < lo[k]) lo[k] = v
                if (v > hi[k]) hi[k] = v
            }
            i += 3
        }
        return lo to hi
    }

    companion object {
        /**
         * [mesh] with normals, **smooth where the surface is smooth and sharp where it is not**.
         *
         * A solid's mesh shares vertices between faces that meet at a right angle (a cube has eight of them),
         * so neither naive answer is acceptable: averaging every incident face's normal rounds a cube's
         * corners, and one normal per facet turns a tessellated bore into a barrel of visible strips. The
         * standard answer is a crease threshold — a corner's normal averages only the incident faces whose own
         * normal is within [thresholdRad] of *its* face's — and the threshold is not a new number: it is
         * [Scene3.CREASE_ANGLE_RAD], the same 30° the 3D view already draws feature edges at, chosen there
         * against exactly this trade-off (see its table). One authority, so a crease the editing view draws a
         * line along is a crease the preview and the GLB shade as one.
         *
         * Area-weighted, which is what makes the average honest where a fan of thin facets meets one big one.
         *
         * **Deterministic.** Corners are visited in triangle order; each corner's incident faces are visited in
         * ascending triangle index; and output vertices are emitted the first time a `(vertex, normal)` pair is
         * met. So the arrays are a pure function of the mesh — a hash map is used for lookup only, never
         * iterated, the rule [Mesh3] itself obeys.
         */
        fun of(
            mesh: Mesh3,
            thresholdRad: Double = Scene3.CREASE_ANGLE_RAD,
        ): RenderMesh {
            val tris = mesh.triangles
            val v = mesh.vertices
            // per-triangle area-weighted normal (the raw cross product *is* twice the area times the unit
            // normal, so no separate weight is needed) plus the unit one the threshold test compares
            val weighted = ArrayList<Vec3>(tris.size)
            val unit = ArrayList<Vec3>(tris.size)
            for (t in tris) {
                val n = (v[t.b] - v[t.a]).cross(v[t.c] - v[t.a])
                weighted.add(n)
                unit.add(if (n.length() <= Vec3.EPS) Vec3.ZERO else n.normalized())
            }
            // vertex -> incident triangles, in ascending order by construction
            val incident = HashMap<Int, MutableList<Int>>(v.size * 2)
            for ((i, t) in tris.withIndex()) {
                for (c in listOf(t.a, t.b, t.c)) incident.getOrPut(c) { ArrayList(6) }.add(i)
            }
            val cosLimit = cos(thresholdRad)
            val positions = ArrayList<Double>(v.size * 3)
            val normals = ArrayList<Double>(v.size * 3)
            val indices = IntArray(tris.size * 3)
            val emitted = HashMap<String, Int>(v.size * 2)
            var out = 0
            for ((i, t) in tris.withIndex()) {
                val face = unit[i]
                for ((corner, vi) in listOf(t.a, t.b, t.c).withIndex()) {
                    var sum = Vec3.ZERO
                    for (j in incident[vi] ?: emptyList<Int>()) {
                        if (unit[j].dot(face) < cosLimit) continue
                        sum += weighted[j]
                    }
                    val n = if (sum.length() <= Vec3.EPS) face else sum.normalized()
                    val p = v[vi]
                    val key = "$vi|${key(n.x)},${key(n.y)},${key(n.z)}"
                    val at =
                        emitted.getOrPut(key) {
                            positions.add(p.x)
                            positions.add(p.y)
                            positions.add(p.z)
                            normals.add(n.x)
                            normals.add(n.y)
                            normals.add(n.z)
                            out++
                        }
                    indices[i * 3 + corner] = at
                }
            }
            return RenderMesh(positions.toDoubleArray(), normals.toDoubleArray(), indices)
        }

        /**
         * A normal component as a **key**: rounded to a millionth, so two corners that computed the same
         * direction through different arithmetic still share one output vertex. Only ever a lookup key — the
         * number written to the file is the unrounded one.
         */
        private fun key(c: Double): Long = kotlin.math.round(c * 1e6).toLong()
    }
}
