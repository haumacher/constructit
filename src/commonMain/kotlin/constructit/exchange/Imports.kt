package constructit.exchange

import constructit.editor.Appearance
import constructit.editor.Document
import constructit.editor.Element
import constructit.editor.Picks
import constructit.editor.Tools
import constructit.geom.Mesh3
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.geom.Xform3
import constructit.units.Quantity
import de.haumacher.kotlinjt.scene.LodPolicy
import de.haumacher.kotlinjt.scene.readScene
import kotlin.math.abs
import kotlin.math.atan2
import de.haumacher.kotlinjt.scene.Scene as JtScene

/**
 * The file kinds this build **reads into** a drawing. One entry per shipped reader and nothing "for later",
 * the same rule [ExportFormat] follows: a format flag with no reader behind it is a promise the app cannot
 * keep.
 */
enum class ImportFormat(
    val label: String,
    val extension: String,
) {
    JT("JT", "jt"),
}

/**
 * What an import produced: which bodies came in, which were **refused by name**, and everything else the
 * import had to say — with [message] the one line the status bar shows, and [refusal] set when nothing came
 * in at all.
 *
 * The shape mirrors [ExportResult] deliberately, because the honesty rule is the same in both directions:
 * silence means success, and a body that is quietly missing is the surprise the result exists to prevent.
 */
class ImportResult(
    val format: ImportFormat,
    val fileName: String,
    /** The bodies that became elements of the drawing, in file order, under the names they took. */
    val bodies: List<String>,
    /** Bodies the drawing would not take, each with the reason — the watertight gate, above all. */
    val refusals: List<String>,
    /** Everything else worth reading: the library's own notes, wireframe parts skipped, poses baked. */
    val notes: List<String>,
    val message: String,
    /** Why nothing at all was imported, or null when something was. */
    val refusal: String? = null,
) {
    val ok: Boolean get() = refusal == null
}

/**
 * **Import: bytes in, reference bodies out** — the mirror of [Exports], and the one entry point the browser
 * shell and the tests both call.
 *
 * Everything a caller could get wrong lives here rather than in the shell: which bodies a file offers, what
 * they are called, what units they are in, what the status line says and every refusal. The browser's whole
 * contribution is a file picker handing over bytes, which is why the import flow is headlessly testable end
 * to end — exactly as the export flow is.
 *
 * **What an imported body *is*, in a construction DAG.** A frozen solid literal with a parametric placement
 * — the tracing-paper model in 3D. The mesh is a literal because it has no construction: it came from
 * outside, so there is nothing to recompute and nothing to rediscover, and the drawing records the
 * triangles themselves (see [MeshText] for the encoding and for why the step never holds the file's bytes).
 * The *placement* is where the parameters are: a point and an angle in a sketch space, ordinary nodes like
 * every other, so an imported body can be dragged, wired, welded onto a construction and re-anchored to a
 * different plane. The file's own transform does not vanish into the vertices — it **initializes** that
 * placement, which is "a click is a choice, state restates as a value" applied to a file instead of to a
 * click.
 *
 * **What is refused, and by name.**
 * - A file with **no declared unit** — the whole file, because a length with no unit is not a length. (A
 *   "state the unit" prompt is the friendlier answer and is a recorded refinement, not a default: a
 *   *default* would be this importer inventing a scale.)
 * - A body that is **not watertight** — the same gate every constructed solid passes (OP-9's
 *   watertight-or-refused doctrine, checked through [ThreeMf]'s production twin of `assertManifold`). The
 *   rest of the file still imports, and the refusal names the body.
 * - **Bytes that are not a JT file at all**, with whatever the reader says about them.
 *
 * Nothing is repaired. A mesh this import would have to mend to accept is a mesh whose geometry it does not
 * understand, and OP-9's whole guarantee is that no such thing gets in.
 */
