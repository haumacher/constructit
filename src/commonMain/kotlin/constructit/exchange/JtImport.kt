package constructit.exchange

import constructit.editor.Appearance
import constructit.geom.Curves3
import constructit.geom.Mesh3
import constructit.geom.Path3
import constructit.geom.Tri
import constructit.geom.Vec3
import constructit.geom.Xform3
import constructit.geom.movedBy
import de.haumacher.kotlinjt.scene.LengthUnit as JtLengthUnit
import de.haumacher.kotlinjt.scene.Mat4 as JtMat4
import de.haumacher.kotlinjt.scene.Material as JtMaterial
import de.haumacher.kotlinjt.scene.Mesh as JtMesh
import de.haumacher.kotlinjt.scene.PolylineSet as JtPolylineSet
import de.haumacher.kotlinjt.scene.Scene as JtScene
import de.haumacher.kotlinjt.scene.SceneNode as JtSceneNode

/**
 * **JT (ISO 14306) — the reading half, and the exact mirror of [Jt].**
 *
 * Everything below the seam is the sibling library's (`de.haumacher.kotlinjt`): `readScene` hands over the
 * same format-agnostic scene the writer takes — named nodes, local transforms, indexed-triangle meshes,
 * simple materials, units explicit in the model — so this file is an adapter and nothing else. No decoding,
 * no format knowledge: a `de.haumacher.kotlinjt.scene.Scene` in, [JtBody]s and [JtWire]s out, and the document
 * work happens in [Imports].
 *
 * Five things it decides, each of which could have gone the other way:
 *
 * **A wireframe-only part is a body of the drawing, not a note** (OP-26, step 9). JT files carry plenty of
 * them — sketches, centrelines, construction curves — and until that step they were skipped and named. They
 * come in as [JtWire]s: the file's own polylines, under the identical contract a mesh gets. What has not
 * changed is that nothing is *inferred* from them — the file said points, and points are what the drawing
 * gets.
 *
 * **One body per geometry-bearing *path*, not per node.** The library shares instanced subtrees, so the ten
 * instances of one bolt are ten paths through the *same* `SceneNode` object — and they are ten bodies,
 * because each carries its own placement. Which is also why the mesh is stored ten times: this model has no
 * instance concept for imported geometry, and pretending it did by sharing one step between ten placements
 * would be a construction the file cannot state (recorded as a future extension, not smuggled in).
 *
 * **The finest LOD, and only it** (`LodPolicy.FINEST_ONLY`). A drawing holds one mesh per body; picking a
 * coarser tier would be a display decision baked into the model, which is precisely the kind of decision
 * OP-9 keeps out of the stored form.
 *
 * **Positions are welded by exact equality.** JT stores positions and normals separately indexed, and a
 * writer that emitted one vertex per (position, normal) pair — which is what [RenderMesh] does, so it is
 * what *our own* files look like — hands back a mesh whose faces share no indices at all. Welding coincident
 * positions is therefore not a repair: it is the inverse of that split, exact because the numbers are
 * identical, and without it every body in every file would fail the watertight gate for a reason that is
 * about indexing rather than about geometry.
 *
 * **A non-rigid node transform is baked, and said.** A placement is a rigid motion (see [Xform3]); a scale,
 * a shear or a mirror is not, so it cannot become one — and silently approximating it as one would move
 * somebody's geometry. It is applied to the vertices instead, that body arrives with an identity pose, and
 * the note says so.
 */
object JtImport {
    /**
     * One body a JT file offers: [mesh] in the body's **own** coordinates in millimetres, at the file's
     * [pose], with whatever the file said about its [name] and [material].
     */
    class JtBody(
        val name: String,
        val mesh: Mesh3,
        val pose: Xform3,
        val material: Appearance?,
        /** What had to be decided about this body on the way in, or null when nothing did. */
        val note: String?,
    )

