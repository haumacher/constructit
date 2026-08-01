package constructit.exchange

import constructit.editor.Appearance
import constructit.editor.Mat4
import constructit.geom.Mesh3
import de.haumacher.kotlinjt.scene.Color
import de.haumacher.kotlinjt.scene.SceneNode
import de.haumacher.kotlinjt.write.writeJt
import de.haumacher.kotlinjt.scene.LengthUnit as JtLengthUnit
import de.haumacher.kotlinjt.scene.Mat4 as JtMat4
import de.haumacher.kotlinjt.scene.Material as JtMaterial
import de.haumacher.kotlinjt.scene.Mesh as JtMesh
import de.haumacher.kotlinjt.scene.Scene as JtScene
import de.haumacher.kotlinjt.scene.Vec3 as JtVec3

/**
 * **JT (ISO 14306) — the CAD-interchange writer, and the one export this project does not write itself.**
 *
 * Everything below the [ExportScene] seam is the sibling library's (`de.haumacher.kotlinjt`, wired in as a
 * Gradle composite build): its Layer 2 `Scene` façade was designed as *this* handoff — named nodes, local
 * transforms, indexed-triangle meshes, simple materials, units explicit in the model — so this file is an
 * adapter and nothing else. No geometry, no encoding, no format knowledge: `ExportScene` in,
 * `de.haumacher.kotlinjt.scene.Scene` out, `writeJt` does the rest. That is what "the adapter is a page when
 * the library exists" meant (OP-9).
 *
 * **Structure.** A root node named after the drawing, one child per [ExportNode] named by the naming authority
 * (OP-18) — the drawing's own name, the one a viewer's tree must show. The split is not decoration: the
 * library's writer *refuses* a node that carries geometry **and** children (its reader would hand the geometry
 * back on an extra unnamed child), so structure lives on the root and meshes live on leaves — exactly the shape
 * this scene already has.
 *
 * **Units and axes.** `LengthUnit.MILLIMETERS` is **declared** in the file (JT's own
 * `JT_PROP_MEASUREMENT_UNITS`), and the coordinates go out world-space, Z-up, in millimetres, unconverted.
 * Unlike GLB there is no root transform to apply, and the reason is a property of the format: **JT declares no
 * up-axis**. glTF says "+Y-up, metres" normatively, so [Glb] converts once at the root; JT says only what unit
 * its numbers are in, so converting anything here would be inventing a convention the file cannot state. Every
 * vertex in a JT written from this app is therefore the model's own millimetre number.
 *
 * **Normals: computed, from the one authority.** The kernel's [Mesh3] stores none, and the
 * library's `Mesh` does allow an empty normal list (all corner normal indices −1) — the "honest" option, since
 * the engine binds none. It is not the one taken, and the reason is that the choice is not between data and no
 * data but between *our* shading answer and *a viewer's*: JT's installed base binds per-vertex normals on every
 * shape, so a reader handed none must invent them, and what it invents is per-facet — a tessellated bore in
 * strips, where the GLB and the in-app preview show it smooth. So JT gets the same [RenderMesh] every other
 * consumer gets: crease-threshold normals at `Scene3.CREASE_ANGLE_RAD`, the same 30° the 3D view draws feature
 * edges at. One authority, so what the preview shows is what *all* exported files show. Nothing is invented —
 * the normals are a function of the mesh, computed the same way for every consumer, and the positions crossing
 * over are the kernel's own numbers.
 *
 * **What JT cannot carry, stated so it is not looked for**: **metalness**. A JT material is Phong; the library
 * maps roughness onto the shininess exponent and back (exactly, to well under 1e-5), and deliberately does not
 * encode metallic, because classic JT has no metalness concept and deriving one from specular chroma would be
 * a guess. So a Tier-1 metalness of 0.9 reads back as 0 from a JT file. That is the format's limit, not a
 * defect of this adapter — recorded here the way STL's loss of names and materials is recorded on [Stl].
 */
object Jt {
    /**
     * [scene] as the library's Layer 2 scene — the whole of the adapter.
     *
     * Separate from [write] because it is the interesting half: a test can inspect the structure, the unit, the
     * names, the transforms and the materials without going through the bytes, and the bytes are the library's
     * business, tested there.
     */
    fun scene(scene: ExportScene): JtScene =
        JtScene(
            units = unitOf(scene.unit),
            root =
                SceneNode(
                    name = scene.name,
                    transform = JtMat4.IDENTITY,
                    meshes = emptyList(),
                    polylines = emptyList(),
                    material = null,
                    children = scene.nodes.map(::nodeOf),
                ),
            notes = emptyList(),
        )