object Imports {
    /**
     * Read [bytes] into [doc] as reference bodies, under the provenance name [fileName].
     *
     * One journal step group per body — the literal, its anchor point, its angle, the placement, the name
     * and the material — which is exactly the set of steps a person could have produced by hand. The caller
     * takes **one** checkpoint over the whole call, so an import is one undo (see `Editor.importFile`).
     */
    fun import(
        doc: Document,
        bytes: ByteArray,
        fileName: String,
    ): ImportResult {
        val scene =
            try {
                readScene(bytes, LodPolicy.FINEST_ONLY)
            } catch (e: Exception) {
                return refused(
                    ImportFormat.JT,
                    fileName,
                    "not imported — this is not a readable JT file (${e.message ?: e.toString()})",
                )
            }
        return importScene(doc, scene, fileName)
    }

    /**
     * The same import, from an already-read [scene] — the seam between "decode a file" and "put bodies in a
     * drawing", and the half this project owns.
     *
     * Separate from [import] because it is the interesting one, exactly as `Jt.scene` is separate from
     * `Jt.write` on the way out: a test can hand over a scene with any unit, any transform and any mesh
     * without having to produce bytes that state it — and the *unit-less* case genuinely cannot be produced
     * as bytes by this toolchain at all, because the library's writer refuses to write a file that declares
     * no unit (which is the same rule, on the other side of the seam).
     */
    fun importScene(
        doc: Document,
        scene: JtScene,
        fileName: String,
    ): ImportResult {
        val format = ImportFormat.JT
        val mmPerUnit =
            JtImport.millimetresPer(scene.units)
                ?: return refused(
                    format,
                    fileName,
                    "not imported — $fileName declares no measurement unit, so its numbers have no length; " +
                        "re-export it from the CAD system with units set (JT_PROP_MEASUREMENT_UNITS)",
                )
        val offered = JtImport.bodies(scene, mmPerUnit)
        val names = ArrayList<String>()
        val refusals = ArrayList<String>()
        val notes = ArrayList<String>(offered.notes)
        for (body in offered.bodies) {
            body.note?.let { notes.add(it) }
            val defect = watertightDefect(body.name, body.mesh)
            if (defect != null) {
                refusals.add("${body.name} — $defect")
                continue
            }
            names.add(place(doc, fileName, body))
        }
        if (names.isEmpty()) {
            val why = (refusals + notes).ifEmpty { listOf("it holds no triangle geometry") }
            return refused(format, fileName, "nothing imported from $fileName: " + why.joinToString("; "))
        }
        val what = "${names.size} bod${if (names.size == 1) "y" else "ies"}"
        val bad = if (refusals.isEmpty()) "" else " (${refusals.size} refused: ${refusals.joinToString("; ")})"
        val rest = if (notes.isEmpty()) "" else " (${notes.joinToString("; ")})"
        return ImportResult(
            format,
            fileName,
            names,
            refusals,
            notes,
            "Imported $what from $fileName$bad$rest",
        )
    }

    private fun refused(
        format: ImportFormat,
        fileName: String,
        why: String,
    ): ImportResult = ImportResult(format, fileName, emptyList(), emptyList(), emptyList(), why, why)

    /**
     * One body, as the steps a person could have made: the literal, the anchor point, the angle parameter,
     * the placement, the name and the material. Returns the name the body actually took.
     *
     * The placement is recorded through [Document.recordingTool] — i.e. as an ordinary `tool placesolid`
     * step — so the import needs no step kind of its own for it and a body placed by an import is
     * indistinguishable from one a user placed by clicking. That is the point of building the placement
     * generic over solids in the first place.
     */
    private fun place(
        doc: Document,
        fileName: String,
        body: JtImport.JtBody,
    ): String {
        val (at, angle, residual) = anchorOf(body.pose)
        val literal = doc.importBody(fileName, body.mesh, residual)
        val anchor = doc.freePoint(Quantity.mm(at.x), Quantity.mm(at.y))
        val turn = doc.newParameter("place-angle", Quantity.rad(angle))
        val picks = Picks(listOf(anchor), listOf(literal), Vec2(at.x, at.y), emptyList())
        val placed: Element =
            doc.recordingTool(Tools.PLACE_SOLID, picks, listOf(turn)) {
                doc.placeSolid(literal, anchor, turn.ref)
            } ?: return body.name
        // the naming authority (OP-18): the file's name for the body is a *decision about the drawing*, so
        // it goes through the same route a rename does and is uniquified the same way
        val took = doc.nameElement(placed, body.name) ?: body.name
        body.material?.let { doc.setMaterial(placed, it) }
        return took.ifEmpty { body.name }
    }