    /**
     * One **wireframe part** a JT file offers (OP-26, step 9): its [runs] in the part's **own** coordinates in
     * millimetres, at the file's [pose], with whatever the file said about its [name] and [material].
     *
     * The exact twin of [JtBody] and deliberately a separate type rather than a body with a nullable mesh: a
     * run and a body are two different values of this engine (a `Path3` and a `Mesh3`) and become two
     * different element kinds, so a single type carrying "one or the other" would push that choice into every
     * consumer.
     *
     * **Several runs, one part**, which is why this is a part rather than a run: a `PolylineSet` is a shared
     * point pool with one index run per polyline, and a `Path3` is a *single* chain, so disjoint polylines
     * cannot be one value. They are one part all the same — so [Imports] gives them **one anchor point and one
     * angle between them**, and dragging it moves the whole wireframe. Sharing a node *is* equality (OP-5);
     * there is nothing to build for it.
     */
    class JtWire(
        val name: String,
        val runs: List<Path3>,
        val pose: Xform3,
        val material: Appearance?,
        val note: String?,
    )

    /** What a scene offered: the bodies, the wireframe parts, and what was skipped on the way. */
    class JtBodies(
        val bodies: List<JtBody>,
        val wires: List<JtWire>,
        val notes: List<String>,
    )

    /**
     * How many millimetres one of [unit]'s units is, or null when the file declares none.
     *
     * Null is the honest answer and the caller refuses on it: `UNSPECIFIED` means the file states no unit,
     * and a length with no unit is not a length. A "state the unit" prompt would be the friendlier answer
     * and is recorded as a refinement — but a *default* would be this importer inventing a scale, which is
     * the one thing it must not do.
     */
    fun millimetresPer(unit: JtLengthUnit): Double? = unit.metersPerUnit?.let { it * 1000.0 }

    /** The bodies of [scene], scaled to millimetres by [mmPerUnit]. */
    fun bodies(
        scene: JtScene,
        mmPerUnit: Double,
    ): JtBodies {
        val out = ArrayList<JtBody>()
        val wires = ArrayList<JtWire>()
        val notes = ArrayList<String>()
        // How many geometry-bearing nodes have been *met*, which is what the positional stand-in name below
        // counts — meshes and wireframe alike, in one sequence. Deliberately not `out.size`: that counts the
        // bodies alone, so back when a wireframe part was skipped every one of them read the same number and
        // five different parts of one file all came out called `body12`. They are imported now (OP-26, step 9)
        // and the rule is unchanged for the reason it was made: a number that names a position in the **file**
        // is the only thing about an unnamed node that is unique, whatever the walk goes on to do with it.
        var met = 0
        // the library's own honesty contract, carried through unchanged: what it could not represent
        // faithfully is what this import could not either
        for (n in scene.notes) notes.add("the file says: ${n.message}")

        fun walk(
            node: JtSceneNode,
            world: JtMat4,
        ) {
            val here = node.transform * world
            val mesh = node.meshes.firstOrNull()
            // **One name per geometry-bearing node, whichever kinds of geometry it bears.** The two cases are
            // asked separately rather than as an either/or: the library's writer refuses to emit a node with
            // triangles *and* polylines, but a file it did not write may carry one, and taking only the mesh
            // would drop that node's wireframe **silently** — the one thing an import may never do. They share
            // the node's name because they are one node.
            val name = if (mesh != null || node.polylines.isNotEmpty()) nameOf(node, met++) else ""
            if (mesh != null) {
                out.add(bodyOf(name, mesh, here, node.material, mmPerUnit))
            }
            if (node.polylines.isNotEmpty()) {
                // **A wireframe part comes in as runs** (OP-26, step 9). JT carries plenty of them —
                // sketches, centrelines, construction curves — and they used to be skipped and named. They are
                // now curves of the drawing under the identical contract an imported body has: a frozen
                // literal with a parametric placement, offering no construction inputs. The finest tier only,
                // for [bodies]'s own reason: the tiers are LODs, and picking a coarse one would bake a display
                // decision into the model.
                wireOf(name, node.polylines.first(), here, node.material, mmPerUnit)?.let { wires.add(it) }
            }
            for (c in node.children) walk(c, here)
        }
        walk(scene.root, JtMat4.IDENTITY)
        return JtBodies(out, wires, notes)
    }

