package constructit.exchange

import constructit.core.Evaluator
import constructit.core.Node
import constructit.core.SolidValue
import constructit.dsl.valueOf
import constructit.editor.Appearance
import constructit.editor.Document
import constructit.editor.ElementKind
import constructit.editor.Mat4
import constructit.geom.Axis3
import constructit.geom.Mesh3

/**
 * One exportable body: a **named node** carrying an indexed triangle mesh and one material.
 *
 * [mesh] is the kernel's own mesh object, handed over by reference and never copied, re-tessellated or
 * repaired (OP-9's sink rule, OP-5's memo). Two consequences, both load-bearing: an export is free of
 * geometry work — it is a re-encoding — and **mesh identity says whether a body changed**, which is what lets
 * the in-app preview re-upload only the buffers that moved.
 */
class ExportNode(
    /**
     * The drawing's one name for this body: the name the user gave it, else its script name (OP-18's
     * naming authority — the [Document.displayName] preference, chosen in `extract`'s `exportName`).
     */
    val name: String,
    val mesh: Mesh3,
    val material: Appearance,
    /**
     * Where this node's mesh sits relative to the scene root.
     *
     * Identity for every node this document produces, because the kernel emits **world-space** meshes: a
     * solid's vertices are already in the drawing's millimetres. The field is not decoration — it is the
     * scene's statement that geometry and placement are separate, which is what the root transform of the GLB
     * writer *uses* (mm→m and Z-up→+Y-up, applied once, at the root) and what a structure-tree consumer such
     * as the JT sibling project writes per node. Nothing here invents a placement that the model does not
     * hold.
     */
    val transform: Mat4 = Mat4.IDENTITY,
) {
    val triangleCount: Int get() = mesh.triangleCount
    val vertexCount: Int get() = mesh.vertexCount
}

/**
 * **The neutral scene** — the one seam every export and the in-app preview reads, and the only thing they
 * read.
 *
 * A format-agnostic value: named nodes, transforms, indexed triangle meshes, one simple material per node,
 * and — stated *in the model* rather than left to convention — the [unit] its numbers are in and which axis
 * is [up]. Four consumers already (GLB, 3MF, binary STL, the three.js preview) and a fifth agreed as a
 * separate project (the kotlinJT library's scene façade), which is why the seam exists at all: a consumer
 * that reads the document directly would have to re-decide what counts as an exportable body, and five
 * answers to that question is five different files out of one drawing.
 *
 * What it contains, and what it refuses, is decided once here:
 * - **Solids only.** An export writes bodies; a construction line is not a body. Nothing else is a candidate.
 * - **Visible solids only.** A hidden solid is a recorded decision about the drawing (OP-18's visibility
 *   reversal), so it is not exported — and it is *named in [notes]*, because a body silently missing from a
 *   file is the kind of surprise an export must not spring.
 * - **Valid solids only.** An invalid solid contributes nothing (OP-3) and is named in [notes] too.
 * - **Outputs, not material.** A solid another visible solid is made *of* — a boolean's operand, the
 *   counterbore's cylinder — is that solid's construction material, not a body of its own
 *   (`Document.isMaterial`, the rule the 3D view already follows). A drilled part therefore exports as **one**
 *   body, and the operand stack is not noted: it was never an output.
 *
 * [notes] is the whole of what an export has to say. **Silence means success**: a scene with nodes and no
 * notes exported everything the drawing has.
 */
class ExportScene(
    /** What the drawing is called — the name of the root node and of the file the bytes go into. */
    val name: String,
    val nodes: List<ExportNode>,
    val notes: List<String> = emptyList(),
    /** The unit every coordinate in this scene is in: millimetres, the engine's canonical base (OP-7). */
    val unit: LengthUnit = LengthUnit.MILLIMETRE,
    /** Which world axis points up: **+Z**, because sketches live on world XY and extrude along its normal. */
    val up: Axis3 = Axis3.Z,
) {
    val isEmpty: Boolean get() = nodes.isEmpty()
    val triangleCount: Int get() = nodes.sumOf { it.triangleCount }

    /**
     * Why there is nothing to export, naming what was skipped — or null when there is.
     *
     * A refusal that says only "nothing to export" leaves the user hunting for a body that is right there in
     * the tree, hidden or invalid. So the notes ride the refusal: *"nothing to export — e5 is hidden"*.
     */
    val refusal: String?
        get() =
            if (nodes.isNotEmpty()) {
                null
            } else if (notes.isEmpty()) {
                "nothing to export: this drawing has no solid yet — trace an outline, then Extrude or Revolve"
            } else {
                "nothing to export: " + notes.joinToString("; ")
            }

    companion object {
        /**
         * The name an export speaks: the one the user gave ([Document.userNameOf], OP-18's renaming), else
         * the script name — the same preference [Document.displayName] encodes, because a viewer's tree is
         * for the person who named the part, not for the file format.
         */
        private fun exportName(
            doc: Document,
            el: constructit.editor.Element,
        ): String = doc.userNameOf(el) ?: doc.nameOf(el)

        /**
         * The exportable scene of [doc], under the drawing's [name].
         *
         * One pass over the elements, no caching and no geometry: everything here is read off evaluated nodes,
         * exactly as the 3D view's `Scene3.extract` does, so a scene cannot disagree with the model after an
         * edit.
         */
        fun extract(
            doc: Document,
            name: String = "drawing",
            ev: Evaluator = Evaluator(),
        ): ExportScene {
            val solids = doc.elements.filter { it.kind == ElementKind.SOLID }
            val visible = solids.filter { it.visible }
            // which solids are *material* for another visible one — the 3D view's rule, one authority
            // (`Document.isMaterial`): reached only along solid-valued inputs, so a face sketched on a plate
            // does not make the plate disappear, while a boolean consuming it does.
            val consumed = HashSet<String>()
            val visited = HashSet<String>()

            fun walk(node: Node) {
                if (!visited.add(node.id)) return
                for (input in node.inputs) {
                    if (!Document.isMaterial(ev, input)) continue
                    consumed.add(input.id)
                    walk(input)
                }
            }
            visible.filter { ev.valueOf(it.ref) is SolidValue }.forEach { walk(it.ref.node) }

            val nodes = ArrayList<ExportNode>()
            val notes = ArrayList<String>()
            for (el in solids) {
                if (el.ref.node.id in consumed) continue
                val mesh = (ev.valueOf(el.ref) as? SolidValue)?.solid?.mesh
                if (!el.visible) {
                    notes.add("${exportName(doc, el)} is hidden — not exported")
                    continue
                }
                if (mesh == null || mesh.triangles.isEmpty()) {
                    notes.add("${exportName(doc, el)} is invalid — not exported (its construction produced no solid)")
                    continue
                }
                nodes.add(ExportNode(exportName(doc, el), mesh, doc.materialOf(el)))
            }
            return ExportScene(name, nodes, notes)
        }
    }
}

/**
 * The unit a scene's numbers are in. One member, because the engine has one canonical length unit (mm) and a
 * second entry would be a conversion nobody performs — the *point* of the field is that the unit is stated in
 * the model instead of assumed by each reader, which is precisely what makes the GLB writer's mm→m scale and
 * the 3MF writer's `unit="millimeter"` derivations rather than folklore.
 */
enum class LengthUnit(
    /** How many millimetres one of these is. */
    val mm: Double,
    /** What the 3MF core spec calls this unit. */
    val threeMfName: String,
) {
    MILLIMETRE(1.0, "millimeter"),
}