    /**
     * A rigid file pose split into the **plan-space anchor** it can be stated as — an in-plane point and a
     * turn about the plan's normal — and the **residual** it cannot.
     *
     * This is where "state restates as a value" is actually paid out, and where its limit is. The
     * placement's editable form is *(a sketch space, a point in it, an angle)*, which reaches three of a
     * pose's six degrees of freedom from any given plane; a file pose that is a plan placement — a shift in
     * XY and a turn about Z, which is what an assembly of parts laid out on a table looks like — therefore
     * lands **entirely** in those two live nodes, and the residual is the identity. A pose that tilts the
     * body or lifts it off the plane cannot: what does not fit stays with the literal as the body's own
     * as-received orientation, recorded verbatim in its step. The **in-plane shift is taken either way**, so
     * the point a user drags always sits under the body it moves, and both nodes still move it about.
     * Re-anchoring such a body to a **datum plane** is what tilts it — the placement's frame *is* the
     * plane's, so there is no second orientation concept and none is stored.
     *
     * (What would close the gap is a placement that also took a height above its plane and a fully general
     * anchor plane, which is the height-point work and the datum vocabulary meeting — recorded as the
     * future extension it is, not half-built here.)
     */
    fun anchorOf(pose: Xform3): Triple<Vec2, Double, Xform3> {
        val m = pose.m
        val flat =
            abs(m[2]) <= AXIS_TOL && abs(m[5]) <= AXIS_TOL &&
                abs(m[6]) <= AXIS_TOL && abs(m[7]) <= AXIS_TOL &&
                abs(m[8] - 1.0) <= AXIS_TOL && abs(m[11]) <= AXIS_TOL
        // Either way the **in-plane shift is the anchor point**, so the point a user drags always sits under
        // the body it moves; what differs is how much else the two live nodes can say.
        val at = Vec2(m[9], m[10])
        if (!flat) return Triple(at, 0.0, pose.withTranslation(Vec3(0.0, 0.0, m[11])))
        return Triple(at, atan2(m[3], m[0]), Xform3.IDENTITY)
    }

    /**
     * How far from an exact rotation about the plan's normal a pose may be and still count as one.
     *
     * The numbers come from a file written in single precision, so an exactly axis-aligned instance matrix
     * arrives a few units in the last place off — and this only decides *which of two exact readings* the
     * pose gets (live nodes, or a recorded residual), never how the body is placed: the residual is computed
     * from what is left, so the body lands in the same place either way.
     */
    private const val AXIS_TOL = 1e-6

    /**
     * Why [mesh] is not a closed solid, or null when it is — **the same gate every constructed solid
     * passes**, reached through its one production implementation ([ThreeMf.check], the twin of the test
     * suite's `assertManifold`): every directed edge used exactly once with its reverse used exactly once,
     * and a positive enclosed volume.
     *
     * Asked through a one-body scene because that is the shape that check takes; its message names the body
     * in printing terms, and the prefix is traded for import ones so a refusal reads as what it is.
     */
    fun watertightDefect(
        name: String,
        mesh: Mesh3,
    ): String? {
        val why = ThreeMf.check(ExportScene(name, listOf(ExportNode(name, mesh, Appearance.DEFAULT)))) ?: return null
        return "not a closed solid, so it is not imported: " + why.removePrefix("$name cannot be printed: ")
    }
}