    /** [scene] as JT file bytes. Throws `JtWriteException` for a scene the library will not misrepresent. */
    fun write(scene: ExportScene): ByteArray = writeJt(scene(scene))

    private fun nodeOf(node: ExportNode): SceneNode =
        SceneNode(
            name = node.name,
            transform = transformOf(node.transform),
            meshes = listOf(meshOf(node.mesh)),
            polylines = emptyList(),
            material = materialOf(node.material),
            children = emptyList(),
        )

    /**
     * The scene's unit as the library's — a `when` rather than a name lookup, so a second member added to
     * [LengthUnit] fails to compile here instead of silently exporting the wrong declaration.
     */
    private fun unitOf(unit: LengthUnit): JtLengthUnit =
        when (unit) {
            LengthUnit.MILLIMETRE -> JtLengthUnit.MILLIMETERS
        }

    /**
     * A node's placement, **element for element** — and that is a result, not an assumption.
     *
     * The two libraries state opposite conventions: [constructit.editor.Mat4] is column-major with the
     * *column*-vector convention (`p' = M · p`, `transform` reads `m[0]·x + m[4]·y + m[8]·z + m[12]`), while
     * the library's `Mat4` is row-major with the *row*-vector convention (`p' = p · M`, `transformPoint` reads
     * `x·m[0] + y·m[4] + z·m[8] + m[12]`). Transposing the convention and transposing the storage cancel: the
     * two read the same sixteen numbers the same way, translation lands in elements 12–14 in both, and a copy
     * is the correct conversion. (It is the same flat layout glTF uses, which is why the library's own KDoc
     * says its matrices interchange without transposition.) `JtExportTest` proves it on a matrix that is not
     * the identity, because "identity survives" would prove nothing.
     */
    private fun transformOf(transform: Mat4): JtMat4 = JtMat4(transform.m.toList())

    /**
     * The mesh with [RenderMesh]'s normals. Positions and normals are in lockstep (corner *i* names position
     * *i* and normal *i*), which forgoes the separate indexing the library's `Mesh` allows — deliberately: its
     * writer expands every mesh to per-corner records anyway, so dual indexing would save nothing on the wire
     * while duplicating the crease logic that already has one home.
     */
    private fun meshOf(mesh: Mesh3): JtMesh {
        val render = RenderMesh.of(mesh)
        val positions = ArrayList<JtVec3>(render.vertexCount)
        val normals = ArrayList<JtVec3>(render.vertexCount)
        for (i in 0 until render.vertexCount) {
            positions.add(
                JtVec3(
                    render.positions[i * 3].toFloat(),
                    render.positions[i * 3 + 1].toFloat(),
                    render.positions[i * 3 + 2].toFloat(),
                ),
            )
            normals.add(
                JtVec3(
                    render.normals[i * 3].toFloat(),
                    render.normals[i * 3 + 1].toFloat(),
                    render.normals[i * 3 + 2].toFloat(),
                ),
            )
        }
        val triangles = ArrayList<JtMesh.Triangle>(render.triangleCount)
        for (t in 0 until render.triangleCount) {
            val a = render.indices[t * 3]
            val b = render.indices[t * 3 + 1]
            val c = render.indices[t * 3 + 2]
            triangles.add(JtMesh.Triangle(a, b, c, a, b, c))
        }
        return JtMesh(positions, normals, triangles)
    }

    /**
     * Tier-1 appearance as the library's material: the **linear** base colour [Appearance.linearRgb] hands out
     * — the same numbers the GLB's `baseColorFactor` carries, from the one place the hex is parsed and the sRGB
     * transfer function is applied — opaque (the record models no transparency), with roughness and metalness
     * clamped to the range both specs define them on. Metalness is passed through even though JT drops it: the
     * scene says what the drawing says, and what the format cannot keep is the format's statement to make.
     */
    private fun materialOf(appearance: Appearance): JtMaterial {
        val rgb = appearance.linearRgb()
        return JtMaterial(
            baseColor = Color(rgb[0].toFloat(), rgb[1].toFloat(), rgb[2].toFloat(), 1f),
            roughness = appearance.roughnessClamped.toFloat(),
            metallic = appearance.metallicClamped.toFloat(),
        )
    }
}
