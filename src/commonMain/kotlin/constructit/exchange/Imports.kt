package constructit.exchange

import constructit.editor.Document
import constructit.editor.Element
import constructit.editor.Picks
import constructit.editor.Tools
import constructit.geom.Mesh3
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.geom.Watertight
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
    /**
     * The **wireframe runs** that became curves of the drawing, in file order, under the names they took
     * (OP-26, step 9).
     *
     * Listed apart from [bodies] because they are a different thing to reach for — a run is what a sweep
     * rides, a station stands on and a sketch can be traced from — and because saying *how many of each* is
     * exactly what a mixed file's result has to say. They are not refusals and not notes: until OP-26's last
     * step a wireframe part was skipped and named in [notes], and this field is what replaced that sentence.
     */
    val runs: List<String> = emptyList(),
    /** Bodies the drawing would not take, each with the reason. */
    val refusals: List<String>,
    /**
     * The bodies that came in as **open shells**, by name — surfaces that do not close.
     *
     * Not refusals: they display, they place, they measure and they export to GLB and JT. They are listed
     * separately because what they cannot do — print, and be a boolean operand — is worth knowing *before*
     * the tool that needs it says no.
     */
    val openShells: List<String> = emptyList(),
    /** Everything else worth reading: the library's own notes, poses baked, anything decided on the way in. */
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
 * **What an imported *curve* is: the same sentence one dimension down** (OP-26, step 9). A file's wireframe
 * parts — centrelines, sketches, section curves, of which JT files carry plenty — used to be skipped and
 * named here. They are bodies of the drawing now: one frozen `Path3` literal per polyline, with the identical
 * parametric placement, the identical naming, the identical hidden literal, and the identical limit (no
 * construction inputs). A part's runs **share one anchor point and one angle**, because they are one part.
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
 * - **Bytes that are not a JT file at all**, with whatever the reader says about them.
 *
 * **And what is *flagged* rather than refused: an open shell.** A body whose surface does not close used to
 * be refused here, by the same watertight gate every constructed solid passes. That is **reversed**, on the
 * user's design and in the user's own words: refusing it "is necessary if the goal is printing, but it is
 * useless when the goal is re-engineering an imported geometry — and too restrictive, if the goal is only
 * arranging and displaying". So the body imports, places, draws and measures like any other, and carries a
 * **flag derived from its own triangles** ([constructit.geom.Feature3.Imported.openShell]) that the
 * consumers who need an inside answer for themselves: the two **print** writers refuse the whole export and
 * name it, every **boolean** refuses it and names it, GLB and JT write it with a note. The flag is named in
 * this result, per body, because "it came in but it is a shell" is the one thing a user must not have to
 * discover from a later refusal.
 *
 * Nothing is repaired, and nothing is measured against a tolerance: what is not closed is said to be not
 * closed. **The kernel's own doctrine is untouched** — everything ConstructIt *constructs* is watertight by
 * construction (OP-9), and this is only about geometry that came from outside.
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
        val runNames = ArrayList<String>()
        val refusals = ArrayList<String>()
        val shells = ArrayList<String>()
        val literals = ArrayList<Element>()
        val notes = ArrayList<String>(offered.notes)
        for (body in offered.bodies) {
            body.note?.let { notes.add(it) }
            // an **open shell** is imported like any other body and flagged; the name it actually took is
            // what the message and the panel speak, so it is read back from the placement
            val (took, literal) = place(doc, fileName, body)
            names.add(took)
            literals.add(literal)
            if (Watertight.defect(body.mesh) != null) shells.add(took)
        }
        // **Wireframe parts, on the identical path** (OP-26, step 9) — a frozen literal per run and a
        // placement riding it, through the same anchor decomposition, the same naming authority and the same
        // hidden-literal rule. They used to be skipped and named here; nothing else about the import changed.
        for (wire in offered.wires) {
            wire.note?.let { notes.add(it) }
            val (took, made) = placeRuns(doc, fileName, wire)
            runNames.addAll(took)
            literals.addAll(made)
        }
        // **The raw literals are hidden, in one recorded step.** A literal is the file's content in the
        // file's own coordinates — the placement riding it is the body of the drawing — so it draws nothing
        // and is nobody's output. While its placement is visible the export seam already skips it as that
        // placement's material; hiding it says the same thing when the placement is *not* visible, which is
        // what makes "hide the body" actually remove the body (and, for an open shell, what makes "hide it to
        // export the rest" true). One step for the whole import, recorded like any other visibility decision
        // (OP-18), so it survives save and undo — and `Show` takes it back.
        if (literals.isNotEmpty()) doc.setElementsVisible(literals, false)
        if (names.isEmpty() && runNames.isEmpty()) {
            val why = (refusals + notes).ifEmpty { listOf("it holds no geometry at all") }
            return refused(format, fileName, "nothing imported from $fileName: " + why.joinToString("; "))
        }
        val bodyWord = "${names.size} bod${if (names.size == 1) "y" else "ies"}"
        val runWord = "${runNames.size} wireframe run${if (runNames.size == 1) "" else "s"}"
        val what =
            when {
                runNames.isEmpty() -> bodyWord
                names.isEmpty() -> runWord
                else -> "$bodyWord and $runWord"
            }
        // stated **first** after the count, because it changes what the body can be used for
        val open =
            if (shells.isEmpty()) {
                ""
            } else {
                " — ${shells.joinToString(", ")} ${if (shells.size == 1) "is an open shell" else "are open shells"}: " +
                    "display and arrangement only, excluded from 3MF/STL and from booleans"
            }
        val bad = if (refusals.isEmpty()) "" else " (${refusals.size} refused: ${refusals.joinToString("; ")})"
        val rest = if (notes.isEmpty()) "" else " (${notes.joinToString("; ")})"
        return ImportResult(
            format = format,
            fileName = fileName,
            bodies = names,
            runs = runNames,
            refusals = refusals,
            openShells = shells,
            notes = notes,
            message = "Imported $what from $fileName$open$bad$rest",
        )
    }

    private fun refused(
        format: ImportFormat,
        fileName: String,
        why: String,
    ): ImportResult =
        ImportResult(
            format = format,
            fileName = fileName,
            bodies = emptyList(),
            refusals = emptyList(),
            openShells = emptyList(),
            notes = emptyList(),
            message = why,
            refusal = why,
        )

    /**
     * One body, as the steps a person could have made: the literal, the anchor point, the angle parameter,
     * the placement, the name and the material. Returns the name the body actually took, and the literal
     * element the placement rides (which the caller hides — see [importScene]).
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
    ): Pair<String, Element> {
        val (at, angle, residual) = anchorOf(body.pose)
        val literal = doc.importBody(fileName, body.mesh, residual)
        val anchor = doc.freePoint(Quantity.mm(at.x), Quantity.mm(at.y))
        val turn = doc.newParameter("place-angle", Quantity.rad(angle))
        val picks = Picks(listOf(anchor), listOf(literal), Vec2(at.x, at.y), emptyList())
        val placed: Element =
            doc.recordingTool(Tools.PLACE_SOLID, picks, listOf(turn)) {
                doc.placeSolid(literal, anchor, turn.ref)
            } ?: return body.name to literal
        // the naming authority (OP-18): the file's name for the body is a *decision about the drawing*, so
        // it goes through the same route a rename does and is uniquified the same way
        val took = doc.nameElement(placed, body.name) ?: body.name
        body.material?.let { doc.setMaterial(placed, it) }
        return took.ifEmpty { body.name } to literal
    }

    /**
     * One wireframe part, as the steps a person could have made (OP-26, step 9): a literal per run, **one**
     * anchor point and **one** angle parameter for the part, a placement per run, and the part's name.
     * Returns the names the runs took and the literal elements the placements ride.
     *
     * Everything here is [place]'s, reached rather than copied: the same [anchorOf] decomposition of the
     * file's pose, the same *Place* tool recorded through [Document.recordingTool] (one dimension down —
     * `tool placecurve`), the same naming authority, the same hidden literals. Two things differ, and both are
     * facts about wireframes rather than decisions about imports:
     * - **A part is several runs, and they share one placement.** A `Path3` is one chain, so disjoint
     *   polylines cannot be one value — but they are one part, and one anchor node feeding every run's
     *   placement is what says so (OP-5: sharing a node *is* equality). Drag it and the whole wireframe moves.
     * - **No material is assigned.** A curve has no appearance to carry in this model (Tier 1 dresses solids),
     *   so a file's colour for a wireframe part is dropped — named here rather than silently ignored.
     */
    private fun placeRuns(
        doc: Document,
        fileName: String,
        wire: JtImport.JtWire,
    ): Pair<List<String>, List<Element>> {
        val (at, angle, residual) = anchorOf(wire.pose)
        val anchor = doc.freePoint(Quantity.mm(at.x), Quantity.mm(at.y))
        val turn = doc.newParameter("place-angle", Quantity.rad(angle))
        val took = ArrayList<String>()
        val literals = ArrayList<Element>()
        for (run in wire.runs) {
            val literal = doc.importCurve(fileName, run, residual) ?: continue
            literals.add(literal)
            val picks = Picks(listOf(anchor), listOf(literal), Vec2(at.x, at.y), emptyList())
            val placed: Element? = doc.recordingTool(Tools.PLACE_CURVE, picks, listOf(turn)) { doc.placeCurve(literal, anchor, turn.ref) }
            if (placed == null) {
                took.add(wire.name)
                continue
            }
            took.add(doc.nameElement(placed, wire.name)?.ifEmpty { wire.name } ?: wire.name)
        }
        return took to literals
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
     * Why [mesh] is an **open shell** — a surface that does not close, or does not close consistently — or
     * null when it is a closed solid.
     *
     * One question, one implementation ([constructit.geom.Watertight]), three consumers: this, the two print
     * writers, and the literal's own derived flag. Kept as a named entry point here because *the import* is
     * where a user meets the answer first, and because it is the pure function the flag is derived from —
     * asking it of a mesh and asking it of a body must never be two different questions.
     */
    fun openShellDefect(mesh: Mesh3): String? = Watertight.defect(mesh)
}