    /**
     * One wireframe part from its [set]: one run per index run, each a **polyline** through the points the
     * file listed — no fitting, no smoothing, no arc recognition. Null when nothing in it is a run.
     *
     * A line of fewer than two points is dropped rather than named: it is not a run at all, and the format's
     * own contract already says every polyline has at least two. A line whose consecutive points coincide is
     * kept as the file wrote it — a zero-length piece is what the *value* says, and this adapter does not
     * repair geometry (the rule [meshOf] follows for a degenerate triangle is the same one).
     *
     * A **non-rigid transform is baked and said**, exactly as [bodyOf] does it and for the same reason: a
     * scale, a shear or a mirror is not a placement, so it cannot become one.
     */
    private fun wireOf(
        name: String,
        set: JtPolylineSet,
        world: JtMat4,
        material: JtMaterial?,
        mmPerUnit: Double,
    ): JtWire? {
        val pose = xformOf(world, mmPerUnit)
        val rigid = pose.isRigid()
        val out = ArrayList<Path3>()
        for (line in set.lines) {
            val pts =
                line.mapNotNull { i ->
                    set.positions.getOrNull(i)?.let { Vec3(it.x.toDouble() * mmPerUnit, it.y.toDouble() * mmPerUnit, it.z.toDouble() * mmPerUnit) }
                }
            if (pts.size < 2) continue
            // a run whose last point *is* its first is a closed one, and closure is **structure** (OP-21): it
            // is said by the file's own index run, never measured back off the geometry afterwards
            val closed = pts.size >= 4 && pts.first() == pts.last()
            val open = if (closed) pts.dropLast(1) else pts
            val path = Path3(Curves3.straightThrough(open, closed), closed)
            if (path.elements.isEmpty()) continue
            out.add(if (rigid) path else path.movedBy(pose))
        }
        if (out.isEmpty()) return null
        val note =
            if (rigid) {
                null
            } else {
                "$name carries a transform that scales, shears or mirrors it, which is not a placement — " +
                    "it was applied to the run's own points instead, so it cannot be re-placed from it"
            }
        return JtWire(name, out, if (rigid) pose else Xform3.IDENTITY, appearanceOf(material), note)
    }

    /**
     * A node's name, or a positional stand-in — a file may leave a shape node unnamed, and this one leaves
     * *every* one of them unnamed.
     *
     * [index] counts the geometry-bearing nodes this walk has met, imported or not, so the stand-in names a
     * position in **the file** rather than a position in the result. That is what makes it unique: two parts
     * of one file can never share it, whichever of them the import went on to take.
     */
    private fun nameOf(
        node: JtSceneNode,
        index: Int,
    ): String = node.name.ifBlank { "body${index + 1}" }

    private fun bodyOf(
        name: String,
        mesh: JtMesh,
        world: JtMat4,
        material: JtMaterial?,
        mmPerUnit: Double,
    ): JtBody {
        val local = meshOf(mesh, mmPerUnit)
        val pose = xformOf(world, mmPerUnit)
        if (pose.isRigid()) return JtBody(name, local, pose, appearanceOf(material), null)
        // not a placement, so it cannot become one — applied to the vertices, and said
        return JtBody(
            name,
            local.movedBy(pose),
            Xform3.IDENTITY,
            appearanceOf(material),
            "$name carries a transform that scales, shears or mirrors it, which is not a placement — " +
                "it was applied to the body's own vertices instead, so the body cannot be re-placed from it",
        )
    }

    /**
     * A node's world transform as this engine's, in millimetres.
     *
     * Two conversions in one, and both are the mirror of what [Jt] records. The library's `Mat4` is
     * **row-major with the row-vector convention** (`p' = p · M`, translation in elements 12–14) while
     * [Xform3] is `p' = R·p + t` — so `R[i][j]` is the library's `m[j*4 + i]`, which is the transpose the
     * two conventions cancel down to. And the **unit**: a coordinate scales by [mmPerUnit], so the
     * translation does too while the rotation does not — a uniform scale commutes with the rotation, which
     * is exactly why the body's own vertices can be scaled independently and the pose still composes.
     *
     * The projective row (`m[3]`, `m[7]`, `m[11]`, `m[15]`) is dropped rather than honoured: a perspective
     * component is not an affine map at all, so a file carrying one produces a matrix [Xform3.isRigid]
     * rejects, and the caller bakes and says so.
     */
    fun xformOf(
        m: JtMat4,
        mmPerUnit: Double,
    ): Xform3 {
        val v = m.values
        return Xform3(
            doubleArrayOf(
                v[0], v[4], v[8],
                v[1], v[5], v[9],
                v[2], v[6], v[10],
                v[12] * mmPerUnit, v[13] * mmPerUnit, v[14] * mmPerUnit,
            ),
        )
    }

    /**
     * The library's dual-indexed mesh as this engine's [Mesh3]: positions scaled to millimetres and
     * **welded by exact equality**, triangles re-indexed onto the welded set, normals dropped.
     *
     * Normals are dropped because they are not the kernel's to hold: [Mesh3] stores none and [RenderMesh]
     * computes them from the geometry on the way *out*, at one crease threshold shared by the 3D view, the
     * preview and every writer. Keeping a file's normals would give an imported body a second shading
     * authority — the one thing the export package went out of its way not to have.
     *
     * Deterministic: positions are welded in index order, first occurrence wins, so the same file always
     * yields the same mesh (the rule [Mesh3] itself states).
     */
    fun meshOf(
        mesh: JtMesh,
        mmPerUnit: Double,
    ): Mesh3 {
        val index = HashMap<Triple<Int, Int, Int>, Int>(mesh.positions.size * 2)
        val vertices = ArrayList<Vec3>(mesh.positions.size)
        val map = IntArray(mesh.positions.size)
        for ((i, p) in mesh.positions.withIndex()) {
            // the key is the three raw bit patterns, so "the same position" means *the same numbers* and
            // never "near enough" — a tolerance here would weld distinct geometry together
            val key = Triple(p.x.toRawBits(), p.y.toRawBits(), p.z.toRawBits())
            map[i] =
                index.getOrPut(key) {
                    vertices.add(Vec3(p.x.toDouble() * mmPerUnit, p.y.toDouble() * mmPerUnit, p.z.toDouble() * mmPerUnit))
                    vertices.size - 1
                }
        }
        val triangles = ArrayList<Tri>(mesh.triangles.size)
        for (t in mesh.triangles) {
            val a = map[t.v0]
            val b = map[t.v1]
            val c = map[t.v2]
            // A triangle whose corners weld together has no area, and a zero-area facet is not part of a
            // surface — it is dropped here rather than left for the watertight gate to refuse the whole
            // body over, which would refuse it for the wrong reason. If dropping one genuinely opens the
            // surface, the gate still says so, by name.
            if (a != b && b != c && a != c) triangles.add(Tri(a, b, c))
        }
        return Mesh3(vertices, triangles)
    }

    /**
     * A JT material as Tier-1 appearance: the library hands out a **linear** base colour (the same number
     * [Appearance.linearRgb] produces), a roughness it derives from the Phong shininess, and a metalness
     * that is **always 0** — classic JT has no metalness concept and the library refuses to invent one.
     *
     * So a round trip loses metalness and nothing else, which is the format's statement rather than this
     * adapter's: [Jt]'s own note records the loss on the way out, and the test asserts it on the way back.
     */
    private fun appearanceOf(material: JtMaterial?): Appearance? {
        val m = material ?: return null
        return Appearance(
            color = Appearance.hexOfLinear(doubleArrayOf(m.baseColor.r.toDouble(), m.baseColor.g.toDouble(), m.baseColor.b.toDouble())),
            roughness = m.roughness.toDouble(),
            metallic = m.metallic.toDouble(),
        )
    }
}
